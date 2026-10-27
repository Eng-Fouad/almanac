package io.quarkiverse.almanac.verify;

import io.quarkiverse.almanac.verify.OfficialPage.Documented;
import io.quarkiverse.almanac.verify.OfficialPage.Generator;
import io.quarkiverse.almanac.verify.OfficialPage.Row;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.TextNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// Reads a quarkus.io "all config" page with jsoup. Both generators render one
/// `table.configuration-reference`; each property is a `<tr>` with three cells:
///
/// 1. a paragraph with the optional lock icon (`i.fa-lock`, "Fixed at build time"), the anchor and the
///    primary path (`span.property-path code` in the new generator, `code > a` in the old one), then
///    one paragraph per alternative path (`code`, in the old generator `code > a`), then the
///    description block (`div.description`) holding the environment variable (`code[id^=env-var]`)
///    and, for deprecated rows, a first paragraph "_This property is deprecated \[since X\]…_";
/// 2. the type: text such as `list of string` or `Duration`, or the accepted enum values as
///    `code` elements, each optionally followed by a tooltip with the constant's description;
/// 3. the default: text or code, `required` with an icon, or empty.
///
/// Header rows (`th`: extension names and sections) are skipped, and only direct rows of the configuration
/// table are read, so admonition tables nested in descriptions cannot break a row.
///
/// The pages write some values unescaped inside `<code>`: the default `<default>` or the map key
/// `"<identifier>"` in `quarkus.fault-tolerance."<identifier>".…`. Browsers (and jsoup) read them as
/// unknown elements and show nothing; the parser turns every unknown element of a row back into its tag text.
public final class OfficialPageParser {

    private static final Pattern VERSION =
            Pattern.compile("\\b(\\d+)\\.(\\d+)((?:\\.\\d+)*)((?:[.-](?:Final|CR\\d*|Beta\\d*|Alpha\\d*))?)\\b");
    private static final Pattern URL_STREAM = Pattern.compile("/version/(\\d+\\.\\d+)/");
    private static final Pattern SINCE = Pattern.compile("^since\\s+(\\S+?)[:.]?(?:\\s|$)");
    private static final String DEPRECATION_NOTICE = "This property is deprecated";
    private static final String TOOLTIP = "span.asciidoc-tooltip";
    private static final String REQUIRED_ICON = "i[title=\"Configuration property is required\"]";
    private static final String CONFIG_TABLE = "table.configuration-reference";

    private OfficialPageParser() {
    }

    /// Parses a whole page; a page without configuration rows gives an empty row list.
    public static OfficialPage parse(String html) {
        Document document = Jsoup.parse(html);
        List<Element> tables = document.select(CONFIG_TABLE).stream()
                .filter(table -> table.parents().stream().noneMatch(parent -> parent.is(CONFIG_TABLE)))
                .toList();
        boolean newGenerator = tables.stream().anyMatch(table -> table.selectFirst("span.property-path") != null);
        List<Row> rows = new ArrayList<>();
        for (Element table : tables) {
            for (Element tr : rows(table)) {
                row(tr).ifPresent(rows::add);
            }
        }
        return new OfficialPage(newGenerator ? Generator.NEW : Generator.OLD, documented(document), List.copyOf(rows));
    }

    /// The release line the page documents: the selected entry of the guide version selector (e.g. `3.20` or
    /// `3.40 (latest)`), else the `og:title` ("All configuration options - 3.20"), else the
    /// `og:url` path (`/version/3.20/`); null when none of them names one. The pages seen name only the
    /// stream (major.minor), never the patch release they were built from.
    static Documented documented(Document document) {
        Element selected = document.selectFirst("select#guide-version-dropdown option[selected]");
        if (selected != null) {
            Documented documented = fromText(selected.text(), "version selector");
            if (documented != null) {
                return documented;
            }
        }
        Element title = document.selectFirst("meta[property=og:title]");
        if (title != null) {
            Documented documented = fromText(title.attr("content"), "page title");
            if (documented != null) {
                return documented;
            }
        }
        Element url = document.selectFirst("meta[property=og:url]");
        if (url != null) {
            Matcher m = URL_STREAM.matcher(url.attr("content"));
            if (m.find()) {
                return new Documented(m.group(1), null, "page URL");
            }
        }
        return null;
    }

    private static Documented fromText(String text, String source) {
        Matcher m = VERSION.matcher(text);
        if (!m.find()) {
            return null;
        }
        boolean full = !m.group(3).isEmpty();
        return new Documented(m.group(1) + "." + m.group(2), full ? m.group() : null, source);
    }

    private static List<Element> rows(Element table) {
        List<Element> rows = new ArrayList<>();
        for (Element child : table.children()) {
            switch (child.normalName()) {
                case "tbody", "thead", "tfoot" -> child.children().stream()
                        .filter(e -> e.normalName().equals("tr"))
                        .forEach(rows::add);
                case "tr" -> rows.add(child);
                default -> {
                }
            }
        }
        return rows;
    }

