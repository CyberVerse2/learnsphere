package edu.learnsphere.ai.prompt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.learnsphere.core.util.Json;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON schemas for structured output. The server turns a schema into a grammar, so replies always parse;
 * properties are generated in the order they are declared, which lets a schema make the model decide one
 * thing (say, the evidence) before another (the verdict).
 */
public final class JsonSchemas {

    private JsonSchemas() {
    }

    /** An object with these properties, in order, all required, nothing else allowed. */
    public static ObjectNode object(Map<String, JsonNode> properties) {
        ObjectNode schema = Json.mapper().createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        ArrayNode required = schema.putArray("required");
        properties.forEach((name, type) -> {
            props.set(name, type);
            required.add(name);
        });
        schema.put("additionalProperties", false);
        return schema;
    }

    /** Ordered properties for {@link #object}. */
    public static Map<String, JsonNode> properties() {
        return new LinkedHashMap<>();
    }

    /** Any string. */
    public static ObjectNode string() {
        ObjectNode schema = Json.mapper().createObjectNode();
        schema.put("type", "string");
        return schema;
    }

    /** One of these strings. */
    public static ObjectNode oneOf(List<String> values) {
        ObjectNode schema = Json.mapper().createObjectNode();
        ArrayNode options = schema.putArray("enum");
        values.forEach(options::add);
        return schema;
    }

    /** Exactly this string. */
    public static ObjectNode constant(String value) {
        ObjectNode schema = Json.mapper().createObjectNode();
        schema.put("const", value);
        return schema;
    }

    /** One of these integers. */
    public static ObjectNode integerIn(List<Integer> values) {
        ObjectNode schema = Json.mapper().createObjectNode();
        schema.put("type", "integer");
        ArrayNode options = schema.putArray("enum");
        values.forEach(options::add);
        return schema;
    }

    /** An array of {@code items} with {@code min} to {@code max} entries. */
    public static ObjectNode array(JsonNode items, int min, int max) {
        ObjectNode schema = Json.mapper().createObjectNode();
        schema.put("type", "array");
        schema.set("items", items);
        schema.put("minItems", min);
        schema.put("maxItems", max);
        return schema;
    }

    /** An array of exactly these entries, in order. */
    public static ObjectNode tuple(List<? extends JsonNode> entries) {
        ObjectNode schema = Json.mapper().createObjectNode();
        schema.put("type", "array");
        ArrayNode prefix = schema.putArray("prefixItems");
        entries.forEach(prefix::add);
        schema.put("minItems", entries.size());
        schema.put("maxItems", entries.size());
        return schema;
    }

    /** {@code {"concepts": [{"name", "description"}]}} with {@code min} to {@code max} concepts. */
    public static ObjectNode concepts(int min, int max) {
        Map<String, JsonNode> concept = properties();
        concept.put("name", string());
        concept.put("description", string());
        Map<String, JsonNode> root = properties();
        root.put("concepts", array(object(concept), min, max));
        return object(root);
    }

    /** {@code {"evidence", "verdict": supported|not_supported, "note"}}. */
    public static ObjectNode support() {
        Map<String, JsonNode> root = properties();
        root.put("evidence", string());
        root.put("verdict", oneOf(List.of("supported", "not_supported")));
        root.put("note", string());
        return object(root);
    }

    /** {@code {"points": [{"point", "status"}], "errors": [...], "feedback"}}. */
    public static ObjectNode grade() {
        Map<String, JsonNode> point = properties();
        point.put("point", string());
        point.put("status", oneOf(List.of("covered", "partly", "missing")));
        Map<String, JsonNode> root = properties();
        root.put("points", array(object(point), 1, 4));
        root.put("errors", array(string(), 0, 3));
        root.put("feedback", string());
        return object(root);
    }

    /** {@code {"figures": [{"description"}]}} with at most {@code max} figures. */
    public static ObjectNode figures(int max) {
        Map<String, JsonNode> figure = properties();
        figure.put("description", string());
        Map<String, JsonNode> root = properties();
        root.put("figures", array(object(figure), 0, max));
        return object(root);
    }
}
