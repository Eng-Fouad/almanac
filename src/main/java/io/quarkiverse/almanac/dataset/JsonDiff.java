package io.quarkiverse.almanac.dataset;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkiverse.almanac.util.Json;

import java.util.Optional;
import java.util.TreeSet;

/// Names the first difference between two JSON values, for messages about snapshots that do not match. Object fields
/// compare by name (in any order), arrays element by element.
final class JsonDiff {

    private static final int MAX_VALUE_LENGTH = 120;

    private JsonDiff() {
    }

    /// The first difference between two records compared as JSON, e.g. `/deps/12/v: "4.4.1" vs "4.4.2"`; empty when
    /// they serialise equally.
    static Optional<String> of(Object a, Object b) {
        return of(Json.mapper().valueToTree(a), Json.mapper().valueToTree(b));
    }

    /// The first difference between two JSON trees; empty when they are equal.
    static Optional<String> of(JsonNode a, JsonNode b) {
        return Optional.ofNullable(first(a, b, ""));
    }

    private static String first(JsonNode a, JsonNode b, String path) {
        if (a == null || b == null || a.getNodeType() != b.getNodeType()) {
            return describe(path, a, b);
        }
        if (a.isObject()) {
            TreeSet<String> fields = new TreeSet<>();
            a.fieldNames().forEachRemaining(fields::add);
            b.fieldNames().forEachRemaining(fields::add);
            for (String field : fields) {
                String difference = first(a.get(field), b.get(field), path + "/" + field);
                if (difference != null) {
                    return difference;
                }
            }
            return null;
        }
        if (a.isArray()) {
            for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
                String difference = first(a.get(i), b.get(i), path + "/" + i);
                if (difference != null) {
                    return difference;
                }
            }
            return a.size() == b.size() ? null : (path.isEmpty() ? "/" : path) + ": " + a.size() + " vs " + b.size()
                    + " elements";
        }
        return a.equals(b) ? null : describe(path, a, b);
    }

    private static String describe(String path, JsonNode a, JsonNode b) {
        return (path.isEmpty() ? "/" : path) + ": " + text(a) + " vs " + text(b);
    }

    private static String text(JsonNode node) {
        if (node == null) {
            return "(absent)";
        }
        String text = node.toString();
        return text.length() > MAX_VALUE_LENGTH ? text.substring(0, MAX_VALUE_LENGTH) + "..." : text;
    }
}
