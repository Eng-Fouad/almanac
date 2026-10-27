package io.quarkiverse.almanac.config;

import io.quarkiverse.almanac.model.ConfigProperty;
import io.quarkiverse.almanac.model.ConfigSnapshot;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Reads a fixture trimmed from the real 3.40.1 models of quarkus-hibernate-orm(-deployment) and quarkus-core.
class ModelJsonConfigReaderTest {

    private static final String VERSION = "3.40.1";
    private static final List<String> FIXTURE_JARS =
            List.of("quarkus-hibernate-orm", "quarkus-hibernate-orm-deployment", "quarkus-core");

    private static ConfigSnapshot snapshot;
    private static Map<String, ConfigProperty> byKey;

    @BeforeAll
    static void readFixture() throws IOException {
        List<JarSource> jars = new ArrayList<>();
        for (String artifactId : FIXTURE_JARS) {
            jars.add(InMemoryJar.of(artifactId, VERSION)
                    .with(ModelJsonConfigReader.MODEL_ENTRY, resource(artifactId + "/quarkus-config-model.json"))
                    .with(ModelJsonConfigReader.JAVADOC_ENTRY, resource(artifactId + "/quarkus-config-javadoc.json")));
        }
        jars.add(InMemoryJar.of("quarkus-arc", VERSION).with("io/quarkus/arc/Arc.class", new byte[] {1}));
        jars.add(InMemoryJar.missing("quarkus-gone", VERSION));
        snapshot = new ModelJsonConfigReader().read(new VersionJars(VERSION, jars, jar -> Optional.empty(), 4));
        byKey = snapshot.properties().stream().collect(Collectors.toMap(ConfigProperty::key, Function.identity()));
    }

    @Test
    void readsEveryPropertyOfEveryJarSortedByKey() {
        assertEquals(VERSION, snapshot.version());
        assertEquals("model", snapshot.source());
        assertEquals("model-json/1", snapshot.extractor());
        assertEquals(4, snapshot.roots());
        assertEquals(List.of(), snapshot.warnings());
        assertEquals(List.of(
                "quarkus.hibernate-orm.active",
                "quarkus.hibernate-orm.batch-fetch-size",
                "quarkus.hibernate-orm.database.generation",
                "quarkus.hibernate-orm.max-fetch-depth",
                "quarkus.hibernate-orm.request-scoped.enabled",
                "quarkus.hibernate-orm.schema-management.create-schemas",
                "quarkus.hibernate-orm.schema-management.strategy",
                "quarkus.hibernate-orm.unsupported-properties.*",
                "quarkus.log.category.*.handlers",
                "quarkus.log.category.*.level",
                "quarkus.profile"), snapshot.properties().stream().map(ConfigProperty::key).toList());
    }

    @Test
    void mapsAllFieldsOfAProperty() {
        assertEquals(new ConfigProperty(
                "quarkus.hibernate-orm.active",
                "quarkus.hibernate-orm.active",
                List.of("quarkus.hibernate-orm.\"persistence-unit-name\".active"),
                "quarkus-hibernate-orm",
                "boolean",
                false,
                true,
                false,
                "`true` if Hibernate ORM is enabled and there are entity types or an active datasource assigned "
                        + "to the persistence unit; `false` otherwise",
                "RUN_TIME",
                null,
                null,
                "Whether this persistence unit should be active at runtime.",
                "io.quarkus.hibernate.orm.runtime.HibernateOrmRuntimeConfigPersistenceUnit#active"),
                byKey.get("quarkus.hibernate-orm.active"));
    }

    @Test
    void readsEnumValuesInOrder() {
        ConfigProperty strategy = byKey.get("quarkus.hibernate-orm.schema-management.strategy");
        assertEquals("HibernateGenerationStrategy", strategy.type());
        assertEquals("none", strategy.defaultValue());
        assertEquals(List.of("create", "drop", "drop-and-create", "none", "update", "validate"),
                strategy.enumValues());
    }

    @Test
    void readsDeprecations() {
        assertEquals("3.22", byKey.get("quarkus.hibernate-orm.database.generation").deprecated());
        ConfigProperty batchFetchSize = byKey.get("quarkus.hibernate-orm.batch-fetch-size");
        assertEquals("", batchFetchSize.deprecated());
        assertEquals("BUILD_TIME", batchFetchSize.phase());
        assertEquals("16", batchFetchSize.defaultValue());
        assertEquals("quarkus-hibernate-orm", batchFetchSize.ext());
        assertNull(byKey.get("quarkus.profile").deprecated());
    }

