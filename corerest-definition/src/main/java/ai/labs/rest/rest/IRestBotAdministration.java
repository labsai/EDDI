package ai.labs.rest.rest;

import ai.labs.memory.model.Deployment;
import io.swagger.annotations.Api;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

/**
 * @author ginccc
 */
@Api(value = "bot administration")
@Path("/administration")
public interface IRestBotAdministration {
    @POST
    @Path("/{environment}/deploy/{botId}")
    Response deployBot(@PathParam("environment") Deployment.Environment environment,
                       @PathParam("botId") String botId,
                       @QueryParam("version") Integer version) throws Exception;

    @GET
    @Path("/{environment}/deploymentstatus/{botId}")
    @Produces(MediaType.TEXT_PLAIN)
    String getDeploymentStatus(@PathParam("environment") Deployment.Environment environment,
                               @PathParam("botId") String botId,
                               @QueryParam("version") Integer version) throws Exception;
}
