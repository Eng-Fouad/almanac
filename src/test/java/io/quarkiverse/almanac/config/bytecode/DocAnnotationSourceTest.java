package io.quarkiverse.almanac.config.bytecode;

import io.quarkiverse.almanac.config.VersionJars;
import io.quarkiverse.almanac.config.sourcedoc.DocAnnotationSource;
import io.quarkiverse.almanac.config.sourcedoc.DocAnnotations;
import io.quarkiverse.almanac.maven.Http;
import io.quarkiverse.almanac.model.ConfigProperty;
import io.quarkiverse.almanac.model.ConfigSnapshot;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Releases before 3.14: `@ConfigDocMapKey`, `@ConfigDocDefault`, `@ConfigDocIgnore` and `@ConfigDocSection` have
/// SOURCE retention, so the reader asks a [DocAnnotationSource] for them and applies them as the processor applies the
/// class-file annotations. Here the source is a fake; the real one, over
/// `-sources` jars, is exercised by `OfflineExtractionTest`.
class DocAnnotationSourceTest {

    private static final String OLD = "io/quarkus/old/OldConfig";
    private static final String NAMED = "io/quarkus/old/OldConfig$NamedConfig";
    private static final String LEGACY = "io/quarkus/old/OldLegacyConfig";
    private static Map<String, byte[]> sourceRetained;
    private static Map<String, byte[]> runtimeRetained;

    @BeforeAll
    static void compile() {
        String header = "package io.quarkus.old;\nimport io.quarkus.runtime.annotations.*;\n"
                + "import io.smallrye.config.*;\nimport java.util.*;\n";
        String mapping = header + """
                @ConfigMapping(prefix = "quarkus.old")
                @ConfigRoot(phase = ConfigPhase.RUN_TIME)
                public interface OldConfig {
                    @WithParentName @WithUnnamedKey Map<String, NamedConfig> namedDataSources();
                    @WithDefault("x") Optional<String> dialect();
                    Optional<Boolean> optimizeContexts();
                    @ConfigDocMapKey("in-class-file") Map<String, String> props();
                    @ConfigDocDefault("class-file default") Optional<String> mode();
                    interface NamedConfig { Optional<String> url(); @ConfigDocSection Map<String, String> extra(); }
                }""";
        String legacy = header + """
                @ConfigRoot
                public class OldLegacyConfig {
                    public boolean ignoredWithoutItem;
                    @ConfigItem public boolean keptWithItem;
                    @ConfigItem public Optional<String> docDefaulted;
                    @ConfigItem(defaultValueDocumentation = "from @ConfigItem") public Optional<String> itemDocDefault;
                    public Optional<String> noItemDocDefault;
                    @ConfigItem public Map<String, String> legacyMap;
                }""";
        sourceRetained = JavaSources.withConfigAnnotations(false)
                .add("io.quarkus.old.OldConfig", mapping).add("io.quarkus.old.OldLegacyConfig", legacy).compile();
        runtimeRetained = JavaSources.withConfigAnnotations(true)
                .add("io.quarkus.old.OldConfig", mapping).add("io.quarkus.old.OldLegacyConfig", legacy).compile();
    }

    /// What the sources jars of the fixture say.
    private static final Map<String, DocAnnotations> SOURCES = Map.ofEntries(
            Map.entry(OLD + "#namedDataSources", new DocAnnotations(null, "datasource-name", false, true)),
            Map.entry(OLD + "#dialect", new DocAnnotations("depends on the database", null, false, false)),
            Map.entry(OLD + "#optimizeContexts", new DocAnnotations(null, null, true, false)),
            Map.entry(OLD + "#props", new DocAnnotations(null, "in-source", false, false)),
            Map.entry(OLD + "#mode", new DocAnnotations("source default", null, false, false)),
            Map.entry(NAMED + "#extra", new DocAnnotations(null, "key", false, true)),
            Map.entry(LEGACY + "#ignoredWithoutItem", new DocAnnotations(null, null, true, false)),
            Map.entry(LEGACY + "#keptWithItem", new DocAnnotations(null, null, true, false)),
            Map.entry(LEGACY + "#docDefaulted", new DocAnnotations("computed", null, false, false)),
            Map.entry(LEGACY + "#itemDocDefault", new DocAnnotations("not used", null, false, false)),
            Map.entry(LEGACY + "#noItemDocDefault", new DocAnnotations("not used", null, false, false)),
            Map.entry(LEGACY + "#legacyMap", new DocAnnotations(null, "property-name", false, false)));

