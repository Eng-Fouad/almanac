package io.quarkiverse.almanac.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConfigKeysTest {

    @Test
    void canonicalReplacesQuotedSegmentsAndIndices() {
        assertEquals("quarkus.log.category.*.level", ConfigKeys.canonical("quarkus.log.category.\"categories\".level"));
        assertEquals("quarkus.hibernate-orm.*.active",
                ConfigKeys.canonical("quarkus.hibernate-orm.\"persistence-unit-name\".active"));
        assertEquals("quarkus.a.*.b.*", ConfigKeys.canonical("quarkus.a.\"x.y\".b.\"z\""));
        assertEquals("quarkus.foo.bar[*].baz[*]", ConfigKeys.canonical("quarkus.foo.bar[0].baz[12]"));
    }

    @Test
    void canonicalLeavesEverythingElseUnchanged() {
        assertEquals("quarkus.http.port", ConfigKeys.canonical("quarkus.http.port"));
        assertEquals("quarkus.foo[x].bar", ConfigKeys.canonical("quarkus.foo[x].bar"));
        assertEquals("quarkus.foo.*.bar", ConfigKeys.canonical("quarkus.foo.*.bar"));
    }

    @Test
    void envVarUpperCasesAndReplacesEveryOtherCharacter() {
        assertEquals("QUARKUS_HIBERNATE_ORM__PU__ACTIVE", ConfigKeys.envVar("quarkus.hibernate-orm.\"pu\".active"));
        assertEquals("QUARKUS_HTTP_PORT", ConfigKeys.envVar("quarkus.http.port"));
        assertEquals("QUARKUS_FOO_0__BAR", ConfigKeys.envVar("quarkus.foo[0].bar"));
        assertEquals("QUARKUS_CAF_", ConfigKeys.envVar("quarkus.café"));
    }
}
