package io.quarkiverse.almanac.cli;

import com.sun.net.httpserver.HttpServer;
import io.quarkiverse.almanac.Main;
import io.quarkiverse.almanac.ScratchDir;
import io.quarkiverse.almanac.maven.Pacer;
import io.quarkiverse.almanac.model.BomSnapshot;
import io.quarkiverse.almanac.model.ManagedDep;
import io.quarkiverse.almanac.util.AtomicFiles;
import io.quarkiverse.almanac.util.SnapshotStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// `bom`, `config` and `extensions` against a local repository that refuses every request (an HTTP 403), or with the
/// stop marker of another command on the same cache: the first stop of the requests ends the pass over the releases.
/// The releases started by then fail as they would, the stop is logged once, then how many releases were not
/// attempted, and the exit code is 1.
class StoppedRequestsTest {

    private static final List<String> RELEASES = List.of("3.90.0", "3.90.1", "3.90.2", "3.90.3", "3.90.4",
            "3.90.5", "3.90.6", "3.90.7", "3.90.8", "3.90.9", "3.90.10", "3.90.11");
    private static final String PLATFORM = "cache/io/quarkus/quarkus-bom-quarkus-platform-descriptor/";
    private static final Pattern BOM_POM = Pattern.compile("/maven2/io/quarkus/quarkus-bom/([^/]+)/[^/]+\\.pom");

    /// Exit code and log (standard error) of one run.
    private record Run(int exitCode, String log) {

        /// The lines that start with `prefix`.
        List<String> lines(String prefix) {
            return log.lines().filter(line -> line.startsWith(prefix)).toList();
        }

        /// The number in the log line that `pattern` (with one group) matches; 0 without such a line.
        int count(String pattern) {
            Matcher m = Pattern.compile(pattern).matcher(log);
            return m.find() ? Integer.parseInt(m.group(1)) : 0;
        }
    }

    private ScratchDir dir;
    private HttpServer refusing;
    private final List<String> requested = new CopyOnWriteArrayList<>();
    /// How many requests for a BOM pom the repository answers before it refuses the next one; 0: it refuses all.
    private volatile int answered;
    /// The stop marker that the repository writes, as another command would, while it answers its last BOM pom;
    /// null: it refuses the next request instead.
    private volatile Path marker;

    @BeforeEach
    void start() throws IOException {
        dir = ScratchDir.create();
        refusing = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        refusing.createContext("/", exchange -> {
            requested.add(exchange.getRequestURI().getPath());
            Matcher bom = BOM_POM.matcher(exchange.getRequestURI().getPath());
            if (requested.size() > answered || !bom.matches()) {
                exchange.sendResponseHeaders(403, -1);
                exchange.close();
                return;
            }
            if (requested.size() == answered && marker != null) {
                AtomicFiles.writeString(marker, (System.currentTimeMillis() + 3_600_000) + "\nHTTP 403 for another "
                        + "command\n");
            }
            byte[] pom = ("<project><groupId>io.quarkus</groupId><artifactId>quarkus-bom</artifactId><version>"
                    + bom.group(1) + "</version><dependencyManagement><dependencies><dependency><groupId>io.quarkus"
                    + "</groupId><artifactId>quarkus-core</artifactId><version>" + bom.group(1) + "</version>"
                    + "</dependency></dependencies></dependencyManagement></project>").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Last-Modified", "Thu, 01 Jan 2026 00:00:00 GMT");
            exchange.sendResponseHeaders(200, pom.length);
            exchange.getResponseBody().write(pom);
            exchange.close();
        });
        refusing.start();
    }

    @AfterEach
    void stop() throws InterruptedException {
        refusing.stop(0);
        dir.close();
    }

    /// The options of a run on the work folder `name` against the refusing repository, one request at a time.
    private String[] options(String name, String... more) {
        String repo = "http://" + refusing.getAddress().getHostString() + ":" + refusing.getAddress().getPort()
                + "/maven2/";
        return Stream.concat(Stream.of("--work", dir.resolve(name).toString(), "--dataset",
                dir.resolve(name + "-dataset").toString(), "--repo", repo, "--pause", "0.001"), Stream.of(more))
                .toArray(String[]::new);
    }

