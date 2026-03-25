package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.api.IGroupConversationService;
import ai.labs.eddi.engine.api.IRestGroupConversation;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.net.URI;
import java.util.List;

import static ai.labs.eddi.engine.exception.SneakyThrow.sneakyThrow;

/**
 * REST implementation for group conversation operations.
 *
 * @author ginccc
 */
@ApplicationScoped
public class RestGroupConversation implements IRestGroupConversation {

    private static final Logger LOGGER = Logger.getLogger(RestGroupConversation.class);

    private final IGroupConversationService groupConversationService;

    @Inject
    public RestGroupConversation(IGroupConversationService groupConversationService) {
        this.groupConversationService = groupConversationService;
    }

    @Override
    public Response discuss(String groupId, DiscussRequest request) {
        try {
            String userId = request.userId() != null ? request.userId() : "anonymous";
            GroupConversation gc = groupConversationService.discuss(groupId, request.question(), userId, 0);
            URI location = URI.create("/groups/" + groupId + "/conversations/" + gc.getId());
            return Response.created(location).entity(gc).build();
        } catch (IGroupConversationService.GroupDepthExceededException e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(e.getMessage()).build();
        } catch (IResourceStore.ResourceNotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND).entity(e.getMessage()).build();
        } catch (Exception e) {
            LOGGER.errorf("Group discussion failed: %s", e.getMessage());
            throw sneakyThrow(e);
        }
    }

    @Override
    public GroupConversation readGroupConversation(String groupId, String groupConversationId) {
        try {
            return groupConversationService.readGroupConversation(groupConversationId);
        } catch (IResourceStore.ResourceNotFoundException | IResourceStore.ResourceStoreException e) {
            throw sneakyThrow(e);
        }
    }

    @Override
    public Response deleteGroupConversation(String groupId, String groupConversationId) {
        try {
            groupConversationService.deleteGroupConversation(groupConversationId);
            return Response.ok().build();
        } catch (IResourceStore.ResourceStoreException e) {
            throw sneakyThrow(e);
        }
    }

    @Override
    public List<GroupConversation> listGroupConversations(String groupId, Integer index, Integer limit) {
        try {
            return groupConversationService.listGroupConversations(groupId, index, limit);
        } catch (IResourceStore.ResourceStoreException e) {
            throw sneakyThrow(e);
        }
    }
}
