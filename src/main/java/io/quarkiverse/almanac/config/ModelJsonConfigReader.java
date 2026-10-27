package io.quarkiverse.almanac.config;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkiverse.almanac.model.ConfigProperty;
import io.quarkiverse.almanac.model.ConfigSnapshot;
import io.quarkiverse.almanac.util.ConfigKeys;
import io.quarkiverse.almanac.util.Descriptions;
import io.quarkiverse.almanac.util.Json;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/// Reads the official config documentation model that Quarkus 3.25+ ships in every jar with config roots
/// ({@value #MODEL_ENTRY}, descriptions in {@value #JAVADOC_ENTRY}). The models of all jars are united and their
/// sections flattened; each property maps to a [ConfigProperty] as follows: name = `path.property`, aliases =
/// `additionalPaths[].property`, type = `typeDescription`, `list` / `optional` / `map` flags, default = `defaultValue`,
/// `phase`, deprecated = `deprecation.since` (or `""` when deprecated without a version), enum =
/// `enumAcceptedValues.values[*].configValue` in order, desc = first sentence of the javadoc element
/// `sourceType + "." + sourceElementName` (raw Javadoc converted with [Descriptions#fromJavadoc], leftover Javadoc tags
/// in asciidoc replaced), ext = the config root's `extension.artifactId`, source = `sourceType#sourceElementName`.
public final class ModelJsonConfigReader implements ConfigReader {

    public static final String MODEL_ENTRY = "META-INF/quarkus-config-doc/quarkus-config-model.json";
    public static final String JAVADOC_ENTRY = "META-INF/quarkus-config-doc/quarkus-config-javadoc.json";

    private static final String ID = "model-json/1";
    private static final String JAVADOC_FORMAT = "JAVADOC";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public ConfigSnapshot read(VersionJars jars) throws IOException {
        List<String> warnings = new ArrayList<>();
        Map<String, JsonNode> javadoc = new HashMap<>();
        for (Map.Entry<JarSource, byte[]> entry : jars.readAll(JAVADOC_ENTRY).entrySet()) {
            JsonNode tree = parse(entry.getKey(), JAVADOC_ENTRY, entry.getValue(), warnings);
            for (Map.Entry<String, JsonNode> element : tree.path("elements").properties()) {
                javadoc.putIfAbsent(element.getKey(), element.getValue());
            }
        }
        List<ConfigProperty> properties = new ArrayList<>();
        Set<String> rootClasses = new TreeSet<>();
        for (Map.Entry<JarSource, byte[]> entry : jars.readAll(MODEL_ENTRY).entrySet()) {
            JsonNode model = parse(entry.getKey(), MODEL_ENTRY, entry.getValue(), warnings);
            for (JsonNode root : model.path("configRoots")) {
                String ext = text(root.path("extension"), "artifactId");
                if (ext == null) {
                    ext = ConfigProperties.extensionOf(entry.getKey().artifactId());
                }
                root.path("qualifiedNames").forEach(name -> rootClasses.add(name.asText()));
                collect(root.path("items"), ext, javadoc, properties);
            }
        }
        List<ConfigProperty> unique = ConfigProperties.sortedUnique(properties, warnings);
        return new ConfigSnapshot(jars.quarkusVersion(), "model", ID, rootClasses.size(), List.copyOf(warnings),
                unique);
    }

    private static JsonNode parse(JarSource jar, String entry, byte[] json, List<String> warnings) {
        try {
            return Json.readTree(json);
        } catch (IOException e) {
            warnings.add("unreadable " + entry + " in " + jar.artifactId() + ": " + e.getMessage());
            return Json.mapper().missingNode();
        }
    }

    /// Flattens config sections recursively (a section without properties has no `items`).
    private static void collect(JsonNode items, String ext, Map<String, JsonNode> javadoc, List<ConfigProperty> out) {
        for (JsonNode item : items) {
            if (isSection(item)) {
                collect(item.path("items"), ext, javadoc, out);
            } else {
                out.add(property(item, ext, javadoc));
            }
        }
    }

    private static boolean isSection(JsonNode item) {
        String type = text(item, "@class");
        return type != null ? type.endsWith(".ConfigSection") : item.has("items") || !item.has("phase");
    }

    private static ConfigProperty property(JsonNode item, String ext, Map<String, JsonNode> javadoc) {
        String name = item.path("path").path("property").asText();
        List<String> aliases = new ArrayList<>();
        for (JsonNode path : item.path("additionalPaths")) {
            aliases.add(path.path("property").asText());
        }
        JsonNode deprecation = item.path("deprecation");
        String deprecated = deprecation.isObject() ? deprecation.path("since").asText("") : null;
        List<String> enumValues = null;
        JsonNode accepted = item.path("enumAcceptedValues").path("values");
        if (accepted.isObject()) {
            enumValues = new ArrayList<>();
            for (Map.Entry<String, JsonNode> value : accepted.properties()) {
                enumValues.add(value.getValue().path("configValue").asText(value.getKey()));
            }
        }
        String element = text(item, "sourceType") + "." + text(item, "sourceElementName");
        return new ConfigProperty(
                name,
                ConfigKeys.canonical(name),
                List.copyOf(aliases),
                ext,
                text(item, "typeDescription"),
                item.path("list").asBoolean(false),
                item.path("optional").asBoolean(false),
                item.path("map").asBoolean(false),
                text(item, "defaultValue"),
                text(item, "phase"),
                deprecated,
                enumValues == null ? null : List.copyOf(enumValues),
                description(javadoc.get(element)),
                text(item, "sourceType") + "#" + text(item, "sourceElementName"));
    }

    private static String description(JsonNode element) {
        if (element == null) {
            return null;
        }
        String text = text(element, "description");
        return Descriptions.firstSentence(JAVADOC_FORMAT.equals(text(element, "format"))
                ? Descriptions.fromJavadoc(text)
                : Descriptions.replaceJavadocTags(text));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
