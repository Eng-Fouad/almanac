package io.quarkiverse.almanac.verify;

import io.quarkiverse.almanac.maven.QuarkusVersion;
import io.quarkiverse.almanac.model.ConfigProperty;
import io.quarkiverse.almanac.model.ConfigSnapshot;
import io.quarkiverse.almanac.util.ConfigKeys;
import io.quarkiverse.almanac.verify.Examples.Example;
import io.quarkiverse.almanac.verify.OfficialPage.Generator;
import io.quarkiverse.almanac.verify.OfficialPage.Row;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// `verify official`: compares a config snapshot with a parsed quarkus.io "all config" page.
///
/// Names are compared as canonical keys ([ConfigKeys#canonical]): every path the page displays (primary and
/// alternative paths) against the keys of our names and aliases. Pages of the old generator (≤ 3.13) show leaf maps
/// without their key segment, so for those pages the trailing `.*` of our `map` properties' keys is
/// stripped too. The checks:
///
/// - **names** (gating): page keys found among ours / page keys;
/// - **ours**: our properties with any key on the page; the others are split into deprecated (the pages leave
///   deprecated properties out; 3.33+ list some of them, struck through), hidden (no row of the page belongs to
///   the property's extension, i.e. the page does not render that extension's config) and other;
/// - **phase** (gating): for every row matched to one of our properties, the lock icon "Fixed at build time"
///   against our phase. Both generators draw the lock for BUILD_TIME and BUILD_AND_RUN_TIME_FIXED, never for
///   RUN_TIME and BOOTSTRAP;
/// - informational, per matched row: `env-var` (the page's environment variable against
///   [ConfigKeys#envVar] of our path with the row's primary key), `env-var-rule` (against
///   [ConfigKeys#envVar] of the page's own path), `display-name` (the page's path against ours),
///   `type` and `default` (normalised, best effort), `deprecated` and `ext` (the anchor
///   prefix, `-common`/`-internal` suffixes ignored).
public final class OfficialComparison {

    /// Phases whose properties the pages mark "Fixed at build time".
    static final Set<String> BUILD_TIME_PHASES = Set.of("BUILD_TIME", "BUILD_AND_RUN_TIME_FIXED");

    private static final Pattern TRAILING_MAP_KEY = Pattern.compile("\\.\"[^\"]*\"$");
    private static final Pattern EXT_FAMILY = Pattern.compile("-(?:common|internal)$");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern LIST_SEPARATOR = Pattern.compile("\\s*,\\s*");
    private static final String LIST_OF = "list of ";

    /// @param examples          examples kept per mismatch kind
    /// @param minNameAgreement  the name agreement below which the comparison fails
    /// @param minPhaseAgreement the phase agreement below which the comparison fails
    public record Options(int examples, double minNameAgreement, double minPhaseAgreement) {

        public static final Options DEFAULTS = new Options(10, 0.995, 0.99);
    }

    /// The compared snapshot.
    ///
    /// @param label      how the snapshot was named (`3.27.6.1`, `3.25.4:bytecode` or a file)
    /// @param file       the snapshot file, null when compared in memory
    /// @param properties number of properties
    /// @param deprecated number of deprecated properties
    public record SnapshotSide(String label, String file, String version, String source, String extractor,
            int properties, int deprecated) {
    }

