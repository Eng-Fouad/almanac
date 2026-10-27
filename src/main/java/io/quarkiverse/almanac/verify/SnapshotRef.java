package io.quarkiverse.almanac.verify;

import io.quarkiverse.almanac.maven.QuarkusVersion;
import io.quarkiverse.almanac.model.ConfigSnapshot;
import io.quarkiverse.almanac.util.Json;
import io.quarkiverse.almanac.util.SnapshotStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/// A config snapshot named on the command line: a snapshot file, or `<version>[:config|:model|:bytecode]`
/// for `work/snapshots/<version>/config.json`, `config-model.json` or `config-bytecode.json`.
/// A version that has no folder of its own finds one that compares equal (`3.0.4` → `3.0.4.Final`).
///
/// @param label how reports name the snapshot: `3.40.1.1`, `3.25.4:bytecode` or the file as given
/// @param file  the snapshot file
public record SnapshotRef(String label, Path file) {

    private static final Pattern VERSION_REF = Pattern.compile("(.+?)(?::(config|model|bytecode))?");
    private static final Map<String, String> FILES = Map.of("config", SnapshotStore.CONFIG,
            "model", SnapshotStore.CONFIG_MODEL, "bytecode", SnapshotStore.CONFIG_BYTECODE);

    /// Resolves an argument against the snapshots folder.
    ///
    /// @throws IOException when the snapshot does not exist
    public static SnapshotRef resolve(String argument, Path snapshotsDir) throws IOException {
        String text = argument.strip();
        Matcher m = VERSION_REF.matcher(text);
        if (m.matches() && QuarkusVersion.tryParse(m.group(1)).isPresent() && !isFile(text)) {
            String kind = m.group(2) == null ? "config" : m.group(2);
            String version = versionFolder(snapshotsDir, m.group(1))
                    .orElseThrow(() -> new IOException("no snapshots of " + m.group(1) + " in " + snapshotsDir));
            Path file = snapshotsDir.resolve(version).resolve(FILES.get(kind));
            if (!Files.isRegularFile(file)) {
                throw new IOException(file + " does not exist (write it with: config --versions " + version
                        + (kind.equals("config") ? "" : " --source both") + ")");
            }
            return new SnapshotRef(kind.equals("config") ? version : version + ":" + kind, file);
        }
        Path file = Path.of(text);
        if (!Files.isRegularFile(file)) {
            throw new IOException("not a snapshot file or <version>[:config|:model|:bytecode]: " + argument);
        }
        return new SnapshotRef(text, file);
    }

    /// Reads the snapshot.
    public ConfigSnapshot read() throws IOException {
        return Json.read(file, ConfigSnapshot.class);
    }

    private static boolean isFile(String text) {
        try {
            return Files.isRegularFile(Path.of(text));
        } catch (InvalidPathException e) {
            return false;
        }
    }

    private static Optional<String> versionFolder(Path snapshotsDir, String version) throws IOException {
        if (Files.isDirectory(snapshotsDir.resolve(version))) {
            return Optional.of(version);
        }
        if (!Files.isDirectory(snapshotsDir)) {
            return Optional.empty();
        }
        QuarkusVersion wanted = QuarkusVersion.parse(version);
        try (Stream<Path> dirs = Files.list(snapshotsDir)) {
            return dirs.filter(Files::isDirectory)
                    .map(dir -> dir.getFileName().toString())
                    .filter(name -> QuarkusVersion.tryParse(name).filter(v -> v.compareTo(wanted) == 0).isPresent())
                    .sorted()
                    .findFirst();
        }
    }
}
