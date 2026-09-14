/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.exception;

import ai.labs.eddi.engine.api.IConversationService.InputTooLargeException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.Map;

/**
 * 413 for a turn input longer than {@code eddi.conversations.max-input-chars}.
 * <p>
 * Covers the synchronous callers; the {@code AsyncResponse}-based say endpoint
 * builds the same response through {@link #responseOf} because a resumed
 * exception never reaches an {@link ExceptionMapper}.
 */
@Provider
public class InputTooLargeExceptionMapper implements ExceptionMapper<InputTooLargeException> {

    public static final String ERROR_CODE = "input_too_large";

    @Override
    public Response toResponse(InputTooLargeException exception) {
        return responseOf(exception);
    }

    public static Response responseOf(InputTooLargeException exception) {
        return Response.status(Response.Status.REQUEST_ENTITY_TOO_LARGE)
                .type(MediaType.APPLICATION_JSON)
                .entity(Map.of("error", ERROR_CODE, "message", exception.getMessage(), "limit", exception.getLimit()))
                .build();
    }
}
