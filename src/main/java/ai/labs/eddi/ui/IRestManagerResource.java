/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.ui;

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
public interface IRestManagerResource {

    @GET
    @Cache(noCache = true, mustRevalidate = true)
    @Path("/manage")
    @Produces(MediaType.TEXT_HTML)
    @Operation(hidden = true)
    Response fetchManagerResources();

    @GET
    @Cache(noCache = true, mustRevalidate = true)
    @Path("/manage/{path:.*}")
    @Produces(MediaType.TEXT_HTML)
    @Operation(hidden = true)
    Response fetchManagerResources(@PathParam("path") String path);
}
