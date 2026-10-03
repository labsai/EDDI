/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.exception;

import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * An {@link IllegalArgumentException} that reaches the REST layer is a request
 * the server cannot act on (a malformed id, an out-of-range value): 400, with
 * the shared JSON error body. The body used to be the bare message under
 * whatever content type the endpoint declared — {@code application/json} for
 * most — so clients received a "JSON" response that did not parse.
 *
 * @author ginccc
 */
@Provider
public class IllegalArgumentExceptionMapper implements ExceptionMapper<IllegalArgumentException> {
    @Context
    UriInfo uriInfo;

    @Override
    public Response toResponse(IllegalArgumentException exception) {
        return ErrorResponses.badRequest(exception.getLocalizedMessage(), uriInfo);
    }

    /**
     * The response this mapper produces, for endpoints that resume an
     * {@code AsyncResponse} themselves (mappers never see those exceptions).
     */
    public static Response responseOf(IllegalArgumentException exception) {
        return ErrorResponses.badRequest(exception.getLocalizedMessage());
    }
}
