package io.quarkiverse.almanac.util;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.core.util.Separators;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/// Shared Jackson configuration.
///
/// All output omits `null` fields (records serialise their components in declaration order, using the
/// names from `@JsonProperty` where present), uses UTF-8 and `\n` line endings and ends with a newline.
/// Snapshots (`work/snapshots`) are pretty-printed; site data (`site/data`) is compact.
public final class Json {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setDefaultPropertyInclusion(JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL))
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private static final ObjectWriter PRETTY = MAPPER.writer(prettyPrinter());
    private static final ObjectWriter COMPACT = MAPPER.writer();

    private Json() {
    }

    /// The shared mapper: lenient reading (unknown properties ignored), compact writing without nulls.
    public static ObjectMapper mapper() {
        return MAPPER;
    }

    /// Parses a JSON document into a tree.
    public static JsonNode readTree(byte[] json) throws IOException {
        return MAPPER.readTree(json);
    }

    /// Reads a JSON file (UTF-8) into the given type, e.g. a snapshot record.
    public static <T> T read(Path file, Class<T> type) throws IOException {
        return MAPPER.readValue(Files.readAllBytes(file), type);
    }

    /// Serialises a value pretty-printed (two-space indent, `"key": value`), with a trailing newline.
    public static String pretty(Object value) throws IOException {
        return PRETTY.writeValueAsString(value) + "\n";
    }

    /// Serialises a value as compact JSON with a trailing newline.
    public static String compact(Object value) throws IOException {
        return COMPACT.writeValueAsString(value) + "\n";
    }

    /// Atomically writes a pretty-printed JSON file (snapshot format).
    public static void writePretty(Path file, Object value) throws IOException {
        AtomicFiles.writeString(file, pretty(value));
    }

    /// Atomically writes a compact JSON file (site data format).
    public static void writeCompact(Path file, Object value) throws IOException {
        AtomicFiles.writeString(file, compact(value));
    }

    private static DefaultPrettyPrinter prettyPrinter() {
        DefaultIndenter indenter = new DefaultIndenter("  ", "\n");
        Separators separators = Separators.createDefaultInstance()
                .withObjectFieldValueSpacing(Separators.Spacing.AFTER)
                .withObjectEmptySeparator("")
                .withArrayEmptySeparator("");
        return new DefaultPrettyPrinter(separators)
                .withObjectIndenter(indenter)
                .withArrayIndenter(indenter);
    }
}
