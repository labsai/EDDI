/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.openai.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One choice of a streaming chunk. The terminal chunk carries an empty
 * {@link Delta} and a non-null {@code finish_reason}.
 *
 * @since 6.1.0
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChunkChoice(int index,
        Delta delta,
        @JsonProperty("finish_reason") String finishReason) {

    /** Opening frame: announces the assistant role, no content yet. */
    public static ChunkChoice role() {
        return new ChunkChoice(0, new Delta("assistant", null), null);
    }

    /** A content frame carrying one token or text fragment. */
    public static ChunkChoice content(String text) {
        return new ChunkChoice(0, new Delta(null, text), null);
    }

    /** Terminal frame: empty delta plus a finish reason. */
    public static ChunkChoice finish(String finishReason) {
        return new ChunkChoice(0, new Delta(null, null), finishReason);
    }

    /**
     * The incremental payload of a chunk. Both fields are {@code NON_NULL}-included
     * so the terminal frame serializes as {@code "delta":{}} exactly as the OpenAI
     * protocol specifies.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Delta(String role, String content) {
    }
}
