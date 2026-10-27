package io.quarkiverse.almanac.config.sourcedoc;

import io.quarkiverse.almanac.config.JarSource;
import io.quarkiverse.almanac.config.VersionJars;
import io.quarkiverse.almanac.maven.MavenRepo;
import io.quarkiverse.almanac.maven.RemoteJar;
import io.quarkiverse.almanac.util.Log;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/// Reads `@ConfigDoc*` annotations from the `-sources` jars of a release, for releases whose class files
/// lack them (before 3.14).
///
/// [#get(String, String)] resolves the owner's internal name to the binary jar that holds it
/// ([VersionJars#jarOf]), then to that artifact's `sources` jar, to the top-level source file (package
/// path + outer class name + `.java`), to the nested type by simple names and to the field or method without
/// parameters of that name (see [SourceFileIndex] for what is read and how values are evaluated). A class
/// declared in a file of another name (a second top-level class, e.g. `ObjectStoreConfig` in
/// `TransactionManagerConfiguration.java` of `quarkus-narayana-jta`) is found through the
/// `SourceFile` attribute of its class file.
///
/// - Each source file is parsed at most once and kept as a small [SourceFileIndex] (no syntax tree). At most
///   {@value #OPEN_JARS} sources jars are kept open, least recently used first out; a closed one is reopened from
///   the on-disk cache, so memory stays bounded however many jars a release has.
/// - Failures give [DocAnnotations#NONE], are counted ([#stats()]), logged once per jar, file, type or
///   member, and listed in [#warnings()]: no jar contains the owner, no sources jar, no such file, type or
///   member, a parse error, or an annotation value that is not a constant of its file (that value alone is
///   null).
/// - I/O errors (network, corrupt jar, offline mode without a cached copy) propagate; a later call retries.
///
/// Instances are thread-safe.
public final class SourceDocAnnotations implements DocAnnotationSource {

    /// Sources jars kept open at the same time.
    static final int OPEN_JARS = 32;

    static final String SOURCES_CLASSIFIER = "sources";

    /// Where classes and their sources come from: in production the release's jars and the Maven repository.
    interface Locator {

        /// The binary jar that contains a class; empty when no jar of the release has it.
        Optional<JarSource> binaryJarOf(String internalName) throws IOException;

        /// The path of the file a class was compiled from, as named by its class file's `SourceFile` attribute
        /// (e.g. `io/quarkus/narayana/jta/runtime/TransactionManagerConfiguration.java`); empty when unknown.
        Optional<String> sourceFileOf(String internalName) throws IOException;

        /// The sources jar of a binary jar, opened lazily (it may not exist).
        JarSource sourcesJarOf(JarSource binaryJar);
    }

    /// Members per doc annotation; a member with several annotations counts for each.
    public record AnnotationCounts(int defaults, int mapKeys, int ignores, int sections) {

        static AnnotationCounts of(Collection<DocAnnotations> annotations) {
            int defaults = 0;
            int mapKeys = 0;
            int ignores = 0;
            int sections = 0;
            for (DocAnnotations a : annotations) {
                defaults += a.defaultValue() != null ? 1 : 0;
                mapKeys += a.mapKey() != null ? 1 : 0;
                ignores += a.ignore() ? 1 : 0;
                sections += a.section() ? 1 : 0;
            }
            return new AnnotationCounts(defaults, mapKeys, ignores, sections);
        }

        @Override
        public String toString() {
            return String.format(Locale.ROOT, "@ConfigDocDefault %,d, @ConfigDocMapKey %,d, @ConfigDocIgnore %,d, "
                    + "@ConfigDocSection %,d", defaults, mapKeys, ignores, sections);
        }
    }