    /// The compared page.
    ///
    /// @param location          URL or file of the page
    /// @param generator         `OLD` or `NEW` ([OfficialPage.Generator])
    /// @param documentedStream  the stream the page says it documents, null when it does not say
    /// @param documentedVersion the full version the page names, null when it names none
    /// @param documentedBy      where the documented stream was read
    /// @param rows              property rows
    /// @param paths             displayed paths (primary and alternative)
    /// @param keys              distinct canonical keys of the paths
    /// @param buildTime         rows marked "Fixed at build time"
    /// @param deprecated        rows marked deprecated
    /// @param withoutEnvVar     rows without an environment variable
    public record PageSide(String location, String generator, String documentedStream, String documentedVersion,
            String documentedBy, int rows, int paths, int keys, int buildTime, int deprecated, int withoutEnvVar) {

        /// One-line summary, e.g. `new generator, documents 3.20 (version selector), 4,096 rows, …`.
        public String summary() {
            String documents = documentedStream == null ? "does not say which release it documents"
                    : "documents " + (documentedVersion != null ? documentedVersion : documentedStream)
                            + " (" + documentedBy + ")";
            return location + ": " + generator.toLowerCase(Locale.ROOT) + " generator, " + documents + ", "
                    + Texts.count(rows) + " rows, " + Texts.count(paths) + " paths (" + Texts.count(keys)
                    + " keys), " + Texts.count(buildTime) + " fixed at build time, " + Texts.count(deprecated)
                    + " deprecated, " + Texts.count(withoutEnvVar) + " without env var";
        }
    }

    /// Coverage of the page's keys by ours.
    ///
    /// @param total         distinct page keys
    /// @param found         page keys that are keys or aliases of ours
    /// @param ratio         `found / total`
    /// @param unmatchedRows rows none of whose paths is ours
    public record Names(int total, int found, double ratio, int unmatchedRows) {
    }

    /// Our properties on the page.
    ///
    /// @param properties our properties
    /// @param onPage     properties with any key on the page
    /// @param deprecated properties not on the page that are deprecated
    /// @param hidden     properties not on the page, not deprecated, of an extension that has no row on the page
    /// @param other      the remaining properties not on the page
    public record Presence(int properties, int onPage, int deprecated, int hidden, int other) {
    }

    /// Build-time flags of the matched rows.
    ///
    /// @param agreement            rows whose lock icon agrees with our phase
    /// @param lockedButRunTime     rows marked "Fixed at build time" whose property has phase RUN_TIME or BOOTSTRAP
    /// @param unlockedButBuildTime rows without the lock whose property has phase BUILD_TIME or
    ///                             BUILD_AND_RUN_TIME_FIXED
    public record Phase(Agreement agreement, int lockedButRunTime, int unlockedButBuildTime) {
    }

    /// The comparison result; [#lines()] renders it.
    ///
    /// @param expectedStream the stream whose page was expected
    /// @param warnings       e.g. the page documents another stream
    /// @param informational  the informational checks, in report order
    /// @param failures       the thresholds that were not met; empty when the comparison passed
    public record Report(SnapshotSide ours, PageSide page, String expectedStream, List<String> warnings, Names names,
            Presence presence, Phase phase, List<Agreement> informational, List<Examples> examples, int exampleLimit,
            double minNameAgreement, double minPhaseAgreement, List<String> failures, boolean pass) {

        /// Human-readable report: one summary line per metric, then the examples.
        public List<String> lines() {
            List<String> out = new ArrayList<>();
            out.add(Texts.line("snapshot", Texts.snapshot(ours.label(), ours.version(), ours.source(),
                    ours.extractor()) + ", " + Texts.count(ours.properties()) + " properties, "
                    + Texts.count(ours.deprecated()) + " deprecated" + (ours.file() == null ? "" : " [" + ours.file()
                    + "]")));
            out.add(Texts.line("page", page.summary()));
            for (String warning : warnings) {
                out.add(Texts.line("warning", warning));
            }
            out.add(Texts.line("names", Texts.count(names.found()) + " / " + Texts.count(names.total())
                    + " page keys are ours (" + Texts.percent(names.ratio()) + "), "
                    + Texts.count(names.total() - names.found()) + " missing from ours, "
                    + Texts.count(names.unmatchedRows()) + " rows unmatched"));
            out.add(Texts.line("ours", Texts.count(presence.onPage()) + " / " + Texts.count(presence.properties())
                    + " properties on the page; not on it: " + Texts.count(presence.deprecated()) + " deprecated, "
                    + Texts.count(presence.hidden()) + " hidden (extension not on the page), "
                    + Texts.count(presence.other()) + " other"));
            Agreement phaseAgreement = phase.agreement();
            out.add(Texts.line("phase", Texts.count(phaseAgreement.equal()) + " / "
                    + Texts.count(phaseAgreement.compared()) + " matched rows agree ("
                    + Texts.percent(phaseAgreement.ratio()) + "), " + Texts.count(phase.lockedButRunTime())
                    + " locked but run time in ours, " + Texts.count(phase.unlockedButBuildTime())
                    + " not locked but build time in ours"));
            for (Agreement check : informational) {
                out.add(Texts.line(check.check(), check.text()));
            }
            out.add(Texts.line("thresholds", "names >= " + Texts.percent(minNameAgreement) + ", phase >= "
                    + Texts.percent(minPhaseAgreement)));
            out.add(Texts.line("result", pass ? "PASS" : "FAIL: " + String.join("; ", failures)));
            Texts.examples(out, examples, exampleLimit, "page", "ours");
            return out;
        }
    }

