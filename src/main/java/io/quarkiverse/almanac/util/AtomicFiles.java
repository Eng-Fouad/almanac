package io.quarkiverse.almanac.util;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/// Writes files so that readers never observe a partially written file: the content goes to a temporary file in
/// the target directory, which is then moved over the target. Parent directories are created as needed.
public final class AtomicFiles {

    /// Windows refuses to replace a file another thread or process has open; such moves are retried briefly.
    private static final int MOVE_ATTEMPTS = 5;

    private AtomicFiles() {
    }

    public static void writeString(Path target, String content) throws IOException {
        write(target, content.getBytes(StandardCharsets.UTF_8));
    }

    public static void write(Path target, byte[] content) throws IOException {
        Path dir = target.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, ".tmp-", ".part");
        try {
            Files.write(tmp, content);
            move(tmp, target);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static void move(Path source, Path target) throws IOException {
        for (int attempt = 1; ; attempt++) {
            try {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                return;
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (AccessDeniedException e) {
                if (attempt == MOVE_ATTEMPTS) {
                    throw e;
                }
                pause(attempt * 50L);
            }
        }
    }

    private static void pause(long millis) throws InterruptedIOException {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("interrupted while replacing a file");
        }
    }
}
