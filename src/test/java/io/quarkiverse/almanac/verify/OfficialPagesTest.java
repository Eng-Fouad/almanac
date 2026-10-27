package io.quarkiverse.almanac.verify;

import io.quarkiverse.almanac.ScratchDir;
import io.quarkiverse.almanac.maven.Http;
import io.quarkiverse.almanac.util.AtomicFiles;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OfficialPagesTest {

    private static final URI LTS_320 = URI.create("https://quarkus.io/version/3.20/guides/all-config/");

    @Test
    void knowsThePagesOfLtsStreamsAndOfTheNewestFinalStream() {
        assertEquals(Optional.of(LTS_320), OfficialPages.urlOf("3.20", "3.40"));
        assertEquals(Optional.of(URI.create("https://quarkus.io/version/3.2/guides/all-config/")),
                OfficialPages.urlOf("3.2", null));
        // an LTS stream keeps its versioned page even while it is the newest final stream
        assertEquals(Optional.of(URI.create("https://quarkus.io/version/3.40/guides/all-config/")),
                OfficialPages.urlOf("3.40", "3.40"));
        assertEquals(Optional.of(OfficialPages.LATEST), OfficialPages.urlOf("3.41", "3.41"));
        assertEquals(Optional.empty(), OfficialPages.urlOf("3.39", "3.41"));
        assertEquals(Optional.empty(), OfficialPages.urlOf("4.0", "3.41"));
    }

    @Test
    void cacheMirrorsTheUrl() {
        Path cache = Path.of("work", "cache");

        assertEquals(cache.resolve("official/quarkus.io/version/3.20/guides/all-config.html"),
                OfficialPages.cacheFile(cache, LTS_320));
        assertEquals(cache.resolve("official/quarkus.io/guides/all-config.html"),
                OfficialPages.cacheFile(cache, OfficialPages.LATEST));
        assertEquals(cache.resolve("official/example.org/a_b/page.html"),
                OfficialPages.cacheFile(cache, URI.create("https://example.org/a%20b/page.html?x=1")));
    }

    @Test
    void usesTheCacheAndNeverFetchesOffline() throws Exception {
        try (ScratchDir dir = ScratchDir.create(); Http offline = new Http(1, true)) {
            Path cache = dir.resolve("cache");
            AtomicFiles.writeString(OfficialPages.cacheFile(cache, LTS_320), "<html>cached</html>");

            OfficialPages.Page page = OfficialPages.fetch(offline, LTS_320, cache, false);
            assertTrue(page.fromCache());
            assertEquals("<html>cached</html>", page.html());
            assertEquals(0, offline.requestCount());

            IOException missing = assertThrows(IOException.class,
                    () -> OfficialPages.fetch(offline, OfficialPages.LATEST, cache, false));
            assertTrue(missing.getMessage().startsWith("offline mode"), missing.getMessage());
            assertThrows(IOException.class, () -> OfficialPages.fetch(offline, LTS_320, cache, true));
        }
    }
}
