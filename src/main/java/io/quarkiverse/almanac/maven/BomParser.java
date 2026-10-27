package io.quarkiverse.almanac.maven;

import io.quarkiverse.almanac.model.ManagedDep;
import io.quarkiverse.almanac.util.Log;
import org.w3c.dom.Element;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/// Reads poms: the `dependencyManagement` of a flattened BOM and Maven relocations.
public final class BomParser {

    /// Written instead of a classifier that equals the BOM's own version, so ids stay stable across releases.
    public static final String QUARKUS_VERSION_PLACEHOLDER = "${quarkus.version}";

    private BomParser() {
    }

    /// Managed dependencies of a BOM, sorted by id. The id is `groupId:artifactId`, plus `:type` when the
    /// type is not `jar`, plus `:classifier` when present.
    ///
    /// @param pom        the BOM pom
    /// @param bomVersion the BOM's own version, used for the classifier placeholder
    public static List<ManagedDep> parse(byte[] pom, String bomVersion) throws IOException {
        Element project = Xml.parse(pom);
        Optional<Element> dependencies = Xml.path(project, "dependencyManagement", "dependencies");
        Map<String, ManagedDep> byId = new TreeMap<>();
        for (Element dependency : dependencies.map(d -> Xml.children(d, "dependency")).orElse(List.of())) {
            Optional<String> groupId = Xml.childText(dependency, "groupId");
            Optional<String> artifactId = Xml.childText(dependency, "artifactId");
            Optional<String> version = Xml.childText(dependency, "version");
            if (groupId.isEmpty() || artifactId.isEmpty() || version.isEmpty()) {
                Log.warn("BOM %s: skipping incomplete managed dependency %s:%s", bomVersion,
                        groupId.orElse("?"), artifactId.orElse("?"));
                continue;
            }
            String id = id(groupId.get(), artifactId.get(), Xml.childText(dependency, "type").orElse("jar"),
                    Xml.childText(dependency, "classifier").orElse(null), bomVersion);
            if (version.get().contains("${")) {
                Log.warn("BOM %s: unresolved property in the version of %s: %s", bomVersion, id, version.get());
            }
            ManagedDep previous = byId.putIfAbsent(id, new ManagedDep(id, version.get()));
            if (previous != null && !previous.v().equals(version.get())) {
                Log.warn("BOM %s: %s managed twice (%s, %s); keeping the first", bomVersion, id, previous.v(),
                        version.get());
            }
        }
        return List.copyOf(byId.values());
    }

    /// The [ManagedDep#id()] of a dependency.
    public static String id(String groupId, String artifactId, String type, String classifier, String bomVersion) {
        StringBuilder id = new StringBuilder(groupId).append(':').append(artifactId);
        if (type != null && !type.isEmpty() && !type.equals("jar")) {
            id.append(':').append(type);
        }
        if (classifier != null && !classifier.isEmpty()) {
            id.append(':').append(classifier.equals(bomVersion) ? QUARKUS_VERSION_PLACEHOLDER : classifier);
        }
        return id.toString();
    }

    /// The `groupId:artifactId` a pom relocates to (`<distributionManagement><relocation>`); a missing
    /// groupId or artifactId in the relocation defaults to the pom's own.
    public static Optional<String> relocation(byte[] pom) throws IOException {
        Element project = Xml.parse(pom);
        return Xml.path(project, "distributionManagement", "relocation").map(relocation -> {
            String ownGroupId = Xml.childText(project, "groupId")
                    .or(() -> Xml.path(project, "parent").flatMap(parent -> Xml.childText(parent, "groupId")))
                    .orElse("");
            String ownArtifactId = Xml.childText(project, "artifactId").orElse("");
            return Xml.childText(relocation, "groupId").orElse(ownGroupId) + ":"
                    + Xml.childText(relocation, "artifactId").orElse(ownArtifactId);
        });
    }
}
