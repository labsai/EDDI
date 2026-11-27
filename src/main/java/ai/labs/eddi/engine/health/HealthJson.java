/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * JSON of the small Agent Health documents the stores keep as text — the health
 * profile above all — with a mapper of its own, so the stored shape does not
 * depend on the REST layer's mapper settings.
 */
public final class HealthJson {

    private static final ObjectMapper MAPPER = new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private HealthJson() {
    }

    public static String write(Object value) {
        try {
            return value == null ? null : MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new HealthStoreException("Could not write " + value.getClass().getSimpleName(), e);
        }
    }

    public static <T> T read(String json, Class<T> type) {
        try {
            return json == null ? null : MAPPER.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new HealthStoreException("Could not read " + type.getSimpleName(), e);
        }
    }
}
