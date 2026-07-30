/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.openai;

import ai.labs.eddi.integrations.openai.model.OpenAiErrorResponse;
import jakarta.ws.rs.core.Response;

/**
 * A failure that must reach the client in the OpenAI error envelope.
 * <p>
 * Carries its own HTTP status, error {@code type} and {@code code} so
 * {@link OpenAiExceptionMapper} can render it without a translation table.
 * OpenAI clients parse {@code {"error":{…}}}; an HTML error page or a bare
 * string body surfaces to the user as an unexplained failure.
 *
 * @since 6.1.0
 */
public class OpenAiApiException extends RuntimeException {

    private final int status;
    private final String type;
    private final String code;

    public OpenAiApiException(int status, String type, String code, String message) {
        super(message);
        this.status = status;
        this.type = type;
        this.code = code;
    }

    public static OpenAiApiException badRequest(String code, String message) {
        return new OpenAiApiException(Response.Status.BAD_REQUEST.getStatusCode(),
                OpenAiErrorResponse.TYPE_INVALID_REQUEST, code, message);
    }

    public static OpenAiApiException unauthorized(String message) {
        return new OpenAiApiException(Response.Status.UNAUTHORIZED.getStatusCode(),
                OpenAiErrorResponse.TYPE_INVALID_REQUEST, OpenAiErrorResponse.CODE_INVALID_API_KEY, message);
    }

    public static OpenAiApiException notFound(String code, String message) {
        return new OpenAiApiException(Response.Status.NOT_FOUND.getStatusCode(),
                OpenAiErrorResponse.TYPE_INVALID_REQUEST, code, message);
    }

    /**
     * The conversation could not accept the turn right now (another turn is in
     * flight, or the conversation is no longer active). 429 rather than 409 so
     * OpenAI clients apply their built-in backoff and retry.
     */
    public static OpenAiApiException busy(String message) {
        return new OpenAiApiException(429, OpenAiErrorResponse.TYPE_RATE_LIMIT, null, message);
    }

    public static OpenAiApiException serverError(String code, String message) {
        return new OpenAiApiException(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode(),
                OpenAiErrorResponse.TYPE_SERVER_ERROR, code, message);
    }

    public static OpenAiApiException unavailable(String code, String message) {
        return new OpenAiApiException(Response.Status.SERVICE_UNAVAILABLE.getStatusCode(),
                OpenAiErrorResponse.TYPE_SERVER_ERROR, code, message);
    }

    public static OpenAiApiException timeout(String message) {
        return new OpenAiApiException(Response.Status.GATEWAY_TIMEOUT.getStatusCode(),
                OpenAiErrorResponse.TYPE_SERVER_ERROR, OpenAiErrorResponse.CODE_TIMEOUT, message);
    }

    public int getStatus() {
        return status;
    }

    public String getType() {
        return type;
    }

    public String getCode() {
        return code;
    }

    /** This exception rendered as the OpenAI error envelope. */
    public OpenAiErrorResponse toErrorResponse() {
        return OpenAiErrorResponse.of(getMessage(), type, code);
    }
}
