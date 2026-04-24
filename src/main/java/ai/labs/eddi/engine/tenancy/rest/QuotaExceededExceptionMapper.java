/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.tenancy.rest;

import ai.labs.eddi.engine.tenancy.QuotaExceededException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.Map;

/**
 * Maps {@link QuotaExceededException} to HTTP 429 Too Many Requests.
 */
@Provider
public class QuotaExceededExceptionMapper implements ExceptionMapper<QuotaExceededException> {

    private static final int TOO_MANY_REQUESTS = 429;

    @Override
    public Response toResponse(QuotaExceededException exception) {
        return Response.status(TOO_MANY_REQUESTS).entity(Map.of("error", "quota_exceeded", "message", exception.getMessage()))
                .type(MediaType.APPLICATION_JSON).header("Retry-After", "60").build();
    }
}