    /// A work folder whose cache lists [#RELEASES] and nothing else.
    private Path work(String name) throws IOException {
        Path work = dir.resolve(name);
        AtomicFiles.writeString(work.resolve("cache/io/quarkus/quarkus-bom/maven-metadata.xml"), metadata(RELEASES));
        return work;
    }

    @Test
    void bomStartsNoFurtherReleaseOnceARequestIsRefused() throws Exception {
        for (int concurrency : List.of(1, 4)) {
            String name = "bom-" + concurrency;
            work(name);
            requested.clear();

            Run run = run(concat(List.of("bom", "--since", "3.90"), options(name, "--concurrency", "" + concurrency)));

            assertEquals(1, run.exitCode(), run.log());
            assertEquals(1, requested.size(), "only the first request went out: " + requested);
            List<String> stops = run.lines("error: bom: requests stopped (HTTP 403 for ");
            assertEquals(1, stops.size(), run.log());
            assertTrue(stops.getFirst().endsWith("); no further release is started"), stops.getFirst());
            int failed = run.count("error: bom: (\\d+) of 12 releases failed");
            int notAttempted = run.count("error: bom: (\\d+) of 12 releases not attempted, since requests stopped");
            assertEquals(12, failed + notAttempted, run.log());
            // the releases started before the stop fail as they would, each with its line; no other release starts
            assertTrue(failed >= 1 && failed <= concurrency, run.log());
            assertEquals(failed, run.lines("error: bom 3.90.").size(), run.log());
            if (concurrency == 1) {
                assertEquals(11, notAttempted);
            }
        }
    }

    @Test
    void bomStartsNoFurtherReleaseOnceAnotherCommandStoppedTheRequests() throws Exception {
        Path work = work("bom");
        AtomicFiles.writeString(work.resolve("cache").resolve(Pacer.STOP_FILE),
                (System.currentTimeMillis() + 3_600_000) + "\nHTTP 403 for another command\n");

        Run run = run(concat(List.of("bom", "--since", "3.90"), options("bom", "--concurrency", "1")));

        assertEquals(1, run.exitCode(), run.log());
        assertEquals(List.of(), requested);
        assertEquals(1, run.lines("error: bom: requests stopped (a stop of every command on this cache until ").size(),
                run.log());
        assertEquals(1, run.lines("error: bom 3.90.").size(), run.log());
        assertTrue(run.log().contains("error: bom: 11 of 12 releases not attempted, since requests stopped\n"),
                run.log());
    }

    @Test
    void configStartsNoFurtherReleaseOnceARequestIsRefused() throws Exception {
        Path work = work("config");
        // the BOMs of the other releases are there: only their config would need requests
        SnapshotStore snapshots = new SnapshotStore(work.resolve("snapshots"), null);
        for (String release : List.of("3.90.1", "3.90.2")) {
            snapshots.write(release, SnapshotStore.BOM, new BomSnapshot(release, "2026-01-01",
                    List.of(new ManagedDep("io.quarkus:quarkus-core", release))));
        }

        Run run = run(concat(List.of("config", "--versions", "3.90.0,3.90.1,3.90.2"), options("config")));

        assertEquals(1, run.exitCode(), run.log());
        assertEquals(List.of("/maven2/io/quarkus/quarkus-bom/3.90.0/quarkus-bom-3.90.0.pom"), requested);
        assertEquals(List.of("error: config 3.90.0: HTTP 403 for http://" + refusing.getAddress().getHostString() + ":"
                + refusing.getAddress().getPort() + "/maven2/io/quarkus/quarkus-bom/3.90.0/quarkus-bom-3.90.0.pom"),
                run.lines("error: config 3.90."));
        assertEquals(1, run.lines("error: config: requests stopped (HTTP 403 for ").size(), run.log());
        assertTrue(run.log().contains("error: config: 1 of 3 releases produced no snapshot\n"
                + "error: config: 2 of 3 releases not attempted, since requests stopped\n"), run.log());
    }

