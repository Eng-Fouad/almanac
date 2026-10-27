package io.quarkiverse.almanac.util;

import java.util.regex.Pattern;

/// Config property name helpers shared by every reader and mirrored in `site/assets/core.js`.
public final class ConfigKeys {

    private static final Pattern QUOTED_SEGMENT = Pattern.compile("\"[^\"]*\"");
    private static final Pattern INDEX = Pattern.compile("\\[\\d+]");

    private ConfigKeys() {
    }

    /// Canonical key of a display name: every double-quoted segment becomes `*` and every `[digits]`
    /// becomes `[*]`; nothing else changes.
    /// `quarkus.log.category."categories".level` → `quarkus.log.category.*.level`.
    public static String canonical(String name) {
        String key = QUOTED_SEGMENT.matcher(name).replaceAll("*");
        return INDEX.matcher(key).replaceAll("[*]");
    }

    /// Environment variable form of a property name: upper case, every character outside `[A-Za-z0-9]`
    /// replaced by `_`. `quarkus.hibernate-orm."pu".active` → `QUARKUS_HIBERNATE_ORM__PU__ACTIVE`.
    public static String envVar(String name) {
        StringBuilder env = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean alphanumeric = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
            env.append(alphanumeric ? Character.toUpperCase(c) : '_');
        }
        return env.toString();
    }
}
