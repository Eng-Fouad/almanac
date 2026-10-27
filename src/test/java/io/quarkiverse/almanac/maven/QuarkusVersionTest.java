package io.quarkiverse.almanac.maven;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuarkusVersionTest {

    @Test
    void ordersPreReleasesBeforeFinal() {
        assertAscending("3.0.0.Alpha1", "3.0.0.Alpha2", "3.0.0.Beta1", "3.0.0.CR1", "3.0.0.CR2", "3.0.0.Final");
        assertEquals(0, v("3.0.0.Final").compareTo(v("3.0.0")));
        assertEquals(0, v("3.8").compareTo(v("3.8.0")));
    }

    @Test
    void ordersFourPartVersions() {
        assertAscending("3.8.6", "3.8.6.1", "3.9.0.CR1", "3.9.0");
        assertAscending("3.40.1", "3.40.1.1", "4.0.0.Beta1", "4.0.0");
        assertAscending("3.2.9.Final", "3.2.10.Final", "3.10.0", "3.20.6", "3.20.6.2");
    }

    @Test
    void parsesQualifiers() {
        QuarkusVersion beta = v("4.0.0.Beta1");
        assertEquals(QuarkusVersion.Qualifier.BETA, beta.qualifier());
        assertEquals(1, beta.qualifierNumber());
        assertFalse(beta.isFinal());
        assertTrue(v("3.0.4.Final").isFinal());
        assertTrue(v("3.40.1.1").isFinal());
        assertEquals(List.of(3, 40, 1, 1), v("3.40.1.1").numbers());
        assertEquals(0, v("3.0.0-CR1").compareTo(v("3.0.0.CR1")));
        assertEquals(QuarkusVersion.Qualifier.CR, v("3.0.0.cr").qualifier());
    }

    @Test
    void exposesStreamAndText() {
        assertEquals("3.20", v("3.20.6.2").stream());
        assertEquals("4.0", v("4.0.0.Beta1").stream());
        assertEquals("3.0.4.Final", v("3.0.4.Final").toString());
    }

    @Test
    void rejectsOtherVersions() {
        assertTrue(QuarkusVersion.tryParse("999-SNAPSHOT").isEmpty());
        assertTrue(QuarkusVersion.tryParse("3.0.0.RC1").isEmpty());
        assertTrue(QuarkusVersion.tryParse("").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> QuarkusVersion.parse("3.x"));
    }

    private static void assertAscending(String... versions) {
        for (int i = 1; i < versions.length; i++) {
            assertTrue(v(versions[i - 1]).compareTo(v(versions[i])) < 0, versions[i - 1] + " < " + versions[i]);
            assertTrue(v(versions[i]).compareTo(v(versions[i - 1])) > 0, versions[i] + " > " + versions[i - 1]);
        }
    }

    private static QuarkusVersion v(String text) {
        return QuarkusVersion.parse(text);
    }
}
