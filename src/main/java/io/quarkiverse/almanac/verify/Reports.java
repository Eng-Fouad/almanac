package io.quarkiverse.almanac.verify;

import io.quarkiverse.almanac.util.AtomicFiles;
import io.quarkiverse.almanac.util.Json;
import io.quarkiverse.almanac.util.Log;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.List;

/// Where the verify commands put a report: stdout, and optionally a JSON file and a text file.
final class Reports {

    private Reports() {
    }

    /// Prints the report lines to the command's output, then writes `json` (the report record, pretty-printed)
    /// and `text` (the same lines) when given.
    static void output(CommandSpec spec, List<String> lines, Object report, Path json, Path text) throws IOException {
        PrintWriter out = spec.commandLine().getOut();
        lines.forEach(out::println);
        out.flush();
        if (json != null) {
            Json.writePretty(json, report);
            Log.info("verify: wrote %s", json);
        }
        if (text != null) {
            AtomicFiles.writeString(text, String.join("\n", lines) + "\n");
            Log.info("verify: wrote %s", text);
        }
    }

    /// Fails with a usage error unless `0 <= value <= 1`.
    static double ratio(CommandSpec spec, String option, double value) {
        if (!(value >= 0 && value <= 1)) {
            throw new CommandLine.ParameterException(spec.commandLine(),
                    option + " must be between 0 and 1, e.g. 0.99 (was " + value + ")");
        }
        return value;
    }

    /// Fails with a usage error for a negative example count.
    static int examples(CommandSpec spec, int value) {
        if (value < 0) {
            throw new CommandLine.ParameterException(spec.commandLine(), "--examples must not be negative");
        }
        return value;
    }
}
