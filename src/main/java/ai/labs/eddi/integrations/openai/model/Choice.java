/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.openai.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One choice of a non-streaming {@code chat.completion}. EDDI always returns
 * exactly one — {@code n > 1} is not supported.
 *
 * @since 6.1.0
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Choice(int index,
        ChatMessage message,
        @JsonProperty("finish_reason") String finishReason) {

    public static final String FINISH_STOP = "stop";
    /** The model hit its output-token limit; the text is cut off. */
    public static final String FINISH_LENGTH = "length";
    /** The provider withheld or cut the answer under its content policy. */
    public static final String FINISH_CONTENT_FILTER = "content_filter";
}
