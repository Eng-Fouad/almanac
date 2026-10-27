package io.quarkiverse.almanac.maven;

import io.quarkiverse.almanac.util.Log;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
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
public final class Pacer {

    /// The lock file in a cache folder (see [#forCache]).
    public static final String LOCK_FILE = ".pacing";

    /// A random pause between `min` and `max`, both included; [#NONE] turns pacing off.
    public record Pause(Duration min, Duration max) {

        public static final Pause NONE = new Pause(Duration.ZERO, Duration.ZERO);
        /// 2 to 5 seconds.
        public static final Pause DEFAULT = new Pause(Duration.ofSeconds(2), Duration.ofSeconds(5));

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
        T send() throws IOException;
    }

    /// Waits; replaced in tests.
    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    /// One turn lock per lock file, so that two threads of this process never lock the same file at once (which
    /// `FileChannel` refuses within a process).
    private static final ConcurrentMap<Path, ReentrantLock> SHARED_TURNS = new ConcurrentHashMap<>();

    private final Pause pause;
    private final Path lockFile;
    private final ReentrantLock turn;
    private final LongSupplier clock;
    private final Sleeper sleeper;
    private final RandomGenerator random;
    private final AtomicBoolean announced = new AtomicBoolean();
    private final AtomicBoolean lockFileFailed = new AtomicBoolean();
    private final AtomicLong pausedMillis = new AtomicLong();
    /// Earliest start of this process's next request, in epoch milliseconds; guarded by `turn`.
    private long nextStart;

    /// @param lockFile null for a pacer of this process only
    /// @param clock    epoch milliseconds
    Pacer(Pause pause, Path lockFile, LongSupplier clock, Sleeper sleeper, RandomGenerator random) {
        this.pause = pause;
        this.lockFile = lockFile == null ? null : lockFile.toAbsolutePath().normalize();
        this.turn = this.lockFile == null
                ? new ReentrantLock(true)
                : SHARED_TURNS.computeIfAbsent(this.lockFile, file -> new ReentrantLock(true));
        this.clock = clock;
        this.sleeper = sleeper;
        this.random = random;
    }

    /// No pauses: requests start as soon as an HTTP slot is free.
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

    /// Sends `request` when its turn comes: once the previous request (of this process or, for a shared pacer, of any
    /// process using the lock file) has ended and a random pause has passed. The pause also follows a request that
    /// failed.
    public <T> T run(Request<T> request) throws IOException {
        if (pause.isNone()) {
            return request.send();
        }
        if (announced.compareAndSet(false, true)) {
            Log.info("HTTP: one request at a time, %s s apart at random (--pause)", pause);
        }
        try {
            turn.lockInterruptibly();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("interrupted while waiting for the turn to send a request");
        }
        try {
            if (lockFile == null) {
                return paced(request, null);
            }
            Files.createDirectories(lockFile.getParent());
            try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.READ,
                    StandardOpenOption.WRITE)) {
                channel.lock(); // released when the channel closes
                return paced(request, channel);
            }
        } finally {
            turn.unlock();
        }
    }

    /// Waits for the earliest start, sends, and sets the next earliest start, also in the lock file when there is one.
    private <T> T paced(Request<T> request, FileChannel lock) throws IOException {
        waitUntil(lock == null ? nextStart : Math.max(nextStart, readNextStart(lock)));
        try {
            return request.send();
        } finally {
            nextStart = clock.getAsLong() + randomPauseMillis();
            if (lock != null) {
                writeNextStart(lock, nextStart);
            }
        }
    }

    /// Sleeps until `notBefore`, but never longer than the longest pause: a time from a damaged lock file or a clock
    /// that jumped cannot stall the requests.
    private void waitUntil(long notBefore) throws InterruptedIOException {
        long wait = Math.min(notBefore - clock.getAsLong(), pause.max().toMillis());
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

    private long randomPauseMillis() {
        long min = pause.min().toMillis();
        long max = pause.max().toMillis();
        return min == max ? min : random.nextLong(min, max + 1);
    }

    /// The earliest start the lock file holds (epoch milliseconds as text); 0 when it is empty or unreadable.
    private static long readNextStart(FileChannel lock) {
        try {
            ByteBuffer buffer = ByteBuffer.allocate(32);
            lock.read(buffer, 0);
            String text = new String(buffer.array(), 0, buffer.position(), StandardCharsets.US_ASCII).strip();
            return text.isEmpty() ? 0 : Long.parseLong(text);
        } catch (IOException | NumberFormatException e) {
            return 0;
        }
    }

    private void writeNextStart(FileChannel lock, long millis) {
        try {
            lock.truncate(0);
            lock.write(ByteBuffer.wrap((millis + "\n").getBytes(StandardCharsets.US_ASCII)), 0);
        } catch (IOException e) {
            if (lockFileFailed.compareAndSet(false, true)) {
                Log.warn("cannot write %s (%s); other commands may not wait for this one's pauses", lockFile,
                        e.getMessage());
            }
        }
    }
}
