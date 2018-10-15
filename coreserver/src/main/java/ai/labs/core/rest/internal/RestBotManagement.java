package ai.labs.core.rest.internal;

import ai.labs.caching.ICache;
import ai.labs.caching.ICacheFactory;
import ai.labs.models.*;
import ai.labs.resources.rest.botmanagement.IRestBotTriggerStore;
import ai.labs.resources.rest.botmanagement.IUserConversationStore;
import ai.labs.rest.rest.IRestBotEngine;
import ai.labs.rest.rest.IRestBotManagement;
import ai.labs.utilities.RestUtilities;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.ws.rs.container.AsyncResponse;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.net.URI;
import java.util.List;
import java.util.Random;

import static ai.labs.persistence.IResourceStore.*;

@Slf4j
public class RestBotManagement implements IRestBotManagement {
    private final IRestBotEngine restBotEngine;
    private final IUserConversationStore userConversationStore;
    private final IRestBotTriggerStore restBotManagementStore;
    private final ICache<String, UserConversation> userConversationCache;

    @Inject
    public RestBotManagement(IRestBotEngine restBotEngine,
                             IUserConversationStore userConversationStore,
                             IRestBotTriggerStore restBotManagementStore,
                             ICacheFactory cacheFactory) {
        this.restBotEngine = restBotEngine;
        this.userConversationStore = userConversationStore;
        this.restBotManagementStore = restBotManagementStore;
        userConversationCache = cacheFactory.getCache("userConversations");
    }

    @Override
    public void sayWithinContext(String intent,
                                 String userId,
                                 Boolean returnDetailed,
                                 Boolean returnCurrentStepOnly,
                                 InputData inputData,
                                 AsyncResponse response) {
        try {

            UserConversation userConversation = getUserConversation(intent, userId);

            if (userConversation != null && isConversationEnded(userConversation)) {
                deleteUserConversation(intent, userId);
                userConversation = null;
            }

            if (userConversation == null) {
                userConversation = createNewConversation(intent, userId);
            }

            restBotEngine.sayWithinContext(userConversation.getEnvironment(), userConversation.getBotId(),
                    userConversation.getConversationId(), returnDetailed, returnCurrentStepOnly, inputData, response);

        } catch (Exception e) {
            log.error(e.getLocalizedMessage(), e);
            response.resume(Response.status(Response.Status.INTERNAL_SERVER_ERROR).type(MediaType.TEXT_PLAIN).
                    entity(e.getLocalizedMessage()).
                    build());
        }
    }

    private void deleteUserConversation(String intent, String userId)
            throws ResourceStoreException {

        userConversationStore.deleteUserConversation(intent, userId);
        userConversationCache.remove(calculateCacheKey(intent, userId));
    }

    private boolean isConversationEnded(UserConversation userConversation) {
        ConversationState conversationState = restBotEngine.getConversationState(
                userConversation.getEnvironment(), userConversation.getConversationId());
        return conversationState.equals(ConversationState.ENDED);
    }

    private UserConversation createNewConversation(String intent, String userId)
            throws CannotCreateConversationException, ResourceStoreException, ResourceAlreadyExistsException {

        BotTriggerConfiguration botTriggerConfiguration = fetchBotTrigger(intent);
        BotDeployment botDeployment = getRandom(botTriggerConfiguration.getBotDeployments());
        Response botResponse = restBotEngine.startConversation(botDeployment.getEnvironment(), botDeployment.getBotId());
        if (botResponse.getStatus() == 201) {
            URI locationUri = URI.create(botResponse.getHeaders().get("location").get(0).toString());
            IResourceId resourceId = RestUtilities.extractResourceId(locationUri);
            return createUserConversation(intent, userId, botDeployment, resourceId.getId());
        } else {
            throw new CannotCreateConversationException(String.format("Cannot create conversation for %s in %s",
                    botDeployment.getBotId(),
                    botDeployment.getEnvironment()));
        }
    }

    private UserConversation createUserConversation(String intent, String userId,
                                                    BotDeployment botDeployment, String conversationId)
            throws ResourceStoreException, ResourceAlreadyExistsException {

        UserConversation userConversation = new UserConversation(
                intent,
                userId,
                botDeployment.getEnvironment(),
                botDeployment.getBotId(),
                conversationId);

        storeUserConversation(intent, userId, userConversation);

        return userConversation;
    }

    private BotDeployment getRandom(List<BotDeployment> botDeployments) {
        return botDeployments.get(new Random().nextInt(botDeployments.size()));
    }

    private BotTriggerConfiguration fetchBotTrigger(String intent) {
        return restBotManagementStore.readBotTrigger(intent);
    }

    private UserConversation getUserConversation(String intent, String userId)
            throws ResourceNotFoundException, ResourceStoreException {

        UserConversation userConversation = userConversationCache.get(calculateCacheKey(intent, userId));
        if (userConversation == null) {
            userConversation = fetchUserConversation(intent, userId);
        }

        return userConversation;
    }

    private UserConversation fetchUserConversation(String intent, String userId)
            throws ResourceNotFoundException, ResourceStoreException {

        return userConversationStore.readUserConversation(intent, userId);
    }

    private void storeUserConversation(String intent, String userId, UserConversation userConversation)
            throws ResourceStoreException, ResourceAlreadyExistsException {

        userConversationCache.put(calculateCacheKey(intent, userId), userConversation);
        userConversationStore.createUserConversation(userConversation);
    }

    private static String calculateCacheKey(String intent, String userId) {
        return intent + "::" + userId;
    }

    private class CannotCreateConversationException extends Exception {
        CannotCreateConversationException(String message) {
            super(message);
        }
    }
}
