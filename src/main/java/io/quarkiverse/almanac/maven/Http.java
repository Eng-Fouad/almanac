package io.quarkiverse.almanac.maven;

import io.quarkiverse.almanac.util.Log;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPInputStream;

/// Polite HTTP client for Maven Central: HTTP/2, redirects followed, at most `concurrency`
/// requests in flight and, through its [Pacer], one at a time with a random pause between them; 60 s timeouts, up to
/// 6 attempts with exponential backoff (1, 2, 4, 8, 16 s) on I/O errors, 429 and 5xx, honouring `Retry-After`. A 403
/// (the repository refusing this client) or a 429 that outlasts the retries stops all further requests. In offline
/// mode every request fails.
public final class Http implements AutoCloseable {

    public static final String USER_AGENT = "quarkus-almanac/0.1 (unofficial Quarkus release almanac)";

    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final int MAX_ATTEMPTS = 6;
    private static final Duration MAX_RETRY_AFTER = Duration.ofMinutes(2);
    private static final Duration PROGRESS_INTERVAL = Duration.ofMinutes(1);

    /// A completed exchange; non-2xx statuses are returned, not thrown.
    public record Response(int status, byte[] body, HttpHeaders headers) {

        public Optional<String> header(String name) {
            return headers.firstValue(name);
        }
    }

    /// Thrown, without retries, for every request after the repository refused one.
    public static final class StoppedException extends IOException {

        private static final long serialVersionUID = 1L;

        StoppedException(String message) {
            super(message);
        }
    }

    private record Exchange(HttpResponse<byte[]> response, long nanos) {
    }

    private final HttpClient client;
    private final Semaphore permits;
    private final int concurrency;
    private final boolean offline;
    private final Pacer pacer;
    private final AtomicLong requests = new AtomicLong();
    private final AtomicLong bytesReceived = new AtomicLong();
    private final AtomicLong lastProgress = new AtomicLong(System.nanoTime());
    /// Why requests stopped (the refused request), null while they may go out.
    private volatile String stopped;

    /// A client that pauses between its own requests ([Pacer.Pause#DEFAULT]).
    public Http(int concurrency, boolean offline) {
        this(concurrency, offline, Pacer.local(Pacer.Pause.DEFAULT));
    }

    public Http(int concurrency, boolean offline, Pacer pacer) {
        this.concurrency = Math.max(1, concurrency);
        this.offline = offline;
        this.pacer = pacer;
        this.permits = new Semaphore(this.concurrency, true);
        this.client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(TIMEOUT)
                .build();
    }

    public int concurrency() {
        return concurrency;
    }

    public boolean offline() {
        return offline;
    }

    public Pacer pacer() {
        return pacer;
    }

    /// Number of HTTP exchanges made so far (retries included).
    public long requestCount() {
        return requests.get();
    }

    /// Body bytes received so far (as transferred, i.e. before gzip decoding).
    public long bytesReceived() {
        return bytesReceived.get();
    }

    /// GET of a whole resource; gzip transfer encoding is accepted and decoded transparently.
    public Response get(URI uri) throws IOException {
        return send(request(uri).header("Accept-Encoding", "gzip").build(), null);
    }

    /// GET of a byte range, e.g. `bytes=-65536` or `bytes=100-199`. A server that ignores ranges answers
    /// `200` with the whole body; callers must handle both.
    public Response getRange(URI uri, String range) throws IOException {
        return send(request(uri).header("Range", range).build(), range);
    }

    @Override
    public void close() {
        client.close();
    }

    private HttpRequest.Builder request(URI uri) throws IOException {
        if (offline) {
            throw new IOException("offline mode: not fetching " + uri);
        }
        return HttpRequest.newBuilder(uri).timeout(TIMEOUT).header("User-Agent", USER_AGENT).GET();
    }

