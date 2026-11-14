/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.spendcontrol;

import ai.labs.eddi.engine.spendcontrol.model.SpendControlChange;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The storage form of a {@link SpendControlChange}, shared by both stores — a
 * private mapper, as {@code ModelPriceCatalogJson} keeps one, so the stored
 * shape does not move with the REST mapper's configuration.
 */
public final class SpendControlChangeJson {

    private static final ObjectMapper MAPPER = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private SpendControlChangeJson() {
    }

    public static String write(SpendControlChange change) {
        try {
            return MAPPER.writeValueAsString(change);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize the spend-control change", e);
        }
    }

    public static SpendControlChange read(String json) {
        try {
            return MAPPER.readValue(json, SpendControlChange.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not read a stored spend-control change", e);
        }
    }

    /** Canonical JSON of any value, for content hashes. */
    static String canonical(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize a value", e);
        }
    }
}
