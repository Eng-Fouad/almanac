package io.quarkiverse.almanac.maven;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BomParserTest {

    @Test
    void buildsIds() {
        assertEquals("io.vertx:vertx-core", BomParser.id("io.vertx", "vertx-core", null, null, "3.40.1"));
        assertEquals("io.vertx:vertx-core", BomParser.id("io.vertx", "vertx-core", "jar", "", "3.40.1"));
        assertEquals("io.quarkus:quarkus-core:test-jar:tests",
                BomParser.id("io.quarkus", "quarkus-core", "test-jar", "tests", "3.40.1"));
        assertEquals("org.example:shaded:shaded", BomParser.id("org.example", "shaded", "jar", "shaded", "1.0"));
        assertEquals("io.quarkus:quarkus-bom-quarkus-platform-descriptor:json:${quarkus.version}",
                BomParser.id("io.quarkus", "quarkus-bom-quarkus-platform-descriptor", "json", "3.40.1", "3.40.1"));
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
        String plain = "<project><groupId>g</groupId><artifactId>a</artifactId><version>1</version></project>";
        assertEquals(Optional.of("io.quarkus:quarkus-rest"),
                BomParser.relocation(relocated.getBytes(StandardCharsets.UTF_8)));
        assertEquals(Optional.empty(), BomParser.relocation(plain.getBytes(StandardCharsets.UTF_8)));
    }
}
