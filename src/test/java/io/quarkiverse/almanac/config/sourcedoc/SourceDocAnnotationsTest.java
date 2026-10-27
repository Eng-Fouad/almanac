package io.quarkiverse.almanac.config.sourcedoc;

import io.quarkiverse.almanac.config.InMemoryJar;
import io.quarkiverse.almanac.config.JarSource;
import io.quarkiverse.almanac.config.VersionJars;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceDocAnnotationsTest {

    private static final String RELEASE = "3.8.6.1";
    private static final DocAnnotations NONE = DocAnnotations.NONE;

    /// The binary jar: only entry names matter, plus the SourceFile attributes of two real class files.
    private final InMemoryJar binary = InMemoryJar.of(Fixtures.ARTIFACT, Fixtures.VERSION)
            .with("com/acme/LiteralForms.class", new byte[0])
            .with("com/acme/LiteralForms$Nested.class", new byte[0])
            .with("com/acme/LiteralForms$Nested$Deeper.class", new byte[0])
            .with("com/acme/LiteralForms$1.class", new byte[0])
            .with("com/acme/legacy/LegacyConfig.class", new byte[0])
            .with("com/acme/legacy/LegacyConfig$Group.class", new byte[0])
            .with("com/acme/legacy/SecondaryGroup.class", classFile("com/acme/legacy/SecondaryGroup",
                    "LegacyConfig.java"))
            .with("com/acme/Nowhere.class", classFile("com/acme/Nowhere", "Nowhere.java"))
            .with("com/acme/NoAttribute.class", new byte[0])
            .with("com/acme/Broken.class", new byte[0]);
    private final InMemoryJar sources = InMemoryJar.of(Fixtures.ARTIFACT, Fixtures.VERSION)
            .with("com/acme/LiteralForms.java", Fixtures.text("LiteralForms"))
            .with("com/acme/legacy/LegacyConfig.java", Fixtures.text("LegacyConfig"))
            .with("com/acme/Broken.java", Fixtures.text("Broken"));
    /// A jar whose sources jar the repository does not have.
    private final InMemoryJar withoutSources = InMemoryJar.of("quarkus-lonely", "1.0")
            .with("com/acme/lonely/Lonely.class", new byte[0])
            .with("com/acme/lonely/Alone.class", new byte[0]);
    private final VersionJars jars = new VersionJars(RELEASE, List.of(binary, withoutSources),
            jar -> Optional.empty(), 2);
    private final SourceDocAnnotations source = new SourceDocAnnotations(RELEASE, SourceDocAnnotations.locator(jars,
            jar -> jar.artifactId().equals(Fixtures.ARTIFACT) ? sources : InMemoryJar.missing(jar.artifactId(),
                    jar.version())));

    @Test
    void readsMembersOfNestedTypes() throws IOException {
        assertEquals(new DocAnnotations("suf", null, false, false), source.get("com/acme/LiteralForms$Nested",
                "inner"));
        assertEquals(new DocAnnotations(null, "sufpre", false, false),
                source.get("com/acme/LiteralForms$Nested$Deeper", "deep"));
        assertEquals(new DocAnnotations("several", "key", false, true), source.get("com/acme/LiteralForms",
                "several"));
        assertEquals(new DocAnnotations(null, null, true, false), source.get("com/acme/LiteralForms", "ignored"));
        assertEquals(NONE, source.get("com/acme/LiteralForms", "unannotated"));

        SourceDocAnnotations.Stats stats = source.stats();
        assertEquals(5, stats.lookups());
        assertEquals(4, stats.annotatedLookups());
        assertEquals(3, stats.ownersResolved());
        assertEquals(1, stats.filesParsed());
        assertEquals(1, stats.sourcesJarsFound());
        assertEquals(new SourceDocAnnotations.AnnotationCounts(2, 2, 1, 1), stats.lookedUp());
        assertEquals(new SourceDocAnnotations.AnnotationCounts(18, 4, 1, 2), stats.inParsedFiles());
        assertEquals(List.of(), source.warnings());
    }

    @Test
    void readsFieldsOfClassesAndMethodsOfInterfaces() throws IOException {
        // LegacyConfig has an unannotated field "both" and an annotated method both(): a class's member is its field
        assertEquals(NONE, source.get("com/acme/legacy/LegacyConfig", "both"));
        assertEquals(Optional.of(new DocAnnotations("method", null, false, false)),
                source.declared("com/acme/legacy/LegacyConfig", "both", true));
        assertEquals(Optional.of(NONE), source.declared("com/acme/legacy/LegacyConfig", "both", false));
        assertEquals(Optional.empty(), source.declared("com/acme/legacy/LegacyConfig", "image", true));
        assertEquals(new DocAnnotations("image:1.0", null, false, false),
                source.get("com/acme/legacy/LegacyConfig", "image"));
        assertEquals(new DocAnnotations("in group", null, false, false),
                source.get("com/acme/legacy/LegacyConfig$Group", "value"));
    }

    @Test
    void findsASecondTopLevelClassThroughItsSourceFileAttribute() throws IOException {
        assertEquals(new DocAnnotations(null, "secondary-key", false, false),
                source.get("com/acme/legacy/SecondaryGroup", "entries"));
        SourceDocAnnotations.Stats stats = source.stats();
        assertEquals(1, stats.ownersResolved());
        assertEquals(1, stats.ownersInOtherFiles());
        assertEquals(0, stats.filesMissing());
        assertEquals(1, stats.filesParsed());
        assertEquals(List.of(), source.warnings());
    }

    @Test
    void reportsMissingOwnersFilesTypesAndMembersOncePerItem() throws IOException {
        for (int i = 0; i < 3; i++) {
            assertEquals(NONE, source.get("com/acme/Unknown", "value"));
            assertEquals(NONE, source.get("com/acme/Nowhere", "value"));
            assertEquals(NONE, source.get("com/acme/NoAttribute", "value"));
            assertEquals(NONE, source.get("com/acme/LiteralForms$1", "value"));
            assertEquals(NONE, source.get("com/acme/LiteralForms", "gone"));
        }
        SourceDocAnnotations.Stats stats = source.stats();
        assertEquals(15, stats.lookups());
        assertEquals(0, stats.annotatedLookups());
        assertEquals(1, stats.ownersWithoutJar());
        assertEquals(2, stats.filesMissing());
        assertEquals(1, stats.typesMissing());
        assertEquals(1, stats.membersMissing());
        assertEquals(List.of(
                "no jar of the release contains com/acme/Unknown",
                "com/acme/Nowhere.java is not in the sources jar of io.quarkus:quarkus-acme:1.0",
                "com/acme/NoAttribute.java is not in the sources jar of io.quarkus:quarkus-acme:1.0",
                "the source of com/acme/LiteralForms$1 in io.quarkus:quarkus-acme:1.0 declares no type "
                        + "LiteralForms$1",
                "the source of com/acme/LiteralForms declares no field or method without parameters named gone"),
                source.warnings());
    }

    @Test
    void reportsAMissingSourcesJarOnce() throws IOException {
        assertEquals(NONE, source.get("com/acme/lonely/Lonely", "a"));
        assertEquals(NONE, source.get("com/acme/lonely/Lonely", "b"));
        assertEquals(NONE, source.get("com/acme/lonely/Alone", "a"));
        SourceDocAnnotations.Stats stats = source.stats();
        assertEquals(1, stats.sourcesJarsMissing());
        assertEquals(0, stats.sourcesJarsFound());
        assertEquals(0, stats.filesMissing());
        assertEquals(List.of("no sources jar for io.quarkus:quarkus-lonely:1.0; the @ConfigDoc* annotations of its "
                + "classes are unknown"), source.warnings());
    }

    @Test
    void reportsAnUnparsableFileOnce() throws IOException {
        assertEquals(NONE, source.get("com/acme/Broken", "value"));
        assertEquals(NONE, source.get("com/acme/Broken", "other"));
        SourceDocAnnotations.Stats stats = source.stats();
        assertEquals(1, stats.filesUnparsable());
        assertEquals(0, stats.filesParsed());
        assertEquals(1, source.warnings().size());
        assertTrue(source.warnings().get(0).startsWith(
                "cannot parse the source of io.quarkus:quarkus-acme:1.0: com/acme/Broken.java: "),
                source.warnings().get(0));
    }

    @Test
    void parsesEachFileOnceUnderConcurrentLookups() throws Exception {
        Map<String, DocAnnotations> expected = new HashMap<>();
        expected.put("com/acme/LiteralForms#plain", new DocAnnotations("plain", null, false, false));
        expected.put("com/acme/LiteralForms#textBlockWithoutNewline",
                new DocAnnotations("no final newline", null, false, false));
        expected.put("com/acme/LiteralForms$Nested#outer", new DocAnnotations("pre", null, false, false));
        expected.put("com/acme/LiteralForms$Nested$Deeper#deep", new DocAnnotations(null, "sufpre", false, false));
        expected.put("com/acme/legacy/LegacyConfig#named",
                new DocAnnotations(null, "datasource-name", false, false));
        expected.put("com/acme/legacy/LegacyConfig#first", NONE);
        expected.put("com/acme/legacy/SecondaryGroup#entries",
                new DocAnnotations(null, "secondary-key", false, false));
        int threads = 16;
        int rounds = 100;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Void>> results = new ArrayList<>();
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            for (int t = 0; t < threads; t++) {
                Callable<Void> task = () -> {
                    start.await();
                    for (int round = 0; round < rounds; round++) {
                        for (Map.Entry<String, DocAnnotations> member : expected.entrySet()) {
                            String[] parts = member.getKey().split("#");
                            assertEquals(member.getValue(), source.get(parts[0], parts[1]), member.getKey());
                        }
                    }
                    return null;
                };
                results.add(executor.submit(task));
            }
            start.countDown();
            for (Future<Void> result : results) {
                result.get(1, TimeUnit.MINUTES);
            }
        } finally {
            executor.shutdownNow();
        }
        SourceDocAnnotations.Stats stats = source.stats();
        assertEquals(2, stats.filesParsed());
        assertEquals((long) threads * rounds * expected.size(), stats.lookups());
        assertEquals((long) threads * rounds * (expected.size() - 1), stats.annotatedLookups());
        assertEquals(5, stats.ownersResolved());
        assertEquals(1, stats.ownersInOtherFiles());
        assertEquals(List.of(), source.warnings());
    }

    @Test
    void keepsABoundedNumberOfSourcesJarsOpen() throws IOException {
        int count = SourceDocAnnotations.OPEN_JARS + 8;
        List<InMemoryJar> binaries = new ArrayList<>();
        Map<String, JarSource> sourcesJars = new HashMap<>();
        for (int i = 0; i < count; i++) {
            String artifact = "quarkus-ext" + i;
            String packagePath = "com/acme/ext" + i;
            binaries.add(InMemoryJar.of(artifact, "1.0")
                    .with(packagePath + "/A.class", new byte[0])
                    .with(packagePath + "/B.class", new byte[0]));
            sourcesJars.put(artifact, InMemoryJar.of(artifact, "1.0")
                    .with(packagePath + "/A.java", member("ext" + i, "A", "a" + i))
                    .with(packagePath + "/B.java", member("ext" + i, "B", "b" + i)));
        }
        AtomicInteger opened = new AtomicInteger();
        VersionJars many = new VersionJars(RELEASE, binaries, jar -> Optional.empty(), 2);
        SourceDocAnnotations bounded = new SourceDocAnnotations(RELEASE, SourceDocAnnotations.locator(many, jar -> {
            opened.incrementAndGet();
            return sourcesJars.get(jar.artifactId());
        }));
        for (int i = 0; i < count; i++) {
            assertEquals("a" + i, bounded.get("com/acme/ext" + i + "/A", "value").defaultValue());
        }
        assertEquals(count, opened.get());
        // parsed files need no jar: no reopening
        assertEquals("a0", bounded.get("com/acme/ext0/A", "value").defaultValue());
        assertEquals(count, opened.get());
        // the least recently used jars were closed: another file of the first one opens it again
        assertEquals("b0", bounded.get("com/acme/ext0/B", "value").defaultValue());
        assertEquals(count + 1, opened.get());
        // the most recently used ones are still open
        int last = count - 1;
        assertEquals("b" + last, bounded.get("com/acme/ext" + last + "/B", "value").defaultValue());
        assertEquals(count + 1, opened.get());
        assertEquals(count + 2, bounded.stats().filesParsed());
    }

    private static String member(String packageName, String type, String value) {
        return "package com.acme." + packageName + ";\n\ninterface " + type + " {\n"
                + "    @io.quarkus.runtime.annotations.ConfigDocDefault(\"" + value + "\")\n"
                + "    String value();\n}\n";
    }

    /// A minimal class file with a `SourceFile` attribute.
    private static byte[] classFile(String internalName, String sourceFile) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, internalName, null, "java/lang/Object",
                null);
        writer.visitSource(sourceFile, null);
        writer.visitEnd();
        return writer.toByteArray();
    }
}
