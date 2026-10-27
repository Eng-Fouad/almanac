package io.quarkiverse.almanac.maven;

import io.quarkiverse.almanac.ScratchDir;
import io.quarkiverse.almanac.model.BomSnapshot;
import io.quarkiverse.almanac.model.ManagedDep;
import io.quarkiverse.almanac.util.SnapshotStore;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// [BomSnapshots] over an offline repository cache: a BOM that is not flattened is resolved with its parents and
/// imports, and a BOM that keeps an unresolved version writes nothing.
class BomSnapshotsTest {

    /// Shaped like the published Quarkus 1.x BOMs: a parent chain, properties and imported BOMs.
    static final String UNFLATTENED = """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <parent>
                <groupId>io.quarkus</groupId>
                <artifactId>quarkus-parent</artifactId>
                <version>1.0.0.Final</version>
                <relativePath>../../pom.xml</relativePath>
              </parent>
              <artifactId>quarkus-bom</artifactId>
              <packaging>pom</packaging>
              <properties>
                <netty.version>4.1.42.Final</netty.version>
                <resteasy.version>4.3.1.Final</resteasy.version>
              </properties>
              <dependencyManagement>
                <dependencies>
                  <dependency>
                    <groupId>io.netty</groupId>
                    <artifactId>netty-bom</artifactId>
                    <version>${netty.version}</version>
                    <type>pom</type>
                    <scope>import</scope>
                  </dependency>
                  <dependency>
                    <groupId>io.quarkus</groupId>
                    <artifactId>quarkus-core</artifactId>
                    <version>${project.version}</version>
                  </dependency>
                  <dependency>
                    <groupId>org.jboss.resteasy</groupId>
                    <artifactId>resteasy-core</artifactId>
                    <version>${resteasy.version}</version>
                  </dependency>
                  <dependency>
                    <groupId>io.netty</groupId>
                    <artifactId>netty-codec</artifactId>
                    <version>${netty.version}</version>
                  </dependency>
                  <dependency>
                    <groupId>org.graalvm.sdk</groupId>
                    <artifactId>graal-sdk</artifactId>
                    <version>${graal-sdk.version}</version>
                  </dependency>
                </dependencies>
              </dependencyManagement>
            </project>
            """;

    static final String QUARKUS_PARENT = """
            <project>
              <parent>
                <groupId>org.jboss</groupId>
                <artifactId>jboss-parent</artifactId>
                <version>35</version>
              </parent>
              <groupId>io.quarkus</groupId>
              <artifactId>quarkus-parent</artifactId>
              <version>1.0.0.Final</version>
              <properties>
                <graal-sdk.version>19.2.1</graal-sdk.version>
              </properties>
            </project>
            """;

    static final String JBOSS_PARENT = """
            <project>
              <groupId>org.jboss</groupId>
              <artifactId>jboss-parent</artifactId>
              <version>35</version>
            </project>
            """;

    static final String NETTY_BOM = """
            <project>
              <parent>
                <groupId>org.sonatype.oss</groupId>
                <artifactId>oss-parent</artifactId>
                <version>7</version>
              </parent>
              <groupId>io.netty</groupId>
              <artifactId>netty-bom</artifactId>
              <version>4.1.42.Final</version>
              <dependencyManagement>
                <dependencies>
                  <dependency>
                    <groupId>${project.groupId}</groupId>
                    <artifactId>netty-codec</artifactId>
                    <version>4.0.0-loses</version>
                  </dependency>
                  <dependency>
                    <groupId>${project.groupId}</groupId>
                    <artifactId>netty-common</artifactId>
                    <version>${project.version}</version>
                  </dependency>
                </dependencies>
              </dependencyManagement>
            </project>
            """;

    static final String OSS_PARENT = """
            <project>
              <groupId>org.sonatype.oss</groupId>
              <artifactId>oss-parent</artifactId>
              <version>7</version>
            </project>
            """;

    static final List<ManagedDep> RESOLVED = List.of(
            new ManagedDep("io.netty:netty-codec", "4.1.42.Final"),
            new ManagedDep("io.netty:netty-common", "4.1.42.Final"),
            new ManagedDep("io.quarkus:quarkus-core", "1.0.0.Final"),
            new ManagedDep("org.graalvm.sdk:graal-sdk", "19.2.1"),
            new ManagedDep("org.jboss.resteasy:resteasy-core", "4.3.1.Final"));

    /// Writes a pom into a repository cache folder, as [MavenRepo] keeps downloads.
    static void cache(Path cache, String coordinates, String pom) throws IOException {
        String[] parts = coordinates.split(":");
        Path file = cache.resolve(MavenRepo.artifactPath(parts[0], parts[1], parts[2], "pom"));
        Files.createDirectories(file.getParent());
        Files.writeString(file, pom, StandardCharsets.UTF_8);
    }

