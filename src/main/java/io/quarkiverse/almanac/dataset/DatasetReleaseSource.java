package io.quarkiverse.almanac.dataset;

import io.quarkiverse.almanac.assemble.HistoryBuilder;
import io.quarkiverse.almanac.assemble.ReleaseSource;
import io.quarkiverse.almanac.model.BomSnapshot;
import io.quarkiverse.almanac.model.ConfigSnapshot;
import io.quarkiverse.almanac.model.ExtensionsSnapshot;
import io.quarkiverse.almanac.util.Log;
import io.quarkiverse.almanac.util.SnapshotStore;

import java.io.IOException;
import java.util.Collection;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;

/// What `assemble` publishes: the releases of the committed dataset and, only when asked for
/// (`--include-work`), the work snapshots of the releases and kinds the dataset lacks. Work snapshots are not validated,
/// so by default none reaches the site.
public final class DatasetReleaseSource implements ReleaseSource {

    private final Dataset dataset;
    private final SnapshotStore work;
    private final AtomicInteger workBoms = new AtomicInteger();
    private final AtomicInteger workConfigs = new AtomicInteger();

    /// @param dataset the committed dataset
    /// @param work    work snapshots to add where the dataset has none (a store without dataset fallback), or null
    ///                for the dataset alone
    public DatasetReleaseSource(Dataset dataset, SnapshotStore work) {
        this.dataset = dataset;
        this.work = work;
    }

    @Override
    public Collection<String> bomVersions() throws IOException {
        SortedSet<String> versions = new TreeSet<>(ReleaseOrder.COMPARATOR);
        versions.addAll(dataset.versionsWith(Kind.BOM));
        if (work != null) {
            versions.addAll(work.versionsWith(SnapshotStore.BOM));
        }
        return versions;
    }

    @Override
    public boolean hasConfig(String version) {
        return dataset.has(version, Kind.CONFIG) || (work != null && work.inWork(version, SnapshotStore.CONFIG));
    }

    @Override
    public HistoryBuilder.Release load(String version) throws IOException {
        BomSnapshot bom;
        if (dataset.has(version, Kind.BOM)) {
            bom = dataset.bom(version);
        } else {
            bom = work.read(version, SnapshotStore.BOM, BomSnapshot.class);
            workBoms.incrementAndGet();
        }
        if (dataset.has(version, Kind.CONFIG) && dataset.has(version, Kind.EXTENSIONS)) {
            return new HistoryBuilder.Release(bom, dataset.config(version), dataset.extensions(version));
        }
        if (dataset.has(version, Kind.CONFIG) || dataset.has(version, Kind.EXTENSIONS)) {
            Log.warn("assemble: the dataset has only one of config and extensions for %s (dataset verify reports "
                    + "it); its config is ignored", version);
            return new HistoryBuilder.Release(bom, null, null);
        }
        if (work != null) {
            boolean config = work.inWork(version, SnapshotStore.CONFIG);
            boolean extensions = work.inWork(version, SnapshotStore.EXTENSIONS);
            if (config && extensions) {
                workConfigs.incrementAndGet();
                return new HistoryBuilder.Release(bom, work.read(version, SnapshotStore.CONFIG, ConfigSnapshot.class),
                        work.read(version, SnapshotStore.EXTENSIONS, ExtensionsSnapshot.class));
            }
            if (config != extensions) {
                Log.warn("assemble: %s has only one of config.json and extensions.json in %s; its config is ignored",
                        version, work.root());
            }
        }
        return new HistoryBuilder.Release(bom, null, null);
    }

    /// How many BOM snapshots came from the work folder.
    public int workBoms() {
        return workBoms.get();
    }

    /// How many config (and extensions) snapshots came from the work folder.
    public int workConfigs() {
        return workConfigs.get();
    }
}