    @Test
    void readsListMapAndNamedMapProperties() {
        ConfigProperty profile = byKey.get("quarkus.profile");
        assertTrue(profile.list() && profile.optional() && !profile.map());
        assertEquals("quarkus-core", profile.ext());

        ConfigProperty unsupported = byKey.get("quarkus.hibernate-orm.unsupported-properties.*");
        assertTrue(unsupported.map());
        assertEquals("Map<String,String>", unsupported.type());
        assertEquals("quarkus.hibernate-orm.unsupported-properties.\"full-property-key\"", unsupported.name());

        ConfigProperty level = byKey.get("quarkus.log.category.*.level");
        assertEquals("quarkus.log.category.\"categories\".level", level.name());
        assertEquals(List.of(), level.aliases());
        assertFalse(level.map());
    }

    @Test
    void takesTheFirstSentenceOfJavadocAndAsciidocDescriptions() {
        assertEquals("The log level for this category.", byKey.get("quarkus.log.category.*.level").desc());
        assertEquals("Enable or disable access to a Hibernate ORM `EntityManager`/`Session`/`StatelessSession` "
                        + "*when no transaction is active* but a request scope is.",
                byKey.get("quarkus.hibernate-orm.request-scoped.enabled").desc());
        assertEquals("Select whether the database schema is generated or not.",
                byKey.get("quarkus.hibernate-orm.schema-management.strategy").desc());
    }

    @Test
    void mergesDuplicateKeysWithAWarning() throws IOException {
        String duplicate = """
                {"configRoots": [{"extension": {"artifactId": "quarkus-other"}, "qualifiedNames": ["x.Other"],
                  "items": [{"path": {"property": "quarkus.profile"},
                             "additionalPaths": [{"property": "quarkus.profiles"}],
                             "typeDescription": "string", "phase": "RUN_TIME",
                             "sourceType": "x.Other", "sourceElementName": "profile"}]}]}
                """;
        List<JarSource> jars = List.of(
                InMemoryJar.of("quarkus-core", VERSION)
                        .with(ModelJsonConfigReader.MODEL_ENTRY, resource("quarkus-core/quarkus-config-model.json")),
                InMemoryJar.of("quarkus-other", VERSION).with(ModelJsonConfigReader.MODEL_ENTRY, duplicate));
        ConfigSnapshot merged = new ModelJsonConfigReader().read(
                new VersionJars(VERSION, jars, jar -> Optional.empty(), 2));

        ConfigProperty profile = merged.properties().stream().filter(p -> p.key().equals("quarkus.profile"))
                .findFirst().orElseThrow();
        assertEquals("quarkus-core", profile.ext());
        assertEquals(List.of("quarkus.profiles"), profile.aliases());
        assertEquals(1, merged.warnings().size());
        assertTrue(merged.warnings().getFirst().startsWith("duplicate key quarkus.profile"), merged.warnings()
                .getFirst());
        assertEquals(3, merged.roots());
    }

    @Test
    void ignoresEmptySectionsAndExactRepeats() throws IOException {
        String emptySection = """
                {"configRoots": [{"extension": {"artifactId": "quarkus-reactive-oracle-client"},
                  "qualifiedNames": ["x.DataSources"],
                  "items": [{"@class": "io.quarkus.annotation.processor.documentation.config.model.ConfigSection",
                             "path": {"property": "quarkus.datasource"},
                             "sourceType": "x.DataSources", "sourceElementName": "dataSources"}]}]}
                """;
        byte[] coreModel = resource("quarkus-core/quarkus-config-model.json");
        List<JarSource> jars = List.of(
                InMemoryJar.of("quarkus-core", VERSION).with(ModelJsonConfigReader.MODEL_ENTRY, coreModel),
                InMemoryJar.of("quarkus-core-copy", VERSION).with(ModelJsonConfigReader.MODEL_ENTRY, coreModel),
                InMemoryJar.of("quarkus-reactive-oracle-client", VERSION)
                        .with(ModelJsonConfigReader.MODEL_ENTRY, emptySection));
        ConfigSnapshot read = new ModelJsonConfigReader().read(
                new VersionJars(VERSION, jars, jar -> Optional.empty(), 2));

        assertEquals(List.of("quarkus.log.category.*.handlers", "quarkus.log.category.*.level", "quarkus.profile"),
                read.properties().stream().map(ConfigProperty::key).toList());
        assertEquals(List.of(), read.warnings());
        assertEquals(3, read.roots());
    }

    private static byte[] resource(String path) throws IOException {
        try (InputStream in = ModelJsonConfigReaderTest.class.getResourceAsStream("/config-model-3.40.1/" + path)) {
            if (in == null) {
                throw new IOException("missing test resource " + path);
            }
            return in.readAllBytes();
        }
    }
}
