package io.quarkiverse.almanac.config.bytecode;

import org.objectweb.asm.tree.AnnotationNode;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/// The annotations the processor reads, by type descriptor, and access to explicit element values of ASM
/// [AnnotationNode]s. Like `Element#getAnnotationMirrors`, only declaration annotations count
/// (`Runtime[In]VisibleAnnotations`), never type annotations. Element
/// values that a class file does not hold are the annotation's defaults, which callers apply.
final class Annotations {

    static final String CONFIG_ROOT = "Lio/quarkus/runtime/annotations/ConfigRoot;";
    static final String CONFIG_ITEM = "Lio/quarkus/runtime/annotations/ConfigItem;";
    static final String CONFIG_GROUP = "Lio/quarkus/runtime/annotations/ConfigGroup;";
    static final String CONVERT_WITH = "Lio/quarkus/runtime/annotations/ConvertWith;";
    static final String DEFAULT_CONVERTER = "Lio/quarkus/runtime/annotations/DefaultConverter;";
    static final String CONFIG_DOC_IGNORE = "Lio/quarkus/runtime/annotations/ConfigDocIgnore;";
    static final String CONFIG_DOC_MAP_KEY = "Lio/quarkus/runtime/annotations/ConfigDocMapKey;";
    static final String CONFIG_DOC_DEFAULT = "Lio/quarkus/runtime/annotations/ConfigDocDefault;";
    static final String CONFIG_DOC_SECTION = "Lio/quarkus/runtime/annotations/ConfigDocSection;";
    static final String CONFIG_DOC_ENUM_VALUE = "Lio/quarkus/runtime/annotations/ConfigDocEnumValue;";
    static final String CONFIG_DOC_ENUM = "Lio/quarkus/runtime/annotations/ConfigDocEnum;";
    static final String CONFIG_MAPPING = "Lio/smallrye/config/ConfigMapping;";
    static final String WITH_NAME = "Lio/smallrye/config/WithName;";
    static final String WITH_PARENT_NAME = "Lio/smallrye/config/WithParentName;";
    static final String WITH_DEFAULT = "Lio/smallrye/config/WithDefault;";
    static final String WITH_UNNAMED_KEY = "Lio/smallrye/config/WithUnnamedKey;";
    static final String WITH_CONVERTER = "Lio/smallrye/config/WithConverter;";
    static final String DEPRECATED = "Ljava/lang/Deprecated;";

    private Annotations() {
    }

    /// Declaration annotations by type descriptor (visible ones win over invisible ones of the same type).
    static Map<String, AnnotationNode> declared(List<AnnotationNode> visible, List<AnnotationNode> invisible) {
        Map<String, AnnotationNode> byType = new HashMap<>();
        if (invisible != null) {
            for (AnnotationNode annotation : invisible) {
                byType.put(annotation.desc, annotation);
            }
        }
        if (visible != null) {
            for (AnnotationNode annotation : visible) {
                byType.put(annotation.desc, annotation);
            }
        }
        return byType;
    }

    /// An explicit element value as ASM stores it, or null when the class file does not hold it.
    static Object value(AnnotationNode annotation, String element) {
        if (annotation == null || annotation.values == null) {
            return null;
        }
        List<Object> values = annotation.values;
        for (int i = 0; i + 1 < values.size(); i += 2) {
            if (element.equals(values.get(i))) {
                return values.get(i + 1);
            }
        }
        return null;
    }

    /// An explicit `String` (or other scalar) element value as text, or null.
    static String string(AnnotationNode annotation, String element) {
        Object value = value(annotation, element);
        return value == null ? null : value.toString();
    }

    /// The first explicit element value as text, like the processor's
    /// `getElementValues().values().iterator().next().getValue().toString()`; null when there is none.
    static String firstValue(AnnotationNode annotation) {
        if (annotation == null || annotation.values == null || annotation.values.size() < 2) {
            return null;
        }
        return text(annotation.values.get(1));
    }

    /// The constant name of an explicit enum element value, or null.
    static String enumConstant(AnnotationNode annotation, String element) {
        return value(annotation, element) instanceof String[] constant && constant.length == 2 ? constant[1] : null;
    }

    private static String text(Object value) {
        if (value instanceof String[] constant && constant.length == 2) {
            return constant[1];
        }
        return value.toString();
    }
}
