package io.quarkiverse.almanac.config.sourcedoc;

import io.quarkiverse.almanac.config.InMemoryJar;
import io.quarkiverse.almanac.config.JarSource;
import io.quarkiverse.almanac.config.VersionJars;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.annotation.RetentionPolicy;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/// The Java sources of `src/test/resources/sourcedoc` (`<TypeName>.java.txt`, so that no tool compiles
/// them by accident), stand-ins for the Quarkus annotations, and releases built from them in memory.
final class Fixtures {

    /// The artifact the fixture classes and their sources belong to.
    static final String ARTIFACT = "quarkus-acme";
    static final String VERSION = "1.0";

    private static final Pattern PACKAGE = Pattern.compile("^package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);

    private Fixtures() {
    }

    /// A release of two jars: {@value #ARTIFACT}, with the compiled fixtures and a config roots list, and
    /// `quarkus-core`, with the annotation classes.
    ///
    /// @param sources the {@value #ARTIFACT} sources jar
    /// @param classes every compiled class by internal name
    record Release(VersionJars jars, InMemoryJar sources, Map<String, byte[]> classes) {

        /// Reads the sources jar above; `quarkus-core` has none.
        SourceDocAnnotations annotationSource() {
            return annotationSource(sources);
        }

        /// Reads the given jar as the {@value #ARTIFACT} sources jar; `quarkus-core` has none.
        SourceDocAnnotations annotationSource(JarSource sourcesJar) {
            return new SourceDocAnnotations(jars.quarkusVersion(), SourceDocAnnotations.locator(jars,
                    binary -> binary.artifactId().equals(ARTIFACT)
                            ? sourcesJar
                            : InMemoryJar.missing(binary.artifactId(), binary.version())));
        }
    }

    /// Compiles fixtures with javac and packs them as a release.
    ///
    /// @param version      the Quarkus version
    /// @param docRetention retention of the stand-in doc annotations: RUNTIME as from 3.14, SOURCE before
    /// @param roots        binary names for {@value ConfigMembers#ROOTS_ENTRY}
    /// @param fixtures     fixture names, e.g. `LiteralForms`
    static Release release(String version, RetentionPolicy docRetention, List<String> roots, String... fixtures) {
        Map<String, String> sources = sources(fixtures);
        Map<String, String> all = new LinkedHashMap<>(annotations(docRetention));
        all.putAll(sources);
        Map<String, byte[]> classes = TestCompiler.compile(all);
        InMemoryJar binary = InMemoryJar.of(ARTIFACT, VERSION)
                .with(ConfigMembers.ROOTS_ENTRY, String.join("\n", roots) + "\n");
        InMemoryJar core = InMemoryJar.of("quarkus-core", VERSION);
        classes.forEach((name, bytes) -> {
            boolean annotation = name.startsWith("io/quarkus/") || name.startsWith("com/other/");
            (annotation ? core : binary).with(name + ".class", bytes);
        });
        InMemoryJar sourcesJar = InMemoryJar.of(ARTIFACT, VERSION);
        sources.forEach(sourcesJar::with);
        VersionJars jars = new VersionJars(version, List.of(binary, core), jar -> Optional.empty(), 4);
        return new Release(jars, sourcesJar, classes);
    }

    /// The content of the fixture `sourcedoc/<name>.java.txt`.
    static String text(String name) {
        try (InputStream in = Fixtures.class.getResourceAsStream("/sourcedoc/" + name + ".java.txt")) {
            assertNotNull(in, "no fixture " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /// Fixtures by their path in a sources jar, e.g. `com/acme/LiteralForms.java`.
    static Map<String, String> sources(String... names) {
        Map<String, String> sources = new LinkedHashMap<>();
        for (String name : names) {
            String text = text(name);
            Matcher matcher = PACKAGE.matcher(text);
            String packagePath = matcher.find() ? matcher.group(1).replace('.', '/') + "/" : "";
            sources.put(packagePath + name + ".java", text);
        }
        return sources;
    }

    /// The parsed index of a fixture.
    static SourceFileIndex index(String name) throws SourceFileIndex.UnparsableSourceException {
        return SourceFileIndex.parse(name + ".java", text(name).getBytes(StandardCharsets.UTF_8));
    }

    /// Stand-ins for the annotations of `io.quarkus.runtime.annotations`: the four doc annotations with the given
    /// retention, `ConfigGroup` with RUNTIME retention as in every release; and
    /// `com.other.ConfigDocDefault`, a namesake from another package.
    static Map<String, String> annotations(RetentionPolicy docRetention) {
        Map<String, String> stubs = new LinkedHashMap<>();
        stub(stubs, SourceFileIndex.ANNOTATIONS_PACKAGE, SourceFileIndex.DOC_DEFAULT, docRetention, "String value();");
        stub(stubs, SourceFileIndex.ANNOTATIONS_PACKAGE, SourceFileIndex.DOC_MAP_KEY, docRetention, "String value();");
        stub(stubs, SourceFileIndex.ANNOTATIONS_PACKAGE, SourceFileIndex.DOC_IGNORE, docRetention, "");
        stub(stubs, SourceFileIndex.ANNOTATIONS_PACKAGE, SourceFileIndex.DOC_SECTION, docRetention,
                "boolean generated() default false;");
        stub(stubs, SourceFileIndex.ANNOTATIONS_PACKAGE, "ConfigGroup", RetentionPolicy.RUNTIME, "");
        stub(stubs, "com.other", SourceFileIndex.DOC_DEFAULT, RetentionPolicy.RUNTIME, "String value();");
        return stubs;
    }

    private static void stub(Map<String, String> stubs, String packageName, String name, RetentionPolicy retention,
            String body) {
        stubs.put(packageName.replace('.', '/') + "/" + name + ".java", "package " + packageName + ";\n\n"
                + "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy." + retention + ")\n"
                + "public @interface " + name + " {\n    " + body + "\n}\n");
    }
}
