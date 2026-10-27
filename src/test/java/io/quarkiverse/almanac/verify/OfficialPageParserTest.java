package io.quarkiverse.almanac.verify;

import io.quarkiverse.almanac.verify.OfficialPage.Documented;
import io.quarkiverse.almanac.verify.OfficialPage.Generator;
import io.quarkiverse.almanac.verify.OfficialPage.Row;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OfficialPageParserTest {

    @Test
    void readsTheRowsOfTheNewGenerator() {
        OfficialPage page = Fixtures.page("new-generator.html");

        assertEquals(Generator.NEW, page.generator());
        assertEquals(new Documented("3.20", null, "version selector"), page.documented());
        assertEquals(9, page.rows().size());
        assertEquals(11, page.names());

        Row jdbc = row(page, "quarkus.datasource.jdbc");
        assertEquals(List.of("quarkus.datasource.\"datasource-name\".jdbc"), jdbc.alternatives());
        assertEquals("quarkus-agroal_quarkus-datasource-jdbc", jdbc.anchor());
        assertEquals("quarkus-agroal", jdbc.anchorExtension());
        assertTrue(jdbc.buildTime());
        assertEquals("QUARKUS_DATASOURCE_JDBC", jdbc.envVar());
        assertEquals("boolean", jdbc.type());
        assertEquals("true", jdbc.defaultValue());
        assertFalse(jdbc.required());
        assertNull(jdbc.deprecated());
    }

    @Test
    void enumValuesLoseTheirTooltipsAndNestedTablesDoNotBreakARow() {
        OfficialPage page = Fixtures.page("new-generator.html");

        Row transactions = row(page, "quarkus.datasource.jdbc.transactions");
        assertFalse(transactions.buildTime());
        assertEquals("enabled, xa, disabled", transactions.type());
        assertEquals(List.of("enabled", "xa", "disabled"), transactions.typeCodes());
        assertEquals("enabled", transactions.defaultValue());
        // the environment variable comes after an admonition table nested in the description
        assertEquals("QUARKUS_DATASOURCE_JDBC_TRANSACTIONS", transactions.envVar());
        assertEquals(List.of("quarkus.datasource.\"datasource-name\".jdbc.transactions"), transactions.alternatives());
    }

    @Test
    void readsRequiredMapDurationAndDescriptionlessRows() {
        OfficialPage page = Fixtures.page("new-generator.html");

        Row url = row(page, "quarkus.datasource.jdbc.url");
        assertTrue(url.required());
        assertNull(url.defaultValue());

        Row map = row(page, "quarkus.datasource.jdbc.additional-jdbc-properties.\"property-key\"");
        assertEquals("Map<String,String>", map.type());
        assertNull(map.defaultValue());

        Row timeout = row(page, "quarkus.http.read-timeout");
        assertEquals("Duration", timeout.type());
        assertEquals("60S", timeout.defaultValue());
        assertEquals("quarkus-vertx-http", timeout.anchorExtension());

        Row docker = row(page, "quarkus.docker.dockerfile-jvm-path");
        assertTrue(docker.buildTime());
        assertEquals("QUARKUS_DOCKER_DOCKERFILE_JVM_PATH", docker.envVar());
        assertEquals("src/main/docker/Dockerfile.jvm", docker.defaultValue());
    }

    @Test
    void readsDeprecationNotices() {
        OfficialPage page = Fixtures.page("new-generator.html");

        assertEquals("3.31", row(page, "quarkus.debug.dump-build-metrics").deprecated());
        Row locale = row(page, "quarkus.default-locale");
        assertEquals("", locale.deprecated());
        assertEquals("`en-US` in native mode", locale.defaultValue());
        assertEquals(1, page.rows().stream().filter(r -> "3.31".equals(r.deprecated())).count());
        assertEquals(2, page.rows().stream().filter(r -> r.deprecated() != null).count());
    }

    @Test
    void restoresValuesThatTheHtmlReadsAsUnknownTags() {
        OfficialPage page = Fixtures.page("new-generator.html");

        Row retries = row(page, "quarkus.fault-tolerance.\"<identifier>\".retry.max-retries");
        assertEquals("<default>", retries.defaultValue());
        assertEquals("QUARKUS_FAULT_TOLERANCE___IDENTIFIER___RETRY_MAX_RETRIES", retries.envVar());
    }

    @Test
    void readsTheRowsOfTheOldGenerator() {
        OfficialPage page = Fixtures.page("old-generator.html");

        assertEquals(Generator.OLD, page.generator());
        // no stream is selected in the version selector (3.2 is not listed there): the page title says it
        assertEquals(new Documented("3.2", null, "page title"), page.documented());
        assertEquals(6, page.rows().size());
        assertEquals(7, page.names());

        Row jdbc = row(page, "quarkus.datasource.jdbc");
        assertEquals(List.of("quarkus.datasource.\"datasource-name\".jdbc"), jdbc.alternatives());
        assertTrue(jdbc.buildTime());
        assertEquals("quarkus-agroal", jdbc.anchorExtension());

        Row transactions = row(page, "quarkus.datasource.jdbc.transactions");
        assertEquals(List.of("enabled", "xa", "disabled"), transactions.typeCodes());
        assertFalse(transactions.buildTime());

        Row map = row(page, "quarkus.datasource.jdbc.additional-jdbc-properties");
        assertEquals("Map<String,String>", map.type());
        assertEquals("QUARKUS_DATASOURCE_JDBC_ADDITIONAL_JDBC_PROPERTIES", map.envVar());

        Row level = row(page, "quarkus.log.category.\"categories\".level");
        assertEquals("Level", level.type());
        assertEquals("inherit", level.defaultValue());
        assertEquals("quarkus-core", level.anchorExtension());

        Row host = row(page, "quarkus.http.host");
        assertTrue(host.required());
        assertNull(host.defaultValue());
        assertNull(host.deprecated());
    }

    @Test
    void documentedReleaseComesFromTheSelectorTheTitleOrTheUrl() {
        assertEquals(new Documented("3.40", null, "version selector"), OfficialPageParser.parse(
                "<select id=\"guide-version-dropdown\"><option value=\"latest\" selected>3.40 (latest)</option>"
                        + "</select>").documented());
        assertEquals(new Documented("3.20", "3.20.6", "page title"), OfficialPageParser.parse(
                "<head><meta property=\"og:title\" content=\"All configuration options - 3.20.6\"></head>")
                .documented());
        assertEquals(new Documented("3.15", null, "page URL"), OfficialPageParser.parse(
                "<head><meta property=\"og:title\" content=\"All configuration options\">"
                        + "<meta property=\"og:url\" content=\"https://quarkus.io/version/3.15/guides/all-config/\">"
                        + "</head>").documented());
        assertNull(OfficialPageParser.parse("<p>nothing</p>").documented());
    }

    @Test
    void pageWithoutConfigurationTableHasNoRows() {
        OfficialPage page = OfficialPageParser.parse("<table><tr><td><code>quarkus.a.b</code></td></tr></table>");

        assertTrue(page.rows().isEmpty());
        assertEquals(0, page.names());
    }

    private static Row row(OfficialPage page, String name) {
        return page.rows().stream().filter(r -> r.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no row " + name + " in " + page.rows().stream()
                        .map(Row::name).toList()));
    }
}