    private Response send(HttpRequest request, String range) throws IOException {
        for (int attempt = 1; ; attempt++) {
            Duration wait;
            try {
                Response response = sendOnce(request, range);
                if (!isRetryable(response.status()) || attempt == MAX_ATTEMPTS) {
                    if (response.status() == 403 || response.status() == 429) {
                        stop(request.uri(), response.status());
                    }
                    return response;
                }
                wait = retryAfter(response).orElse(backoff(attempt));
                Log.debug("HTTP %d for %s, retry %d/%d in %d s", response.status(), request.uri(), attempt,
                        MAX_ATTEMPTS - 1, wait.toSeconds());
            } catch (IOException e) {
                if (e instanceof StoppedException || Thread.currentThread().isInterrupted()) {
                    throw e;
                }
                if (attempt == MAX_ATTEMPTS) {
                    throw new IOException("GET " + request.uri() + " failed after " + attempt + " attempts: " + e, e);
                }
                wait = backoff(attempt);
                Log.debug("%s for %s, retry %d/%d in %d s", e, request.uri(), attempt, MAX_ATTEMPTS - 1,
                        wait.toSeconds());
            }
            sleep(wait);
        }
    }

    private Response sendOnce(HttpRequest request, String range) throws IOException {
        checkNotStopped(request);
        try {
            permits.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("interrupted while waiting for a connection slot");
        }
        Exchange exchange;
        try {
            exchange = pacer.run(() -> exchange(request));
        } finally {
            permits.release();
        }
        HttpResponse<byte[]> response = exchange.response();
        requests.incrementAndGet();
        byte[] body = response.body();
        bytesReceived.addAndGet(body.length);
        Log.debug("GET %s%s -> %d (%d bytes, %d ms)", request.uri(), range == null ? "" : " [" + range + "]",
                response.statusCode(), body.length, exchange.nanos() / 1_000_000);
        logProgress();
        if (response.headers().firstValue("Content-Encoding").filter("gzip"::equalsIgnoreCase).isPresent()) {
            try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(body))) {
                body = gzip.readAllBytes();
            }
        }
        return new Response(response.statusCode(), body, response.headers());
    }

    /// One exchange, unless an earlier one stopped the requests; called in the pacer's turn, so that a 403 stops the
    /// requests before the next one starts.
    private Exchange exchange(HttpRequest request) throws IOException {
        checkNotStopped(request);
        long start = System.nanoTime();
        HttpResponse<byte[]> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("interrupted during GET " + request.uri());
        }
        if (response.statusCode() == 403) {
            stop(request.uri(), 403);
        }
        return new Exchange(response, System.nanoTime() - start);
    }

    private void checkNotStopped(HttpRequest request) throws StoppedException {
        String reason = stopped;
        if (reason != null) {
            throw new StoppedException("not requesting " + request.uri() + ": requests stopped after " + reason);
        }
    }

    /// Stops all further requests: the repository refuses this client (403) or keeps asking it to slow down (429).
    private void stop(URI uri, int status) {
        String reason = "HTTP " + status + " for " + uri;
        synchronized (this) {
            if (stopped != null) {
                return;
            }
            stopped = reason;
        }
        Log.error("%s: the repository refuses requests, so no more are sent", reason);
    }

    /// A progress line at most once a minute, for long runs.
    private void logProgress() {
        long now = System.nanoTime();
        long last = lastProgress.get();
        if (now - last >= PROGRESS_INTERVAL.toNanos() && lastProgress.compareAndSet(last, now)) {
            Log.info("HTTP: %d requests so far, %.1f MB received", requests.get(), bytesReceived.get() / 1e6);
        }
    }

    private static boolean isRetryable(int status) {
        return status == 429 || status / 100 == 5;
    }

    private static Duration backoff(int attempt) {
        return Duration.ofSeconds(1L << (attempt - 1));
    }

    private static Optional<Duration> retryAfter(Response response) {
        return response.header("Retry-After").map(String::strip).flatMap(value -> {
            OptionalLong seconds = parseSeconds(value);
            if (seconds.isPresent()) {
                return Optional.of(Duration.ofSeconds(seconds.getAsLong()));
            }
            try {
                Instant at = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                return Optional.of(Duration.between(Instant.now(), at));
            } catch (DateTimeParseException e) {
                return Optional.empty();
            }
        }).map(d -> d.isNegative() ? Duration.ZERO : d.compareTo(MAX_RETRY_AFTER) > 0 ? MAX_RETRY_AFTER : d);
    }

    private static OptionalLong parseSeconds(String value) {
        try {
            return OptionalLong.of(Long.parseLong(value));
        } catch (NumberFormatException e) {
            return OptionalLong.empty();
        }
    }

    private static void sleep(Duration wait) throws InterruptedIOException {
        try {
            Thread.sleep(wait);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("interrupted during retry backoff");
        }
    }
}
