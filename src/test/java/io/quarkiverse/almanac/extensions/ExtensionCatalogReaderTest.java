package io.quarkiverse.almanac.extensions;

import io.quarkiverse.almanac.model.ExtensionInfo;
import io.quarkiverse.almanac.model.ExtensionsSnapshot;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// [ExtensionCatalogReader] on catalogs shaped like the published ones (the registry catalog of 1.10 and later, the
/// descriptor of 0.27 to 1.9 and its 0.26 layout) and on malformed documents.
class ExtensionCatalogReaderTest {

    /// The layout of `quarkus-bom-quarkus-platform-descriptor-2.16.12.Final-2.16.12.Final.json`, shortened.
    private static final String REGISTRY_CATALOG = """
            {
              "id" : "io.quarkus:quarkus-bom-quarkus-platform-descriptor:2.16.12.Final:json:2.16.12.Final",
              "platform" : true,
              "bom" : "io.quarkus:quarkus-bom::pom:2.16.12.Final",
              "quarkus-core-version" : "2.16.12.Final",
              "extensions" : [ {
                "name" : "Quarkus - Core",
                "description" : "Quarkus core components",
                "metadata" : {
                  "built-with-quarkus-core" : "2.16.12.Final",
                  "minimum-java-version" : "11",
                  "unlisted" : true
                },
                "artifact" : "io.quarkus:quarkus-core::jar:2.16.12.Final",
                "origins" : [ "io.quarkus:quarkus-bom-quarkus-platform-descriptor:2.16.12.Final:json:2.16.12.Final" ]
              }, {
                "name" : "Hibernate ORM",
                "description" : "Define your persistent model with Hibernate ORM and JPA",
                "metadata" : {
                  "keywords" : [ "hibernate-orm", "jpa" ],
                  "guide" : "https://quarkus.io/guides/hibernate-orm",
                  "categories" : [ "data" ],
                  "status" : "stable",
                  "config" : [ "quarkus.hibernate-orm." ],
                  "extension-dependencies" : [ "io.quarkus:quarkus-agroal" ]
                },
                "artifact" : "io.quarkus:quarkus-hibernate-orm::jar:2.16.12.Final"
              }, {
                "name" : "Caffeine",
                "description" : "A high performance caching library for Java",
                "metadata" : {
                  "unlisted" : "true",
                  "guide" : "",
                  "status" : [ "Preview", "experimental" ],
                  "stable" : "true"
                },
                "artifact" : "io.quarkus:quarkus-caffeine::jar:2.16.12.Final"
              }, {
                "name" : "Not Quarkus core",
                "metadata" : { },
                "artifact" : "io.quarkiverse.example:quarkus-example::jar:1.0.0"
              } ],
              "categories" : [ { "id" : "data", "name" : "Data" } ],
              "metadata" : { "project" : { } }
            }
            """;

    /// The layout of `quarkus-bom-descriptor-json-1.9.2.Final.json`, shortened.
    private static final String DESCRIPTOR_JSON = """
            {
              "bom" : { "group-id" : "io.quarkus", "artifact-id" : "quarkus-bom", "version" : "1.9.2.Final" },
              "quarkus-core-version" : "1.9.2.Final",
              "extensions" : [ {
                "name" : "Quarkus - Core",
                "metadata" : { },
                "group-id" : "io.quarkus",
                "artifact-id" : "quarkus-core",
                "version" : "1.9.2.Final",
                "description" : "Quarkus core components"
              }, {
                "name" : "Agroal - Database connection pool",
                "metadata" : {
                  "keywords" : [ "agroal", "jdbc" ],
                  "guide" : "https://quarkus.io/guides/datasource",
                  "categories" : [ "data" ],
                  "status" : "stable"
                },
                "group-id" : "io.quarkus",
                "artifact-id" : "quarkus-agroal",
                "version" : "1.9.2.Final",
                "description" : "Pool JDBC database connections (included in Hibernate ORM)"
              } ],
              "categories" : [ ]
            }
            """;

