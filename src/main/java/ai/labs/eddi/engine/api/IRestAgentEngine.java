package ai.labs.eddi.engine.api;

import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.model.Deployment;
import ai.labs.eddi.engine.model.InputData;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.*;
import jakarta.ws.rs.container.AsyncResponse;
import jakarta.ws.rs.container.Suspended;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.resteasy.reactive.NoCache;

import java.util.List;
import java.util.Map;

/**
 * v6 simplified conversation API. Start uses agentId; all other operations use
 * only conversationId.
 */
@Path("/agents")
@Tag(name = "Conversations")
@RolesAllowed({"eddi-admin", "eddi-editor", "eddi-user"})
public interface IRestAgentEngine {

    // --- Start conversation (still needs agentId) ---

    @POST
    @Path("/{agentId}/start")
    @Operation(summary = "Start a conversation", description = "Creates a new conversation for the specified agent in the given environment. "
            + "Returns 201 with Location header pointing to the new conversation.")
    @APIResponse(responseCode = "201", description = "Conversation created. See Location header.")
    Response startConversation(
                               @PathParam("agentId") String agentId,
                               @Parameter(description = "Deployment environment.")
                               @QueryParam("environment")
                               @DefaultValue("production") Deployment.Environment environment,
                               @Parameter(description = "Optional user identifier.")
                               @QueryParam("userId") String userId);

    @POST
    @Path("/{agentId}/start")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Start a conversation with context", description = "Creates a new conversation with initial context map. "
            + "Returns 201 with Location header pointing to the new conversation.")
    @APIResponse(responseCode = "201", description = "Conversation created. See Location header.")
    Response startConversationWithContext(
                                          @PathParam("agentId") String agentId,
                                          @Parameter(description = "Deployment environment.")
                                          @QueryParam("environment")
                                          @DefaultValue("production") Deployment.Environment environment,
                                          @Parameter(description = "Optional user identifier.")
                                          @QueryParam("userId") String userId,
                                          Map<String, Context> context);

    // --- End conversation ---

    @POST
    @Path("/{conversationId}/endConversation")
    @Operation(summary = "End a conversation", description = "Marks the conversation as ended. No further messages can be sent.")
    @APIResponse(responseCode = "200", description = "Conversation ended.")
    @APIResponse(responseCode = "404", description = "Conversation not found.")
    Response endConversation(@PathParam("conversationId") String conversationId);

    // --- Read / Log ---

    @GET
    @NoCache
    @Path("/{conversationId}/log")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Read conversation log", description = "Returns conversation log in text or JSON format.")
    @APIResponse(responseCode = "200", description = "Conversation log.")
    @APIResponse(responseCode = "404", description = "Conversation not found.")
    Response readConversationLog(@PathParam("conversationId") String conversationId,
                                 @Parameter(description = "Output format: 'text' or 'json'.")
                                 @QueryParam("outputType")
                                 @DefaultValue("json") String outputType,
                                 @Parameter(description = "Number of log entries to return. -1 for all.")
                                 @QueryParam("logSize")
                                 @DefaultValue("-1") Integer logSize);

    @GET
    @NoCache
    @Path("/{conversationId}")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Read a conversation", description = "Returns conversation memory snapshot with configurable detail level.")
    @APIResponse(responseCode = "200", description = "Conversation snapshot.")
    @APIResponse(responseCode = "404", description = "Conversation not found.")
    SimpleConversationMemorySnapshot readConversation(
                                                      @PathParam("conversationId") String conversationId,
                                                      @Parameter(description = "Include detailed step data.")
                                                      @QueryParam("returnDetailed")
                                                      @DefaultValue("false") Boolean returnDetailed,
                                                      @Parameter(description = "Only return the current (latest) step.")
                                                      @QueryParam("returnCurrentStepOnly")
                                                      @DefaultValue("true") Boolean returnCurrentStepOnly,
                                                      @Parameter(description = "Filter fields to return.")
                                                      @QueryParam("returningFields") List<String> returningFields);

    @GET
    @Path("/{conversationId}/status")
    @Operation(summary = "Get conversation state", description = "Returns the current lifecycle state of the conversation.")
    @APIResponse(responseCode = "200", description = "Conversation state.")
    @APIResponse(responseCode = "404", description = "Conversation not found.")
    ConversationState getConversationState(@PathParam("conversationId") String conversationId);

    // --- Talk (say) ---

    @POST
    @Path("/{conversationId}/rerun")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Rerun last conversation step", description = "Re-executes the last conversation step, useful for retrying after errors.")
    void rerunLastConversationStep(@PathParam("conversationId") String conversationId,
                                   @Parameter(description = "Language code for NLP processing.")
                                   @QueryParam("language") String language,
                                   @QueryParam("returnDetailed")
                                   @DefaultValue("false") Boolean returnDetailed,
                                   @QueryParam("returnCurrentStepOnly")
                                   @DefaultValue("true") Boolean returnCurrentStepOnly,
                                   @QueryParam("returningFields") List<String> returningFields, @Suspended final AsyncResponse response);

    @POST
    @Path("/{conversationId}")
    @Consumes(MediaType.TEXT_PLAIN)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Send a message to the agent", description = "Send a plain text message and receive the agent's response.")
    void say(@PathParam("conversationId") String conversationId, @QueryParam("returnDetailed")
    @DefaultValue("false") Boolean returnDetailed,
             @QueryParam("returnCurrentStepOnly")
             @DefaultValue("true") Boolean returnCurrentStepOnly,
             @QueryParam("returningFields") List<String> returningFields, @DefaultValue("") String message, @Suspended final AsyncResponse response);

    @POST
    @Path("/{conversationId}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Send a message with context", description = "Send a structured message with context data and receive the agent's response.")
    void sayWithinContext(@PathParam("conversationId") String conversationId,
                          @QueryParam("returnDetailed")
                          @DefaultValue("false") Boolean returnDetailed,
                          @QueryParam("returnCurrentStepOnly")
                          @DefaultValue("true") Boolean returnCurrentStepOnly,
                          @QueryParam("returningFields") List<String> returningFields, InputData inputData, @Suspended final AsyncResponse response);

    // --- Undo / Redo ---

    @GET
    @Path("/{conversationId}/undo")
    @Produces(MediaType.TEXT_PLAIN)
    @Operation(summary = "Check if undo is available", description = "Returns true if the last conversation step can be undone.")
    Boolean isUndoAvailable(@PathParam("conversationId") String conversationId);

    @POST
    @Path("/{conversationId}/undo")
    @Operation(summary = "Undo last conversation step", description = "Reverts the last conversation step.")
    @APIResponse(responseCode = "200", description = "Undo successful.")
    Response undo(@PathParam("conversationId") String conversationId);

    @GET
    @Path("/{conversationId}/redo")
    @Produces(MediaType.TEXT_PLAIN)
    @Operation(summary = "Check if redo is available", description = "Returns true if a previously undone step can be redone.")
    Boolean isRedoAvailable(@PathParam("conversationId") String conversationId);

    @POST
    @Path("/{conversationId}/redo")
    @Operation(summary = "Redo last undone step", description = "Re-applies a previously undone conversation step.")
    @APIResponse(responseCode = "200", description = "Redo successful.")
    Response redo(@PathParam("conversationId") String conversationId);
}
