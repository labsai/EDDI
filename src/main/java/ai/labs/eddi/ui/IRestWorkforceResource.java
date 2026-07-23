/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.ui;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.jboss.resteasy.reactive.Cache;

/**
 * Serves the workforce SPA shell. Multi-agent collaboration workspace.
 */
@Path("/workforce")
@Produces(MediaType.TEXT_HTML)
public interface IRestWorkforceResource {

    @GET
    @Cache(noCache = true, mustRevalidate = true)
    @Operation(hidden = true)
    Response viewDefault();

    @GET
    @Cache(noCache = true, mustRevalidate = true)
    @Path("{path:.*}")
    @Operation(hidden = true)
    Response viewHtml(@PathParam("path") String path);
}
