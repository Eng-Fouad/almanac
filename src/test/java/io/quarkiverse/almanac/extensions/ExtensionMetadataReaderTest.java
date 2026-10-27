package io.quarkiverse.almanac.extensions;

import io.quarkiverse.almanac.config.InMemoryJar;
import io.quarkiverse.almanac.config.VersionJars;
import io.quarkiverse.almanac.model.ExtensionInfo;
import io.quarkiverse.almanac.model.ExtensionsSnapshot;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExtensionMetadataReaderTest {

    private static final String HIBERNATE_ORM = """
            ---
            artifact: "io.quarkus:quarkus-hibernate-orm:3.40.1"
            name: "Hibernate ORM"
            description: "Object-relational mapping with JPA/Hibernate for relational database\\
              \\ access"
            metadata:
              keywords:
              - "hibernate-orm"
              guide: "https://quarkus.io/guides/hibernate-orm"
              categories:
              - "data"
              status: "stable"
              config:
              - "quarkus.datasource."
              - "quarkus.hibernate-orm."
            """;

    @Test
    void readsTheExtensionYaml() throws IOException {
        assertEquals(new ExtensionInfo("quarkus-hibernate-orm", "Hibernate ORM",
                        "Object-relational mapping with JPA/Hibernate for relational database access", "stable",
                        List.of("data"), "https://quarkus.io/guides/hibernate-orm", false,
                        List.of("quarkus.datasource", "quarkus.hibernate-orm"), null),
                ExtensionMetadataReader.parse("quarkus-hibernate-orm", HIBERNATE_ORM.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void toleratesStatusListsStringFlagsAndMissingFields() throws IOException {
        String yaml = """
                name: Legacy
                metadata:
                  status: [Preview, experimental]
                  unlisted: "true"
                  categories: web
                """;
        assertEquals(new ExtensionInfo("quarkus-legacy", "Legacy", null, "preview", List.of("web"), null, true,
                List.of(), null), ExtensionMetadataReader.parse("quarkus-legacy", yaml.getBytes(StandardCharsets.UTF_8)));
        assertEquals(new ExtensionInfo("quarkus-bare", null, null, null, List.of(), null, false, List.of(), null),
                ExtensionMetadataReader.parse("quarkus-bare", "name:\n".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void addsRelocatedRuntimeArtifactsAndSortsById() throws IOException {
        VersionJars jars = new VersionJars("3.9.0", List.of(
                InMemoryJar.of("quarkus-rest", "3.9.0").with(ExtensionMetadataReader.ENTRY, "name: REST\n"),
                InMemoryJar.of("quarkus-rest-deployment", "3.9.0"),
                InMemoryJar.of("quarkus-arc", "3.9.0").with(ExtensionMetadataReader.ENTRY, "name: ArC\n"),
                InMemoryJar.missing("quarkus-resteasy-reactive", "3.9.0"),
                InMemoryJar.missing("quarkus-resteasy-reactive-deployment", "3.9.0")),
                jar -> jar.artifactId().contains("resteasy-reactive")
                        ? Optional.of("io.quarkus:" + jar.artifactId().replace("resteasy-reactive", "rest"))
                        : Optional.empty(), 2);

        ExtensionsSnapshot snapshot = new ExtensionMetadataReader().read(jars);

        assertEquals("3.9.0", snapshot.version());
        assertEquals(List.of(
                new ExtensionInfo("quarkus-arc", "ArC", null, null, List.of(), null, false, List.of(), null),
                new ExtensionInfo("quarkus-rest", "REST", null, null, List.of(), null, false, List.of(), null),
                new ExtensionInfo("quarkus-resteasy-reactive", null, null, null, null, null, false, null,
                        "io.quarkus:quarkus-rest")), snapshot.extensions());
    }
}
