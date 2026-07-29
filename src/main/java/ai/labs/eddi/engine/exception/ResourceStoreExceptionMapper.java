/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.exception;

import ai.labs.eddi.datastore.IResourceStore;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

import java.util.UUID;

/**
 * Maps a store failure to a 500 that says nothing about the deployment.
 * <p>
 * Most configuration stores sneaky-throw into this mapper, so whatever it puts
 * in the body is what an arbitrary caller sees when the database misbehaves —
 * and a raw driver message names collections, hostnames, and replica-set
 * topology. The detail is logged at ERROR against a correlation id; the body
 * carries only that id, which is meaningless to an attacker and sufficient for
 * a support request.
 */
@Provider
public class ResourceStoreExceptionMapper implements ExceptionMapper<IResourceStore.ResourceStoreException> {
    private static final Logger log = Logger.getLogger(ResourceStoreExceptionMapper.class);

    static final String GENERIC_MESSAGE = "Internal server error";

    @Override
    public Response toResponse(IResourceStore.ResourceStoreException exception) {
        String correlationId = UUID.randomUUID().toString();
        log.errorf(exception, "Resource store failure [correlationId=%s]: %s", correlationId, exception.getLocalizedMessage());
        return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .type(MediaType.TEXT_PLAIN)
                .entity(GENERIC_MESSAGE + " (correlationId: " + correlationId + ")")
                .build();
    }
}
