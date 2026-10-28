package io.quarkiverse.almanac.maven;

import io.quarkiverse.almanac.util.AtomicFiles;
import io.quarkiverse.almanac.util.Log;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;
import java.util.random.RandomGenerator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// Spaces out the requests to a repository: one request at a time, each starting a random pause after
/// the previous one ended, so that a long extraction never sends a burst. A shared pacer keeps the earliest start of
/// the next request in a lock file, so the pauses also hold between processes that use the same file: every command
/// working on one `work/cache` waits for its turn.
///
/// In its turn a request can push the next start further back ([Turn#delayNext]: the repository asked for fewer
/// requests, or could not be reached) and stop the requests of every pacer on the lock file for a while
/// ([Turn#stopAll], kept in the [#STOP_FILE] and checked at the start of every turn). The lock file itself holds
/// nothing but the earliest start, one line of epoch milliseconds, which earlier versions of this class read as well.
public final class Pacer {

    /// The lock file in a cache folder (see [#forCache]): the earliest start of the next request, in epoch
    /// milliseconds, as one line of text.
    public static final String LOCK_FILE = ".pacing";

    /// The stop marker next to the lock file: the end of the stop in epoch milliseconds on its first line, the reason
    /// on the second. Until then every request of every pacer on the lock file fails at once; deleting the file ends
    /// the stop.
    public static final String STOP_FILE = ".pacing-stop";

    /// The longest wait for a start: one read from the lock file (another process's pause or delay, a damaged file, a
    /// clock that jumped) or a [delay][Turn#delayNext] a request asked for.
    public static final Duration MAX_WAIT = Duration.ofMinutes(10);

    /// A random pause between `min` and `max`, both included; [#NONE] turns pacing off.
    public record Pause(Duration min, Duration max) {

        public static final Pause NONE = new Pause(Duration.ZERO, Duration.ZERO);
        /// 0.5 to 1.5 seconds.
        public static final Pause DEFAULT = new Pause(Duration.ofMillis(500), Duration.ofMillis(1500));

        private static final Pattern SECONDS =
                Pattern.compile("(\\d{1,6}(?:\\.\\d{1,3})?)(?:-(\\d{1,6}(?:\\.\\d{1,3})?))?");

        public Pause {
            if (min.isNegative() || max.compareTo(min) < 0) {
                throw new IllegalArgumentException("a pause needs 0 <= min <= max, not " + min + " and " + max);
            }
        }

        /// Seconds: a range such as `2-5`, a fixed `3`, or `0` for none; up to three decimals, as in `1.5-4`.
        public static Pause parse(String text) {
            Matcher m = SECONDS.matcher(text.strip());
            if (!m.matches()) {
                throw new IllegalArgumentException("'" + text + "' is not a pause in seconds such as 2-5, 3 or 0");
            }
            Duration min = seconds(m.group(1));
            Duration max = m.group(2) == null ? min : seconds(m.group(2));
            if (max.compareTo(min) < 0) {
                throw new IllegalArgumentException("'" + text + "': the shorter pause comes first, as in 2-5");
            }
            return new Pause(min, max);
        }

        public boolean isNone() {
            return max.isZero();
        }

        /// The text [#parse] reads, e.g. `2-5` or `1.5`.
        @Override
        public String toString() {
            return min.equals(max) ? text(min) : text(min) + "-" + text(max);
        }

        private static Duration seconds(String text) {
            return Duration.ofMillis(new BigDecimal(text).movePointRight(3).longValueExact());
        }

        private static String text(Duration duration) {
            return BigDecimal.valueOf(duration.toMillis(), 3).stripTrailingZeros().toPlainString();
        }
    }

    /// A request the pacer starts when its turn comes.
    @FunctionalInterface
    public interface Request<T> {
        T send(Turn turn) throws IOException;
    }

    /// What a request can do in its turn.
    public interface Turn {

        /// Lets the next request, of any thread and of any process on the lock file, start no earlier than `delay`
        /// after this one ends (at most [#MAX_WAIT]); a longer random pause still applies.
        void delayNext(Duration delay);

        /// Stops the requests of every pacer on the lock file until `until`, through the [#STOP_FILE]; a stop that
        /// lasts longer stays.
        ///
        /// @return the stop now in force; empty for a pacer without lock file, or when the marker cannot be written
        Optional<Stop> stopAll(Instant until, String reason);
    }

    /// Decides, in the turn and before the pause, whether a request goes out.
    @FunctionalInterface
    public interface Check {

