package io.quarkiverse.almanac.config.sourcedoc;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceFileIndexTest {

    private static final DocAnnotations IGNORE = new DocAnnotations(null, null, true, false);
    private static final DocAnnotations SECTION = new DocAnnotations(null, null, false, true);

    @Test
    void indexesEveryTypeByItsBinaryNameRelativeToThePackage() throws Exception {
        SourceFileIndex index = Fixtures.index("LiteralForms");
        assertEquals("com.acme", index.packageName());
        assertEquals(Set.of("LiteralForms", "LiteralForms$Nested", "LiteralForms$Nested$Deeper", "Holder"),
                index.typeNames());
        assertEquals(SourceFileIndex.LANGUAGE_LEVEL, index.languageLevel());
        assertEquals(List.of(), index.problems());
        assertTrue(index.type("LiteralForms").orElseThrow().isInterface());
        assertFalse(index.type("Holder").orElseThrow().isInterface());
        assertTrue(index.type("LiteralForms$Gone").isEmpty());
    }

    @Test
    void evaluatesEveryLiteralForm() throws Exception {
        SourceFileIndex.TypeMembers type = Fixtures.index("LiteralForms").type("LiteralForms").orElseThrow();
        assertEquals("plain", defaultOf(type, "plain"));
        assertEquals("named", defaultOf(type, "named"));
        assertEquals("tab\there \"quoted\" back\\slash é A0  ", defaultOf(type, "escapes"));
        assertEquals("first line\n  indented \"quotes\" \"\"\"\ntrailing spaces \njoined line\n",
                defaultOf(type, "textBlock"));
        assertEquals("no final newline", defaultOf(type, "textBlockWithoutNewline"));
        assertEquals("ab12ctrue1.52.510-3", defaultOf(type, "concatenation"));
        assertEquals("3x98", defaultOf(type, "numericFirst"));
        assertEquals("pre", defaultOf(type, "constant"));
        assertEquals("pre-suf", defaultOf(type, "derived"));
        assertEquals("suf", defaultOf(type, "nestedConstant"));
        assertEquals("suf", defaultOf(type, "qualifiedConstant"));
        assertEquals("pre", defaultOf(type, "fullyQualifiedConstant"));
        assertEquals("held", defaultOf(type, "otherTopLevelType"));
        assertEquals("cast", defaultOf(type, "cast"));
        assertEquals("parenthesised", defaultOf(type, "parenthesised"));
        assertEquals(new DocAnnotations(null, "tenant", false, false), member(type, "tenants"));
        assertEquals(new DocAnnotations(null, "qualified", false, false), member(type, "qualifiedAnnotation"));
        assertEquals(SECTION, member(type, "section"));
        assertEquals(IGNORE, member(type, "ignored"));
        assertEquals(new DocAnnotations("several", "key", false, true), member(type, "several"));
        assertEquals(DocAnnotations.NONE, member(type, "unannotated"));
        // methods with parameters are not config members
        assertTrue(type.member("helper").isEmpty());
    }

    @Test
    void resolvesSimpleNamesInTheTypeAndItsEnclosingTypes() throws Exception {
        SourceFileIndex index = Fixtures.index("LiteralForms");
        SourceFileIndex.TypeMembers nested = index.type("LiteralForms$Nested").orElseThrow();
        assertEquals("suf", defaultOf(nested, "inner"));
        assertEquals("pre", defaultOf(nested, "outer"));
        SourceFileIndex.TypeMembers deeper = index.type("LiteralForms$Nested$Deeper").orElseThrow();
        assertEquals(new DocAnnotations(null, "sufpre", false, false), member(deeper, "deep"));
    }

    @Test
    void readsFieldsOfClassesAndMethodsOfInterfacesFirst() throws Exception {
        SourceFileIndex index = Fixtures.index("LegacyConfig");
        assertEquals(Set.of("LegacyConfig", "LegacyConfig$Group", "LegacyConfig$Version", "SecondaryGroup"),
                index.typeNames());
        SourceFileIndex.TypeMembers legacy = index.type("LegacyConfig").orElseThrow();
        assertFalse(legacy.isInterface());
        // a field and a method named "both": a class's member is its field
        assertEquals(Optional.of(DocAnnotations.NONE), legacy.member("both"));
        assertEquals(new DocAnnotations("method", null, false, false), legacy.methods().get("both"));
        assertEquals("image:1.0", defaultOf(legacy, "image"));
        assertEquals(new DocAnnotations(null, "datasource-name", false, false), member(legacy, "named"));
        assertEquals(SECTION, member(legacy, "group"));
        assertEquals(DocAnnotations.NONE, member(legacy, "first"));
        assertEquals(DocAnnotations.NONE, member(legacy, "second"));
        assertEquals("in group", defaultOf(index.type("LegacyConfig$Group").orElseThrow(), "value"));
        assertEquals(new DocAnnotations(null, "secondary-key", false, false),
                member(index.type("SecondaryGroup").orElseThrow(), "entries"));

        SourceFileIndex inInterface = parse("""
                package p;
                interface I {
                    @io.quarkus.runtime.annotations.ConfigDocDefault("field")
                    String x = "x";
                    @io.quarkus.runtime.annotations.ConfigDocDefault("method")
                    String x();
                }
                """);
        SourceFileIndex.TypeMembers type = inInterface.type("I").orElseThrow();
        assertEquals("method", defaultOf(type, "x"));
        assertEquals("field", type.fields().get("x").defaultValue());
    }

    @Test
    void listsAnnotatedMembersSortedFieldsFirst() throws Exception {
        List<String> annotated = Fixtures.index("LegacyConfig").annotatedMembers().stream()
                .map(m -> m.type() + (m.method() ? "#" + m.name() + "()" : "#" + m.name()))
                .toList();
        assertEquals(List.of("LegacyConfig#group", "LegacyConfig#image", "LegacyConfig#named", "LegacyConfig#both()",
                "LegacyConfig$Group#value", "SecondaryGroup#entries"), annotated);
    }

    @Test
    void readsRecordComponentsAndEnumConstants() throws Exception {
        SourceFileIndex index = parse("""
                package p;
                import io.quarkus.runtime.annotations.*;
                record R(@ConfigDocDefault("component") String value) {
                }
                enum E {
                    @ConfigDocSection A,
                    B;
                    @ConfigDocIgnore
                    String label() {
                        return name();
                    }
                }
                """);
        SourceFileIndex.TypeMembers record = index.type("R").orElseThrow();
        assertEquals("component", record.fields().get("value").defaultValue());
        assertEquals("component", record.methods().get("value").defaultValue());
        SourceFileIndex.TypeMembers enumeration = index.type("E").orElseThrow();
        assertEquals(SECTION, enumeration.fields().get("A"));
        assertEquals(DocAnnotations.NONE, enumeration.fields().get("B"));
        assertEquals(IGNORE, enumeration.methods().get("label"));
    }

    @Test
    void recognisesOnlyTheQuarkusAnnotations() throws Exception {
        SourceFileIndex index = Fixtures.index("Unresolved");
        SourceFileIndex.TypeMembers type = index.type("Unresolved").orElseThrow();
        // @ConfigDocDefault is imported from com.other in that file
        assertEquals(DocAnnotations.NONE, member(type, "foreign"));
        assertEquals(List.of("Unresolved#remote", "Unresolved#staticImport"), index.annotatedMembers().stream()
                .map(m -> m.type() + "#" + m.name()).toList());

        SourceFileIndex qualified = parse("""
                package p;
                interface I {
                    @com.other.ConfigDocSection
                    String other();
                    @io.quarkus.runtime.annotations.ConfigDocSection
                    String quarkus();
                    @ConfigDocSection
                    String simple();
                }
                """);
        SourceFileIndex.TypeMembers i = qualified.type("I").orElseThrow();
        assertEquals(DocAnnotations.NONE, member(i, "other"));
        assertEquals(SECTION, member(i, "quarkus"));
        assertEquals(SECTION, member(i, "simple"));
    }

    @Test
    void leavesValuesFromOtherFilesNullAndListsThem() throws Exception {
        SourceFileIndex index = Fixtures.index("Unresolved");
        SourceFileIndex.TypeMembers type = index.type("Unresolved").orElseThrow();
        assertEquals(new DocAnnotations(null, null, false, false), member(type, "remote"));
        assertEquals(new DocAnnotations(null, null, false, false), member(type, "staticImport"));
        assertEquals(List.of(
                "Unresolved#remote: @ConfigDocDefault(Constants.REMOTE): Constants.REMOTE is not a constant declared "
                        + "in this file",
                "Unresolved#staticImport: @ConfigDocMapKey(STATIC_IMPORTED): STATIC_IMPORTED is not a constant "
                        + "declared in this file"), index.problems());
    }

    @Test
    void retriesAtTheNewerLanguageLevel() throws Exception {
        SourceFileIndex index = Fixtures.index("Modern");
        assertEquals(SourceFileIndex.NEWER_LANGUAGE_LEVEL, index.languageLevel());
        assertEquals("modern", defaultOf(index.type("Modern").orElseThrow(), "value"));
    }

    @Test
    void rejectsFilesThatDoNotParse() {
        SourceFileIndex.UnparsableSourceException e = assertThrows(SourceFileIndex.UnparsableSourceException.class,
                () -> Fixtures.index("Broken"));
        assertTrue(e.getMessage().startsWith("Broken.java: "), e.getMessage());
    }

    @Test
    void acceptsAByteOrderMarkAndWindowsLineEnds() throws Exception {
        String source = "﻿package p;\r\n"
                + "interface I {\r\n"
                + "    @io.quarkus.runtime.annotations.ConfigDocDefault(\"\"\"\r\n"
                + "        one\r\n"
                + "        two\r\n"
                + "        \"\"\")\r\n"
                + "    String x();\r\n"
                + "}\r\n";
        SourceFileIndex index = parse(source);
        assertEquals("p", index.packageName());
        assertEquals("one\ntwo\n", defaultOf(index.type("I").orElseThrow(), "x"));
    }

    private static SourceFileIndex parse(String source) throws SourceFileIndex.UnparsableSourceException {
        return SourceFileIndex.parse("p/I.java", source.getBytes(StandardCharsets.UTF_8));
    }

    private static DocAnnotations member(SourceFileIndex.TypeMembers type, String name) {
        return type.member(name).orElseThrow(() -> new AssertionError("no member " + name));
    }

    private static String defaultOf(SourceFileIndex.TypeMembers type, String name) {
        return member(type, name).defaultValue();
    }
}