    private static Optional<Row> row(Element tr) {
        List<Element> cells = tr.children().stream().filter(e -> e.normalName().equals("td")).toList();
        if (cells.isEmpty()) {
            return Optional.empty();
        }
        restoreUnknownTags(tr);
        Element first = cells.getFirst();
        Element content = first.children().stream().filter(e -> e.hasClass("content")).findFirst().orElse(first);
        Element head = null;
        Element description = null;
        List<String> alternatives = new ArrayList<>();
        for (Element block : content.children()) {
            if (block.hasClass("description") || block.hasClass("description-decoration")) {
                description = block.hasClass("description") ? block : null;
                break;
            }
            if (!block.hasClass("paragraph")) {
                continue;
            }
            if (head == null) {
                if (pathCode(block) != null) {
                    head = block;
                }
                continue;
            }
            for (Element code : block.select("code")) {
                String alternative = code.text().strip();
                if (isPath(alternative)) {
                    alternatives.add(alternative);
                }
            }
        }
        if (head == null) {
            return Optional.empty();
        }
        String name = pathCode(head).text().strip();
        if (!isPath(name)) {
            return Optional.empty();
        }
        Element link = head.selectFirst("a[href^=#]");
        Element target = head.selectFirst("a[id]");
        String anchor = link != null ? link.attr("href").substring(1) : target != null ? target.id() : null;
        boolean buildTime = head.selectFirst("i.fa-lock") != null;
        Element env = first.selectFirst("code[id^=env-var]");
        Element typeCell = cells.size() > 1 ? withoutTooltips(cells.get(1)) : null;
        Element defaultCell = cells.size() > 2 ? withoutTooltips(cells.get(2)) : null;
        boolean required = defaultCell != null
                && (defaultCell.selectFirst(REQUIRED_ICON) != null || defaultCell.text().strip().equals("required"));
        String defaultValue = defaultCell == null || required ? null : emptyToNull(defaultCell.text());
        List<String> typeCodes = typeCell == null
                ? List.of()
                : typeCell.select("code").stream().map(e -> e.text().strip()).filter(s -> !s.isEmpty()).toList();
        return Optional.of(new Row(name, List.copyOf(alternatives), anchor, buildTime,
                typeCell == null ? null : emptyToNull(typeCell.text()), typeCodes, defaultValue, required,
                env == null ? null : emptyToNull(env.text()), deprecation(head, description)));
    }

    /// The code element holding the primary path: new generator first, then the old `code > a` form.
    private static Element pathCode(Element paragraph) {
        Element code = paragraph.selectFirst("span.property-path code");
        if (code == null) {
            Element link = paragraph.selectFirst("code > a[href^=#]");
            code = link != null ? link.parent() : null;
        }
        return code;
    }

    /// Deprecation of a row (new generator, 3.33+): the path is struck through (`span.line-through`) and the
    /// description starts with "_This property is deprecated since `3.31`: …_" or "_This property
    /// is deprecated: …_". Returns the since text, `""` without one, null when the row is not deprecated.
    private static String deprecation(Element head, Element description) {
        Element notice = description == null ? null : description.select("em").stream()
                .filter(em -> em.text().strip().startsWith(DEPRECATION_NOTICE))
                .findFirst()
                .orElse(null);
        if (notice == null) {
            return head.selectFirst(".line-through") != null ? "" : null;
        }
        String rest = notice.text().strip().substring(DEPRECATION_NOTICE.length()).strip();
        if (!rest.startsWith("since")) {
            return "";
        }
        Element code = notice.selectFirst("code");
        if (code != null && !code.text().isBlank()) {
            return code.text().strip();
        }
        Matcher m = SINCE.matcher(rest);
        return m.find() ? m.group(1) : "";
    }

    /// Replaces every unknown element below `root` (an unescaped `<default>` read as a tag) by its tag
    /// text, keeping its content after it: `<empty set>` becomes the text `<empty set>`.
    private static void restoreUnknownTags(Element root) {
        for (Element element : root.select("*")) {
            if (element == root || element.tag().isKnownTag()) {
                continue;
            }
            StringBuilder text = new StringBuilder("<").append(element.tagName());
            for (Attribute attribute : element.attributes()) {
                text.append(' ').append(attribute.getKey());
                if (!attribute.getValue().isEmpty()) {
                    text.append("=\"").append(attribute.getValue()).append('"');
                }
            }
            element.before(new TextNode(text.append('>').toString()));
            element.unwrap();
        }
    }

    /// A displayed path: non-empty, one token, dotted.
    private static boolean isPath(String text) {
        return !text.isEmpty() && text.indexOf('.') > 0 && text.chars().noneMatch(Character::isWhitespace);
    }

    private static Element withoutTooltips(Element cell) {
        Element copy = cell.clone();
        copy.select(TOOLTIP).remove();
        return copy;
    }

    private static String emptyToNull(String text) {
        String stripped = text == null ? "" : text.strip();
        return stripped.isEmpty() ? null : stripped;
    }
}
