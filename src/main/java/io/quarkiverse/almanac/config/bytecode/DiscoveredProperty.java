package io.quarkiverse.almanac.config.bytecode;

import io.quarkiverse.almanac.config.sourcedoc.DocAnnotations;
import org.objectweb.asm.tree.AnnotationNode;

import java.util.Map;

/// A config member before path resolution: the processor's `DiscoveryConfigProperty`. Built from the member's declaration annotations; the `@ConfigDoc*` annotations that pre-3.14 class
/// files lack come from `doc` when the class file has none.
///
/// @param name                      path segment(s): hyphenated member name, an explicit name, or `<<parent>>`
/// @param sourceType                canonical name of the declaring type
/// @param sourceElementName         field or method name
/// @param type                      the resolved member type
/// @param defaultValue              `@WithDefault` / `@ConfigItem(defaultValue)` after `build()` (implicit primitive
///                                  default, Duration normalisation), null when none
/// @param defaultValueForDoc        `@ConfigDocDefault` / `@ConfigItem(defaultValueDocumentation)`
/// @param deprecated                null = not deprecated, `""` = without since, else `@Deprecated(since)`
/// @param mapKey                    the key name when the type is a map, else null
/// @param unnamedMapKey             a map with `@WithUnnamedKey`
/// @param converted                 `@WithConverter`, `@ConvertWith` or `@DefaultConverter`
/// @param enforceHyphenateEnumValue `@ConfigDocEnum(enforceHyphenateValues = true)`
/// @param section                   `@ConfigDocSection`: a group member whose items the model nests in a section, which
///                                  only changes the order of the model's properties
record DiscoveredProperty(String name, String sourceType, String sourceElementName, ResolvedType type,
        String defaultValue, String defaultValueForDoc, String deprecated, String mapKey, boolean unnamedMapKey,
        boolean converted, boolean enforceHyphenateEnumValue, boolean section) {

    private static final Map<String, String> PRIMITIVE_DEFAULTS = Map.of("int", "0", "byte", "0", "char", "",
            "short", "0", "long", "0l", "float", "0f", "double", "0d", "boolean", "false");

    /// A method of a `@ConfigMapping` interface or group (`ConfigMappingListener#onEnclosedMethod`).
    static DiscoveredProperty ofMethod(String sourceType, String method, Map<String, AnnotationNode> annotations,
            DocAnnotations doc, ResolvedType type) {
        String name = ConfigNames.hyphenate(method);
        AnnotationNode withName = annotations.get(Annotations.WITH_NAME);
        if (withName != null && Annotations.firstValue(withName) != null) {
            name = Annotations.firstValue(withName);
        }
        if (annotations.containsKey(Annotations.WITH_PARENT_NAME)) {
            name = ConfigNames.PARENT;
        }
        String defaultValue = Annotations.firstValue(annotations.get(Annotations.WITH_DEFAULT));
        String docDefault = docDefault(annotations, doc);
        String mapKey = null;
        boolean unnamedMapKey = false;
        if (type.map()) {
            mapKey = mapKey(method, annotations, doc);
            unnamedMapKey = annotations.containsKey(Annotations.WITH_UNNAMED_KEY);
        }
        boolean converted = annotations.containsKey(Annotations.WITH_CONVERTER);
        return build(name, sourceType, method, type, defaultValue, docDefault, annotations, doc, mapKey,
                unnamedMapKey, converted);
    }

    /// A field of a legacy `@ConfigRoot` class or group (`LegacyConfigRootListener#onEnclosedField`).
    static DiscoveredProperty ofField(String sourceType, String field, Map<String, AnnotationNode> annotations,
            DocAnnotations doc, ResolvedType type) {
        String name = ConfigNames.hyphenate(field);
        String defaultValue = null;
        String docDefault = null;
        AnnotationNode configItem = annotations.get(Annotations.CONFIG_ITEM);
        if (configItem != null) {
            String itemName = Annotations.string(configItem, "name");
            if (itemName != null && !ConfigNames.HYPHENATED_ELEMENT_NAME.equals(itemName)) {
                name = itemName;
            }
            String itemDefault = Annotations.string(configItem, "defaultValue");
            if (itemDefault != null && !ConfigNames.NO_DEFAULT.equals(itemDefault)) {
                defaultValue = itemDefault;
            }
            String itemDocDefault = Annotations.string(configItem, "defaultValueDocumentation");
            // @ConfigDocDefault counts on fields only together with @ConfigItem
            docDefault = itemDocDefault != null && !itemDocDefault.isEmpty() ? itemDocDefault
                    : docDefault(annotations, doc);
        }
        String mapKey = type.map() ? mapKey(field, annotations, doc) : null;
        boolean converted = annotations.containsKey(Annotations.DEFAULT_CONVERTER)
                || annotations.containsKey(Annotations.CONVERT_WITH);
        return build(name, sourceType, field, type, defaultValue, docDefault, annotations, doc, mapKey, false,
                converted);
    }

    /// `@ConfigDocDefault` from the class file, else from the doc annotation source.
    private static String docDefault(Map<String, AnnotationNode> annotations, DocAnnotations doc) {
        AnnotationNode docDefault = annotations.get(Annotations.CONFIG_DOC_DEFAULT);
        return docDefault != null ? Annotations.firstValue(docDefault) : doc.defaultValue();
    }

    /// `@ConfigDocMapKey` from the class file, else from the doc annotation source, else the member name.
    private static String mapKey(String member, Map<String, AnnotationNode> annotations, DocAnnotations doc) {
        AnnotationNode mapKey = annotations.get(Annotations.CONFIG_DOC_MAP_KEY);
        if (mapKey != null && Annotations.firstValue(mapKey) != null) {
            return Annotations.firstValue(mapKey);
        }
        return doc.mapKey() != null ? doc.mapKey() : ConfigNames.hyphenate(member);
    }

    /// Common annotations (`handleCommonPropertyAnnotations`) and `Builder#build()`.
    private static DiscoveredProperty build(String name, String sourceType, String member, ResolvedType type,
            String defaultValue, String defaultValueForDoc, Map<String, AnnotationNode> annotations,
            DocAnnotations doc, String mapKey, boolean unnamedMapKey, boolean converted) {
        String deprecated = null;
        AnnotationNode deprecation = annotations.get(Annotations.DEPRECATED);
        if (deprecation != null) {
            String since = Annotations.string(deprecation, "since");
            deprecated = since == null ? "" : since;
        }
        boolean section = annotations.containsKey(Annotations.CONFIG_DOC_SECTION) || doc.section();
        AnnotationNode docEnum = annotations.get(Annotations.CONFIG_DOC_ENUM);
        boolean enforceHyphenate = Boolean.TRUE.equals(Annotations.value(docEnum, "enforceHyphenateValues"));

        String value = defaultValue;
        if (type.primitive() && value == null) {
            value = PRIMITIVE_DEFAULTS.get(type.qualifiedName());
        }
        if (type.duration() && value != null && !value.isBlank()) {
            value = ConfigNames.normalizeDuration(value);
        }
        return new DiscoveredProperty(name, sourceType, member, type, value, defaultValueForDoc, deprecated, mapKey,
                unnamedMapKey, converted, enforceHyphenate, section);
    }
}
