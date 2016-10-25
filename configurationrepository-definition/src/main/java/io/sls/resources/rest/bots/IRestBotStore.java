package io.sls.resources.rest.bots;

import io.sls.resources.rest.IRestVersionInfo;
import io.sls.resources.rest.bots.model.BotConfiguration;
import io.sls.resources.rest.documentdescriptor.model.DocumentDescriptor;
import org.jboss.resteasy.annotations.GZIP;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.net.URI;
import java.util.List;

/**
 * @author ginccc
 */
@Path("/botstore/bots")
public interface IRestBotStore extends IRestVersionInfo {
    String resourceURI = "resource://io.sls.bot/botstore/bots/";
    String versionQueryParam = "?version=";

    @GET
    @GZIP
    @Produces(MediaType.APPLICATION_JSON)
    List<DocumentDescriptor> readBotDescriptors(@QueryParam("filter") @DefaultValue("") String filter,
                                                @QueryParam("index") @DefaultValue("0") Integer index,
                                                @QueryParam("limit") @DefaultValue("20") Integer limit);

    @GET
    @GZIP
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    BotConfiguration readBot(@PathParam("id") String id, @QueryParam("version") Integer version) throws Exception;

    @PUT
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    URI updateBot(@PathParam("id") String id, @QueryParam("version") Integer version, @GZIP BotConfiguration botConfiguration);

    @PUT
    @Path("/{id}")
    @Consumes(MediaType.TEXT_PLAIN)
    Response updateResourceInBot(@PathParam("id") String id, @QueryParam("version") Integer version, @GZIP URI resourceURI);

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    Response createBot(@GZIP BotConfiguration botConfiguration);

    @DELETE
    @Path("/{id}")
    void deleteBot(@PathParam("id") String id, @QueryParam("version") Integer version);
}
