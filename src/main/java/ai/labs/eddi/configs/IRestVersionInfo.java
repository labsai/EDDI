/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs;

import ai.labs.eddi.datastore.IResourceStore;
import io.quarkus.security.ForbiddenException;
import jakarta.annotation.security.RolesAllowed;
import org.eclipse.microprofile.openapi.annotations.Operation;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

import java.net.URI;

import static ai.labs.eddi.engine.exception.SneakyThrow.sneakyThrow;

/**
 * Version-resolution mixin for the configuration stores.
 * <p>
 * It has no {@code @Path} of its own, so it is never an addressable resource —
 * its two default methods only become endpoints through a {@code @Path}-bearing
 * sub-interface. <strong>The two endpoint methods carry their own
 * {@code @RolesAllowed}</strong>, the authoring tier every configuration store
 * declares at class level: Quarkus resolves a class-level security annotation
 * from the type that <em>declares</em> the method, and for these default
 * methods that is this mixin, not the store interface. Without it they carried
 * no check at all — any token reached {@code …/{id}/currentversion} on every
 * store — and under {@code quarkus.security.jaxrs.deny-unannotated-endpoints}
 * nobody did. The type itself stays unannotated: guard new stores at the store
 * interface, not here.
 *
 * @author ginccc
 */
public interface IRestVersionInfo {
    String versionQueryParam = "?version=";

    @POST
    @Path("/{id}/currentversion")
    @RolesAllowed({"eddi-admin", "eddi-editor"})
    @Operation(description = "Redirect to latest version.")
    @APIResponse(responseCode = "303", description = "Redirect to latest version")
    @APIResponse(responseCode = "404", description = "Resource not found")
    default Response redirectToLatestVersion(@PathParam("id") String id) {
        requireCurrentVersionAccess(id);
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
    @RolesAllowed({"eddi-admin", "eddi-editor"})
    @Produces(MediaType.TEXT_PLAIN)
    @Operation(description = "Get current version of this resource.")
    @APIResponse(responseCode = "200", description = "Current version")
    @APIResponse(responseCode = "404", description = "Resource not found")
    default Integer getCurrentVersion(@PathParam("id") String id) {
        requireCurrentVersionAccess(id);
        try {
            IResourceStore.IResourceId currentResourceId = getCurrentResourceId(id);
            return currentResourceId.getVersion();
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw sneakyThrow(e);
        }
    }

    String getResourceURI();

    /**
     * The per-resource check the two {@code currentversion} endpoints run before
     * resolving {@code id}: the caller must be able to VIEW the resource, exactly
     * as for reading it. The role check above says only that the caller is an
     * author; without this an editor could probe any known id — learning whether it
     * exists and how often it was edited — in a workspace they cannot read.
     * <p>
     * Every store implements it by delegating to its
     * {@code RestVersionInfo#requireViewAccess}. The default refuses, so a store
     * that forgets fails closed rather than open. Not an endpoint (no HTTP method),
     * and deliberately separate from {@link #getCurrentResourceId}, which
     * in-process engine code calls as a trusted resolver and must stay unguarded.
     */
    default void requireCurrentVersionAccess(String id) {
        throw new ForbiddenException("Access denied: this store does not define how its version info is authorized");
    }

    default IResourceStore.IResourceId getCurrentResourceId(String id) throws IResourceStore.ResourceNotFoundException {
        throw new IllegalStateException("Method getCurrentVersion of interface IRestVersionInfo needs to be implemented");
    }
}
