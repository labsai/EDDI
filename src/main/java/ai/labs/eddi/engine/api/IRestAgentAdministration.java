package ai.labs.eddi.engine.api;

import ai.labs.eddi.engine.model.AgentDeploymentStatus;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.resteasy.reactive.NoCache;

import java.util.List;

import static ai.labs.eddi.engine.model.Deployment.Environment;

/**
 * @author ginccc
 */
// @Api(value = "Bot Administration", authorizations = {@Authorization(value =
// "eddi_auth")})
@Path("/administration")
@Tag(name = "08. Agent Administration", description = "Deploy & Undeploy Bots")
public interface IRestAgentAdministration {
    @POST
    @Path("/{environment}/deploy/{agentId}")
    @Operation(description = "Deploy agent.")
    Response deployAgent(@PathParam("environment") Environment environment,
            @PathParam("agentId") String agentId,
            @Parameter(name = "version", required = true, example = "1") @QueryParam("version") Integer version,
            @QueryParam("autoDeploy") @DefaultValue("true") Boolean autoDeploy,
            @Parameter(description = "If true, wait for deployment to complete (up to 30s) and return the final status. " +
                    "If false (default), return 202 Accepted immediately.")
            @QueryParam("waitForCompletion") @DefaultValue("false") Boolean waitForCompletion);

    @POST
    @Path("/{environment}/undeploy/{agentId}")
    @Operation(description = "Undeploy agent.")
    Response undeployAgent(@PathParam("environment") Environment environment,
            @PathParam("agentId") String agentId,
            @Parameter(name = "version", required = true, example = "1") @QueryParam("version") Integer version,
            @QueryParam("endAllActiveConversations") @DefaultValue("false") Boolean endAllActiveConversations,
            @QueryParam("undeployThisAndAllPreviousBotVersions") @DefaultValue("false") Boolean undeployThisAndAllPreviousBotVersions);

    @GET
    @NoCache
    @Path("/{environment}/deploymentstatus/{agentId}")
    @Produces({ MediaType.APPLICATION_JSON, MediaType.TEXT_PLAIN })
    @Operation(description = "Get deployment status. Returns JSON by default. Use ?format=text for plain text (deprecated).")
    Response getDeploymentStatus(@PathParam("environment") Environment environment,
            @PathParam("agentId") String agentId,
            @Parameter(name = "version", required = true, example = "1") @QueryParam("version") Integer version,
            @QueryParam("format") @DefaultValue("json") String format);

    @GET
    @NoCache
    @Path("/{environment}/deploymentstatus")
    @Produces(MediaType.APPLICATION_JSON)
    List<AgentDeploymentStatus> getDeploymentStatuses(
            @PathParam("environment") @DefaultValue("production") Environment environment);
}
