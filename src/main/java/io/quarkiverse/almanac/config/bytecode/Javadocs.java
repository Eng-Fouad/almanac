package io.quarkiverse.almanac.config.bytecode;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkiverse.almanac.config.JarSource;
import io.quarkiverse.almanac.config.ModelJsonConfigReader;
import io.quarkiverse.almanac.config.VersionJars;
import io.quarkiverse.almanac.util.Descriptions;
import io.quarkiverse.almanac.util.Json;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/// The release-wide Javadoc of config members, keyed
/// `<canonical name of the declaring type>.<member>`: the {@value #ENTRY} files of all jars and, from 3.25 on, the
/// `quarkus-config-javadoc.json` files, which hold the same text plus its format and win over the properties files. A
/// key found in several jars keeps the text of the first jar by artifactId, as in the model reader.
final class Javadocs {

    static final String ENTRY = "META-INF/quarkus-javadoc.properties";

    private static final String ASCIIDOC_MARKER = "@asciidoclet";
    private static final String JAVADOC_FORMAT = "JAVADOC";

    /// A stored text.
    ///
    /// @param text     the description, null when a JSON element has none
    /// @param format   the recorded format (`JAVADOC`, `ASCIIDOC`, …) of a JSON element, else null
    /// @param fromJson whether the text comes from `quarkus-config-javadoc.json`
    private record Text(String text, String format, boolean fromJson) {
    }

    private final Map<String, Text> texts;

    private Javadocs(Map<String, Text> texts) {
        this.texts = Map.copyOf(texts);
    }

    /// Javadoc from properties-file texts only (for tests).
    static Javadocs ofProperties(Map<String, String> texts) {
        Map<String, Text> stored = new HashMap<>();
        texts.forEach((key, text) -> stored.put(key, new Text(text, null, false)));
        return new Javadocs(stored);
    }

    /// Reads the Javadoc files of every jar of the release; an unreadable file becomes a warning.
    static Javadocs read(VersionJars jars, List<String> warnings) throws IOException {
        Map<String, Text> texts = new HashMap<>();
        for (Map.Entry<JarSource, byte[]> entry : jars.readAll(ModelJsonConfigReader.JAVADOC_ENTRY).entrySet()) {
            JsonNode tree;
            try {
                tree = Json.readTree(entry.getValue());
            } catch (IOException e) {
                warnings.add("unreadable " + ModelJsonConfigReader.JAVADOC_ENTRY + " in "
                        + entry.getKey().artifactId() + ": " + e.getMessage());
                continue;
            }
            for (Map.Entry<String, JsonNode> element : tree.path("elements").properties()) {
                texts.putIfAbsent(element.getKey(), new Text(text(element.getValue(), "description"),
                        text(element.getValue(), "format"), true));
            }
        }
        Map<String, Text> fromProperties = new HashMap<>();
        for (Map.Entry<JarSource, byte[]> entry : jars.readAll(ENTRY).entrySet()) {
            Properties properties = new Properties();
            try (InputStreamReader reader = new InputStreamReader(new ByteArrayInputStream(entry.getValue()),
                    StandardCharsets.UTF_8)) {
                properties.load(reader);
            } catch (IOException | IllegalArgumentException e) {
                warnings.add("unreadable " + ENTRY + " in " + entry.getKey().artifactId() + ": " + e.getMessage());
                continue;
            }
            for (String key : properties.stringPropertyNames()) {
                fromProperties.putIfAbsent(key, new Text(properties.getProperty(key), null, false));
            }
        }
        fromProperties.forEach(texts::putIfAbsent);
        return new Javadocs(texts);
    }

    /// Number of documented elements.
    int size() {
        return texts.size();
    }

    /// The almanac description of a member: the first sentence of its Javadoc, or null when the release has none.
    ///
    /// @param sourceType canonical name of the type that declares the member
    /// @param member     field or method name
    String description(String sourceType, String member) {
        Text text = texts.get(sourceType + "." + member);
        if (text == null) {
            return null;
        }
        if (text.fromJson()) {
            // as ModelJsonConfigReader: Javadoc is converted, asciidoc only gets leftover Javadoc tags replaced
            return Descriptions.firstSentence(JAVADOC_FORMAT.equals(text.format())
                    ? Descriptions.fromJavadoc(text.text())
                    : Descriptions.replaceJavadocTags(text.text()));
        }
        return description(text.text());
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    /// Turns a properties-file text into a description. Releases up to 3.15 store the raw comment, block tags included:
    /// it is cut before the first line that starts with `@`, and an `@asciidoclet` tag among the removed lines marks
    /// asciidoc. Later releases store the description only, whose format is not recorded; it is treated as Javadoc,
    /// whose conversion leaves asciidoc markup alone.
    static String description(String stored) {
        if (stored == null) {
            return null;
        }
        String[] lines = stored.split("\n", -1);
        StringBuilder text = new StringBuilder(stored.length());
        boolean asciidoc = false;
        boolean inTags = false;
        for (String line : lines) {
            String trimmed = line.strip();
            if (!inTags && trimmed.startsWith("@")) {
                inTags = true;
            }
            if (inTags) {
                asciidoc |= trimmed.equals(ASCIIDOC_MARKER) || trimmed.startsWith(ASCIIDOC_MARKER + " ");
            } else {
                text.append(line).append('\n');
            }
        }
        String description = text.toString();
        return Descriptions.firstSentence(asciidoc ? Descriptions.replaceJavadocTags(description)
                : Descriptions.fromJavadoc(description));
    }
}
