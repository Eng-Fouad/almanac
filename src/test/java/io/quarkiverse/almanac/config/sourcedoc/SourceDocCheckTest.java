package io.quarkiverse.almanac.config.sourcedoc;

import io.quarkiverse.almanac.config.InMemoryJar;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.annotation.RetentionPolicy;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The self-check on releases compiled in memory: javac is the oracle for what the class files of a release from 3.14
/// on carry, so these tests also check that [ConstantEvaluator] folds every literal form as javac does.
class SourceDocCheckTest {

    private static final List<String> ROOTS = List.of("com.acme.LiteralForms", "com.acme.legacy.LegacyConfig");

    @Test
    void agreesWithJavacInBothDirections() throws IOException {
        Fixtures.Release release = Fixtures.release("3.14.0", RetentionPolicy.RUNTIME, ROOTS, "LiteralForms",
                "LegacyConfig");
        SourceDocCheck.Report report = new SourceDocCheck.Checker(release.jars(), release.annotationSource(), 4).run();

        assertTrue(report.runtime());
        assertEquals(List.of("quarkus-acme"), report.configJars());
        assertEquals(List.of(), report.ownerJars());
        assertEquals(List.of(), report.classes().mismatches());
        assertEquals(List.of(), report.sources().mismatches());
        assertEquals(29, report.classes().compared());
        assertEquals(8, report.classes().units());
        assertEquals(0, report.classes().skipped());
        assertEquals(new SourceDocAnnotations.AnnotationCounts(21, 6, 1, 3), report.classes().counts());
        // the class's method both() is annotated, but readers of a class ask for its field "both"
        assertEquals(List.of("com/acme/legacy/LegacyConfig#both()"), report.notVisited());
        assertEquals(29, report.sources().compared());
        assertEquals(2, report.sources().units());
        assertEquals(2, report.sources().parsed());
        assertEquals(report.classes().counts(), report.sources().counts());
        assertEquals(33, report.coverage().lookups());
        assertEquals(28, report.coverage().annotatedLookups());
        assertEquals(List.of(), report.warnings());
        assertTrue(report.passed());
        String text = report.format();
        assertTrue(text.contains("  agree: 29 / 29 (100.00 %)\n"), text);
        assertTrue(text.contains("  not among the config members the reader looks up: 1 "
                + "(com/acme/legacy/LegacyConfig#both())\n"), text);
        assertTrue(text.endsWith("Result: PASS\n"), text);
    }

    @Test
    void reportsValuesTheSourcesOfOneFileCannotResolve() throws IOException {
        Fixtures.Release release = Fixtures.release("3.20.0", RetentionPolicy.RUNTIME,
                List.of("com.acme.Unresolved"), "Unresolved", "Constants");
        SourceDocCheck.Report report = new SourceDocCheck.Checker(release.jars(), release.annotationSource(), 4).run();

        assertEquals(List.of(
                new SourceDocCheck.Mismatch("com/acme/Unresolved#remote", true,
                        new DocAnnotations("remote-value", null, false, false), DocAnnotations.NONE, false),
                new SourceDocCheck.Mismatch("com/acme/Unresolved#staticImport", true,
                        new DocAnnotations(null, "static-key", false, false), DocAnnotations.NONE, false)),
                report.classes().mismatches());
        assertEquals(List.of(
                new SourceDocCheck.Mismatch("com/acme/Unresolved#remote", true,
                        new DocAnnotations("remote-value", null, false, false), DocAnnotations.NONE, true),
                new SourceDocCheck.Mismatch("com/acme/Unresolved#staticImport", true,
                        new DocAnnotations(null, "static-key", false, false), DocAnnotations.NONE, true)),
                report.sources().mismatches());
        assertEquals(1, report.sources().parsed());
        assertEquals(2, report.total().unresolvedValues());
        assertFalse(report.passed());
        String text = report.format();
        assertTrue(text.contains("    com/acme/Unresolved#remote(): class file @ConfigDocDefault(\"remote-value\"), "
                + "sources none\n"), text);
        assertTrue(text.contains("    com/acme/Unresolved#staticImport(): class file @ConfigDocMapKey(\"static-key\"), "
                + "sources an annotation whose value is not a constant of its file\n"), text);
        assertTrue(text.endsWith("Result: FAIL\n"), text);
    }

