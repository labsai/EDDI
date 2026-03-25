package ai.labs.eddi.engine.api;

import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.model.Deployment;
import ai.labs.eddi.engine.model.InputData;
import jakarta.ws.rs.*;
import jakarta.ws.rs.container.AsyncResponse;
import jakarta.ws.rs.container.Suspended;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.resteasy.reactive.NoCache;

import java.util.List;
import java.util.Map;

/**
 * v6 simplified conversation API. Start uses agentId; all other operations use
 * only conversationId.
 */
@Path("/agents")
@Tag(name = "09. Talk to Agents", description = "Communicate with agents")
public interface IRestAgentEngine {

    // --- Start conversation (still needs agentId) ---

    @POST
    @Path("/{agentId}/start")
    @Operation(description = "Start conversation.")
    Response startConversation(@PathParam("agentId") String agentId,
            @QueryParam("environment") @DefaultValue("production") Deployment.Environment environment, @QueryParam("userId") String userId);

    @POST
    @Path("/{agentId}/start")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(description = "Start conversation with context.")
    Response startConversationWithContext(@PathParam("agentId") String agentId,
            @QueryParam("environment") @DefaultValue("production") Deployment.Environment environment, @QueryParam("userId") String userId,
            Map<String, Context> context);

    // --- End conversation ---

    @POST
    @Path("/{conversationId}/endConversation")
    @Operation(description = "End conversation.")
    Response endConversation(@PathParam("conversationId") String conversationId);

    // --- Read / Log ---

    @GET
    @NoCache
    @Path("/{conversationId}/log")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(description = "Read conversation log. outputType=text || json")
    Response readConversationLog(@PathParam("conversationId") String conversationId,
            @QueryParam("outputType") @DefaultValue("json") String outputType, @QueryParam("logSize") @DefaultValue("-1") Integer logSize);

    @GET
    @NoCache
    @Path("/{conversationId}")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(description = "Read conversation.")
    SimpleConversationMemorySnapshot readConversation(@PathParam("conversationId") String conversationId,
            @QueryParam("returnDetailed") @DefaultValue("false") Boolean returnDetailed,
            @QueryParam("returnCurrentStepOnly") @DefaultValue("true") Boolean returnCurrentStepOnly,
            @QueryParam("returningFields") List<String> returningFields);

    @GET
    @Path("/{conversationId}/status")
    @Operation(description = "Get conversation state.")
    ConversationState getConversationState(@PathParam("conversationId") String conversationId);

    // --- Talk (say) ---

    @POST
    @Path("/{conversationId}/rerun")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(description = "Rerun last conversation step.")
    void rerunLastConversationStep(@PathParam("conversationId") String conversationId, @QueryParam("language") String language,
            @QueryParam("returnDetailed") @DefaultValue("false") Boolean returnDetailed,
            @QueryParam("returnCurrentStepOnly") @DefaultValue("true") Boolean returnCurrentStepOnly,
            @QueryParam("returningFields") List<String> returningFields, @Suspended final AsyncResponse response);

    @POST
    @Path("/{conversationId}")
    @Consumes(MediaType.TEXT_PLAIN)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(description = "Talk to agent.")
    void say(@PathParam("conversationId") String conversationId, @QueryParam("returnDetailed") @DefaultValue("false") Boolean returnDetailed,
            @QueryParam("returnCurrentStepOnly") @DefaultValue("true") Boolean returnCurrentStepOnly,
            @QueryParam("returningFields") List<String> returningFields, @DefaultValue("") String message, @Suspended final AsyncResponse response);

    @POST
    @Path("/{conversationId}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(description = "Talk to agent with context.")
    void sayWithinContext(@PathParam("conversationId") String conversationId,
            @QueryParam("returnDetailed") @DefaultValue("false") Boolean returnDetailed,
            @QueryParam("returnCurrentStepOnly") @DefaultValue("true") Boolean returnCurrentStepOnly,
            @QueryParam("returningFields") List<String> returningFields, InputData inputData, @Suspended final AsyncResponse response);

    // --- Undo / Redo ---

    @GET
    @Path("/{conversationId}/undo")
    @Produces(MediaType.TEXT_PLAIN)
    @Operation(description = "Is UNDO available?")
    Boolean isUndoAvailable(@PathParam("conversationId") String conversationId);

    @POST
    @Path("/{conversationId}/undo")
    @Operation(description = "UNDO last conversation step.")
    Response undo(@PathParam("conversationId") String conversationId);

    @GET
    @Path("/{conversationId}/redo")
    @Produces(MediaType.TEXT_PLAIN)
    @Operation(description = "Is REDO available?")
    Boolean isRedoAvailable(@PathParam("conversationId") String conversationId);

    @POST
    @Path("/{conversationId}/redo")
    @Operation(description = "REDO last conversation step.")
    Response redo(@PathParam("conversationId") String conversationId);
}
