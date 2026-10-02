/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.ui;

import jakarta.annotation.security.PermitAll;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.Cache;

import org.eclipse.microprofile.openapi.annotations.Operation;

/**
 * Internal SPA serving endpoint — not a public API.
 */
@Path("/")
// Explicitly public — the Manager SPA shell and its runtime auth config: the
// browser loads them before it holds a token, and the HTTP policy permits these
// paths.
// quarkus.security.jaxrs.deny-unannotated-endpoints denies anything
// unannotated.
@PermitAll
public interface IRestManagerResource {

    @GET
    @Cache(noCache = true, mustRevalidate = true)
    @Path("/manage")
    @Produces(MediaType.TEXT_HTML)
    @Operation(hidden = true)
    Response fetchManagerResources();

    @GET
    @Cache(noCache = true, mustRevalidate = true)
    @Path("/manage/__auth_config__.js")
    @Produces("application/javascript")
    @Operation(hidden = true)
    Response fetchAuthConfig();

    @GET
    @Cache(noCache = true, mustRevalidate = true)
    @Path("/manage/{path:.*}")
    @Produces(MediaType.TEXT_HTML)
    @Operation(hidden = true)
    Response fetchManagerResources(@PathParam("path") String path);
}
