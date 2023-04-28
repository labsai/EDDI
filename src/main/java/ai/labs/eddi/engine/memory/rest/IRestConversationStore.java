package ai.labs.eddi.engine.memory.rest;

import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.memory.descriptor.model.ConversationDescriptor;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.models.ConversationState;
import ai.labs.eddi.models.ConversationStatus;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;

/**
 * @author ginccc
 */
// @Api(value = "Bot Engine -> Conversations", authorizations = {@Authorization(value = "eddi_auth")})
@Path("/conversationstore/conversations")
@Tag(name = "09. Talk to Bots", description = "Communicate with bots")
public interface IRestConversationStore {
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    List<ConversationDescriptor> readConversationDescriptors(@QueryParam("index") @DefaultValue("0") Integer index,
                                                             @QueryParam("limit") @DefaultValue("20") Integer limit,
                                                             @QueryParam("filter") String filter,
                                                             @QueryParam("conversationId") String conversationId,
                                                             @QueryParam("botId") String botId,
                                                             @QueryParam("botVersion") Integer botVersion,
                                                             @QueryParam("conversationState") ConversationState conversationState,
                                                             @QueryParam("viewState") ConversationDescriptor.ViewState viewState);

    @GET
    @Path("/simple/{conversationId}")
    @Produces(MediaType.APPLICATION_JSON)
    SimpleConversationMemorySnapshot readSimpleConversationLog(@PathParam("conversationId") String conversationId,
                                                               @QueryParam("returnDetailed") @DefaultValue("false") Boolean returnDetailed,
                                                               @QueryParam("returnCurrentStepOnly") @DefaultValue("true") Boolean returnCurrentStepOnly,
                                                               @QueryParam("returningFields") List<String> returningFields)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException;

    @GET
    @Path("/{conversationId}")
    @Produces(MediaType.APPLICATION_JSON)
    ConversationMemorySnapshot readRawConversationLog(@PathParam("conversationId") String conversationId)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException;

    @DELETE
    @Path("/{conversationId}")
    void deleteConversationLog(@PathParam("conversationId") String conversationId,
                               @QueryParam("deletePermanently") @DefaultValue("false") Boolean deletePermanently)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException;

    @GET
    @Path("/active/{botId}")
    @Produces(MediaType.APPLICATION_JSON)
    List<ConversationStatus> getActiveConversations(@PathParam("botId") String botId,
                                                    @Parameter(name = "botVersion", required = true, example = "1")
                                                    @QueryParam("botVersion") Integer botVersion)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException;

    @POST
    @Path("end")
    Response endActiveConversations(List<ConversationStatus> conversationStatuses);
}

