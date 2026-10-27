package io.quarkiverse.almanac.verify;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkiverse.almanac.ScratchDir;
import io.quarkiverse.almanac.util.AtomicFiles;
import io.quarkiverse.almanac.util.Json;
import io.quarkiverse.almanac.util.SnapshotStore;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The verify commands end to end on a scratch work folder (no network: pages come from --page-file).
class VerifyCommandTest {

    /// Exit code and standard output of one run.
    private record Run(int exitCode, String out) {
    }

    @Test
    void snapshotsPrintsTheSummaryWritesJsonAndFailsBelowTheThresholds() throws Exception {
        try (ScratchDir dir = ScratchDir.create()) {
            SnapshotStore store = new SnapshotStore(dir.resolve("snapshots"));
            store.write("3.25.4", SnapshotStore.CONFIG_BYTECODE, SnapshotComparisonTest.bytecode());
            store.write("3.25.4", SnapshotStore.CONFIG_MODEL, SnapshotComparisonTest.model());
            Path json = dir.resolve("out/report.json");
            Path text = dir.resolve("out/report.txt");

            Run failing = run("snapshots", "3.25.4:model", "3.25.4:bytecode", "--work", dir.path().toString(),
                    "--json", json.toString(), "--report", text.toString());
            assertEquals(1, failing.exitCode());
            assertTrue(failing.out().startsWith("a             3.25.4:model: 3.25.4, model (test), 4 properties"),
                    failing.out());
            assertTrue(failing.out().contains("result        FAIL: key agreement 66.66 % < 99.00 %"), failing.out());
            JsonNode report = Json.readTree(Files.readAllBytes(json));
            assertEquals(1, report.at("/keys/viaAlias").asInt());
            assertFalse(report.get("pass").asBoolean());
            String textReport = Files.readString(text);
            assertTrue(textReport.startsWith("a             3.25.4:model"), textReport);
            assertTrue(textReport.endsWith("\n") && !textReport.contains("```"), textReport);

            Run passing = run("snapshots", "3.25.4:model", "3.25.4:bytecode", "--work", dir.path().toString(),
                    "--min-key-agreement", "0.6", "--min-field-agreement", "0.6", "--examples", "0");
            assertEquals(0, passing.exitCode(), passing.out());
            assertTrue(passing.out().contains("result        PASS"), passing.out());
        }
    }

    @Test
    void officialComparesWithAPageFile() throws Exception {
        try (ScratchDir dir = ScratchDir.create()) {
            new SnapshotStore(dir.resolve("snapshots"))
                    .write("3.20.6", SnapshotStore.CONFIG, OfficialComparisonTest.newGeneratorSnapshot());
            Path page = dir.resolve("all-config.html");
            AtomicFiles.writeString(page, Fixtures.html("new-generator.html"));
            Path json = dir.resolve("official.json");

            Run failing = run("official", "3.20.6", "--work", dir.path().toString(), "--page-file", page.toString(),
                    "--json", json.toString());
            assertEquals(1, failing.exitCode(), failing.out());
            assertTrue(failing.out().contains(
                    "names         10 / 11 page keys are ours (90.90 %), 1 missing from ours"), failing.out());
            assertEquals(11, Json.readTree(Files.readAllBytes(json)).at("/names/total").asInt());

            Run passing = run("official", "3.20.6", "--work", dir.path().toString(), "--page-file", page.toString(),
                    "--min-name-agreement", "0.9", "--min-phase-agreement", "0.8");
            assertEquals(0, passing.exitCode(), passing.out());

            Run otherStream = run("official", "3.20.6", "--work", dir.path().toString(), "--page-file",
                    page.toString(), "--stream", "3.27", "--min-name-agreement", "0", "--min-phase-agreement", "0");
            assertEquals(0, otherStream.exitCode(), otherStream.out());
            assertTrue(otherStream.out().contains(
                    "warning       the page documents 3.20 (version selector), not stream 3.27"), otherStream.out());
        }
    }

    @Test
    void officialWithoutSnapshotStillSummarisesThePage() throws Exception {
        try (ScratchDir dir = ScratchDir.create()) {
            Path page = dir.resolve("all-config.html");
            AtomicFiles.writeString(page, Fixtures.html("old-generator.html"));

            Run run = run("official", "3.2.12.Final", "--work", dir.path().toString(), "--page-file",
                    page.toString());
            assertEquals(1, run.exitCode());
            assertTrue(run.out().startsWith("page          " + page + ": old generator, documents 3.2 (page title), "
                    + "6 rows, 7 paths (7 keys), 2 fixed at build time"), run.out());
        }
    }

    @Test
    void usageErrors() throws Exception {
        try (ScratchDir dir = ScratchDir.create()) {
            assertEquals(2, run().exitCode());
            assertEquals(2, run("snapshots", "a").exitCode());
            assertEquals(2, run("snapshots", "a", "b", "--work", dir.path().toString(), "--min-key-agreement", "2")
                    .exitCode());
            assertEquals(2, run("official", "not-a-version", "--work", dir.path().toString()).exitCode());
        }
    }

    private static Run run(String... args) {
        StringWriter out = new StringWriter();
        CommandLine commandLine = new CommandLine(new VerifyCommand());
        commandLine.setOut(new PrintWriter(out));
        commandLine.setErr(new PrintWriter(new StringWriter()));
        int exitCode = commandLine.execute(args);
        return new Run(exitCode, out.toString().replace("\r\n", "\n"));
    }
}
