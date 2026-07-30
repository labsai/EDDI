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
 * {@code usage} is populated from the turn's {@code audit:token_usage} entry
 * when the agent called a model, and omitted entirely when it did not — a
 * rule-based agent spends no tokens, and reporting zeros would read in a client
 * as a measurement rather than as an absence.
 *
 * @since 6.1.0
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatCompletionResponse(String id,
        String object,
        long created,
        String model,
        List<Choice> choices,
        TokenUsage usage) {

    public static final String OBJECT_TYPE = "chat.completion";

    public static ChatCompletionResponse of(String id, String model, long createdEpochSeconds, Choice choice,
                                            TokenUsage usage) {
        return new ChatCompletionResponse(id, OBJECT_TYPE, createdEpochSeconds, model, List.of(choice), usage);
    }
}
