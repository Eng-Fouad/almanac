package io.quarkiverse.almanac.extensions;

import com.sun.net.httpserver.HttpServer;
import io.quarkiverse.almanac.ScratchDir;
import io.quarkiverse.almanac.maven.Http;
import io.quarkiverse.almanac.maven.MavenRepo;
import io.quarkiverse.almanac.maven.Pacer;
import io.quarkiverse.almanac.maven.RemoteJar;
import io.quarkiverse.almanac.model.ExtensionInfo;
import io.quarkiverse.almanac.model.ExtensionsSnapshot;
import io.quarkiverse.almanac.util.SnapshotStore;
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
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// [ExtensionCatalogs] against a local repository: the catalog of a release is found through the metadata of both
/// catalog artifacts, the registry catalog is read with its classifier, a snapshot is written once and then reused,
/// and a release without a catalog gets none.
class ExtensionCatalogsTest {

    private static final String PLATFORM = "/io/quarkus/quarkus-bom-quarkus-platform-descriptor/";
    private static final String DESCRIPTOR = "/io/quarkus/quarkus-bom-descriptor-json/";

    private HttpServer server;
    private final Map<String, byte[]> files = new ConcurrentHashMap<>();
    private final List<String> requests = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            requests.add(path);
            byte[] body = files.get(path);
            exchange.sendResponseHeaders(body == null ? 404 : 200, body == null ? -1 : body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                if (body != null) {
                    out.write(body);
                }
            }
        });
        server.start();
        files.put(PLATFORM + "maven-metadata.xml", metadata("2.16.12.Final", "3.0.0"));
        files.put(DESCRIPTOR + "maven-metadata.xml", metadata("1.9.2.Final"));
        files.put(PLATFORM + "2.16.12.Final/quarkus-bom-quarkus-platform-descriptor-2.16.12.Final-2.16.12.Final.json",
                bytes("""
                        {"quarkus-core-version": "2.16.12.Final", "extensions": [
                          {"name": "ArC", "metadata": {"status": "stable", "config": ["quarkus.arc."]},
                           "artifact": "io.quarkus:quarkus-arc::jar:2.16.12.Final"}]}
                        """));
        files.put(DESCRIPTOR + "1.9.2.Final/quarkus-bom-descriptor-json-1.9.2.Final.json", bytes("""
                {"quarkus-core-version": "1.9.2.Final", "extensions": [
                  {"name": "ArC", "metadata": {}, "group-id": "io.quarkus", "artifact-id": "quarkus-arc",
                   "version": "1.9.2.Final"}]}
                """));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void writesASnapshotFromTheCatalogOfEachRelease() throws Exception {
        try (ScratchDir dir = ScratchDir.create(); Http http = new Http(1, false, Pacer.none())) {
            SnapshotStore store = new SnapshotStore(dir.resolve("snapshots"), null);
            ExtensionCatalogs catalogs = new ExtensionCatalogs(repo(dir, http), store);
            assertEquals(Optional.of(ExtensionCatalog.PLATFORM_DESCRIPTOR), catalogs.catalogOf("2.16.12.Final"));
            assertEquals(Optional.of(ExtensionCatalog.BOM_DESCRIPTOR_JSON), catalogs.catalogOf("1.9.2.Final"));
            assertEquals(Optional.empty(), catalogs.catalogOf("0.25.0"));

            assertTrue(catalogs.write("2.16.12.Final", false));
            assertTrue(catalogs.write("1.9.2.Final", false));
            assertFalse(catalogs.write("0.25.0", false), "no catalog, no snapshot");
            assertFalse(store.inWork("0.25.0", SnapshotStore.EXTENSIONS));
            assertEquals(new ExtensionsSnapshot("2.16.12.Final", List.of(new ExtensionInfo("quarkus-arc", "ArC", null,
                            "stable", List.of(), null, false, List.of("quarkus.arc"), null))),
                    store.read("2.16.12.Final", SnapshotStore.EXTENSIONS, ExtensionsSnapshot.class));
            assertEquals(new ExtensionsSnapshot("1.9.2.Final", List.of(new ExtensionInfo("quarkus-arc", "ArC", null,
                            null, List.of(), null, false, List.of(), null))),
                    store.read("1.9.2.Final", SnapshotStore.EXTENSIONS, ExtensionsSnapshot.class));
            assertTrue(Files.isRegularFile(dir.resolve("cache" + PLATFORM
                    + "2.16.12.Final/quarkus-bom-quarkus-platform-descriptor-2.16.12.Final-2.16.12.Final.json")));

            // each metadata file and each catalog once; an existing snapshot is reused without a request
            int made = requests.size();
            assertEquals(4, made, requests.toString());
            assertTrue(catalogs.write("2.16.12.Final", false));
            assertEquals(made, requests.size());
            // force reads the catalog again (from the cache)
            assertTrue(catalogs.write("2.16.12.Final", true));
            assertEquals(made, requests.size());
        }
    }

    @Test
    void aListedCatalogThatCannotBeReadIsAnError() throws Exception {
        try (ScratchDir dir = ScratchDir.create(); Http http = new Http(1, false, Pacer.none())) {
            ExtensionCatalogs catalogs = new ExtensionCatalogs(repo(dir, http),
                    new SnapshotStore(dir.resolve("snapshots"), null));
            IOException missing = assertThrows(IOException.class, () -> catalogs.write("3.0.0", false));
            assertEquals("io.quarkus:quarkus-bom-quarkus-platform-descriptor:3.0.0:json:3.0.0 is listed in its "
                    + "maven-metadata.xml but not in the repository", missing.getMessage());

            // the 404 is cached, so a catalog published later needs another cache folder here
            files.put(PLATFORM + "3.0.0/quarkus-bom-quarkus-platform-descriptor-3.0.0-3.0.0.json",
                    bytes("{\"extensions\": [{\"artifact\": \"org.acme:acme-extension::jar:1.0\"}]}"));
            ExtensionCatalogs fresh = new ExtensionCatalogs(repo(dir.resolve("fresh-cache"), http),
                    new SnapshotStore(dir.resolve("snapshots"), null));
            IOException empty = assertThrows(IOException.class, () -> fresh.write("3.0.0", false));
            assertEquals("io.quarkus:quarkus-bom-quarkus-platform-descriptor:3.0.0:json:3.0.0 lists no io.quarkus "
                    + "extension", empty.getMessage());
        }
    }

    private MavenRepo repo(ScratchDir dir, Http http) {
        return repo(dir.resolve("cache"), http);
    }

    private MavenRepo repo(Path cache, Http http) {
        URI base = URI.create("http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort()
                + "/");
        return new MavenRepo(base, cache, http, RemoteJar.Options.DEFAULT);
    }

    private static byte[] metadata(String... versions) {
        StringBuilder xml = new StringBuilder("<metadata><versioning><versions>");
        for (String version : versions) {
            xml.append("<version>").append(version).append("</version>");
        }
        return bytes(xml.append("</versions></versioning></metadata>\n").toString());
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
