package io.quarkiverse.almanac.maven;

import io.quarkiverse.almanac.ScratchDir;
import io.quarkiverse.almanac.maven.Pacer.Pause;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicLong;

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
            return () -> {
                now.addAndGet(millis);
                return value;
            };
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
        assertEquals(Pause.DEFAULT, TWO_TO_FIVE);
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
        assertThrows(IOException.class, () -> pacer.run(() -> {
            throw failure;
        }));
        assertThrows(IOException.class, () -> pacer.run(() -> {
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
    void aDamagedLockFileNeverStallsLongerThanOnePause() throws Exception {
        try (ScratchDir dir = ScratchDir.create()) {
            Path lockFile = dir.resolve(Pacer.LOCK_FILE);
            FakeTime time = new FakeTime();
            Pacer pacer = time.pacer(Pause.parse("2-5"), lockFile);

            Files.writeString(lockFile, "not a time", StandardCharsets.US_ASCII);
            pacer.run(time.takes(10, "a"));
            assertEquals(List.of(), time.sleeps, "unreadable: no wait");

            Files.writeString(lockFile, "999999999999999\n", StandardCharsets.US_ASCII);
            pacer.run(time.takes(10, "b"));
            assertEquals(List.of(5000L), time.sleeps, "far in the future: at most the longest pause");
        }
    }

    @Test
    void anInterruptedPauseFailsTheRequestWithoutSendingIt() throws IOException {
        AtomicLong sent = new AtomicLong();
        Pacer pacer = new Pacer(Pause.parse("1"), null, () -> 0, millis -> {
            throw new InterruptedException();
        }, new SplittableRandom(1));
        assertEquals(1L, pacer.run(sent::incrementAndGet));
        try {
            assertThrows(InterruptedIOException.class, () -> pacer.run(sent::incrementAndGet));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        assertEquals(1, sent.get(), "only the first request went out");
    }

    @Test
    void threadsTakeTurnsOneRequestAtATime() throws Exception {
        Pacer pacer = Pacer.local(Pause.parse("0.05-0.08"));
        List<long[]> spans = new ArrayList<>();
        List<Thread> threads = new ArrayList<>();
        for (int t = 0; t < 4; t++) {
            threads.add(Thread.ofVirtual().start(() -> {
                for (int i = 0; i < 3; i++) {
                    try {
                        pacer.run(() -> {
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
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                }
            }));
        }
        for (Thread thread : threads) {
            thread.join();
        }
        spans.sort((a, b) -> Long.compare(a[0], b[0]));
        assertEquals(12, spans.size());
        for (int i = 1; i < spans.size(); i++) {
            long gapMillis = (spans.get(i)[0] - spans.get(i - 1)[1]) / 1_000_000;
            // 50 ms at least, give or take the resolution of the wall clock the pacer uses (up to 15.6 ms on Windows)
            assertTrue(gapMillis >= 34, "gap " + gapMillis + " ms between requests " + (i - 1) + " and " + i);
        }
    }
}