        /// Throws to send nothing: then nobody waits, and the next request may start as early as before.
        ///
        /// @param stop the stop of every pacer on the lock file, null when none is in force
        void check(Stop stop) throws IOException;
    }

    /// A stop of every pacer on a lock file, as its [#STOP_FILE] records it.
    ///
    /// @param until  when the stop ends
    /// @param reason why the requests stopped
    /// @param file   the stop marker; deleting it ends the stop at once
    public record Stop(Instant until, String reason, Path file) {

        /// For messages: until when, why, and how to end the stop earlier.
        public String describe() {
            return "a stop of every command on this cache until " + until + " (" + reason + "), recorded in " + file
                    + ": delete that file to allow requests earlier";
        }
    }

    /// The lock file or the stop marker cannot be used: the pacer cannot keep its pauses, so it sends nothing.
    public static final class LockFileException extends IOException {

        private static final long serialVersionUID = 1L;

        LockFileException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /// Waits; replaced in tests.
    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    /// Where a pacer takes its turns: the turn lock and, for a shared pacer, the real paths of its files.
    private record Place(ReentrantLock turn, Path lockFile, Path stopFile) {
    }

    /// One turn lock per lock file, keyed by its real path, so that two threads of this process never lock the file at
    /// once (which `FileChannel` refuses within a process), however the file is named (another case, a junction).
    private static final ConcurrentMap<Path, ReentrantLock> SHARED_TURNS = new ConcurrentHashMap<>();

    private final Pause pause;
    /// The lock file as given, null for a pacer of this process only.
    private final Path lockFile;
    private final LongSupplier clock;
    private final Sleeper sleeper;
    private final RandomGenerator random;
    private final AtomicBoolean announced = new AtomicBoolean();
    private final AtomicLong pausedMillis = new AtomicLong();
    /// Earliest start of this process's next request, in epoch milliseconds.
    private final AtomicLong nextStart = new AtomicLong();
    /// Known from the first request on, for a shared pacer.
    private volatile Place place;

    /// @param lockFile null for a pacer of this process only
    /// @param clock    epoch milliseconds
    Pacer(Pause pause, Path lockFile, LongSupplier clock, Sleeper sleeper, RandomGenerator random) {
        this.pause = pause;
        this.lockFile = lockFile == null ? null : lockFile.toAbsolutePath().normalize();
        this.clock = clock;
        this.sleeper = sleeper;
        this.random = random;
        this.place = lockFile == null ? new Place(new ReentrantLock(true), null, null) : null;
    }

    /// No pauses: requests start as soon as an HTTP slot is free, unless a request asked for a delay.
    public static Pacer none() {
        return local(Pause.NONE);
    }

    /// Pauses between the requests of this process.
    public static Pacer local(Pause pause) {
        return new Pacer(pause, null, System::currentTimeMillis, Thread::sleep, RandomGenerator.getDefault());
    }

    /// Pauses between the requests of every process whose pacer uses `lockFile`; the first request creates the file
    /// and its folder.
    public static Pacer shared(Pause pause, Path lockFile) {
        return new Pacer(pause, lockFile, System::currentTimeMillis, Thread::sleep, RandomGenerator.getDefault());
    }

    /// The pacer of the commands that share a cache folder: [#shared] on its [#LOCK_FILE], or [#none] for a pause
    /// of zero.
    public static Pacer forCache(Pause pause, Path cacheDir) {
        return pause.isNone() ? none() : shared(pause, cacheDir.resolve(LOCK_FILE));
    }

    public Pause pause() {
        return pause;
    }

    /// Time spent pausing so far.
    public Duration paused() {
        return Duration.ofMillis(pausedMillis.get());
    }

    /// Sends `request` when its turn comes (see [#run(Check, Request)]); fails with [Http.StoppedException] while a
    /// stop of every pacer on the lock file is in force.
    public <T> T run(Request<T> request) throws IOException {
        return run(Pacer::refuseDuringStop, request);
    }

