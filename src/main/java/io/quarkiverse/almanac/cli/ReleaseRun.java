package io.quarkiverse.almanac.cli;

import io.quarkiverse.almanac.maven.Http;
import io.quarkiverse.almanac.util.Log;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/// A command's pass over its releases, one after the other or in parallel, that ends when the repository's requests
/// stop ([Http.StoppedException]: a 403, the 429 or server error limits, a long outage, or the stop marker of another
/// command on the same cache): from then on no further release starts, instead of each failing with its own error.
/// Releases already started finish or fail as they would. The stop is logged once, and so is the number of releases
/// not attempted. Thread-safe.
final class ReleaseRun {

    private final String command;
    private final Http http;
    private final int releases;
    private final AtomicBoolean stopLogged = new AtomicBoolean();
    private final AtomicInteger notAttempted = new AtomicInteger();
    private volatile boolean stopped;

    /// @param command  the command, for the log lines
    /// @param http     the client whose requests the releases need
    /// @param releases how many releases the pass is over
    ReleaseRun(String command, Http http, int releases) {
        this.command = command;
        this.http = http;
        this.releases = releases;
    }

    /// Whether the next release may start: not once requests stopped, here or before this pass; it then counts as not
    /// attempted.
    boolean mayStart() {
        if (!stopped && !http.stopped()) {
            return true;
        }
        stop(null);
        notAttempted.incrementAndGet();
        return false;
    }

    /// Takes the failure of a release into account: when requests stopped, by this failure or meanwhile, no further
    /// release starts.
    void failed(Exception failure) {
        if (failure instanceof Http.StoppedException || http.stopped()) {
            stop(failure);
        }
    }

    /// Logs how many releases were not attempted, if any.
    ///
    /// @return false when a release was not attempted
    boolean finish() {
        int count = notAttempted.get();
        if (count > 0) {
            Log.error("%s: %d of %d releases not attempted, since requests stopped", command, count, releases);
        }
        return count == 0;
    }

    private void stop(Exception failure) {
        stopped = true;
        if (stopLogged.compareAndSet(false, true)) {
            String reason = http.stopReason().orElseGet(() -> failure != null ? failure.getMessage() : "?");
            Log.error("%s: requests stopped (%s); no further release is started", command, reason);
        }
    }
}
