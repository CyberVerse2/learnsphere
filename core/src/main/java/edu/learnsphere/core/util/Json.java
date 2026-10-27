package edu.learnsphere.core.util;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.UncheckedIOException;

/**
 * The shared, thread-safe {@link ObjectMapper}. Records serialize by component name, {@code java.time}
 * values as ISO-8601 strings, unknown properties are ignored on read and nulls are omitted on write.
 */
public final class Json {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
            .defaultPropertyInclusion(JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.USE_DEFAULTS))
            .build();

    private Json() {
    }

    /** The shared mapper. Do not reconfigure it. */
    public static ObjectMapper mapper() {
        return MAPPER;
    }

    /** Serializes {@code value} to compact JSON. */
    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Serializes {@code value} to indented JSON. */
    public static String writePretty(Object value) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Parses {@code json} into {@code type}. */
    public static <T> T read(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Parses {@code json} into a generic type, e.g. {@code new TypeReference<List<Citation>>() {}}. */
    public static <T> T read(String json, TypeReference<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Parses {@code json} into a tree. */
    public static JsonNode tree(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }
}
