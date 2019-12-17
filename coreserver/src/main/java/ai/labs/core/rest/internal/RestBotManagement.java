package ai.labs.core.rest.internal;

import ai.labs.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.models.*;
import ai.labs.resources.rest.botmanagement.IRestBotTriggerStore;
import ai.labs.resources.rest.botmanagement.IRestUserConversationStore;
import ai.labs.rest.restinterfaces.IRestBotEngine;
import ai.labs.rest.restinterfaces.IRestBotManagement;
import ai.labs.utilities.RestUtilities;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.WebApplicationException;
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
    public SimpleConversationMemorySnapshot loadConversationMemory(String intent, String userId, Boolean returnDetailed, Boolean returnCurrentStepOnly, List<String> returningFields) {
        try {
            UserConversation userConversation = initUserConversation(intent, userId);
            return restBotEngine.readConversation(userConversation.getEnvironment(),
                    userConversation.getBotId(),
                    userConversation.getConversationId(),
                    returnDetailed,
                    returnCurrentStepOnly, returningFields);
        } catch (CannotCreateConversationException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e.getLocalizedMessage());
        }
    }

    @Override
    public void sayWithinContext(String intent,
                                 String userId,
                                 Boolean returnDetailed,
                                 Boolean returnCurrentStepOnly,
                                 List<String> returningFields,
                                 InputData inputData,
                                 AsyncResponse response) {
        try {

            UserConversation userConversation = initUserConversation(intent, userId);

            restBotEngine.sayWithinContext(userConversation.getEnvironment(), userConversation.getBotId(),
                    userConversation.getConversationId(), returnDetailed, returnCurrentStepOnly, returningFields, inputData, response);

        } catch (Exception e) {
            log.error(e.getLocalizedMessage(), e);
            Response.ResponseBuilder responseStatus;
            if (e instanceof WebApplicationException) {
                Response exResponse = ((WebApplicationException) e).getResponse();
                responseStatus = Response.status(exResponse.getStatus());
            } else {
                responseStatus = Response.status(Response.Status.INTERNAL_SERVER_ERROR);
            }
            response.resume(responseStatus.type(MediaType.TEXT_PLAIN).entity(e.getLocalizedMessage()).build());
        }
    }

    private UserConversation initUserConversation(String intent, String userId) throws CannotCreateConversationException {
        UserConversation userConversation;
        try {
            userConversation = getUserConversation(intent, userId);
        } catch (NotFoundException e) {
            userConversation = createNewConversation(intent, userId);
        }

        if (isConversationEnded(userConversation)) {
            deleteUserConversation(intent, userId);
            userConversation = createNewConversation(intent, userId);
        }
        return userConversation;
    }

    @Override
    public Response endCurrentConversation(String intent, String userId) {
        UserConversation userConversation = restUserConversationStore.readUserConversation(intent, userId);
        restBotEngine.endConversation(userConversation.getConversationId());
        return Response.ok().build();
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
        String botId = botDeployment.getBotId();
        Response botResponse = restBotEngine.startConversationWithContext(botDeployment.getEnvironment(),
                botId,
                userId,
                botDeployment.getInitialContext());
        int responseHttpCode = botResponse.getStatus();
        if (responseHttpCode == 201) {
            URI locationUri = URI.create(botResponse.getHeaders().get("location").get(0).toString());
            IResourceId resourceId = RestUtilities.extractResourceId(locationUri);
            return createUserConversation(intent, userId, botDeployment, resourceId.getId());
        } else {
            throw new CannotCreateConversationException(
                    String.format("Cannot create conversation for botId=%s in environment=%s (httpCode=%s)",
                            botId,
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

    private static class CannotCreateConversationException extends Exception {
        CannotCreateConversationException(String message) {
            super(message);
        }
    }
}