    /// One of our properties under one of its keys, with the path that gave the key.
    private record Owner(ConfigProperty property, String path) {
    }

    private OfficialComparison() {
    }

    /// Summary of a page on its own (for `verify official` without a snapshot).
    public static PageSide pageSide(String location, OfficialPage page) {
        Set<String> keys = new HashSet<>();
        int buildTime = 0;
        int deprecated = 0;
        int withoutEnvVar = 0;
        for (Row row : page.rows()) {
            row.paths().forEach(path -> keys.add(ConfigKeys.canonical(path)));
            buildTime += row.buildTime() ? 1 : 0;
            deprecated += row.deprecated() != null ? 1 : 0;
            withoutEnvVar += row.envVar() == null ? 1 : 0;
        }
        OfficialPage.Documented documented = page.documented();
        return new PageSide(location, page.generator().name(), documented == null ? null : documented.stream(),
                documented == null ? null : documented.version(), documented == null ? null : documented.source(),
                page.rows().size(), page.names(), keys.size(), buildTime, deprecated, withoutEnvVar);
    }

    /// Compares our snapshot with a page.
    ///
    /// @param location       URL or file of the page, for the report
    /// @param expectedStream the stream whose page this should be (the snapshot's, or `--stream`)
    public static Report compare(SnapshotComparison.Input input, OfficialPage page, String location,
            String expectedStream, Options options) {
        ConfigSnapshot snapshot = input.snapshot();
        List<ConfigProperty> properties = snapshot.properties() == null ? List.of() : snapshot.properties();
        boolean oldGenerator = page.generator() == Generator.OLD;
        int limit = options.examples();

        Map<String, Owner> ours = index(properties, oldGenerator);
        Map<String, String> pagePaths = new LinkedHashMap<>();
        Map<String, Row> pageRows = new HashMap<>();
        Set<String> pageExtensions = new HashSet<>();
        for (Row row : page.rows()) {
            for (String path : row.paths()) {
                String key = ConfigKeys.canonical(path);
                pagePaths.putIfAbsent(key, path);
                pageRows.putIfAbsent(key, row);
            }
            if (row.anchorExtension() != null) {
                pageExtensions.add(extFamily(row.anchorExtension()));
            }
        }

        List<Examples> examples = new ArrayList<>();
        List<Example> missingFromOurs = new ArrayList<>();
        int found = 0;
        for (Map.Entry<String, String> entry : pagePaths.entrySet()) {
            if (ours.containsKey(entry.getKey())) {
                found++;
            } else if (missingFromOurs.size() < limit) {
                missingFromOurs.add(new Example(entry.getKey(), describe(entry.getValue(),
                        pageRows.get(entry.getKey())), null));
            }
        }
        int missingCount = pagePaths.size() - found;
        if (missingCount > 0) {
            examples.add(new Examples("page-only", "on the page, not in ours", missingCount, missingFromOurs));
        }

        Presence presence = presence(properties, oldGenerator, pagePaths.keySet(), pageExtensions, limit, examples);

        Tally envVar = new Tally("env-var", "env var (ours)", false, limit);
        Tally envVarRule = new Tally("env-var-rule", "env var (rule on the page's path)", false, limit);
        Tally displayName = new Tally("display-name", "display name", false, limit);
        Tally type = new Tally("type", "type", false, limit);
        Tally defaultValue = new Tally("default", "default", false, limit);
        Tally deprecated = new Tally("deprecated", "deprecated", false, limit);
        Tally ext = new Tally("ext", "ext (anchor)", false, limit);
        int phaseEqual = 0;
        int lockedButRunTime = 0;
        int unlockedButBuildTime = 0;
        int unmatchedRows = 0;
        List<Example> lockedExamples = new ArrayList<>();
        List<Example> unlockedExamples = new ArrayList<>();
        for (Row row : page.rows()) {
            String primaryKey = ConfigKeys.canonical(row.name());
            if (row.envVar() != null) {
                envVarRule.compare(primaryKey, row.envVar(), ConfigKeys.envVar(row.name()));
            }
            String pagePath = null;
            Owner owner = null;
            for (String path : row.paths()) {
                owner = ours.get(ConfigKeys.canonical(path));
                if (owner != null) {
                    pagePath = path;
                    break;
                }
            }
            if (owner == null) {
                unmatchedRows++;
                continue;
            }
            ConfigProperty property = owner.property();
            String key = ConfigKeys.canonical(pagePath);
            boolean ourBuildTime = BUILD_TIME_PHASES.contains(property.phase());
            if (row.buildTime() == ourBuildTime) {
                phaseEqual++;
            } else if (row.buildTime()) {
                if (lockedButRunTime++ < limit) {
                    lockedExamples.add(new Example(key, "fixed at build time", property.phase()));
                }
            } else if (unlockedButBuildTime++ < limit) {
                unlockedExamples.add(new Example(key, "not fixed at build time", property.phase()));
            }
            if (row.envVar() != null && pagePath.equals(row.name())) {
                envVar.compare(key, row.envVar(), ConfigKeys.envVar(owner.path()));
            }
            displayName.compare(key, pagePath, owner.path());
            type.compare(key, pageType(row, property), ourType(property));
            defaultValue.compare(key, normalisedDefault(row.defaultValue()),
                    normalisedDefault(property.defaultValue()));
            deprecated.compare(key, row.deprecated(), property.deprecated());
            String anchorExt = row.anchorExtension();
            ext.add(Tally.outcome(anchorExt == null ? null : extFamily(anchorExt),
                    property.ext() == null ? null : extFamily(property.ext())), key, anchorExt, property.ext());
        }
        int matchedRows = page.rows().size() - unmatchedRows;
        Agreement phaseAgreement = new Agreement("phase", matchedRows, phaseEqual, matchedRows - phaseEqual, 0,
                Agreement.ratio(phaseEqual, matchedRows), true);
        if (lockedButRunTime > 0) {
            examples.add(new Examples("phase/locked-but-run-time",
                    "fixed at build time on the page, run time in ours", lockedButRunTime, lockedExamples));
        }
        if (unlockedButBuildTime > 0) {
            examples.add(new Examples("phase/unlocked-but-build-time",
                    "not fixed at build time on the page, build time in ours", unlockedButBuildTime,
                    unlockedExamples));
        }
        List<Tally> checks = List.of(envVar, envVarRule, displayName, type, defaultValue, deprecated, ext);
        checks.forEach(check -> examples.addAll(check.examples()));

        Names names = new Names(pagePaths.size(), found, Agreement.ratio(found, pagePaths.size()), unmatchedRows);
        List<String> failures = new ArrayList<>();
        if (names.ratio() < options.minNameAgreement()) {
            failures.add("name agreement " + Texts.percent(names.ratio()) + " < "
                    + Texts.percent(options.minNameAgreement()));
        }
        if (phaseAgreement.ratio() < options.minPhaseAgreement()) {
            failures.add("phase agreement " + Texts.percent(phaseAgreement.ratio()) + " < "
                    + Texts.percent(options.minPhaseAgreement()));
        }
        PageSide pageSide = pageSide(location, page);
        SnapshotSide snapshotSide = new SnapshotSide(input.label(), input.file(), snapshot.version(),
                snapshot.source(), snapshot.extractor(), properties.size(),
                (int) properties.stream().filter(p -> p.deprecated() != null).count());
        return new Report(snapshotSide, pageSide, expectedStream, warnings(snapshot.version(), pageSide,
                expectedStream), names, presence, new Phase(phaseAgreement, lockedButRunTime, unlockedButBuildTime),
                checks.stream().map(Tally::agreement).toList(), List.copyOf(examples), limit,
                options.minNameAgreement(), options.minPhaseAgreement(), List.copyOf(failures), failures.isEmpty());
    }