    /// A cache holding the 1.0.0.Final BOM of [#UNFLATTENED] with its parents and import, released 2019-11-25.
    static void cacheUnflattened(Path cache) throws IOException {
        cache(cache, "io.quarkus:quarkus-bom:1.0.0.Final", UNFLATTENED);
        Files.writeString(cache.resolve(MavenRepo.artifactPath("io.quarkus", "quarkus-bom", "1.0.0.Final", "pom")
                + ".last-modified"), "2019-11-25T10:03:01Z\n", StandardCharsets.UTF_8);
        cache(cache, "io.quarkus:quarkus-parent:1.0.0.Final", QUARKUS_PARENT);
        cache(cache, "org.jboss:jboss-parent:35", JBOSS_PARENT);
        cache(cache, "io.netty:netty-bom:4.1.42.Final", NETTY_BOM);
        cache(cache, "org.sonatype.oss:oss-parent:7", OSS_PARENT);
    }

    @Test
    void resolvesAndWritesABomThatIsNotFlattened() throws Exception {
        try (ScratchDir dir = ScratchDir.create(); Http offline = new Http(1, true)) {
            cacheUnflattened(dir.resolve("cache"));
            SnapshotStore store = new SnapshotStore(dir.resolve("snapshots"), null);
            BomSnapshots boms = new BomSnapshots(
                    new MavenRepo(MavenRepo.CENTRAL, dir.resolve("cache"), offline, RemoteJar.Options.DEFAULT), store);

            BomSnapshot snapshot = boms.load("1.0.0.Final", false);

            assertEquals(new BomSnapshot("1.0.0.Final", "2019-11-25", RESOLVED), snapshot);
            assertEquals(snapshot, store.read("1.0.0.Final", SnapshotStore.BOM, BomSnapshot.class));
            assertEquals(snapshot, boms.load("1.0.0.Final", false), "reused");
        }
    }

    @Test
    void aBomWithAnUnresolvedVersionWritesNothing() throws Exception {
        String flattened = """
                <project>
                  <groupId>io.quarkus</groupId>
                  <artifactId>quarkus-bom</artifactId>
                  <version>2.4.1.Final</version>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>io.netty</groupId>
                        <artifactId>netty-tcnative</artifactId>
                        <version>${tcnative.version}</version>
                      </dependency>
                      <dependency>
                        <groupId>io.netty</groupId>
                        <artifactId>netty-common</artifactId>
                        <version>4.1.69.Final</version>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                </project>
                """;
        try (ScratchDir dir = ScratchDir.create(); Http offline = new Http(1, true)) {
            cache(dir.resolve("cache"), "io.quarkus:quarkus-bom:2.4.1.Final", flattened);
            SnapshotStore store = new SnapshotStore(dir.resolve("snapshots"), null);
            BomSnapshots boms = new BomSnapshots(
                    new MavenRepo(MavenRepo.CENTRAL, dir.resolve("cache"), offline, RemoteJar.Options.DEFAULT), store);

            IOException e = assertThrows(IOException.class, () -> boms.load("2.4.1.Final", false));
            assertTrue(e.getMessage().contains("${tcnative.version} (declared in io.quarkus:quarkus-bom:2.4.1.Final): "
                    + "io.netty:netty-tcnative"), e.getMessage());
            assertFalse(store.exists("2.4.1.Final", SnapshotStore.BOM));

            BomSnapshot old = new BomSnapshot("2.4.1.Final", "2021-11-03",
                    List.of(new ManagedDep("io.netty:netty-tcnative", "${tcnative.version}")));
            store.write("2.4.1.Final", SnapshotStore.BOM, old);
            assertEquals(old, boms.load("2.4.1.Final", false), "an existing snapshot is reused with a warning");
            assertThrows(IOException.class, () -> boms.load("2.4.1.Final", true));
            assertEquals(old, store.read("2.4.1.Final", SnapshotStore.BOM, BomSnapshot.class));
        }
    }

    @Test
    void tellsUnresolvedSnapshotEntries() {
        assertTrue(BomSnapshots.unresolved(new ManagedDep("io.netty:netty-tcnative", "${tcnative.version}")));
        assertTrue(BomSnapshots.unresolved(new ManagedDep("${group}:a", "1")));
        assertFalse(BomSnapshots.unresolved(
                new ManagedDep("io.quarkus:quarkus-bom-quarkus-platform-descriptor:json:${quarkus.version}", "1")));
    }
}
