package ai.labs.rest.restinterfaces;


import ai.labs.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.models.Context;
import ai.labs.models.ConversationState;
import ai.labs.models.Deployment;
import ai.labs.models.InputData;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.Authorization;
import org.jboss.resteasy.annotations.cache.NoCache;

import javax.ws.rs.Consumes;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.container.AsyncResponse;
import javax.ws.rs.container.Suspended;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.List;
import java.util.Map;

/**
 * @author ginccc
 */
@Api(value = "Bot Engine", authorizations = {@Authorization(value = "eddi_auth")})
@Path("/bots")
public interface IRestBotEngine {

    /**
     * create new conversation
     *
     * @param environment [restricted|unrestricted|test]
     * @param botId       String
     * @return Response HTTP 201 URI conversation ID
     */
    @POST
    @Path("/{environment}/{botId}")
    @ApiOperation(value = "Start conversation.")
    Response startConversation(@PathParam("environment") Deployment.Environment environment,
                               @PathParam("botId") String botId,
                               @QueryParam("userId") String userId);

    /**
     * create new conversation
     *
     * @param environment [restricted|unrestricted|test]
     * @param botId       String
     * @param context     json context Map<String, Context>
     * @return Response HTTP 201 URI conversation ID
     */
    @POST
    @Path("/{environment}/{botId}")
    @Consumes(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Start conversation with context.")
    Response startConversationWithContext(@PathParam("environment") Deployment.Environment environment,
                                          @PathParam("botId") String botId,
                                          @QueryParam("userId") String userId, Map<String, Context> context);

    @POST
    @Path("/{conversationId}/endConversation")
    @ApiOperation(value = "End conversation.")
    Response endConversation(@PathParam("conversationId") String conversationId);


    @GET
    @NoCache
    @Path("/{environment}/{botId}/{conversationId}")
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Read conversation.")
    SimpleConversationMemorySnapshot readConversation(@PathParam("environment") Deployment.Environment environment,
                                                      @PathParam("botId") String botId,
                                                      @PathParam("conversationId") String conversationId,
                                                      @QueryParam("returnDetailed") @DefaultValue("false") Boolean returnDetailed,
                                                      @QueryParam("returnCurrentStepOnly") @DefaultValue("true") Boolean returnCurrentStepOnly,
                                                      @QueryParam("returningFields") List<String> returningFields);

    @GET
    @Path("/{environment}/conversationstatus/{conversationId}")
    @ApiOperation(value = "Get conversation state.")
    ConversationState getConversationState(@PathParam("environment") Deployment.Environment environment,
                                           @PathParam("conversationId") String conversationId);

    /**
     * talk to bot
     *
     * @param environment
     * @param botId
     * @param conversationId
     * @param message
     * @return
     * @throws Exception
     */
    @POST
    @Path("/{environment}/{botId}/{conversationId}")
    @Consumes(MediaType.TEXT_PLAIN)
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Talk to bot.")
    void say(@PathParam("environment") Deployment.Environment environment,
             @PathParam("botId") String botId,
             @PathParam("conversationId") String conversationId,
             @QueryParam("returnDetailed") @DefaultValue("false") Boolean returnDetailed,
             @QueryParam("returnCurrentStepOnly") @DefaultValue("true") Boolean returnCurrentStepOnly,
             @QueryParam("returningFields") List<String> returningFields,
             @DefaultValue("") String message,
             @Suspended final AsyncResponse response);

    /**
     * talk to bot with adding context information to it
     *
     * @param environment
     * @param botId
     * @param conversationId
     * @param returningFields
     * @param inputData       of type ai.labs.models.InputData
     * @return
     * @throws Exception
     */
    @POST
    @Path("/{environment}/{botId}/{conversationId}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Talk to bot with context.")
    void sayWithinContext(@PathParam("environment") Deployment.Environment environment,
                          @PathParam("botId") String botId,
                          @PathParam("conversationId") String conversationId,
                          @QueryParam("returnDetailed") @DefaultValue("false") Boolean returnDetailed,
                          @QueryParam("returnCurrentStepOnly") @DefaultValue("true") Boolean returnCurrentStepOnly,
                          @QueryParam("returningFields") List<String> returningFields,
                          InputData inputData,
                          @Suspended final AsyncResponse response);

    @GET
    @Path("/{environment}/{botId}/undo/{conversationId}")
    @Produces(MediaType.TEXT_PLAIN)
    @ApiOperation(value = "Is UNDO available?")
    Boolean isUndoAvailable(@PathParam("environment") Deployment.Environment environment,
                            @PathParam("botId") String botId,
                            @PathParam("conversationId") String conversationId);

    @POST
    @Path("/{environment}/{botId}/undo/{conversationId}")
    @ApiOperation(value = "UNDO last conversation step.")
    Response undo(@PathParam("environment") Deployment.Environment environment,
                  @PathParam("botId") String botId,
                  @PathParam("conversationId") String conversationId);

    @GET
    @Path("/{environment}/{botId}/redo/{conversationId}")
    @Produces(MediaType.TEXT_PLAIN)
    @ApiOperation(value = "Is REDO available?")
    Boolean isRedoAvailable(@PathParam("environment") Deployment.Environment environment,
                            @PathParam("botId") String botId,
                            @PathParam("conversationId") String conversationId);

    @POST
    @Path("/{environment}/{botId}/redo/{conversationId}")
    @ApiOperation(value = "REDO last conversation step.")
    Response redo(@PathParam("environment") Deployment.Environment environment,
                  @PathParam("botId") String botId,
                  @PathParam("conversationId") String conversationId);
}
