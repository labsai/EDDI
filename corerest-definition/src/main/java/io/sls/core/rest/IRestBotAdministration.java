package io.sls.core.rest;

import io.sls.memory.model.Deployment;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

/**
 * User: jarisch
 * Date: 27.08.12
 * Time: 15:06
 */
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
