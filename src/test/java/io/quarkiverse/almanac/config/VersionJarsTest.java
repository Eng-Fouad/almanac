package io.quarkiverse.almanac.config;

import io.quarkiverse.almanac.maven.Http;
import io.quarkiverse.almanac.maven.MavenRepo;
import io.quarkiverse.almanac.maven.RemoteJar;
import io.quarkiverse.almanac.model.BomSnapshot;
import io.quarkiverse.almanac.model.ManagedDep;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VersionJarsTest {

    private final VersionJars jars = new VersionJars("3.9.0", List.of(
            InMemoryJar.of("quarkus-rest", "3.9.0")
                    .with("META-INF/quarkus-extension.yaml", "name: REST")
                    .with("io/quarkus/rest/Config.class", new byte[] {1})
                    .with("io/quarkus/rest/Config$Nested.class", new byte[] {2}),
            InMemoryJar.of("quarkus-core", "3.9.0")
                    .with("META-INF/quarkus-extension.yaml", "name: Core")
                    .with("io/quarkus/runtime/ApplicationConfig.class", new byte[] {3})
                    .with("META-INF/versions/21/io/quarkus/runtime/ApplicationConfig.class", new byte[] {4}),
            InMemoryJar.missing("quarkus-resteasy-reactive", "3.9.0"),
            InMemoryJar.missing("quarkus-resteasy-reactive-deployment", "3.9.0"),
            InMemoryJar.of("quarkus-resteasy-reactive-jackson", "3.9.0")
                    .with("META-INF/maven/io.quarkus/quarkus-resteasy-reactive-jackson/pom.xml", "<project/>"),
            InMemoryJar.missing("quarkus-gone", "3.9.0")),
            jar -> jar.artifactId().startsWith("quarkus-resteasy-reactive")
                    ? Optional.of("io.quarkus:" + jar.artifactId().replace("resteasy-reactive", "rest"))
                    : Optional.empty(),
            2);

    @Test
    void keepsExistingJarsSortedByArtifactId() throws IOException {
        assertEquals(List.of("quarkus-core", "quarkus-rest", "quarkus-resteasy-reactive-jackson"),
                jars.jars().stream().map(JarSource::artifactId).toList());
        assertEquals(6, jars.candidateCount());
    }

    @Test
    void resolvesRelocationsOfMissingAndEmptyJars() throws IOException {
        assertEquals(Map.of("quarkus-resteasy-reactive", "io.quarkus:quarkus-rest",
                "quarkus-resteasy-reactive-deployment", "io.quarkus:quarkus-rest-deployment",
                "quarkus-resteasy-reactive-jackson", "io.quarkus:quarkus-rest-jackson"), jars.relocations());
    }

    @Test
    void findsEntriesAndClasses() throws IOException {
        assertEquals(2, jars.jarsContaining("META-INF/quarkus-extension.yaml").size());
        assertEquals("name: REST", new String(jars.readAll("META-INF/quarkus-extension.yaml").values().stream()
                .toList().get(1), StandardCharsets.UTF_8));
        assertEquals("quarkus-rest", jars.jarOf("io/quarkus/rest/Config$Nested").orElseThrow().artifactId());
        assertArrayEquals(new byte[] {3}, jars.classBytes("io/quarkus/runtime/ApplicationConfig").orElseThrow());
        assertTrue(jars.classBytes("io/quarkus/Missing").isEmpty());
    }

    @Test
    void listsUsableThirdPartyJarsOfTheBom() {
        BomSnapshot bom = new BomSnapshot("3.2.12.Final", "2024-01-01", List.of(
                new ManagedDep("io.quarkus:quarkus-core", "3.2.12.Final"),
                new ManagedDep("io.vertx:vertx-core", "4.4.6"),
                new ManagedDep("org.jboss:jdk-misc", "${version.jdk-misc}"),
                new ManagedDep("org.acme:acme-bom:pom", "1.0"),
                new ManagedDep("org.acme:acme:${quarkus.version}", "1.0")));
        try (Http http = new Http(3, true)) {
            MavenRepo repo = new MavenRepo(MavenRepo.CENTRAL, Path.of("unused-cache"), http, RemoteJar.Options.DEFAULT);
            VersionJars jars = VersionJars.of(bom, repo);
            assertEquals(List.of("io.vertx:vertx-core:4.4.6"), jars.libraries().stream()
                    .map(jar -> jar.groupId() + ":" + jar.artifactId() + ":" + jar.version()).toList());
            assertEquals(1, jars.candidateCount());
            assertEquals(3, jars.parallelism());
        }
    }

    @Test
    void rejectsVersionsThatCannotBePartOfAPath() {
        assertTrue(VersionJars.isUsableVersion("4.1.115.Final"));
        assertTrue(VersionJars.isUsableVersion("2.0.0-alpha+build"));
        assertFalse(VersionJars.isUsableVersion("${version.jdk-misc}"));
        assertFalse(VersionJars.isUsableVersion(""));
        assertFalse(VersionJars.isUsableVersion(null));
        assertFalse(VersionJars.isUsableVersion("1 0"));
    }
}
