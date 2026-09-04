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
@Tag(name = "Agents", description = "Agent configuration CRUD")
@RolesAllowed({"eddi-admin", "eddi-editor"})
public interface IRestAgentStore extends IRestVersionInfo {
    String resourceURI = "eddi://ai.labs.agent/agentstore/agents/";

    @GET
    @Path("/jsonSchema")
    @Produces(MediaType.APPLICATION_JSON)
    @APIResponse(responseCode = "200", description = "JSON Schema (for validation).")
    @Operation(operationId = "readAgentJsonSchema", description = "Read JSON Schema for Agent definition.")
    Response readJsonSchema();

    /**
     * @param space
     *            narrows the listing to one space id ({@code user:<principal>} or
     *            {@code team:<group>}) — the server side of the Manager's space
     *            switcher, and the reason it is a query parameter rather than a
     *            client-side filter: page 2 of "everything" is not page 2 of "this
     *            space". A narrowing only: asking for a space you cannot reach
     *            returns nothing rather than granting it. Blank means every space
     *            you can reach.
     */
    @GET
    @Path("descriptors")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(operationId = "readAgentDescriptors", description = "Read list of Agent descriptors.")
    List<DocumentDescriptor> readAgentDescriptors(@QueryParam("filter")
    @DefaultValue("") String filter,
                                                  @QueryParam("index")
                                                  @DefaultValue("0") Integer index,
                                                  @QueryParam("limit")
                                                  @DefaultValue("20") Integer limit,
                                                  @QueryParam("space")
                                                  @DefaultValue("") String space);

    @POST
    @Path("descriptors")
    @Consumes(MediaType.TEXT_PLAIN)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(operationId = "readAgentDescriptorsWithWorkflow", description = "Read list of Agent descriptors including a given workflowUri. "
            + "filter, index and limit are applied to the result AFTER the reverse-reference lookup, which "
            + "takes neither. They used to be accepted and ignored, so a client that never paged received the "
            + "full list; with limit defaulting to 20 such a client now receives at most 20 rows per page.")
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
            + "Partial failures are logged but do not prevent the Agent from being deleted. "
            + "With cascade=true the version must be the Agent's current one, otherwise the request is "
            + "refused with 409 before anything is deleted.")
    @APIResponse(responseCode = "200", description = "Agent deleted successfully.")
    @APIResponse(responseCode = "404", description = "Agent not found.")
    @APIResponse(responseCode = "409", description = "cascade=true against a version that is not the current one; nothing was deleted.")
    // @formatter:off
    Response deleteAgent(@PathParam("id") String id,
            @Parameter(name = "version", required = true, example = "1",
                    description = "Version of the Agent to delete.")
            @QueryParam("version") Integer version,
            @Parameter(description = "If true, permanently remove from database, and additionally destroy this "
                    + "Agent's signing keys in the secrets vault — irreversibly, since no endpoint can "
                    + "regenerate them, so an Agent restored from a backup afterwards cannot sign again. "
                    + "If false (default), soft-delete only: the vault keys are kept so the Agent stays "
                    + "restorable.")
            @QueryParam("permanent") @DefaultValue("false") Boolean permanent,
            @Parameter(description = "If true, also delete all packages "
                    + "and extension resources referenced by this agent. "
                    + "Workflows still referenced by another agent are skipped. "
                    + "Cascaded resources are always soft-deleted, even when permanent=true, "
                    + "so a resource shared at a different pinned version can be recovered.")
            @QueryParam("cascade") @DefaultValue("false") Boolean cascade);
    // @formatter:on
}
