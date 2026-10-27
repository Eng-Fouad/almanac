package io.quarkiverse.almanac.cli;

import io.quarkiverse.almanac.ScratchDir;
import io.quarkiverse.almanac.maven.BomSnapshots;
import io.quarkiverse.almanac.maven.Http;
import io.quarkiverse.almanac.maven.MavenRepo;
import io.quarkiverse.almanac.maven.RemoteJar;
import io.quarkiverse.almanac.util.SnapshotStore;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// `bom --compare-pom`: the BOM of a release's git tag, resolved with its parent and imports, against the published
/// flattened BOM's snapshot.
class BomCommandTest {

    private static final String PUBLISHED = """
            <project>
              <groupId>io.quarkus</groupId>
              <artifactId>quarkus-bom</artifactId>
              <version>2.0.0.Final</version>
              <dependencyManagement>
                <dependencies>
                  <dependency>
                    <groupId>io.quarkus</groupId>
                    <artifactId>quarkus-bom-quarkus-platform-descriptor</artifactId>
                    <version>2.0.0.Final</version>
                    <type>json</type>
                    <classifier>2.0.0.Final</classifier>
                  </dependency>
                  <dependency>
                    <groupId>io.quarkus</groupId>
                    <artifactId>quarkus-core</artifactId>
                    <version>2.0.0.Final</version>
                  </dependency>
                  <dependency>
                    <groupId>io.netty</groupId>
                    <artifactId>netty-common</artifactId>
                    <version>4.1.65.Final</version>
                  </dependency>
                </dependencies>
              </dependencyManagement>
            </project>
            """;

    private static final String QUARKUS_PARENT = """
            <project>
              <groupId>io.quarkus</groupId>
              <artifactId>quarkus-parent</artifactId>
              <version>2.0.0.Final</version>
              <properties><netty.version>4.1.65.Final</netty.version></properties>
            </project>
            """;

    private static final String NETTY_BOM = """
            <project>
              <groupId>io.netty</groupId>
              <artifactId>netty-bom</artifactId>
              <version>4.1.65.Final</version>
              <dependencyManagement><dependencies><dependency>
                <groupId>io.netty</groupId><artifactId>netty-common</artifactId><version>${project.version}</version>
              </dependency></dependencies></dependencyManagement>
            </project>
            """;

    /// The BOM at the release's tag; `%s` is the version of quarkus-core, `%s` an extra entry.
    private static final String AT_TAG = """
            <project>
              <parent>
                <groupId>io.quarkus</groupId>
                <artifactId>quarkus-parent</artifactId>
                <version>2.0.0.Final</version>
              </parent>
              <artifactId>quarkus-bom</artifactId>
              <dependencyManagement>
                <dependencies>
                  <dependency>
                    <groupId>io.quarkus</groupId>
                    <artifactId>quarkus-bom-quarkus-platform-descriptor</artifactId>
                    <version>${project.version}</version>
                    <type>json</type>
                    <classifier>${project.version}</classifier>
                  </dependency>
                  <dependency>
                    <groupId>io.quarkus</groupId><artifactId>quarkus-core</artifactId><version>%s</version>
                  </dependency>
                  %s
                  <dependency>
                    <groupId>io.netty</groupId><artifactId>netty-bom</artifactId><version>${netty.version}</version>
                    <type>pom</type><scope>import</scope>
                  </dependency>
                </dependencies>
              </dependencyManagement>
            </project>
            """;

    private static void cache(Path cache, String groupId, String artifactId, String version, String pom)
            throws IOException {
        Path file = cache.resolve(MavenRepo.artifactPath(groupId, artifactId, version, "pom"));
        Files.createDirectories(file.getParent());
        Files.writeString(file, pom, StandardCharsets.UTF_8);
    }

    @Test
    void comparesTheBomAtTheTagWithThePublishedOne() throws Exception {
        try (ScratchDir dir = ScratchDir.create(); Http offline = new Http(1, true)) {
            Path cache = dir.resolve("cache");
            cache(cache, "io.quarkus", "quarkus-bom", "2.0.0.Final", PUBLISHED);
            cache(cache, "io.quarkus", "quarkus-parent", "2.0.0.Final", QUARKUS_PARENT);
            cache(cache, "io.netty", "netty-bom", "4.1.65.Final", NETTY_BOM);
            BomSnapshots boms = new BomSnapshots(new MavenRepo(MavenRepo.CENTRAL, cache, offline,
                    RemoteJar.Options.DEFAULT), new SnapshotStore(dir.resolve("snapshots"), null));
            boms.load("2.0.0.Final", false);

            Path same = dir.resolve("same.xml");
            Files.writeString(same, AT_TAG.formatted("${project.version}", ""), StandardCharsets.UTF_8);
            StringWriter equal = new StringWriter();
            assertTrue(BomCommand.compare(boms, "2.0.0.Final", same, new PrintWriter(equal)));
            assertEquals("2.0.0.Final: 3 managed dependencies resolved from " + same + " (poms read: 3, parents: 1, "
                    + "imported BOMs: 1, entries imported: 1), 3 in the snapshot: equal\n",
                    equal.toString().replace("\r\n", "\n"));

            Path other = dir.resolve("other.xml");
            Files.writeString(other, AT_TAG.formatted("1.0", "<dependency><groupId>org.extra</groupId>"
                    + "<artifactId>x</artifactId><version>1</version></dependency>"), StandardCharsets.UTF_8);
            StringWriter differences = new StringWriter();
            assertFalse(BomCommand.compare(boms, "2.0.0.Final", other, new PrintWriter(differences)));
            String report = differences.toString().replace("\r\n", "\n");
            assertTrue(report.contains(": differences: 2\n"
                    + "  other version: io.quarkus:quarkus-core 1.0 (declared in io.quarkus:quarkus-bom:2.0.0.Final), "
                    + "snapshot 2.0.0.Final\n"
                    + "  only in the pom: org.extra:x 1 (declared in io.quarkus:quarkus-bom:2.0.0.Final)\n"), report);
        }
    }
}
