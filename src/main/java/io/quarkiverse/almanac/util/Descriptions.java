package io.quarkiverse.almanac.util;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// Turns config documentation into the short descriptions shown on the site.
public final class Descriptions {

    /// Longest first sentence kept as-is; longer ones are hard-cut and end with an ellipsis.
    public static final int MAX_LENGTH = 300;

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern CODE_ELEMENT =
            Pattern.compile("<(code|tt|samp|kbd)>(.*?)</\\1\\s*>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern HTML_TAG = Pattern.compile("</?([a-zA-Z][a-zA-Z0-9]*)\\b[^<>]*>");
    private static final Pattern ENTITY = Pattern.compile("&(#\\d{1,7}|#[xX][0-9a-fA-F]{1,6}|[a-zA-Z]{2,8});");
    private static final Set<String> BLOCK_TAGS = Set.of("p", "ul", "ol", "li", "dl", "dt", "dd", "div", "pre",
            "blockquote", "table", "thead", "tbody", "tr", "td", "th", "h1", "h2", "h3", "h4", "h5", "h6", "hr");
    private static final Map<String, String> NAMED_ENTITIES = Map.of(
            "lt", "<", "gt", ">", "amp", "&", "quot", "\"", "apos", "'", "nbsp", " ");

    private Descriptions() {
    }

    /// The first sentence of a description: trimmed, cut after the first `.` that is followed by whitespace
    /// or ends the text, is not inside backticks and does not end the abbreviation `e.g.` or `i.e.`,
    /// with whitespace runs collapsed to one space. A result longer than {@value #MAX_LENGTH} characters is
    /// hard-cut there and ends with `…`. Asciidoc inline markup (backticks, `*bold*`) is kept as-is.
    ///
    /// @return the sentence, or `null` for a null or blank text
    public static String firstSentence(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String trimmed = text.strip();
        String sentence = WHITESPACE.matcher(trimmed.substring(0, sentenceEnd(trimmed))).replaceAll(" ");
        if (sentence.length() <= MAX_LENGTH) {
            return sentence;
        }
        int cut = Character.isHighSurrogate(sentence.charAt(MAX_LENGTH - 1)) ? MAX_LENGTH - 1 : MAX_LENGTH;
        return sentence.substring(0, cut).stripTrailing() + "…";
    }

    private static int sentenceEnd(String text) {
        boolean inCode = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '`') {
                inCode = !inCode;
            } else if (c == '.' && !inCode && (i + 1 == text.length() || Character.isWhitespace(text.charAt(i + 1)))
                    && !endsAbbreviation(text, i)) {
                return i + 1;
            }
        }
        return text.length();
    }

    /// Whether the period at `dot` ends a standalone `e.g.` or `i.e.`.
    private static boolean endsAbbreviation(String text, int dot) {
        int start = dot - 3;
        boolean abbreviation = start >= 0
                && (text.regionMatches(true, start, "e.g", 0, 3) || text.regionMatches(true, start, "i.e", 0, 3));
        return abbreviation && (start == 0 || !Character.isLetterOrDigit(text.charAt(start - 1)));
    }

    /// Converts raw Javadoc (as stored with format `JAVADOC` in `quarkus-config-javadoc.json`) into the
    /// asciidoc-like plain text used for every description: `{@code x}` and `<code>x</code>` become
    /// backtick spans, `{@link T label}` becomes the label (or `` `T` ``), block HTML elements become
    /// paragraph breaks, other tags are dropped and entities are decoded. Text already inside backticks is kept.
    public static String fromJavadoc(String javadoc) {
        if (javadoc == null) {
            return null;
        }
        String text = replaceJavadocTags(javadoc);
        text = CODE_ELEMENT.matcher(text).replaceAll(m ->
                Matcher.quoteReplacement("`" + decodeEntities(HTML_TAG.matcher(m.group(2)).replaceAll("")) + "`"));
        return convertHtmlOutsideCode(text);
    }

    /// Replaces Javadoc inline tags (`{@code x}`, `{@link T}`, …) as [#fromJavadoc] does, leaving
    /// everything else alone. Meant for asciidoc descriptions, where the doc processor sometimes leaves such tags.
    public static String replaceJavadocTags(String text) {
        if (text == null) {
            return null;
        }
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            int start = text.indexOf("{@", i);
            if (start < 0) {
                break;
            }
            int end = matchingBrace(text, start);
            if (end < 0) {
                break;
            }
            out.append(text, i, start);
            String body = text.substring(start + 2, end);
            int nameEnd = 0;
            while (nameEnd < body.length() && Character.isLetter(body.charAt(nameEnd))) {
                nameEnd++;
            }
            out.append(renderInlineTag(body.substring(0, nameEnd), body.substring(nameEnd).strip()));
            i = end + 1;
        }
        return out.append(text, i, text.length()).toString();
    }

    private static int matchingBrace(String text, int open) {
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    private static String renderInlineTag(String tag, String content) {
        return switch (tag) {
            case "code" -> "`" + content + "`";
            case "link", "linkplain" -> renderLink(content);
            case "value" -> content.isEmpty() ? "" : "`" + content + "`";
            case "inheritDoc", "docRoot" -> "";
            default -> content; // {@literal x} and unknown tags keep their text
        };
    }

    private static String renderLink(String content) {
        int depth = 0;
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (Character.isWhitespace(c) && depth == 0) {
                String label = content.substring(i).strip();
                if (!label.isEmpty()) {
                    return label;
                }
                break;
            }
        }
        String target = content.strip();
        return "`" + (target.startsWith("#") ? target.substring(1) : target) + "`";
    }

    private static String convertHtmlOutsideCode(String text) {
        StringBuilder out = new StringBuilder(text.length());
        boolean inCode = false;
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '`') {
                inCode = !inCode;
            } else if (!inCode && c == '<') {
                Matcher tag = HTML_TAG.matcher(text).region(i, text.length());
                if (tag.lookingAt()) {
                    String name = tag.group(1).toLowerCase(Locale.ROOT);
                    out.append(name.equals("br") ? "\n" : BLOCK_TAGS.contains(name) ? "\n\n" : "");
                    i = tag.end();
                    continue;
                }
            } else if (!inCode && c == '&') {
                Matcher entity = ENTITY.matcher(text).region(i, text.length());
                if (entity.lookingAt()) {
                    out.append(decodeEntity(entity.group(1), entity.group()));
                    i = entity.end();
                    continue;
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static String decodeEntities(String text) {
        return ENTITY.matcher(text).replaceAll(m -> Matcher.quoteReplacement(decodeEntity(m.group(1), m.group())));
    }

    private static String decodeEntity(String name, String original) {
        if (name.startsWith("#")) {
            boolean hex = name.length() > 1 && (name.charAt(1) == 'x' || name.charAt(1) == 'X');
            int codePoint = Integer.parseInt(name.substring(hex ? 2 : 1), hex ? 16 : 10);
            return Character.isValidCodePoint(codePoint) ? Character.toString(codePoint) : original;
        }
        return NAMED_ENTITIES.getOrDefault(name, original);
    }
}
