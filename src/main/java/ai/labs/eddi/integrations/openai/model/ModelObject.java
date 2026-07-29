/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.openai.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One entry of {@code GET /v1/models}. {@code id} is the adapter's model id
 * (see {@code AgentModelResolver}), which is what clients echo back in the
 * {@code model} field of a completion request.
 *
 * @since 6.1.0
 */
public record ModelObject(String id,
        String object,
        long created,
        @JsonProperty("owned_by") String ownedBy) {

    public static final String OBJECT_TYPE = "model";
    public static final String OWNER = "eddi";

    public static ModelObject of(String id, long createdEpochSeconds) {
        return new ModelObject(id, OBJECT_TYPE, createdEpochSeconds, OWNER);
    }
}
