package io.quarkiverse.almanac.config;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/// [JarSource] over in-memory entries, for tests.
public final class InMemoryJar implements JarSource {

    private final String artifactId;
    private final String version;
    private final Map<String, byte[]> entries = new LinkedHashMap<>();
    private final boolean exists;

    private InMemoryJar(String artifactId, String version, boolean exists) {
        this.artifactId = artifactId;
        this.version = version;
        this.exists = exists;
    }

    public static InMemoryJar of(String artifactId, String version) {
        return new InMemoryJar(artifactId, version, true);
    }

    /// A jar the repository does not have (404).
    public static InMemoryJar missing(String artifactId, String version) {
        return new InMemoryJar(artifactId, version, false);
    }

    public InMemoryJar with(String entry, byte[] content) {
        entries.put(entry, content);
        return this;
    }

    public InMemoryJar with(String entry, String content) {
        return with(entry, content.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String groupId() {
        return "io.quarkus";
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
        return exists;
    }

    @Override
    public Set<String> entryNames() {
        return exists ? entries.keySet() : Set.of();
    }

    @Override
    public boolean contains(String entry) {
        return exists && entries.containsKey(entry);
    }

    @Override
    public Optional<byte[]> read(String entry) {
        return exists ? Optional.ofNullable(entries.get(entry)) : Optional.empty();
    }

    @Override
    public String toString() {
        return artifactId;
    }
}
