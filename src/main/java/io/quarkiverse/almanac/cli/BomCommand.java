package io.quarkiverse.almanac.cli;

import io.quarkiverse.almanac.dataset.Dataset;
import io.quarkiverse.almanac.dataset.Kind;
import io.quarkiverse.almanac.maven.QuarkusVersion;
import io.quarkiverse.almanac.util.Log;
import io.quarkiverse.almanac.util.Parallel;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.Callable;

/// `bom`: writes `bom.json` snapshots of the releases the committed dataset does not have yet.
@Command(name = "bom", description = "Write bom.json snapshots (managed dependency versions) of the releases the "
        + "dataset does not have yet.")
public final class BomCommand implements Callable<Integer> {

    @Mixin
    CommonOptions common;

    @Option(names = "--versions", defaultValue = "all", paramLabel = "all|streams|LIST",
            description = "Releases to process (default: ${DEFAULT-VALUE}).")
    String versions;

    @Option(names = "--force", description = "Also process releases the dataset has, and rewrite existing snapshots.")
    boolean force;

    @Override
    public Integer call() throws IOException {
        try (Workspace workspace = Workspace.open(common)) {
            List<QuarkusVersion> selected = workspace.select(versions);
            return writeSnapshots(workspace, notInDataset(workspace, selected, force), force) ? 0 : 1;
        }
    }

    /// The releases whose BOM the dataset does not have (all of them with `force`), logging how many it has: published
    /// releases never change, so a release in the dataset is never downloaded again.
    static List<QuarkusVersion> notInDataset(Workspace workspace, List<QuarkusVersion> versions, boolean force)
            throws IOException {
        if (force) {
            return versions;
        }
        Dataset dataset = workspace.dataset();
        List<QuarkusVersion> missing = versions.stream()
                .filter(version -> !dataset.has(version.text(), Kind.BOM))
                .toList();
        int known = versions.size() - missing.size();
        if (known > 0) {
            Log.info("bom: %d releases already in the dataset (of %d selected; --force processes them again)", known,
                    versions.size());
        }
        return missing;
    }

    /// Writes the BOM snapshots of the given releases in parallel; false when any release failed.
    static boolean writeSnapshots(Workspace workspace, List<QuarkusVersion> versions, boolean force)
            throws IOException {
        if (versions.isEmpty()) {
            return true;
        }
        Log.info("bom: %d releases", versions.size());
        List<Boolean> results = Parallel.map(versions, workspace.concurrency(), version -> {
            try {
                workspace.boms().load(version.text(), force);
                return true;
            } catch (IOException e) {
                Log.error("bom %s: %s", version, e.getMessage());
                return false;
            }
        });
        long failed = results.stream().filter(ok -> !ok).count();
        if (failed > 0) {
            Log.error("bom: %d of %d releases failed", failed, versions.size());
        }
        return failed == 0;
    }
}
