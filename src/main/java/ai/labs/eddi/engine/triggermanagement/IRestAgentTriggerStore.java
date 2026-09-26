/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.triggermanagement;

import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.triggermanagement.model.AgentTriggerConfiguration;
import jakarta.annotation.security.RolesAllowed;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

@Path("/AgentTriggerStore/agenttriggers")
@Tag(name = "Agents / Administration", description = "Deploy, undeploy, trigger, and monitor agents")
@RolesAllowed({"eddi-admin", "eddi-editor"})
public interface IRestAgentTriggerStore {
    String resourceURI = "eddi://ai.labs.agentTrigger/AgentTriggerStore/agenttriggers/";

    /**
     * The trigger exists but routes to an agent the caller may not use. A not-found
     * to HTTP clients (the status must not confirm another team's intent), but a
     * distinct type in-process: a caller that treats "not found" as "the trigger
     * was deleted" and cleans up after it must not do so for a refusal.
     */
    class TriggerNotVisibleException extends IResourceStore.ResourceNotFoundException {

        public TriggerNotVisibleException(String message) {
            super(message);
        }
    }

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
