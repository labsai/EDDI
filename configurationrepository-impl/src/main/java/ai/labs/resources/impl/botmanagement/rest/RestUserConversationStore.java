package ai.labs.resources.impl.botmanagement.rest;

import ai.labs.caching.ICache;
import ai.labs.caching.ICacheFactory;
import ai.labs.models.UserConversation;
import ai.labs.persistence.IResourceStore;
import ai.labs.resources.rest.botmanagement.IRestUserConversationStore;
import ai.labs.resources.rest.botmanagement.IUserConversationStore;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;

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
                if (userConversation != null) {
                    userConversationCache.put(cacheKey, userConversation);
                }
            }

            if (userConversation == null) {
                String message = "UserConversation with intent=%s and userId=%s does not exist.";
                message = String.format(message, intent, userId);
                throw new NotFoundException(message);
            }

            return userConversation;

        } catch (IResourceStore.ResourceStoreException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e.getLocalizedMessage());
        }
    }

    @Override
    public Response createUserConversation(String intent, String userId, UserConversation userConversation) {
        try {
            userConversationStore.createUserConversation(userConversation);
            userConversationCache.put(calculateCacheKey(intent, userId), userConversation);
            return Response.ok().build();
        } catch (IResourceStore.ResourceAlreadyExistsException e) {
            throw new WebApplicationException(e.getLocalizedMessage(), Response.Status.CONFLICT);
        } catch (IResourceStore.ResourceStoreException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e.getLocalizedMessage());
        }
    }

    @Override
    public Response deleteUserConversation(String intent, String userId) {
        try {
            userConversationStore.deleteUserConversation(intent, userId);
            userConversationCache.remove(calculateCacheKey(intent, userId));
            return Response.ok().build();
        } catch (IResourceStore.ResourceStoreException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e.getLocalizedMessage());
        }
    }

    private static String calculateCacheKey(String intent, String userId) {
        return intent + "::" + userId;
    }
}
