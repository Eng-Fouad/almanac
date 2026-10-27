package io.quarkiverse.almanac.verify;

import io.quarkiverse.almanac.verify.Examples.Example;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/// Formatting shared by the verify reports: numbers, percentages, values and example blocks.
final class Texts {

    /// Width of the label column of summary lines.
    private static final int LABEL_WIDTH = 13;
    /// Values longer than this are shortened in examples.
    private static final int MAX_VALUE = 160;
    /// Suffix of the example kinds holding values set on one side only.
    static final String ONE_SIDE_NULL = "/one-side-null";

    private Texts() {
    }

    static String count(long n) {
        return String.format(Locale.ROOT, "%,d", n);
    }

    /// `0.99978` → `99.97 %`: rounded down, so it never shows 100 % unless it is (the tiny offset only
    /// absorbs binary representation errors such as `0.57 * 10000 = 5699.999…`).
    static String percent(double ratio) {
        double floored = Math.floor(ratio * 10_000 + 1e-9) / 100;
        return String.format(Locale.ROOT, "%.2f %%", floored);
    }

    /// How a report names a snapshot: `3.40.1.1, model (model-json/1)`, or with the label first when it is not
    /// the version itself (`3.25.4:bytecode: 3.25.4, bytecode (…)`).
    static String snapshot(String label, String version, String source, String extractor) {
        String name = label == null || label.equals(version) ? version : label + ": " + version;
        return name + ", " + source + " (" + extractor + ")";
    }

    /// A summary line: label column, then text.
    static String line(String label, String text) {
        return String.format(Locale.ROOT, "%-" + LABEL_WIDTH + "s %s", label, text);
    }

    /// Display form of a compared value: null, a collection as `[a, b]`, `""` for empty text.
    static String show(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Collection<?> values) {
            return values.stream().map(String::valueOf).collect(Collectors.joining(", ", "[", "]"));
        }
        String text = String.valueOf(value);
        return text.isEmpty() ? "\"\"" : text;
    }

    /// Appends the example blocks: a heading with the total per kind, then one line per example with the values of
    /// both sides (`-` for a missing one) or, for kinds that exist on one side only, that side's description.
    static void examples(List<String> out, List<Examples> examples, int limit, String sideA, String sideB) {
        if (examples.isEmpty()) {
            return;
        }
        out.add("");
        out.add("examples (up to " + limit + " per kind)");
        for (Examples kind : examples) {
            out.add("");
            out.add(kind.title() + ": " + count(kind.total()));
            for (Example example : kind.items()) {
                boolean bothSides = kind.kind().endsWith(ONE_SIDE_NULL)
                        || (example.a() != null && example.b() != null);
                StringBuilder line = new StringBuilder("  ").append(example.key());
                if (example.a() != null || bothSides) {
                    line.append("  ").append(sideA).append(": ").append(value(example.a()));
                }
                if (example.b() != null || bothSides) {
                    line.append("  ").append(sideB).append(": ").append(value(example.b()));
                }
                out.add(line.toString());
            }
        }
    }

    private static String value(String text) {
        if (text == null) {
            return "-";
        }
        String flat = text.replace('\n', ' ').replace('\r', ' ');
        return flat.length() > MAX_VALUE ? flat.substring(0, MAX_VALUE - 1) + "…" : flat;
    }
}
