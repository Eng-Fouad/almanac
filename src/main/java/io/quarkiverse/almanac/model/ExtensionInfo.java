package io.quarkiverse.almanac.model;

import java.util.List;

/// Metadata of one extension, from its `META-INF/quarkus-extension.yaml`.
///
/// @param id             artifactId
/// @param name           display name
/// @param description    short description
/// @param status         first status, lower case (stable, preview, experimental, deprecated, …)
/// @param categories     categories
/// @param guide          guide URL
/// @param unlisted       hidden from extension catalogs
/// @param configPrefixes config prefixes without the trailing dot, e.g. `quarkus.hibernate-orm`
/// @param relocatedTo    `g:a` when the artifact is a Maven relocation (then only id is set besides it)
public record ExtensionInfo(String id, String name, String description, String status,
        List<String> categories, String guide, boolean unlisted, List<String> configPrefixes, String relocatedTo) {}
