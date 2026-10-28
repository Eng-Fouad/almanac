package io.quarkiverse.almanac.cli;

import io.quarkiverse.almanac.Main;
import io.quarkiverse.almanac.ScratchDir;
import io.quarkiverse.almanac.maven.BomSnapshots;
import io.quarkiverse.almanac.maven.Http;
import io.quarkiverse.almanac.maven.MavenRepo;
import io.quarkiverse.almanac.maven.Pacer;
import io.quarkiverse.almanac.maven.QuarkusVersion;
import io.quarkiverse.almanac.maven.RemoteJar;
import io.quarkiverse.almanac.util.AtomicFiles;
import io.quarkiverse.almanac.util.SnapshotStore;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// `bom --compare-pom`: the BOM of a release's git tag, resolved with its parent and imports, against the published
/// flattened BOM's snapshot; and the pass of `bom` over its releases, in which one broken release fails alone, a
/// release's [RuntimeException] after a stop of the requests also notes the stop, and an [Error] is not caught.
class BomCommandTest {

    /// The repository of the passes below, which none of them reaches: their requests are refused before they are
    /// sent, or not made (offline).
    private static final String REPO = "http://127.0.0.1:9/maven2/";

    /// The parent pom of release 9.2.1 ([#cacheReleases]), which is not cached.
    private static final String PARENT_OF_9_2_1 = MavenRepo.artifactPath("io.quarkus", "quarkus-parent", "9.2.1",
            "pom");

    private static final String PUBLISHED = """
            <project>
              <groupId>io.quarkus</groupId>
              <artifactId>quarkus-bom</artifactId>
              <version>2.0.0.Final</version>
              <dependencyManagement>
                <dependencies>
                  <dependency>
                    <groupId>io.quarkus</groupId>
                    <artifactId>quarkus-bom-quarkus-platform-descriptor</artifactId>
                    <version>2.0.0.Final</version>
                    <type>json</type>
                    <classifier>2.0.0.Final</classifier>
                  </dependency>
                  <dependency>
                    <groupId>io.quarkus</groupId>
                    <artifactId>quarkus-core</artifactId>
                    <version>2.0.0.Final</version>
                  </dependency>
                  <dependency>
                    <groupId>io.netty</groupId>
                    <artifactId>netty-common</artifactId>
                    <version>4.1.65.Final</version>
                  </dependency>
                </dependencies>
              </dependencyManagement>
            </project>
            """;

    private static final String QUARKUS_PARENT = """
            <project>
              <groupId>io.quarkus</groupId>
              <artifactId>quarkus-parent</artifactId>
              <version>2.0.0.Final</version>
              <properties><netty.version>4.1.65.Final</netty.version></properties>
            </project>
            """;

    private static final String NETTY_BOM = """
            <project>
              <groupId>io.netty</groupId>
              <artifactId>netty-bom</artifactId>
              <version>4.1.65.Final</version>
              <dependencyManagement><dependencies><dependency>
                <groupId>io.netty</groupId><artifactId>netty-common</artifactId><version>${project.version}</version>
              </dependency></dependencies></dependencyManagement>
            </project>
            """;

    /// The BOM at the release's tag; `%s` is the version of quarkus-core, `%s` an extra entry.
    private static final String AT_TAG = """
            <project>
              <parent>
                <groupId>io.quarkus</groupId>
                <artifactId>quarkus-parent</artifactId>
                <version>2.0.0.Final</version>
              </parent>
              <artifactId>quarkus-bom</artifactId>
              <dependencyManagement>
                <dependencies>
                  <dependency>
                    <groupId>io.quarkus</groupId>
                    <artifactId>quarkus-bom-quarkus-platform-descriptor</artifactId>
                    <version>${project.version}</version>
                    <type>json</type>
                    <classifier>${project.version}</classifier>
                  </dependency>
                  <dependency>
                    <groupId>io.quarkus</groupId><artifactId>quarkus-core</artifactId><version>%s</version>
                  </dependency>
                  %s
                  <dependency>
                    <groupId>io.netty</groupId><artifactId>netty-bom</artifactId><version>${netty.version}</version>
                    <type>pom</type><scope>import</scope>
                  </dependency>
                </dependencies>
              </dependencyManagement>
            </project>
            """;

    private static void cache(Path cache, String groupId, String artifactId, String version, String pom)
            throws IOException {
        Path file = cache.resolve(MavenRepo.artifactPath(groupId, artifactId, version, "pom"));
        Files.createDirectories(file.getParent());
        Files.writeString(file, pom, StandardCharsets.UTF_8);
    }

