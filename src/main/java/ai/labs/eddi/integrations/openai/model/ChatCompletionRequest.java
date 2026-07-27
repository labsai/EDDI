/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.openai.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * An OpenAI {@code POST /v1/chat/completions} request body.
 * <p>
 * Only the fields EDDI acts on are modelled. Everything else a client may send
 * ({@code temperature}, {@code max_tokens}, {@code top_p},
 * {@code stream_options}, {@code tools}, {@code tool_choice}, {@code metadata},
 * {@code files}, …) is deliberately ignored rather than rejected — model
 * parameters and tools belong to the agent's {@code langchain.json}, and a 400
 * on an unknown field would break clients that always send them.
 * <p>
 * {@code user} is typed as {@link JsonNode} on purpose: the OpenAI spec says it
 * is a string, but Open WebUI sends an <em>object</em>
 * ({@code {name,id,email,role}}) for pipeline models. Use
 * {@link #userAsString()} rather than reading the node directly.
 *
 * @since 6.1.0
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChatCompletionRequest(String model,
        List<ChatMessage> messages,
        Boolean stream,
        JsonNode user) {

    /** Whether the client asked for an SSE stream. {@code stream} is nullable. */
    public boolean isStreaming() {
        return Boolean.TRUE.equals(stream);
    }

    /**
     * The {@code user} field flattened to a scalar, or {@code null} when absent or
     * unusable. Handles both the spec shape (a string) and Open WebUI's object
     * shape (falls back to its {@code id} property).
     */
    public String userAsString() {
        if (user == null || user.isNull()) {
            return null;
        }
        if (user.isTextual()) {
            String value = user.asText();
            return value.isBlank() ? null : value;
        }
        if (user.isObject()) {
            JsonNode id = user.get("id");
            if (id != null && id.isTextual() && !id.asText().isBlank()) {
                return id.asText();
            }
        }
        return null;
    }

    /**
     * The last {@code role: "user"} message, or {@code null} when the request
     * carries none. Only the last one is sent to EDDI — earlier turns live in the
     * conversation memory, not in the request.
     */
    public ChatMessage lastUserMessage() {
        return lastMessageWithRole("user");
    }

    /**
     * The last {@code role: "system"} message, or {@code null}. Earlier system
     * messages are ignored.
     */
    public ChatMessage lastSystemMessage() {
        return lastMessageWithRole("system");
    }

    private ChatMessage lastMessageWithRole(String role) {
        if (messages == null) {
            return null;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage message = messages.get(i);
            if (message != null && role.equals(message.role())) {
                return message;
            }
        }
        return null;
    }
}
