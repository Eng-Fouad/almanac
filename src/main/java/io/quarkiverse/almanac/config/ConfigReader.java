package io.quarkiverse.almanac.config;

import io.quarkiverse.almanac.model.ConfigSnapshot;

import java.io.IOException;

/// Extracts the config properties of one Quarkus release from its jars.
///
/// Implementations: [ModelJsonConfigReader] (official doc model, releases 3.25+) and `bytecode.BytecodeConfigReader`
/// (reconstruction from bytecode for releases without the model). [ConfigExtractor] chooses between them.
///
/// Contract for the returned [ConfigSnapshot]: `version` is [VersionJars#quarkusVersion()], `source` is `"model"` or
/// `"bytecode"`, `extractor` is [#id()], and the properties are sorted by key and unique by key, as produced by
/// [ConfigProperties#sortedUnique]. Problems that do not prevent reading (an unreadable entry, a duplicate key, …) go
/// to `warnings` instead of failing the read.
public interface ConfigReader {

    /// Stable identifier of the reader and its algorithm version, recorded as `ConfigSnapshot.extractor`.
    String id();

    /// Reads the config of the release whose jars are given.
    ConfigSnapshot read(VersionJars jars) throws IOException;
}
