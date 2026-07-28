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
import java.net.URI;

import static ai.labs.eddi.engine.exception.SneakyThrow.sneakyThrow;

/**
 * Version-resolution mixin for the configuration stores.
 * <p>
 * <strong>Deliberately carries no {@code @RolesAllowed}.</strong> It has no
 * {@code @Path} of its own, so it is never an addressable resource — its two
 * default methods only become endpoints through a {@code @Path}-bearing
 * sub-interface, and every one of those already declares
 * {@code @RolesAllowed({"eddi-admin", "eddi-editor"})} at class level. A role
 * here would therefore protect nothing that is not already protected, while
 * putting a security annotation on the declaring type of methods that
 * {@link ai.labs.eddi.configs.rest.RestVersionInfo} — a plain, non-CDI helper
 * shared by every store implementation — calls in-process from
 * {@code validateParameters()} during config resolution and ZIP import. Guard
 * new stores at the store interface, not here.
 *
 * @author ginccc
 */
public interface IRestVersionInfo {
    String versionQueryParam = "?version=";

    @POST
    @Path("/{id}/currentversion")
    @Operation(description = "Redirect to latest version.")
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
