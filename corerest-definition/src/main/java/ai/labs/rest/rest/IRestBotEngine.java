package ai.labs.rest.rest;


import ai.labs.memory.model.ConversationState;
import ai.labs.memory.model.Deployment;
import ai.labs.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.rest.model.InputData;
import io.swagger.annotations.Api;
import org.jboss.resteasy.annotations.cache.NoCache;

import javax.ws.rs.*;
import javax.ws.rs.container.AsyncResponse;
import javax.ws.rs.container.Suspended;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

/**
 * @author ginccc
 */
@Api(value = "bot engine")
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
    Response startConversation(@PathParam("environment") Deployment.Environment environment,
                               @PathParam("botId") String botId);

    @GET
    @NoCache
    @Path("/{environment}/{botId}/{conversationId}")
    @Produces(MediaType.APPLICATION_JSON)
    SimpleConversationMemorySnapshot readConversation(@PathParam("environment") Deployment.Environment environment,
                                                      @PathParam("botId") String botId,
                                                      @PathParam("conversationId") String conversationId,
                                                      @QueryParam("includeAll") @DefaultValue("false") Boolean includeAll);

    @GET
    @NoCache
    @Path("/{environment}/{botId}/{conversationId}")
    @Produces(MediaType.TEXT_PLAIN)
    String readConversation(@PathParam("environment") Deployment.Environment environment,
                                                      @PathParam("botId") String botId,
                                                      @PathParam("conversationId") String conversationId);


    @GET
    @Path("/{environment}/conversationstatus/{conversationId}")
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
    void say(@PathParam("environment") Deployment.Environment environment,
             @PathParam("botId") String botId,
             @PathParam("conversationId") String conversationId,
             @DefaultValue("") String message,
             @Suspended final AsyncResponse response);

    /**
     * talk to bot with adding context information to it
     *
     * @param environment
     * @param botId
     * @param conversationId
     * @param inputData      of type ai.labs.rest.model.InputData
     * @return
     * @throws Exception
     */
    @POST
    @Path("/{environment}/{botId}/{conversationId}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    void sayWithinContext(@PathParam("environment") Deployment.Environment environment,
                          @PathParam("botId") String botId,
                          @PathParam("conversationId") String conversationId,
                          InputData inputData,
                          @Suspended final AsyncResponse response);

    @GET
    @Path("/{environment}/{botId}/undo/{conversationId}")
    @Produces(MediaType.TEXT_PLAIN)
    Boolean isUndoAvailable(@PathParam("environment") Deployment.Environment environment,
                            @PathParam("botId") String botId,
                            @PathParam("conversationId") String conversationId);

    @POST
    @Path("/{environment}/{botId}/undo/{conversationId}")
    Response undo(@PathParam("environment") Deployment.Environment environment,
                  @PathParam("botId") String botId,
                  @PathParam("conversationId") String conversationId);

    @GET
    @Path("/{environment}/{botId}/redo/{conversationId}")
    @Produces(MediaType.TEXT_PLAIN)
    Boolean isRedoAvailable(@PathParam("environment") Deployment.Environment environment,
                            @PathParam("botId") String botId,
                            @PathParam("conversationId") String conversationId);

    @POST
    @Path("/{environment}/{botId}/redo/{conversationId}")
    Response redo(@PathParam("environment") Deployment.Environment environment,
                  @PathParam("botId") String botId,
                  @PathParam("conversationId") String conversationId);
}
