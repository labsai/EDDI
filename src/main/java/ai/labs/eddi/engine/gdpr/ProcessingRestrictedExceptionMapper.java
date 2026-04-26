/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.gdpr;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.Map;

/**
 * Maps {@link ProcessingRestrictedException} to HTTP 403 Forbidden. Provides a
 * clear JSON body explaining why the operation was blocked.
 *
 * @author ginccc
 * @since 6.0.0
 */
@Provider
public class ProcessingRestrictedExceptionMapper implements ExceptionMapper<ProcessingRestrictedException> {

    @Override
    public Response toResponse(ProcessingRestrictedException exception) {
        return Response.status(Response.Status.FORBIDDEN)
                .entity(Map.of(
                        "error", "processing_restricted",
                        "message", exception.getMessage()))
                .type(MediaType.APPLICATION_JSON)
                .build();
    }
}
