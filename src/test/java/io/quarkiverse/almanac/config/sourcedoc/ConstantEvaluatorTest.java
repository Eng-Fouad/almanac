package io.quarkiverse.almanac.config.sourcedoc;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConstantEvaluatorTest {

    private static final String INTERFACE = "interface";
    private static final String CLASS = "class";

    @Test
    void foldsNumbersLikeJavac() throws Exception {
        assertEquals("1.0E10|0.1|2147483647|-2147483648|9223372036854775807|98|b|3.0|0.5", value(
                "1e10 + \"|\" + 0.1f + \"|\" + MAX + \"|\" + (MAX + 1) + \"|\" + LONG_MAX + \"|\" + ('a' + 1)"
                        + " + \"|\" + 'b' + \"|\" + (1 + 2.0) + \"|\" + 0x1p-1",
                "int MAX = 2147483647; long LONG_MAX = 9223372036854775807L;"));
        assertEquals("1000_1000:255:8:-5", value("1_0_0_0 + \"_\" + 1_000 + \":\" + 0xFF + \":\" + 010 + \":\" + -5",
                ""));
    }

    @Test
    void decodesEscapesAndTextBlocks() throws Exception {
        assertEquals("a\tb\\c\"d'e\u0000f", ConstantEvaluator.decodeEscapes("a\\tb\\\\c\\\"d\\'e\\0f"));
        assertEquals("x\n  y\nz", ConstantEvaluator.textBlock("    x\r\n      y\r    z"));
        assertEquals("tab\tand space \n", ConstantEvaluator.textBlock("  tab\\tand space\\s\n  "));
        assertThrows(ConstantEvaluator.NotConstantException.class, () -> ConstantEvaluator.decodeEscapes("\\q"));
    }

    @Test
    void rejectsWhatIsNotAConstantOfTheFile() throws Exception {
        assertProblem(CLASS, "static String NOT_FINAL = \"x\";", "NOT_FINAL",
                "NOT_FINAL is not a static final field with an initializer");
        assertProblem(CLASS, "final String INSTANCE = \"x\";", "INSTANCE",
                "INSTANCE is not a static final field with an initializer");
        assertProblem(INTERFACE, "String A = B; String B = A;", "A", "circular constant I#A");
        assertProblem(INTERFACE, "", "Other.VALUE", "Other.VALUE is not a constant declared in this file");
        assertProblem(INTERFACE, "", "UNKNOWN", "UNKNOWN is not a constant declared in this file");
        assertProblem(INTERFACE, "", "String.valueOf(1)", "unsupported expression (MethodCallExpr)");
        assertProblem(INTERFACE, "", "\"a\" + (1 - 2)", "unsupported expression (BinaryExpr)");
        assertProblem(INTERFACE, "", "!true + \"\"", "unsupported unary operator LOGICAL_COMPLEMENT");
        assertProblem(INTERFACE, "", "true + false", "unsupported operands of + (Boolean, Boolean)");
        assertProblem(INTERFACE, "", "1", "not a String but Integer");
    }

    /// The value of `@ConfigDocDefault(<expression>)` on a member of interface `I`.
    private static String value(String expression, String fields) throws Exception {
        SourceFileIndex index = parse(INTERFACE, fields, expression);
        assertEquals(List.of(), index.problems());
        return index.type("I").orElseThrow().member("x").orElseThrow().defaultValue();
    }

    private static void assertProblem(String kind, String fields, String expression, String reason)
            throws Exception {
        SourceFileIndex index = parse(kind, fields, expression);
        assertNull(index.type("I").orElseThrow().member("x").orElseThrow().defaultValue());
        assertEquals(List.of("I#x: @ConfigDocDefault(" + expression + "): " + reason), index.problems());
    }

    /// `kind I { <fields> @ConfigDocDefault(<expression>) String x...; }`: a method or a field.
    private static SourceFileIndex parse(String kind, String fields, String expression)
            throws SourceFileIndex.UnparsableSourceException {
        String member = kind.equals(INTERFACE) ? "String x();" : "String x;";
        String source = "package p; " + kind + " I { " + fields + " @io.quarkus.runtime.annotations.ConfigDocDefault("
                + expression + ") " + member + " }";
        return SourceFileIndex.parse("p/I.java", source.getBytes(StandardCharsets.UTF_8));
    }
}