    /// The layout of `quarkus-bom-descriptor-json-0.26.1.json`, shortened: camel-case coordinates, no `metadata`, the
    /// guide next to the name.
    private static final String DESCRIPTOR_JSON_026 = """
            {
                "bom": { "groupId": "io.quarkus", "artifactId": "quarkus-bom", "version": "0.26.1" },
                "extensions": [
                    {
                        "name": "Quarkus - Core",
                        "labels": [ ],
                        "groupId": "io.quarkus",
                        "artifactId": "quarkus-core",
                        "version": "0.26.1",
                        "description": "Build parent to bring in required dependencies"
                    },
                    {
                        "name": "ArC",
                        "shortName": "CDI",
                        "labels": [ "arc", "cdi", "dependency-injection", "di" ],
                        "guide": "https://quarkus.io/guides/cdi-reference",
                        "groupId": "io.quarkus",
                        "artifactId": "quarkus-arc",
                        "version": "0.26.1",
                        "description": "Build time CDI dependency injection"
                    },
                    {
                        "groupId": "io.quarkus",
                        "artifactId": "quarkus-caffeine",
                        "version": "0.26.1",
                        "name": "Quarkus - Caffeine - Runtime",
                        "description": "A high performance caching library for Java 8+"
                    }
                ]
            }
            """;

    @Test
    void readsTheRegistryCatalog() throws IOException {
        ExtensionsSnapshot snapshot = ExtensionCatalogReader.read("2.16.12.Final", bytes(REGISTRY_CATALOG));
        assertEquals("2.16.12.Final", snapshot.version());
        assertEquals(List.of(
                new ExtensionInfo("quarkus-caffeine", "Caffeine", "A high performance caching library for Java",
                        "preview", List.of(), null, true, List.of(), null),
                new ExtensionInfo("quarkus-core", "Quarkus - Core", "Quarkus core components", null, List.of(), null,
                        true, List.of(), null),
                new ExtensionInfo("quarkus-hibernate-orm", "Hibernate ORM",
                        "Define your persistent model with Hibernate ORM and JPA", "stable", List.of("data"),
                        "https://quarkus.io/guides/hibernate-orm", false, List.of("quarkus.hibernate-orm"), null)),
                snapshot.extensions(), "sorted by id, other groups left out, no relocations");
    }

    @Test
    void readsTheDescriptorOfReleasesUpTo19() throws IOException {
        ExtensionsSnapshot snapshot = ExtensionCatalogReader.read("1.9.2.Final", bytes(DESCRIPTOR_JSON));
        assertEquals(List.of(
                new ExtensionInfo("quarkus-agroal", "Agroal - Database connection pool",
                        "Pool JDBC database connections (included in Hibernate ORM)", "stable", List.of("data"),
                        "https://quarkus.io/guides/datasource", false, List.of(), null),
                new ExtensionInfo("quarkus-core", "Quarkus - Core", "Quarkus core components", null, List.of(), null,
                        false, List.of(), null)), snapshot.extensions());
    }

    @Test
    void readsTheDescriptorOf026WhoseEntriesHaveNoMetadata() throws IOException {
        ExtensionsSnapshot snapshot = ExtensionCatalogReader.read("0.26.1", bytes(DESCRIPTOR_JSON_026));
        assertEquals(List.of(
                new ExtensionInfo("quarkus-arc", "ArC", "Build time CDI dependency injection", null, List.of(),
                        "https://quarkus.io/guides/cdi-reference", false, List.of(), null),
                new ExtensionInfo("quarkus-caffeine", "Quarkus - Caffeine - Runtime",
                        "A high performance caching library for Java 8+", null, List.of(), null, false, List.of(),
                        null),
                new ExtensionInfo("quarkus-core", "Quarkus - Core", "Build parent to bring in required dependencies",
                        null, List.of(), null, false, List.of(), null)), snapshot.extensions(),
                "labels are keywords, not categories; 0.26 has no status");
    }

