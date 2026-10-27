package io.quarkiverse.almanac.model;

import java.util.List;

/// `work/snapshots/<v>/config.json` (and `config-model.json` / `config-bytecode.json`).
///
/// @param version    the Quarkus release
/// @param source     `"model"` or `"bytecode"`
/// @param extractor  identifier of the reader that produced the snapshot
/// @param roots      number of config roots found
/// @param warnings   problems noticed while reading (duplicates, unreadable entries, …)
/// @param properties properties sorted by key, unique by key
public record ConfigSnapshot(String version, String source, String extractor,
        int roots, List<String> warnings, List<ConfigProperty> properties) {}
