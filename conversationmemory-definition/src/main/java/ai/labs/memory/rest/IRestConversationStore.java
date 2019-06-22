package ai.labs.memory.rest;

import ai.labs.memory.descriptor.model.ConversationDescriptor;
import ai.labs.memory.model.ConversationMemorySnapshot;
import ai.labs.models.ConversationState;
import ai.labs.models.ConversationStatus;
import ai.labs.persistence.IResourceStore;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiParam;
import io.swagger.annotations.Authorization;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.List;

/**
 * @author ginccc
 */
@Api(value = "Bot Engine -> Conversations", authorizations = {@Authorization(value = "eddi_auth")})
@Path("/conversationstore/conversations")
public interface IRestConversationStore {
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    List<ConversationDescriptor> readConversationDescriptors(@QueryParam("index") @DefaultValue("0") Integer index,
                                                             @QueryParam("limit") @DefaultValue("20") Integer limit,
                                                             @QueryParam("botId") String botId,
                                                             @QueryParam("botVersion") Integer botVersion,
                                                             @QueryParam("conversationState") ConversationState conversationState,
                                                             @QueryParam("viewState") ConversationDescriptor.ViewState viewState);

    @GET
    @Path("/{conversationId}")
    @Produces(MediaType.APPLICATION_JSON)
    ConversationMemorySnapshot readConversationLog(@PathParam("conversationId") String conversationId) throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException;

    @DELETE
    @Path("/{conversationId}")
    void deleteConversationLog(@PathParam("conversationId") String conversationId,
                               @QueryParam("deletePermanently") @DefaultValue("false") Boolean deletePermanently)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException;

    @GET
    @Path("/active/{botId}")
    @Produces(MediaType.APPLICATION_JSON)
    List<ConversationStatus> getActiveConversations(@PathParam("botId") String botId,
                                                    @ApiParam(name = "botVersion", required = true, format = "integer", example = "1")
                                                    @QueryParam("botVersion") Integer botVersion,
                                                    @ApiParam(name = "index", required = true, format = "integer", example = "0")
                                                    @QueryParam("index") @DefaultValue("0") Integer index,
                                                    @ApiParam(name = "limit", required = true, format = "integer", example = "20")
                                                    @QueryParam("limit") @DefaultValue("20") Integer limit);

    @POST
    @Path("end")
    Response endActiveConversations(List<ConversationStatus> conversationStatuses);
}

