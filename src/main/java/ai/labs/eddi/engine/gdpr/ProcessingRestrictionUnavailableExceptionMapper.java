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
 * Maps {@link ProcessingRestrictionUnavailableException} to HTTP 503 Service
 * Unavailable — the honest status for "we could not read the restriction flag",
 * as opposed to the 403 the fail-closed path used to return, which told the
 * user their processing was restricted when nobody had restricted it.
 *
 * @author ginccc
 * @since 6.0.0
 */
@Provider
public class ProcessingRestrictionUnavailableExceptionMapper implements ExceptionMapper<ProcessingRestrictionUnavailableException> {

    @Override
    public Response toResponse(ProcessingRestrictionUnavailableException exception) {
        return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                .entity(Map.of(
                        "error", "restriction_status_unavailable",
                        "message", exception.getMessage()))
                .type(MediaType.APPLICATION_JSON)
                .build();
    }
}
