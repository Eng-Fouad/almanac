package io.quarkiverse.almanac.verify;

/// Outcome counts of one check over the compared items.
///
/// @param check       what was compared, e.g. `default` or `phase`
/// @param compared    number of compared items
/// @param equal       items that agree (both sides null counts as agreement)
/// @param differ      items with two different values
/// @param oneSideNull items with a value on one side only (null, or an empty list, on the other)
/// @param ratio       `equal / compared`, 1 when nothing was compared
/// @param gating      whether the check counts towards the exit code (false: informational)
public record Agreement(String check, int compared, int equal, int differ, int oneSideNull, double ratio,
        boolean gating) {

    /// `part / whole`, 1 for an empty whole.
    public static double ratio(long part, long whole) {
        return whole == 0 ? 1.0 : (double) part / whole;
    }

    /// One report line: `4,650 / 4,652 equal (99.96 %), 1 differ, 1 one side null`.
    String text() {
        StringBuilder text = new StringBuilder(Texts.count(equal) + " / " + Texts.count(compared) + " equal ("
                + Texts.percent(ratio) + ")");
        if (differ > 0) {
            text.append(", ").append(Texts.count(differ)).append(" differ");
        }
        if (oneSideNull > 0) {
            text.append(", ").append(Texts.count(oneSideNull)).append(" one side null");
        }
        if (!gating) {
            text.append(" [informational]");
        }
        return text.toString();
    }
}
