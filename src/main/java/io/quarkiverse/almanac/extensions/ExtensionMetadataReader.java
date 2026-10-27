package io.quarkiverse.almanac.extensions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.quarkiverse.almanac.config.JarSource;
import io.quarkiverse.almanac.config.VersionJars;
import io.quarkiverse.almanac.model.ExtensionInfo;
import io.quarkiverse.almanac.model.ExtensionsSnapshot;
import io.quarkiverse.almanac.util.Log;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/// Builds the extensions snapshot of a release: one entry per jar that contains
/// {@value #ENTRY} (runtime extension jars), plus one relocation-only entry (id + relocatedTo) per relocated runtime
/// artifact (see [VersionJars#relocations()]). Deployment artifacts are not extensions, so their relocations
/// are skipped. The YAML is read leniently: `status` may be a string or a list, `unlisted` a boolean or
/// a string, and any field may be missing.
public final class ExtensionMetadataReader {

    public static final String ENTRY = "META-INF/quarkus-extension.yaml";

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final String DEPLOYMENT_SUFFIX = "-deployment";

    public ExtensionsSnapshot read(VersionJars jars) throws IOException {
        Map<String, ExtensionInfo> byId = new TreeMap<>();
        for (Map.Entry<JarSource, byte[]> entry : jars.readAll(ENTRY).entrySet()) {
            String artifactId = entry.getKey().artifactId();
            try {
                byId.put(artifactId, parse(artifactId, entry.getValue()));
            } catch (IOException e) {
                Log.warn("%s: unreadable %s in %s: %s", jars.quarkusVersion(), ENTRY, artifactId, e.getMessage());
            }
        }
        for (Map.Entry<String, String> relocation : jars.relocations().entrySet()) {
            String id = relocation.getKey();
            if (id.endsWith(DEPLOYMENT_SUFFIX)) {
                continue;
            }
            ExtensionInfo found = byId.get(id);
            byId.put(id, found == null
                    ? new ExtensionInfo(id, null, null, null, null, null, false, null, relocation.getValue())
                    : new ExtensionInfo(id, found.name(), found.description(), found.status(), found.categories(),
                            found.guide(), found.unlisted(), found.configPrefixes(), relocation.getValue()));
        }
        return new ExtensionsSnapshot(jars.quarkusVersion(), List.copyOf(byId.values()));
    }

    /// Parses one `quarkus-extension.yaml`.
    static ExtensionInfo parse(String artifactId, byte[] yaml) throws IOException {
        JsonNode root = YAML.readTree(yaml);
        if (root == null || !root.isObject()) {
            throw new IOException("not a YAML mapping");
        }
        return parse(artifactId, root);
    }

    /// The entry of one extension from its descriptor: `name` and `description` at the top, the rest from `metadata`
    /// (the layout of `quarkus-extension.yaml` and of each extension in an extension catalog).
    static ExtensionInfo parse(String artifactId, JsonNode root) {
        JsonNode metadata = root.path("metadata");
        JsonNode status = metadata.path("status");
        String firstStatus = text(status.isArray() ? status.path(0) : status);
        List<String> prefixes = strings(metadata.path("config")).stream()
                .map(prefix -> prefix.endsWith(".") ? prefix.substring(0, prefix.length() - 1) : prefix)
                .toList();
        return new ExtensionInfo(
                artifactId,
                text(root.path("name")),
                text(root.path("description")),
                firstStatus == null ? null : firstStatus.toLowerCase(Locale.ROOT),
                strings(metadata.path("categories")),
                text(metadata.path("guide")),
                metadata.path("unlisted").asBoolean(false),
                prefixes,
                null);
    }

    private static String text(JsonNode node) {
        return node.isValueNode() && !node.isNull() && !node.asText().isBlank() ? node.asText().strip() : null;
    }

    private static List<String> strings(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node.isArray()) {
            node.forEach(item -> {
                String value = text(item);
                if (value != null) {
                    values.add(value);
                }
            });
        } else if (text(node) != null) {
            values.add(text(node));
        }
        return List.copyOf(values);
    }
}
