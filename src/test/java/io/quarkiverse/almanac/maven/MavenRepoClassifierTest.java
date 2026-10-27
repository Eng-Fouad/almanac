package io.quarkiverse.almanac.maven;

import com.sun.net.httpserver.HttpServer;
import io.quarkiverse.almanac.ScratchDir;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// [MavenRepo] artifacts with a classifier: their repository path, and the cache (content, `Last-Modified`, 404
/// marker) shared with the artifacts without one.
class MavenRepoClassifierTest {

    private static final String CATALOG = "/io/quarkus/quarkus-bom-quarkus-platform-descriptor/3.20.6/"
            + "quarkus-bom-quarkus-platform-descriptor-3.20.6-3.20.6.json";

    private HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            requests.add(path);
            byte[] body = path.equals(CATALOG) ? "{\"extensions\": []}".getBytes(StandardCharsets.UTF_8) : null;
            if (body != null) {
                exchange.getResponseHeaders().add("Last-Modified", "Tue, 17 Mar 2026 10:15:30 GMT");
            }
            exchange.sendResponseHeaders(body == null ? 404 : 200, body == null ? -1 : body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                if (body != null) {
                    out.write(body);
                }
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void artifactPathWithAndWithoutClassifier() {
        assertEquals(CATALOG.substring(1), MavenRepo.artifactPath("io.quarkus",
                "quarkus-bom-quarkus-platform-descriptor", "3.20.6", "3.20.6", "json"));
        assertEquals("io/quarkus/quarkus-bom/3.20.6/quarkus-bom-3.20.6.pom",
                MavenRepo.artifactPath("io.quarkus", "quarkus-bom", "3.20.6", null, "pom"));
        assertEquals(MavenRepo.artifactPath("io.quarkus", "quarkus-bom", "3.20.6", "pom"),
                MavenRepo.artifactPath("io.quarkus", "quarkus-bom", "3.20.6", "", "pom"));
    }

    @Test
    void anArtifactWithAClassifierIsCachedLikeTheOthers() throws Exception {
        try (ScratchDir dir = ScratchDir.create(); Http http = new Http(1, false, Pacer.none())) {
            URI base = URI.create("http://" + server.getAddress().getHostString() + ":"
                    + server.getAddress().getPort() + "/");
            MavenRepo repo = new MavenRepo(base, dir.resolve("cache"), http, RemoteJar.Options.DEFAULT);

            MavenRepo.Download catalog = repo.artifact("io.quarkus", "quarkus-bom-quarkus-platform-descriptor",
                    "3.20.6", "3.20.6", "json").orElseThrow();
            assertArrayEquals("{\"extensions\": []}".getBytes(StandardCharsets.UTF_8), catalog.content());
            assertEquals("2026-03-17T10:15:30Z", catalog.lastModified().toString());
            assertTrue(Files.isRegularFile(dir.resolve("cache" + CATALOG)));
            assertTrue(repo.artifact("io.quarkus", "quarkus-bom-quarkus-platform-descriptor", "3.20.7", "3.20.7",
                    "json").isEmpty(), "404");
            assertTrue(Files.isRegularFile(dir.resolve("cache/io/quarkus/quarkus-bom-quarkus-platform-descriptor/"
                    + "3.20.7/quarkus-bom-quarkus-platform-descriptor-3.20.7-3.20.7.json.404")));

            // both answers come from the cache from now on
            assertEquals(2, requests.size());
            MavenRepo.Download cached = repo.artifact("io.quarkus", "quarkus-bom-quarkus-platform-descriptor",
                    "3.20.6", "3.20.6", "json").orElseThrow();
            assertArrayEquals(catalog.content(), cached.content());
            assertEquals(catalog.lastModified(), cached.lastModified());
            assertTrue(repo.artifact("io.quarkus", "quarkus-bom-quarkus-platform-descriptor", "3.20.7", "3.20.7",
                    "json").isEmpty());
            assertEquals(2, requests.size());
        }
    }
}
