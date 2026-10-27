package io.quarkiverse.almanac.verify;

import io.quarkiverse.almanac.ScratchDir;
import io.quarkiverse.almanac.util.Json;
import io.quarkiverse.almanac.util.SnapshotStore;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;

import static io.quarkiverse.almanac.verify.Fixtures.prop;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnapshotRefTest {

    @Test
    void resolvesVersionsKindsAndFiles() throws Exception {
        try (ScratchDir dir = ScratchDir.create()) {
            Path snapshots = dir.resolve("snapshots");
            SnapshotStore store = new SnapshotStore(snapshots);
            store.write("3.0.4.Final", SnapshotStore.CONFIG, Fixtures.snapshot("3.0.4.Final", "bytecode",
                    prop("quarkus.a").build()));
            store.write("3.40.1", SnapshotStore.CONFIG_MODEL, Fixtures.snapshot("3.40.1", "model",
                    prop("quarkus.b").build()));
            Path loose = dir.resolve("loose.json");
            Json.writePretty(loose, Fixtures.snapshot("1.0", "model"));

            SnapshotRef config = SnapshotRef.resolve("3.0.4", snapshots);
            assertEquals("3.0.4.Final", config.label());
            assertEquals(store.file("3.0.4.Final", SnapshotStore.CONFIG), config.file());
            assertEquals("quarkus.a", config.read().properties().getFirst().name());

            SnapshotRef model = SnapshotRef.resolve("3.40.1:model", snapshots);
            assertEquals("3.40.1:model", model.label());
            assertEquals(store.file("3.40.1", SnapshotStore.CONFIG_MODEL), model.file());

            SnapshotRef file = SnapshotRef.resolve(loose.toString(), snapshots);
            assertEquals(loose.toString(), file.label());
            assertEquals("1.0", file.read().version());
        }
    }

    @Test
    void missingSnapshotsSayHowToWriteThem() throws Exception {
        try (ScratchDir dir = ScratchDir.create()) {
            Path snapshots = dir.resolve("snapshots");
            new SnapshotStore(snapshots).write("3.40.1", SnapshotStore.CONFIG, Fixtures.snapshot("3.40.1", "model"));

            IOException bytecode = assertThrows(IOException.class,
                    () -> SnapshotRef.resolve("3.40.1:bytecode", snapshots));
            assertTrue(bytecode.getMessage().contains("config --versions 3.40.1 --source both"),
                    bytecode.getMessage());
            IOException unknown = assertThrows(IOException.class, () -> SnapshotRef.resolve("3.9.0", snapshots));
            assertTrue(unknown.getMessage().startsWith("no snapshots of 3.9.0"), unknown.getMessage());
            IOException notAFile = assertThrows(IOException.class,
                    () -> SnapshotRef.resolve(dir.resolve("nothing.json").toString(), snapshots));
            assertTrue(notAFile.getMessage().startsWith("not a snapshot file"), notAFile.getMessage());
        }
    }
}
