package io.quarkiverse.almanac.verify;

import io.quarkiverse.almanac.model.ConfigProperty;
import io.quarkiverse.almanac.model.ConfigSnapshot;
import io.quarkiverse.almanac.util.ConfigKeys;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/// Tiny synthetic snapshots and the HTML page fixtures of `src/test/resources/verify`.
final class Fixtures {

    private Fixtures() {
    }

    /// A property builder with neutral defaults: ext quarkus-core, type string, RUN_TIME.
    static Prop prop(String name) {
        return new Prop(name);
    }

    static ConfigSnapshot snapshot(String version, String source, ConfigProperty... properties) {
        List<ConfigProperty> sorted = Arrays.stream(properties).sorted(Comparator.comparing(ConfigProperty::key))
                .toList();
        return new ConfigSnapshot(version, source, "test", 1, List.of(), sorted);
    }

    /// A page fixture as text.
    static String html(String name) {
        try (InputStream in = Fixtures.class.getResourceAsStream("/verify/" + name)) {
            if (in == null) {
                throw new IllegalArgumentException("no fixture " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static OfficialPage page(String name) {
        return OfficialPageParser.parse(html(name));
    }

    static final class Prop {

        private final String name;
        private List<String> aliases = List.of();
        private String ext = "quarkus-core";
        private String type = "string";
        private boolean list;
        private boolean optional;
        private boolean map;
        private String defaultValue;
        private String phase = "RUN_TIME";
        private String deprecated;
        private List<String> enumValues;
        private String desc;

        private Prop(String name) {
            this.name = name;
        }

        Prop aliases(String... aliases) {
            this.aliases = List.of(aliases);
            return this;
        }

        Prop ext(String ext) {
            this.ext = ext;
            return this;
        }

        Prop type(String type) {
            this.type = type;
            return this;
        }

        Prop list() {
            this.list = true;
            return this;
        }

        Prop optional() {
            this.optional = true;
            return this;
        }

        Prop map() {
            this.map = true;
            this.optional = true;
            return this;
        }

        Prop defaultValue(String defaultValue) {
            this.defaultValue = defaultValue;
            return this;
        }

        Prop phase(String phase) {
            this.phase = phase;
            return this;
        }

        Prop deprecated(String since) {
            this.deprecated = since;
            return this;
        }

        Prop enumValues(String... values) {
            this.enumValues = List.of(values);
            return this;
        }

        Prop desc(String desc) {
            this.desc = desc;
            return this;
        }

        ConfigProperty build() {
            return new ConfigProperty(name, ConfigKeys.canonical(name), aliases, ext, type, list, optional, map,
                    defaultValue, phase, deprecated, enumValues, desc, "test.Config#" + name);
        }
    }
}
