package io.quarkiverse.almanac.maven;

import io.quarkiverse.almanac.ScratchDir;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Shared pacers in three JVMs on one lock file, each with several virtual threads: one request at a time across the
/// processes, each starting a pause after the previous one ended.
class PacerProcessesTest {

    private static final int PROCESSES = 3;
    private static final int THREADS = 3;
    private static final int REQUESTS = 3;
    private static final String PAUSE = "0.1-0.12";
    /// The shortest pause, less the resolution of the wall clocks of the pacer and of the log (15.6 ms each on
    /// Windows at worst).
    private static final long MIN_GAP_MICROS = 50_000;

    @Test
    void processesTakeTurnsThroughTheLockFile() throws Exception {
        try (ScratchDir dir = ScratchDir.create()) {
            Path lockFile = dir.resolve("cache").resolve(Pacer.LOCK_FILE);
            Path marker = dir.resolve("in-flight");
            Path start = dir.resolve("start");
            String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            List<Process> children = new ArrayList<>();
            List<Path> logs = new ArrayList<>();
            try {
                for (int i = 1; i < PROCESSES; i++) {
                    Path log = dir.resolve("worker-" + i + ".log");
                    logs.add(log);
                    children.add(new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                            PacerWorker.class.getName(), lockFile.toString(), marker.toString(), log.toString(),
                            "" + THREADS, "" + REQUESTS, PAUSE, dir.resolve("ready-" + i).toString(), start.toString())
                            .redirectErrorStream(true)
                            .redirectOutput(dir.resolve("worker-" + i + ".out").toFile())
                            .start());
                }
                for (int i = 1; i < PROCESSES; i++) {
                    Path ready = dir.resolve("ready-" + i);
                    for (int wait = 0; wait < 600 && !Files.exists(ready); wait++) {
                        Thread.sleep(50);
                    }
                    assertTrue(Files.exists(ready), "worker " + i + " did not start: " + output(dir, i));
                }
                Files.writeString(start, "go\n");
                List<long[]> spans = spans(PacerWorker.run(lockFile, marker, THREADS, REQUESTS,
                        Pacer.Pause.parse(PAUSE)), 0);
                for (int i = 1; i < PROCESSES; i++) {
                    Process child = children.get(i - 1);
                    assertTrue(child.waitFor(60, TimeUnit.SECONDS), "worker " + i + " did not finish");
                    assertEquals(0, child.exitValue(), output(dir, i));
                    spans.addAll(spans(Files.readAllLines(logs.get(i - 1)), i));
                }

                assertEquals(PROCESSES * THREADS * REQUESTS, spans.size());
                spans.sort(Comparator.comparingLong(span -> span[0]));
                int switches = 0;
                for (int i = 1; i < spans.size(); i++) {
                    long gap = spans.get(i)[0] - spans.get(i - 1)[1];
                    assertTrue(gap >= MIN_GAP_MICROS, "requests " + (i - 1) + " and " + i + " only " + gap
                            + " microseconds apart");
                    switches += spans.get(i)[2] != spans.get(i - 1)[2] ? 1 : 0;
                }
                assertTrue(switches >= PROCESSES - 1, "the processes took turns: " + switches + " switches");
            } finally {
                children.forEach(Process::destroyForcibly);
                for (Process child : children) {
                    child.waitFor(10, TimeUnit.SECONDS);
                }
            }
        }
    }

    /// The `start end` lines of one process as `[start, end, process]`; any other line fails the test.
    private static List<long[]> spans(List<String> lines, int process) {
        List<long[]> spans = new ArrayList<>();
        for (String line : lines) {
            String[] fields = line.split(" ");
            assertTrue(fields.length == 2 && !line.startsWith("OVERLAP") && !line.startsWith("ERROR"),
                    "process " + process + ": " + line);
            spans.add(new long[] {Long.parseLong(fields[0]), Long.parseLong(fields[1]), process});
        }
        return spans;
    }

    private static String output(ScratchDir dir, int process) {
        try {
            return Files.readString(dir.resolve("worker-" + process + ".out"));
        } catch (IOException e) {
            return "(no output: " + e + ")";
        }
    }
}
