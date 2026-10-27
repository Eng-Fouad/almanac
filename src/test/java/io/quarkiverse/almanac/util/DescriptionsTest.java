package io.quarkiverse.almanac.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DescriptionsTest {

    @Test
    void cutsAtFirstPeriodFollowedByWhitespace() {
        assertEquals("Foo bar.", Descriptions.firstSentence("Foo bar. Baz."));
        assertEquals("Line one.", Descriptions.firstSentence("Line one.\nLine two."));
        assertEquals("Ends here.", Descriptions.firstSentence("Ends here."));
        assertEquals("Version 1.2 is used.", Descriptions.firstSentence("Version 1.2 is used. More text."));
    }

    @Test
    void doesNotEndAtEgOrIe() {
        assertEquals("The kind of database we will connect to (e.g. h2, postgresql).",
                Descriptions.firstSentence("The kind of database we will connect to (e.g. h2, postgresql). More."));
        assertEquals("Based on effective POMs (I.E. resolved ones).",
                Descriptions.firstSentence("Based on effective POMs (I.E. resolved ones). More."));
        assertEquals("Use the type.", Descriptions.firstSentence("Use the type. Next sentence."));
        assertEquals("Supports a cafe.g.", Descriptions.firstSentence("Supports a cafe.g. Next."));
    }

    @Test
    void replacesJavadocTagsLeftInAsciidoc() {
        assertEquals("The preferred JDBC type for `java.time.Duration` values, see <<anchor>> and Map<K, V>.",
                Descriptions.replaceJavadocTags("The preferred JDBC type for {@link java.time.Duration} values, "
                        + "see <<anchor>> and Map<K, V>."));
    }

    @Test
    void ignoresPeriodsInsideBackticks() {
        assertEquals("Sets `quarkus.http. port` and `a. b` here.",
                Descriptions.firstSentence("Sets `quarkus.http. port` and `a. b` here. Second sentence."));
    }

    @Test
    void trimsAndCollapsesWhitespace() {
        assertEquals("Multi line text.", Descriptions.firstSentence("  Multi\n   line\ttext.\n\nNext."));
        assertEquals("No period at all", Descriptions.firstSentence("No period\nat all  "));
    }

    @Test
    void keepsAsciidocMarkup() {
        assertEquals("Enable access *when no transaction is active* but a request scope is.",
                Descriptions.firstSentence("Enable access\n*when no transaction is active* but a request scope is."
                        + "\n\nWhen enabled, sessions are read-only."));
    }

    @Test
    void hardCutsLongSentences() {
        assertEquals("x".repeat(Descriptions.MAX_LENGTH) + "…", Descriptions.firstSentence("x".repeat(400)));
        String words = Descriptions.firstSentence("word ".repeat(100));
        assertTrue(words.endsWith("word…") && words.length() <= Descriptions.MAX_LENGTH + 1, words);
        String exact = "x".repeat(Descriptions.MAX_LENGTH);
        assertEquals(exact, Descriptions.firstSentence(exact));
    }

    @Test
    void blankIsNull() {
        assertNull(Descriptions.firstSentence(null));
        assertNull(Descriptions.firstSentence(" \n\t "));
    }

    @Test
    void convertsJavadocInlineTags() {
        assertEquals("If set to `true`, Quarkus prints `mutable-jar` times.",
                Descriptions.fromJavadoc("If set to {@code true}, Quarkus prints {@code mutable-jar} times."));
        assertEquals("See `java.time.Duration`, `foo()` and the bar.",
                Descriptions.fromJavadoc("See {@link java.time.Duration}, {@link #foo()} and {@linkplain Bar the bar}."));
        assertEquals("Use `Map<String, List<String>>` or x.",
                Descriptions.fromJavadoc("Use {@code Map<String, List<String>>} or {@literal x}."));
        assertEquals("Unclosed {@code tag", Descriptions.fromJavadoc("Unclosed {@code tag"));
    }

    @Test
    void convertsHtml() {
        assertEquals("The log level.\n\n\n\nNote that `INFO` and `List<String>` differ.",
                Descriptions.fromJavadoc("The log level.\n<p>\nNote that <code>INFO</code> and "
                        + "<code>List&lt;String&gt;</code> differ."));
        assertEquals("a < b & c\nd", Descriptions.fromJavadoc("a &lt; b &amp; c<br/>d"));
        assertEquals("Visit the docs, 5 > 3.",
                Descriptions.fromJavadoc("Visit <a href=\"https://quarkus.io\">the docs</a>, 5 &#62; 3."));
        assertEquals("Keeps `<b>` in code", Descriptions.fromJavadoc("Keeps `<b>` in <b>code</b>"));
    }

    @Test
    void javadocFirstSentence() {
        assertEquals("The log level for this category.", Descriptions.firstSentence(Descriptions.fromJavadoc(
                "The log level for this category.\n<p>\nNote that to get log levels below <code>INFO</code>, ...")));
        assertEquals("Validates that a `@ConfigMapping` maps every name.", Descriptions.firstSentence(
                Descriptions.fromJavadoc("Validates that a <code>@ConfigMapping</code> maps every name.<p>More")));
    }
}
