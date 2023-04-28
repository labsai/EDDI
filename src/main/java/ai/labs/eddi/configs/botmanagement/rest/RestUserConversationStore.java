package ai.labs.eddi.configs.botmanagement.rest;

import ai.labs.eddi.configs.botmanagement.IRestUserConversationStore;
import ai.labs.eddi.configs.botmanagement.IUserConversationStore;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.caching.ICache;
import ai.labs.eddi.engine.caching.ICacheFactory;
import ai.labs.eddi.models.UserConversation;
import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

/**
 * @author ginccc
 */

@ApplicationScoped
public class RestUserConversationStore implements IRestUserConversationStore {
    private static final String CACHE_NAME = "userConversations";
    private final IUserConversationStore userConversationStore;
    private final ICache<String, UserConversation> userConversationCache;

    private static final Logger log = Logger.getLogger(RestUserConversationStore.class);

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

    static String calculateCacheKey(String intent, String userId) {
        return intent + "::" + userId;
    }
}
