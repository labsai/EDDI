package io.sls.core.rest;


import io.sls.memory.model.ConversationState;
import io.sls.memory.model.Deployment;
import io.sls.memory.model.SimpleConversationMemorySnapshot;
import org.jboss.resteasy.annotations.GZIP;
import org.jboss.resteasy.annotations.cache.NoCache;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

/**
 * @author ginccc
 */
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
    @GZIP
    @NoCache
    @Path("/{environment}/{botId}/{conversationId}")
    @Produces(MediaType.APPLICATION_JSON)
    SimpleConversationMemorySnapshot readConversationLog(@PathParam("environment") Deployment.Environment environment,
                                                         @PathParam("botId") String botId,
                                                         @PathParam("conversationId") String conversationId) throws Exception;

    @GET
    @Path("/{environment}/conversationstatus/{conversationId}")
    ConversationState getConversationState(@PathParam("environment") Deployment.Environment environment,
                                           @PathParam("conversationId") String conversationId) throws Exception;

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
    Response say(@PathParam("environment") Deployment.Environment environment,
                 @PathParam("botId") String botId,
                 @PathParam("conversationId") String conversationId,
                 @DefaultValue("") String message) throws Exception;

    @GET
    @Path("/{environment}/{botId}/undo/{conversationId}")
    @Produces(MediaType.TEXT_PLAIN)
    Boolean isUndoAvailable(@PathParam("environment") Deployment.Environment environment,
                            @PathParam("botId") String botId,
                            @PathParam("conversationId") String conversationId) throws Exception;

    @POST
    @Path("/{environment}/{botId}/undo/{conversationId}")
    Response undo(@PathParam("environment") Deployment.Environment environment,
                  @PathParam("botId") String botId,
                  @PathParam("conversationId") String conversationId) throws Exception;

    @GET
    @Path("/{environment}/{botId}/redo/{conversationId}")
    @Produces(MediaType.TEXT_PLAIN)
    Boolean isRedoAvailable(@PathParam("environment") Deployment.Environment environment,
                            @PathParam("botId") String botId,
                            @PathParam("conversationId") String conversationId) throws Exception;

    @POST
    @Path("/{environment}/{botId}/redo/{conversationId}")
    Response redo(@PathParam("environment") Deployment.Environment environment,
                  @PathParam("botId") String botId,
                  @PathParam("conversationId") String conversationId) throws Exception;
}
