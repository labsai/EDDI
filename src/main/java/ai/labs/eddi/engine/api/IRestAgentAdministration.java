/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.api;

import ai.labs.eddi.engine.model.AgentDeploymentStatus;
import ai.labs.eddi.engine.model.DeploymentImpact;
import ai.labs.eddi.engine.model.DeploymentPreflight;
import jakarta.annotation.security.RolesAllowed;
import org.eclipse.microprofile.openapi.annotations.Operation;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.resteasy.reactive.NoCache;

import java.util.List;

import static ai.labs.eddi.engine.model.Deployment.Environment;

/**
 * REST API for agent deployment lifecycle management.
 */
@Path("/administration")
@Tag(name = "Agents / Administration", description = "Deploy, undeploy, trigger, and monitor agents")
@RolesAllowed({"eddi-admin", "eddi-editor"})
public interface IRestAgentAdministration {
    @POST
    @Path("/{environment}/deploy/{agentId}")
    @Operation(summary = "Deploy an agent", description = "Deploys the specified agent version to the given environment. "
            + "Subject to the tenant's maxAgentsPerTenant quota, which counts distinct agent ids — "
            + "redeploying an agent or bumping its version never consumes additional capacity.")
    @APIResponse(responseCode = "200", description = "Agent deployed (or accepted if async). With waitForCompletion=true the body "
            + "carries status; a deployment in ERROR also carries `error` and `failure` (see GET …/deploymentstatus/{agentId}?format=detailed).")
    @APIResponse(responseCode = "404", description = "Agent not found.")
    @APIResponse(responseCode = "429", description = "Tenant agent quota exceeded; undeploy an agent before deploying another.")
    // @formatter:off
    Response deployAgent(@PathParam("environment") Environment environment,
            @PathParam("agentId") String agentId,
            @Parameter(name = "version", required = true, example = "1")
            @QueryParam("version") Integer version,
            @QueryParam("autoDeploy") @DefaultValue("true") Boolean autoDeploy,
            @Parameter(description = "If true, wait for deployment to complete "
                    + "(up to 30s) and return the final status. "
                    + "If false (default), return 202 Accepted immediately.")
            @QueryParam("waitForCompletion") @DefaultValue("false")
            Boolean waitForCompletion);
    // @formatter:on

    @POST
    @Path("/{environment}/undeploy/{agentId}")
    @Operation(summary = "Undeploy an agent", description = "Undeploys an agent from the given environment. "
            + "Open conversations on the version block it with 409 unless endAllActiveConversations=true ends them — "
            + "except when another deployed version with the same compatibility generation is there to take them: "
            + "those conversations move to it on their next turn and neither block the undeploy nor are ended.")
    @APIResponse(responseCode = "200", description = "Agent undeployed.")
    @APIResponse(responseCode = "404", description = "Agent not found.")
    Response undeployAgent(@PathParam("environment") Environment environment, @PathParam("agentId") String agentId,
                           @Parameter(name = "version", required = true, example = "1")
                           @QueryParam("version") Integer version,
                           @QueryParam("endAllActiveConversations")
                           @DefaultValue("false") Boolean endAllActiveConversations,
                           @QueryParam("undeployThisAndAllPreviousAgentVersions")
                           @DefaultValue("false") Boolean undeployThisAndAllPreviousAgentVersions);

    @GET
    @NoCache
    @Path("/{environment}/deploymentimpact/{agentId}")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Preview what deploying a version does to running conversations",
               description = "For every other deployed version of the agent in this environment: its open conversations, "
                       + "and whether they FOLLOW the given version on their next turn (same compatibility generation, "
                       + "older version) or STAY where they are (a breaking change, a version without a generation, or "
                       + "a newer version). Read-only; works before or after the version is deployed.")
    @APIResponse(responseCode = "200", description = "The impact of deploying this version.")
    @APIResponse(responseCode = "404", description = "Agent version not found.")
    DeploymentImpact getDeploymentImpact(@PathParam("environment") Environment environment, @PathParam("agentId") String agentId,
                                         @Parameter(name = "version", required = true, example = "2")
                                         @QueryParam("version") Integer version);

    @GET
    @NoCache
    @Path("/{environment}/deploy/{agentId}/preflight")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Check what would stop a deploy, before deploying",
               description = "Runs the vault-grant check a deploy runs and lists every restricted secret the agent uses but is not "
                       + "granted. A grant lists agent ids, so a NEWLY created agent is never on one: run this right after creating "
                       + "an agent and add it to each listed secret's grant (POST /secretstore/secrets/{tenantId}/{keyName}/grant/"
                       + "agents/{agentId}, eddi-admin) before the first deploy. ready=false means the deploy would be refused "
                       + "(eddi.vault.grant-enforcement=enforce). Read-only; never returns a secret value. The ids on a grant "
                       + "(allowedAgents) are returned to administrators only.")
    @APIResponse(responseCode = "200", description = "What the deploy would run into.")
    @APIResponse(responseCode = "403", description = "The caller may not edit (and so may not deploy) this agent.")
    @APIResponse(responseCode = "404", description = "Agent version not found.")
    DeploymentPreflight preflightDeployment(@PathParam("environment") Environment environment, @PathParam("agentId") String agentId,
                                            @Parameter(name = "version", required = true, example = "1")
                                            @QueryParam("version") Integer version);

    @GET
    @NoCache
    @Path("/{environment}/deploymentstatus/{agentId}")
    @Produces({MediaType.APPLICATION_JSON, MediaType.TEXT_PLAIN})
    @Operation(summary = "Get deployment status", description = "Returns JSON by default. Use ?format=text for plain text (deprecated). "
            + "?format=detailed adds `failure` for a deployment in ERROR — its code (VAULT_GRANT_MISSING or DEPLOYMENT_FAILED), "
            + "message, and for a missing grant the secrets and the grant call that fixes it — to a caller who may edit the agent.")
    @APIResponse(responseCode = "200", description = "Deployment status.")
    @APIResponse(responseCode = "404", description = "Agent not found.")
    Response getDeploymentStatus(@PathParam("environment") Environment environment, @PathParam("agentId") String agentId,
                                 @Parameter(name = "version", required = true, example = "1")
                                 @QueryParam("version") Integer version,
                                 @QueryParam("format")
                                 @DefaultValue("json") String format);

    @GET
    @NoCache
    @Path("/{environment}/deploymentstatus")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "List all deployment statuses", description = "Returns deployment status for all agents in the given environment.")
    @APIResponse(responseCode = "200", description = "List of deployment statuses.")
    List<AgentDeploymentStatus> getDeploymentStatuses(@PathParam("environment")
    @DefaultValue("production") Environment environment);
}
