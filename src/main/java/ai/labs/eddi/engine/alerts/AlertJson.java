/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.alerts;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.util.List;
import java.util.Map;

/**
 * The one JSON mapping the alert stores use for their nested values (scope,
 * condition, delivery targets, deliveries, match) — instants as ISO-8601
 * strings, unknown fields ignored so a document written by a newer version
 * still reads.
 */
public final class AlertJson {

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };
    private static final TypeReference<List<Object>> LIST = new TypeReference<>() {
    };

    private AlertJson() {
    }

    public static Map<String, Object> toMap(Object value) {
        return value == null ? null : MAPPER.convertValue(value, MAP);
    }

    public static List<Object> toList(Object value) {
        return value == null ? null : MAPPER.convertValue(value, LIST);
    }

    public static <T> T convert(Object value, Class<T> type) {
        return value == null ? null : MAPPER.convertValue(value, type);
    }

    public static <T> T convert(Object value, TypeReference<T> type) {
        return value == null ? null : MAPPER.convertValue(value, type);
    }

    public static String write(Object value) {
        try {
            return value == null ? null : MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize " + value.getClass().getSimpleName(), e);
        }
    }

    public static <T> T read(String json, Class<T> type) {
        try {
            return json == null ? null : MAPPER.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not read " + type.getSimpleName(), e);
        }
    }

    public static <T> T read(String json, TypeReference<T> type) {
        try {
            return json == null ? null : MAPPER.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not read JSON", e);
        }
    }
}
