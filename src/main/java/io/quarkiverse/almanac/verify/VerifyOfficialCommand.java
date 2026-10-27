package io.quarkiverse.almanac.verify;

import io.quarkiverse.almanac.cli.CommonOptions;
import io.quarkiverse.almanac.maven.BomSnapshots;
import io.quarkiverse.almanac.maven.Http;
import io.quarkiverse.almanac.maven.MavenRepo;
import io.quarkiverse.almanac.maven.QuarkusVersion;
import io.quarkiverse.almanac.maven.RemoteJar;
import io.quarkiverse.almanac.maven.VersionSelector;
import io.quarkiverse.almanac.util.Log;
import io.quarkiverse.almanac.util.SnapshotStore;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.URI;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;
import java.util.concurrent.Callable;

/// `verify official <version>`: compares a release's config snapshot with the official "all config" page of its
/// stream on quarkus.io. The page is fetched once and cached under
/// `work/cache/official/`; `--refresh` fetches it again and `--page-file` reads a local copy.
@Command(name = "official", description = "Compare a release's config snapshot with the official quarkus.io "
        + "\"all config\" page of its stream: names, build-time flags, env vars, types and defaults.")
public final class VerifyOfficialCommand implements Callable<Integer> {

    @Mixin
    CommonOptions common;

    @Spec
    CommandSpec spec;

    @Parameters(index = "0", paramLabel = "VERSION", description = "The release, e.g. 3.27.6.1.")
    String version;

    @Option(names = "--stream", paramLabel = "STREAM",
            description = "Stream whose page is used, e.g. 3.20 (default: the release's stream).")
    String stream;

    @Option(names = "--snapshot", paramLabel = "SNAPSHOT", description = "Snapshot to compare: a file or "
            + "<version>[:config|:model|:bytecode] (default: the release's config.json).")
    String snapshot;

    @Option(names = "--page-url", paramLabel = "URL", description = "URL of the page. Known without it: "
            + "https://quarkus.io/version/<stream>/guides/all-config/ for the LTS streams and "
            + "https://quarkus.io/guides/all-config/ for the newest final stream.")
    URI pageUrl;

    @Option(names = "--page-file", paramLabel = "FILE", description = "Read the page from a local file instead.")
    Path pageFile;

    @Option(names = "--refresh", description = "Fetch the page again even when it is cached.")
    boolean refresh;

    @Option(names = "--examples", defaultValue = "10", paramLabel = "N",
            description = "Examples shown per kind of mismatch (default: ${DEFAULT-VALUE}).")
    int examples;

    @Option(names = "--min-name-agreement", defaultValue = "0.995", paramLabel = "RATIO",
            description = "Fail when fewer of the page's keys are ours (default: ${DEFAULT-VALUE}).")
    double minNameAgreement;

    @Option(names = "--min-phase-agreement", defaultValue = "0.99", paramLabel = "RATIO",
            description = "Fail when fewer matched rows agree on \"fixed at build time\" (default: ${DEFAULT-VALUE}).")
    double minPhaseAgreement;

    @Option(names = "--json", paramLabel = "FILE", description = "Also write the report as JSON.")
    Path json;

    @Option(names = "--report", paramLabel = "FILE",
            description = "Also write the text report to this file.")
    Path report;

    /// The page HTML, where it came from and whether it was read from the cache.
    private record Source(String location, String html, boolean cached) {
    }

