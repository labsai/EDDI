package ai.labs.eddi.engine.api;

import ai.labs.eddi.engine.model.InputData;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import jakarta.ws.rs.*;
import jakarta.ws.rs.container.AsyncResponse;
import jakarta.ws.rs.container.Suspended;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;

/**
 * Managed agent endpoint — automatically resolves the active conversation for a
 * given intent/userId pair.
 */
@Path("/agents/managed")
@Tag(name = "Conversations")
public interface IRestAgentManagement {

    @GET
    @Path("/{intent}/{userId}")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Load managed conversation", description = "Loads the active conversation for the given intent and user.")
    // @formatter:off
    void loadConversationMemory(@PathParam("intent") String intent,
            @PathParam("userId") String userId,
            @Parameter(name = "language", example = "en")
            @QueryParam("language") String language,
            @Parameter(name = "returnDetailed", example = "false")
            @QueryParam("returnDetailed") @DefaultValue("false")
            Boolean returnDetailed,
            @Parameter(name = "returnCurrentStepOnly", example = "true")
            @QueryParam("returnCurrentStepOnly") @DefaultValue("true")
            Boolean returnCurrentStepOnly,
            @QueryParam("returningFields") List<String> returningFields,
            @Suspended final AsyncResponse response);
    // @formatter:on

    @POST
    @Path("/{intent}/{userId}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(operationId = "sayWithinManagedContext",
               summary = "Talk to managed agent with context",
               description = "Send a structured message with context "
                       + "to the managed conversation.")
    void sayWithinContext(@PathParam("intent") String intent, @PathParam("userId") String userId,
                          @QueryParam("returnDetailed")
                          @DefaultValue("false") Boolean returnDetailed,
                          @QueryParam("returnCurrentStepOnly")
                          @DefaultValue("true") Boolean returnCurrentStepOnly,
                          @QueryParam("returningFields") List<String> returningFields, InputData inputData, @Suspended final AsyncResponse response);

    @POST
    @Path("/{intent}/{userId}/endConversation")
    @Operation(summary = "End managed conversation", description = "Ends the active conversation for the given intent and user.")
    @APIResponse(responseCode = "200", description = "Conversation ended.")
    Response endCurrentConversation(@PathParam("intent") String intent, @PathParam("userId") String userId);

    @GET
    @Path("/{intent}/{userId}/undo")
    @Produces(MediaType.TEXT_PLAIN)
    @Operation(operationId = "isManagedUndoAvailable",
               summary = "Check undo availability (managed)",
               description = "Returns true if undo is available "
                       + "for the managed conversation.")
    Boolean isUndoAvailable(@PathParam("intent") String intent, @PathParam("userId") String userId);

    @POST
    @Path("/{intent}/{userId}/undo")
    @Operation(operationId = "managedUndo",
               summary = "Undo last step (managed)",
               description = "Undoes the last conversation step "
                       + "for the managed conversation.")
    @APIResponse(responseCode = "200", description = "Undo successful.")
    Response undo(@PathParam("intent") String intent, @PathParam("userId") String userId);

    @GET
    @Path("/{intent}/{userId}/redo")
    @Produces(MediaType.TEXT_PLAIN)
    @Operation(operationId = "isManagedRedoAvailable",
               summary = "Check redo availability (managed)",
               description = "Returns true if redo is available "
                       + "for the managed conversation.")
    Boolean isRedoAvailable(@PathParam("intent") String intent, @PathParam("userId") String userId);

    @POST
    @Path("/{intent}/{userId}/redo")
    @Operation(operationId = "managedRedo",
               summary = "Redo last undone step (managed)",
               description = "Redoes the last undone step "
                       + "for the managed conversation.")
    @APIResponse(responseCode = "200", description = "Redo successful.")
    Response redo(@PathParam("intent") String intent, @PathParam("userId") String userId);
}
