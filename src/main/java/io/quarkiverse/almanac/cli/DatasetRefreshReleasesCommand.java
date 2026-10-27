package io.quarkiverse.almanac.cli;

import io.quarkiverse.almanac.dataset.DatasetEditor;
import io.quarkiverse.almanac.maven.BomSnapshots;
import io.quarkiverse.almanac.util.Log;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.Callable;

/// `dataset refresh-releases`: rebuilds `releases.jsonl` from `maven-metadata.xml`.
@Command(name = "refresh-releases", description = "Rebuild releases.jsonl from the maven-metadata.xml of "
        + "io.quarkus:quarkus-bom (the cached copy; fetched when older than 6 hours unless --offline). Releases with "
        + "data always stay.")
public final class DatasetRefreshReleasesCommand implements Callable<Integer> {

    @Mixin
    CommonOptions common;

    @Override
    public Integer call() throws IOException {
        try (Workspace workspace = Workspace.open(common)) {
            List<String> published = workspace.repo().versions(BomSnapshots.GROUP_ID, BomSnapshots.BOM_ARTIFACT_ID);
            DatasetEditor editor = DatasetEditor.open(workspace.datasetDir());
            logRefresh(editor.refreshReleases(published,
                    version -> DatasetImportCommand.cachedBomDate(workspace.cacheDir(), version)));
            DatasetCommand.write(editor);
            return 0;
        }
    }

    static void logRefresh(DatasetEditor.RefreshReport report) {
        Log.info("dataset: %d known releases (%d new%s, %d dropped%s, %d dated from the HTTP cache)", report.known(),
                report.added().size(), abbreviated(report.added()), report.dropped().size(),
                abbreviated(report.dropped()), report.dated());
    }

    private static String abbreviated(List<String> versions) {
        if (versions.isEmpty()) {
            return "";
        }
        return ": " + String.join(", ", versions.subList(0, Math.min(5, versions.size())))
                + (versions.size() > 5 ? ", ..." : "");
    }
}
