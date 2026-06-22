/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.agents;

import ai.labs.eddi.configs.IRestVersionInfo;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import jakarta.annotation.security.RolesAllowed;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.util.List;

/**
 * @author ginccc
 */
@Path("/agentstore/agents")
@Tag(name = "Agents")
@RolesAllowed({"eddi-admin", "eddi-editor"})
public interface IRestAgentStore extends IRestVersionInfo {
    String resourceURI = "eddi://ai.labs.agent/agentstore/agents/";

    @GET
    @Path("/jsonSchema")
    @Produces(MediaType.APPLICATION_JSON)
    @APIResponse(responseCode = "200", description = "JSON Schema (for validation).")
    @Operation(operationId = "readAgentJsonSchema", description = "Read JSON Schema for Agent definition.")
    Response readJsonSchema();

    @GET
    @Path("descriptors")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(operationId = "readAgentDescriptors", description = "Read list of Agent descriptors.")
    List<DocumentDescriptor> readAgentDescriptors(@QueryParam("filter")
    @DefaultValue("") String filter,
                                                  @QueryParam("index")
                                                  @DefaultValue("0") Integer index,
                                                  @QueryParam("limit")
                                                  @DefaultValue("20") Integer limit);

    @POST
    @Path("descriptors")
    @Consumes(MediaType.TEXT_PLAIN)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(operationId = "readAgentDescriptorsWithWorkflow", description = "Read list of Agent descriptors including a given workflowUri.")
    // @formatter:off
    List<DocumentDescriptor> readAgentDescriptors(@QueryParam("filter") @DefaultValue("") String filter,
            @QueryParam("index") @DefaultValue("0") Integer index,
            @QueryParam("limit") @DefaultValue("20") Integer limit,
            @Parameter(name = "body",
                    example = "eddi://ai.labs.workflow/workflowstore/workflows/ID?version=VERSION")
            @DefaultValue("") String containingWorkflowUri,
            @QueryParam("includePreviousVersions") @DefaultValue("false") Boolean includePreviousVersions);
    // @formatter:on

    @GET
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(description = "Read agent.")
    AgentConfiguration readAgent(@PathParam("id") String id,
                                 @Parameter(name = "version", required = true, example = "1")
                                 @QueryParam("version") Integer version);

    @PUT
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(description = "Update agent.")
    Response updateAgent(@PathParam("id") String id,
                         @Parameter(name = "version", required = true, example = "1")
                         @QueryParam("version") Integer version,
                         AgentConfiguration agentConfiguration);

    @PUT
    @Path("/{id}/updateResourceUri")
    @Consumes(MediaType.TEXT_PLAIN)
    @Operation(description = "Update references to other resources within this Agent resource.")
    Response updateResourceInAgent(@PathParam("id") String id,
                                   @Parameter(name = "version", required = true, example = "1")
                                   @QueryParam("version") Integer version,
                                   URI resourceURI);

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(description = "Create agent.")
    Response createAgent(AgentConfiguration agentConfiguration);

    @POST
    @Path("/{id}")
    @Operation(description = "Duplicate this agent.")
    Response duplicateAgent(@PathParam("id") String id, @QueryParam("version") Integer version,
                            @QueryParam("deepCopy")
                            @DefaultValue("false") Boolean deepCopy);

    @DELETE
    @Path("/{id}")
    @Operation(summary = "Delete agent", description = "Delete a Agent configuration. When cascade=true, also deletes referenced packages "
            + "and their extension resources (behavior sets, HTTP calls, output sets, langchains, "
            + "property setters, dictionaries). Shared resources (packages used by other agents, "
            + "extensions used by other packages) are skipped. "
            + "Partial failures are logged but do not prevent the Agent from being deleted.")
    @APIResponse(responseCode = "200", description = "Agent deleted successfully.")
    @APIResponse(responseCode = "404", description = "Agent not found.")
    // @formatter:off
    Response deleteAgent(@PathParam("id") String id,
            @Parameter(name = "version", required = true, example = "1",
                    description = "Version of the Agent to delete.")
            @QueryParam("version") Integer version,
            @Parameter(description = "If true, permanently remove from database. "
                    + "If false (default), soft-delete only.")
            @QueryParam("permanent") @DefaultValue("false") Boolean permanent,
            @Parameter(description = "If true, also delete all packages "
                    + "and extension resources referenced by this agent. "
                    + "Resources shared with other agents are still deleted "
                    + "— use with care.")
            @QueryParam("cascade") @DefaultValue("false") Boolean cascade);
    // @formatter:on
}