    @Test
    void extensionsStartsNoFurtherReleaseOnceARequestIsRefused() throws Exception {
        Path work = work("extensions");
        AtomicFiles.writeString(work.resolve(PLATFORM + "maven-metadata.xml"), metadata(List.of("3.90.0", "3.90.1")));
        AtomicFiles.writeString(work.resolve("cache/io/quarkus/quarkus-bom-descriptor-json/maven-metadata.xml"),
                metadata(List.of()));
        // the catalog of 3.90.1 is cached: it would be written without any request
        AtomicFiles.writeString(work.resolve(PLATFORM + "3.90.1/quarkus-bom-quarkus-platform-descriptor-3.90.1-3.90.1"
                + ".json"), """
                {"extensions": [{"name": "ArC", "metadata": {"status": "stable"},
                                 "artifact": "io.quarkus:quarkus-arc::jar:3.90.1"}]}
                """);

        Run run = run(concat(List.of("extensions", "--versions", "3.90.0,3.90.1"), options("extensions")));

        assertEquals(1, run.exitCode(), run.log());
        assertEquals(1, requested.size(), requested.toString());
        assertEquals(1, run.lines("error: extensions 3.90.").size(), run.log());
        assertEquals(1, run.lines("error: extensions: requests stopped (HTTP 403 for ").size(), run.log());
        assertTrue(run.log().contains("error: extensions: 1 of 2 releases not attempted, since requests stopped\n"),
                run.log());
        assertFalse(new SnapshotStore(work.resolve("snapshots"), null).inWork("3.90.1", SnapshotStore.EXTENSIONS));
    }

    @Test
    void allStartsNoConfigReleaseOnceTheBomStepStoppedTheRequests() throws Exception {
        work("all");

        Run run = run(concat(List.of("all", "--since", "3.90.11", "--site", dir.resolve("site").toString()),
                options("all")));

        assertEquals(1, run.exitCode(), run.log());
        assertEquals(1, requested.size(), requested.toString());
        assertEquals(1, run.lines("error: config: requests stopped (HTTP 403 for ").size(), run.log());
        assertTrue(run.log().contains("error: config: 1 of 1 releases not attempted, since requests stopped\n"),
                run.log());
        assertEquals(List.of(), run.lines("error: config 3.90."), run.log());
    }

    @Test
    void releasesWrittenBeforeTheStopStayAndEveryReleaseIsCounted() throws Exception {
        // the repository answers the first BOM poms, then refuses one; or another command stops the requests meanwhile
        for (boolean byMarker : List.of(false, true)) {
            String name = byMarker ? "bom-marker" : "bom-refused";
            Path work = work(name);
            requested.clear();
            answered = byMarker ? 3 : 2;
            marker = byMarker ? work.resolve("cache").resolve(Pacer.STOP_FILE) : null;

            Run run = run(concat(List.of("bom", "--since", "3.90"), options(name, "--concurrency", "1")));

            assertEquals(1, run.exitCode(), run.log());
            assertEquals(answered + (byMarker ? 0 : 1), requested.size(), requested.toString());
            // the releases whose BOM pom was answered are written, whichever started first; no other one
            List<String> written = RELEASES.stream()
                    .filter(release -> Files.exists(work.resolve("snapshots/" + release + "/bom.json"))).toList();
            List<String> served = requested.subList(0, answered).stream()
                    .map(path -> path.split("/")[5]).sorted().toList();
            assertEquals(served, written.stream().sorted().toList(), run.log());
            assertEquals(1, run.lines("error: bom: requests stopped (").size(), run.log());
            int failed = run.count("error: bom: (\\d+) of 12 releases failed");
            int notAttempted = run.count("error: bom: (\\d+) of 12 releases not attempted, since requests stopped");
            assertEquals(1, failed, run.log());
            assertEquals(12, written.size() + failed + notAttempted, run.log());
        }
    }