    /// Counters, for reports.
    ///
    /// @param lookups            calls of [#get]
    /// @param annotatedLookups   calls that found at least one annotation
    /// @param ownersResolved     distinct owners found in their source file
    /// @param ownersInOtherFiles of those, the owners found through their class file's `SourceFile` attribute
    /// @param ownersWithoutJar   distinct owners that no jar of the release contains
    /// @param sourcesJarsFound   distinct sources jars read
    /// @param sourcesJarsMissing distinct sources jars the repository does not have
    /// @param filesParsed        source files parsed
    /// @param filesParsedAsNewer of those, the files that only parsed at the newer language level
    /// @param filesMissing       distinct source files that are not in their sources jar
    /// @param filesUnparsable    source files that did not parse
    /// @param typesMissing       distinct owners not declared in their source file
    /// @param membersMissing     distinct members not declared by their owner's source
    /// @param unresolvedValues   annotation values in parsed files that are not constants of their file
    /// @param lookedUp           distinct looked-up members per annotation
    /// @param inParsedFiles      annotated members of all parsed files per annotation
    public record Stats(long lookups, long annotatedLookups, int ownersResolved, int ownersInOtherFiles,
            int ownersWithoutJar, int sourcesJarsFound, int sourcesJarsMissing, int filesParsed,
            int filesParsedAsNewer, int filesMissing, int filesUnparsable, int typesMissing, int membersMissing,
            int unresolvedValues, AnnotationCounts lookedUp, AnnotationCounts inParsedFiles) {

        /// One line for logs.
        public String summary() {
            return String.format(Locale.ROOT, "%d lookups (%d annotated), owners %d resolved (%d in a file of "
                            + "another name) / %d without jar, sources jars %d found / %d missing, files %d parsed "
                            + "(%d as %s) / %d missing / %d unparsable, types missing %d, members missing %d, "
                            + "unresolved values %d",
                    lookups, annotatedLookups, ownersResolved, ownersInOtherFiles, ownersWithoutJar, sourcesJarsFound,
                    sourcesJarsMissing, filesParsed, filesParsedAsNewer, SourceFileIndex.NEWER_LANGUAGE_LEVEL,
                    filesMissing, filesUnparsable, typesMissing, membersMissing, unresolvedValues);
        }
    }

    /// What loading one source file gave; `index` is set only for [FileStatus#PARSED].
    private record FileResult(FileStatus status, SourceFileIndex index) {
    }

    private enum FileStatus { PARSED, NO_SOURCES_JAR, NO_FILE, UNPARSABLE }

    /// An internal class name split into its package path and its binary name relative to the package.
    private record ClassName(String packagePath, String type) {

        static ClassName of(String internalName) {
            int slash = internalName.lastIndexOf('/');
            return new ClassName(slash < 0 ? "" : internalName.substring(0, slash), internalName.substring(slash + 1));
        }

        /// The top-level source file: package path, outer class name (before the first `$`), `.java`.
        String sourceFile() {
            int dollar = type.indexOf('$');
            String outer = dollar < 0 ? type : type.substring(0, dollar);
            return packagePath.isEmpty() ? outer + ".java" : packagePath + "/" + outer + ".java";
        }
    }

