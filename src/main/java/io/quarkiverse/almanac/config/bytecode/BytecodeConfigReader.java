package io.quarkiverse.almanac.config.bytecode;

import io.quarkiverse.almanac.config.ConfigProperties;
import io.quarkiverse.almanac.config.ConfigReader;
import io.quarkiverse.almanac.config.JarSource;
import io.quarkiverse.almanac.config.VersionJars;
import io.quarkiverse.almanac.config.sourcedoc.DocAnnotationSource;
import io.quarkiverse.almanac.config.sourcedoc.SourceDocAnnotations;
import io.quarkiverse.almanac.maven.QuarkusVersion;
import io.quarkiverse.almanac.model.ConfigProperty;
import io.quarkiverse.almanac.model.ConfigSnapshot;
import io.quarkiverse.almanac.util.ConfigKeys;
import io.quarkiverse.almanac.util.Log;
import io.quarkiverse.almanac.util.Parallel;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

/// Reconstructs the config of a release from bytecode, for releases without the JSON model (3.0 – 3.24) and, as a
/// cross-check, for later ones. It emulates the Quarkus 3.25 config documentation processor on the class files of every
/// jar that lists config roots in {@value #ROOTS_ENTRY}, whatever the era, and maps each property as [io.quarkiverse.almanac.config.ModelJsonConfigReader] does: name = path,
/// aliases = additional paths, type = type description, the list / optional / map flags, the documented default (`""`
/// becomes null), the root's phase (`BOOTSTRAP` in 3.0 – 3.2), deprecation, enum values, desc = first sentence of the
/// member's Javadoc anywhere in the release, ext = jar artifactId without `-deployment`, source = `sourceType#member`.
///
/// - Classes come from the release's `io.quarkus` jars (a module sees its own copy first), the running JDK, and, for
///   types found nowhere else (mostly enums such as `io.vertx.core.http.ClientAuth`), the BOM's third-party jars whose
///   coordinates match the package ([LibraryClasses]). A type found nowhere is read as a non-enum class, with one
///   warning per type.
/// - Before 3.14, `@ConfigDocDefault`, `@ConfigDocMapKey` and `@ConfigDocIgnore` have SOURCE retention; they are asked
///   from the [DocAnnotationSource] given to the constructor (class-file annotations win when present) and applied as
///   the processor applies the class-file annotations. When the source cannot provide them (no source, or every
///   lookup failed), the snapshot equals a read of the class files only and its first warning starts with
///   `doc annotations unavailable`; the problems a [SourceDocAnnotations] reports (missing sources jars, files,
///   members) become warnings too.
/// - Jars are scanned in parallel; the result does not depend on timing. Properties are united in jar order (by
///   artifactId) and de-duplicated by [ConfigProperties#sortedUnique], like the model reader.
public final class BytecodeConfigReader implements ConfigReader {

    /// Config roots of a module (binary class names, one per line), present in every era.
    public static final String ROOTS_ENTRY = "META-INF/quarkus-config-roots.list";

    /// First release whose class files carry the `@ConfigDoc*` annotations (RUNTIME retention).
    public static final QuarkusVersion RUNTIME_DOC_ANNOTATIONS = QuarkusVersion.parse("3.14.0");

    /// `bytecode/1` was the reader while it was being finished; its snapshots are never imported.
    private static final String ID = "bytecode/2";
    private static final String SOURCE = "bytecode";

    private final Function<VersionJars, DocAnnotationSource> docAnnotations;

    /// A reader that uses the class files only, also for releases before 3.14.
    public BytecodeConfigReader() {
        this(jars -> DocAnnotationSource.NONE);
    }

