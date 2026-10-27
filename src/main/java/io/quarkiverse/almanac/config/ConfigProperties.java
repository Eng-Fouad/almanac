package io.quarkiverse.almanac.config;

import io.quarkiverse.almanac.model.ConfigProperty;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/// Helpers shared by the config readers and the history builder.
public final class ConfigProperties {

    private static final String DEPLOYMENT_SUFFIX = "-deployment";

    private ConfigProperties() {
    }

    /// Sorts properties by key and merges those sharing a key: the first one wins and the others' names and aliases
    /// become its aliases, with a warning naming both sources and the fields that differ. Exact repeats (the same
    /// config root documented by two jars) are dropped without a warning.
    public static List<ConfigProperty> sortedUnique(Collection<ConfigProperty> properties, List<String> warnings) {
        Map<String, ConfigProperty> byKey = new TreeMap<>();
        for (ConfigProperty property : properties) {
            ConfigProperty existing = byKey.get(property.key());
            if (existing == null) {
                byKey.put(property.key(), property);
            } else if (!existing.equals(property)) {
                warnings.add("duplicate key " + property.key() + ": " + existing.source() + " and "
                        + property.source() + " (differ in " + String.join(", ", differences(existing, property))
                        + ")");
                byKey.put(property.key(), withExtraAliases(existing, property));
            }
        }
        return List.copyOf(byKey.values());
    }

    private static List<String> differences(ConfigProperty a, ConfigProperty b) {
        Map<String, Boolean> same = new LinkedHashMap<>();
        same.put("name", a.name().equals(b.name()));
        same.put("aliases", Objects.equals(a.aliases(), b.aliases()));
        same.put("ext", Objects.equals(a.ext(), b.ext()));
        same.put("type", Objects.equals(a.type(), b.type()) && a.list() == b.list() && a.map() == b.map());
        same.put("optional", a.optional() == b.optional());
        same.put("default", Objects.equals(a.defaultValue(), b.defaultValue()));
        same.put("phase", Objects.equals(a.phase(), b.phase()));
        same.put("deprecated", Objects.equals(a.deprecated(), b.deprecated()));
        same.put("enum", Objects.equals(a.enumValues(), b.enumValues()));
        same.put("desc", Objects.equals(a.desc(), b.desc()));
        same.put("source", Objects.equals(a.source(), b.source()));
        return same.entrySet().stream().filter(e -> !e.getValue()).map(Map.Entry::getKey).toList();
    }

    /// The property with the other property's name and aliases added to its aliases (without duplicates and without its
    /// own name).
    public static ConfigProperty withExtraAliases(ConfigProperty property, ConfigProperty other) {
        Set<String> aliases = new LinkedHashSet<>(property.aliases());
        aliases.add(other.name());
        aliases.addAll(other.aliases());
        aliases.remove(property.name());
        return new ConfigProperty(property.name(), property.key(), List.copyOf(aliases), property.ext(),
                property.type(), property.list(), property.optional(), property.map(), property.defaultValue(),
                property.phase(), property.deprecated(), property.enumValues(), property.desc(), property.source());
    }

    /// Extension attribution for a config root found in a jar when the jar gives no better information: the jar's artifactId without a trailing `-deployment`.
    public static String extensionOf(String jarArtifactId) {
        return jarArtifactId.endsWith(DEPLOYMENT_SUFFIX)
                ? jarArtifactId.substring(0, jarArtifactId.length() - DEPLOYMENT_SUFFIX.length())
                : jarArtifactId;
    }
}
