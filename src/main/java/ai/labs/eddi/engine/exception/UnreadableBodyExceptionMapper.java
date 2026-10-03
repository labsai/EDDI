/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.exception;

import com.fasterxml.jackson.core.JsonProcessingException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * Gives an unreadable JSON request body a message.
 * <p>
 * The framework's JSON reader wraps syntax errors and parser-limit violations
 * (a document nested thousands of levels deep, say) in a bare
 * {@code WebApplicationException(cause, 400)} — no entity, so the client got
 * {@code 400} with {@code content-length: 0}. Exactly those are answered with
 * the shared JSON error body ({@link JsonBodyErrors}).
 * <p>
 * Every other {@code WebApplicationException} that reaches this mapper (the
 * more specific {@link ClientErrorExceptionMapper} and
 * {@link ParameterConversionExceptionMapper} take theirs first) is answered
 * with its own response, unchanged — which is what the framework does without a
 * mapper.
 */
@Provider
public class UnreadableBodyExceptionMapper implements ExceptionMapper<WebApplicationException> {
    @Override
    public Response toResponse(WebApplicationException exception) {
        Response original = exception.getResponse();
        if (original != null && original.getStatus() == Response.Status.BAD_REQUEST.getStatusCode()
                && !original.hasEntity() && exception.getCause() instanceof JsonProcessingException cause) {
            return ErrorResponses.badRequest(JsonBodyErrors.describe(cause));
        }
        return original;
    }
}
