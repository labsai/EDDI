package ai.labs.core.rest.internal;

import ai.labs.models.BotDeployment;
import ai.labs.models.BotTriggerConfiguration;
import ai.labs.models.Context;
import ai.labs.models.ConversationState;
import ai.labs.models.InputData;
import ai.labs.models.Property;
import ai.labs.models.UserConversation;
import ai.labs.resources.rest.botmanagement.IRestBotTriggerStore;
import ai.labs.resources.rest.botmanagement.IRestUserConversationStore;
import ai.labs.rest.restinterfaces.IRestBotEngine;
import ai.labs.rest.restinterfaces.IRestBotManagement;
import ai.labs.utilities.RestUtilities;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
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
import java.util.Map;
import java.util.Random;

import static ai.labs.persistence.IResourceStore.IResourceId;

@Slf4j
public class RestBotManagement implements IRestBotManagement {
    public static final String KEY_LANG = "lang";
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
    public void loadConversationMemory(String intent, String userId, String language,
                                       Boolean returnDetailed,
                                       Boolean returnCurrentStepOnly,
                                       List<String> returningFields,
                                       AsyncResponse asyncResponse) {

        try {
            var userConversationResult = initUserConversation(intent, userId, language);

            var userConversation = userConversationResult.getUserConversation();

            var memorySnapshot =
                    restBotEngine.readConversation(userConversation.getEnvironment(),
                            userConversation.getBotId(),
                            userConversation.getConversationId(),
                            returnDetailed,
                            returnCurrentStepOnly, returningFields);

            Property languageProperty = memorySnapshot.getConversationProperties().get("lang");
            if (!userConversationResult.isNewlyCreatedConversation() &&
                    (languageProperty != null && !languageProperty.getValue().equals(language))) {
                restBotEngine.rerunLastConversationStep(userConversation.getEnvironment(),
                        userConversation.getBotId(),
                        userConversation.getConversationId(),
                        language,
                        returnDetailed,
                        returnCurrentStepOnly, returningFields, asyncResponse);
            } else {
                asyncResponse.resume(memorySnapshot);
            }
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
            var userConversation =
                    initUserConversation(intent, userId, extractLanguage(inputData)).getUserConversation();

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

    private UserConversationResult initUserConversation(String intent, String userId, String language) throws CannotCreateConversationException {
        UserConversation userConversation;
        boolean newlyCreatedConversation = false;
        try {
            userConversation = getUserConversation(intent, userId);
        } catch (NotFoundException e) {
            userConversation = createNewConversation(intent, userId, language);
            newlyCreatedConversation = true;
        }

        if (isConversationEnded(userConversation)) {
            deleteUserConversation(intent, userId);
            userConversation = createNewConversation(intent, userId, language);
            newlyCreatedConversation = true;
        }
        return new UserConversationResult(newlyCreatedConversation, userConversation);
    }

    @Override
    public Response endCurrentConversation(String intent, String userId) {
        UserConversation userConversation = restUserConversationStore.readUserConversation(intent, userId);
        restBotEngine.endConversation(userConversation.getConversationId());
        return Response.ok().build();
    }

    @Override
    public Boolean isUndoAvailable(String intent, String userId) {
        var userConversation = getUserConversation(intent, userId);
        return restBotEngine.isUndoAvailable(
                userConversation.getEnvironment(),
                userConversation.getBotId(),
                userConversation.getConversationId());
    }

    @Override
    public Response undo(String intent, String userId) {
        var userConversation = getUserConversation(intent, userId);
        return restBotEngine.undo(
                userConversation.getEnvironment(),
                userConversation.getBotId(),
                userConversation.getConversationId());
    }

    @Override
    public Boolean isRedoAvailable(String intent, String userId) {
        var userConversation = getUserConversation(intent, userId);
        return restBotEngine.isRedoAvailable(
                userConversation.getEnvironment(),
                userConversation.getBotId(),
                userConversation.getConversationId());
    }

    @Override
    public Response redo(String intent, String userId) {
        var userConversation = getUserConversation(intent, userId);
        return restBotEngine.redo(
                userConversation.getEnvironment(),
                userConversation.getBotId(),
                userConversation.getConversationId());
    }

    private static String extractLanguage(InputData inputData) {
        var context = inputData.getContext();
        String language = null;
        if (context != null) {
            var langContext = context.get(KEY_LANG);
            if (langContext != null) {
                var contextValue = langContext.getValue();
                if (contextValue != null) {
                    language = contextValue.toString();
                }
            }
        }

        return language;
    }

    private void deleteUserConversation(String intent, String userId) {
        restUserConversationStore.deleteUserConversation(intent, userId);
    }

    private boolean isConversationEnded(UserConversation userConversation) {
        ConversationState conversationState = restBotEngine.getConversationState(
                userConversation.getEnvironment(), userConversation.getConversationId());
        return conversationState.equals(ConversationState.ENDED);
    }

    private UserConversation createNewConversation(String intent, String userId, String language)
            throws CannotCreateConversationException {

        BotTriggerConfiguration botTriggerConfiguration = getBotTrigger(intent);
        BotDeployment botDeployment = getRandom(botTriggerConfiguration.getBotDeployments());
        String botId = botDeployment.getBotId();
        Map<String, Context> initialContext = botDeployment.getInitialContext();
        initialContext.put(KEY_LANG, new Context(Context.ContextType.string, language));
        Response botResponse = restBotEngine.startConversationWithContext(botDeployment.getEnvironment(),
                botId,
                userId,
                initialContext);
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

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UserConversationResult {
        private boolean newlyCreatedConversation;
        private UserConversation userConversation;
    }
}