    /// Sends `request` when its turn comes: once the previous request (of this process or, for a shared pacer, of any
    /// process using the lock file) has ended and a random pause, or the longer delay that request asked for, has
    /// passed. The pause also follows a request that failed. `ready` decides first, before the pause, whether the
    /// request goes out at all.
    ///
    /// @throws LockFileException when the lock file or the stop marker cannot be used: nothing was sent, unless only
    ///                           the next start could not be written afterwards
    public <T> T run(Check ready, Request<T> request) throws IOException {
        if (pause.isNone()) {
            return unpaced(ready, request);
        }
        if (announced.compareAndSet(false, true)) {
            Log.info("HTTP: one request at a time, %s s apart at random (--pause)", pause);
        }
        Place current = place();
        try {
            current.turn().lockInterruptibly();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("interrupted while waiting for the turn to send a request");
        }
        try {
            if (current.lockFile() == null) {
                return paced(ready, request, current, null);
            }
            try (RandomAccessFile file = open(current.lockFile())) {
                lock(file.getChannel(), current.lockFile()); // released when the file closes
                return paced(ready, request, current, file);
            }
        } finally {
            current.turn().unlock();
        }
    }

    /// Without pauses: no turns either, only the delays that requests asked for.
    private <T> T unpaced(Check ready, Request<T> request) throws IOException {
        ready.check(null);
        waitUntil(nextStart.get());
        TurnState turn = new TurnState(null);
        try {
            return request.send(turn);
        } finally {
            if (turn.delayMillis > 0) {
                nextStart.accumulateAndGet(clock.getAsLong() + turn.delayMillis, Math::max);
            }
        }
    }

    /// In the turn: checks, waits for the earliest start, sends, and sets the next earliest start, also in the lock
    /// file when there is one.
    private <T> T paced(Check ready, Request<T> request, Place current, RandomAccessFile file) throws IOException {
        ready.check(file == null ? null : readStop(current.stopFile()).orElse(null));
        waitUntil(file == null ? nextStart.get()
                : Math.max(nextStart.get(), readNextStart(file.getChannel(), current)));
        TurnState turn = new TurnState(current);
        T result;
        try {
            result = request.send(turn);
        } catch (Throwable failure) {
            try {
                setNextStart(file, current, turn);
            } catch (LockFileException e) {
                e.addSuppressed(failure);
                throw e;
            }
            throw failure;
        }
        setNextStart(file, current, turn);
        return result;
    }

    /// Sleeps until `notBefore`, but never longer than [#MAX_WAIT]: a damaged lock file or a clock that jumped cannot
    /// stall the requests for long.
    private void waitUntil(long notBefore) throws InterruptedIOException {
        long wait = Math.min(notBefore - clock.getAsLong(), MAX_WAIT.toMillis());
        if (wait <= 0) {
            return;
        }
        try {
            sleeper.sleep(wait);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("interrupted while pausing between requests");
        }
        pausedMillis.addAndGet(wait);
    }

    /// Sets the earliest start of the next request: a random pause after now, or the longer delay the request asked
    /// for. The lock file gets it even when this thread is interrupted before or during the write: it goes through
    /// the file, not its channel, whose operations an interrupt ends by closing the channel (and releasing the lock).
    /// The new start replaces the old one before the file is cut to its length, so the file is never empty.
    private void setNextStart(RandomAccessFile file, Place current, TurnState turn) throws LockFileException {
        long next = clock.getAsLong() + Math.max(randomPauseMillis(), turn.delayMillis);
        nextStart.set(next);
        if (file == null) {
            return;
        }
        byte[] line = (next + "\n").getBytes(StandardCharsets.US_ASCII);
        try {
            file.seek(0);
            file.write(line);
            file.setLength(line.length);
        } catch (IOException e) {
            throw new LockFileException("cannot write the next start to " + current.lockFile() + ": " + e, e);
        }
    }

    private long randomPauseMillis() {
        long min = pause.min().toMillis();
        long max = pause.max().toMillis();
        return min == max ? min : random.nextLong(min, max + 1);
    }

    /// The turn lock and the files; for a shared pacer the first call creates the lock file and its folder, so that
    /// every name of the file (another case, a junction) leads to the same turn lock.
    private Place place() throws LockFileException {
        Place current = place;
        if (current != null) {
            return current;
        }
        Path real;
        try {
            Files.createDirectories(lockFile.getParent());
            if (!Files.exists(lockFile)) {
                try {
                    Files.createFile(lockFile);
                } catch (FileAlreadyExistsException e) {
                    // made by another thread or process meanwhile
                }
            }
            real = lockFile.toRealPath();
        } catch (IOException e) {
            throw new LockFileException("cannot create " + lockFile + ": " + e, e);
        }
        current = new Place(SHARED_TURNS.computeIfAbsent(real, file -> new ReentrantLock(true)), real,
                real.resolveSibling(STOP_FILE));
        place = current;
        return current;
    }

