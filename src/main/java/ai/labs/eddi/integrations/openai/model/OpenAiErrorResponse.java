/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.openai.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The OpenAI error envelope:
 * {@code {"error":{"message":…,"type":…,"param":…,"code":…}}}.
 * <p>
 * Every non-2xx response from the adapter uses this shape so clients can parse
 * failures instead of receiving an HTML error page.
 *
 * @since 6.1.0
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OpenAiErrorResponse(OpenAiError error) {

    // --- type values (OpenAI vocabulary) ---
    public static final String TYPE_INVALID_REQUEST = "invalid_request_error";
    public static final String TYPE_SERVER_ERROR = "server_error";
    public static final String TYPE_RATE_LIMIT = "rate_limit_exceeded";

    // --- code values (adapter-specific, stable) ---
    public static final String CODE_INVALID_API_KEY = "invalid_api_key";
    public static final String CODE_MODEL_NOT_FOUND = "model_not_found";
    public static final String CODE_AMBIGUOUS_MODEL = "ambiguous_model";
    public static final String CODE_NO_USER_MESSAGE = "no_user_message";
    public static final String CODE_AGENT_NOT_READY = "agent_not_ready";
    public static final String CODE_UNKNOWN_ENDPOINT = "unknown_endpoint";
    public static final String CODE_TIMEOUT = "timeout";

    public static OpenAiErrorResponse of(String message, String type, String code) {
        return new OpenAiErrorResponse(new OpenAiError(message, type, null, code));
    }

    /**
     * @param param
     *            the offending request field, or {@code null} when the error is not
     *            attributable to one
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OpenAiError(String message, String type, String param, String code) {
    }
}