    /// @param docAnnotations the source of the `@ConfigDoc*` annotations of a release, consulted only for releases
    ///                       before [#RUNTIME_DOC_ANNOTATIONS]
    public BytecodeConfigReader(Function<VersionJars, DocAnnotationSource> docAnnotations) {
        this.docAnnotations = docAnnotations;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public ConfigSnapshot read(VersionJars jars) throws IOException {
        long start = System.nanoTime();
        String version = jars.quarkusVersion();
        List<String> readWarnings = new ArrayList<>();
        Map<JarSource, byte[]> rootLists = jars.readAll(ROOTS_ENTRY);
        Javadocs javadocs = Javadocs.read(jars, readWarnings);
        MemberDocs docs = memberDocs(jars);
        ClassPath classPath = new ClassPath(jars);
        ModelFormat format = ModelFormat.of(version);
        UnresolvedTypes unresolved = new UnresolvedTypes();

        List<Map.Entry<JarSource, byte[]>> modules = List.copyOf(rootLists.entrySet());
        List<ModuleScanner.Result> results = Parallel.map(modules, Math.max(1, jars.parallelism()) * 2,
                module -> new ModuleScanner(module.getKey(), classPath, format, docs, unresolved)
                        .scan(rootNames(module.getValue())));

        List<ConfigProperty> properties = new ArrayList<>();
        Set<String> roots = new TreeSet<>();
        List<String> warnings = docAnnotationWarnings(version, docs);
        warnings.addAll(readWarnings);
        for (ModuleScanner.Result result : results) {
            warnings.addAll(result.warnings());
            roots.addAll(result.roots());
            String ext = ConfigProperties.extensionOf(result.jar().artifactId());
            for (LeafProperty leaf : result.properties()) {
                properties.add(property(leaf, ext, javadocs));
            }
        }
        warnings.addAll(classPath.warnings());
        warnings.addAll(unresolved.warnings());
        List<ConfigProperty> unique = ConfigProperties.sortedUnique(properties, warnings);
        Log.debug("%s: bytecode reader: %d modules, %d roots, %d properties (%d before de-duplication), "
                        + "%d javadoc elements, %d third-party classes from %d probes, %d unresolved types, %.1f s",
                version, results.size(), roots.size(), unique.size(), properties.size(), javadocs.size(),
                classPath.thirdPartyClasses().size(), classPath.libraryProbes(), unresolved.types().size(),
                (System.nanoTime() - start) / 1e9);
        return new ConfigSnapshot(version, SOURCE, ID, roots.size(), List.copyOf(warnings), unique);
    }

    /// Whether the class files of a release lack the SOURCE-retention `@ConfigDoc*` annotations.
    static boolean lacksDocAnnotations(String quarkusVersion) {
        return QuarkusVersion.tryParse(quarkusVersion)
                .map(version -> version.compareTo(RUNTIME_DOC_ANNOTATIONS) < 0)
                .orElse(false);
    }

    /// The doc annotations of a release: none for releases whose class files carry them or when the source is
    /// [DocAnnotationSource#NONE], else the release's source.
    private MemberDocs memberDocs(VersionJars jars) {
        if (!lacksDocAnnotations(jars.quarkusVersion())) {
            return MemberDocs.NONE;
        }
        DocAnnotationSource source = docAnnotations.apply(jars);
        return source == null || source == DocAnnotationSource.NONE ? MemberDocs.NONE : new MemberDocs(source);
    }

    /// What the snapshot says about the doc annotations of a release before 3.14, first among its warnings: that they
    /// are unavailable, or the problems of the source (lookups that failed, missing sources jars or members).
    private static List<String> docAnnotationWarnings(String version, MemberDocs docs) {
        List<String> warnings = new ArrayList<>();
        if (!lacksDocAnnotations(version)) {
            return warnings;
        }
        if (!docs.active()) {
            warnings.add(MemberDocs.UNAVAILABLE + ": " + MemberDocs.CONSEQUENCE);
            return warnings;
        }
        warnings.addAll(docs.warnings());
        if (docs.source() instanceof SourceDocAnnotations sources) {
            SourceDocAnnotations.Stats stats = sources.stats();
            Log.debug("%s: doc annotations from sources jars: %s; found %s", version, stats.summary(),
                    stats.lookedUp());
            if (docs.ownersFailed() < docs.ownersLookedUp()) {
                // when every lookup failed, the single warning above says it all
                for (String problem : sources.warnings()) {
                    warnings.add("doc annotations: " + problem);
                }
            }
        }
        return warnings;
    }

    /// The binary class names listed in a {@value #ROOTS_ENTRY} file.
    static List<String> rootNames(byte[] rootsList) {
        List<String> names = new ArrayList<>();
        for (String line : new String(rootsList, StandardCharsets.UTF_8).split("\n")) {
            String name = line.strip();
            if (!name.isEmpty() && !name.startsWith("#")) {
                names.add(name);
            }
        }
        return names;
    }

    /// Maps a leaf to the almanac record.
    static ConfigProperty property(LeafProperty leaf, String ext, Javadocs javadocs) {
        String defaultValue = leaf.defaultValue() == null || leaf.defaultValue().isEmpty() ? null
                : leaf.defaultValue();
        return new ConfigProperty(
                leaf.path(),
                ConfigKeys.canonical(leaf.path()),
                List.copyOf(leaf.additionalPaths()),
                ext,
                leaf.typeDescription(),
                leaf.list(),
                leaf.optional(),
                leaf.map(),
                defaultValue,
                leaf.phase(),
                leaf.deprecated(),
                leaf.enumValues() == null ? null : List.copyOf(leaf.enumValues()),
                javadocs.description(leaf.sourceType(), leaf.sourceElementName()),
                leaf.sourceType() + "#" + leaf.sourceElementName());
    }
}