    @Test
    void anEntryReadsLikeTheExtensionYamlOfItsJar() throws IOException {
        String yaml = """
                name: "Hibernate ORM"
                description: "Define your persistent model with Hibernate ORM and JPA"
                metadata:
                  keywords: ["hibernate-orm", "jpa"]
                  guide: "https://quarkus.io/guides/hibernate-orm"
                  categories: ["data"]
                  status: "stable"
                  config: ["quarkus.hibernate-orm."]
                """;
        ExtensionInfo fromJar = ExtensionMetadataReader.parse("quarkus-hibernate-orm",
                yaml.getBytes(StandardCharsets.UTF_8));
        ExtensionInfo fromCatalog = ExtensionCatalogReader.read("2.16.12.Final", bytes(REGISTRY_CATALOG)).extensions()
                .stream().filter(extension -> extension.id().equals("quarkus-hibernate-orm")).findFirst().orElseThrow();
        assertEquals(fromJar, fromCatalog);
    }

    @Test
    void refusesDocumentsThatAreNoCatalog() {
        IOException noList = assertThrows(IOException.class,
                () -> ExtensionCatalogReader.read("3.0.0", bytes("{\"extensions\": {}}")));
        assertEquals("not an extension catalog: no extensions array", noList.getMessage());
        assertThrows(IOException.class, () -> ExtensionCatalogReader.read("3.0.0", bytes("[]")));

        IOException noCoordinates = assertThrows(IOException.class, () -> ExtensionCatalogReader.read("3.0.0",
                bytes("{\"extensions\": [{\"name\": \"Nameless\", \"metadata\": {}}]}")));
        assertTrue(noCoordinates.getMessage().startsWith("an extension entry without artifact coordinates"),
                noCoordinates.getMessage());

        IOException twice = assertThrows(IOException.class, () -> ExtensionCatalogReader.read("3.0.0", bytes("""
                {"extensions": [{"artifact": "io.quarkus:quarkus-arc::jar:3.0.0"},
                                {"artifact": "io.quarkus:quarkus-arc::jar:3.0.0"}]}
                """)));
        assertEquals("the catalog lists quarkus-arc twice", twice.getMessage());
    }

    @Test
    void refusesMalformedDocuments() {
        String arc = "{\"extensions\": [{\"artifact\": \"io.quarkus:quarkus-arc::jar:3.0.0\"}]}";
        for (String document : List.of("", "  \n", "null", "\"extensions\"", "<html><body>503</body></html>",
                arc.substring(0, arc.length() - 3), arc + " {\"extensions\": []}", "{\"extensions\": null}")) {
            assertThrows(IOException.class, () -> ExtensionCatalogReader.read("3.0.0", bytes(document)), document);
        }
        for (String entry : List.of("null", "\"io.quarkus:quarkus-arc\"", "{\"artifact\": \"io.quarkus\"}",
                "{\"artifact\": \":quarkus-arc::jar:3.0.0\"}", "{\"artifact\": {\"groupId\": \"io.quarkus\"}}",
                "{\"group-id\": \"io.quarkus\", \"artifactId\": \"quarkus-arc\"}")) {
            assertThrows(IOException.class, () -> ExtensionCatalogReader.read("3.0.0",
                    bytes("{\"extensions\": [" + entry + "]}")), entry);
        }
    }

    @Test
    void aCatalogWithoutQuarkusExtensionsReadsAsEmpty() throws IOException {
        // ExtensionCatalogs refuses to write such a snapshot
        assertEquals(List.of(), ExtensionCatalogReader.read("3.0.0", bytes("{\"extensions\": []}")).extensions());
        assertEquals(List.of(), ExtensionCatalogReader.read("3.0.0",
                bytes("{\"extensions\": [{\"artifact\": \"io.quarkiverse.acme:quarkus-acme::jar:1.0\"}]}"))
                .extensions());
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