    /// A fake source over [#SOURCES] that counts its calls.
    private static final class FakeSource implements DocAnnotationSource {

        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public DocAnnotations get(String ownerInternalName, String memberName) {
            calls.incrementAndGet();
            return SOURCES.getOrDefault(ownerInternalName + "#" + memberName, DocAnnotations.NONE);
        }
    }

    private static VersionJars release(String version, Map<String, byte[]> classes) {
        TestJar jar = TestJar.of("io.quarkus", "quarkus-old", version)
                .withClasses(classes, List.of(OLD, NAMED, LEGACY))
                .withRoots("io.quarkus.old.OldConfig", "io.quarkus.old.OldLegacyConfig");
        return new VersionJars(version, List.of(jar), jar1 -> Optional.empty(), 1);
    }

    private static ConfigSnapshot read(BytecodeConfigReader reader, String version, Map<String, byte[]> classes)
            throws IOException {
        return reader.read(release(version, classes));
    }

    private static Optional<ConfigProperty> find(ConfigSnapshot snapshot, String name) {
        return snapshot.properties().stream().filter(p -> p.name().equals(name)).findFirst();
    }

    private static ConfigProperty property(ConfigSnapshot snapshot, String name) {
        return find(snapshot, name).orElseThrow(() -> new AssertionError("no property " + name + " in "
                + snapshot.properties().stream().map(ConfigProperty::name).toList()));
    }

    private static List<String> docWarnings(ConfigSnapshot snapshot) {
        return snapshot.warnings().stream().filter(w -> w.startsWith("doc annotations")).toList();
    }

    @Test
    void appliesSourceAnnotationsLikeClassFileAnnotations() throws IOException {
        FakeSource source = new FakeSource();
        ConfigSnapshot snapshot = read(new BytecodeConfigReader(jars -> source), "3.8.6", sourceRetained);
        // @ConfigDocMapKey names the key of a group map (here an unnamed one) and of a leaf map
        assertEquals(List.of("quarkus.old.\"datasource-name\".url"), property(snapshot, "quarkus.old.url").aliases());
        assertEquals(List.of("quarkus.old.\"datasource-name\".extra.\"key\""),
                property(snapshot, "quarkus.old.extra.\"key\"").aliases());
        assertTrue(find(snapshot, "quarkus.old.props.\"in-source\"").isPresent());
        // @ConfigDocDefault wins over @WithDefault, @ConfigDocIgnore hides the member
        assertEquals("depends on the database", property(snapshot, "quarkus.old.dialect").defaultValue());
        assertEquals("source default", property(snapshot, "quarkus.old.mode").defaultValue());
        assertTrue(find(snapshot, "quarkus.old.optimize-contexts").isEmpty());
        // legacy fields: @ConfigDocIgnore only without @ConfigItem, @ConfigDocDefault only with it and without
        // @ConfigItem(defaultValueDocumentation)
        assertTrue(find(snapshot, "quarkus.old-legacy.ignored-without-item").isEmpty());
        assertEquals("false", property(snapshot, "quarkus.old-legacy.kept-with-item").defaultValue());
        assertEquals("computed", property(snapshot, "quarkus.old-legacy.doc-defaulted").defaultValue());
        assertEquals("from @ConfigItem", property(snapshot, "quarkus.old-legacy.item-doc-default").defaultValue());
        assertNull(property(snapshot, "quarkus.old-legacy.no-item-doc-default").defaultValue());
        assertTrue(find(snapshot, "quarkus.old-legacy.legacy-map.\"property-name\"").isPresent());
        assertTrue(source.calls.get() >= SOURCES.size());
        assertTrue(docWarnings(snapshot).isEmpty(), snapshot.warnings().toString());
    }

    @Test
    void readsClassFilesOnlyWithoutSource() throws IOException {
        ConfigSnapshot snapshot = read(new BytecodeConfigReader(), "3.8.6", sourceRetained);
        assertEquals(List.of("quarkus.old.\"named-data-sources\".url"),
                property(snapshot, "quarkus.old.url").aliases());
        assertEquals("x", property(snapshot, "quarkus.old.dialect").defaultValue());
        assertTrue(find(snapshot, "quarkus.old.optimize-contexts").isPresent());
        assertTrue(find(snapshot, "quarkus.old.props.\"props\"").isPresent());
        assertNull(property(snapshot, "quarkus.old.mode").defaultValue());
        assertTrue(find(snapshot, "quarkus.old-legacy.ignored-without-item").isPresent());
        assertTrue(snapshot.warnings().getFirst().startsWith("doc annotations unavailable"),
                snapshot.warnings().toString());
        assertEquals(1, docWarnings(snapshot).size());
        // canonical keys do not depend on the doc annotations, except for ignored members
        ConfigSnapshot withSource = read(new BytecodeConfigReader(jars -> new FakeSource()), "3.8.6",
                sourceRetained);
        List<String> keys = snapshot.properties().stream().map(ConfigProperty::key)
                .filter(key -> !key.equals("quarkus.old.optimize-contexts")
                        && !key.equals("quarkus.old-legacy.ignored-without-item"))
                .toList();
        assertEquals(keys, withSource.properties().stream().map(ConfigProperty::key).toList());
    }

