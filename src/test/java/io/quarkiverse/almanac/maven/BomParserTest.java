package io.quarkiverse.almanac.maven;

import io.quarkiverse.almanac.model.ManagedDep;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BomParserTest {

    private static final String BOM = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0"
                     xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
              <modelVersion>4.0.0</modelVersion>
              <groupId>io.quarkus</groupId>
              <artifactId>quarkus-bom</artifactId>
              <version>3.40.1</version>
              <dependencyManagement>
                <dependencies>
                  <dependency>
                    <groupId>io.quarkus</groupId>
                    <artifactId>quarkus-bom-quarkus-platform-descriptor</artifactId>
                    <version>3.40.1</version>
                    <type>json</type>
                    <classifier>3.40.1</classifier>
                  </dependency>
                  <dependency>
                    <groupId>io.vertx</groupId>
                    <artifactId>vertx-core</artifactId>
                    <version>4.5.21</version>
                    <exclusions>
                      <exclusion>
                        <groupId>io.netty</groupId>
                        <artifactId>netty-codec-dns</artifactId>
                      </exclusion>
                    </exclusions>
                  </dependency>
                  <dependency>
                    <groupId>io.quarkus</groupId>
                    <artifactId>quarkus-core</artifactId>
                    <version>3.40.1</version>
                  </dependency>
                  <dependency>
                    <groupId>io.quarkus</groupId>
                    <artifactId>quarkus-core</artifactId>
                    <version>3.40.1</version>
                    <type>test-jar</type>
                    <classifier>tests</classifier>
                  </dependency>
                  <dependency>
                    <groupId>com.google.protobuf</groupId>
                    <artifactId>protoc</artifactId>
                    <version>4.35.0</version>
                    <type>exe</type>
                    <classifier>linux-x86_64</classifier>
                  </dependency>
                  <dependency>
                    <groupId>org.example</groupId>
                    <artifactId>shaded</artifactId>
                    <version>1.0</version>
                    <type>jar</type>
                    <classifier>shaded</classifier>
                  </dependency>
                  <dependency>
                    <groupId>org.example</groupId>
                    <artifactId>no-version</artifactId>
                  </dependency>
                </dependencies>
              </dependencyManagement>
            </project>
            """;

    @Test
    void parsesManagedDependenciesIntoSortedIds() throws IOException {
        List<ManagedDep> deps = BomParser.parse(BOM.getBytes(StandardCharsets.UTF_8), "3.40.1");
        assertEquals(List.of(
                new ManagedDep("com.google.protobuf:protoc:exe:linux-x86_64", "4.35.0"),
                new ManagedDep("io.quarkus:quarkus-bom-quarkus-platform-descriptor:json:${quarkus.version}", "3.40.1"),
                new ManagedDep("io.quarkus:quarkus-core", "3.40.1"),
                new ManagedDep("io.quarkus:quarkus-core:test-jar:tests", "3.40.1"),
                new ManagedDep("io.vertx:vertx-core", "4.5.21"),
                new ManagedDep("org.example:shaded:shaded", "1.0")), deps);
    }

    @Test
    void parsesPomsWithoutNamespace() throws IOException {
        String pom = "<project><dependencyManagement><dependencies><dependency><groupId>g</groupId>"
                + "<artifactId>a</artifactId><version>1</version></dependency></dependencies>"
                + "</dependencyManagement></project>";
        assertEquals(List.of(new ManagedDep("g:a", "1")), BomParser.parse(pom.getBytes(StandardCharsets.UTF_8), "9"));
    }

    @Test
    void readsRelocations() throws IOException {
        String relocated = """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <groupId>io.quarkus</groupId>
                  <artifactId>quarkus-resteasy-reactive</artifactId>
                  <version>3.9.0</version>
                  <distributionManagement>
                    <relocation>
                      <artifactId>quarkus-rest</artifactId>
                    </relocation>
                  </distributionManagement>
                </project>
                """;
        assertEquals(Optional.of("io.quarkus:quarkus-rest"),
                BomParser.relocation(relocated.getBytes(StandardCharsets.UTF_8)));
        assertEquals(Optional.empty(), BomParser.relocation(BOM.getBytes(StandardCharsets.UTF_8)));
    }
}
