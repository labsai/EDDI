package ai.labs.eddi.engine.triggermanagement;

import ai.labs.eddi.engine.triggermanagement.model.AgentTriggerConfiguration;
import jakarta.annotation.security.RolesAllowed;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

@Path("/AgentTriggerStore/agenttriggers")
@Tag(name = "Agent Administration")
@RolesAllowed({"eddi-admin", "eddi-editor"})
public interface IRestAgentTriggerStore {
    String resourceURI = "eddi://ai.labs.agentTrigger/AgentTriggerStore/agenttriggers/";

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    List<AgentTriggerConfiguration> readAllAgentTriggers();

    @GET
    @Path("/{intent}")
    @Produces(MediaType.APPLICATION_JSON)
    AgentTriggerConfiguration readAgentTrigger(@PathParam("intent") String intent);

    @PUT
    @Path("/{intent}")
    @Consumes(MediaType.APPLICATION_JSON)
    Response updateAgentTrigger(@PathParam("intent") String intent, AgentTriggerConfiguration agentTriggerConfiguration);

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    Response createAgentTrigger(AgentTriggerConfiguration agentTriggerConfiguration);

    @DELETE
    @Path("/{intent}")
    Response deleteAgentTrigger(@PathParam("intent") String intent);
}