    /// Opens the lock file, creating it when missing.
    private static RandomAccessFile open(Path file) throws IOException {
        try {
            return new RandomAccessFile(file.toFile(), "rw");
        } catch (IOException e) {
            throw failure("cannot open " + file, e);
        }
    }

    private static void lock(FileChannel channel, Path file) throws IOException {
        try {
            channel.lock();
        } catch (OverlappingFileLockException e) {
            throw new LockFileException(file + " is locked by this process already, under another name", e);
        } catch (IOException e) {
            throw failure("cannot lock " + file, e);
        }
    }

    /// The earliest start the lock file holds (epoch milliseconds as text); 0 when it is empty or damaged.
    private static long readNextStart(FileChannel channel, Place current) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(32);
        try {
            channel.read(buffer, 0);
        } catch (IOException e) {
            throw failure("cannot read " + current.lockFile(), e);
        }
        String text = new String(buffer.array(), 0, buffer.position(), StandardCharsets.US_ASCII).strip();
        try {
            return text.isEmpty() ? 0 : Long.parseLong(text);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /// The stop in force for every pacer on the lock file, from the stop marker.
    private Optional<Stop> readStop(Path stopFile) throws IOException {
        List<String> lines;
        try {
            lines = Files.readAllLines(stopFile, StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            return Optional.empty();
        } catch (IOException e) {
            throw failure("cannot read " + stopFile, e);
        }
        long until;
        try {
            until = Long.parseLong(lines.isEmpty() ? "" : lines.getFirst().strip());
        } catch (NumberFormatException e) {
            throw new LockFileException(stopFile + " does not start with the end of a stop in epoch milliseconds; "
                    + "delete it to allow requests", e);
        }
        if (until <= clock.getAsLong()) {
            return Optional.empty();
        }
        String reason = lines.size() > 1 && !lines.get(1).isBlank() ? lines.get(1).strip() : "no reason recorded";
        return Optional.of(new Stop(Instant.ofEpochMilli(until), reason, stopFile));
    }

    /// Writes the stop marker, unless it holds a stop that lasts longer; a failure only costs the other commands'
    /// early warning, so it is logged, not thrown.
    private Optional<Stop> writeStop(Place current, Instant until, String reason) {
        if (current == null || current.stopFile() == null) {
            return Optional.empty();
        }
        Path file = current.stopFile();
        String oneLine = reason.replaceAll("\\s+", " ").strip();
        try {
            Optional<Stop> existing;
            try {
                existing = readStop(file);
            } catch (LockFileException e) {
                existing = Optional.empty(); // damaged: replaced below
            }
            if (existing.isPresent() && !existing.get().until().isBefore(until)) {
                return existing;
            }
            AtomicFiles.writeString(file, until.toEpochMilli() + "\n" + oneLine + "\n"
                    + "No command using " + current.lockFile().getParent() + " sends requests before " + until
                    + "; delete this file to allow them earlier.\n");
            return Optional.of(new Stop(until, oneLine, file));
        } catch (IOException e) {
            Log.warn("cannot write %s (%s); the other commands on the same cache are not stopped", file, e);
            return Optional.empty();
        }
    }

    /// An I/O error on the lock file or the stop marker: a [LockFileException], or an [InterruptedIOException] when
    /// an interrupt of this thread caused it.
    private static IOException failure(String what, IOException e) {
        if (Thread.currentThread().isInterrupted()) {
            InterruptedIOException interrupted = new InterruptedIOException("interrupted: " + what);
            interrupted.initCause(e);
            return interrupted;
        }
        return new LockFileException(what + ": " + e, e);
    }

    /// The check of [#run(Request)].
    private static void refuseDuringStop(Stop stop) throws Http.StoppedException {
        if (stop != null) {
            throw new Http.StoppedException("not sending: " + stop.describe());
        }
    }

    /// The turn of one request.
    private final class TurnState implements Turn {

        private final Place current;
        private long delayMillis;

        TurnState(Place current) {
            this.current = current;
        }

        @Override
        public void delayNext(Duration delay) {
            Duration capped = delay.isNegative() ? Duration.ZERO : delay.compareTo(MAX_WAIT) > 0 ? MAX_WAIT : delay;
            delayMillis = Math.max(delayMillis, capped.toMillis());
        }

        @Override
        public Optional<Stop> stopAll(Instant until, String reason) {
            return writeStop(current, until, reason);
        }
    }
}
