package io.quarkiverse.almanac.config.sourcedoc;

/// The `@ConfigDoc*` annotations found on one config member.
///
/// @param defaultValue value of `@ConfigDocDefault`, or null when absent
/// @param mapKey       value of `@ConfigDocMapKey`, or null when absent
/// @param ignore       whether `@ConfigDocIgnore` is present
/// @param section      whether `@ConfigDocSection` is present
public record DocAnnotations(String defaultValue, String mapKey, boolean ignore, boolean section) {

    public static final DocAnnotations NONE = new DocAnnotations(null, null, false, false);
}