    private final String release;
    private final Locator locator;
    private final ReentrantLock openJarsLock = new ReentrantLock();
    private final Map<String, JarSource> openJars = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, JarSource> eldest) {
            return size() > OPEN_JARS;
        }
    };
    private final Map<String, FutureTask<FileResult>> files = new ConcurrentHashMap<>();
    private final Queue<String> warnings = new ConcurrentLinkedQueue<>();

    private final LongAdder lookups = new LongAdder();
    private final LongAdder annotatedLookups = new LongAdder();
    private final Set<String> resolvedOwners = ConcurrentHashMap.newKeySet();
    private final Set<String> ownersInOtherFiles = ConcurrentHashMap.newKeySet();
    private final Set<String> ownersWithoutJar = ConcurrentHashMap.newKeySet();
    private final Set<String> foundJars = ConcurrentHashMap.newKeySet();
    private final Set<String> missingJars = ConcurrentHashMap.newKeySet();
    private final Set<String> missingFiles = ConcurrentHashMap.newKeySet();
    private final LongAdder filesParsed = new LongAdder();
    private final LongAdder filesParsedAsNewer = new LongAdder();
    private final LongAdder filesUnparsable = new LongAdder();
    private final LongAdder unresolvedValues = new LongAdder();
    private final Set<String> missingTypes = ConcurrentHashMap.newKeySet();
    private final Set<String> missingMembers = ConcurrentHashMap.newKeySet();
    private final Map<String, DocAnnotations> annotatedMembers = new ConcurrentHashMap<>();
    private final LongAdder defaultsInFiles = new LongAdder();
    private final LongAdder mapKeysInFiles = new LongAdder();
    private final LongAdder ignoresInFiles = new LongAdder();
    private final LongAdder sectionsInFiles = new LongAdder();

    /// @param release label for messages, usually the Quarkus version
    /// @param locator where classes and sources come from
    SourceDocAnnotations(String release, Locator locator) {
        this.release = release;
        this.locator = locator;
    }

    /// The annotation source of one release. A member's sources jar is the `sources` classifier of the jar that
    /// [VersionJars#jarOf] finds for the member's owner. The result is a [SourceDocAnnotations]; see
    /// [#open] for statistics.
    public static DocAnnotationSource create(MavenRepo repo, VersionJars jars) {
        return open(repo, jars);
    }

    /// Like [#create], typed as the implementation, for [#stats()] and [#warnings()].
    public static SourceDocAnnotations open(MavenRepo repo, VersionJars jars) {
        return new SourceDocAnnotations(jars.quarkusVersion(), locator(jars, binary -> new RemoteSourcesJar(
                repo.jar(binary.groupId(), binary.artifactId(), binary.version(), SOURCES_CLASSIFIER))));
    }

    /// Classes from the release's jars; each binary jar's sources jar from `sourcesJars`.
    ///
    /// @param sourcesJars the sources jar of a binary jar; must not do I/O (the jar is opened on first use)
    static Locator locator(VersionJars jars, Function<JarSource, JarSource> sourcesJars) {
        return new Locator() {
            @Override
            public Optional<JarSource> binaryJarOf(String internalName) throws IOException {
                return jars.jarOf(internalName);
            }

            @Override
            public Optional<String> sourceFileOf(String internalName) throws IOException {
                Optional<byte[]> classFile = jars.classBytes(internalName);
                if (classFile.isEmpty()) {
                    return Optional.empty();
                }
                String packagePath = ClassName.of(internalName).packagePath();
                return ClassDocAnnotations.sourceFile(classFile.get())
                        .map(file -> packagePath.isEmpty() ? file : packagePath + "/" + file);
            }

            @Override
            public JarSource sourcesJarOf(JarSource binaryJar) {
                return sourcesJars.apply(binaryJar);
            }
        };
    }

    @Override
    public DocAnnotations get(String ownerInternalName, String memberName) throws IOException {
        lookups.increment();
        Optional<SourceFileIndex.TypeMembers> type = type(ownerInternalName);
        if (type.isEmpty()) {
            return DocAnnotations.NONE;
        }
        Optional<DocAnnotations> member = type.get().member(memberName);
        if (member.isEmpty()) {
            if (missingMembers.add(ownerInternalName + "#" + memberName)) {
                warn("the source of %s declares no field or method without parameters named %s", ownerInternalName,
                        memberName);
            }
            return DocAnnotations.NONE;
        }
        if (!member.get().equals(DocAnnotations.NONE)) {
            annotatedLookups.increment();
            annotatedMembers.putIfAbsent(ownerInternalName + "#" + memberName, member.get());
        }
        return member.get();
    }

    /// The counters so far.
    public Stats stats() {
        return new Stats(lookups.sum(), annotatedLookups.sum(), resolvedOwners.size(), ownersInOtherFiles.size(),
                ownersWithoutJar.size(), foundJars.size(), missingJars.size(), filesParsed.intValue(),
                filesParsedAsNewer.intValue(), missingFiles.size(), filesUnparsable.intValue(), missingTypes.size(),
                missingMembers.size(), unresolvedValues.intValue(), AnnotationCounts.of(annotatedMembers.values()),
                new AnnotationCounts(defaultsInFiles.intValue(), mapKeysInFiles.intValue(), ignoresInFiles.intValue(),
                        sectionsInFiles.intValue()));
    }

    /// Every problem reported so far, once each, in the order seen (also logged as warnings).
    public List<String> warnings() {
        return List.copyOf(warnings);
    }

    /// The annotations of exactly the field (`method` false) or the method without parameters (`method`
    /// true) of that name, for checks: unlike [#get] it never falls back to the other kind of member and counts
    /// no lookup.
    ///
    /// @return empty when the source or the member is not available
    Optional<DocAnnotations> declared(String ownerInternalName, String memberName, boolean method)
            throws IOException {
        return type(ownerInternalName)
                .map(type -> (method ? type.methods() : type.fields()).get(memberName));
    }

    /// The sources jar of a binary jar when the repository has it. A missing one is counted and reported once, like
    /// a lookup that needs it.
    Optional<JarSource> existingSourcesJar(JarSource binaryJar) throws IOException {
        String jar = coordinates(binaryJar);
        JarSource sources = sourcesJarOf(binaryJar);
        if (!sources.exists()) {
            if (missingJars.add(jar)) {
                warn("no sources jar for %s; the @ConfigDoc* annotations of its classes are unknown", jar);
            }
            return Optional.empty();
        }
        foundJars.add(jar);
        return Optional.of(sources);
    }

    /// The parsed source file at `path` of a binary jar's sources; empty when the sources jar or the file is
    /// missing or the file does not parse (the first two are not reported here, the last one is).
    Optional<SourceFileIndex> sourceFile(JarSource binaryJar, String path) throws IOException {
        return Optional.ofNullable(file(binaryJar, path).index());
    }

    /// The sources jar of a binary jar, through the bounded set of open jars.
    JarSource sourcesJarOf(JarSource binaryJar) {
        openJarsLock.lock();
        try {
            return openJars.computeIfAbsent(coordinates(binaryJar), key -> locator.sourcesJarOf(binaryJar));
        } finally {
            openJarsLock.unlock();
        }
    }

    /// The owner's type in its parsed source file; empty (and reported) when it cannot be found.
    private Optional<SourceFileIndex.TypeMembers> type(String ownerInternalName) throws IOException {
        Optional<JarSource> binary = locator.binaryJarOf(ownerInternalName);
        if (binary.isEmpty()) {
            if (ownersWithoutJar.add(ownerInternalName)) {
                warn("no jar of the release contains %s", ownerInternalName);
            }
            return Optional.empty();
        }
        ClassName name = ClassName.of(ownerInternalName);
        FileResult file = file(binary.get(), name.sourceFile());
        boolean otherFile = false;
        if (file.status() == FileStatus.NO_FILE) {
            // a second top-level class of a file with another name: javac recorded that name in the class file
            Optional<String> compiledFrom = locator.sourceFileOf(ownerInternalName);
            if (compiledFrom.isPresent() && !compiledFrom.get().equals(name.sourceFile())) {
                FileResult declaring = file(binary.get(), compiledFrom.get());
                if (declaring.status() != FileStatus.NO_FILE) {
                    file = declaring;
                    otherFile = true;
                }
            }
        }
        if (file.status() == FileStatus.NO_FILE) {
            String jar = coordinates(binary.get());
            if (missingFiles.add(jar + "!/" + name.sourceFile())) {
                warn("%s is not in the sources jar of %s", name.sourceFile(), jar);
            }
            return Optional.empty();
        }
        if (file.index() == null) {
            // the missing sources jar or the parse error was reported when the file was loaded
            return Optional.empty();
        }
        Optional<SourceFileIndex.TypeMembers> type = file.index().type(name.type());
        if (type.isEmpty()) {
            if (missingTypes.add(ownerInternalName)) {
                warn("the source of %s in %s declares no type %s", ownerInternalName, coordinates(binary.get()),
                        name.type());
            }
            return Optional.empty();
        }
        resolvedOwners.add(ownerInternalName);
        if (otherFile) {
            ownersInOtherFiles.add(ownerInternalName);
        }
        return type;
    }

    private FileResult file(JarSource binaryJar, String path) throws IOException {
        String key = coordinates(binaryJar) + "!/" + path;
        FutureTask<FileResult> task = files.get(key);
        if (task == null) {
            FutureTask<FileResult> created = new FutureTask<>(() -> load(binaryJar, path));
            task = files.putIfAbsent(key, created);
            if (task == null) {
                task = created;
                created.run();
            }
        }
        try {
            return task.get();
        } catch (ExecutionException e) {
            // forget the failure, so that a later call tries again
            files.remove(key, task);
            throw asIOException(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("interrupted while reading " + path);
        }
    }

    private FileResult load(JarSource binaryJar, String path) throws IOException {
        Optional<JarSource> sources = existingSourcesJar(binaryJar);
        if (sources.isEmpty()) {
            return new FileResult(FileStatus.NO_SOURCES_JAR, null);
        }
        Optional<byte[]> content = sources.get().read(path);
        if (content.isEmpty()) {
            return new FileResult(FileStatus.NO_FILE, null);
        }
        String jar = coordinates(binaryJar);
        SourceFileIndex index;
        try {
            index = SourceFileIndex.parse(path, content.get());
        } catch (SourceFileIndex.UnparsableSourceException e) {
            filesUnparsable.increment();
            warn("cannot parse the source of %s: %s", jar, e.getMessage());
            return new FileResult(FileStatus.UNPARSABLE, null);
        }
        filesParsed.increment();
        if (index.languageLevel() != SourceFileIndex.LANGUAGE_LEVEL) {
            filesParsedAsNewer.increment();
        }
        for (String problem : index.problems()) {
            unresolvedValues.increment();
            warn("%s of %s: %s", path, jar, problem);
        }
        AnnotationCounts counts = AnnotationCounts.of(index.annotatedMembers().stream()
                .map(SourceFileIndex.AnnotatedMember::annotations).toList());
        defaultsInFiles.add(counts.defaults());
        mapKeysInFiles.add(counts.mapKeys());
        ignoresInFiles.add(counts.ignores());
        sectionsInFiles.add(counts.sections());
        return new FileResult(FileStatus.PARSED, index);
    }

    private void warn(String format, Object... args) {
        String message = String.format(Locale.ROOT, format, args);
        warnings.add(message);
        Log.warn("sourcedoc %s: %s", release, message);
    }

    private static String coordinates(JarSource jar) {
        return jar.groupId() + ":" + jar.artifactId() + ":" + jar.version();
    }

    private static IOException asIOException(Throwable failure) {
        if (failure instanceof IOException io) {
            return io;
        }
        if (failure instanceof UncheckedIOException unchecked) {
            return unchecked.getCause();
        }
        if (failure instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        return new IOException(failure);
    }

    /// [JarSource] view of a sources jar in the repository.
    private record RemoteSourcesJar(RemoteJar jar) implements JarSource {

        @Override
        public String groupId() {
            return jar.groupId();
        }

        @Override
        public String artifactId() {
            return jar.artifactId();
        }

        @Override
        public String version() {
            return jar.version();
        }

        @Override
        public boolean exists() throws IOException {
            return jar.exists();
        }

        @Override
        public Set<String> entryNames() throws IOException {
            return jar.entryNames();
        }

        @Override
        public boolean contains(String entry) throws IOException {
            return jar.contains(entry);
        }

        @Override
        public Optional<byte[]> read(String entry) throws IOException {
            return jar.read(entry);
        }

        @Override
        public String toString() {
            return jar + ":" + SOURCES_CLASSIFIER;
        }
    }
}
