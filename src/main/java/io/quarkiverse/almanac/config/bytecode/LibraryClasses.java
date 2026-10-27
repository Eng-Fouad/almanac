package io.quarkiverse.almanac.config.bytecode;

import io.quarkiverse.almanac.config.JarSource;
import io.quarkiverse.almanac.maven.Http;
import io.quarkiverse.almanac.util.Log;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicInteger;

/// Finds classes in the third-party jars of a release (BOM-managed, outside `io.quarkus`), lazily and targeted: a class
/// is looked for only in jars whose coordinates match its package, and each probed jar costs one central directory
/// fetch, which the jar's cache keeps. The reader needs this for third-party enums used by config members
///, e.g. `io.vertx.core.http.ClientAuth` or `org.hibernate.FlushMode`.
///
/// Candidates are the jars whose groupId is a segment prefix of the class's package, plus the jars that the [#EXPLICIT]
/// table names for packages whose jars live under another groupId; the longest match comes first. Within one match
/// length, jars whose artifactId mentions more of the remaining package segments come first (`vertx-core` for
/// `io.vertx.core.http`), then shorter artifactIds. Thread-safe.
final class LibraryClasses {

    /// Packages whose jars' groupId is not a prefix of the package: package prefix → `groupId:artifactId` candidates,
    /// where artifactId `*` stands for every jar of the group.
    static final Map<String, List<String>> EXPLICIT = Map.of(
            "org.bson", List.of("org.mongodb:bson"),
            "org.hibernate", List.of("org.hibernate.orm:hibernate-core", "org.hibernate:hibernate-core"),
            "com.fasterxml.jackson.annotation", List.of("com.fasterxml.jackson.core:jackson-annotations"),
            "org.jboss.resteasy.reactive", List.of("io.quarkus.resteasy.reactive:*"));

    /// Most jars probed for one class.
    static final int MAX_PROBES = 40;

    /// A class file found in a third-party jar.
    record Found(JarSource jar, byte[] bytes) {
    }

    private record Candidate(JarSource jar, int matched, int[] mentions, int mentionCount) {
    }

    private final List<JarSource> libraries;
    private final AtomicInteger probes = new AtomicInteger();
    private final Map<String, String> unreadable = new ConcurrentSkipListMap<>();

    /// @param libraries the release's third-party jars (see `VersionJars#libraries()`)
    LibraryClasses(List<JarSource> libraries) {
        this.libraries = List.copyOf(libraries);
    }

    /// Looks for a class in the candidate jars. A candidate that cannot be read is skipped and reported by
    /// [#warnings()]; one whose request was refused because requests stopped ends the search.
    ///
    /// @param internalName e.g. `io/vertx/core/http/ClientAuth`
    /// @return the class file and its jar; empty when no candidate has it
    /// @throws Http.StoppedException when requests stopped, so that the class is unknown rather than missing
    Optional<Found> find(String internalName) throws Http.StoppedException {
        String entry = internalName + ".class";
        int probed = 0;
        for (JarSource jar : candidates(internalName)) {
            if (probed++ == MAX_PROBES) {
                Log.debug("third-party class %s: not in the first %d candidate jars", internalName, MAX_PROBES);
                break;
            }
            probes.incrementAndGet();
            try {
                if (jar.contains(entry)) {
                    Optional<byte[]> bytes = jar.read(entry);
                    if (bytes.isPresent()) {
                        return Optional.of(new Found(jar, bytes.get()));
                    }
                }
            } catch (Http.StoppedException e) {
                throw e;
            } catch (IOException e) {
                unreadable.putIfAbsent(coordinates(jar) + ":" + jar.version(), e.toString());
            }
        }
        return Optional.empty();
    }

    /// Number of candidate jars probed so far.
    int probes() {
        return probes.get();
    }

    /// One warning per third-party jar that could not be read, sorted.
    List<String> warnings() {
        List<String> warnings = new ArrayList<>();
        unreadable.forEach((jar, failure) -> warnings.add("third-party jar " + jar + " unreadable (" + failure
                + "); its classes are unknown"));
        return warnings;
    }

    /// The jars that may hold a class, best first (see the class comment).
    List<JarSource> candidates(String internalName) {
        int slash = internalName.lastIndexOf('/');
        if (slash < 0) {
            return List.of();
        }
        String[] packageSegments = internalName.substring(0, slash).split("/");
        String packageName = String.join(".", packageSegments);
        List<Candidate> candidates = new ArrayList<>();
        for (JarSource jar : libraries) {
            String[] groupSegments = jar.groupId().split("\\.");
            int matched = commonPrefix(packageSegments, groupSegments) == groupSegments.length
                    ? groupSegments.length
                    : explicitMatch(packageName, jar);
            if (matched == 0) {
                continue;
            }
            int[] mentions = mentions(jar.artifactId(), packageSegments, matched);
            int count = 0;
            for (int mention : mentions) {
                count += mention;
            }
            candidates.add(new Candidate(jar, matched, mentions, count));
        }
        candidates.sort(Comparator.comparingInt(Candidate::matched).reversed()
                .thenComparing(Comparator.comparingInt(Candidate::mentionCount).reversed())
                .thenComparing(Candidate::mentions, LibraryClasses::compareMentions)
                .thenComparingInt(c -> c.jar().artifactId().length())
                .thenComparing(c -> coordinates(c.jar())));
        return candidates.stream().map(Candidate::jar).toList();
    }

    /// Number of leading package segments covered by an [#EXPLICIT] entry naming this jar, else 0.
    private static int explicitMatch(String packageName, JarSource jar) {
        int best = 0;
        for (Map.Entry<String, List<String>> entry : EXPLICIT.entrySet()) {
            String prefix = entry.getKey();
            boolean covers = packageName.equals(prefix) || packageName.startsWith(prefix + ".");
            if (covers && (entry.getValue().contains(coordinates(jar))
                    || entry.getValue().contains(jar.groupId() + ":*"))) {
                best = Math.max(best, prefix.split("\\.").length);
            }
        }
        return best;
    }

    private static String coordinates(JarSource jar) {
        return jar.groupId() + ":" + jar.artifactId();
    }

    private static int commonPrefix(String[] a, String[] b) {
        int n = 0;
        while (n < a.length && n < b.length && a[n].equals(b[n])) {
            n++;
        }
        return n;
    }

    /// For each package segment after the matched ones: 1 when the artifactId (without dashes) contains it.
    private static int[] mentions(String artifactId, String[] packageSegments, int matched) {
        String compact = artifactId.replace("-", "").toLowerCase(Locale.ROOT);
        int[] mentions = new int[Math.max(0, packageSegments.length - matched)];
        for (int i = 0; i < mentions.length; i++) {
            String segment = packageSegments[matched + i].toLowerCase(Locale.ROOT);
            mentions[i] = segment.length() >= 2 && compact.contains(segment) ? 1 : 0;
        }
        return mentions;
    }

    /// Orders mention vectors so that a mention of an earlier segment comes first.
    private static int compareMentions(int[] a, int[] b) {
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            if (a[i] != b[i]) {
                return Integer.compare(b[i], a[i]);
            }
        }
        return 0;
    }
}
