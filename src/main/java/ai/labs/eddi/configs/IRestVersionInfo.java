/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs;

import ai.labs.eddi.datastore.IResourceStore;
import org.eclipse.microprofile.openapi.annotations.Operation;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

import java.net.URI;

import static ai.labs.eddi.engine.exception.SneakyThrow.sneakyThrow;

/**
 * @author ginccc
 */
public interface IRestVersionInfo {
    String versionQueryParam = "?version=";

    @POST
    @Path("/{id}/currentversion")
    @Operation(description = "Redirect to latest version.")
    @APIResponse(responseCode = "303", description = "Redirect to latest version")
    @APIResponse(responseCode = "404", description = "Resource not found")
    default Response redirectToLatestVersion(@PathParam("id") String id) {
        try {
            IResourceStore.IResourceId currentResourceId = getCurrentResourceId(id);
            String path = URI.create(getResourceURI()).getPath();
            return Response.seeOther(URI.create(path + id + versionQueryParam + currentResourceId.getVersion())).build();
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw sneakyThrow(e);
        }
    }

    @GET
    @Path("/{id}/currentversion")
    @Produces(MediaType.TEXT_PLAIN)
    @Operation(description = "Get current version of this resource.")
    @APIResponse(responseCode = "200", description = "Current version")
    @APIResponse(responseCode = "404", description = "Resource not found")
    default Integer getCurrentVersion(@PathParam("id") String id) {
        try {
            IResourceStore.IResourceId currentResourceId = getCurrentResourceId(id);
            return currentResourceId.getVersion();
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw sneakyThrow(e);
        }
    }

    String getResourceURI();

    default IResourceStore.IResourceId getCurrentResourceId(String id) throws IResourceStore.ResourceNotFoundException {
        throw new IllegalStateException("Method getCurrentVersion of interface IRestVersionInfo needs to be implemented");
    }
}