    /// Mismatches between what the page documents and what is compared: another stream than expected, a full
    /// version other than the snapshot's, or a snapshot from another stream than the page's.
    static List<String> warnings(String snapshotVersion, PageSide page, String expectedStream) {
        List<String> warnings = new ArrayList<>();
        if (page.documentedStream() != null && expectedStream != null
                && !page.documentedStream().equals(expectedStream)) {
            warnings.add("the page documents " + page.documentedStream() + " (" + page.documentedBy()
                    + "), not stream " + expectedStream);
        }
        if (page.documentedVersion() != null && snapshotVersion != null
                && !page.documentedVersion().equals(snapshotVersion)) {
            warnings.add("the page documents " + page.documentedVersion() + ", the snapshot is " + snapshotVersion);
        }
        String snapshotStream = snapshotVersion == null ? null
                : QuarkusVersion.tryParse(snapshotVersion).map(QuarkusVersion::stream).orElse(null);
        if (snapshotStream != null && expectedStream != null && !snapshotStream.equals(expectedStream)) {
            warnings.add("the snapshot is from stream " + snapshotStream + ", the page from stream " + expectedStream);
        }
        return List.copyOf(warnings);
    }

    /// Our properties by every key they can be found under on the page: names first, then aliases, then (old
    /// generator only) the keys of leaf maps without their trailing `.*`.
    private static Map<String, Owner> index(List<ConfigProperty> properties, boolean oldGenerator) {
        Map<String, Owner> ours = new HashMap<>();
        for (ConfigProperty property : properties) {
            ours.putIfAbsent(ConfigKeys.canonical(property.name()), new Owner(property, property.name()));
        }
        for (ConfigProperty property : properties) {
            for (String alias : property.aliases() == null ? List.<String>of() : property.aliases()) {
                ours.putIfAbsent(ConfigKeys.canonical(alias), new Owner(property, alias));
            }
        }
        if (oldGenerator) {
            for (ConfigProperty property : properties) {
                if (property.map()) {
                    for (String path : paths(property)) {
                        String stripped = withoutMapKey(path);
                        if (stripped != null) {
                            ours.putIfAbsent(ConfigKeys.canonical(stripped), new Owner(property, stripped));
                        }
                    }
                }
            }
        }
        return ours;
    }

