package io.quarkiverse.almanac.verify;

import io.quarkiverse.almanac.model.ConfigSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static io.quarkiverse.almanac.verify.Fixtures.prop;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnapshotComparisonTest {

    private static final String NAMED_URL = "quarkus.datasource.\"datasource-name\".jdbc.url";

    /// Legacy representation: the default and the named datasource are two properties.
    static ConfigSnapshot bytecode() {
        return Fixtures.snapshot("3.25.4", "bytecode",
                prop("quarkus.http.port").type("int").defaultValue("8080").build(),
                prop("quarkus.log.level").type("Level").defaultValue("INFO").phase("BUILD_AND_RUN_TIME_FIXED")
                        .enumValues("INFO", "DEBUG").build(),
                prop("quarkus.datasource.jdbc.url").ext("quarkus-agroal").desc("The URL.").build(),
                prop(NAMED_URL).ext("quarkus-agroal").desc("The URL.").build(),
                prop("quarkus.only.in-a").build());
    }

    /// Model representation: one property with the named variant as alias.
    static ConfigSnapshot model() {
        return Fixtures.snapshot("3.25.4", "model",
                prop("quarkus.http.port").type("int").defaultValue("8081").phase("BUILD_TIME").deprecated("")
                        .build(),
                prop("quarkus.log.level").type("Level").defaultValue("INFO").phase("BUILD_AND_RUN_TIME_FIXED")
                        .enumValues("DEBUG", "INFO").build(),
                prop("quarkus.datasource.jdbc.url").aliases(NAMED_URL).ext("quarkus-agroal").desc("The URL.")
                        .build(),
                prop("quarkus.only.in-b").build());
    }

    @Test
    void identicalSnapshotsAgreeEverywhere() {
        SnapshotComparison.Report report = compare(model(), model(), SnapshotComparison.Options.DEFAULTS);

        assertEquals(new SnapshotComparison.Keys(4, 4, 0, 0, 0, 1.0), report.keys());
        assertTrue(report.fields().stream().allMatch(field -> field.ratio() == 1.0 && field.compared() == 4));
        assertTrue(report.pass());
        assertTrue(report.examples().isEmpty());
        assertEquals(SnapshotComparison.FIELDS, report.fields().stream().map(Agreement::check).toList());
    }

    @Test
    void matchesKeysAliasAwareAndCountsAliasMatchesSeparately() {
        SnapshotComparison.Report report = compare(bytecode(), model(), SnapshotComparison.Options.DEFAULTS);

        // port, level and the default URL by key; the named URL via the model's alias
        assertEquals(new SnapshotComparison.Keys(4, 3, 1, 1, 1, 4 / 6.0), report.keys());
        Map<String, Examples> examples = examples(report);
        assertEquals(List.of(new Examples.Example("quarkus.datasource.*.jdbc.url ~ quarkus.datasource.jdbc.url",
                NAMED_URL, "quarkus.datasource.jdbc.url")), examples.get("via-alias").items());
        assertEquals("quarkus.only.in-a", examples.get("only-in-a").items().getFirst().key());
        assertEquals("quarkus.only.in-b", examples.get("only-in-b").items().getFirst().key());
    }

    @Test
    void comparesFieldsCountingOneSideNullSeparately() {
        SnapshotComparison.Report report = compare(bytecode(), model(), SnapshotComparison.Options.DEFAULTS);
        Map<String, Agreement> fields = report.fields().stream()
                .collect(Collectors.toMap(Agreement::check, field -> field));

        // name and aliases only on the 3 pairs with the same key
        assertEquals(new Agreement("name", 3, 3, 0, 0, 1.0, true), fields.get("name"));
        assertEquals(new Agreement("aliases", 3, 2, 0, 1, 2 / 3.0, true), fields.get("aliases"));
        // the other fields on all 4 pairs
        assertEquals(new Agreement("default", 4, 3, 1, 0, 0.75, true), fields.get("default"));
        assertEquals(new Agreement("phase", 4, 3, 1, 0, 0.75, true), fields.get("phase"));
        assertEquals(new Agreement("deprecated", 4, 3, 0, 1, 0.75, true), fields.get("deprecated"));
        // enum values compare as sets
        assertEquals(new Agreement("enum", 4, 4, 0, 0, 1.0, true), fields.get("enum"));
        assertEquals(new Agreement("desc", 4, 4, 0, 0, 1.0, false), report.desc());

        Map<String, Examples> examples = examples(report);
        assertEquals(List.of(new Examples.Example("quarkus.http.port", "8080", "8081")),
                examples.get("default").items());
        assertEquals(List.of(new Examples.Example("quarkus.http.port", null, "\"\"")),
                examples.get("deprecated" + Texts.ONE_SIDE_NULL).items());
        assertEquals(List.of(new Examples.Example("quarkus.datasource.jdbc.url", null,
                "[quarkus.datasource.*.jdbc.url]")), examples.get("aliases" + Texts.ONE_SIDE_NULL).items());
    }

    @Test
    void thresholdsDecideTheResult() {
        SnapshotComparison.Report failing = compare(bytecode(), model(), SnapshotComparison.Options.DEFAULTS);
        assertFalse(failing.pass());
        assertEquals(List.of("key agreement 66.66 % < 99.00 %", "aliases agreement 66.66 % < 99.00 %",
                "default agreement 75.00 % < 99.00 %", "phase agreement 75.00 % < 99.00 %",
                "deprecated agreement 75.00 % < 99.00 %"), failing.failures());

        SnapshotComparison.Report passing = compare(bytecode(), model(), new SnapshotComparison.Options(1, 0.6, 0.6));
        assertTrue(passing.pass());
        List<String> lines = passing.lines();
        assertEquals(Texts.line("keys", "4 in both (3 same key, 1 via an alias), 1 only in a, 1 only in b: "
                + "66.66 % agreement"), lines.get(2));
        assertTrue(lines.contains(Texts.line("result", "PASS")));
        assertTrue(lines.contains("default differs: 1"), String.join("\n", lines));
    }

    @Test
    void examplesAreLimited() {
        SnapshotComparison.Report report = compare(bytecode(), model(), new SnapshotComparison.Options(0, 0, 0));

        assertTrue(report.examples().stream().allMatch(kind -> kind.items().isEmpty()));
        assertEquals(1, examples(report).get("only-in-a").total());
    }

    private static SnapshotComparison.Report compare(ConfigSnapshot a, ConfigSnapshot b,
            SnapshotComparison.Options options) {
        return SnapshotComparison.compare(new SnapshotComparison.Input("a", null, a),
                new SnapshotComparison.Input("b", null, b), options);
    }

    private static Map<String, Examples> examples(SnapshotComparison.Report report) {
        return report.examples().stream().collect(Collectors.toMap(Examples::kind, kind -> kind));
    }
}
