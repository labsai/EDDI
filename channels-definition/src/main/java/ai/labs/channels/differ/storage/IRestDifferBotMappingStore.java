package ai.labs.channels.differ.storage;

import ai.labs.channels.differ.model.DifferBotMapping;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import java.util.List;

@Path("/differ/botMappings")
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
