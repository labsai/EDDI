package ai.labs.eddi.engine.api;

import ai.labs.eddi.engine.model.AgentDeploymentStatus;
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
@Tag(name = "Agent Administration")
@RolesAllowed({"eddi-admin", "eddi-editor"})
public interface IRestAgentAdministration {
    @POST
    @Path("/{environment}/deploy/{agentId}")
    @Operation(summary = "Deploy an agent", description = "Deploys the specified agent version to the given environment.")
    @APIResponse(responseCode = "200", description = "Agent deployed (or accepted if async).")
    @APIResponse(responseCode = "404", description = "Agent not found.")
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
    @Operation(summary = "Undeploy an agent", description = "Undeploys an agent from the given environment.")
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
    @Path("/{environment}/deploymentstatus/{agentId}")
    @Produces({MediaType.APPLICATION_JSON, MediaType.TEXT_PLAIN})
    @Operation(summary = "Get deployment status", description = "Returns JSON by default. Use ?format=text for plain text (deprecated).")
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
