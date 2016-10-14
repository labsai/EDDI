package io.sls.core.rest;

import io.sls.memory.model.Deployment;
import org.jboss.resteasy.annotations.GZIP;
import org.jboss.resteasy.annotations.cache.NoCache;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;

/**
 * User: jarisch
 * Date: 14.09.12
 * Time: 10:14
 */
@Path("/ui")
public interface IRestBotUI {
    @GET
    @GZIP
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
    @GZIP
    @NoCache
    @Path("/{environment}")
    @Produces(MediaType.TEXT_HTML)
    String viewUI(@PathParam("environment") Deployment.Environment environment,
                  @QueryParam("lang") String language,
                  @QueryParam("loc") String location,
                  @QueryParam("targetDevice") String targetDevice);

    @GET
    @GZIP
    @NoCache
    @Path("/")
    @Produces(MediaType.TEXT_HTML)
    String viewUI(@QueryParam("lang") String language,
                  @QueryParam("loc") String location,
                  @QueryParam("targetDevice") String targetDevice);
}
