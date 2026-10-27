package io.quarkiverse.almanac.config.bytecode;

import io.quarkiverse.almanac.config.JarSource;
import io.quarkiverse.almanac.maven.Http;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The reader's helpers: third-party jar candidates, Javadoc texts, generic type names, model formats.
class SupportTest {

    private static final LibraryClasses LIBRARIES = new LibraryClasses(List.of(
            TestJar.of("io.vertx", "vertx-core", "4.5.1"),
            TestJar.of("io.vertx", "vertx-redis-client", "4.5.1"),
            TestJar.of("io.vertx", "vertx-pg-client", "4.5.1"),
            TestJar.of("org.hibernate.orm", "hibernate-core", "7.0.0.Final"),
            TestJar.of("org.hibernate.search", "hibernate-search-mapper-orm", "8.0.0.Final"),
            TestJar.of("org.mongodb", "bson", "5.5.1"),
            TestJar.of("org.mongodb", "mongodb-driver-sync", "5.5.1"),
            TestJar.of("com.fasterxml.jackson.core", "jackson-annotations", "2.19.2"),
            TestJar.of("com.fasterxml.jackson.core", "jackson-databind", "2.19.2"),
            TestJar.of("io.smallrye", "smallrye-open-api-core", "4.0.1"),
            TestJar.of("io.smallrye", "smallrye-open-api-ui", "4.0.1"),
            TestJar.of("io.quarkus.resteasy.reactive", "resteasy-reactive-common", "3.8.6"),
            TestJar.of("org.jboss.resteasy", "resteasy-core", "6.2.1.Final")));

    private static List<String> candidates(String internalName) {
        return LIBRARIES.candidates(internalName).stream().map(JarSource::artifactId).toList();
    }

    @Test
    void ranksThirdPartyJarsByGroupPrefixThenArtifactMentions() {
        assertEquals("vertx-redis-client", candidates("io/vertx/redis/client/RedisRole").getFirst());
        assertEquals("vertx-pg-client", candidates("io/vertx/pgclient/SslMode").getFirst());
        assertEquals("vertx-core", candidates("io/vertx/core/http/ClientAuth").getFirst());
        assertEquals(3, candidates("io/vertx/core/http/ClientAuth").size());
        assertEquals("smallrye-open-api-ui", candidates("io/smallrye/openapi/ui/ThemeHref").getFirst());
        // the longest groupId match comes first
        assertEquals(List.of("hibernate-search-mapper-orm", "hibernate-core"),
                candidates("org/hibernate/search/mapper/orm/schema/management/SchemaManagementStrategyName"));
    }

    @Test
    void usesTheExplicitTableForPackagesOutsideTheirGroup() {
        assertEquals(List.of("hibernate-core"), candidates("org/hibernate/FlushMode"));
        assertEquals(List.of("bson"), candidates("org/bson/UuidRepresentation"));
        assertEquals(List.of("jackson-annotations"),
                candidates("com/fasterxml/jackson/annotation/JsonInclude$Include"));
        assertEquals("resteasy-reactive-common",
                candidates("org/jboss/resteasy/reactive/common/SomeEnum").getFirst());
        assertTrue(candidates("org/unknown/Thing").isEmpty());
        assertTrue(candidates("NoPackage").isEmpty());
    }

    @Test
    void findsClassesOnlyInCandidateJars() throws IOException {
        TestJar core = TestJar.of("io.vertx", "vertx-core", "4.5.1").with("io/vertx/core/http/ClientAuth.class",
                new byte[] {1, 2});
        TestJar other = TestJar.of("org.acme", "acme", "1.0").with("io/vertx/core/http/ClientAuth.class",
                new byte[] {3});
        LibraryClasses libraries = new LibraryClasses(List.of(core, other));
        LibraryClasses.Found found = libraries.find("io/vertx/core/http/ClientAuth").orElseThrow();
        assertEquals("vertx-core", found.jar().artifactId());
        assertEquals(0, other.probes());
        assertTrue(libraries.find("org/acme/Missing").isEmpty());
        assertEquals(1, other.probes());
        assertTrue(libraries.warnings().isEmpty());
    }

