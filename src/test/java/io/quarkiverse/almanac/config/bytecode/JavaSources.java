package io.quarkiverse.almanac.config.bytecode;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/// Compiles Java sources in memory with the running JDK's `javac`, so tests read real class files (generic signatures,
/// inner classes, annotation retention), and provides stand-ins for the Quarkus and SmallRye config annotations with
/// the retention of a chosen era.
final class JavaSources {

    private final Map<String, String> sources = new LinkedHashMap<>();

    /// Sources with the config annotations.
    ///
    /// @param docAnnotationsAtRuntime true for the 3.14+ (RUNTIME) retention of `@ConfigDocMapKey`,
    ///                                `@ConfigDocDefault`, `@ConfigDocIgnore` and `@ConfigDocSection`; false for the
    ///                                SOURCE retention of earlier releases
    static JavaSources withConfigAnnotations(boolean docAnnotationsAtRuntime) {
        JavaSources sources = new JavaSources();
        String doc = docAnnotationsAtRuntime ? "RUNTIME" : "SOURCE";
        String q = "package io.quarkus.runtime.annotations;\nimport java.lang.annotation.*;\n";
        sources.add("io.quarkus.runtime.annotations.ConfigPhase", "package io.quarkus.runtime.annotations;\n"
                + "public enum ConfigPhase { BUILD_TIME, BUILD_AND_RUN_TIME_FIXED, RUN_TIME, BOOTSTRAP }");
        sources.add("io.quarkus.runtime.annotations.ConfigRoot", q + "@Retention(RetentionPolicy.RUNTIME) "
                + "public @interface ConfigRoot { String name() default \"<<hyphenated element name>>\"; "
                + "String prefix() default \"quarkus\"; ConfigPhase phase() default ConfigPhase.BUILD_TIME; }");
        sources.add("io.quarkus.runtime.annotations.ConfigItem", q + "@Retention(RetentionPolicy.RUNTIME) "
                + "public @interface ConfigItem { String name() default \"<<hyphenated element name>>\"; "
                + "String defaultValue() default \"<<no default>>\"; String defaultValueDocumentation() default \"\"; "
                + "boolean generateDocumentation() default true; }");
        sources.add("io.quarkus.runtime.annotations.ConfigGroup",
                q + "@Retention(RetentionPolicy.RUNTIME) public @interface ConfigGroup { }");
        sources.add("io.quarkus.runtime.annotations.ConvertWith",
                q + "@Retention(RetentionPolicy.RUNTIME) public @interface ConvertWith { Class<?> value(); }");
        sources.add("io.quarkus.runtime.annotations.DefaultConverter",
                q + "@Retention(RetentionPolicy.RUNTIME) public @interface DefaultConverter { }");
        sources.add("io.quarkus.runtime.annotations.ConfigDocEnumValue",
                q + "@Retention(RetentionPolicy.RUNTIME) public @interface ConfigDocEnumValue { String value(); }");
        sources.add("io.quarkus.runtime.annotations.ConfigDocEnum", q + "@Retention(RetentionPolicy.RUNTIME) "
                + "public @interface ConfigDocEnum { boolean enforceHyphenateValues() default false; }");
        sources.add("io.quarkus.runtime.annotations.ConfigDocMapKey",
                q + "@Retention(RetentionPolicy." + doc + ") public @interface ConfigDocMapKey { String value(); }");
        sources.add("io.quarkus.runtime.annotations.ConfigDocDefault",
                q + "@Retention(RetentionPolicy." + doc + ") public @interface ConfigDocDefault { String value(); }");
        sources.add("io.quarkus.runtime.annotations.ConfigDocIgnore",
                q + "@Retention(RetentionPolicy." + doc + ") public @interface ConfigDocIgnore { }");
        sources.add("io.quarkus.runtime.annotations.ConfigDocSection", q + "@Retention(RetentionPolicy." + doc
                + ") public @interface ConfigDocSection { boolean generated() default false; }");
        String s = "package io.smallrye.config;\nimport java.lang.annotation.*;\n";
        sources.add("io.smallrye.config.ConfigMapping", s + "@Retention(RetentionPolicy.RUNTIME) "
                + "public @interface ConfigMapping { String prefix() default \"\"; }");
        sources.add("io.smallrye.config.WithName", s + "@Retention(RetentionPolicy.RUNTIME) "
                + "@Target(ElementType.METHOD) public @interface WithName { String value(); }");
        sources.add("io.smallrye.config.WithParentName", s + "@Retention(RetentionPolicy.RUNTIME) "
                + "@Target(ElementType.METHOD) public @interface WithParentName { }");
        sources.add("io.smallrye.config.WithDefault", s + "@Retention(RetentionPolicy.RUNTIME) "
                + "@Target(ElementType.METHOD) public @interface WithDefault { String value(); }");
        sources.add("io.smallrye.config.WithUnnamedKey", s + "@Retention(RetentionPolicy.RUNTIME) "
                + "@Target({ElementType.METHOD, ElementType.TYPE_USE}) "
                + "public @interface WithUnnamedKey { String value() default \"\"; }");
        sources.add("io.smallrye.config.WithConverter", s + "@Retention(RetentionPolicy.RUNTIME) "
                + "@Target({ElementType.METHOD, ElementType.TYPE_USE}) "
                + "public @interface WithConverter { Class<?> value(); }");
        return sources;
    }

    /// Adds one compilation unit.
    JavaSources add(String qualifiedName, String source) {
        sources.put(qualifiedName, source);
        return this;
    }

    /// Compiles everything (Java 21 class files, no annotation processing).
    ///
    /// @return class files by internal name, e.g. `a/b/Outer$Inner`
    Map<String, byte[]> compile() {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("tests need a JDK with javac");
        }
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        Map<String, ByteArrayOutputStream> outputs = new TreeMap<>();
        try (StandardJavaFileManager standard =
                     compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            JavaFileManager manager = new ForwardingJavaFileManager<>(standard) {
                @Override
                public JavaFileObject getJavaFileForOutput(Location location, String className,
                        JavaFileObject.Kind kind, FileObject sibling) {
                    URI uri = URI.create("mem:///" + className.replace('.', '/') + kind.extension);
                    return new SimpleJavaFileObject(uri, kind) {
                        @Override
                        public OutputStream openOutputStream() {
                            ByteArrayOutputStream out = new ByteArrayOutputStream();
                            outputs.put(className.replace('.', '/'), out);
                            return out;
                        }
                    };
                }
            };
            List<JavaFileObject> units = new ArrayList<>();
            sources.forEach((name, text) -> units.add(new SimpleJavaFileObject(
                    URI.create("string:///" + name.replace('.', '/') + ".java"), JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    return text;
                }
            }));
            boolean ok = compiler.getTask(null, manager, diagnostics,
                    List.of("--release", "21", "-proc:none", "-Xlint:none"), null, units).call();
            if (!ok) {
                StringBuilder message = new StringBuilder("compilation failed:");
                for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
                    message.append('\n').append(d);
                }
                throw new IllegalStateException(message.toString());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Map<String, byte[]> classes = new TreeMap<>();
        outputs.forEach((name, out) -> classes.put(name, out.toByteArray()));
        return classes;
    }
}
