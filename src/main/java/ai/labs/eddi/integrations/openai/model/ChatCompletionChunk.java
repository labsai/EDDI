/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.openai.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * One SSE frame of a streaming completion.
 * <p>
 * {@code id}, {@code created} and {@code model} must be identical across every
 * chunk of a single response — some clients key on that.
 *
 * @since 6.1.0
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatCompletionChunk(String id,
        String object,
        long created,
        String model,
        List<ChunkChoice> choices) {

    public static final String OBJECT_TYPE = "chat.completion.chunk";

    public static ChatCompletionChunk of(String id, String model, long createdEpochSeconds, ChunkChoice choice) {
        return new ChatCompletionChunk(id, OBJECT_TYPE, createdEpochSeconds, model, List.of(choice));
    }
}
