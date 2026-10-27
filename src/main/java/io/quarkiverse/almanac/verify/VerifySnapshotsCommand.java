package io.quarkiverse.almanac.verify;

import io.quarkiverse.almanac.cli.CommonOptions;
import io.quarkiverse.almanac.util.Log;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.Callable;

/// `verify snapshots <a> <b>`: field-by-field comparison of two config snapshots.
@Command(name = "snapshots", description = "Compare two config snapshots: keys (alias-aware) and every field.")
public final class VerifySnapshotsCommand implements Callable<Integer> {

    @Mixin
    CommonOptions common;

    @Spec
    CommandSpec spec;

    @Parameters(index = "0", paramLabel = "A", description = "A snapshot file, or <version>[:config|:model|:bytecode] "
            + "for work/snapshots/<version>/config.json, config-model.json or config-bytecode.json.")
    String a;

    @Parameters(index = "1", paramLabel = "B", description = "The other snapshot, in the same forms.")
    String b;

    @Option(names = "--examples", defaultValue = "10", paramLabel = "N",
            description = "Examples shown per kind of mismatch (default: ${DEFAULT-VALUE}).")
    int examples;

    @Option(names = "--min-key-agreement", defaultValue = "0.99", paramLabel = "RATIO",
            description = "Fail below this key agreement: in both / (in both + only in a + only in b) "
                    + "(default: ${DEFAULT-VALUE}).")
    double minKeyAgreement;

    @Option(names = "--min-field-agreement", defaultValue = "0.99", paramLabel = "RATIO",
            description = "Fail when any field agrees on fewer of the matched pairs (default: ${DEFAULT-VALUE}).")
    double minFieldAgreement;

    @Option(names = "--json", paramLabel = "FILE", description = "Also write the report as JSON.")
    Path json;

    @Option(names = "--report", paramLabel = "FILE",
            description = "Also write the text report to this file.")
    Path report;

    @Override
    public Integer call() throws IOException {
        SharedOptions shared = SharedOptions.of(spec);
        Log.setVerbose(shared.verbose());
        SnapshotComparison.Options options = new SnapshotComparison.Options(Reports.examples(spec, examples),
                Reports.ratio(spec, "--min-key-agreement", minKeyAgreement),
                Reports.ratio(spec, "--min-field-agreement", minFieldAgreement));
        SnapshotRef refA = SnapshotRef.resolve(a, shared.snapshots());
        SnapshotRef refB = SnapshotRef.resolve(b, shared.snapshots());
        Log.info("verify snapshots: %s vs %s", refA.label(), refB.label());
        SnapshotComparison.Report result = SnapshotComparison.compare(input(refA), input(refB), options);
        Reports.output(spec, result.lines(), result, json, report);
        return result.pass() ? 0 : 1;
    }

    static SnapshotComparison.Input input(SnapshotRef ref) throws IOException {
        return new SnapshotComparison.Input(ref.label(), ref.file().toString(), ref.read());
    }
}
