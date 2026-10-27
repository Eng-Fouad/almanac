package io.quarkiverse.almanac.maven;

import org.w3c.dom.Element;
import org.w3c.dom.Node;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/// What [BomResolver] reads from one pom, exactly as written: no inheritance, no interpolation, no import.
///
/// @param groupId    null when inherited from the parent
/// @param artifactId the pom's own artifactId
/// @param version    null when inherited from the parent
/// @param packaging  null for the default, `jar`
/// @param parent     the `<parent>` coordinates, null when there is none
/// @param properties the `<properties>` in document order (a name written twice keeps its last value, as in Maven)
/// @param managed    the `<dependencyManagement>` dependencies in document order
/// @param profiles   the profiles that declare properties or managed dependencies
record Pom(String groupId, String artifactId, String version, String packaging, Coordinates parent,
           Map<String, String> properties, List<Dependency> managed, List<Profile> profiles) {

    /// `groupId:artifactId:version` of a pom, as written in a `<parent>` or an import.
    record Coordinates(String groupId, String artifactId, String version) {

        @Override
        public String toString() {
            return groupId + ":" + artifactId + ":" + version;
        }
    }

    /// A managed dependency as written; absent or blank elements are null.
    record Dependency(String groupId, String artifactId, String version, String type, String classifier,
                      String scope) {
    }

    /// A profile, reduced to what could change managed versions.
    ///
    /// @param properties the names of the properties it sets
    /// @param managed    how many dependencies its `<dependencyManagement>` declares
    record Profile(String id, boolean activeByDefault, Set<String> properties, int managed) {
    }

    /// Reads a pom.
    ///
    /// @throws IOException when the document is not XML or has no `<project>` root
    static Pom parse(byte[] xml) throws IOException {
        Element project = Xml.parse(xml);
        if (!"project".equals(project.getLocalName() != null ? project.getLocalName() : project.getTagName())) {
            throw new IOException("not a pom: the root element is <" + project.getTagName() + ">");
        }
        Coordinates parent = Xml.path(project, "parent")
                .map(element -> new Coordinates(text(element, "groupId"), text(element, "artifactId"),
                        text(element, "version")))
                .orElse(null);
        List<Profile> profiles = new ArrayList<>();
        for (Element profile : Xml.path(project, "profiles").map(p -> Xml.children(p, "profile")).orElse(List.of())) {
            Set<String> properties = properties(profile).keySet();
            int managed = managed(profile).size();
            if (!properties.isEmpty() || managed > 0) {
                boolean activeByDefault = Xml.path(profile, "activation", "activeByDefault")
                        .map(element -> element.getTextContent().strip().equals("true"))
                        .orElse(false);
                profiles.add(new Profile(Optional.ofNullable(text(profile, "id")).orElse("default"), activeByDefault,
                        Collections.unmodifiableSet(new LinkedHashSet<>(properties)), managed));
            }
        }
        return new Pom(text(project, "groupId"), text(project, "artifactId"), text(project, "version"),
                text(project, "packaging"), parent, Collections.unmodifiableMap(properties(project)),
                managed(project), List.copyOf(profiles));
    }

    /// The `<properties>` of a project or profile: element names to trimmed text.
    private static Map<String, String> properties(Element owner) {
        Map<String, String> properties = new LinkedHashMap<>();
        Xml.path(owner, "properties").ifPresent(element -> {
            for (Node node = element.getFirstChild(); node != null; node = node.getNextSibling()) {
                if (node instanceof Element property) {
                    String name = property.getLocalName() != null ? property.getLocalName() : property.getTagName();
                    properties.put(name, property.getTextContent().strip());
                }
            }
        });
        return properties;
    }

    /// The `<dependencyManagement>` dependencies of a project or profile.
    private static List<Dependency> managed(Element owner) {
        List<Dependency> managed = new ArrayList<>();
        for (Element dependency : Xml.path(owner, "dependencyManagement", "dependencies")
                .map(d -> Xml.children(d, "dependency")).orElse(List.of())) {
            managed.add(new Dependency(text(dependency, "groupId"), text(dependency, "artifactId"),
                    text(dependency, "version"), text(dependency, "type"), text(dependency, "classifier"),
                    text(dependency, "scope")));
        }
        return List.copyOf(managed);
    }

    private static String text(Element parent, String localName) {
        return Xml.childText(parent, localName).orElse(null);
    }
}
