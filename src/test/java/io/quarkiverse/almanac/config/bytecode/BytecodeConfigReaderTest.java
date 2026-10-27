package io.quarkiverse.almanac.config.bytecode;

import io.quarkiverse.almanac.config.VersionJars;
import io.quarkiverse.almanac.model.ConfigProperty;
import io.quarkiverse.almanac.model.ConfigSnapshot;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The reader on class files compiled from fixture sources that exercise the processor rules (3.14+
/// retention: the class files carry the doc annotations), with test vectors confirmed against the 3.25.0 model.
class BytecodeConfigReaderTest {

    private static final String RUNTIME = "io.quarkus.demo.runtime.";
    private static Map<String, byte[]> classes;

    @BeforeAll
    static void compile() {
        String imports = "import io.quarkus.runtime.annotations.*;\nimport io.smallrye.config.*;\n"
                + "import java.time.Duration;\nimport java.time.temporal.ChronoUnit;\nimport java.util.*;\n";
        String runtime = "package io.quarkus.demo.runtime;\n" + imports;
        classes = JavaSources.withConfigAnnotations(true)
                .add(RUNTIME + "DemoBase", runtime + """
                        public interface DemoBase {
                            Optional<String> name();
                            Optional<String> overridden();
                        }""")
                .add(RUNTIME + "DemoConfig", runtime + """
                        @ConfigMapping(prefix = "quarkus.demo")
                        @ConfigRoot(phase = ConfigPhase.RUN_TIME)
                        public interface DemoConfig extends DemoBase {
                            @WithDefault("true") boolean enabled();
                            @WithDefault("10s") Duration timeout();
                            @WithDefault("60") Duration delay();
                            @WithDefault("PT1M") Duration iso();
                            @ConfigDocDefault("10s") Duration docTimeout();
                            Optional<Duration> connectionTTL();
                            int maxHTTP2Streams();
                            @WithDefault("5") @ConfigDocDefault("depends on the dialect") int poolSize();
                            @WithDefault("") Optional<String> emptyDefault();
                            @WithDefault("DEBUG_ALL") DemoLevel level();
                            @WithDefault("READ_ONLY, read_write") List<Mode> modes();
                            @WithDefault("auto") Set<ValidationMode> validationModes();
                            @WithDefault("USB, NFC") List<Transport> transports();
                            @ConfigDocDefault("ES256,RS256") List<CoseAlgorithm> algorithms();
                            @WithConverter(Conv.class) @WithDefault("V5_6") Compat compat();
                            @WithDefault("SECONDS") ChronoUnit unit();
                            @WithName("request-scoped.enabled") @WithDefault("true")
                            boolean requestScopedSessionEnabled();
                            @WithName("category") Map<String, CategoryConfig> categories();
                            @WithParentName @WithUnnamedKey @ConfigDocMapKey("datasource-name")
                            Map<String, DataSourceConfig> dataSources();
                            Optional<ThemeConfig> theme();
                            List<ProxyConfig> trustedProxy();
                            Set<ProxyConfig> proxySet();
                            ProxyConfig[] proxyArray();
                            Map<String, Map<String, CategoryConfig>> nested();
                            @ConfigDocMapKey("label-name") Map<String, String> labels();
                            Map<String, Map<String, List<String>>> complexConfig();
                            @ConfigDocIgnore boolean hidden();
                            default String helper() { return ""; }
                            String toString();
                            void run();
                            @WithDefault("DEBUG_ALL") Optional<@WithConverter(Conv.class) DemoLevel> typeUseLevel();
                            @WithConverter(Conv.class) @WithDefault("DEBUG_ALL") DemoLevel convertedLevel();
                            @Deprecated(since = "3.22") GenerationConfig generation();
                            Optional<org.acme.http.ClientAuth> clientAuth();
                            Optional<org.acme.missing.Gone> gone();
                            @Override Optional<String> overridden();
                            static String constant() { return "x"; }
                        }""")
                .add(RUNTIME + "CategoryConfig", runtime
                        + "public interface CategoryConfig { @WithDefault(\"inherit\") String level(); }")
                .add(RUNTIME + "DataSourceConfig", runtime
                        + "public interface DataSourceConfig { Optional<String> dbKind();"
                        + " @WithDefault(\"20\") int maxSize(); }")
                .add(RUNTIME + "ThemeConfig", runtime + "@Deprecated public interface ThemeConfig {"
                        + " @WithDefault(\"blue\") String color(); Optional<Boolean> dark(); }")
                .add(RUNTIME + "ProxyConfig", runtime + "public interface ProxyConfig { String host(); }")
                .add(RUNTIME + "GenerationConfig", runtime + """
                        public interface GenerationConfig {
                            @WithParentName Optional<String> generation();
                            @Deprecated(since = "3.23") boolean createSchemas();
                            boolean haltOnError();
                        }""")
                .add(RUNTIME + "DemoLevel", runtime + "public enum DemoLevel { DEBUG_ALL, ClusterIP, TLSv1_2 }")
                .add(RUNTIME + "Mode", runtime + "public enum Mode { READ_ONLY, READ_WRITE }")
                .add(RUNTIME + "ValidationMode", runtime + "public enum ValidationMode { AUTO, CALLBACK, DDL, NONE }")
                .add(RUNTIME + "Transport", runtime + "public enum Transport { USB, NFC, BLE, INTERNAL }")
                .add(RUNTIME + "CoseAlgorithm", runtime + "public enum CoseAlgorithm { ES256, RS256, EdDSA }")
                .add(RUNTIME + "Compat", runtime + "public enum Compat { @ConfigDocEnumValue(\"5.6\") V5_6,"
                        + " @ConfigDocEnumValue(\"latest\") LATEST, NEXT }")
                .add(RUNTIME + "Conv", runtime + "public class Conv { }")
                .add(RUNTIME + "XConfig", runtime + """
                        @ConfigMapping(prefix = "quarkus.x")
                        @ConfigRoot(phase = ConfigPhase.RUN_TIME)
                        public interface XConfig {
                            @WithParentName @WithUnnamedKey @ConfigDocMapKey("a") Map<String, G1> named();
                            interface G1 { @WithUnnamedKey @ConfigDocMapKey("b") Map<String, G2> m(); }
                            interface G2 { int v(); }
                        }""")
                .add("io.quarkus.shared.SharedConfig", "package io.quarkus.shared;\n" + imports
                        + "@ConfigMapping(prefix = \"quarkus.shared\") @ConfigRoot\n"
                        + "public interface SharedConfig { @WithDefault(\"on\") String mode(); }")
                .add("io.quarkus.demo.deployment.DemoBuildTimeConfig", "package io.quarkus.demo.deployment;\n"
                        + imports + "@ConfigMapping(prefix = \"quarkus.demo\") @ConfigRoot\n"
                        + "public interface DemoBuildTimeConfig { @WithDefault(\"false\") boolean metricsEnabled(); }")
                .add("org.acme.http.ClientAuth", "package org.acme.http;\n"
                        + "public enum ClientAuth { NONE, REQUEST, REQUIRED }")
                .add("org.acme.missing.Gone", "package org.acme.missing;\npublic enum Gone { A, B }")
                .add("io.quarkus.legacy.BannerRuntimeConfig", "package io.quarkus.legacy;\n" + imports + """
                        @ConfigRoot(phase = ConfigPhase.RUN_TIME)
                        public class BannerRuntimeConfig {
                            @ConfigItem(defaultValue = "true") public boolean enabled;
                            @ConfigItem public Optional<String> path;
                            @ConfigItem public float growthResistance;
                            public boolean plainFlag;
                            @ConfigItem public char separator;
                            @ConfigItem public long count;
                            @ConfigItem public double ratio;
                            @ConfigItem(generateDocumentation = false) public String hidden;
                            public static String CONSTANT = "x";
                            @Deprecated private final Map<String, UnitConfig> cache = new HashMap<>();
                            @ConfigDocIgnore public String ignoredField;
                            @ConfigItem @ConfigDocIgnore public Optional<String> keptField;
                            @ConfigItem(defaultValue = "5", defaultValueDocumentation = "five") public int docDefault;
                            @ConfigItem @ConvertWith(Object.class) public Optional<String> converted;
                            @ConfigItem(name = "<<parent>>") public UnitConfig defaultUnit;
                            @ConfigItem(name = "<<parent>>") public Map<String, UnitConfig> namedUnits;
                            @ConfigItem(name = "telemetry.enabled") public boolean telemetryEnabled;
                            @ConfigItem(defaultValue = "8h") public Duration interval;
                            @ConfigItem public Inner inner;
                            @ConfigItem public Optional<List<String>> tags;
                            @ConfigItem public ProxyLike proxyLike;
                            @ConfigGroup public class Inner {
                                @ConfigItem(defaultValue = "1") public int depth;
                                public boolean outerEnabled() { return BannerRuntimeConfig.this.enabled; }
                            }
                        }""")
                .add("io.quarkus.legacy.ProxyLike", "package io.quarkus.legacy;\n" + imports
                        + "@ConfigGroup public interface ProxyLike { String host(); }")
                .add("io.quarkus.legacy.UnitConfig", "package io.quarkus.legacy;\n" + imports + """
                        @ConfigGroup
                        public class UnitConfig {
                            @ConfigItem public Optional<String> datasource;
                            @ConfigItem(defaultValue = "x") @Deprecated public String old;
                        }""")
                .add("io.quarkus.legacy.KubernetesConfigSourceConfig", "package io.quarkus.legacy;\n" + imports
                        + "@ConfigRoot(name = \"kubernetes-config\", phase = ConfigPhase.BOOTSTRAP)\n"
                        + "public class KubernetesConfigSourceConfig { @ConfigItem public boolean enabled; }")
                .add("io.quarkus.legacy.ConfigBuildTimeConfig", "package io.quarkus.legacy;\n" + imports
                        + "@ConfigRoot(name = \"<<parent>>\")\npublic class ConfigBuildTimeConfig {"
                        + " @ConfigItem(name = \"config.profile.parent\") public Optional<String> profileParent; }")
                .add("io.quarkus.legacy.ApplicationArchiveBuildStep", "package io.quarkus.legacy;\n" + imports
                        + "public class ApplicationArchiveBuildStep {\n"
                        + "  @ConfigRoot public static class IndexDependencyConfiguration {"
                        + " @ConfigItem public List<String> groupIds; }\n}")
                .add("io.quarkus.docs.DocsConfig", "package io.quarkus.docs;\n" + imports + """
                        @ConfigMapping(prefix = "quarkus.docs")
                        @ConfigRoot
                        public interface DocsConfig {
                            Optional<String> json();
                            Optional<String> asciidoc();
                            Optional<String> props();
                            io.quarkus.docs.common.SharedGroup shared();
                        }""")
                .add("io.quarkus.docs.common.SharedGroup", "package io.quarkus.docs.common;\n" + imports
                        + "public interface SharedGroup { Optional<String> value(); }")
                .compile();
    }

    private static List<String> runtimeClasses() {
        return List.of("io/quarkus/demo/runtime/DemoBase", "io/quarkus/demo/runtime/DemoConfig",
                "io/quarkus/demo/runtime/CategoryConfig", "io/quarkus/demo/runtime/DataSourceConfig",
                "io/quarkus/demo/runtime/ThemeConfig", "io/quarkus/demo/runtime/ProxyConfig",
                "io/quarkus/demo/runtime/GenerationConfig", "io/quarkus/demo/runtime/DemoLevel",
                "io/quarkus/demo/runtime/Mode", "io/quarkus/demo/runtime/ValidationMode",
                "io/quarkus/demo/runtime/Transport", "io/quarkus/demo/runtime/CoseAlgorithm",
                "io/quarkus/demo/runtime/Compat", "io/quarkus/demo/runtime/Conv",
                "io/quarkus/demo/runtime/XConfig", "io/quarkus/demo/runtime/XConfig$G1",
                "io/quarkus/demo/runtime/XConfig$G2", "io/quarkus/shared/SharedConfig");
    }

    /// The fixture release: six io.quarkus jars and one third-party jar.
    private static VersionJars release(String version) {
        TestJar demo = TestJar.of("io.quarkus", "quarkus-demo", version)
                .withClasses(classes, runtimeClasses())
                .withRoots("io.quarkus.demo.runtime.DemoConfig", "io.quarkus.demo.runtime.XConfig",
                        "io.quarkus.shared.SharedConfig")
                .with(Javadocs.ENTRY, """
                        io.quarkus.demo.runtime.DemoBase.name=The name.
                        io.quarkus.demo.runtime.DemoBase.overridden=Base text.
                        io.quarkus.demo.runtime.DemoConfig.overridden=Overridden here. More.
                        io.quarkus.demo.runtime.DemoConfig.enabled=Whether {@code demo} is enabled.\\n@deprecated no
                        io.quarkus.demo.runtime.CategoryConfig.level=The <em>level</em>.
                        io.quarkus.demo.runtime.XConfig.G2.v=The value.
                        """);
        TestJar deployment = TestJar.of("io.quarkus", "quarkus-demo-deployment", version)
                .withClasses(classes, List.of("io/quarkus/demo/deployment/DemoBuildTimeConfig"))
                .withRoots("io.quarkus.demo.deployment.DemoBuildTimeConfig");
        TestJar other = TestJar.of("io.quarkus", "quarkus-other", version)
                .withRoots("io.quarkus.shared.SharedConfig");
        TestJar legacy = TestJar.of("io.quarkus", "quarkus-legacy", version)
                .withClasses(classes, List.of("io/quarkus/legacy/BannerRuntimeConfig",
                        "io/quarkus/legacy/BannerRuntimeConfig$Inner", "io/quarkus/legacy/UnitConfig",
                        "io/quarkus/legacy/ProxyLike", "io/quarkus/legacy/KubernetesConfigSourceConfig",
                        "io/quarkus/legacy/ConfigBuildTimeConfig", "io/quarkus/legacy/ApplicationArchiveBuildStep",
                        "io/quarkus/legacy/ApplicationArchiveBuildStep$IndexDependencyConfiguration"))
                .withRoots("io.quarkus.legacy.BannerRuntimeConfig", "io.quarkus.legacy.KubernetesConfigSourceConfig",
                        "io.quarkus.legacy.ConfigBuildTimeConfig",
                        "io.quarkus.legacy.ApplicationArchiveBuildStep$IndexDependencyConfiguration");
        // Javadoc as 3.25+ ship it: the JSON wins over the properties file; groups are documented by their own jar
        TestJar docs = TestJar.of("io.quarkus", "quarkus-docs", version)
                .withClasses(classes, List.of("io/quarkus/docs/DocsConfig"))
                .withRoots("io.quarkus.docs.DocsConfig")
                .with("META-INF/quarkus-config-doc/quarkus-config-javadoc.json", """
                        {"elements": {
                          "io.quarkus.docs.DocsConfig.json": {"description": "From {@code JSON}. Second.",
                                                              "format": "JAVADOC"},
                          "io.quarkus.docs.DocsConfig.asciidoc": {"description": "Kept `as is` and <b>raw</b>. More.",
                                                                  "format": "ASCIIDOC"}}}""")
                .with(Javadocs.ENTRY, """
                        io.quarkus.docs.DocsConfig.json=From the properties file.
                        io.quarkus.docs.DocsConfig.props=Only in the properties file.
                        """);
        TestJar docsCommon = TestJar.of("io.quarkus", "quarkus-docs-common", version)
                .withClasses(classes, List.of("io/quarkus/docs/common/SharedGroup"))
                .with(Javadocs.ENTRY, "io.quarkus.docs.common.SharedGroup.value=Documented by another jar.\n");
        TestJar acme = TestJar.of("org.acme", "acme-http", "1.0")
                .withClasses(classes, List.of("org/acme/http/ClientAuth"));
        return new VersionJars(version, List.of(demo, deployment, other, legacy, docs, docsCommon), List.of(acme),
                jar -> Optional.empty(), 2);
    }

    private static ConfigSnapshot read(String version) throws IOException {
        return new BytecodeConfigReader().read(release(version));
    }

    private static ConfigProperty property(ConfigSnapshot snapshot, String name) {
        return snapshot.properties().stream().filter(p -> p.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no property " + name + " in "
                        + snapshot.properties().stream().map(ConfigProperty::name).toList()));
    }

    private static boolean has(ConfigSnapshot snapshot, String name) {
        return snapshot.properties().stream().anyMatch(p -> p.name().equals(name));
    }

    @Test
    void describesTheSnapshot() throws IOException {
        ConfigSnapshot snapshot = read("3.20.0");
        assertEquals("3.20.0", snapshot.version());
        assertEquals("bytecode", snapshot.source());
        assertEquals("bytecode/2", new BytecodeConfigReader().id());
        assertEquals(snapshot.extractor(), new BytecodeConfigReader().id());
        assertEquals(9, snapshot.roots());
        List<String> keys = snapshot.properties().stream().map(ConfigProperty::key).toList();
        assertEquals(keys.stream().sorted().distinct().toList(), keys);
    }

    @Test
    void readsMappingNamesAndPrefixes() throws IOException {
        ConfigSnapshot snapshot = read("3.20.0");
        ConfigProperty enabled = property(snapshot, "quarkus.demo.enabled");
        assertEquals("quarkus-demo", enabled.ext());
        assertEquals("RUN_TIME", enabled.phase());
        assertEquals("io.quarkus.demo.runtime.DemoConfig#enabled", enabled.source());
        assertTrue(has(snapshot, "quarkus.demo.connection-ttl"));
        assertTrue(has(snapshot, "quarkus.demo.max-http2-streams"));
        assertTrue(has(snapshot, "quarkus.demo.request-scoped.enabled"));
        // deployment jars belong to their runtime extension; the default phase is BUILD_TIME
        ConfigProperty metrics = property(snapshot, "quarkus.demo.metrics-enabled");
        assertEquals("quarkus-demo", metrics.ext());
        assertEquals("BUILD_TIME", metrics.phase());
    }

    @Test
    void scansSuperInterfacesFirstAndKeepsTheDeclaringType() throws IOException {
        ConfigSnapshot snapshot = read("3.20.0");
        ConfigProperty name = property(snapshot, "quarkus.demo.name");
        assertEquals("io.quarkus.demo.runtime.DemoBase#name", name.source());
        assertEquals("The name.", name.desc());
        ConfigProperty overridden = property(snapshot, "quarkus.demo.overridden");
        assertEquals("io.quarkus.demo.runtime.DemoConfig#overridden", overridden.source());
        assertEquals("Overridden here.", overridden.desc());
        // Javadoc: block tags stripped, inline tags and HTML converted
        assertEquals("Whether `demo` is enabled.", property(snapshot, "quarkus.demo.enabled").desc());
        assertEquals("The level.", property(snapshot, "quarkus.demo.category.\"categories\".level").desc());
        assertNull(property(snapshot, "quarkus.demo.timeout").desc());
    }

    @Test
    void looksUpJavadocReleaseWide() throws IOException {
        ConfigSnapshot snapshot = read("3.25.0");
        // quarkus-config-javadoc.json wins over the properties file; its format says how to convert the text
        assertEquals("From `JSON`.", property(snapshot, "quarkus.docs.json").desc());
        assertEquals("Kept `as is` and <b>raw</b>.", property(snapshot, "quarkus.docs.asciidoc").desc());
        assertEquals("Only in the properties file.", property(snapshot, "quarkus.docs.props").desc());
        // a group declared and documented in another jar
        ConfigProperty shared = property(snapshot, "quarkus.docs.shared.value");
        assertEquals("io.quarkus.docs.common.SharedGroup#value", shared.source());
        assertEquals("quarkus-docs", shared.ext());
        assertEquals("Documented by another jar.", shared.desc());
    }

    @Test
    void skipsIgnoredMembers() throws IOException {
        ConfigSnapshot snapshot = read("3.20.0");
        for (String ignored : List.of("quarkus.demo.hidden", "quarkus.demo.helper", "quarkus.demo.to-string",
                "quarkus.demo.run", "quarkus.demo.constant", "quarkus.banner.hidden", "quarkus.banner.constant",
                "quarkus.banner.ignored-field", "quarkus.banner.inner.this$0", "quarkus.banner.inner.outer-enabled")) {
            assertFalse(has(snapshot, ignored), ignored);
        }
        // the inner group class really has the synthetic outer-instance field the processor never sees
        ClassFile inner = ClassFile.parse(classes.get("io/quarkus/legacy/BannerRuntimeConfig$Inner"));
        assertTrue(inner.node().fields.stream().anyMatch(f -> (f.access & Opcodes.ACC_SYNTHETIC) != 0));
        assertEquals("io.quarkus.legacy.BannerRuntimeConfig.Inner", inner.canonicalName());
        // @ConfigDocIgnore does not count on a field with @ConfigItem; fields without annotations are properties
        assertTrue(has(snapshot, "quarkus.banner.kept-field"));
        assertEquals("false", property(snapshot, "quarkus.banner.plain-flag").defaultValue());
        // a final field is no config item (RestClientsConfig#configs before 3.14): neither it nor its group's members
        assertTrue(snapshot.properties().stream().noneMatch(p -> p.key().startsWith("quarkus.banner.cache.")),
                snapshot.properties().stream().map(ConfigProperty::key).filter(k -> k.contains("cache")).toList()
                        .toString());
        assertTrue(has(snapshot, "quarkus.banner.\"named-units\".datasource"));
    }

    @Test
    void appliesTheDefaultRules() throws IOException {
        ConfigSnapshot snapshot = read("3.20.0");
        // Appendix B normalizeDuration: only @WithDefault / @ConfigItem(defaultValue) of a Duration
        assertEquals("10S", property(snapshot, "quarkus.demo.timeout").defaultValue());
        assertEquals("60S", property(snapshot, "quarkus.demo.delay").defaultValue());
        assertEquals("PT1M", property(snapshot, "quarkus.demo.iso").defaultValue());
        assertEquals("8H", property(snapshot, "quarkus.banner.interval").defaultValue());
        assertEquals("Duration", property(snapshot, "quarkus.demo.timeout").type());
        assertEquals("10s", property(snapshot, "quarkus.demo.doc-timeout").defaultValue());
        assertNull(property(snapshot, "quarkus.demo.connection-ttl").defaultValue());
        assertTrue(property(snapshot, "quarkus.demo.connection-ttl").optional());
        // implicit primitive defaults ("" of a char is no default); doc defaults verbatim; "" means no default
        assertEquals("0", property(snapshot, "quarkus.demo.max-http2-streams").defaultValue());
        assertEquals("0f", property(snapshot, "quarkus.banner.growth-resistance").defaultValue());
        assertEquals("float", property(snapshot, "quarkus.banner.growth-resistance").type());
        assertEquals("0l", property(snapshot, "quarkus.banner.count").defaultValue());
        assertEquals("0d", property(snapshot, "quarkus.banner.ratio").defaultValue());
        assertNull(property(snapshot, "quarkus.banner.separator").defaultValue());
        assertEquals("char", property(snapshot, "quarkus.banner.separator").type());
        assertEquals("depends on the dialect", property(snapshot, "quarkus.demo.pool-size").defaultValue());
        assertEquals("five", property(snapshot, "quarkus.banner.doc-default").defaultValue());
        assertNull(property(snapshot, "quarkus.demo.empty-default").defaultValue());
        assertEquals("true", property(snapshot, "quarkus.banner.enabled").defaultValue());
    }

    @Test
    void readsEnumValuesAndHyphenatesEnumDefaults() throws IOException {
        ConfigSnapshot snapshot = read("3.20.0");
        ConfigProperty level = property(snapshot, "quarkus.demo.level");
        assertEquals("DemoLevel", level.type());
        assertEquals(List.of("debug-all", "cluster-ip", "tl-sv1-2"), level.enumValues());
        assertEquals("debug-all", level.defaultValue());
        ConfigProperty modes = property(snapshot, "quarkus.demo.modes");
        assertTrue(modes.list());
        assertEquals("Mode", modes.type());
        assertEquals("read-only,read-write", modes.defaultValue());
        // Appendix B enum defaults: a Set, and a comma list split, trimmed, hyphenated and joined without spaces
        ConfigProperty validation = property(snapshot, "quarkus.demo.validation-modes");
        assertTrue(validation.list());
        assertEquals("auto", validation.defaultValue());
        assertEquals(List.of("auto", "callback", "ddl", "none"), validation.enumValues());
        assertEquals("usb,nfc", property(snapshot, "quarkus.demo.transports").defaultValue());
        // a doc default is verbatim, also on an enum list
        ConfigProperty algorithms = property(snapshot, "quarkus.demo.algorithms");
        assertEquals("ES256,RS256", algorithms.defaultValue());
        assertEquals(List.of("es256", "rs256", "ed-dsa"), algorithms.enumValues());
        // a converted enum keeps constant names and its default; explicit values win
        ConfigProperty compat = property(snapshot, "quarkus.demo.compat");
        assertEquals(List.of("5.6", "latest", "NEXT"), compat.enumValues());
        assertEquals("V5_6", compat.defaultValue());
        // JDK enum from the running JDK, third-party enum from the BOM's jars
        ConfigProperty unit = property(snapshot, "quarkus.demo.unit");
        assertEquals("seconds", unit.defaultValue());
        assertTrue(unit.enumValues().containsAll(List.of("nanos", "seconds", "forever")));
        ConfigProperty clientAuth = property(snapshot, "quarkus.demo.client-auth");
        assertEquals(List.of("none", "request", "required"), clientAuth.enumValues());
        assertTrue(clientAuth.optional());
    }

    @Test
    void readsUnresolvableTypesAsClassesWithOneWarning() throws IOException {
        ConfigSnapshot snapshot = read("3.20.0");
        ConfigProperty gone = property(snapshot, "quarkus.demo.gone");
        assertEquals("Gone", gone.type());
        assertNull(gone.enumValues());
        List<String> warnings = snapshot.warnings().stream().filter(w -> w.contains("org.acme.missing.Gone")).toList();
        assertEquals(1, warnings.size(), snapshot.warnings().toString());
        assertTrue(warnings.getFirst().contains("io.quarkus.demo.runtime.DemoConfig#gone"), warnings.getFirst());
    }

    @Test
    void resolvesMapKeysAndUnnamedKeys() throws IOException {
        ConfigSnapshot snapshot = read("3.20.0");
        // Appendix B map key fallback: the key segment is named after the member, the path segment after @WithName
        ConfigProperty category = property(snapshot, "quarkus.demo.category.\"categories\".level");
        assertEquals("quarkus.demo.category.*.level", category.key());
        assertEquals("inherit", category.defaultValue());
        // @WithParentName @WithUnnamedKey: the unkeyed path is primary, the keyed one an alias
        ConfigProperty dbKind = property(snapshot, "quarkus.demo.db-kind");
        assertEquals(List.of("quarkus.demo.\"datasource-name\".db-kind"), dbKind.aliases());
        // leaf maps: one key segment, always optional, generic type description
        ConfigProperty labels = property(snapshot, "quarkus.demo.labels.\"label-name\"");
        assertTrue(labels.map());
        assertTrue(labels.optional());
        assertEquals("Map<String,String>", labels.type());
        ConfigProperty complex = property(snapshot, "quarkus.demo.complex-config.\"complex-config\"");
        assertEquals("Map<String,Map<String,List<String>>>", complex.type());
        assertFalse(complex.list());
        // a map of maps of groups adds the outer key only
        assertTrue(has(snapshot, "quarkus.demo.nested.\"nested\".level"));
        // Appendix B unnamed keys: nested unnamed keys multiply
        ConfigProperty v = property(snapshot, "quarkus.x.m.v");
        assertEquals(List.of("quarkus.x.m.\"b\".v", "quarkus.x.\"a\".m.v", "quarkus.x.\"a\".m.\"b\".v"), v.aliases());
        assertEquals("0", v.defaultValue());
        assertEquals("The value.", v.desc());
        assertFalse(v.map());
    }

    @Test
    void expandsOptionalAndListGroupsInPlace() throws IOException {
        ConfigSnapshot snapshot = read("3.20.0");
        ConfigProperty color = property(snapshot, "quarkus.demo.theme.color");
        assertFalse(color.optional());
        assertEquals("blue", color.defaultValue());
        assertTrue(property(snapshot, "quarkus.demo.theme.dark").optional());
        // List, Set and arrays of groups: no index, and the children are not lists
        for (String name : List.of("quarkus.demo.trusted-proxy.host", "quarkus.demo.proxy-set.host",
                "quarkus.demo.proxy-array.host")) {
            ConfigProperty host = property(snapshot, name);
            assertFalse(host.list(), name);
            assertEquals("string", host.type(), name);
        }
    }

    @Test
    void propagatesMemberDeprecationOnly() throws IOException {
        ConfigSnapshot snapshot = read("3.20.0");
        assertEquals("3.22", property(snapshot, "quarkus.demo.generation").deprecated());
        assertEquals("3.23", property(snapshot, "quarkus.demo.generation.create-schemas").deprecated());
        assertEquals("3.22", property(snapshot, "quarkus.demo.generation.halt-on-error").deprecated());
        // class-level @Deprecated on a group has no effect
        assertNull(property(snapshot, "quarkus.demo.theme.color").deprecated());
        assertEquals("", property(snapshot, "quarkus.banner.old").deprecated());
        assertEquals("", property(snapshot, "quarkus.banner.\"named-units\".old").deprecated());
        assertNull(property(snapshot, "quarkus.banner.datasource").deprecated());
    }

    @Test
    void countsOnlyDeclarationConverters() throws IOException {
        ConfigSnapshot snapshot = read("3.20.0");
        // Appendix B: a @WithConverter inside a type argument does not make the property converted
        ConfigProperty typeUse = property(snapshot, "quarkus.demo.type-use-level");
        assertEquals(List.of("debug-all", "cluster-ip", "tl-sv1-2"), typeUse.enumValues());
        assertEquals("debug-all", typeUse.defaultValue());
        ConfigProperty converted = property(snapshot, "quarkus.demo.converted-level");
        assertEquals(List.of("DEBUG_ALL", "ClusterIP", "TLSv1_2"), converted.enumValues());
        assertEquals("DEBUG_ALL", converted.defaultValue());
    }

    @Test
    void readsLegacyRoots() throws IOException {
        ConfigSnapshot snapshot = read("3.20.0");
        ConfigProperty path = property(snapshot, "quarkus.banner.path");
        assertEquals("quarkus-legacy", path.ext());
        assertEquals("RUN_TIME", path.phase());
        assertTrue(path.optional());
        assertEquals("io.quarkus.legacy.BannerRuntimeConfig#path", path.source());
        // <<parent>> fields: the default instance and the named map are two properties without aliases
        assertTrue(property(snapshot, "quarkus.banner.datasource").aliases().isEmpty());
        assertTrue(has(snapshot, "quarkus.banner.\"named-units\".datasource"));
        assertTrue(has(snapshot, "quarkus.banner.telemetry.enabled"));
        assertEquals("1", property(snapshot, "quarkus.banner.inner.depth").defaultValue());
        ConfigProperty tags = property(snapshot, "quarkus.banner.tags");
        assertTrue(tags.list());
        assertTrue(tags.optional());
        // a legacy field typed as an interface is a leaf, even with @ConfigGroup
        assertEquals("ProxyLike", property(snapshot, "quarkus.banner.proxy-like").type());
        assertFalse(has(snapshot, "quarkus.banner.proxy-like.host"));
        ConfigProperty bootstrap = property(snapshot, "quarkus.kubernetes-config.enabled");
        assertEquals("BOOTSTRAP", bootstrap.phase());
        assertEquals("false", bootstrap.defaultValue());
        assertEquals("BUILD_TIME", property(snapshot, "quarkus.config.profile.parent").phase());
        ConfigProperty groupIds = property(snapshot, "quarkus.index-dependency.group-ids");
        assertTrue(groupIds.list());
        assertEquals("string", groupIds.type());
    }

    @Test
    void keepsTheFirstJarOfARootListedTwice() throws IOException {
        ConfigSnapshot snapshot = read("3.20.0");
        ConfigProperty mode = property(snapshot, "quarkus.shared.mode");
        assertEquals("quarkus-demo", mode.ext());
        assertEquals(1, snapshot.warnings().stream().filter(w -> w.startsWith("duplicate key quarkus.shared.mode"))
                .count());
    }

    @Test
    void warnsAboutDocAnnotationsOnlyBefore314() throws IOException {
        assertTrue(read("3.20.0").warnings().stream().noneMatch(w -> w.startsWith("doc annotations")));
        ConfigSnapshot old = read("3.13.3");
        assertTrue(old.warnings().getFirst().startsWith("doc annotations unavailable: the class files"),
                old.warnings().toString());
        assertEquals(1, old.warnings().stream().filter(w -> w.startsWith("doc annotations")).count());
    }

    @Test
    void followsTheModelFormatOfLaterReleases() throws IOException {
        ConfigSnapshot snapshot = read("3.40.1");
        assertTrue(has(snapshot, "quarkus.demo.trusted-proxy[i].host"));
        assertTrue(has(snapshot, "quarkus.demo.proxy-set[i].host"));
        assertTrue(has(snapshot, "quarkus.demo.proxy-array[i].host"));
        assertFalse(has(snapshot, "quarkus.demo.trusted-proxy.host"));
        // the index goes on the primary path only, and keys keep the literal [i]
        assertEquals("quarkus.demo.trusted-proxy[i].host", property(snapshot, "quarkus.demo.trusted-proxy[i].host")
                .key());
        assertEquals(List.of("cluster-ip", "debug-all", "tl-sv1-2"),
                property(snapshot, "quarkus.demo.level").enumValues());
        assertEquals(List.of("latest", "NEXT", "5.6"), property(snapshot, "quarkus.demo.compat").enumValues());
    }
}
