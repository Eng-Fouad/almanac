package io.quarkiverse.almanac.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/// One config property of a release.
///
/// @param name         display path, e.g. `quarkus.hibernate-orm."persistence-unit-name".active`
/// @param key          `ConfigKeys.canonical(name)`
/// @param aliases      other display paths of the same property (e.g. named-map variants), may be empty
/// @param ext          extension artifactId, e.g. `quarkus-hibernate-orm`
/// @param type         processor "typeDescription": boolean, string, int, Duration, MemorySize, host:port, Level,
///                     `<EnumSimpleName>`…
/// @param list         collection-valued (List/Set/array)
/// @param optional     whether the property is optional
/// @param map          leaf is a `Map<String, X>` (key segment appears in the name)
/// @param defaultValue as documented, null if none
/// @param phase        BUILD_TIME | BUILD_AND_RUN_TIME_FIXED | RUN_TIME | BOOTSTRAP
/// @param deprecated   null = not deprecated, "" = deprecated without since, "3.22" = since
/// @param enumValues   accepted config values, null when unknown / not an enum
/// @param desc         `Descriptions.firstSentence(javadoc)`, may be null
/// @param source       `fqcn#member` for debugging
public record ConfigProperty(
        String name,
        String key,
        List<String> aliases,
        String ext,
        String type,
        boolean list,
        boolean optional,
        boolean map,
        @JsonProperty("default") String defaultValue,
        String phase,
        String deprecated,
        @JsonProperty("enum") List<String> enumValues,
        String desc,
        String source) {}
