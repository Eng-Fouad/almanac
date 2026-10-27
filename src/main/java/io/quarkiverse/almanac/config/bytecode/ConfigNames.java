package io.quarkiverse.almanac.config.bytecode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/// Naming rules of the Quarkus config documentation processor, ported from `ConfigNamingUtil` and `TypeUtil` of
/// quarkus-extension-processor 3.25.
final class ConfigNames {

    /// `@ConfigItem(name)` / `@ConfigRoot(name)` marker and `@WithParentName`: the parent path.
    static final String PARENT = "<<parent>>";
    /// Default of `@ConfigItem(name)` and `@ConfigRoot(name)`: derive the name from the element.
    static final String HYPHENATED_ELEMENT_NAME = "<<hyphenated element name>>";
    /// Default of `@ConfigItem(defaultValue)`.
    static final String NO_DEFAULT = "<<no default>>";
    /// Prefix of roots that do not declare one.
    static final String DEFAULT_PREFIX = "quarkus";

    private static final Pattern ENUM_SEPARATORS = Pattern.compile("[-_]+");

    private ConfigNames() {
    }

    /// The processor's `hyphenate`: the camel-case words of a name, lower-cased and joined with `-`. `connectionTTL` →
    /// `connection-ttl`, `IPv4Address` → `i-pv4-address`, `FOO_BAR` → `foo_bar` (`_`, `-` and `.` never split a word).
    static String hyphenate(String name) {
        List<String> words = words(name);
        StringBuilder out = new StringBuilder(name.length() + words.size());
        for (String word : words) {
            if (!out.isEmpty()) {
                out.append('-');
            }
            out.append(word.toLowerCase(Locale.ROOT));
        }
        return out.toString();
    }

    /// Splits a name into words like `ConfigNamingUtil#camelHumpsIterator`: a word starts at an upper-case letter; a
    /// run of capitals is one word whose last capital starts the next word when a lower-case letter follows; digits are
    /// neither upper nor lower case; `JBoss` is always one word.
    static List<String> words(String name) {
        List<String> words = new ArrayList<>();
        int length = name.length();
        int i = 0;
        while (i < length) {
            if (name.startsWith("JBoss", i)) {
                words.add("JBoss");
                i += 5;
                continue;
            }
            int start = i;
            i++;
            if (Character.isUpperCase(name.charAt(start)) && i < length && Character.isUpperCase(name.charAt(i))) {
                // all-caps run: ends before the capital that is followed by a lower-case letter
                int next = i + 1;
                boolean ended = false;
                while (next < length) {
                    if (Character.isLowerCase(name.charAt(next))) {
                        ended = true;
                        break;
                    }
                    i = next;
                    next++;
                }
                if (!ended) {
                    i = length;
                }
                words.add(name.substring(start, i));
                continue;
            }
            // capitalised or lower-case word: up to the next upper-case letter
            while (i < length && !Character.isUpperCase(name.charAt(i))) {
                i++;
            }
            words.add(name.substring(start, i));
        }
        return words;
    }

    /// The processor's `hyphenateEnumValue`: [#hyphenate] with every run of `-` and `_` replaced by one `-`.
    /// `READ_UNCOMMITTED` → `read-uncommitted`, `ClusterIP` → `cluster-ip`, `TLSv1_2` → `tl-sv1-2`.
    static String hyphenateEnumValue(String name) {
        return ENUM_SEPARATORS.matcher(hyphenate(name)).replaceAll("-");
    }

    /// `TypeUtil#normalizeDurationValue`, applied to `Duration` defaults: a value ending with a digit that parses as an
    /// int gets the `S` unit, then everything is upper-cased (`10s` → `10S`, `60` → `60S`, `PT1M` unchanged).
    static String normalizeDuration(String value) {
        String normalized = value;
        if (!normalized.isEmpty() && Character.isDigit(normalized.charAt(normalized.length() - 1))) {
            try {
                normalized = Integer.parseInt(normalized) + "S";
            } catch (NumberFormatException e) {
                // keep the value: only plain seconds get a unit
            }
        }
        return normalized.toUpperCase(Locale.ROOT);
    }

    /// `ConfigNamingUtil#getRootPrefix`: the path prefix of a config root.
    ///
    /// @param prefix          `@ConfigRoot(prefix)` or `@ConfigMapping(prefix)`
    /// @param name            `@ConfigRoot(name)` for legacy roots, `""` for mapping roots
    /// @param simpleClassName the root's simple (innermost) class name
    /// @param phase           the root's phase, which selects the class name suffix to strip
    static String rootPrefix(String prefix, String name, String simpleClassName, String phase) {
        String rootPrefix;
        if (name.equals(HYPHENATED_ELEMENT_NAME)) {
            rootPrefix = deriveRootName(simpleClassName, prefix, phase);
        } else if (!prefix.isEmpty()) {
            rootPrefix = name.isEmpty() ? prefix : prefix + "." + name;
        } else {
            rootPrefix = name;
        }
        if (rootPrefix.endsWith("." + PARENT)) {
            rootPrefix = rootPrefix.replace("." + PARENT, "");
        }
        return rootPrefix;
    }

    /// `ConfigNamingUtil#deriveConfigRootName`: strips `Config`, `Configuration` and the phase suffix (`RunTime` or
    /// `BuildTime`, case-insensitively, repeatedly) from the class name and hyphenates the rest. `BannerRuntimeConfig`
    /// (RUN_TIME) → `quarkus.banner`.
    static String deriveRootName(String simpleClassName, String prefix, String phase) {
        String lower = simpleClassName.toLowerCase(Locale.ROOT);
        int length = simpleClassName.length();
        String suffix = phaseSuffix(phase);
        if (lower.endsWith("config")) {
            return deriveRootName(simpleClassName.substring(0, length - 6), prefix, phase);
        }
        if (lower.endsWith("configuration")) {
            return deriveRootName(simpleClassName.substring(0, length - 13), prefix, phase);
        }
        if (lower.endsWith(suffix.toLowerCase(Locale.ROOT))) {
            return deriveRootName(simpleClassName.substring(0, length - suffix.length()), prefix, phase);
        }
        return (prefix.isEmpty() ? DEFAULT_PREFIX : prefix) + "." + hyphenate(simpleClassName);
    }

    /// Class name suffix of a phase: `RunTime` for RUN_TIME, `BuildTime` for the build phases and, as in the pre-3.14
    /// processor, `Bootstrap` for BOOTSTRAP (which the 3.25 processor no longer knows).
    static String phaseSuffix(String phase) {
        return switch (phase) {
            case "RUN_TIME" -> "RunTime";
            case "BOOTSTRAP" -> "Bootstrap";
            default -> "BuildTime";
        };
    }

    /// `ConfigResolver#appendPath`: `<<parent>>` adds nothing, any other name adds `.name`.
    static String appendPath(String parent, String name) {
        return PARENT.equals(name) ? parent : parent + "." + name;
    }

    /// `ConfigNamingUtil#getMapKey`: the path segment of a map key, `."key"`.
    static String mapKey(String key) {
        return ".\"" + key + "\"";
    }
}
