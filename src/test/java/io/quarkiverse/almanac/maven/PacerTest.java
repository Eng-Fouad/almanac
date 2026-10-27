package io.quarkiverse.almanac.maven;

import io.quarkiverse.almanac.ScratchDir;
import io.quarkiverse.almanac.maven.Pacer.Pause;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PacerTest {

    private static final Pause TWO_TO_FIVE = Pause.parse("2-5");

    /// A clock that only moves when a request takes time or the pacer sleeps.
    private static final class FakeTime {
        final AtomicLong now = new AtomicLong(1_000_000);
        final List<Long> sleeps = new ArrayList<>();

        Pacer pacer(Pause pause, Path lockFile) {
            return new Pacer(pause, lockFile, now::get, millis -> {
                sleeps.add(millis);
                now.addAndGet(millis);
            }, new SplittableRandom(42));
        }

        /// A request that takes `millis` and answers `value`.
        Pacer.Request<String> takes(long millis, String value) {
            return turn -> {
                now.addAndGet(millis);
                return value;
            };
        }
    }

    /// The earliest start a lock file holds, read as the first version of this class reads it: up to 32 bytes,
    /// stripped, a long; 0 for anything else (which makes that version stop waiting).
    private static long readAsBefore(Path lockFile) throws IOException {
        try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.READ)) {
            ByteBuffer buffer = ByteBuffer.allocate(32);
            channel.read(buffer, 0);
            String text = new String(buffer.array(), 0, buffer.position(), StandardCharsets.US_ASCII).strip();
            try {
                return text.isEmpty() ? 0 : Long.parseLong(text);
            } catch (NumberFormatException e) {
                return 0;
            }
        }
    }

    @Test
    void parsesPausesInSeconds() {
        assertEquals(new Pause(Duration.ofSeconds(2), Duration.ofSeconds(5)), TWO_TO_FIVE);
        assertEquals(new Pause(Duration.ofSeconds(3), Duration.ofSeconds(3)), Pause.parse(" 3 "));
        assertEquals(new Pause(Duration.ofMillis(1500), Duration.ofMillis(4250)), Pause.parse("1.5-4.25"));
        assertTrue(Pause.parse("0").isNone());
        assertTrue(Pause.parse("0-0").isNone());
        assertFalse(Pause.parse("0-1").isNone());
        assertEquals(new Pause(Duration.ofSeconds(1), Duration.ofSeconds(3)), Pause.DEFAULT);
        for (String text : List.of("2-5", "3", "0", "1.5-4.25", "0.001")) {
            assertEquals(text, Pause.parse(text).toString());
        }
        for (String bad : List.of("5-2", "-1", "2-", "abc", "", "1.2345", "2s", "2 - 5")) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> Pause.parse(bad));
            assertTrue(error.getMessage().contains("pause") || error.getMessage().contains("2-5"), error.getMessage());
        }
    }

    @Test
    void noPauseNeverWaits() throws IOException {
        FakeTime time = new FakeTime();
        Pacer pacer = time.pacer(Pause.NONE, null);
        for (int i = 0; i < 5; i++) {
            assertEquals("ok", pacer.run(time.takes(100, "ok")));
        }
        assertEquals(List.of(), time.sleeps);
        assertEquals(Duration.ZERO, pacer.paused());
    }

    @Test
    void eachRequestStartsARandomPauseAfterThePreviousOneEnded() throws IOException {
        FakeTime time = new FakeTime();
        Pacer pacer = time.pacer(TWO_TO_FIVE, null);

        assertEquals("first", pacer.run(time.takes(700, "first")));
        assertEquals(List.of(), time.sleeps, "the first request starts at once");

        for (int i = 0; i < 40; i++) {
            pacer.run(time.takes(300, "next"));
        }
        assertEquals(40, time.sleeps.size());
        for (long sleep : time.sleeps) {
            assertTrue(sleep >= 2000 && sleep <= 5000, "pause " + sleep);
        }
        assertTrue(new HashSet<>(time.sleeps).size() > 10, "pauses vary: " + time.sleeps);
        assertEquals(time.sleeps.stream().mapToLong(Long::longValue).sum(), pacer.paused().toMillis());
    }

    @Test
    void timeAlreadySpentElsewhereCountsTowardsThePause() throws IOException {
        FakeTime time = new FakeTime();
        Pacer pacer = time.pacer(Pause.parse("3"), null);
        pacer.run(time.takes(10, "a"));
        time.now.addAndGet(1000);
        pacer.run(time.takes(10, "b"));
        time.now.addAndGet(60_000);
        pacer.run(time.takes(10, "c"));
        assertEquals(List.of(2000L), time.sleeps);
    }

    @Test
    void aFailedRequestIsFollowedByAPauseToo() {
        FakeTime time = new FakeTime();
        Pacer pacer = time.pacer(Pause.parse("4"), null);
        IOException failure = new IOException("connection reset");
        assertThrows(IOException.class, () -> pacer.run(turn -> {
            throw failure;
        }));
        assertThrows(IOException.class, () -> pacer.run(turn -> {
            throw failure;
        }));
        assertEquals(List.of(4000L), time.sleeps);
    }

    @Test
    void sharedPacersWaitForEachOtherThroughTheLockFile() throws Exception {
        try (ScratchDir dir = ScratchDir.create()) {
            Path lockFile = dir.resolve("cache").resolve(Pacer.LOCK_FILE);
            FakeTime time = new FakeTime();
            Pacer first = time.pacer(Pause.parse("4"), lockFile);
            Pacer second = time.pacer(Pause.parse("4"), lockFile);

            first.run(time.takes(500, "a"));
            long nextStart = Long.parseLong(Files.readString(lockFile).strip());
            assertEquals(time.now.get() + 4000, nextStart);

            time.now.addAndGet(1500);
            second.run(time.takes(500, "b"));
            assertEquals(List.of(2500L), time.sleeps, "the second pacer waits for the first one's pause");
            first.run(time.takes(500, "c"));
            assertEquals(List.of(2500L, 4000L), time.sleeps, "and the first one for the second's");
        }
    }

    @Test
    void aStartReadFromTheLockFileIsWaitedForAtMostTenMinutes() throws Exception {
        try (ScratchDir dir = ScratchDir.create()) {
            Path lockFile = dir.resolve(Pacer.LOCK_FILE);
            FakeTime time = new FakeTime();
            Pacer pacer = time.pacer(Pause.parse("2-5"), lockFile);

            Files.writeString(lockFile, "not a time", StandardCharsets.US_ASCII);
            pacer.run(time.takes(10, "a"));
            assertEquals(List.of(), time.sleeps, "unreadable: no wait");

            // a process with longer pauses, or a delay after a 429, is waited for beyond this pacer's own pauses
            Files.writeString(lockFile, (time.now.get() + 90_000) + "\n", StandardCharsets.US_ASCII);
            pacer.run(time.takes(10, "b"));
            assertEquals(List.of(90_000L), time.sleeps);

            Files.writeString(lockFile, "999999999999999\n", StandardCharsets.US_ASCII);
            pacer.run(time.takes(10, "c"));
            assertEquals(List.of(90_000L, Pacer.MAX_WAIT.toMillis()), time.sleeps, "far in the future: 10 minutes");
        }
    }

    @Test
    void aDelayHoldsTheNextRequestOfEveryPacerOnTheLockFile() throws Exception {
        try (ScratchDir dir = ScratchDir.create()) {
            Path lockFile = dir.resolve("cache").resolve(Pacer.LOCK_FILE);
            FakeTime time = new FakeTime();
            Pacer first = time.pacer(Pause.parse("2"), lockFile);
            Pacer second = time.pacer(Pause.parse("2"), lockFile);

            first.run(turn -> {
                time.now.addAndGet(100);
                turn.delayNext(Duration.ofSeconds(90));
                return null;
            });
            assertEquals(time.now.get() + 90_000, readAsBefore(lockFile), "the delay is the next start");
            second.run(time.takes(100, "b"));
            assertEquals(List.of(90_000L), time.sleeps);

            // a short delay leaves the pause (the third wait); a long one stops at 10 minutes
            second.run(turn -> {
                turn.delayNext(Duration.ofMillis(10));
                return null;
            });
            first.run(turn -> {
                turn.delayNext(Duration.ofDays(1));
                return null;
            });
            first.run(time.takes(100, "d"));
            assertEquals(List.of(90_000L, 2000L, 2000L, Pacer.MAX_WAIT.toMillis()), time.sleeps);
        }
    }

    @Test
    void aDelayHoldsAPacerWithoutPausesToo() throws IOException {
        FakeTime time = new FakeTime();
        Pacer pacer = time.pacer(Pause.NONE, null);
        pacer.run(turn -> {
            turn.delayNext(Duration.ofSeconds(5));
            return null;
        });
        pacer.run(time.takes(10, "b"));
        pacer.run(time.takes(10, "c"));
        assertEquals(List.of(5000L), time.sleeps);
        assertEquals(Optional.empty(), pacer.run(turn -> turn.stopAll(Instant.now(), "no file, no stop marker")));
    }

    @Test
    void aStopSendsNothingWaitsForNothingAndKeepsTheNextStart() throws Exception {
        try (ScratchDir dir = ScratchDir.create()) {
            Path lockFile = dir.resolve("cache").resolve(Pacer.LOCK_FILE);
            Path stopFile = lockFile.resolveSibling(Pacer.STOP_FILE);
            FakeTime time = new FakeTime();
            Pacer first = time.pacer(Pause.parse("3"), lockFile);
            Pacer second = time.pacer(Pause.parse("3"), lockFile);
            Instant until = Instant.ofEpochMilli(time.now.get() + 3_600_000);

            Optional<Pacer.Stop> stop = first.run(turn -> turn.stopAll(until, "HTTP 403 for\nhttp://x/a.jar"));
            assertEquals(Optional.of(new Pacer.Stop(until, "HTTP 403 for http://x/a.jar", stopFile.toRealPath())),
                    stop);
            List<String> marker = Files.readAllLines(stopFile);
            assertEquals(List.of(String.valueOf(until.toEpochMilli()), "HTTP 403 for http://x/a.jar"),
                    marker.subList(0, 2));
            long nextStart = readAsBefore(lockFile);

            AtomicInteger sent = new AtomicInteger();
            for (Pacer pacer : List.of(first, second)) {
                Http.StoppedException refused = assertThrows(Http.StoppedException.class,
                        () -> pacer.run(turn -> sent.incrementAndGet()));
                assertTrue(refused.getMessage().contains("HTTP 403 for http://x/a.jar"), refused.getMessage());
                assertTrue(refused.getMessage().contains(stopFile.toRealPath() + ": delete that file"),
                        refused.getMessage());
            }
            assertEquals(0, sent.get());
            assertEquals(List.of(), time.sleeps, "no pause before a refusal");
            assertEquals(nextStart, readAsBefore(lockFile), "a refusal sends nothing, so it sets no next start");

            // a shorter stop keeps the longer one; once it is over, requests go again
            assertEquals(until, second.run(ignored -> {
            }, turn -> turn.stopAll(until.minusSeconds(60), "later")).orElseThrow().until());
            assertEquals(String.valueOf(until.toEpochMilli()), Files.readAllLines(stopFile).getFirst());
            time.now.set(until.toEpochMilli());
            int afterTheStop = second.run(turn -> sent.incrementAndGet());
            assertEquals(1, afterTheStop);

            // deleting the marker ends a stop at once
            first.run(turn -> turn.stopAll(Instant.ofEpochMilli(time.now.get() + 60_000), "again"));
            assertThrows(Http.StoppedException.class, () -> second.run(turn -> sent.incrementAndGet()));
            Files.delete(stopFile);
            int afterTheDelete = second.run(turn -> sent.incrementAndGet());
            assertEquals(2, afterTheDelete);
        }
    }

    @Test
    void aCheckThatRefusesSendsNothingAndKeepsTheNextStart() throws Exception {
        try (ScratchDir dir = ScratchDir.create()) {
            Path lockFile = dir.resolve(Pacer.LOCK_FILE);
            FakeTime time = new FakeTime();
            Pacer pacer = time.pacer(Pause.parse("4"), lockFile);
            pacer.run(time.takes(10, "a"));
            long nextStart = readAsBefore(lockFile);
            AtomicInteger sent = new AtomicInteger();
            for (int i = 0; i < 3; i++) {
                assertThrows(Http.StoppedException.class, () -> pacer.run(stop -> {
                    throw new Http.StoppedException("stopped here");
                }, turn -> sent.incrementAndGet()));
            }
            assertEquals(0, sent.get());
            assertEquals(List.of(), time.sleeps, "no pause before a refusal");
            assertEquals(nextStart, readAsBefore(lockFile));
            pacer.run(time.takes(10, "b"));
            assertEquals(List.of(4000L), time.sleeps, "the next request waits for the last one sent");
        }
    }

    @Test
    void theLockFileStaysOneLineOfEpochMillisecondsAsEarlierVersionsWroteIt() throws Exception {
        try (ScratchDir dir = ScratchDir.create()) {
            Path lockFile = dir.resolve("cache").resolve(Pacer.LOCK_FILE);
            FakeTime time = new FakeTime();
            Pacer pacer = time.pacer(Pause.parse("2-5"), lockFile);

            // written by an earlier version: one line, read and waited for
            Files.createDirectories(lockFile.getParent());
            Files.writeString(lockFile, (time.now.get() + 3000) + "\n", StandardCharsets.US_ASCII);
            pacer.run(time.takes(10, "a"));
            assertEquals(List.of(3000L), time.sleeps);

            // whatever a request does in its turn, the file stays one line that an earlier version reads
            pacer.run(turn -> {
                turn.delayNext(Duration.ofSeconds(42));
                turn.stopAll(Instant.ofEpochMilli(time.now.get() + 1000), "a short stop");
                return null;
            });
            String text = Files.readString(lockFile, StandardCharsets.US_ASCII);
            assertTrue(text.matches("\\d{1,19}\n"), "'" + text + "'");
            assertEquals(time.now.get() + 42_000, readAsBefore(lockFile));
            assertTrue(Files.exists(lockFile.resolveSibling(Pacer.STOP_FILE)), "the stop goes into its own file");
            for (int i = 0; i < 20; i++) {
                time.now.addAndGet(60_000);
                pacer.run(time.takes(10, "next"));
                text = Files.readString(lockFile, StandardCharsets.US_ASCII);
                assertTrue(text.matches("\\d{1,19}\n"), "'" + text + "'");
                long next = readAsBefore(lockFile);
                assertTrue(next >= time.now.get() + 2000 && next <= time.now.get() + 5000, "next start " + next);
            }
        }
    }

    @Test
    void aLockFileThatCannotBeUsedFailsAtOnce() throws Exception {
        try (ScratchDir dir = ScratchDir.create()) {
            // a folder where the lock file should be: neither the file nor a lock can be had
            Path lockFile = dir.resolve(Pacer.LOCK_FILE);
            Files.createDirectories(lockFile);
            FakeTime time = new FakeTime();
            Pacer pacer = time.pacer(Pause.parse("2-5"), lockFile);
            AtomicInteger sent = new AtomicInteger();
            Pacer.LockFileException failure = assertThrows(Pacer.LockFileException.class,
                    () -> pacer.run(turn -> sent.incrementAndGet()));
            assertTrue(failure.getMessage().contains(Pacer.LOCK_FILE), failure.getMessage());
            assertEquals(0, sent.get());

            // a stop marker that is not one fails at once too, and says how to clear it
            Path other = dir.resolve("other").resolve(Pacer.LOCK_FILE);
            Files.createDirectories(other.getParent());
            Files.writeString(other.resolveSibling(Pacer.STOP_FILE), "soon\n");
            Pacer.LockFileException damaged = assertThrows(Pacer.LockFileException.class,
                    () -> time.pacer(Pause.parse("2-5"), other).run(turn -> sent.incrementAndGet()));
            assertTrue(damaged.getMessage().contains("delete it"), damaged.getMessage());
            assertEquals(0, sent.get());
            assertEquals(List.of(), time.sleeps);
        }
    }

    @Test
    void anInterruptedPauseFailsTheRequestWithoutSendingIt() throws IOException {
        AtomicLong sent = new AtomicLong();
        Pacer pacer = new Pacer(Pause.parse("1"), null, () -> 0, millis -> {
            throw new InterruptedException();
        }, new SplittableRandom(1));
        long first = pacer.run(turn -> sent.incrementAndGet());
        assertEquals(1, first);
        try {
            assertThrows(InterruptedIOException.class, () -> pacer.run(turn -> sent.incrementAndGet()));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        assertEquals(1, sent.get(), "only the first request went out");
    }

    @Test
    void anInterruptedRequestStillSetsTheNextStart() throws Exception {
        try (ScratchDir dir = ScratchDir.create()) {
            Path lockFile = dir.resolve(Pacer.LOCK_FILE);
            FakeTime time = new FakeTime();
            Pacer pacer = time.pacer(Pause.parse("4"), lockFile);
            try {
                assertEquals("sent", pacer.run(turn -> {
                    time.now.addAndGet(250);
                    // as when the thread is interrupted while it waits for the answer
                    Thread.currentThread().interrupt();
                    return "sent";
                }));
                assertTrue(Thread.currentThread().isInterrupted(), "the interrupt stays set");
            } finally {
                Thread.interrupted();
            }
            assertEquals(time.now.get() + 4000, readAsBefore(lockFile), "the request's pause is in the lock file");
        }
    }

    @Test
    void interruptsWhileTheNextStartIsWrittenNeitherLoseItNorFailTheRequest() throws Exception {
        try (ScratchDir dir = ScratchDir.create()) {
            Path lockFile = dir.resolve(Pacer.LOCK_FILE);
            FakeTime time = new FakeTime();
            Pacer pacer = time.pacer(Pause.parse("4"), lockFile);
            Thread caller = Thread.currentThread();
            for (int i = 0; i < 20; i++) {
                AtomicBoolean answered = new AtomicBoolean();
                AtomicReference<Thread> interrupter = new AtomicReference<>();
                try {
                    // from the answer on, interrupts keep coming while the pacer writes the next start
                    assertEquals("sent", pacer.run(turn -> {
                        interrupter.set(Thread.ofPlatform().start(() -> {
                            while (!answered.get()) {
                                caller.interrupt();
                            }
                        }));
                        while (!Thread.currentThread().isInterrupted()) {
                            Thread.onSpinWait();
                        }
                        return "sent";
                    }));
                } finally {
                    answered.set(true);
                    while (interrupter.get() != null && interrupter.get().isAlive()) {
                        Thread.onSpinWait();
                    }
                    Thread.interrupted();
                }
                assertEquals(time.now.get() + 4000, readAsBefore(lockFile), "request " + i);
                time.now.addAndGet(4000);
            }
        }
    }

    @Test
    void threadsTakeTurnsOneRequestAtATime() throws Exception {
        Pacer pacer = Pacer.local(Pause.parse("0.1-0.12"));
        List<long[]> spans = spans(List.of(pacer), 4, 3);
        assertEquals(12, spans.size());
        assertApart(spans, 60);
    }

    @Test
    void twoPacersOnOneLockFileTakeTurnsAcrossManyThreads() throws Exception {
        try (ScratchDir dir = ScratchDir.create()) {
            Path lockFile = dir.resolve("cache").resolve(Pacer.LOCK_FILE);
            Files.createDirectories(lockFile.getParent());
            Files.createFile(lockFile);
            List<Pacer> pacers = List.of(Pacer.shared(Pause.parse("0.08-0.1"), lockFile),
                    Pacer.shared(Pause.parse("0.08-0.1"), otherName(dir, lockFile)));
            List<long[]> spans = spans(pacers, 6, 2);
            assertEquals(24, spans.size());
            assertApart(spans, 40);
        }
    }

    /// Another name of the lock file, under which this process still takes one turn at a time (`FileChannel` refuses
    /// two locks of one file within a process): through a linked folder where the platform allows links, else in
    /// another case where the file system ignores case (which `Path` itself may ignore), else the same name.
    private static Path otherName(ScratchDir dir, Path lockFile) throws IOException {
        Path linked = dir.resolve("linked-cache");
        try {
            Files.createSymbolicLink(linked, lockFile.getParent());
            return linked.resolve(lockFile.getFileName());
        } catch (IOException | UnsupportedOperationException e) {
            // Windows creates links only with a privilege
        }
        Path upperCase = dir.resolve("CACHE").resolve(Pacer.LOCK_FILE.toUpperCase(Locale.ROOT));
        return Files.exists(upperCase) && Files.isSameFile(upperCase, lockFile) ? upperCase : lockFile;
    }

    /// Runs `requests` requests of 5 ms on each of `threads` virtual threads per pacer.
    ///
    /// @return the requests as `[start, end]` in nanoseconds, by start
    private static List<long[]> spans(List<Pacer> pacers, int threads, int requests) throws Exception {
        List<long[]> spans = new ArrayList<>();
        List<Thread> started = new ArrayList<>();
        List<Throwable> failures = new ArrayList<>();
        for (Pacer pacer : pacers) {
            for (int t = 0; t < threads; t++) {
                started.add(Thread.ofVirtual().start(() -> {
                    for (int i = 0; i < requests; i++) {
                        try {
                            pacer.run(turn -> {
                                long start = System.nanoTime();
                                try {
                                    Thread.sleep(5);
                                } catch (InterruptedException e) {
                                    throw new InterruptedIOException();
                                }
                                synchronized (spans) {
                                    spans.add(new long[] {start, System.nanoTime()});
                                }
                                return null;
                            });
                        } catch (IOException | RuntimeException e) {
                            synchronized (failures) {
                                failures.add(e);
                            }
                        }
                    }
                }));
            }
        }
        for (Thread thread : started) {
            thread.join();
        }
        assertEquals(List.of(), failures);
        spans.sort((a, b) -> Long.compare(a[0], b[0]));
        return spans;
    }

    /// No two requests overlap, and each starts at least `minGapMillis` after the previous one ended: the shortest
    /// pause, give or take the resolution of the wall clock the pacer uses (up to 15.6 ms on Windows).
    private static void assertApart(List<long[]> spans, long minGapMillis) {
        for (int i = 1; i < spans.size(); i++) {
            long gapMillis = (spans.get(i)[0] - spans.get(i - 1)[1]) / 1_000_000;
            assertTrue(gapMillis >= minGapMillis, "gap " + gapMillis + " ms between requests " + (i - 1) + " and " + i);
        }
    }
}