    @Test
    void skipsUnreadableJarsButNotRefusedRequests() throws IOException {
        TestJar later = TestJar.of("io.vertx", "vertx-core-extra", "4.5.1").with("io/vertx/core/http/ClientAuth.class",
                new byte[] {1});
        LibraryClasses unreadable = new LibraryClasses(List.of(failing(new IOException("corrupt jar")), later));
        assertEquals("vertx-core-extra", unreadable.find("io/vertx/core/http/ClientAuth").orElseThrow().jar()
                .artifactId());
        assertEquals(1, unreadable.warnings().size(), unreadable.warnings().toString());

        // a refused request says nothing about the jar: the class is unknown, not missing
        LibraryClasses refused = new LibraryClasses(List.of(failing(new Http.StoppedException("requests stopped")),
                later));
        int probes = later.probes();
        assertThrows(Http.StoppedException.class, () -> refused.find("io/vertx/core/http/ClientAuth"));
        assertEquals(probes, later.probes(), "the search ended at the refused jar");
        assertTrue(refused.warnings().isEmpty());
    }

    /// `io.vertx:vertx-core`, every read of which fails with `failure`.
    private static JarSource failing(IOException failure) {
        return new JarSource() {
            @Override
            public String groupId() {
                return "io.vertx";
            }

            @Override
            public String artifactId() {
                return "vertx-core";
            }

            @Override
            public String version() {
                return "4.5.1";
            }

            @Override
            public boolean exists() throws IOException {
                throw failure;
            }

            @Override
            public Set<String> entryNames() throws IOException {
                throw failure;
            }

            @Override
            public boolean contains(String entry) throws IOException {
                throw failure;
            }

            @Override
            public Optional<byte[]> read(String entry) throws IOException {
                throw failure;
            }
        };
    }

    @Test
    void stripsBlockTagsFromStoredJavadoc() {
        assertEquals("The `name` of the thing.",
                Javadocs.description("The {@code name} of the thing. More text.\n@deprecated use other\n@see X"));
        assertEquals("Asciidoc `kept` as is, see link:http://x[x].",
                Javadocs.description("Asciidoc `kept` as is, see link:http://x[x].\n@asciidoclet"));
        assertEquals("Whether <b> is HTML.", Javadocs.description("Whether &lt;b&gt; is <i>HTML</i>."));
        assertNull(Javadocs.description("@deprecated only a tag"));
        Javadocs javadocs = Javadocs.ofProperties(Map.of("a.b.Outer.Inner.member", "Text of the member."));
        assertEquals("Text of the member.", javadocs.description("a.b.Outer.Inner", "member"));
        assertNull(javadocs.description("a.b.Outer.Inner", "other"));
    }

    @Test
    void simplifiesGenericTypesLikeTheProcessor() {
        JavaType map = JavaType.returnType(
                "()Ljava/util/Map<Ljava/lang/String;Ljava/util/Map<Ljava/lang/String;Ljava/lang/Integer;>;>;");
        assertEquals("Map<String,Map<String,Integer>>", TypeResolver.simplifyGenericType(map));
        JavaType inner = JavaType.fieldType("Ljava/util/Map<Ljava/lang/String;La/b/Outer<TT;>.Inner;>;");
        assertEquals("Map<String,Inner>", TypeResolver.simplifyGenericType(inner));
        assertEquals(new JavaType.Declared("a/b/Outer$Inner", List.of()),
                ((JavaType.Declared) inner).arguments().get(1));
    }

    @Test
    void knowsTheModelFormatOfEachRelease() {
        assertEquals(ModelFormat.V3_25, ModelFormat.of("3.0.4.Final"));
        assertEquals(ModelFormat.V3_25, ModelFormat.of("3.25.4"));
        assertTrue(ModelFormat.of("3.26.0").sortedEnumValues());
        assertFalse(ModelFormat.of("3.32.1").listIndex());
        assertTrue(ModelFormat.of("3.32.2").listIndex());
        assertTrue(ModelFormat.of("4.0.0.Beta1").listIndex());
        assertEquals(ModelFormat.V3_25, ModelFormat.of("999-SNAPSHOT"));
    }

    @Test
    void tellsWhichReleasesLackDocAnnotations() {
        assertTrue(BytecodeConfigReader.lacksDocAnnotations("3.13.3"));
        assertTrue(BytecodeConfigReader.lacksDocAnnotations("3.14.0.CR1"));
        assertFalse(BytecodeConfigReader.lacksDocAnnotations("3.14.0"));
        assertFalse(BytecodeConfigReader.lacksDocAnnotations("3.40.1.1"));
    }

    @Test
    void readsRootListsAndSkipsBlankLines() {
        assertEquals(List.of("a.B", "a.C$D"), BytecodeConfigReader.rootNames("a.B\r\n\n  a.C$D  \n".getBytes()));
    }
}
