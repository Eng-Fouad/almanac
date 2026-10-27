package io.quarkiverse.almanac.verify;

import io.quarkiverse.almanac.maven.Http;
import io.quarkiverse.almanac.maven.VersionSelector;
import io.quarkiverse.almanac.util.AtomicFiles;
import io.quarkiverse.almanac.util.Log;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/// Where the official "all config" page of a stream lives and how it is fetched: one polite GET
/// through [Http] (same `User-Agent`, retries), cached for good under `work/cache/official/` in a
/// layout that mirrors the URL, e.g. `official/quarkus.io/version/3.20/guides/all-config.html`.
public final class OfficialPages {

    /// Page of the newest final stream.
    public static final URI LATEST = URI.create("https://quarkus.io/guides/all-config/");

    /// Cache folder below `work/cache`.
    public static final String CACHE_FOLDER = "official";

    private static final DateTimeFormatter DAY = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC);

    /// A fetched or cached page.
    public record Page(URI url, Path file, String html, boolean fromCache) {
    }

    private OfficialPages() {
    }

    /// The page URL of a stream: the versioned page for LTS streams, the latest page for the newest final stream,
    /// otherwise empty (the caller needs an explicit URL). The trailing slash is the canonical form (no redirect).
    ///
    /// @param newestFinalStream stream of the newest final release, may be null when unknown
    public static Optional<URI> urlOf(String stream, String newestFinalStream) {
        if (VersionSelector.LTS_STREAMS.contains(stream)) {
            return Optional.of(URI.create("https://quarkus.io/version/" + stream + "/guides/all-config/"));
        }
        if (stream.equals(newestFinalStream)) {
            return Optional.of(LATEST);
        }
        return Optional.empty();
    }

    /// Cache file of a page URL: `<cacheRoot>/official/<host>/<path>.html`; every path segment is reduced to
    /// `[A-Za-z0-9._-]` and a query string is ignored.
    public static Path cacheFile(Path cacheRoot, URI url) {
        Path file = cacheRoot.resolve(CACHE_FOLDER).resolve(safe(url.getHost() == null ? "local" : url.getHost()));
        String path = url.getPath() == null ? "" : url.getPath();
        String[] segments = path.replaceAll("^/+|/+$", "").split("/+");
        String last = "index";
        for (int i = 0; i < segments.length; i++) {
            if (segments[i].isEmpty()) {
                continue;
            }
            if (i == segments.length - 1) {
                last = safe(segments[i]);
            } else {
                file = file.resolve(safe(segments[i]));
            }
        }
        return file.resolve(last.endsWith(".html") ? last : last + ".html");
    }

    /// The page HTML: the cached copy unless `refresh`, otherwise one GET (the response is cached atomically).
    ///
    /// @throws IOException on a non-200 answer, or in offline mode when the page is not cached
    public static Page fetch(Http http, URI url, Path cacheRoot, boolean refresh) throws IOException {
        Path file = cacheFile(cacheRoot, url);
        if (!refresh && Files.isRegularFile(file)) {
            Log.info("official: using cached %s (fetched %s, %,d bytes)", file,
                    DAY.format(Files.getLastModifiedTime(file).toInstant()), Files.size(file));
            return new Page(url, file, new String(Files.readAllBytes(file), StandardCharsets.UTF_8), true);
        }
        if (http.offline()) {
            throw new IOException("offline mode: " + url + " is not cached (" + file + ")");
        }
        Log.info("official: fetching %s", url);
        Http.Response response = http.get(url);
        if (response.status() != 200) {
            throw new IOException("HTTP " + response.status() + " for " + url);
        }
        AtomicFiles.write(file, response.body());
        Log.info("official: cached %s (%,d bytes)", file, response.body().length);
        return new Page(url, file, new String(response.body(), StandardCharsets.UTF_8), false);
    }

    /// A local copy of a page (`--page-file`).
    public static Page read(Path file) throws IOException {
        Log.info("official: reading %s (%,d bytes)", file, Files.size(file));
        return new Page(null, file, new String(Files.readAllBytes(file), StandardCharsets.UTF_8), true);
    }

    private static String safe(String segment) {
        String safe = segment.replaceAll("[^A-Za-z0-9._-]", "_");
        return safe.isEmpty() || safe.chars().allMatch(c -> c == '.') ? "_" : safe;
    }
}
