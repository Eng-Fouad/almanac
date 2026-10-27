package io.quarkiverse.almanac.extensions;

import io.quarkiverse.almanac.maven.MavenRepo;
import io.quarkiverse.almanac.model.ExtensionInfo;
import io.quarkiverse.almanac.model.ExtensionsSnapshot;
import io.quarkiverse.almanac.util.Log;
import io.quarkiverse.almanac.util.SnapshotStore;

import java.io.IOException;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/// Writes the `extensions.json` snapshot of a release from its extension catalog ([ExtensionCatalog]): one small
/// JSON file per release instead of the hundreds of jars the config extraction reads. Which catalog a release has is
/// read from the `maven-metadata.xml` of both catalog artifacts, on first use (cached like every metadata file).
public final class ExtensionCatalogs {

    private final MavenRepo repo;
    private final SnapshotStore store;
    private volatile Map<ExtensionCatalog, Set<String>> published;

    /// @param repo  the repository the catalogs come from
    /// @param store where the snapshots are written: its work folder, which is also the only place checked for an
    ///              existing snapshot
    public ExtensionCatalogs(MavenRepo repo, SnapshotStore store) {
        this.repo = repo;
        this.store = store;
    }

    /// The catalog published with a release: the first [ExtensionCatalog] whose `maven-metadata.xml` lists it; empty
    /// when none does (releases before 0.26.0).
    ///
    /// @throws IOException when a `maven-metadata.xml` can neither be read from the cache nor downloaded
    public Optional<ExtensionCatalog> catalogOf(String version) throws IOException {
        for (Map.Entry<ExtensionCatalog, Set<String>> catalog : published().entrySet()) {
            if (catalog.getValue().contains(version)) {
                return Optional.of(catalog.getKey());
            }
        }
        return Optional.empty();
    }

    /// Writes a release's snapshot from its catalog, unless the work folder has one already and `force` is false.
    ///
    /// @return false when no catalog is published for the release, so nothing was written
    /// @throws IOException when the catalog cannot be downloaded or read, or lists no extension
    public boolean write(String version, boolean force) throws IOException {
        if (!force && store.inWork(version, SnapshotStore.EXTENSIONS)) {
            Log.info("extensions %s: reusing the existing snapshot %s", version,
                    store.file(version, SnapshotStore.EXTENSIONS));
            return true;
        }
        Optional<ExtensionCatalog> catalog = catalogOf(version);
        if (catalog.isEmpty()) {
            Log.warn("extensions %s: no extension catalog is published for this release (catalogs exist from 0.26.0 "
                    + "on); no snapshot written", version);
            return false;
        }
        ExtensionCatalog source = catalog.get();
        MavenRepo.Download download = repo.artifact(ExtensionCatalog.GROUP_ID, source.artifactId(), version,
                source.classifier(version), ExtensionCatalog.EXTENSION).orElseThrow(() -> new IOException(
                source.coordinates(version) + " is listed in its maven-metadata.xml but not in the repository"));
        ExtensionsSnapshot snapshot;
        try {
            snapshot = ExtensionCatalogReader.read(version, download.content());
        } catch (IOException e) {
            throw new IOException(source.coordinates(version) + ": " + e.getMessage(), e);
        }
        if (snapshot.extensions().isEmpty()) {
            throw new IOException(source.coordinates(version) + " lists no io.quarkus extension");
        }
        store.write(version, SnapshotStore.EXTENSIONS, snapshot);
        long unlisted = snapshot.extensions().stream().filter(ExtensionInfo::unlisted).count();
        Log.info("extensions %s: %d extensions (%d unlisted) from %s", version, snapshot.extensions().size(), unlisted,
                source.coordinates(version));
        return true;
    }

    /// The versions each catalog artifact lists, read once.
    private Map<ExtensionCatalog, Set<String>> published() throws IOException {
        Map<ExtensionCatalog, Set<String>> result = published;
        if (result == null) {
            synchronized (this) {
                result = published;
                if (result == null) {
                    result = new EnumMap<>(ExtensionCatalog.class);
                    for (ExtensionCatalog catalog : ExtensionCatalog.values()) {
                        result.put(catalog, new HashSet<>(repo.versions(ExtensionCatalog.GROUP_ID,
                                catalog.artifactId())));
                    }
                    published = result;
                }
            }
        }
        return result;
    }
}
