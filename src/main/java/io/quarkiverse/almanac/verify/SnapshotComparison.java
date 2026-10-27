package io.quarkiverse.almanac.verify;

import io.quarkiverse.almanac.model.ConfigProperty;
import io.quarkiverse.almanac.model.ConfigSnapshot;
import io.quarkiverse.almanac.util.ConfigKeys;
import io.quarkiverse.almanac.verify.Examples.Example;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/// `verify snapshots`: compares two config snapshots property by property.
///
/// Properties are matched by canonical key ([ConfigKeys#canonical]). A property whose key equals a canonical
/// alias of a property on the other side matches that property too; such pairs are counted as "via alias" (a legacy
/// default + named-map split matches one modern property twice, once by key and once via its alias). Key agreement is
/// `matched pairs / (matched pairs + only in a + only in b)`.
///
/// Every matched pair is compared field by field: `ext`, `type`, `list`, `optional`,
/// `map`, `default`, `phase`, `deprecated` and `enum` (as sets); pairs matched by the same
/// key are also compared by `name` and `aliases` (as sets of canonical keys without the property's own key),
/// since the names of a pair matched via an alias differ by construction. A field is equal (both null counts as
/// equal), differs, or is set on one side only (null, or no aliases, on the other). One-side-null cases are reported
/// separately and count against the agreement, which is `equal / compared pairs`. `desc` is compared for
/// information only.
public final class SnapshotComparison {

    /// Fields that count towards the field agreement, in report order.
    public static final List<String> FIELDS = List.of("name", "aliases", "ext", "type", "list", "optional", "map",
            "default", "phase", "deprecated", "enum");

    /// Fields compared only on pairs matched by the same key.
    static final Set<String> NAME_FIELDS = Set.of("name", "aliases");

    /// The informational field.
    public static final String DESC = "desc";

    /// @param examples          examples kept per mismatch kind
    /// @param minKeyAgreement   the key agreement below which the comparison fails
    /// @param minFieldAgreement the agreement below which any field of [#FIELDS] fails the comparison
    public record Options(int examples, double minKeyAgreement, double minFieldAgreement) {

        public static final Options DEFAULTS = new Options(10, 0.99, 0.99);
    }

    /// A snapshot to compare and how to name it.
    public record Input(String label, String file, ConfigSnapshot snapshot) {
    }

    /// One compared snapshot.
    ///
    /// @param duplicateKeys properties ignored because an earlier one had the same key (snapshots should have none)
    public record Side(String label, String file, String version, String source, String extractor, int properties,
            int duplicateKeys) {
    }

    /// Key matching.
    ///
    /// @param matched   matched pairs (`sameKey + viaAlias`)
    /// @param sameKey   pairs with equal canonical keys
    /// @param viaAlias  pairs where one side's key is a canonical alias on the other side
    /// @param onlyInA   properties of a without a match
    /// @param onlyInB   properties of b without a match
    /// @param agreement `matched / (matched + onlyInA + onlyInB)`
    public record Keys(int matched, int sameKey, int viaAlias, int onlyInA, int onlyInB, double agreement) {
    }

    /// The comparison result; [#lines()] renders it.
    ///
    /// @param fields   agreement per field of [#FIELDS]
    /// @param desc     agreement of the descriptions (informational)
    /// @param failures the thresholds that were not met; empty when the comparison passed
    public record Report(Side a, Side b, Keys keys, List<Agreement> fields, Agreement desc, List<Examples> examples,
            int exampleLimit, double minKeyAgreement, double minFieldAgreement, List<String> failures,
            boolean pass) {

        /// Human-readable report: one summary line per metric, then the examples.
        public List<String> lines() {
            List<String> out = new ArrayList<>();
            out.add(Texts.line("a", describe(a)));
            out.add(Texts.line("b", describe(b)));
            out.add(Texts.line("keys", Texts.count(keys.matched()) + " in both (" + Texts.count(keys.sameKey())
                    + " same key, " + Texts.count(keys.viaAlias()) + " via an alias), " + Texts.count(keys.onlyInA())
                    + " only in a, " + Texts.count(keys.onlyInB()) + " only in b: " + Texts.percent(keys.agreement())
                    + " agreement"));
            for (Agreement field : fields) {
                out.add(Texts.line(field.check(), field.text()));
            }
            out.add(Texts.line(desc.check(), desc.text()));
            out.add(Texts.line("thresholds", "keys >= " + Texts.percent(minKeyAgreement) + ", every field >= "
                    + Texts.percent(minFieldAgreement)));
            out.add(Texts.line("result", pass ? "PASS" : "FAIL: " + String.join("; ", failures)));
            Texts.examples(out, examples, exampleLimit, "a", "b");
            return out;
        }

        private static String describe(Side side) {
            return Texts.snapshot(side.label(), side.version(), side.source(), side.extractor()) + ", "
                    + Texts.count(side.properties()) + " properties"
                    + (side.duplicateKeys() > 0 ? ", " + side.duplicateKeys() + " duplicate keys ignored" : "")
                    + (side.file() == null ? "" : " [" + side.file() + "]");
        }
    }