    private static Presence presence(List<ConfigProperty> properties, boolean oldGenerator, Set<String> pageKeys,
            Set<String> pageExtensions, int limit, List<Examples> examples) {
        int onPage = 0;
        List<Example> deprecated = new ArrayList<>();
        List<Example> hidden = new ArrayList<>();
        List<Example> other = new ArrayList<>();
        int deprecatedCount = 0;
        int hiddenCount = 0;
        int otherCount = 0;
        for (ConfigProperty property : properties) {
            if (keys(property, oldGenerator).stream().anyMatch(pageKeys::contains)) {
                onPage++;
            } else if (property.deprecated() != null) {
                deprecatedCount++;
                keep(deprecated, property, limit);
            } else if (!pageExtensions.isEmpty() && property.ext() != null
                    && !pageExtensions.contains(extFamily(property.ext()))) {
                hiddenCount++;
                keep(hidden, property, limit);
            } else {
                otherCount++;
                keep(other, property, limit);
            }
        }
        if (otherCount > 0) {
            examples.add(new Examples("ours-only", "in ours, not on the page (not deprecated)", otherCount, other));
        }
        if (hiddenCount > 0) {
            examples.add(new Examples("ours-only/hidden", "in ours, not on the page: extension not on the page",
                    hiddenCount, hidden));
        }
        if (deprecatedCount > 0) {
            examples.add(new Examples("ours-only/deprecated", "in ours, not on the page: deprecated",
                    deprecatedCount, deprecated));
        }
        return new Presence(properties.size(), onPage, deprecatedCount, hiddenCount, otherCount);
    }

