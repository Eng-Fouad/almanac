package io.quarkiverse.almanac.assemble;

import io.quarkiverse.almanac.model.BomSnapshot;
import io.quarkiverse.almanac.model.ConfigSnapshot;
import io.quarkiverse.almanac.model.ExtensionsSnapshot;
import io.quarkiverse.almanac.util.Log;
import io.quarkiverse.almanac.util.SnapshotStore;

import java.io.IOException;
import java.util.Collection;

/// Where [HistoryBuilder#assemble(ReleaseSource, Collection, java.nio.file.Path, java.time.Instant)] reads the
/// snapshots of the releases it publishes: the committed dataset (`io.quarkiverse.almanac.dataset.DatasetReleaseSource`, the
/// default of the `assemble` command) or a snapshot store ([#of(SnapshotStore)]).
public interface ReleaseSource {

    /// The releases that have a BOM snapshot, in any order.
    Collection<String> bomVersions() throws IOException;

    /// Whether the release has a config snapshot; used only to warn about one without a BOM snapshot.
    boolean hasConfig(String version) throws IOException;

    /// The snapshots of a release listed by [#bomVersions()]: its BOM, and its config and extensions when it has both.
    HistoryBuilder.Release load(String version) throws IOException;

    /// The snapshots of a store (its work folder, then its dataset fallback, as [SnapshotStore#read] finds them).
    static ReleaseSource of(SnapshotStore store) {
        return new ReleaseSource() {
            @Override
            public Collection<String> bomVersions() throws IOException {
                return store.versionsWith(SnapshotStore.BOM);
            }

            @Override
            public boolean hasConfig(String version) {
                return store.exists(version, SnapshotStore.CONFIG);
            }

            @Override
            public HistoryBuilder.Release load(String version) throws IOException {
                BomSnapshot bom = store.read(version, SnapshotStore.BOM, BomSnapshot.class);
                boolean hasConfig = store.exists(version, SnapshotStore.CONFIG);
                boolean hasExtensions = store.exists(version, SnapshotStore.EXTENSIONS);
                if (hasConfig != hasExtensions) {
                    Log.warn("assemble: %s has only one of config.json and extensions.json; its config is ignored",
                            version);
                }
                if (!hasConfig || !hasExtensions) {
                    return new HistoryBuilder.Release(bom, null, null);
                }
                return new HistoryBuilder.Release(bom, store.read(version, SnapshotStore.CONFIG, ConfigSnapshot.class),
                        store.read(version, SnapshotStore.EXTENSIONS, ExtensionsSnapshot.class));
            }
        };
    }
}