    private enum Via { KEY, ALIAS }

    private record Pair(ConfigProperty a, ConfigProperty b, Via via) {

        String key() {
            return via == Via.KEY ? keyOf(a) : keyOf(a) + " ~ " + keyOf(b);
        }
    }

    /// Properties of one snapshot by key and by canonical alias.
    private record Index(List<ConfigProperty> properties, Map<String, List<ConfigProperty>> byAlias,
            Map<String, ConfigProperty> byKey, int duplicates) {

        static Index of(ConfigSnapshot snapshot) {
            Map<String, ConfigProperty> byKey = new TreeMap<>();
            int duplicates = 0;
            for (ConfigProperty property : snapshot.properties() == null ? List.<ConfigProperty>of()
                    : snapshot.properties()) {
                if (byKey.putIfAbsent(keyOf(property), property) != null) {
                    duplicates++;
                }
            }
            Map<String, List<ConfigProperty>> byAlias = new TreeMap<>();
            for (ConfigProperty property : byKey.values()) {
                for (String alias : aliasKeys(property)) {
                    byAlias.computeIfAbsent(alias, k -> new ArrayList<>()).add(property);
                }
            }
            return new Index(List.copyOf(byKey.values()), byAlias, byKey, duplicates);
        }
    }

    private SnapshotComparison() {
    }

    /// Compares `a` with `b`.
    public static Report compare(Input a, Input b, Options options) {
        Index left = Index.of(a.snapshot());
        Index right = Index.of(b.snapshot());
        Set<ConfigProperty> matchedA = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<ConfigProperty> matchedB = Collections.newSetFromMap(new IdentityHashMap<>());
        List<Pair> pairs = new ArrayList<>();
        for (ConfigProperty pa : left.properties()) {
            ConfigProperty pb = right.byKey().get(keyOf(pa));
            if (pb != null) {
                pairs.add(new Pair(pa, pb, Via.KEY));
                matchedA.add(pa);
                matchedB.add(pb);
            }
        }
        for (ConfigProperty pa : left.properties()) {
            List<ConfigProperty> owners = right.byAlias().get(keyOf(pa));
            if (!matchedA.contains(pa) && owners != null) {
                ConfigProperty pb = preferUnmatched(owners, matchedB);
                pairs.add(new Pair(pa, pb, Via.ALIAS));
                matchedA.add(pa);
                matchedB.add(pb);
            }
        }
        for (ConfigProperty pb : right.properties()) {
            List<ConfigProperty> owners = left.byAlias().get(keyOf(pb));
            if (!matchedB.contains(pb) && owners != null) {
                ConfigProperty pa = preferUnmatched(owners, matchedA);
                pairs.add(new Pair(pa, pb, Via.ALIAS));
                matchedA.add(pa);
                matchedB.add(pb);
            }
        }
        pairs.sort(Comparator.comparing(Pair::key));

        int limit = options.examples();
        List<Example> onlyA = new ArrayList<>();
        List<Example> onlyB = new ArrayList<>();
        int onlyACount = unmatched(left, matchedA, onlyA, limit, true);
        int onlyBCount = unmatched(right, matchedB, onlyB, limit, false);
        List<Example> viaAlias = new ArrayList<>();
        int viaAliasCount = 0;
        Map<String, Tally> tallies = new LinkedHashMap<>();
        for (String field : FIELDS) {
            tallies.put(field, new Tally(field, field, true, limit));
        }
        Tally desc = new Tally(DESC, DESC, false, limit);
        for (Pair pair : pairs) {
            if (pair.via() == Via.ALIAS) {
                if (viaAliasCount < limit) {
                    viaAlias.add(new Example(pair.key(), pair.a().name(), pair.b().name()));
                }
                viaAliasCount++;
            }
            for (Map.Entry<String, Tally> tally : tallies.entrySet()) {
                String field = tally.getKey();
                if (pair.via() == Via.KEY || !NAME_FIELDS.contains(field)) {
                    tally.getValue().compare(pair.key(), value(field, pair.a()), value(field, pair.b()));
                }
            }
            desc.compare(pair.key(), pair.a().desc(), pair.b().desc());
        }

        int sameKey = pairs.size() - viaAliasCount;
        Keys keys = new Keys(pairs.size(), sameKey, viaAliasCount, onlyACount, onlyBCount,
                Agreement.ratio(pairs.size(), pairs.size() + onlyACount + onlyBCount));
        List<Agreement> fields = tallies.values().stream().map(Tally::agreement).toList();
        List<Examples> examples = new ArrayList<>();
        if (onlyACount > 0) {
            examples.add(new Examples("only-in-a", "only in a", onlyACount, List.copyOf(onlyA)));
        }
        if (onlyBCount > 0) {
            examples.add(new Examples("only-in-b", "only in b", onlyBCount, List.copyOf(onlyB)));
        }
        if (viaAliasCount > 0) {
            examples.add(new Examples("via-alias", "matched via an alias (names a / b)", viaAliasCount,
                    List.copyOf(viaAlias)));
        }
        tallies.values().forEach(tally -> examples.addAll(tally.examples()));
        examples.addAll(desc.examples());

        List<String> failures = new ArrayList<>();
        if (keys.agreement() < options.minKeyAgreement()) {
            failures.add("key agreement " + Texts.percent(keys.agreement()) + " < "
                    + Texts.percent(options.minKeyAgreement()));
        }
        for (Agreement field : fields) {
            if (field.ratio() < options.minFieldAgreement()) {
                failures.add(field.check() + " agreement " + Texts.percent(field.ratio()) + " < "
                        + Texts.percent(options.minFieldAgreement()));
            }
        }
        return new Report(side(a, left), side(b, right), keys, fields, desc.agreement(), List.copyOf(examples),
                limit, options.minKeyAgreement(), options.minFieldAgreement(), List.copyOf(failures),
                failures.isEmpty());
    }

