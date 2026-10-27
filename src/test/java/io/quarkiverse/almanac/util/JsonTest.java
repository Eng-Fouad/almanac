package io.quarkiverse.almanac.util;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkiverse.almanac.ScratchDir;
import io.quarkiverse.almanac.model.ConfigProperty;
import io.quarkiverse.almanac.model.ConfigSnapshot;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonTest {

    private static final ConfigProperty PROPERTY = new ConfigProperty("quarkus.x.\"n\".mode", "quarkus.x.*.mode",
            List.of(), "quarkus-x", "Mode", false, true, false, "auto", "RUN_TIME", null, List.of("auto", "off"),
            null, "io.quarkus.X#mode");

    @Test
    void recordsUseContractFieldNamesInDeclarationOrderWithoutNulls() throws IOException {
        JsonNode json = Json.readTree(Json.compact(PROPERTY).getBytes(StandardCharsets.UTF_8));
        List<String> fields = new ArrayList<>();
        json.fieldNames().forEachRemaining(fields::add);
        assertEquals(List.of("name", "key", "aliases", "ext", "type", "list", "optional", "map", "default", "phase",
                "enum", "source"), fields);
        assertEquals("auto", json.get("default").asText());
        assertEquals("off", json.get("enum").get(1).asText());
    }

    @Test
    void prettyOutputUsesUnixLineEndingsAndRoundTrips() throws Exception {
        ConfigSnapshot snapshot = new ConfigSnapshot("3.40.1", "model", "test", 1, List.of(), List.of(PROPERTY));
        try (ScratchDir dir = ScratchDir.create()) {
            Path file = dir.resolve("config.json");
            Json.writePretty(file, snapshot);
            String text = Files.readString(file);
            assertFalse(text.contains("\r"));
            assertTrue(text.endsWith("}\n"));
            assertTrue(text.contains("\n  \"version\": \"3.40.1\",\n"), text);
            assertTrue(text.contains("\"warnings\": [],"), text);
            assertEquals(snapshot, Json.read(file, ConfigSnapshot.class));
        }
    }
}
