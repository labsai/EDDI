package ai.labs.resources.rest.bots;

import ai.labs.resources.rest.IRestVersionInfo;
import ai.labs.resources.rest.bots.model.BotConfiguration;
import ai.labs.resources.rest.documentdescriptor.model.DocumentDescriptor;
import io.swagger.annotations.Api;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.net.URI;
import java.util.List;

/**
 * @author ginccc
 */
@Api(value = "botstore")
@Path("/botstore/bots")
public interface IRestBotStore extends IRestVersionInfo {
    String resourceURI = "eddi://ai.labs.bot/botstore/bots/";
    String versionQueryParam = "?version=";

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    List<DocumentDescriptor> readBotDescriptors(@QueryParam("filter") @DefaultValue("") String filter,
                                                @QueryParam("index") @DefaultValue("0") Integer index,
                                                @QueryParam("limit") @DefaultValue("20") Integer limit);

    @GET
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    BotConfiguration readBot(@PathParam("id") String id, @QueryParam("version") Integer version) throws Exception;

    @PUT
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    URI updateBot(@PathParam("id") String id, @QueryParam("version") Integer version, BotConfiguration botConfiguration);

    @PUT
    @Path("/{id}")
    @Consumes(MediaType.TEXT_PLAIN)
    Response updateResourceInBot(@PathParam("id") String id, @QueryParam("version") Integer version, URI resourceURI);

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    Response createBot(BotConfiguration botConfiguration);

    @DELETE
    @Path("/{id}")
    void deleteBot(@PathParam("id") String id, @QueryParam("version") Integer version);
}
