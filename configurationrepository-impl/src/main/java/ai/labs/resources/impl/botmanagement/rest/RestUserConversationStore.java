package ai.labs.resources.impl.botmanagement.rest;

import ai.labs.caching.ICache;
import ai.labs.caching.ICacheFactory;
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
    private static final String CACHE_NAME = "userConversations";
    private final IUserConversationStore userConversationStore;
    private final ICache<String, UserConversation> userConversationCache;

    @Inject
    public RestUserConversationStore(IUserConversationStore userConversationStore,
                                     ICacheFactory cacheFactory) {
        this.userConversationStore = userConversationStore;
        userConversationCache = cacheFactory.getCache(CACHE_NAME);
    }

    @Override
    public UserConversation readUserConversation(String intent, String userId) {
        try {
            String cacheKey = calculateCacheKey(intent, userId);
            UserConversation userConversation = userConversationCache.get(cacheKey);
            if (userConversation == null) {
                userConversation = userConversationStore.readUserConversation(intent, userId);
                userConversationCache.put(cacheKey, userConversation);
            }

            return userConversation;
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NoLogWebApplicationException(404);
        } catch (IResourceStore.ResourceStoreException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException();
        }
    }

    @Override
    public void createUserConversation(String intent, String userId, UserConversation userConversation) {
        try {
            userConversationStore.createUserConversation(userConversation);
            userConversationCache.put(calculateCacheKey(intent, userId), userConversation);
        } catch (IResourceStore.ResourceAlreadyExistsException | IResourceStore.ResourceStoreException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException();
        }
    }

    @Override
    public void deleteUserConversation(String intent, String userId) {
        try {
            userConversationStore.deleteUserConversation(intent, userId);
            userConversationCache.remove(calculateCacheKey(intent, userId));
        } catch (IResourceStore.ResourceStoreException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException();
        }
    }

    private static String calculateCacheKey(String intent, String userId) {
        return intent + "::" + userId;
    }
}