    @Test
    void comparesTheBomAtTheTagWithThePublishedOne() throws Exception {
        try (ScratchDir dir = ScratchDir.create(); Http offline = new Http(1, true)) {
            Path cache = dir.resolve("cache");
            cache(cache, "io.quarkus", "quarkus-bom", "2.0.0.Final", PUBLISHED);
            cache(cache, "io.quarkus", "quarkus-parent", "2.0.0.Final", QUARKUS_PARENT);
            cache(cache, "io.netty", "netty-bom", "4.1.65.Final", NETTY_BOM);
            BomSnapshots boms = new BomSnapshots(new MavenRepo(MavenRepo.CENTRAL, cache, offline,
                    RemoteJar.Options.DEFAULT), new SnapshotStore(dir.resolve("snapshots"), null));
            boms.load("2.0.0.Final", false);

            Path same = dir.resolve("same.xml");
            Files.writeString(same, AT_TAG.formatted("${project.version}", ""), StandardCharsets.UTF_8);
            StringWriter equal = new StringWriter();
            assertTrue(BomCommand.compare(boms, "2.0.0.Final", same, new PrintWriter(equal)));
            assertEquals("2.0.0.Final: 3 managed dependencies resolved from " + same + " (poms read: 3, parents: 1, "
                    + "imported BOMs: 1, entries imported: 1), 3 in the snapshot: equal\n",
                    equal.toString().replace("\r\n", "\n"));

            Path other = dir.resolve("other.xml");
            Files.writeString(other, AT_TAG.formatted("1.0", "<dependency><groupId>org.extra</groupId>"
                    + "<artifactId>x</artifactId><version>1</version></dependency>"), StandardCharsets.UTF_8);
            StringWriter differences = new StringWriter();
            assertFalse(BomCommand.compare(boms, "2.0.0.Final", other, new PrintWriter(differences)));
            String report = differences.toString().replace("\r\n", "\n");
            assertTrue(report.contains(": differences: 2\n"
                    + "  other version: io.quarkus:quarkus-core 1.0 (declared in io.quarkus:quarkus-bom:2.0.0.Final), "
                    + "snapshot 2.0.0.Final\n"
                    + "  only in the pom: org.extra:x 1 (declared in io.quarkus:quarkus-bom:2.0.0.Final)\n"), report);
        }
    }

    @Test
    void aBrokenReleaseFailsAloneAndThePassEndsWithItsSummary() throws Exception {
        // 9.2.1 imports a BOM whose version Maven's coordinate check accepts but no repository path can hold
        try (ScratchDir dir = ScratchDir.create()) {
            Path cache = dir.resolve("work/cache");
            Files.createDirectories(cache.resolve("io/quarkus/quarkus-bom"));
            Files.writeString(cache.resolve("io/quarkus/quarkus-bom/maven-metadata.xml"), "<metadata><versioning>"
                    + "<versions><version>9.2.0</version><version>9.2.1</version><version>9.2.2</version></versions>"
                    + "</versioning></metadata>\n", StandardCharsets.UTF_8);
            for (String version : new String[] {"9.2.0", "9.2.1", "9.2.2"}) {
                String entry = version.equals("9.2.1")
                        ? "<dependency><groupId>org.acme</groupId><artifactId>acme-bom</artifactId><version>1.0{x}"
                        + "</version><type>pom</type><scope>import</scope></dependency>"
                        : "<dependency><groupId>io.quarkus</groupId><artifactId>quarkus-core</artifactId><version>"
                        + version + "</version></dependency>";
                cache(cache, "io.quarkus", "quarkus-bom", version, "<project><groupId>io.quarkus</groupId><artifactId>"
                        + "quarkus-bom</artifactId><version>" + version + "</version><dependencyManagement>"
                        + "<dependencies>" + entry + "</dependencies></dependencyManagement></project>");
            }

            ByteArrayOutputStream log = new ByteArrayOutputStream();
            PrintStream originalErr = System.err;
            System.setErr(new PrintStream(log, true, StandardCharsets.UTF_8));
            int exitCode;
            try {
                exitCode = new CommandLine(new Main()).execute("bom", "--since", "9.2", "--offline",
                        "--concurrency", "1", "--work", dir.resolve("work").toString(), "--dataset",
                        dir.resolve("dataset").toString());
            } finally {
                System.setErr(originalErr);
            }

            String text = log.toString(StandardCharsets.UTF_8).replace("\r\n", "\n");
            assertEquals(1, exitCode, text);
            assertTrue(text.contains("error: bom 9.2.1: java.lang.IllegalArgumentException: "), text);
            assertTrue(text.contains("error: bom: 1 of 3 releases failed\n"), text);
            for (String written : new String[] {"9.2.0", "9.2.2"}) {
                assertTrue(Files.exists(dir.resolve("work/snapshots/" + written + "/bom.json")), written);
            }
            assertFalse(Files.exists(dir.resolve("work/snapshots/9.2.1/bom.json")));
        }
    }

