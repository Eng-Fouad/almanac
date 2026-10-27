package io.quarkiverse.almanac.config.bytecode;

import java.io.IOException;
import java.net.URI;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/// Class files of the running JDK, read from the `jrt:/` file system. Config members use a few JDK types whose kind
/// matters, e.g. `java.time.temporal.ChronoUnit` (an enum) or `java.nio.file.Path` (an interface that is never a
/// group).
final class JdkClasses {

    private static final FileSystem JRT = openJrt();

    private JdkClasses() {
    }

    /// The class file of a JDK class.
    ///
    /// @param internalName e.g. `java/time/temporal/ChronoUnit`
    /// @return empty when the running JDK has no such class (or no `jrt:/` file system)
    static Optional<byte[]> read(String internalName) throws IOException {
        int slash = internalName.lastIndexOf('/');
        if (JRT == null || slash < 0) {
            return Optional.empty();
        }
        Path packageDir = JRT.getPath("/packages", internalName.substring(0, slash).replace('/', '.'));
        if (!Files.isDirectory(packageDir)) {
            return Optional.empty();
        }
        // /packages/<package>/<module> links to /modules/<module>
        try (DirectoryStream<Path> modules = Files.newDirectoryStream(packageDir)) {
            for (Path module : modules) {
                Path file = JRT.getPath("/modules", module.getFileName().toString(), internalName + ".class");
                if (Files.isRegularFile(file)) {
                    return Optional.of(Files.readAllBytes(file));
                }
            }
        }
        return Optional.empty();
    }

    private static FileSystem openJrt() {
        try {
            return FileSystems.getFileSystem(URI.create("jrt:/"));
        } catch (FileSystemNotFoundException | UnsupportedOperationException e) {
            return null;
        }
    }
}
