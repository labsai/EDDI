/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.openai.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One entry of the OpenAI {@code messages[]} array.
 * <p>
 * {@code content} is polymorphic in the OpenAI spec — a plain string for text,
 * or an array of {@link ContentPart} for multimodal input. Modelling it as a
 * {@link JsonNode} handles both shapes without a custom deserializer; use
 * {@link #isPlainText()}, {@link #textContent()} and
 * {@link #contentParts(ObjectMapper)} instead of reading the node.
 *
 * @since 6.1.0
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatMessage(String role, JsonNode content) {

    /** Convenience factory for assistant replies on the response path. */
    public static ChatMessage assistant(String text, ObjectMapper mapper) {
        return new ChatMessage("assistant", mapper.getNodeFactory().textNode(text == null ? "" : text));
    }

    /**
     * Whether {@code content} is the simple string form.
     * <p>
     * {@code @JsonIgnore} is load-bearing: Jackson treats an {@code isX()} method
     * on a record as a bean property, so without it every assistant message goes
     * out carrying a {@code "plainText"} field that is not in the OpenAI schema.
     */
    @JsonIgnore
    public boolean isPlainText() {
        return content != null && content.isTextual();
    }

    /**
     * The text of a plain-string content, or {@code null} when the content is an
     * array (use {@link #contentParts(ObjectMapper)}) or absent.
     */
    public String textContent() {
        return isPlainText() ? content.asText() : null;
    }

    /**
     * The content parts of an array-form content, or an empty list when the content
     * is not an array. Parts that fail to bind are skipped rather than failing the
     * whole request.
     */
    public List<ContentPart> contentParts(ObjectMapper mapper) {
        if (content == null || !content.isArray()) {
            return Collections.emptyList();
        }
        List<ContentPart> parts = new ArrayList<>();
        for (JsonNode node : content) {
            try {
                ContentPart part = mapper.treeToValue(node, ContentPart.class);
                if (part != null) {
                    parts.add(part);
                }
            } catch (Exception ignored) {
                // A malformed part must not fail the request — skip it.
            }
        }
        return parts;
    }
}