    @Test
    void aReleaseThatFailsWithARuntimeExceptionOnceRequestsStoppedLogsTheStop() throws Exception {
        // the pom source of 9.2.1 requests its parent, which the stop marker of another command refuses, and carries
        // the stop in an UncheckedIOException; no other release starts after it to log the stop
        try (ScratchDir dir = ScratchDir.create()) {
            cacheReleases(dir, "9.2.1");
            AtomicFiles.writeString(dir.resolve("work/cache").resolve(Pacer.STOP_FILE),
                    (System.currentTimeMillis() + 3_600_000) + "\nHTTP 403 for another command\n");

            ByteArrayOutputStream log = new ByteArrayOutputStream();
            PrintStream originalErr = System.err;
            System.setErr(new PrintStream(log, true, StandardCharsets.UTF_8));
            boolean passed;
            // with a pause, requests take turns on the cache's lock file, next to the stop marker
            try (Workspace workspace = Workspace.open(options(dir, "--pause", "0.001"))) {
                BomSnapshots boms = boms(workspace, () -> {
                    try {
                        workspace.repo().artifact("io.quarkus", "quarkus-parent", "9.2.1", "pom");
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
                passed = BomCommand.writeSnapshots(workspace, boms, versions("9.2.1"), false);
            } finally {
                System.setErr(originalErr);
            }

            String text = log.toString(StandardCharsets.UTF_8).replace("\r\n", "\n");
            assertFalse(passed, text);
            assertTrue(text.contains("error: bom 9.2.1: java.io.UncheckedIOException: "), text);
            assertEquals(1, text.lines().filter(line -> line.startsWith("error: bom: requests stopped (a stop of every "
                    + "command on this cache until ")).count(), text);
            assertTrue(text.contains("error: bom: 1 of 1 releases failed\n"), text);
            assertFalse(text.contains("not attempted"), text);
        }
    }

    @Test
    void anErrorIsNotCaughtPerReleaseButEndsThePassOnceEveryReleaseRan() throws Exception {
        // an Error, here of the pom source of 9.2.1, is no broken release that the pass could log and go on from
        StackOverflowError error = new StackOverflowError("deep pom");
        try (ScratchDir dir = ScratchDir.create()) {
            cacheReleases(dir, "9.2.0", "9.2.1", "9.2.2");

            ByteArrayOutputStream log = new ByteArrayOutputStream();
            PrintStream originalErr = System.err;
            System.setErr(new PrintStream(log, true, StandardCharsets.UTF_8));
            try (Workspace workspace = Workspace.open(options(dir, "--offline", "--pause", "0", "--concurrency",
                    "1"))) {
                BomSnapshots boms = boms(workspace, () -> {
                    throw error;
                });
                assertSame(error, assertThrows(StackOverflowError.class, () -> BomCommand.writeSnapshots(workspace,
                        boms, versions("9.2.0", "9.2.1", "9.2.2"), false)));
            } finally {
                System.setErr(originalErr);
            }

            String text = log.toString(StandardCharsets.UTF_8).replace("\r\n", "\n");
            assertFalse(text.contains("error: bom 9.2.1"), text);
            assertFalse(text.contains("releases failed"), text);
            for (String written : new String[] {"9.2.0", "9.2.2"}) {
                assertTrue(Files.exists(dir.resolve("work/snapshots/" + written + "/bom.json")), written);
            }
            assertFalse(Files.exists(dir.resolve("work/snapshots/9.2.1/bom.json")));
        }
    }

    /// Caches the BOM pom of each release in the work folder of `dir`; that of 9.2.1 has the parent
    /// [#PARENT_OF_9_2_1].
    private static void cacheReleases(ScratchDir dir, String... versions) throws IOException {
        for (String version : versions) {
            String parent = version.equals("9.2.1")
                    ? "<parent><groupId>io.quarkus</groupId><artifactId>quarkus-parent</artifactId><version>9.2.1"
                    + "</version></parent>"
                    : "";
            cache(dir.resolve("work/cache"), "io.quarkus", "quarkus-bom", version, "<project>" + parent + "<groupId>"
                    + "io.quarkus</groupId><artifactId>quarkus-bom</artifactId><version>" + version + "</version>"
                    + "<dependencyManagement><dependencies><dependency><groupId>io.quarkus</groupId><artifactId>"
                    + "quarkus-core</artifactId><version>" + version + "</version></dependency></dependencies>"
                    + "</dependencyManagement></project>");
        }
    }

    /// The options of a run on the work folder of `dir` against [#REPO], with `more`.
    private static CommonOptions options(ScratchDir dir, String... more) {
        List<String> args = new ArrayList<>(List.of("--work", dir.resolve("work").toString(), "--dataset",
                dir.resolve("dataset").toString(), "--repo", REPO));
        args.addAll(List.of(more));
        return CommandLine.populateCommand(new CommonOptions(), args.toArray(String[]::new));
    }

    /// The BOM snapshots of the work folder of `workspace`, read from its cache with its client, except that the pom
    /// source runs `failure` first for [#PARENT_OF_9_2_1], as the cache folder resolves its path: a failure that no
    /// cached file or answer of the repository gives.
    private static BomSnapshots boms(Workspace workspace, Runnable failure) {
        Path cache = workspace.cacheDir();
        Path failing = (Path) Proxy.newProxyInstance(BomCommandTest.class.getClassLoader(), new Class<?>[] {Path.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("resolve") && PARENT_OF_9_2_1.equals(args[0])) {
                        failure.run();
                    }
                    try {
                        return method.invoke(cache, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        return new BomSnapshots(new MavenRepo(URI.create(REPO), failing, workspace.repo().http(),
                RemoteJar.Options.DEFAULT), workspace.workSnapshots());
    }

    private static List<QuarkusVersion> versions(String... versions) {
        return Stream.of(versions).map(QuarkusVersion::parse).toList();
    }
}
