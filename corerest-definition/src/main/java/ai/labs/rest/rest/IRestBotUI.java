package ai.labs.rest.rest;

import ai.labs.memory.model.Deployment;
import org.jboss.resteasy.annotations.cache.NoCache;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;

/**
 * @author ginccc
 */
@Path("/ui")
public interface IRestBotUI {
    @GET
    @NoCache
    @Path("/{environment}/{botId}")
    @Produces(MediaType.TEXT_HTML)
    String viewBotUI(@PathParam("environment") Deployment.Environment environment,
                     @PathParam("botId") String botId,
                     @QueryParam("lang") String language,
                     @QueryParam("loc") String location,
                     @QueryParam("ui") String uiIdentifier,
                     @QueryParam("targetDevice") String targetDevice);

    @GET
    @NoCache
    @Path("/{environment}")
    @Produces(MediaType.TEXT_HTML)
    String viewUI(@PathParam("environment") Deployment.Environment environment,
                  @QueryParam("lang") String language,
                  @QueryParam("loc") String location,
                  @QueryParam("targetDevice") String targetDevice);

    @GET
    @NoCache
    @Path("/")
    @Produces(MediaType.TEXT_HTML)
    String viewUI(@QueryParam("lang") String language,
                  @QueryParam("loc") String location,
                  @QueryParam("targetDevice") String targetDevice);
}
