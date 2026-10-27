package io.quarkiverse.almanac.config.bytecode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;

/// The member types of a release whose class files could not be found in the `io.quarkus` jars, the JDK or the
/// candidate third-party jars. They are read as non-enum class leaves, so their
/// enum values are unknown; the snapshot gets one warning per type. Thread-safe.
final class UnresolvedTypes {

    private final Map<String, Set<String>> users = new ConcurrentSkipListMap<>();

    /// Records a type that could not be loaded.
    ///
    /// @param qualifiedName canonical name of the type
    /// @param member        `fqcn#member` whose type it is, or null when unknown
    void add(String qualifiedName, String member) {
        Set<String> members = users.computeIfAbsent(qualifiedName, name -> ConcurrentHashMap.newKeySet());
        if (member != null) {
            members.add(member);
        }
    }

    /// Canonical names of the types recorded, sorted.
    Set<String> types() {
        return new TreeSet<>(users.keySet());
    }

    /// One warning per type, sorted by type, naming the first member (by name) that uses it.
    List<String> warnings() {
        List<String> warnings = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : users.entrySet()) {
            TreeSet<String> members = new TreeSet<>(entry.getValue());
            String usage = members.isEmpty() ? ""
                    : "; used by " + members.first() + (members.size() > 1
                            ? " and " + (members.size() - 1) + " more member" + (members.size() > 2 ? "s" : "")
                            : "");
            warnings.add("type " + entry.getKey() + " not found in the release's jars or the JDK: read as a "
                    + "non-enum class, enum values unknown" + usage);
        }
        return warnings;
    }
}
