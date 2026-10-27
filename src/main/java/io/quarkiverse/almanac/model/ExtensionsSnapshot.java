package io.quarkiverse.almanac.model;

import java.util.List;

/// `work/snapshots/<v>/extensions.json`.
///
/// @param version    the Quarkus release
/// @param extensions extensions sorted by id
public record ExtensionsSnapshot(String version, List<ExtensionInfo> extensions) {}
