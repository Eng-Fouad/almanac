package io.quarkiverse.almanac.maven;

import io.quarkiverse.almanac.model.ManagedDep;
import org.w3c.dom.Element;

import java.io.IOException;
import java.util.Optional;

/// Pom helpers: the [ManagedDep#id()] of a managed dependency and Maven relocations. [BomResolver] reads the managed
/// dependencies of a BOM.
public final class BomParser {

    /// Written instead of a classifier that equals the BOM's own version, so ids stay stable across releases.
    public static final String QUARKUS_VERSION_PLACEHOLDER = "${quarkus.version}";

    private BomParser() {
    }

    /// The [ManagedDep#id()] of a dependency: `groupId:artifactId`, plus `:type` when the type is not `jar`, plus
    /// `:classifier` when present (a classifier equal to `bomVersion` is written as [#QUARKUS_VERSION_PLACEHOLDER]).
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