    private static void keep(List<Example> examples, ConfigProperty property, int limit) {
        if (examples.size() < limit) {
            examples.add(new Example(SnapshotComparison.keyOf(property), null, SnapshotComparison.describe(property)));
        }
    }

    /// The keys a property can be found under on the page.
    private static Set<String> keys(ConfigProperty property, boolean oldGenerator) {
        Set<String> keys = new HashSet<>();
        for (String path : paths(property)) {
            keys.add(ConfigKeys.canonical(path));
            String stripped = oldGenerator && property.map() ? withoutMapKey(path) : null;
            if (stripped != null) {
                keys.add(ConfigKeys.canonical(stripped));
            }
        }
        return keys;
    }

    private static List<String> paths(ConfigProperty property) {
        List<String> paths = new ArrayList<>();
        paths.add(property.name());
        if (property.aliases() != null) {
            paths.addAll(property.aliases());
        }
        return paths;
    }

    /// A leaf map path without its trailing key segment (`a.b."key"` → `a.b`); null when it has none.
    static String withoutMapKey(String path) {
        Matcher m = TRAILING_MAP_KEY.matcher(path);
        return m.find() ? path.substring(0, m.start()) : null;
    }

    /// An extension artifactId without a `-common` or `-internal` suffix (the doc merger drops them).
    static String extFamily(String ext) {
        return EXT_FAMILY.matcher(ext).replaceFirst("");
    }

    /// The page's type for comparison: for our enums, the accepted values as a sorted set (with `list of` when
    /// the page says so); otherwise the cell text with whitespace collapsed.
    static String pageType(Row row, ConfigProperty property) {
        if (row.type() == null) {
            return null;
        }
        String text = WHITESPACE.matcher(row.type()).replaceAll(" ").strip();
        if (isEnum(property) && !row.typeCodes().isEmpty()) {
            return (text.startsWith(LIST_OF) ? LIST_OF : "") + new TreeSet<>(row.typeCodes());
        }
        return text;
    }

    /// Our type as the new generator prints it: `list of ` + type for lists, enums as their sorted values.
    static String ourType(ConfigProperty property) {
        String prefix = property.list() ? LIST_OF : "";
        if (isEnum(property)) {
            return prefix + new TreeSet<>(property.enumValues());
        }
        return property.type() == null ? null : prefix + property.type();
    }

    /// A default for comparison: backticks removed (the pages move them), whitespace collapsed, no space around
    /// commas (the pages print list defaults as `gzip, deflate`), empty → null.
    static String normalisedDefault(String value) {
        if (value == null) {
            return null;
        }
        String text = WHITESPACE.matcher(value.replace("`", "")).replaceAll(" ").strip();
        text = LIST_SEPARATOR.matcher(text).replaceAll(",");
        return text.isEmpty() ? null : text;
    }

    private static boolean isEnum(ConfigProperty property) {
        return property.enumValues() != null && !property.enumValues().isEmpty();
    }

    private static String describe(String path, Row row) {
        if (row == null) {
            return path;
        }
        return path + "  [" + Objects.requireNonNullElse(row.anchorExtension(), "?") + ", "
                + Objects.requireNonNullElse(row.type(), "?") + (row.buildTime() ? ", fixed at build time" : "")
                + (row.deprecated() != null ? ", deprecated" : "") + "]";
    }
}
