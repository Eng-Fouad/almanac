package io.quarkiverse.almanac.model;

/// One managed dependency of a BOM.
///
/// @param id `groupId:artifactId`, plus `:type` when the type is not `jar`, plus `:classifier`
///           when present (a classifier equal to the BOM's own version is written as `${quarkus.version}`)
/// @param v  the managed version
public record ManagedDep(String id, String v) {}
