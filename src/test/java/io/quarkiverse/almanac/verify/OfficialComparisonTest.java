package io.quarkiverse.almanac.verify;

import io.quarkiverse.almanac.model.ConfigSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static io.quarkiverse.almanac.verify.Fixtures.prop;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OfficialComparisonTest {

    private static final String NAMED_JDBC = "quarkus.datasource.\"datasource-name\".jdbc";

    /// The rows of new-generator.html, except: the docker row is missing, the URL is BUILD_TIME (the page has no lock),
    /// the fault-tolerance key is named differently; plus three properties that are not on the page.
    static ConfigSnapshot newGeneratorSnapshot() {
        return Fixtures.snapshot("3.20.6", "model",
                prop("quarkus.datasource.jdbc").aliases(NAMED_JDBC).ext("quarkus-agroal").type("boolean")
                        .defaultValue("true").phase("BUILD_AND_RUN_TIME_FIXED").build(),
                prop("quarkus.datasource.jdbc.transactions")
                        .aliases("quarkus.datasource.\"datasource-name\".jdbc.transactions").ext("quarkus-agroal")
                        .type("TransactionIntegration").enumValues("enabled", "xa", "disabled")
                        .defaultValue("enabled").build(),
                prop("quarkus.datasource.jdbc.url").ext("quarkus-agroal").phase("BUILD_TIME").build(),
                prop("quarkus.datasource.jdbc.additional-jdbc-properties.\"property-key\"").ext("quarkus-agroal")
                        .type("Map<String,String>").map().build(),
                prop("quarkus.debug.dump-build-metrics").type("boolean").defaultValue("false").phase("BUILD_TIME")
                        .deprecated("3.31").build(),
                prop("quarkus.default-locale").type("Locale").defaultValue("`en-US` in native mode")
                        .deprecated("").build(),
                prop("quarkus.http.read-timeout").ext("quarkus-vertx-http").type("Duration").defaultValue("60S")
                        .optional().build(),
                prop("quarkus.fault-tolerance.\"identifier\".retry.max-retries")
                        .ext("quarkus-smallrye-fault-tolerance").type("int").defaultValue("<default>").build(),
                prop("quarkus.datasource.old-flag").ext("quarkus-agroal").type("boolean").deprecated("").build(),
                prop("quarkus.apicurio-registry.devservices.enabled").ext("quarkus-schema-registry-devservice")
                        .type("boolean").phase("BUILD_TIME").build(),
                prop("quarkus.http.new-thing").ext("quarkus-vertx-http").build());
    }

    @Test
    void comparesNamesPresenceAndPhase() {
        OfficialComparison.Report report = compare(newGeneratorSnapshot(), "new-generator.html",
                OfficialComparison.Options.DEFAULTS);

        assertEquals(new OfficialComparison.Names(11, 10, 10 / 11.0, 1), report.names());
        assertEquals(new OfficialComparison.Presence(11, 8, 1, 1, 1), report.presence());
        Agreement phase = report.phase().agreement();
        assertEquals(8, phase.compared());
        assertEquals(7, phase.equal());
        assertEquals(0, report.phase().lockedButRunTime());
        assertEquals(1, report.phase().unlockedButBuildTime());
        assertFalse(report.pass());
        assertEquals(2, report.failures().size());
        assertTrue(report.failures().get(0).startsWith("name agreement 90.90 %"), report.failures().get(0));
        assertTrue(report.failures().get(1).startsWith("phase agreement 87.50 %"), report.failures().get(1));

        Map<String, Integer> totals = report.examples().stream()
                .collect(Collectors.toMap(Examples::kind, Examples::total));
        assertEquals(Map.of("page-only", 1, "ours-only", 1, "ours-only/hidden", 1, "ours-only/deprecated", 1,
                "phase/unlocked-but-build-time", 1, "env-var", 1, "display-name", 1), totals);
        Examples pageOnly = report.examples().getFirst();
        assertEquals("quarkus.docker.dockerfile-jvm-path", pageOnly.items().getFirst().key());
    }

    @Test
    void informationalChecksNormaliseTypesAndDefaults() {
        OfficialComparison.Report report = compare(newGeneratorSnapshot(), "new-generator.html",
                OfficialComparison.Options.DEFAULTS);

        Map<String, Agreement> checks = report.informational().stream()
                .collect(Collectors.toMap(Agreement::check, check -> check));
        assertEquals(List.of("env-var", "env-var-rule", "display-name", "type", "default", "deprecated", "ext"),
                report.informational().stream().map(Agreement::check).toList());
        // the page's own env vars all follow ConfigKeys.envVar of its paths, "<identifier>" restored included
        assertEquals(9, checks.get("env-var-rule").equal());
        assertEquals(9, checks.get("env-var-rule").compared());
        // our "identifier" key name differs from the page's "<identifier>"
        assertEquals(7, checks.get("env-var").equal());
        assertEquals(7, checks.get("display-name").equal());
        // enums as value sets, Duration with its info icon, Map, Locale
        assertEquals(8, checks.get("type").equal());
        // "`en-US` in native mode", "required" (no default), "<default>"
        assertEquals(8, checks.get("default").equal());
        assertEquals(8, checks.get("deprecated").equal());
        assertEquals(8, checks.get("ext").equal());
        assertTrue(report.informational().stream().noneMatch(Agreement::gating));
    }

    @Test
    void thresholdsDecide() {
        OfficialComparison.Report report = compare(newGeneratorSnapshot(), "new-generator.html",
                new OfficialComparison.Options(2, 0.9, 0.875));

        assertTrue(report.pass(), report.failures().toString());
        assertTrue(report.lines().contains(Texts.line("result", "PASS")), String.join("\n", report.lines()));
    }

    @Test
    void oldPagesNameLeafMapsWithoutTheirKey() {
        ConfigSnapshot snapshot = Fixtures.snapshot("3.2.12.Final", "bytecode",
                prop("quarkus.datasource.jdbc").aliases("quarkus.datasource.\"data-sources\".jdbc")
                        .ext("quarkus-agroal").type("boolean").defaultValue("true")
                        .phase("BUILD_AND_RUN_TIME_FIXED").build(),
                prop("quarkus.datasource.jdbc.transactions").ext("quarkus-agroal").type("TransactionIntegration")
                        .enumValues("enabled", "xa", "disabled").defaultValue("enabled").build(),
                prop("quarkus.datasource.jdbc.additional-jdbc-properties.\"additional-jdbc-properties\"")
                        .ext("quarkus-agroal").type("Map<String,String>").map().build(),
                prop("quarkus.package.type").defaultValue("jar").phase("BUILD_TIME").build(),
                prop("quarkus.log.category.\"categories\".level").type("Level").defaultValue("inherit").build(),
                prop("quarkus.http.host").build());

        OfficialComparison.Report report = compare(snapshot, "old-generator.html", OfficialComparison.Options.DEFAULTS);

        assertEquals(new OfficialComparison.Names(7, 7, 1.0, 0), report.names());
        assertEquals(new OfficialComparison.Presence(6, 6, 0, 0, 0), report.presence());
        assertEquals(1.0, report.phase().agreement().ratio());
        assertTrue(report.pass(), report.failures().toString());
        Map<String, Agreement> checks = report.informational().stream()
                .collect(Collectors.toMap(Agreement::check, check -> check));
        // only the path that matched is compared: the page's alternative "datasource-name" vs our "data-sources" is not
        assertEquals(6, checks.get("display-name").equal());
        assertEquals(6, checks.get("env-var").equal());
    }

    @Test
    void leafMapKeysAreKeptOnNewPages() {
        ConfigSnapshot snapshot = Fixtures.snapshot("3.20.6", "model",
                prop("quarkus.datasource.jdbc.additional-jdbc-properties").ext("quarkus-agroal").build());

        OfficialComparison.Report report = compare(snapshot, "new-generator.html", OfficialComparison.Options.DEFAULTS);

        assertEquals(0, report.names().found());
        assertEquals(1, report.presence().other());
    }

    @Test
    void warnsWhenThePageDocumentsAnotherStream() {
        OfficialComparison.PageSide page = OfficialComparison.pageSide("page.html",
                Fixtures.page("new-generator.html"));

        assertEquals(List.of(), OfficialComparison.warnings("3.20.6", page, "3.20"));
        assertEquals(List.of("the page documents 3.20 (version selector), not stream 3.27",
                        "the snapshot is from stream 3.20, the page from stream 3.27"),
                OfficialComparison.warnings("3.20.6", page, "3.27"));
        OfficialComparison.PageSide named = new OfficialComparison.PageSide("p", "NEW", "3.20", "3.20.7", "page title",
                0, 0, 0, 0, 0, 0);
        assertEquals(List.of("the page documents 3.20.7, the snapshot is 3.20.6"),
                OfficialComparison.warnings("3.20.6", named, "3.20"));
    }

    @Test
    void pageSummaryCountsRowsPathsKeysAndFlags() {
        OfficialComparison.PageSide page = OfficialComparison.pageSide("page.html",
                Fixtures.page("new-generator.html"));

        assertEquals(new OfficialComparison.PageSide("page.html", "NEW", "3.20", null, "version selector", 9, 11, 11,
                3, 2, 0), page);
        assertEquals("page.html: new generator, documents 3.20 (version selector), 9 rows, 11 paths (11 keys), "
                + "3 fixed at build time, 2 deprecated, 0 without env var", page.summary());
    }

    @Test
    void normalisation() {
        assertEquals("true if the URL is set,false otherwise",
                OfficialComparison.normalisedDefault("`true` if the URL is set, `false` otherwise"));
        assertEquals("gzip,deflate", OfficialComparison.normalisedDefault("gzip, deflate"));
        assertNull(OfficialComparison.normalisedDefault(" `` "));
        assertEquals("quarkus.a.b", OfficialComparison.withoutMapKey("quarkus.a.b.\"key\""));
        assertNull(OfficialComparison.withoutMapKey("quarkus.a.\"key\".b"));
        assertEquals("quarkus-amazon-lambda", OfficialComparison.extFamily("quarkus-amazon-lambda-common"));
        assertEquals("quarkus-rest", OfficialComparison.extFamily("quarkus-rest"));
        assertEquals("list of [a, b]", OfficialComparison.ourType(prop("x.y").list().enumValues("b", "a").build()));
        assertEquals("list of string", OfficialComparison.ourType(prop("x.y").list().build()));
    }

    private static OfficialComparison.Report compare(ConfigSnapshot snapshot, String page,
            OfficialComparison.Options options) {
        return OfficialComparison.compare(new SnapshotComparison.Input("ours", null, snapshot), Fixtures.page(page),
                page, snapshot.version().startsWith("3.2.") ? "3.2" : "3.20", options);
    }
}
