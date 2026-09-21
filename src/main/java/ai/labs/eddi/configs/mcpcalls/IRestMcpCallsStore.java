/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.mcpcalls;

import ai.labs.eddi.configs.IRestVersionInfo;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.mcpcalls.model.McpCallsConfiguration;
import ai.labs.eddi.configs.mcpcalls.model.McpToolDiscoveryRequest;
import jakarta.annotation.security.RolesAllowed;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import java.util.List;

/**
 * JAX-RS interface for MCP Calls configuration store.
 */
@Path("/mcpcallsstore/mcpcalls")
@Tag(name = "Configuration / MCP Calls", description = "MCP server call definitions for tool use")
@RolesAllowed({"eddi-admin", "eddi-editor"})
public interface IRestMcpCallsStore extends IRestVersionInfo {
    String resourceBaseType = "eddi://ai.labs.mcpcalls";
    String resourceURI = resourceBaseType + "/mcpcallsstore/mcpcalls/";
    String versionQueryParam = "?version=";

    /** Header carrying the probed MCP server's credential. */
    String MCP_AUTHORIZATION_HEADER = "X-Mcp-Authorization";

    @GET
    @Path("/jsonSchema")
    @Produces(MediaType.APPLICATION_JSON)
    @APIResponse(responseCode = "200", description = "JSON Schema for McpCalls validation.")
    @Operation(operationId = "readMcpCallsJsonSchema", summary = "Get JSON Schema", description = "Read JSON Schema for MCP Calls configuration.")
    Response readJsonSchema();

    @GET
    @Path("descriptors")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "List MCP Calls descriptors", description = "Read list of MCP Calls configuration descriptors.")
    List<DocumentDescriptor> readMcpCallsDescriptors(@QueryParam("filter")
    @DefaultValue("") String filter,
                                                     @QueryParam("index")
                                                     @DefaultValue("0") Integer index,
                                                     @QueryParam("limit")
                                                     @DefaultValue("20") Integer limit);

    @GET
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Read MCP Calls config", description = "Read a specific MCP Calls configuration.")
    McpCallsConfiguration readMcpCalls(@PathParam("id") String id,
                                       @Parameter(name = "version", required = true, example = "1")
                                       @QueryParam("version") Integer version);

    @PUT
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Update MCP Calls config", description = "Update an existing MCP Calls configuration.")
    Response updateMcpCalls(@PathParam("id") String id,
                            @Parameter(name = "version", required = true, example = "1")
                            @QueryParam("version") Integer version,
                            McpCallsConfiguration mcpCallsConfiguration);

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Create MCP Calls config", description = "Create a new MCP Calls configuration.")
    Response createMcpCalls(McpCallsConfiguration mcpCallsConfiguration);

    @POST
    @Path("/{id}")
    @Operation(summary = "Duplicate MCP Calls config", description = "Duplicate an existing MCP Calls configuration.")
    Response duplicateMcpCalls(@PathParam("id") String id, @QueryParam("version") Integer version);

    @DELETE
    @Path("/{id}")
    @Operation(summary = "Delete MCP Calls config", description = "Delete an MCP Calls configuration.")
    Response deleteMcpCalls(@PathParam("id") String id,
                            @Parameter(name = "version", required = true, example = "1")
                            @QueryParam("version") Integer version,
                            @QueryParam("permanent")
                            @DefaultValue("false") Boolean permanent);

    /**
     * Probe a live MCP server for its tool list.
     * <p>
     * {@code POST}, not {@code GET}, and the API key travels in
     * {@code X-Mcp-Authorization} rather than a query parameter. The superseded
     * {@code GET} form took {@code ?apiKey=}, which put a live credential into
     * ingress logs, reverse-proxy logs, browser history and APM traces — every one
     * of them before any EDDI code ran, which is why the parameter had to be
     * removed rather than validated. Follows the {@code X-Source-Authorization}
     * pattern already used by {@code IRestImportService}.
     */
    @POST
    @Path("/discover-tools")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Discover MCP tools", description = "Probe a live MCP server to discover available tools. "
            + "Used by the Manager UI for whitelist/blacklist selection. "
            + "Supply the server's credential in the X-Mcp-Authorization header, never in the URL.")
    Response discoverTools(McpToolDiscoveryRequest request, @HeaderParam(MCP_AUTHORIZATION_HEADER) String apiKey);

    /**
     * Credential-free discovery, kept for MCP servers that need no authentication.
     * <p>
     * Rejects a request that still carries a credential query parameter instead of
     * ignoring it: a caller that has not migrated is sending a live secret through
     * the URL on every attempt, and a 400 naming the replacement is the only way
     * they find out.
     */
    @GET
    @Path("/discover-tools")
    @Produces(MediaType.APPLICATION_JSON)
    @Deprecated(since = "6.3.0", forRemoval = true)
    @Operation(deprecated = true, summary = "Discover MCP tools (no credential)", description = "Deprecated — use POST /discover-tools. "
            + "Probes an MCP server that requires no authentication. A credential query parameter is rejected.")
    Response discoverToolsUnauthenticated(@QueryParam("url") String url, @QueryParam("transport")
    @DefaultValue("http") String transport, @Context UriInfo uriInfo);
}
