package ai.labs.memory.rest;

import ai.labs.memory.descriptor.model.ConversationDescriptor;
import ai.labs.memory.model.ConversationMemorySnapshot;
import ai.labs.memory.model.ConversationState;
import ai.labs.persistence.IResourceStore;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import java.util.List;

/**
 * @author ginccc
 */
@Path("/conversationstore/conversations")
public interface IRestMonitorStore {
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
    void deleteConversationLog(@PathParam("conversationId") String conversationId) throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException;
}

