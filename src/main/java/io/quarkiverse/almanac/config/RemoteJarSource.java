package io.quarkiverse.almanac.config;

import io.quarkiverse.almanac.maven.RemoteJar;

import java.io.IOException;
import java.util.Optional;
import java.util.Set;

/// [JarSource] view of a [RemoteJar].
record RemoteJarSource(RemoteJar jar) implements JarSource {

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
}