    @Override
    public Integer call() throws IOException {
        SharedOptions shared = SharedOptions.of(spec);
        Log.setVerbose(shared.verbose());
        OfficialComparison.Options options = new OfficialComparison.Options(Reports.examples(spec, examples),
                Reports.ratio(spec, "--min-name-agreement", minNameAgreement),
                Reports.ratio(spec, "--min-phase-agreement", minPhaseAgreement));
        QuarkusVersion release = QuarkusVersion.tryParse(version).orElseThrow(() ->
                new CommandLine.ParameterException(spec.commandLine(), "not a Quarkus version: " + version));
        String pageStream = stream != null ? stream.strip() : release.stream();
        if (pageUrl != null && pageFile != null) {
            throw new CommandLine.ParameterException(spec.commandLine(), "use either --page-url or --page-file");
        }

        SnapshotRef ref = null;
        IOException missingSnapshot = null;
        try {
            ref = SnapshotRef.resolve(snapshot != null ? snapshot : version, shared.snapshots());
        } catch (IOException e) {
            missingSnapshot = e;
        }
        Source source = load(shared, pageStream);
        OfficialPage page = OfficialPageParser.parse(source.html());
        if (missingSnapshot != null) {
            OfficialComparison.PageSide summary = OfficialComparison.pageSide(source.location(), page);
            PrintWriter out = spec.commandLine().getOut();
            out.println(Texts.line("page", summary.summary()));
            out.flush();
            OfficialComparison.warnings(null, summary, pageStream).forEach(warning -> Log.warn("%s", warning));
            throw missingSnapshot;
        }
        Log.info("verify official: %s vs %s", ref.label(), source.location());
        OfficialComparison.Report result = OfficialComparison.compare(VerifySnapshotsCommand.input(ref), page,
                source.location(), pageStream, options);
        Reports.output(spec, result.lines(), result, json, report);
        if (!result.warnings().isEmpty() && source.cached() && pageFile == null) {
            Log.info("verify: the page came from the cache; --refresh fetches it again");
        }
        return result.pass() ? 0 : 1;
    }

    /// The page: `--page-file`, else the cached or fetched page of `--page-url` or the stream.
    private Source load(SharedOptions shared, String pageStream) throws IOException {
        if (pageFile != null) {
            return new Source(pageFile.toString(), OfficialPages.read(pageFile).html(), false);
        }
        long start = System.nanoTime();
        try (Http http = shared.http()) {
            URI url = pageUrl != null ? pageUrl : pageUrl(shared, http, pageStream);
            OfficialPages.Page page = OfficialPages.fetch(http, url, shared.cache(), refresh);
            if (http.requestCount() > 0) {
                Log.info("HTTP: %d requests, %.1f MB received, %.1f s elapsed", http.requestCount(),
                        http.bytesReceived() / 1e6, (System.nanoTime() - start) / 1e9);
            }
            return new Source(url.toString(), page.html(), page.fromCache());
        }
    }

    /// The page of an LTS stream or of the newest final stream; fails for any other stream.
    private static URI pageUrl(SharedOptions shared, Http http, String pageStream) {
        if (VersionSelector.LTS_STREAMS.contains(pageStream)) {
            return OfficialPages.urlOf(pageStream, null).orElseThrow();
        }
        Optional<String> newest = newestFinalStream(shared, http);
        return OfficialPages.urlOf(pageStream, newest.orElse(null)).orElseThrow(() ->
                new IllegalArgumentException("no official page is known for stream " + pageStream
                        + ": quarkus.io keeps one for the LTS streams " + String.join(", ", VersionSelector.LTS_STREAMS)
                        + " and for the newest final stream" + newest.map(s -> " (" + s + ")").orElse("")
                        + "; pass --page-url or --page-file"));
    }

    /// Stream of the newest final release: from the repository's release list, else from the snapshot folders.
    private static Optional<String> newestFinalStream(SharedOptions shared, Http http) {
        SnapshotStore store = new SnapshotStore(shared.snapshots());
        try {
            MavenRepo repo = new MavenRepo(shared.repo(), shared.cache(), http, RemoteJar.Options.DEFAULT);
            return new BomSnapshots(repo, store).selector()
                    .all(QuarkusVersion.parse(VersionSelector.DEFAULT_SINCE)).stream()
                    .filter(QuarkusVersion::isFinal)
                    .max(Comparator.naturalOrder())
                    .map(QuarkusVersion::stream);
        } catch (IOException e) {
            Log.warn("cannot read the release list (%s); using the snapshot folders", e.getMessage());
            try {
                return store.versionsWith(SnapshotStore.BOM).stream()
                        .flatMap(name -> QuarkusVersion.tryParse(name).stream())
                        .filter(QuarkusVersion::isFinal)
                        .max(Comparator.naturalOrder())
                        .map(QuarkusVersion::stream);
            } catch (IOException e2) {
                return Optional.empty();
            }
        }
    }
}
