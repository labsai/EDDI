package ai.labs.resources.impl.botmanagement.rest;

import ai.labs.models.UserConversation;
import ai.labs.persistence.IResourceStore;
import ai.labs.resources.rest.botmanagement.IRestUserConversationStore;
import ai.labs.resources.rest.botmanagement.IUserConversationStore;
import lombok.extern.slf4j.Slf4j;
import org.jboss.resteasy.spi.NoLogWebApplicationException;

import javax.inject.Inject;
import javax.ws.rs.InternalServerErrorException;

/**
 * @author ginccc
 */
@Slf4j
public class RestUserConversationStore implements IRestUserConversationStore {
    private final IUserConversationStore userConversationStore;

    @Inject
    public RestUserConversationStore(IUserConversationStore userConversationStore) {
        this.userConversationStore = userConversationStore;
    }

    @Override
    public UserConversation readUserConversation(String intent, String userId) {
        try {
            return userConversationStore.readUserConversation(intent, userId);
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NoLogWebApplicationException(404);
        } catch (IResourceStore.ResourceStoreException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException();
        }
    }

    @Override
    public void deleteUserConversation(String intent, String userId) {
        try {
            userConversationStore.deleteUserConversation(intent, userId);
        } catch (IResourceStore.ResourceStoreException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException();
        }
    }
}
