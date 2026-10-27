package io.quarkiverse.almanac.config.bytecode;

import io.quarkiverse.almanac.config.JarSource;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

/// A [JarSource] over in-memory entries with any coordinates; counts how often it is probed.
final class TestJar implements JarSource {

    private final String groupId;
    private final String artifactId;
    private final String version;
    private final Map<String, byte[]> entries = new TreeMap<>();
    private final AtomicInteger probes = new AtomicInteger();

    private TestJar(String groupId, String artifactId, String version) {
        this.groupId = groupId;
        this.artifactId = artifactId;
        this.version = version;
    }

    static TestJar of(String groupId, String artifactId, String version) {
        return new TestJar(groupId, artifactId, version);
    }

    /// Adds a file.
    TestJar with(String entry, byte[] content) {
        entries.put(entry, content);
        return this;
    }

    /// Adds a text file (UTF-8).
    TestJar with(String entry, String content) {
        return with(entry, content.getBytes(StandardCharsets.UTF_8));
    }

    /// Adds the given compiled classes (`internal name → bytes`).
    TestJar withClasses(Map<String, byte[]> classes, Collection<String> internalNames) {
        for (String name : internalNames) {
            byte[] bytes = classes.get(name);
            if (bytes == null) {
                throw new IllegalArgumentException("not compiled: " + name);
            }
            entries.put(name + ".class", bytes);
        }
        return this;
    }

    /// Adds `META-INF/quarkus-config-roots.list` with the given binary names.
    TestJar withRoots(String... binaryNames) {
        return with(BytecodeConfigReader.ROOTS_ENTRY, String.join("\n", binaryNames) + "\n");
    }

    /// Number of [#contains] calls, i.e. how often a reader probed this jar for an entry.
    int probes() {
        return probes.get();
    }

    @Override
    public String groupId() {
        return groupId;
    }

    @Override
    public String artifactId() {
        return artifactId;
    }

    @Override
    public String version() {
        return version;
    }

    @Override
    public boolean exists() {
        return true;
    }

    @Override
    public Set<String> entryNames() {
        return entries.keySet();
    }

    @Override
    public boolean contains(String entry) {
        probes.incrementAndGet();
        return entries.containsKey(entry);
    }

    @Override
    public Optional<byte[]> read(String entry) {
        return Optional.ofNullable(entries.get(entry));
    }

    @Override
    public String toString() {
        return groupId + ":" + artifactId + ":" + version;
    }
}
