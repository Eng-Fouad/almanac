package io.quarkiverse.almanac.model;

import java.util.List;

/// `work/snapshots/<v>/bom.json`.
///
/// @param version the Quarkus release
/// @param date    UTC `yyyy-MM-dd` of the BOM pom's `Last-Modified`
/// @param deps    managed dependencies sorted by id
public record BomSnapshot(String version, String date, List<ManagedDep> deps) {}
