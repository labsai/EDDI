/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.mcpcalls;

import ai.labs.eddi.configs.IRestVersionInfo;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.mcpcalls.model.McpCallsConfiguration;
import jakarta.annotation.security.RolesAllowed;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;

/**
 * JAX-RS interface for MCP Calls configuration store.
 */
@Path("/mcpcallsstore/mcpcalls")
@Tag(name = "MCP Calls")
@RolesAllowed({"eddi-admin", "eddi-editor"})
public interface IRestMcpCallsStore extends IRestVersionInfo {
    String resourceBaseType = "eddi://ai.labs.mcpcalls";
    String resourceURI = resourceBaseType + "/mcpcallsstore/mcpcalls/";
    String versionQueryParam = "?version=";

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

    @GET
    @Path("/discover-tools")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Discover MCP tools", description = "Probe a live MCP server to discover available tools. "
            + "Used by the Manager UI for whitelist/blacklist selection.")
    Response discoverTools(@QueryParam("url") String url, @QueryParam("transport")
    @DefaultValue("http") String transport,
                           @QueryParam("apiKey")
                           @DefaultValue("") String apiKey);
}
