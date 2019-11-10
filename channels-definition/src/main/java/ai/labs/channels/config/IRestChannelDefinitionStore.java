package ai.labs.channels.config;

import ai.labs.channels.config.model.ChannelDefinition;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.Authorization;

import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.List;

@Api(value = "Configurations -> (6) Channels", authorizations = {@Authorization(value = "eddi_auth")})
@Path("/channelstore/channels")
public interface IRestChannelDefinitionStore {
    String resourceURI = "eddi://ai.labs.channel/channelstore/channels/";

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @ApiResponse(code = 200, message = "Read All ChannelDefinition.", response = ChannelDefinition.class, responseContainer = "List")
    @ApiOperation(value = "Read All ChannelDefinition.")
    List<ChannelDefinition> readAllChannelDefinitions();

    @GET
    @Path("/{name}")
    @Produces(MediaType.APPLICATION_JSON)
    @ApiResponse(code = 200, message = "Read ChannelDefinition by name.", response = ChannelDefinition.class)
    @ApiOperation(value = "Read ChannelDefinition by name.")
    ChannelDefinition readChannelDefinition(@PathParam("name") String name);

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @ApiResponse(code = 200, message = "Create ChannelDefinition.")
    @ApiOperation(value = "Create ChannelDefinition.")
    Response createChannelDefinition(ChannelDefinition channelDefinition);

    @DELETE
    @Path("/{name}")
    @ApiResponse(code = 200, message = "Remove ChannelDefinition.")
    @ApiOperation(value = "Remove ChannelDefinition.")
    Response deleteChannelDefinition(@PathParam("name") String name);
}
