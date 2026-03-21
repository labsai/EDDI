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
 * @author ginccc
 */
//@Api(value = "Bot Engine", authorizations = {@Authorization(value = "eddi_auth")})
@Path("/bots")
@Tag(name = "09. Talk to Bots", description = "Communicate with bots")
public interface IRestAgentEngine {

    /**
     * create new conversation
     *
     * @param environment [restricted|production|test]
     * @param agentId       String
     * @return Response HTTP 201 URI conversation ID
     */
    @POST
    @Path("/{environment}/{agentId}")
    @Operation(description = "Start conversation.")
    Response startConversation(@PathParam("environment") Deployment.Environment environment,
                               @PathParam("agentId") String agentId,
                               @QueryParam("userId") String userId);

    /**
     * create new conversation
     *
     * @param environment [restricted|production|test]
     * @param agentId       String
     * @param context     json context Map<String, Context>
     * @return Response HTTP 201 URI conversation ID
     */
    @POST
    @Path("/{environment}/{agentId}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(description = "Start conversation with context.")
    Response startConversationWithContext(@PathParam("environment") Deployment.Environment environment,
                                          @PathParam("agentId") String agentId,
                                          @QueryParam("userId") String userId, Map<String, Context> context);

    @POST
    @Path("/{conversationId}/endConversation")
    @Operation(description = "End conversation.")
    Response endConversation(@PathParam("conversationId") String conversationId);

    @GET
    @NoCache
    @Path("/{conversationId}/log")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(description = "Read conversation. outputType=text || json")
    Response readConversationLog(@PathParam("conversationId") String conversationId,
                                 @QueryParam("outputType") @DefaultValue("json") String outputType,
                                 @QueryParam("logSize") @DefaultValue("-1") Integer logSize);

    @GET
    @NoCache
    @Path("/{environment}/{agentId}/{conversationId}")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(description = "Read conversation.")
    SimpleConversationMemorySnapshot readConversation(@PathParam("environment") Deployment.Environment environment,
                                                      @PathParam("agentId") String agentId,
                                                      @PathParam("conversationId") String conversationId,
                                                      @QueryParam("returnDetailed") @DefaultValue("false") Boolean returnDetailed,
                                                      @QueryParam("returnCurrentStepOnly") @DefaultValue("true") Boolean returnCurrentStepOnly,
                                                      @QueryParam("returningFields") List<String> returningFields);


    @GET
    @Path("/{environment}/conversationstatus/{conversationId}")
    @Operation(description = "Get conversation state.")
    ConversationState getConversationState(@PathParam("environment") Deployment.Environment environment,
                                           @PathParam("conversationId") String conversationId);

    @POST
    @Path("/{environment}/{agentId}/{conversationId}/rerun")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(description = "Read conversation.")
    void rerunLastConversationStep(@PathParam("environment") Deployment.Environment environment,
                                   @PathParam("agentId") String agentId,
                                   @PathParam("conversationId") String conversationId,
                                   @QueryParam("language") String language,
                                   @QueryParam("returnDetailed") @DefaultValue("false") Boolean returnDetailed,
                                   @QueryParam("returnCurrentStepOnly") @DefaultValue("true") Boolean returnCurrentStepOnly,
                                   @QueryParam("returningFields") List<String> returningFields,
                                   @Suspended final AsyncResponse response);

    /**
     * talk to bot
     *
     * @param environment
     * @param agentId
     * @param conversationId
     * @param message
     * @return
     * @throws Exception
     */
    @POST
    @Path("/{environment}/{agentId}/{conversationId}")
    @Consumes(MediaType.TEXT_PLAIN)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(description = "Talk to agent.")
    void say(@PathParam("environment") Deployment.Environment environment,
             @PathParam("agentId") String agentId,
             @PathParam("conversationId") String conversationId,
             @QueryParam("returnDetailed") @DefaultValue("false") Boolean returnDetailed,
             @QueryParam("returnCurrentStepOnly") @DefaultValue("true") Boolean returnCurrentStepOnly,
             @QueryParam("returningFields") List<String> returningFields,
             @DefaultValue("") String message,
             @Suspended final AsyncResponse response);

    /**
     * talk to Agent with adding context information to it
     *
     * @param environment
     * @param agentId
     * @param conversationId
     * @param returningFields
     * @param inputData       of type ai.labs.models.InputData
     * @return
     * @throws Exception
     */
    @POST
    @Path("/{environment}/{agentId}/{conversationId}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(description = "Talk to Agent with context.")
    void sayWithinContext(@PathParam("environment") Deployment.Environment environment,
                          @PathParam("agentId") String agentId,
                          @PathParam("conversationId") String conversationId,
                          @QueryParam("returnDetailed") @DefaultValue("false") Boolean returnDetailed,
                          @QueryParam("returnCurrentStepOnly") @DefaultValue("true") Boolean returnCurrentStepOnly,
                          @QueryParam("returningFields") List<String> returningFields,
                          InputData inputData,
                          @Suspended final AsyncResponse response);

    @GET
    @Path("/{environment}/{agentId}/undo/{conversationId}")
    @Produces(MediaType.TEXT_PLAIN)
    @Operation(description = "Is UNDO available?")
    Boolean isUndoAvailable(@PathParam("environment") Deployment.Environment environment,
                            @PathParam("agentId") String agentId,
                            @PathParam("conversationId") String conversationId);

    @POST
    @Path("/{environment}/{agentId}/undo/{conversationId}")
    @Operation(description = "UNDO last conversation step.")
    Response undo(@PathParam("environment") Deployment.Environment environment,
                  @PathParam("agentId") String agentId,
                  @PathParam("conversationId") String conversationId);

    @GET
    @Path("/{environment}/{agentId}/redo/{conversationId}")
    @Produces(MediaType.TEXT_PLAIN)
    @Operation(description = "Is REDO available?")
    Boolean isRedoAvailable(@PathParam("environment") Deployment.Environment environment,
                            @PathParam("agentId") String agentId,
                            @PathParam("conversationId") String conversationId);

    @POST
    @Path("/{environment}/{agentId}/redo/{conversationId}")
    @Operation(description = "REDO last conversation step.")
    Response redo(@PathParam("environment") Deployment.Environment environment,
                  @PathParam("agentId") String agentId,
                  @PathParam("conversationId") String conversationId);
}
