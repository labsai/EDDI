/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.oversight;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * The JSON form the oversight stores keep their documents in (the {@code data}
 * column / field next to the indexed columns). One mapper for both backends, so
 * a round trip is the same on MongoDB and PostgreSQL and is unit-testable
 * without a database: instants as ISO-8601 strings, nulls omitted, unknown
 * fields ignored (a newer node's document stays readable by an older one).
 *
 * @since 6.7.0
 */
public final class OversightJson {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private OversightJson() {
    }

    /** The JSON of a value. */
    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize " + value.getClass().getSimpleName(), e);
        }
    }

    /** A value read back from its JSON. */
    public static <T> T read(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not read a stored " + type.getSimpleName(), e);
        }
    }

    /** A deep copy through JSON (for configuration objects). */
    public static <T> T copy(Object value, Class<T> type) {
        return value == null ? null : MAPPER.convertValue(value, type);
    }

    /** The shared mapper (read-only use). */
    public static ObjectMapper mapper() {
        return MAPPER;
    }
}