    /// The canonical key of a property (recomputed when a snapshot omits it).
    static String keyOf(ConfigProperty property) {
        return property.key() != null ? property.key() : ConfigKeys.canonical(property.name());
    }

    /// Canonical keys of the aliases, without the property's own key.
    static SortedSet<String> aliasKeys(ConfigProperty property) {
        SortedSet<String> keys = new TreeSet<>();
        if (property.aliases() != null) {
            for (String alias : property.aliases()) {
                keys.add(ConfigKeys.canonical(alias));
            }
        }
        keys.remove(keyOf(property));
        return keys;
    }

    /// One-line description of a property for examples: name, extension, type and phase.
    static String describe(ConfigProperty property) {
        return property.name() + "  [" + property.ext() + ", " + (property.list() ? "list of " : "")
                + property.type() + ", " + property.phase()
                + (property.deprecated() != null ? ", deprecated" : "") + "]";
    }

    private static Object value(String field, ConfigProperty property) {
        return switch (field) {
            case "name" -> property.name();
            case "aliases" -> {
                SortedSet<String> aliases = aliasKeys(property);
                yield aliases.isEmpty() ? null : aliases;
            }
            case "ext" -> property.ext();
            case "type" -> property.type();
            case "list" -> property.list();
            case "optional" -> property.optional();
            case "map" -> property.map();
            case "default" -> property.defaultValue();
            case "phase" -> property.phase();
            case "deprecated" -> property.deprecated();
            case "enum" -> property.enumValues() == null ? null : new TreeSet<>(property.enumValues());
            default -> throw new IllegalArgumentException("unknown field " + field);
        };
    }

    private static ConfigProperty preferUnmatched(List<ConfigProperty> owners, Set<ConfigProperty> matched) {
        return owners.stream().filter(p -> !matched.contains(p)).findFirst().orElse(owners.getFirst());
    }

    private static int unmatched(Index index, Set<ConfigProperty> matched, List<Example> examples, int limit,
            boolean sideA) {
        int count = 0;
        for (ConfigProperty property : index.properties()) {
            if (!matched.contains(property)) {
                if (count++ < limit) {
                    examples.add(sideA ? new Example(keyOf(property), describe(property), null)
                            : new Example(keyOf(property), null, describe(property)));
                }
            }
        }
        return count;
    }

    private static Side side(Input input, Index index) {
        ConfigSnapshot snapshot = input.snapshot();
        return new Side(input.label(), input.file(), snapshot.version(), snapshot.source(), snapshot.extractor(),
                index.properties().size(), index.duplicates());
    }
}
