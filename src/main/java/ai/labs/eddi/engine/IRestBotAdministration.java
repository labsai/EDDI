package ai.labs.eddi.engine;

import ai.labs.eddi.models.BotDeploymentStatus;
import io.swagger.v3.oas.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.resteasy.annotations.cache.NoCache;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;

import static ai.labs.eddi.models.Deployment.Environment;

/**
 * @author ginccc
 */
// @Api(value = "Bot Administration", authorizations = {@Authorization(value = "eddi_auth")})
@Path("/administration")
@Tag(name = "08. Bot Administration", description = "Deploy & Undeploy Bots")
public interface IRestBotAdministration {
    @POST
    @Path("/{environment}/deploy/{botId}")
    @Operation(description = "Deploy bot.")
    Response deployBot(@PathParam("environment") Environment environment,
                       @PathParam("botId") String botId,
                       @Parameter(name = "version", required = true, example = "1")
                       @QueryParam("version") Integer version,
                       @QueryParam("autoDeploy") @DefaultValue("true") Boolean autoDeploy);

    @POST
    @Path("/{environment}/undeploy/{botId}")
    @Operation(description = "Undeploy bot.")
    Response undeployBot(@PathParam("environment") Environment environment,
                         @PathParam("botId") String botId,
                         @Parameter(name = "version", required = true, example = "1")
                         @QueryParam("version") Integer version,
                         @QueryParam("endAllActiveConversations") @DefaultValue("false") Boolean endAllActiveConversations,
                         @QueryParam("undeployThisAndAllPreviousBotVersions") @DefaultValue("false") Boolean undeployThisAndAllPreviousBotVersions);

    @GET
    @NoCache
    @Path("/{environment}/deploymentstatus/{botId}")
    @Produces(MediaType.TEXT_PLAIN)
    @Operation(description = "Get deployment status.")
    String getDeploymentStatus(@PathParam("environment") Environment environment,
                               @PathParam("botId") String botId,
                               @Parameter(name = "version", required = true, example = "1")
                               @QueryParam("version") Integer version);

    @GET
    @NoCache
    @Path("/{environment}/deploymentstatus")
    @Produces(MediaType.APPLICATION_JSON)
    List<BotDeploymentStatus> getDeploymentStatuses(@PathParam("environment")
                                                    @DefaultValue("unrestricted") Environment environment);
}
