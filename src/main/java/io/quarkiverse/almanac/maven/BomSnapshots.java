package io.quarkiverse.almanac.maven;

import io.quarkiverse.almanac.model.BomSnapshot;
import io.quarkiverse.almanac.model.ManagedDep;
import io.quarkiverse.almanac.util.Log;
import io.quarkiverse.almanac.util.SnapshotStore;

import java.io.IOException;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/// Produces (or reuses) the `bom.json` snapshot of a Quarkus release.
public final class BomSnapshots {

    public static final String GROUP_ID = "io.quarkus";
    public static final String BOM_ARTIFACT_ID = "quarkus-bom";

    private final MavenRepo repo;
    private final SnapshotStore store;

    public BomSnapshots(MavenRepo repo, SnapshotStore store) {
        this.repo = repo;
        this.store = store;
    }

    /// Every published `io.quarkus:quarkus-bom` version: the versions of `maven-metadata.xml` (cached, or fetched
    /// when stale unless offline) together with the releases the committed dataset knows (`releases.jsonl`). Without a
    /// readable `maven-metadata.xml`, as offline on a fresh clone, the dataset's list alone.
    ///
    /// @throws IOException when neither list is available
    public VersionSelector selector() throws IOException {
        List<String> known = store.knownReleases();
        List<String> published;
        try {
            published = repo.versions(GROUP_ID, BOM_ARTIFACT_ID);
        } catch (IOException e) {
            if (known.isEmpty()) {
                throw e;
            }
            Log.info("release list: maven-metadata.xml not available (%s); using the %d releases of the dataset",
                    e.getMessage(), known.size());
            return new VersionSelector(known);
        }
        if (known.isEmpty()) {
            return new VersionSelector(published);
        }
        Set<String> union = new LinkedHashSet<>(published);
        union.addAll(known);
        return new VersionSelector(union);
    }

    /// The BOM snapshot of a release: the existing snapshot (work folder, else the committed dataset) unless `force`,
    /// otherwise parsed from the (cached) BOM pom and written to the work folder.
    public BomSnapshot load(String version, boolean force) throws IOException {
        if (!force && store.exists(version, SnapshotStore.BOM)) {
            Log.debug("bom %s: reusing snapshot", version);
            return store.read(version, SnapshotStore.BOM, BomSnapshot.class);
        }
        MavenRepo.Download pom = repo.artifact(GROUP_ID, BOM_ARTIFACT_ID, version, "pom")
                .orElseThrow(() -> new IOException("quarkus-bom " + version + " not found in the repository"));
        String date = null;
        if (pom.lastModified() != null) {
            date = DateTimeFormatter.ISO_LOCAL_DATE.format(pom.lastModified().atOffset(ZoneOffset.UTC));
        } else {
            Log.warn("bom %s: no Last-Modified date known", version);
        }
        BomSnapshot snapshot = new BomSnapshot(version, date, BomParser.parse(pom.content(), version));
        store.write(version, SnapshotStore.BOM, snapshot);
        long quarkus = snapshot.deps().stream().map(ManagedDep::id).filter(id -> id.startsWith(GROUP_ID + ":")).count();
        Log.info("bom %s: %d managed dependencies (%d io.quarkus), released %s", version, snapshot.deps().size(),
                quarkus, date);
        return snapshot;
    }
}
