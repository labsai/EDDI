package ai.labs.eddi.engine;

import ai.labs.eddi.models.InputData;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;

import javax.ws.rs.*;
import javax.ws.rs.container.AsyncResponse;
import javax.ws.rs.container.Suspended;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.List;

// @Api(value = "Bot Management", authorizations = {@Authorization(value = "eddi_auth")})
@Path("/managedbots")
public interface IRestBotManagement {

    @GET
    @Path("/{intent}/{userId}")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(description = "Read conversation.")
    void loadConversationMemory(@PathParam("intent") String intent,
                                @PathParam("userId") String userId,
                                @Parameter(name = "language", example = "en")
                                @QueryParam("language") String language,
                                @Parameter(name = "returnDetailed", example = "false")
                                @QueryParam("returnDetailed") @DefaultValue("false") Boolean returnDetailed,
                                @Parameter(name = "returnCurrentStepOnly", example = "true")
                                @QueryParam("returnCurrentStepOnly") @DefaultValue("true") Boolean returnCurrentStepOnly,
                                @QueryParam("returningFields") List<String> returningFields,
                                @Suspended final AsyncResponse response);

    @POST
    @Path("/{intent}/{userId}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(description = "Talk to bot with context.")
    void sayWithinContext(@PathParam("intent") String intent,
                          @PathParam("userId") String userId,
                          @QueryParam("returnDetailed") @DefaultValue("false") Boolean returnDetailed,
                          @QueryParam("returnCurrentStepOnly") @DefaultValue("true") Boolean returnCurrentStepOnly,
                          @QueryParam("returningFields") List<String> returningFields,
                          InputData inputData,
                          @Suspended final AsyncResponse response);

    @POST
    @Path("/{intent}/{userId}/endConversation")
    @Operation(description = "End conversation.")
    Response endCurrentConversation(@PathParam("intent") String intent, @PathParam("userId") String userId);

    @GET
    @Path("/{intent}/{userId}/undo")
    @Produces(MediaType.TEXT_PLAIN)
    @Operation(description = "Is UNDO available?")
    Boolean isUndoAvailable(@PathParam("intent") String intent,
                            @PathParam("userId") String userId);

    @POST
    @Path("/{intent}/{userId}/undo")
    @Operation(description = "UNDO last conversation step.")
    Response undo(@PathParam("intent") String intent,
                  @PathParam("userId") String userId);

    @GET
    @Path("/{intent}/{userId}/redo")
    @Produces(MediaType.TEXT_PLAIN)
    @Operation(description = "Is REDO available?")
    Boolean isRedoAvailable(@PathParam("intent") String intent,
                            @PathParam("userId") String userId);

    @POST
    @Path("/{intent}/{userId}/redo")
    @Operation(description = "REDO last conversation step.")
    Response redo(@PathParam("intent") String intent,
                  @PathParam("userId") String userId);
}
