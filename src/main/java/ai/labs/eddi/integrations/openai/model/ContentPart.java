/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.openai.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One element of an array-form {@link ChatMessage#content()}.
 * <p>
 * {@code type} is {@code "text"} or {@code "image_url"}. Other types
 * ({@code input_audio}, {@code file}, …) bind with null payloads and are
 * skipped by the mapper.
 *
 * @since 6.1.0
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ContentPart(String type,
        String text,
        @JsonProperty("image_url") ImageUrl imageUrl) {

    public static final String TYPE_TEXT = "text";
    public static final String TYPE_IMAGE_URL = "image_url";

    public boolean isText() {
        return TYPE_TEXT.equals(type);
    }

    public boolean isImageUrl() {
        return TYPE_IMAGE_URL.equals(type) && imageUrl != null && imageUrl.url() != null;
    }

    /**
     * The {@code image_url} payload. {@code detail} is accepted for wire
     * compatibility and ignored — EDDI's vision handling is governed by
     * {@code ModelCapabilityService}, not by a client hint.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ImageUrl(String url, String detail) {
    }
}
