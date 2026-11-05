/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.api;

import ai.labs.eddi.engine.setup.CreateApiAgentRequest;
import ai.labs.eddi.engine.setup.SetupAgentRequest;
import ai.labs.eddi.engine.setup.SyncApiAgentRequest;
import jakarta.annotation.security.RolesAllowed;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * REST API for one-command agent setup. Delegates to
 * {@link ai.labs.eddi.engine.setup.AgentSetupService}.
 */
@Path("/administration/agents")
@Tag(name = "Agents / Setup", description = "One-command agent creation and deployment")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("eddi-admin")
public interface IRestAgentSetup {

    @POST
    @Path("/setup")
    @Operation(summary = "Setup a standard agent", description = "Creates a fully configured agent with parser, behavior rules, LLM configuration, "
            + "optional output set, workflow, and agent — then optionally deploys it.")
    @APIResponse(responseCode = "200", description = "Agent setup result with resource URIs.")
    @APIResponse(responseCode = "400", description = "Invalid setup request.")
    Response setupAgent(SetupAgentRequest request);

    @POST
    @Path("/setup-api")
    @Operation(summary = "Create an API agent from an OpenAPI spec", description = "Parses an OpenAPI specification, "
            + "generates HttpCalls configurations grouped by tag, "
            + "creates all necessary resources (parser, behavior, LLM, workflow), and optionally deploys the agent.")
    @APIResponse(responseCode = "200", description = "API agent setup result with resource URIs; for \"dryRun\": true, the resources "
            + "the setup would create, with nothing written.")
    @APIResponse(responseCode = "400", description = "Invalid OpenAPI spec or request.")
    Response createApiAgent(CreateApiAgentRequest request);

    @POST
    @Path("/{agentId}/setup-api/sync")
    @Operation(summary = "Re-sync an API agent from a newer OpenAPI spec", description = "Regenerates the httpcalls configurations "
            + "setup-api created for the agent, keeps everything else (prompt, model, hitlConfig, toolQuotas, responseSchema, hand-written "
            + "tools), adds the request's requireApproval patterns to the gate without removing any, and saves a new agent version as a "
            + "compatible save, so running conversations continue on it once deployed. Refused with 400, nothing written, when a removed "
            + "tool is still referenced. \"dryRun\": true reports the changes and writes nothing.")
    @APIResponse(responseCode = "200", description = "Added, changed and removed tools, and the new agent version.")
    @APIResponse(responseCode = "400", description = "Invalid request, no generated configuration, or removed tools still referenced.")
    @APIResponse(responseCode = "404", description = "Agent not found.")
    Response syncApiAgent(@PathParam("agentId") String agentId, SyncApiAgentRequest request);
}
