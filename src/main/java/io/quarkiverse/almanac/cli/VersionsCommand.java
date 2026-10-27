package io.quarkiverse.almanac.cli;

import io.quarkiverse.almanac.maven.QuarkusVersion;
import io.quarkiverse.almanac.maven.VersionSelector;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;

/// `versions`: prints a selection of releases with their flags.
@Command(name = "versions", description = "Print the selected Quarkus releases (final/pre, stream, LTS).")
public final class VersionsCommand implements Callable<Integer> {

    @Mixin
    CommonOptions common;

    @Spec
    CommandSpec spec;

    @Option(names = "--select", defaultValue = "all", paramLabel = "all|streams|LIST",
            description = "all, streams or a comma-separated version list (default: ${DEFAULT-VALUE}).")
    String select;

    @Override
    public Integer call() throws IOException {
        try (Workspace workspace = Workspace.open(common)) {
            List<QuarkusVersion> versions = workspace.select(select);
            PrintWriter out = spec.commandLine().getOut();
            for (QuarkusVersion version : versions) {
                out.println(String.format(Locale.ROOT, "%-14s %-5s %-5s %s", version,
                        version.isFinal() ? "final" : "pre", version.stream(),
                        VersionSelector.isLts(version) ? "LTS" : "").stripTrailing());
            }
            long finals = versions.stream().filter(QuarkusVersion::isFinal).count();
            out.printf("%d releases (%d final, %d pre-release)%n", versions.size(), finals, versions.size() - finals);
            out.flush();
        }
        return 0;
    }
}
