package ai.labs.channels.config;

import ai.labs.channels.config.model.ChannelDefinition;
import io.swagger.annotations.Api;
import io.swagger.annotations.Authorization;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.List;

@Api(value = "Configurations -> (5) Bot Management -> Channels", authorizations = {@Authorization(value = "eddi_auth")})
@Path("/channelstore/channels")
public interface IRestChannelDefinitionStore {

    String resourceURI = "eddi://ai.labs.channel/channelstore/channels/";

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    List<ChannelDefinition> readAllChannelDefinitions();

    @GET
    @Path("/{name}")
    @Produces(MediaType.APPLICATION_JSON)
    ChannelDefinition readChannelDefinition(@PathParam("name") String name);

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    Response createChannelDefinition(ChannelDefinition channelDefinition);

    @DELETE
    @Path("/{name}")
    Response deleteChannelDefinition(@PathParam("name") String name);
}
