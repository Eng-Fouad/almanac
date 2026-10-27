package io.quarkiverse.almanac.verify;

import java.util.ArrayList;
import java.util.List;

/// A parsed quarkus.io "all config" page.
///
/// @param generator  which documentation generator rendered the page
/// @param documented the release line the page says it documents, null when it does not say
/// @param rows       one entry per property row, in page order
public record OfficialPage(Generator generator, Documented documented, List<Row> rows) {

    /// The documentation generators seen on quarkus.io.
    public enum Generator {
        /// `generate_doc` (streams ≤ 3.13, e.g. 3.2 and 3.8): `<code><a href="#…">PATH</a></code>`; leaf
        /// maps are named without their key segment; deprecated properties are not listed.
        OLD,
        /// The config doc processor (3.14+): `span.property-path`. Deprecated properties are not listed up to
        /// 3.27; from 3.33 on some are, struck through (`span.line-through`).
        NEW
    }

    /// What the page says about the release it documents.
    ///
    /// @param stream  `major.minor`, e.g. `3.20`
    /// @param version the full version when the page names one, else null (the pages checked name only the stream)
    /// @param source  where it was read: `version selector`, `page title` or `page URL`
    public record Documented(String stream, String version, String source) {
    }

    /// One property row.
    ///
    /// @param name         the primary path as displayed
    /// @param alternatives other paths displayed in the same row (named-map variants), may be empty
    /// @param anchor       the row anchor (`<ext>_<slug>`), null when absent
    /// @param buildTime    the lock icon "Fixed at build time" is shown
    /// @param type         text of the type cell (tooltips removed, whitespace collapsed), null when empty
    /// @param typeCodes    code values of the type cell outside tooltips (enum values), may be empty
    /// @param defaultValue text of the default cell (tooltips removed), null when empty or "required"
    /// @param required     the default cell says "required"
    /// @param envVar       the environment variable shown in the description, null when absent
    /// @param deprecated   null when the row is not marked deprecated, otherwise the "since" text of the deprecation
    ///                     notice (`3.31`), or `""` when it names none
    public record Row(String name, List<String> alternatives, String anchor, boolean buildTime, String type,
            List<String> typeCodes, String defaultValue, boolean required, String envVar, String deprecated) {

        /// Extension part of the anchor (before the first `_`), null without anchor.
        public String anchorExtension() {
            if (anchor == null) {
                return null;
            }
            int underscore = anchor.indexOf('_');
            return underscore > 0 ? anchor.substring(0, underscore) : null;
        }

        /// The primary path followed by the alternatives.
        public List<String> paths() {
            if (alternatives.isEmpty()) {
                return List.of(name);
            }
            List<String> paths = new ArrayList<>(alternatives.size() + 1);
            paths.add(name);
            paths.addAll(alternatives);
            return List.copyOf(paths);
        }
    }

    /// Number of displayed paths (primary and alternatives).
    public int names() {
        return rows.stream().mapToInt(row -> 1 + row.alternatives().size()).sum();
    }
}
