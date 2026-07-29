/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.openai.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One element of an array-form {@link ChatMessage#content()}.
 * <p>
 * The OpenAI Chat Completions vocabulary covers four part types, and their
 * payload shapes are inconsistent in a way that is easy to get wrong:
 * <ul>
 * <li>{@code text} — plain {@code text} field</li>
 * <li>{@code image_url} — {@code image_url.url}, either a {@code data:} URI or
 * a remote URL</li>
 * <li>{@code input_audio} — {@code input_audio.data} is <b>raw base64 with no
 * {@code data:} prefix</b>, with the type carried separately in
 * {@code input_audio.format} ({@code wav} or {@code mp3})</li>
 * <li>{@code file} — {@code file.file_data} <b>is</b> a full {@code data:} URI,
 * plus a {@code filename}. Alternatively {@code file.file_id} references a
 * previously uploaded file, which requires the OpenAI Files API that EDDI does
 * not implement.</li>
 * </ul>
 *
 * @since 6.1.0
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ContentPart(String type,
        String text,
        @JsonProperty("image_url") ImageUrl imageUrl,
        @JsonProperty("input_audio") InputAudio inputAudio,
        @JsonProperty("file") FilePart file) {

    public static final String TYPE_TEXT = "text";
    public static final String TYPE_IMAGE_URL = "image_url";
    public static final String TYPE_INPUT_AUDIO = "input_audio";
    public static final String TYPE_FILE = "file";

    public boolean isText() {
        return TYPE_TEXT.equals(type);
    }

    public boolean isImageUrl() {
        return TYPE_IMAGE_URL.equals(type) && imageUrl != null && imageUrl.url() != null;
    }

    public boolean isInputAudio() {
        return TYPE_INPUT_AUDIO.equals(type) && inputAudio != null && inputAudio.data() != null;
    }

    public boolean isFile() {
        return TYPE_FILE.equals(type) && file != null;
    }

    /**
     * The {@code image_url} payload. {@code detail} is accepted for wire
     * compatibility and ignored — EDDI's vision handling is governed by
     * {@code ModelCapabilityService}, not by a client hint.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ImageUrl(String url, String detail) {
    }

    /**
     * The {@code input_audio} payload.
     *
     * @param data
     *            raw base64 — <b>not</b> a {@code data:} URI, unlike every other
     *            binary payload in this protocol
     * @param format
     *            {@code wav} or {@code mp3}
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record InputAudio(String data, String format) {
    }

    /**
     * The {@code file} payload.
     *
     * @param filename
     *            display name; {@code file_name} is accepted as an alias because it
     *            appears in the wild
     * @param fileData
     *            a full {@code data:<mime>;base64,<payload>} URI
     * @param fileId
     *            a reference to a file uploaded through the OpenAI Files API, which
     *            EDDI does not implement — such parts are skipped
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FilePart(@JsonProperty("filename")
    @JsonAlias("file_name") String filename,
            @JsonProperty("file_data") String fileData,
            @JsonProperty("file_id") String fileId) {
    }
}
