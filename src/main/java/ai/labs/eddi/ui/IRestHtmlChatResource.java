/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.ui;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import jakarta.annotation.security.PermitAll;
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
// Explicitly public — the chat SPA shell: loaded by the browser before it
// holds a token. quarkus.security.jaxrs.deny-unannotated-endpoints denies
// anything unannotated.
@PermitAll
public interface IRestHtmlChatResource {

    // Hidden from the generated OpenAPI document for the same reason as every
    // other SPA shell in this package (manager, workforce, welcome): these return
    // static HTML, not an API operation, and listing them as documented endpoints
    // only adds noise to the schema.
    @GET
    @Cache(noCache = true, mustRevalidate = true)
    @Operation(hidden = true)
    Response viewDefault();

    /**
     * The operator settings the widget needs before its first request, as a script
     * that sets {@code window.__EDDI_CHAT__} — the way the Manager reads
     * {@code /manage/__auth_config__.js}. A literal path, so it wins over the SPA
     * catch-all below.
     */
    @GET
    @Cache(noCache = true, mustRevalidate = true)
    @Path("__config__.js")
    @Produces("application/javascript")
    @Operation(hidden = true)
    Response fetchChatConfig();

    /**
     * The embed launcher ({@code ui/chat/src/embed}): one standalone script a host
     * page includes with {@code <script src="/chat/embed.js" data-agent-id="…">} to
     * draw a button that opens this widget in an iframe
     * (docs/chat-ui-embedding.md). A literal path, so it wins over the SPA
     * catch-all below. Not content-hashed — host pages hard-code the URL — so it is
     * cached for an hour, not immutably.
     */
    @GET
    @Path("embed.js")
    @Produces("application/javascript")
    @Operation(hidden = true)
    Response fetchEmbedScript();

    @GET
    @Cache(noCache = true, mustRevalidate = true)
    @Path("{path:.*}")
    @Operation(hidden = true)
    Response viewHtml(@PathParam("path") String path);
}
