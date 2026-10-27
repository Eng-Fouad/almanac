package io.quarkiverse.almanac;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/// A temporary folder for tests. Unlike JUnit's `@TempDir`, cleanup retries for a while and never fails the
/// test: on Windows, files that were just written can stay "delete pending" while a virus scanner holds them open.
public final class ScratchDir implements AutoCloseable {

    private static final int DELETE_ATTEMPTS = 20;

    private final Path path;

    private ScratchDir(Path path) {
        this.path = path;
    }

    public static ScratchDir create() throws IOException {
        return new ScratchDir(Files.createTempDirectory("quarkus-almanac-test-"));
    }

    public Path path() {
        return path;
    }

    public Path resolve(String other) {
        return path.resolve(other);
    }

    @Override
    public void close() throws InterruptedException {
        for (int attempt = 0; attempt < DELETE_ATTEMPTS && Files.exists(path); attempt++) {
            try (Stream<Path> tree = Files.walk(path)) {
                List<Path> deepestFirst = tree.sorted(Comparator.reverseOrder()).toList();
                for (Path file : deepestFirst) {
                    Files.deleteIfExists(file);
                }
            } catch (IOException e) {
                Thread.sleep(100);
            }
        }
    }
}
