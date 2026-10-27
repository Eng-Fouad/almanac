package io.quarkiverse.almanac.cli;

import io.quarkiverse.almanac.Main;
import io.quarkiverse.almanac.ScratchDir;
import io.quarkiverse.almanac.config.sourcedoc.SourceDocCheck;
import io.quarkiverse.almanac.maven.Pacer;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// `--pause` against Maven Central: the commands (and the source doc check) refuse a pause under 2 s before they open
/// their cache, offline included; any pause goes for another repository.
class PauseOptionTest {

    /// Exit code and log (standard error, with the error of a failed command) of one run.
    private record Run(int exitCode, String log) {
    }

    @Test
    void theCommandsRefuseTooShortAPauseForMavenCentral() throws Exception {
        try (ScratchDir dir = ScratchDir.create()) {
            String work = dir.resolve("work").toString();
            String dataset = dir.resolve("dataset").toString();
            for (String pause : List.of("0", "1.5", "0-5")) {
                Run versions = run("versions", "--offline", "--pause", pause, "--work", work, "--dataset", dataset);
                assertEquals(1, versions.exitCode(), versions.log());
                assertTrue(versions.log().contains("a pause of " + pause + " s is too short for Maven Central "
                        + "(repo1.maven.org), which needs at least 2 s between requests"), versions.log());
            }
            Run fullyQualified = run("bom", "--offline", "--pause", "0", "--repo", "https://repo1.maven.org./maven2/",
                    "--work", work, "--dataset", dataset);
            assertEquals(1, fullyQualified.exitCode(), fullyQualified.log());
            assertTrue(fullyQualified.log().contains("too short for Maven Central"), fullyQualified.log());
            Run verify = run("verify", "official", "3.20.6", "--offline", "--pause", "1", "--work", work);
            assertEquals(1, verify.exitCode(), verify.log());
            assertTrue(verify.log().contains("too short for Maven Central"), verify.log());
            Run check = run(new SourceDocCheck(), "3.8.6", "--offline", "--pause", "0", "--work", work);
            assertEquals(1, check.exitCode(), check.log());
            assertTrue(check.log().contains("too short for Maven Central"), check.log());

            Run other = run("versions", "--offline", "--pause", "0", "--repo", "http://127.0.0.1:9/maven2/", "--work",
                    work, "--dataset", dataset);
            assertFalse(other.log().contains("too short"), other.log());
            assertFalse(Files.exists(Path.of(work).resolve("cache").resolve(Pacer.LOCK_FILE)));
        }
    }

    private static Run run(String... args) {
        return run(new Main(), args);
    }

    private static Run run(Object root, String... args) {
        ByteArrayOutputStream log = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        System.setErr(new PrintStream(log, true, StandardCharsets.UTF_8));
        try {
            CommandLine commandLine = new CommandLine(root).setCaseInsensitiveEnumValuesAllowed(true)
                    .setExecutionExceptionHandler((e, command, parseResult) -> {
                        System.err.println("error: " + e.getMessage());
                        return 1;
                    });
            commandLine.setOut(new PrintWriter(new StringWriter()));
            commandLine.setErr(new PrintWriter(new StringWriter()));
            int exitCode = commandLine.execute(args);
            return new Run(exitCode, log.toString(StandardCharsets.UTF_8).replace("\r\n", "\n"));
        } finally {
            System.setErr(originalErr);
        }
    }
}
