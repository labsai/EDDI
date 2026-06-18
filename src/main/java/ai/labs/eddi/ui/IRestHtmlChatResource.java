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
@Tag(name = "Chat UI")
public interface IRestHtmlChatResource {

    @GET
    @Cache(noCache = true, mustRevalidate = true)
    @Operation(summary = "View default chat page", description = "Render the default embedded chat UI.")
    Response viewDefault();

    @GET
    @Cache(noCache = true, mustRevalidate = true)
    @Path("{path:.*}")
    @Operation(summary = "View chat resource", description = "Serve a requested chat UI resource by path.")
    Response viewHtml(@PathParam("path") String path);
}
