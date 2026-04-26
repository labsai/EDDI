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

@Provider
public class ResourceModifiedExceptionMapper implements ExceptionMapper<IResourceStore.ResourceModifiedException> {
    @Override
    public Response toResponse(IResourceStore.ResourceModifiedException exception) {
        return Response.status(Response.Status.CONFLICT).type(MediaType.TEXT_PLAIN).entity(exception.getLocalizedMessage()).build();
    }
}
