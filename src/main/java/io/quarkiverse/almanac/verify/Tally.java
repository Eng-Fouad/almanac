package io.quarkiverse.almanac.verify;

import io.quarkiverse.almanac.verify.Examples.Example;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/// Accumulates the outcomes of one check (equal, differ, one side null) and keeps the first `limit` examples
/// of each kind of mismatch.
final class Tally {

    /// Result of comparing two values.
    enum Outcome { EQUAL, DIFFER, ONE_SIDE_NULL }

    private final String check;
    private final String label;
    private final boolean gating;
    private final int limit;
    private int equal;
    private int differ;
    private int oneSideNull;
    private final List<Example> differExamples = new ArrayList<>();
    private final List<Example> nullExamples = new ArrayList<>();

    /// @param check  machine name, e.g. `default`
    /// @param label  human name used in example headings, e.g. `default`
    /// @param gating whether the check counts towards the exit code
    /// @param limit  examples kept per kind
    Tally(String check, String label, boolean gating, int limit) {
        this.check = check;
        this.label = label;
        this.gating = gating;
        this.limit = limit;
    }

    /// Both null or equal → EQUAL; one null → ONE_SIDE_NULL; otherwise DIFFER.
    static Outcome outcome(Object a, Object b) {
        if (Objects.equals(a, b)) {
            return Outcome.EQUAL;
        }
        return a == null || b == null ? Outcome.ONE_SIDE_NULL : Outcome.DIFFER;
    }

    /// Records an outcome; the example is kept for mismatches while there is room.
    Outcome add(Outcome outcome, String key, String a, String b) {
        switch (outcome) {
            case EQUAL -> equal++;
            case DIFFER -> {
                differ++;
                keep(differExamples, key, a, b);
            }
            case ONE_SIDE_NULL -> {
                oneSideNull++;
                keep(nullExamples, key, a, b);
            }
        }
        return outcome;
    }

    /// Compares two values (see [#outcome]) and records the result with their display forms.
    Outcome compare(String key, Object a, Object b) {
        return add(outcome(a, b), key, Texts.show(a), Texts.show(b));
    }

    Agreement agreement() {
        int compared = equal + differ + oneSideNull;
        return new Agreement(check, compared, equal, differ, oneSideNull, Agreement.ratio(equal, compared), gating);
    }

    /// Examples of the mismatches that occurred: differences first, then one-side-null cases.
    List<Examples> examples() {
        List<Examples> examples = new ArrayList<>();
        if (differ > 0) {
            examples.add(new Examples(check, label + " differs", differ, List.copyOf(differExamples)));
        }
        if (oneSideNull > 0) {
            examples.add(new Examples(check + Texts.ONE_SIDE_NULL, label + " set on one side only", oneSideNull,
                    List.copyOf(nullExamples)));
        }
        return examples;
    }

    private void keep(List<Example> examples, String key, String a, String b) {
        if (examples.size() < limit) {
            examples.add(new Example(key, a, b));
        }
    }
}
