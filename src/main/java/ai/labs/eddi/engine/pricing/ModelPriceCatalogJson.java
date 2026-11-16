/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.pricing;

import ai.labs.eddi.engine.pricing.model.ModelPriceCatalog;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;

/**
 * The price catalog's storage form, shared by the MongoDB and PostgreSQL stores
 * so both read and write exactly the same JSON. A private mapper, so the stored
 * shape does not move when the application's REST mapper is reconfigured.
 */
public final class ModelPriceCatalogJson {

    private static final ObjectMapper MAPPER = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private ModelPriceCatalogJson() {
    }

    public static String write(ModelPriceCatalog catalog) {
        try {
            return MAPPER.writeValueAsString(catalog);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize the model price catalog", e);
        }
    }

    public static ModelPriceCatalog read(String json) {
        try {
            return MAPPER.readValue(json, ModelPriceCatalog.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not read the stored model price catalog", e);
        }
    }

    public static ModelPriceCatalog read(InputStream in) throws IOException {
        return MAPPER.readValue(in, ModelPriceCatalog.class);
    }
}
