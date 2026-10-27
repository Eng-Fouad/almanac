package io.quarkiverse.almanac.config.bytecode;

import io.quarkiverse.almanac.maven.QuarkusVersion;

/// The two ways in which the doc models shipped after 3.25 differ from what the 3.25 processor writes, found by
/// comparing the processor sources of later releases. The reader applies the 3.25 rules to every release and these
/// differences only to the releases whose own processor has them, so that it reproduces each shipped model:
///
/// - `sortedEnumValues` (3.26.0+): `JacksonMappers` writes map entries ordered by key (`ORDER_MAP_ENTRIES_BY_KEYS`), so
///   enum accepted values come sorted by constant name instead of in declaration order.
/// - `listIndex` (3.32.2+): `ConfigResolver` appends `[i]` to the primary path (not to the additional paths) of a
///   `List`, `Set` or array of config groups, e.g. `quarkus.http.proxy.trusted-proxy[i].subject-dn`. No release before
///   3.40 has such a member.
///
/// @param sortedEnumValues enum values sorted by constant name
/// @param listIndex        `[i]` after the path of a list of groups
record ModelFormat(boolean sortedEnumValues, boolean listIndex) {

    /// What the 3.25 processor writes, and the rules of every release before.
    static final ModelFormat V3_25 = new ModelFormat(false, false);

    private static final QuarkusVersion SORTED_ENUM_VALUES = QuarkusVersion.parse("3.26.0");
    private static final QuarkusVersion LIST_INDEX = QuarkusVersion.parse("3.32.2");

    /// The list index segment of `ConfigNamingUtil#getListIndex`.
    static final String LIST_INDEX_SEGMENT = "[i]";

    /// The format of a release's own processor; [#V3_25] for unparsable versions.
    static ModelFormat of(String quarkusVersion) {
        return QuarkusVersion.tryParse(quarkusVersion)
                .map(version -> new ModelFormat(version.compareTo(SORTED_ENUM_VALUES) >= 0,
                        version.compareTo(LIST_INDEX) >= 0))
                .orElse(V3_25);
    }
}
