/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.branding;

import ai.labs.eddi.configs.branding.model.BrandDocument;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The brand document's storage form, shared by the MongoDB and PostgreSQL
 * stores so both read and write exactly the same JSON. A private mapper: the
 * document is a handful of strings and booleans, and must not change shape when
 * the application's REST mapper is reconfigured.
 */
public final class BrandingJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private BrandingJson() {
    }

    public static String write(BrandDocument document) {
        try {
            return MAPPER.writeValueAsString(document);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize the brand document", e);
        }
    }

    public static BrandDocument read(String json) {
        try {
            return MAPPER.readValue(json, BrandDocument.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not read the stored brand document", e);
        }
    }
}
