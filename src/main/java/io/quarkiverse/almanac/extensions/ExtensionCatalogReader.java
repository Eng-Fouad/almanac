package io.quarkiverse.almanac.extensions;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkiverse.almanac.model.ExtensionInfo;
import io.quarkiverse.almanac.model.ExtensionsSnapshot;
import io.quarkiverse.almanac.util.Json;
import io.quarkiverse.almanac.util.Log;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/// Builds the extensions snapshot of a release from its extension catalog ([ExtensionCatalog], either format), without
/// reading any jar: one entry per `io.quarkus` extension the catalog lists, filled from the extension's `name`,
/// `description` and `metadata` exactly as [ExtensionMetadataReader] fills it from `META-INF/quarkus-extension.yaml`
/// (the catalog is generated from those files).
///
/// A catalog lists extensions only, never relocated artifacts, so no entry has `relocatedTo`: the jar reader adds one
/// relocation entry per runtime artifact of the BOM that Maven relocates, which a catalog-based snapshot lacks.
public final class ExtensionCatalogReader {

    private ExtensionCatalogReader() {
    }

    /// Reads a catalog.
    ///
    /// @param version the release the catalog belongs to
    /// @throws IOException when the document is not an extension catalog, or an extension has no artifact coordinates
    ///                     or is listed twice
    public static ExtensionsSnapshot read(String version, byte[] json) throws IOException {
        JsonNode root = Json.readTree(json);
        JsonNode extensions = root == null ? null : root.get("extensions");
        if (extensions == null || !extensions.isArray()) {
            throw new IOException("not an extension catalog: no extensions array");
        }
        String coreVersion = root.path("quarkus-core-version").asText(null);
        if (coreVersion != null && !coreVersion.equals(version)) {
            Log.warn("extensions %s: the catalog says quarkus-core-version %s", version, coreVersion);
        }
        Map<String, ExtensionInfo> byId = new TreeMap<>();
        int otherGroups = 0;
        for (JsonNode extension : extensions) {
            String[] coordinates = coordinates(extension);
            if (!coordinates[0].equals(ExtensionCatalog.GROUP_ID)) {
                // the jar reader only reads io.quarkus jars
                otherGroups++;
                continue;
            }
            String artifactId = coordinates[1];
            if (byId.put(artifactId, ExtensionMetadataReader.parse(artifactId, extension)) != null) {
                throw new IOException("the catalog lists " + artifactId + " twice");
            }
        }
        if (otherGroups > 0) {
            Log.debug("extensions %s: %d catalog entries outside %s left out", version, otherGroups,
                    ExtensionCatalog.GROUP_ID);
        }
        return new ExtensionsSnapshot(version, List.copyOf(byId.values()));
    }

    /// The groupId and artifactId of a catalog entry: from `artifact` (`groupId:artifactId:classifier:type:version`,
    /// catalogs from 1.10 on), else from `group-id` and `artifact-id` (catalogs up to 1.9).
    private static String[] coordinates(JsonNode extension) throws IOException {
        if (!extension.isObject()) {
            throw new IOException("an extension entry is not an object: " + abbreviate(extension));
        }
        String artifact = extension.path("artifact").asText("");
        if (!artifact.isEmpty()) {
            String[] parts = artifact.split(":");
            if (parts.length < 2 || parts[0].isBlank() || parts[1].isBlank()) {
                throw new IOException("unreadable artifact coordinates " + artifact);
            }
            return new String[]{parts[0].strip(), parts[1].strip()};
        }
        String groupId = extension.path("group-id").asText("").strip();
        String artifactId = extension.path("artifact-id").asText("").strip();
        if (groupId.isEmpty() || artifactId.isEmpty()) {
            throw new IOException("an extension entry without artifact coordinates: " + abbreviate(extension));
        }
        return new String[]{groupId, artifactId};
    }

    private static String abbreviate(JsonNode node) {
        String text = node.toString();
        return text.length() > 160 ? text.substring(0, 160) + "..." : text;
    }
}
