/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.api;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;

/**
 * Read-only access to EDDI's own documentation.
 * <p>
 * The same doc set that MCP serves at {@code eddi://docs/*}. It needs a REST
 * surface too because MCP <em>resources</em> only reach a client that asks for
 * them and EDDI's own MCP client never calls {@code resources/read} — so an
 * agent built on EDDI could not read EDDI's documentation, which is exactly
 * what an agent meant to explain the platform needs. Over REST these become
 * ordinary generated tools.
 * <p>
 * {@code eddi-viewer}: the docs are the project's published documentation, so
 * anyone who may look at the deployment at all may read them.
 *
 * @since 6.2.0
 */
@Path("/administration/docs")
@Tag(name = "Operations / Docs", description = "Read EDDI's own documentation")
@RolesAllowed("eddi-viewer")
public interface IRestDocs {

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "List available documentation pages", description = "Returns the names of the documentation pages this deployment "
            + "serves, without the .md suffix. The runtime set is smaller than the repository's — the container image ships only the "
            + "top-level docs and omits a few — so read this list rather than assuming a page exists.")
    @APIResponse(responseCode = "200", description = "Available documentation page names.")
    List<String> listDocs();

    @GET
    @Path("/{name}")
    @Produces(MediaType.TEXT_PLAIN)
    @Operation(summary = "Read a documentation page", description = "Returns the markdown source of one documentation page.")
    @APIResponse(responseCode = "200", description = "The page's markdown source.")
    @APIResponse(responseCode = "404", description = "No such documentation page in this deployment.")
    Response readDoc(@Parameter(description = "Page name without the .md suffix, e.g. 'architecture'.")
    @PathParam("name") String name);
}
