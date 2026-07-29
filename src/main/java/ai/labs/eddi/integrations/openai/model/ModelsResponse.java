/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.openai.model;

import java.util.List;

/**
 * The {@code GET /v1/models} envelope.
 *
 * @since 6.1.0
 */
public record ModelsResponse(String object, List<ModelObject> data) {

    public static final String OBJECT_TYPE = "list";

    public static ModelsResponse of(List<ModelObject> models) {
        return new ModelsResponse(OBJECT_TYPE, models);
    }
}
