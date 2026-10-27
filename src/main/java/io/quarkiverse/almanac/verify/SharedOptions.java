package io.quarkiverse.almanac.verify;

import io.quarkiverse.almanac.maven.Http;
import io.quarkiverse.almanac.maven.Pacer;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;

import java.net.URI;
import java.nio.file.Path;

/// The values of the options every command shares (`io.quarkiverse.almanac.cli.CommonOptions`, mixed into each verify
/// subcommand) that verify uses. They are read through picocli because the option fields are package-private to
/// `io.quarkiverse.almanac.cli`.
///
/// @param work        working folder: `cache/` and `snapshots/`
/// @param repo        Maven repository (for the release list)
/// @param concurrency maximum parallel HTTP requests
/// @param pause       random pause between two requests
/// @param offline     cached files only
/// @param verbose     log details
record SharedOptions(Path work, URI repo, int concurrency, Pacer.Pause pause, boolean offline, boolean verbose) {

    static SharedOptions of(CommandSpec spec) {
        return new SharedOptions(value(spec, "--work"), value(spec, "--repo"), value(spec, "--concurrency"),
                value(spec, "--pause"), value(spec, "--offline"), value(spec, "--verbose"));
    }

    /// A client that waits its turn with the other commands on the same cache.
    ///
    /// @throws IllegalArgumentException when the pause is too short for the repository ([Http#checkPause])
    Http http() {
        Http.checkPause(pause, repo);
        return new Http(concurrency, offline, Pacer.forCache(pause, cache()));
    }

    Path snapshots() {
        return work.resolve("snapshots");
    }

    Path cache() {
        return work.resolve("cache");
    }

    private static <T> T value(CommandSpec spec, String name) {
        OptionSpec option = spec.findOption(name);
        if (option == null) {
            throw new IllegalStateException("command " + spec.qualifiedName() + " has no option " + name);
        }
        return option.getValue();
    }
}
