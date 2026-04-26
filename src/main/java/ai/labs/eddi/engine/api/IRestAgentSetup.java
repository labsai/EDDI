/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.api;

import ai.labs.eddi.engine.setup.CreateApiAgentRequest;
import ai.labs.eddi.engine.setup.SetupAgentRequest;
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
@Tag(name = "Agent Setup")
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
    @APIResponse(responseCode = "200", description = "API agent setup result with resource URIs.")
    @APIResponse(responseCode = "400", description = "Invalid OpenAPI spec or request.")
    Response createApiAgent(CreateApiAgentRequest request);
}
