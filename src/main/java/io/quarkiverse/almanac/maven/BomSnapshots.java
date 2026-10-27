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
import java.util.Optional;
import java.util.Set;

/// Produces (or reuses) the `bom.json` snapshot of a Quarkus release.
public final class BomSnapshots {

    public static final String GROUP_ID = "io.quarkus";
    public static final String BOM_ARTIFACT_ID = "quarkus-bom";

    private final MavenRepo repo;
    private final SnapshotStore store;
    private final BomResolver resolver;

    public BomSnapshots(MavenRepo repo, SnapshotStore store) {
        this.repo = repo;
        this.store = store;
        this.resolver = new BomResolver((groupId, artifactId, version) ->
                repo.artifact(groupId, artifactId, version, "pom").map(MavenRepo.Download::content));
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
    /// otherwise resolved from the (cached) BOM pom, its parents and imported BOMs ([BomResolver]) and written to the
    /// work folder. An existing snapshot that keeps an unresolved `${...}` reference (written before the resolver
    /// existed) is reused with a warning; `force` resolves it again, which then fails.
    ///
    /// @throws IOException when the BOM cannot be resolved completely; nothing is written then
    public BomSnapshot load(String version, boolean force) throws IOException {
        Optional<BomSnapshot> existing = force ? Optional.empty() : existing(version);
        if (existing.isPresent()) {
            List<ManagedDep> unresolved = existing.get().deps().stream().filter(BomSnapshots::unresolved).toList();
            if (!unresolved.isEmpty()) {
                Log.warn("bom %s: the existing snapshot keeps %d unresolved versions (%s: %s%s)", version,
                        unresolved.size(), unresolved.getFirst().id(), unresolved.getFirst().v(),
                        unresolved.size() > 1 ? ", ..." : "");
            }
            Log.debug("bom %s: reusing snapshot", version);
            return existing.get();
        }
        MavenRepo.Download pom = repo.artifact(GROUP_ID, BOM_ARTIFACT_ID, version, "pom")
                .orElseThrow(() -> new IOException("quarkus-bom " + version + " not found in the repository"));
        String date = null;
        if (pom.lastModified() != null) {
            date = DateTimeFormatter.ISO_LOCAL_DATE.format(pom.lastModified().atOffset(ZoneOffset.UTC));
        } else {
            Log.warn("bom %s: no Last-Modified date known", version);
        }
        BomResolver.Result result = resolve(version, pom.content());
        BomSnapshot snapshot = new BomSnapshot(version, date, result.requireResolved().deps());
        store.write(version, SnapshotStore.BOM, snapshot);
        long quarkus = snapshot.deps().stream().map(ManagedDep::id).filter(id -> id.startsWith(GROUP_ID + ":")).count();
        Log.info("bom %s: %d managed dependencies (%d io.quarkus), released %s", version, snapshot.deps().size(),
                quarkus, date);
        if (result.poms() > 1) {
            Log.info("bom %s: resolved from %d poms: %d parents (%s), %d imported BOMs; %d entries imported", version,
                    result.poms(), result.parents().size(), String.join(", ", result.parents()),
                    result.imports().size(), result.imported());
            Log.debug("bom %s: imported %s", version, String.join(", ", result.imports()));
        }
        return snapshot;
    }

    /// The effective dependency management of a release's BOM pom, unresolved references included; warnings are
    /// logged. Nothing is written.
    ///
    /// @param pom the published BOM pom, or another one such as the BOM that is not flattened at the release's tag in
    ///            the Quarkus repository; its parents and imported BOMs come from the repository
    public BomResolver.Result resolve(String version, byte[] pom) throws IOException {
        BomResolver.Result result = resolver.resolve(pom, version);
        for (String warning : result.warnings()) {
            Log.warn("bom %s: %s", version, warning);
        }
        return result;
    }

    /// The release's snapshot as stored (work folder, else the committed dataset), if any.
    public Optional<BomSnapshot> existing(String version) throws IOException {
        return store.exists(version, SnapshotStore.BOM)
                ? Optional.of(store.read(version, SnapshotStore.BOM, BomSnapshot.class))
                : Optional.empty();
    }

    /// Whether a snapshot entry keeps a `${...}` reference other than the classifier placeholder.
    public static boolean unresolved(ManagedDep dep) {
        return dep.v().contains("${") || dep.id().replace(BomParser.QUARKUS_VERSION_PLACEHOLDER, "").contains("${");
    }
}
