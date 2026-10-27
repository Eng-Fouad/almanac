package io.quarkiverse.almanac.config.bytecode;

import java.util.List;

/// One property of the processor's doc model, resolved from bytecode. Field
/// names follow `quarkus-config-model.json`.
///
/// @param path              the property path, e.g. `quarkus.hibernate-orm.active`
/// @param additionalPaths   other paths of the property (unnamed map keys), in model order
/// @param typeDescription   e.g. `boolean`, `string`, `Duration`, `Map<String,String>`
/// @param map               the leaf is a map (its key is the last path segment)
/// @param list              a list, set or array
/// @param optional          optional, always true for a leaf map
/// @param mapKey            the key name of a leaf map, null otherwise
/// @param unnamedMapKey     the leaf map has `@WithUnnamedKey`
/// @param withinMap         some enclosing group member is a map
/// @param converted         the member has a converter
/// @param isEnum            the (unwrapped) type is an enum
/// @param enumValues        accepted values in declaration order; null when not an enum
/// @param defaultValue      the documented default, null when none
/// @param deprecated        null = not deprecated, `""` = deprecated without since, else the since text
/// @param phase             BUILD_TIME, BUILD_AND_RUN_TIME_FIXED, RUN_TIME or BOOTSTRAP
/// @param sourceType        canonical name of the type that declares the member
/// @param sourceElementName the field or method name
record LeafProperty(String path, List<String> additionalPaths, String typeDescription, boolean map, boolean list,
        boolean optional, String mapKey, boolean unnamedMapKey, boolean withinMap, boolean converted, boolean isEnum,
        List<String> enumValues, String defaultValue, String deprecated, String phase, String sourceType,
        String sourceElementName) {
}
