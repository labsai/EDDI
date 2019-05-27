package ai.labs.rest.rest;

import ai.labs.models.BotDeploymentStatus;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import io.swagger.annotations.Authorization;
import org.jboss.resteasy.annotations.cache.NoCache;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.List;

import static ai.labs.models.Deployment.Environment;

/**
 * @author ginccc
 */
@Api(value = "Bot Administration", authorizations = {@Authorization(value = "eddi_auth")})
@Path("/administration")
public interface IRestBotAdministration {
    @POST
    @Path("/{environment}/deploy/{botId}")
    @ApiOperation(value = "Deploy bot.")
    Response deployBot(@PathParam("environment") Environment environment,
                       @PathParam("botId") String botId,
                       @ApiParam(name = "version", required = true, format = "integer", example = "1")
                       @QueryParam("version") Integer version,
                       @QueryParam("autoDeploy") @DefaultValue("true") Boolean autoDeploy);

    @POST
    @Path("/{environment}/undeploy/{botId}")
    @ApiOperation(value = "Undeploy bot.")
    Response undeployBot(@PathParam("environment") Environment environment,
                         @PathParam("botId") String botId,
                         @ApiParam(name = "version", required = true, format = "integer", example = "1")
                         @QueryParam("version") Integer version);

    @GET
    @NoCache
    @Path("/{environment}/deploymentstatus/{botId}")
    @Produces(MediaType.TEXT_PLAIN)
    @ApiOperation(value = "Get deployment status.")
    String getDeploymentStatus(@PathParam("environment") Environment environment,
                               @PathParam("botId") String botId,
                               @ApiParam(name = "version", required = true, format = "integer", example = "1")
                               @QueryParam("version") Integer version);

    @GET
    @NoCache
    @Path("/{environment}/deploymentstatus")
    @Produces(MediaType.APPLICATION_JSON)
    List<BotDeploymentStatus> getDeploymentStatuses(@PathParam("environment")
                                                    @DefaultValue("unrestricted") Environment environment);
}
