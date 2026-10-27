package io.quarkiverse.almanac.config.bytecode;

import io.quarkiverse.almanac.config.JarSource;
import io.quarkiverse.almanac.config.VersionJars;
import io.quarkiverse.almanac.maven.Http;
import io.quarkiverse.almanac.util.Log;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

/// The release-wide class path the processor would compile against: the
/// `io.quarkus` jars of the release, then the running JDK, then the release's third-party jars (searched lazily, see
/// [LibraryClasses]). Parsed classes are cached per release; each class is fetched and parsed once even when several
/// threads ask for it at the same time. Thread-safe.
final class ClassPath {

    private static final String CLASS_SUFFIX = ".class";
    private static final String QUARKUS_PACKAGE = "io/quarkus/";

    private final VersionJars jars;
    private final LibraryClasses libraries;
    private final ConcurrentMap<String, FutureTask<Optional<ClassFile>>> cache = new ConcurrentHashMap<>();
    private final Map<String, String> thirdParty = new ConcurrentSkipListMap<>();
    private final Map<String, String> unparsable = new ConcurrentSkipListMap<>();

    ClassPath(VersionJars jars) {
        this.jars = jars;
        this.libraries = new LibraryClasses(jars.libraries());
    }

    /// A class as the compilation of `module` sees it: from the module's own jar when it has the class (several jars of
    /// a release can hold different classes of the same name), else from the class path.
    ///
    /// @param module the jar being scanned, or null
    /// @return empty when no jar, the JDK or a third-party candidate jar has the class (or it cannot be parsed)
    Optional<ClassFile> load(String internalName, JarSource module) throws IOException {
        if (module != null && module.contains(internalName + CLASS_SUFFIX)) {
            Optional<JarSource> owner = jars.jarOf(internalName);
            if (owner.isEmpty() || owner.get() != module) {
                return cached(module.artifactId() + "!" + internalName, () -> fromJar(module, internalName));
            }
        }
        return load(internalName);
    }

    /// A class from the class path (see the class comment).
    Optional<ClassFile> load(String internalName) throws IOException {
        return cached(internalName, () -> find(internalName));
    }

    /// Third-party classes loaded so far: internal name → `groupId:artifactId:version` of their jar, sorted.
    Map<String, String> thirdPartyClasses() {
        return Map.copyOf(thirdParty);
    }

    /// Number of third-party candidate jars probed so far.
    int libraryProbes() {
        return libraries.probes();
    }

    /// Problems met while loading classes: unparsable class files and unreadable third-party jars.
    List<String> warnings() {
        List<String> warnings = new ArrayList<>();
        unparsable.forEach((name, failure) -> warnings.add("unreadable class " + name + ": " + failure));
        warnings.addAll(libraries.warnings());
        return warnings;
    }

    private Optional<ClassFile> find(String internalName) throws IOException {
        Optional<byte[]> bytes = jars.classBytes(internalName);
        String origin = "io.quarkus jars";
        if (bytes.isEmpty() && !internalName.startsWith(QUARKUS_PACKAGE)) {
            bytes = JdkClasses.read(internalName);
            origin = "JDK";
        }
        if (bytes.isEmpty()) {
            Optional<LibraryClasses.Found> found = libraries.find(internalName);
            if (found.isEmpty()) {
                return Optional.empty();
            }
            JarSource jar = found.get().jar();
            origin = jar.groupId() + ":" + jar.artifactId() + ":" + jar.version();
            thirdParty.put(internalName, origin);
            Log.debug("%s: %s from %s", jars.quarkusVersion(), internalName, origin);
            bytes = Optional.of(found.get().bytes());
        }
        return parse(internalName, bytes.get(), origin);
    }

    private Optional<ClassFile> fromJar(JarSource jar, String internalName) throws IOException {
        Optional<byte[]> bytes = jar.read(internalName + CLASS_SUFFIX);
        return bytes.isEmpty() ? Optional.empty() : parse(internalName, bytes.get(), jar.artifactId());
    }

    private Optional<ClassFile> parse(String internalName, byte[] bytes, String origin) {
        try {
            return Optional.of(ClassFile.parse(bytes));
        } catch (RuntimeException e) {
            unparsable.putIfAbsent(ClassFile.canonicalName(internalName), "from " + origin + ": " + e);
            return Optional.empty();
        }
    }

    /// A loader that may fail with an [IOException].
    @FunctionalInterface
    private interface Loader {
        Optional<ClassFile> load() throws IOException;
    }

    private Optional<ClassFile> cached(String key, Loader loader) throws IOException {
        FutureTask<Optional<ClassFile>> task = cache.get(key);
        if (task == null) {
            FutureTask<Optional<ClassFile>> created = new FutureTask<>(() -> {
                try {
                    return loader.load();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
            task = cache.putIfAbsent(key, created);
            if (task == null) {
                task = created;
                created.run();
            }
        }
        try {
            return task.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("interrupted while loading " + key);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof UncheckedIOException unchecked) {
                if (unchecked.getCause() instanceof Http.StoppedException stopped) {
                    // keeps its type: no reader takes a refused request for a missing class
                    throw stopped;
                }
                throw new IOException("cannot load " + key + ": " + unchecked.getCause().getMessage(),
                        unchecked.getCause());
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IOException("cannot load " + key, cause);
        }
    }
}
