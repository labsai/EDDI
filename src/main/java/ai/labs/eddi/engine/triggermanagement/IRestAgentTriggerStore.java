package ai.labs.eddi.engine.triggermanagement;

import ai.labs.eddi.engine.triggermanagement.model.AgentTriggerConfiguration;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

//@Api(value = "Configurations -> (5) Agent Management", authorizations = {@Authorization(value = "eddi_auth")})
@Path("/AgentTriggerStore/bottriggers")
@Tag(name = "08. Agent Administration", description = "Deploy & Undeploy Bots")
public interface IRestAgentTriggerStore {
    String resourceURI = "eddi://ai.labs.agentTrigger/AgentTriggerStore/bottriggers/";


    @GET
    @Produces(MediaType.APPLICATION_JSON)
    List<AgentTriggerConfiguration> readAllBotTriggers();

    @GET
    @Path("/{intent}")
    @Produces(MediaType.APPLICATION_JSON)
    AgentTriggerConfiguration readBotTrigger(@PathParam("intent") String intent);

    @PUT
    @Path("/{intent}")
    @Consumes(MediaType.APPLICATION_JSON)
    Response updateBotTrigger(@PathParam("intent") String intent, AgentTriggerConfiguration AgentTriggerConfiguration);

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    Response createAgentTrigger(AgentTriggerConfiguration AgentTriggerConfiguration);

    @DELETE
    @Path("/{intent}")
    Response deleteBotTrigger(@PathParam("intent") String intent);
}
