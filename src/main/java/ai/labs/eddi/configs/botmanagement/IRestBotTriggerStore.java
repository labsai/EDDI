package ai.labs.eddi.configs.botmanagement;

import ai.labs.eddi.engine.model.BotTriggerConfiguration;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

//@Api(value = "Configurations -> (5) Bot Management", authorizations = {@Authorization(value = "eddi_auth")})
@Path("/bottriggerstore/bottriggers")
@Tag(name = "08. Bot Administration", description = "Deploy & Undeploy Bots")
public interface IRestBotTriggerStore {
    String resourceURI = "eddi://ai.labs.bottrigger/bottriggerstore/bottriggers/";


    @GET
    @Produces(MediaType.APPLICATION_JSON)
    List<BotTriggerConfiguration> readAllBotTriggers();

    @GET
    @Path("/{intent}")
    @Produces(MediaType.APPLICATION_JSON)
    BotTriggerConfiguration readBotTrigger(@PathParam("intent") String intent);

    @PUT
    @Path("/{intent}")
    @Consumes(MediaType.APPLICATION_JSON)
    Response updateBotTrigger(@PathParam("intent") String intent, BotTriggerConfiguration botTriggerConfiguration);

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    Response createBotTrigger(BotTriggerConfiguration botTriggerConfiguration);

    @DELETE
    @Path("/{intent}")
    Response deleteBotTrigger(@PathParam("intent") String intent);
}