    @Test
    void preferClassFileAnnotationsWhenPresent() throws IOException {
        ConfigSnapshot snapshot = read(new BytecodeConfigReader(jars -> new FakeSource()), "3.8.6",
                runtimeRetained);
        assertTrue(find(snapshot, "quarkus.old.props.\"in-class-file\"").isPresent());
        assertEquals("class-file default", property(snapshot, "quarkus.old.mode").defaultValue());
        // members without class-file annotations still get the source's
        assertEquals("depends on the database", property(snapshot, "quarkus.old.dialect").defaultValue());
    }

    @Test
    void neverAsksForReleasesWithRuntimeDocAnnotations() throws IOException {
        FakeSource source = new FakeSource();
        ConfigSnapshot snapshot = read(new BytecodeConfigReader(jars -> source), "3.14.0", runtimeRetained);
        assertEquals(0, source.calls.get());
        assertTrue(find(snapshot, "quarkus.old.props.\"in-class-file\"").isPresent());
        assertTrue(docWarnings(snapshot).isEmpty());
        // without annotations in the class files either, a 3.14+ release reads what it has, without a warning
        ConfigSnapshot stripped = read(new BytecodeConfigReader(jars -> source), "3.14.0", sourceRetained);
        assertEquals(0, source.calls.get());
        assertTrue(docWarnings(stripped).isEmpty());
    }

    @Test
    void turnsSourceFailuresIntoWarnings() throws IOException {
        DocAnnotationSource failing = (owner, member) -> {
            if (owner.equals(LEGACY)) {
                throw new IOException("sources jar unreadable");
            }
            return SOURCES.getOrDefault(owner + "#" + member, DocAnnotations.NONE);
        };
        ConfigSnapshot snapshot = read(new BytecodeConfigReader(jars -> failing), "3.8.6", sourceRetained);
        assertEquals("depends on the database", property(snapshot, "quarkus.old.dialect").defaultValue());
        assertTrue(find(snapshot, "quarkus.old-legacy.ignored-without-item").isPresent());
        // one warning per declaring type that failed, first among the warnings
        List<String> warnings = docWarnings(snapshot);
        assertEquals(1, warnings.size(), snapshot.warnings().toString());
        assertEquals(warnings.getFirst(), snapshot.warnings().getFirst());
        assertTrue(warnings.getFirst().contains("io.quarkus.old.OldLegacyConfig"), warnings.getFirst());
        assertTrue(warnings.getFirst().contains("sources jar unreadable"), warnings.getFirst());
    }

    @Test
    void aRefusedLookupEndsTheReadInsteadOfBecomingAWarning() {
        DocAnnotationSource refused = (owner, member) -> {
            throw new Http.StoppedException("not requesting " + owner + ".java: requests stopped after HTTP 403");
        };
        Http.StoppedException stopped = assertThrows(Http.StoppedException.class,
                () -> read(new BytecodeConfigReader(jars -> refused), "3.8.6", sourceRetained));
        assertTrue(stopped.getMessage().contains("requests stopped after HTTP 403"), stopped.getMessage());
    }

    @Test
    void readsLikeClassFilesOnlyWhenEveryLookupFails() throws IOException {
        DocAnnotationSource offline = (owner, member) -> {
            throw new IOException("offline mode: not fetching " + owner + "-sources.jar");
        };
        ConfigSnapshot failed = read(new BytecodeConfigReader(jars -> offline), "3.8.6", sourceRetained);
        ConfigSnapshot classFilesOnly = read(new BytecodeConfigReader(), "3.8.6", sourceRetained);
        assertEquals(classFilesOnly.properties(), failed.properties());
        // a single warning, naming the number of types and the first failure
        List<String> warnings = docWarnings(failed);
        assertEquals(1, warnings.size(), failed.warnings().toString());
        assertTrue(warnings.getFirst().startsWith("doc annotations unavailable: the lookup failed for all 3 types"),
                warnings.getFirst());
        assertTrue(warnings.getFirst().contains("offline mode"), warnings.getFirst());
        assertFalse(failed.warnings().stream().anyMatch(w -> w.contains("read from the class file only")));
    }
}
