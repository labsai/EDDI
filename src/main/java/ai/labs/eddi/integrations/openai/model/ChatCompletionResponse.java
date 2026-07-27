/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.openai.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * A non-streaming {@code chat.completion} response.
 * <p>
 * {@code usage} is deliberately absent: EDDI does not surface per-request token
 * counts to this layer, and emitting zeros would render as a factual "0 tokens"
 * in clients. Omission is the honest encoding; clients tolerate it.
 *
 * @since 6.1.0
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatCompletionResponse(String id,
        String object,
        long created,
        String model,
        List<Choice> choices) {

    public static final String OBJECT_TYPE = "chat.completion";

    public static ChatCompletionResponse of(String id, String model, long createdEpochSeconds, Choice choice) {
        return new ChatCompletionResponse(id, OBJECT_TYPE, createdEpochSeconds, model, List.of(choice));
    }
}
