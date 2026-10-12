/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.exception;

import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * A request body whose JSON is well formed but has a value of the wrong shape —
 * {@code "context": {"x": "text"}} where a context object belongs. Replaces the
 * framework's built-in mapper, which in production answers 400 with an empty
 * body; see {@link JsonBodyErrors}.
 */
@Provider
@Priority(Priorities.USER - 100)
public class MismatchedJsonInputExceptionMapper implements ExceptionMapper<MismatchedInputException> {
    @Context
    UriInfo uriInfo;

    @Override
    public Response toResponse(MismatchedInputException exception) {
        return ErrorResponses.badRequest(JsonBodyErrors.describe(exception), uriInfo);
    }
}
