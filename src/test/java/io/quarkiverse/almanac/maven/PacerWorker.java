package io.quarkiverse.almanac.maven;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/// Requests of several virtual threads through one shared [Pacer], for [PacerProcessesTest], which runs it in this
/// JVM and in others: each request creates a marker file while it runs (finding it already there means two requests
/// overlap) and is logged as `start end` in wall-clock microseconds.
public final class PacerWorker {

    private PacerWorker() {
    }

    /// Arguments: lock file, marker file, log file, threads, requests per thread, pause, ready file, start file. Writes
    /// the ready file, waits for the start file, runs, and writes the log.
    public static void main(String[] args) throws Exception {
        Files.writeString(Path.of(args[6]), "ready\n");
        Path start = Path.of(args[7]);
        for (int i = 0; i < 3000 && !Files.exists(start); i++) {
            Thread.sleep(10);
        }
        List<String> lines = run(Path.of(args[0]), Path.of(args[1]), Integer.parseInt(args[3]),
                Integer.parseInt(args[4]), Pacer.Pause.parse(args[5]));
        Files.write(Path.of(args[2]), lines);
    }

    /// @return one `start end` line per request, plus `OVERLAP` and `ERROR …` lines
    static List<String> run(Path lockFile, Path marker, int threads, int requests, Pacer.Pause pause)
            throws InterruptedException {
        Pacer pacer = Pacer.shared(pause, lockFile);
        List<String> lines = Collections.synchronizedList(new ArrayList<>());
        List<Thread> started = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            started.add(Thread.ofVirtual().start(() -> {
                for (int i = 0; i < requests; i++) {
                    try {
                        pacer.run(turn -> {
                            long begin = micros();
                            try {
                                Files.createFile(marker);
                            } catch (FileAlreadyExistsException e) {
                                lines.add("OVERLAP");
                            }
                            try {
                                Thread.sleep(10);
                            } catch (InterruptedException e) {
                                throw new InterruptedIOException();
                            }
                            Files.deleteIfExists(marker);
                            lines.add(begin + " " + micros());
                            return null;
                        });
                    } catch (IOException | RuntimeException e) {
                        lines.add("ERROR " + e);
                    }
                }
            }));
        }
        for (Thread thread : started) {
            thread.join();
        }
        return List.copyOf(lines);
    }

    private static long micros() {
        Instant now = Instant.now();
        return now.getEpochSecond() * 1_000_000L + now.getNano() / 1000;
    }
}
