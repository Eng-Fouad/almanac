package io.quarkiverse.almanac.cli;

import io.quarkiverse.almanac.dataset.DatasetVerifier;
import io.quarkiverse.almanac.util.Log;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.concurrent.Callable;

/// `dataset verify`: integrity of the committed dataset.
@Command(name = "verify", description = "Check the dataset: well-formed sorted lines, every referenced state present "
        + "(and no other), state ids, releases.jsonl against the histories and config-meta.jsonl, consistent restored "
        + "snapshots, and a byte-identical, deterministic re-encoding.")
public final class DatasetVerifyCommand implements Callable<Integer> {

    @Mixin
    CommonOptions common;

    @Spec
    CommandSpec spec;

    @Option(names = "--compare-work", description = "Also compare every restored snapshot with the work snapshot of "
            + "the same release, where there is one (as records and as JSON).")
    boolean compareWork;

    @Override
    public Integer call() throws IOException {
        try (Workspace workspace = Workspace.open(common)) {
            DatasetVerifier.Report report = DatasetVerifier.verify(workspace.datasetDir(),
                    compareWork ? workspace.workSnapshots() : null);
            PrintWriter out = spec.commandLine().getOut();
            report.summary().forEach(out::println);
            report.problems().forEach(problem -> out.println("PROBLEM " + problem));
            out.println(report.ok() ? "dataset verify: OK" : "dataset verify: " + report.problems().size()
                    + " problems");
            out.flush();
            if (!report.ok()) {
                Log.error("dataset verify: %d problems in %s", report.problems().size(), workspace.datasetDir());
            }
            return report.ok() ? 0 : 1;
        }
    }
}
