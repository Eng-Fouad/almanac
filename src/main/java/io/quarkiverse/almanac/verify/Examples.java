package io.quarkiverse.almanac.verify;

import java.util.List;

/// The first examples of one kind of mismatch, in key order.
///
/// @param kind  e.g. `only-in-a`, `default` or `default/one-side-null`
/// @param title human-readable heading
/// @param total number of mismatches of this kind (the list holds at most `--examples` of them)
/// @param items the examples
public record Examples(String kind, String title, int total, List<Example> items) {

    /// One mismatch.
    ///
    /// @param key the canonical key (or both keys of a pair matched through an alias)
    /// @param a   the value or description on the first side, null when absent
    /// @param b   the value or description on the second side, null when absent
    public record Example(String key, String a, String b) {
    }
}
