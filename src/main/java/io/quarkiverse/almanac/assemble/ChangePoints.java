package io.quarkiverse.almanac.assemble;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/// Per-id histories as change points `[vi, value]`: a value holds from its version index until
/// the next point, `null` marks absence, and nothing is recorded before an id first appears.
final class ChangePoints {

    private record Point(int index, JsonNode value) {
    }

    private final Map<String, List<Point>> histories = new TreeMap<>();

    /// Records the values present at version index `vi`; ids seen before but absent now get a `null`
    /// point. Version indices must be observed in ascending order.
    void observe(int vi, Map<String, ? extends JsonNode> present) {
        present.forEach((id, value) -> {
            List<Point> history = histories.computeIfAbsent(id, k -> new ArrayList<>());
            if (history.isEmpty() || !Objects.equals(history.getLast().value(), value)) {
                history.add(new Point(vi, value));
            }
        });
        histories.forEach((id, history) -> {
            if (!present.containsKey(id) && history.getLast().value() != null) {
                history.add(new Point(vi, null));
            }
        });
    }

    int size() {
        return histories.size();
    }

    /// `[{idField: id, "h": [[vi, value], …]}, …]` sorted by id.
    ArrayNode toJson(String idField) {
        ArrayNode result = JsonNodeFactory.instance.arrayNode();
        histories.forEach((id, history) -> {
            ObjectNode item = result.addObject().put(idField, id);
            ArrayNode points = item.putArray("h");
            for (Point point : history) {
                points.addArray().add(point.index()).add(point.value() == null ? JsonNodeFactory.instance.nullNode()
                        : point.value());
            }
        });
        return result;
    }
}
