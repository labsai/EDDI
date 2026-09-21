/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.ui;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.Cache;

/**
 * @author ginccc
 */

@Path("/chat")
@Produces(MediaType.TEXT_HTML)
@Tag(name = "UI / Chat", description = "Embedded responsive chat window")
public interface IRestHtmlChatResource {

    // Hidden from the generated OpenAPI document for the same reason as every
    // other SPA shell in this package (manager, workforce, welcome): these return
    // static HTML, not an API operation, and listing them as documented endpoints
    // only adds noise to the schema.
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