    @Test
    void theStopOfTheLastReleaseToStartIsLoggedToo() throws Exception {
        work("bom");
        Run bom = run(concat(List.of("bom", "--versions", "3.90.0"), options("bom")));
        assertEquals(1, bom.exitCode(), bom.log());
        assertEquals(1, bom.lines("error: bom: requests stopped (HTTP 403 for ").size(), bom.log());
        assertTrue(bom.log().contains("error: bom: 1 of 1 releases failed\n"), bom.log());
        assertFalse(bom.log().contains("not attempted"), bom.log());

        work("config");
        Run config = run(concat(List.of("config", "--versions", "3.90.0"), options("config")));
        assertEquals(1, config.exitCode(), config.log());
        assertEquals(1, config.lines("error: config: requests stopped (HTTP 403 for ").size(), config.log());
        assertTrue(config.log().contains("error: config: 1 of 1 releases produced no snapshot\n"), config.log());
        assertFalse(config.log().contains("not attempted"), config.log());

        Path work = work("extensions");
        AtomicFiles.writeString(work.resolve(PLATFORM + "maven-metadata.xml"), metadata(List.of("3.90.0")));
        AtomicFiles.writeString(work.resolve("cache/io/quarkus/quarkus-bom-descriptor-json/maven-metadata.xml"),
                metadata(List.of()));
        Run extensions = run(concat(List.of("extensions", "--versions", "3.90.0"), options("extensions")));
        assertEquals(1, extensions.exitCode(), extensions.log());
        assertEquals(1, extensions.lines("error: extensions: requests stopped (HTTP 403 for ").size(),
                extensions.log());
        assertTrue(extensions.log().contains("error: extensions: 1 of 1 releases failed\n"), extensions.log());
        assertFalse(extensions.log().contains("not attempted"), extensions.log());
    }

    @Test
    void releasesNotAttemptedFailTheCommandWhenNoReleaseFailed() throws Exception {
        // the stop is in force before the pass: the release list comes from the dataset, no release starts
        for (List<String> command : List.of(List.of("bom", "--since", "3.90"),
                List.of("config", "--versions", "3.90.0,3.90.1,3.90.2,3.90.3"),
                List.of("extensions", "--versions", "3.90.0,3.90.1,3.90.2,3.90.3"))) {
            String name = command.getFirst() + "-before";
            Path cache = dir.resolve(name).resolve("cache");
            AtomicFiles.writeString(cache.resolve(Pacer.STOP_FILE),
                    (System.currentTimeMillis() + 3_600_000) + "\nHTTP 403 for another command\n");
            StringBuilder releases = new StringBuilder();
            for (String release : RELEASES.subList(0, 4)) {
                releases.append("{\"version\":\"").append(release).append("\",\"date\":\"2026-01-01\"}\n");
            }
            AtomicFiles.writeString(dir.resolve(name + "-dataset").resolve("releases.jsonl"), releases.toString());
            requested.clear();

            Run run = run(concat(command, options(name)));

            assertEquals(1, run.exitCode(), run.log());
            assertEquals(List.of(), requested);
            String prefix = "error: " + command.getFirst() + ": ";
            assertEquals(1, run.lines(prefix + "requests stopped (").size(), run.log());
            assertTrue(run.log().contains(prefix + "4 of 4 releases not attempted, since requests stopped\n"),
                    run.log());
            assertEquals(List.of(), run.lines("error: " + command.getFirst() + " 3.90."), run.log());
            assertFalse(run.log().contains("releases failed") || run.log().contains("produced no snapshot"),
                    run.log());
        }
    }

    private static String metadata(List<String> versions) {
        StringBuilder xml = new StringBuilder("<metadata><versioning><versions>");
        for (String version : versions) {
            xml.append("<version>").append(version).append("</version>");
        }
        return xml.append("</versions></versioning></metadata>\n").toString();
    }

    private static String[] concat(List<String> command, String[] options) {
        List<String> args = new ArrayList<>(command);
        args.addAll(List.of(options));
        return args.toArray(String[]::new);
    }

    private static Run run(String... args) {
        ByteArrayOutputStream log = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        System.setErr(new PrintStream(log, true, StandardCharsets.UTF_8));
        try {
            CommandLine commandLine = new CommandLine(new Main()).setCaseInsensitiveEnumValuesAllowed(true);
            commandLine.setOut(new PrintWriter(new StringWriter()));
            commandLine.setErr(new PrintWriter(new StringWriter()));
            int exitCode = commandLine.execute(args);
            return new Run(exitCode, log.toString(StandardCharsets.UTF_8).replace("\r\n", "\n"));
        } finally {
            System.setErr(originalErr);
        }
    }
}
