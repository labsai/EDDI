package ai.labs.channels.differ.storage;

import ai.labs.channels.differ.model.DifferBotMapping;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.Authorization;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.List;

@Path("/channels/differ/botMappings")
@Api(value = "Configurations -> (6) Channels --> Differ", authorizations = {@Authorization(value = "eddi_auth")})
public interface IRestDifferBotMappingStore {
    @GET
    @Path("{botIntent}")
    @Produces(MediaType.APPLICATION_JSON)
    @ApiResponse(code = 200, message = "Read Differ Bot Mapping by Intent.", response = DifferBotMapping.class)
    DifferBotMapping readDifferBotMapping(@PathParam("botIntent") String botIntent);

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @ApiResponse(code = 200, message = "Read All Differ Bot Mapping.", response = DifferBotMapping.class, responseContainer = "List")
    List<DifferBotMapping> readAllDifferBotMappings();

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @ApiResponse(code = 201, message = "Create Differ Bot Mapping.")
    Response createDifferBotMapping(DifferBotMapping differBotMapping);

    @PATCH
    @Path("{botIntent}")
    @ApiResponse(code = 200, message = "Add UserId to Differ Bot Mapping.")
    void addBotUserToDifferBotMapping(@PathParam("botIntent") String botIntent, String userId);

    @PATCH
    @ApiResponse(code = 200, message = "Remove UserId from Differ Bot Mapping.")
    void deleteBotUserIdFromDifferBotMappings(String userId);

    @DELETE
    @Path("{botIntent}")
    @ApiResponse(code = 200, message = "Delete Differ Bot Mapping.")
    void deleteDifferBotMapping(@PathParam("botIntent") String botIntent);
}
