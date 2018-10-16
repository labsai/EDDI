package ai.labs.core.rest.internal;

import ai.labs.models.*;
import ai.labs.resources.rest.botmanagement.IRestBotTriggerStore;
import ai.labs.resources.rest.botmanagement.IRestUserConversationStore;
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

import static ai.labs.persistence.IResourceStore.IResourceId;

@Slf4j
public class RestBotManagement implements IRestBotManagement {
    private final IRestBotEngine restBotEngine;
    private final IRestUserConversationStore restUserConversationStore;
    private final IRestBotTriggerStore restBotManagementStore;

    @Inject
    public RestBotManagement(IRestBotEngine restBotEngine,
                             IRestUserConversationStore restUserConversationStore,
                             IRestBotTriggerStore restBotManagementStore) {
        this.restBotEngine = restBotEngine;
        this.restUserConversationStore = restUserConversationStore;
        this.restBotManagementStore = restBotManagementStore;
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

    private void deleteUserConversation(String intent, String userId) {
        restUserConversationStore.deleteUserConversation(intent, userId);
    }

    private boolean isConversationEnded(UserConversation userConversation) {
        ConversationState conversationState = restBotEngine.getConversationState(
                userConversation.getEnvironment(), userConversation.getConversationId());
        return conversationState.equals(ConversationState.ENDED);
    }

    private UserConversation createNewConversation(String intent, String userId)
            throws CannotCreateConversationException {

        BotTriggerConfiguration botTriggerConfiguration = getBotTrigger(intent);
        BotDeployment botDeployment = getRandom(botTriggerConfiguration.getBotDeployments());
        Response botResponse = restBotEngine.startConversation(botDeployment.getEnvironment(), botDeployment.getBotId());
        int responseHttpCode = botResponse.getStatus();
        if (responseHttpCode == 201) {
            URI locationUri = URI.create(botResponse.getHeaders().get("location").get(0).toString());
            IResourceId resourceId = RestUtilities.extractResourceId(locationUri);
            return createUserConversation(intent, userId, botDeployment, resourceId.getId());
        } else {
            throw new CannotCreateConversationException(
                    String.format("Cannot create conversation for botId=%s in environment=%s (httpCode=%s)",
                    botDeployment.getBotId(),
                            botDeployment.getEnvironment(),
                            responseHttpCode));
        }
    }

    private UserConversation createUserConversation(String intent, String userId,
                                                    BotDeployment botDeployment, String conversationId) {

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

    private BotTriggerConfiguration getBotTrigger(String intent) {
        return restBotManagementStore.readBotTrigger(intent);
    }

    private UserConversation getUserConversation(String intent, String userId) {
        return restUserConversationStore.readUserConversation(intent, userId);
    }

    private void storeUserConversation(String intent, String userId, UserConversation userConversation) {
        restUserConversationStore.createUserConversation(intent, userId, userConversation);
    }

    private class CannotCreateConversationException extends Exception {
        CannotCreateConversationException(String message) {
            super(message);
        }
    }
}
