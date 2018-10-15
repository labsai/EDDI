package ai.labs.resources.rest.botmanagement;

import ai.labs.models.BotTriggerConfiguration;
import io.swagger.annotations.Api;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;

@Api(value = "configurations")
@Path("/bottriggerstore/bottriggers")
public interface IRestBotTriggerStore {
    String resourceURI = "eddi://ai.labs.bottrigger/bottriggerstore/bottriggers/";

    @GET
    @Path("/{intent}")
    @Produces(MediaType.APPLICATION_JSON)
    BotTriggerConfiguration readBotTrigger(@PathParam("intent") String intent);

    @PUT
    @Path("/{intent}")
    @Consumes(MediaType.APPLICATION_JSON)
    void updateBotTrigger(@PathParam("intent") String intent, BotTriggerConfiguration botTriggerConfiguration);

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    void createBotTrigger(BotTriggerConfiguration botTriggerConfiguration);

    @DELETE
    @Path("/{intent}")
    void deleteBotTrigger(@PathParam("intent") String id);
}
