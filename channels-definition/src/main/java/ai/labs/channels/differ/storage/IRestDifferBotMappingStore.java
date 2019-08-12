package ai.labs.channels.differ.storage;

import ai.labs.channels.differ.model.DifferBotMapping;
import io.swagger.annotations.Api;
import io.swagger.annotations.Authorization;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import java.util.List;

@Path("/channels/differ/botMappings")
@Api(value = "Configurations -> (6) Channels --> Differ", authorizations = {@Authorization(value = "eddi_auth")})
public interface IRestDifferBotMappingStore {
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    DifferBotMapping readDifferBotMapping(String botIntent);

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    List<DifferBotMapping> readAllDifferBotMappings();

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    void createDifferBotMapping(DifferBotMapping differBotMapping);

    @POST
    @Path("{botIntent}")
    void addBotUserToDifferBotMapping(@PathParam("botIntent") String botIntent, String userId);

    @DELETE
    void deleteBotUserIdFromDifferBotMappings(String userId);

    @DELETE
    @Path("{botIntent}")
    void deleteDifferBotMapping(@PathParam("botIntent") String botIntent);
}