    @Test
    void reportsCoverageWhenClassFilesLackTheAnnotations() throws IOException {
        Fixtures.Release release = Fixtures.release("3.8.6.1", RetentionPolicy.SOURCE, ROOTS, "LiteralForms",
                "LegacyConfig");
        SourceDocCheck.Report report = new SourceDocCheck.Checker(release.jars(), release.annotationSource(), 4).run();

        assertFalse(report.runtime());
        assertNull(report.classes());
        assertNull(report.sources());
        SourceDocAnnotations.Stats coverage = report.coverage();
        assertEquals(33, coverage.lookups());
        assertEquals(28, coverage.annotatedLookups());
        assertEquals(new SourceDocAnnotations.AnnotationCounts(20, 6, 1, 3), coverage.lookedUp());
        assertEquals(new SourceDocAnnotations.AnnotationCounts(21, 6, 1, 3), coverage.inParsedFiles());
        assertEquals(6, coverage.ownersResolved());
        assertEquals(1, coverage.ownersInOtherFiles());
        assertEquals(1, coverage.sourcesJarsFound());
        assertEquals(0, coverage.sourcesJarsMissing());
        assertEquals(2, coverage.filesParsed());
        assertEquals(0, coverage.filesUnparsable());
        assertTrue(report.passed());
        String text = report.format();
        assertTrue(text.contains("  33 members of 6 types (6 types visited: roots, super-types, groups)\n"), text);
        assertTrue(text.contains("  annotated members found: @ConfigDocDefault 20, @ConfigDocMapKey 6, "
                + "@ConfigDocIgnore 1, @ConfigDocSection 3\n"), text);
        assertFalse(text.contains("Class files -> sources"), text);
    }

    @Test
    void failsCoverageWithoutSourcesJar() throws IOException {
        Fixtures.Release release = Fixtures.release("3.8.6.1", RetentionPolicy.SOURCE, ROOTS, "LiteralForms",
                "LegacyConfig");
        SourceDocAnnotations source = release.annotationSource(InMemoryJar.missing(Fixtures.ARTIFACT,
                Fixtures.VERSION));
        SourceDocCheck.Report report = new SourceDocCheck.Checker(release.jars(), source, 4).run();

        assertEquals(33, report.coverage().lookups());
        assertEquals(0, report.coverage().annotatedLookups());
        assertEquals(1, report.coverage().sourcesJarsMissing());
        assertEquals(List.of("no sources jar for io.quarkus:quarkus-acme:1.0; the @ConfigDoc* annotations of its "
                + "classes are unknown"), report.warnings());
        assertFalse(report.passed());
    }

    @Test
    void comparesTheAnnotationsOnlyFromRelease314() {
        assertFalse(SourceDocCheck.hasRuntimeRetention("3.13.3"));
        assertFalse(SourceDocCheck.hasRuntimeRetention("3.8.6.1"));
        assertTrue(SourceDocCheck.hasRuntimeRetention("3.14.0.CR1"));
        assertTrue(SourceDocCheck.hasRuntimeRetention("3.14.4"));
        assertTrue(SourceDocCheck.hasRuntimeRetention("4.0.0.Beta1"));
    }

    @Test
    void describesAnnotationsAsWritten() {
        assertEquals("none", SourceDocCheck.describe(DocAnnotations.NONE));
        assertEquals("@ConfigDocDefault(\"say \\\"hi\\\"\\n\\\\\") @ConfigDocMapKey(\"k\") @ConfigDocIgnore "
                + "@ConfigDocSection", SourceDocCheck.describe(new DocAnnotations("say \"hi\"\n\\", "k", true, true)));
    }
}
