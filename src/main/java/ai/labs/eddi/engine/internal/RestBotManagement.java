package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.botmanagement.IRestBotTriggerStore;
import ai.labs.eddi.configs.botmanagement.IUserConversationStore;
import ai.labs.eddi.configs.properties.model.Property;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.IRestBotEngine;
import ai.labs.eddi.engine.IRestBotManagement;
import ai.labs.eddi.engine.model.*;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.utils.RestUtilities;
import io.quarkus.security.UnauthorizedException;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.container.AsyncResponse;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static ai.labs.eddi.engine.model.Deployment.Environment.unrestricted;

@ApplicationScoped
public class RestBotManagement implements IRestBotManagement {
    public static final String KEY_LANG = "lang";
    private final IRestBotEngine restBotEngine;
    private final IUserConversationStore userConversationStore;
    private final IRestBotTriggerStore restBotManagementStore;
    private final boolean checkForUserAuthentication;

    @Inject
    SecurityIdentity identity;

    private static final Logger log = Logger.getLogger(RestBotManagement.class);

    @Inject
    public RestBotManagement(IRestBotEngine restBotEngine,
                             IUserConversationStore userConversationStore,
                             IRestBotTriggerStore restBotManagementStore,
                             @ConfigProperty(name = "quarkus.oidc.enabled") boolean checkForUserAuthentication) {
        this.restBotEngine = restBotEngine;
        this.userConversationStore = userConversationStore;
        this.restBotManagementStore = restBotManagementStore;
        this.checkForUserAuthentication = checkForUserAuthentication;
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

            checkUserAuthIfApplicable(userConversation);

            var memorySnapshot =
                    restBotEngine.readConversation(userConversation.getEnvironment(),
                            userConversation.getBotId(),
                            userConversation.getConversationId(),
                            returnDetailed,
                            returnCurrentStepOnly, returningFields);

            Property languageProperty = extractLanguageProperty(memorySnapshot);
            if (!userConversationResult.isNewlyCreatedConversation() &&
                    (languageProperty != null && languageProperty.getValueString() != null &&
                            !languageProperty.getValueString().equals(language))) {
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

    private static Property extractLanguageProperty(SimpleConversationMemorySnapshot memorySnapshot) {
        var conversationProperties = memorySnapshot.getConversationProperties();
        return conversationProperties != null ? conversationProperties.get("lang") : null;
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

            checkUserAuthIfApplicable(userConversation);

            restBotEngine.sayWithinContext(
                    userConversation.getEnvironment(),
                    userConversation.getBotId(),
                    userConversation.getConversationId(),
                    returnDetailed, returnCurrentStepOnly, returningFields, inputData, response);

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

    private UserConversationResult initUserConversation(String intent, String userId, String language)
            throws CannotCreateConversationException {

        UserConversation userConversation;
        boolean newlyCreatedConversation = false;

        userConversation = getUserConversation(intent, userId);
        if (userConversation == null) {
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
        try {
            var userConversation = userConversationStore.readUserConversation(intent, userId);
            if (userConversation != null) {
                checkUserAuthIfApplicable(userConversation);
                restBotEngine.endConversation(userConversation.getConversationId());
            }
            return Response.ok().build();
        } catch (IResourceStore.ResourceStoreException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException();
        }
    }

    @Override
    public Boolean isUndoAvailable(String intent, String userId) {
        var userConversation = getUserConversation(intent, userId);
        if (userConversation != null) {
            checkUserAuthIfApplicable(userConversation);
            return restBotEngine.isUndoAvailable(
                    userConversation.getEnvironment(),
                    userConversation.getBotId(),
                    userConversation.getConversationId());
        } else {
            return false;
        }
    }

    @Override
    public Response undo(String intent, String userId) {
        var userConversation = getUserConversation(intent, userId);
        if (userConversation != null) {
            checkUserAuthIfApplicable(userConversation);
            return restBotEngine.undo(
                    userConversation.getEnvironment(),
                    userConversation.getBotId(),
                    userConversation.getConversationId());
        } else {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
    }

    @Override
    public Boolean isRedoAvailable(String intent, String userId) {
        var userConversation = getUserConversation(intent, userId);
        if (userConversation != null) {
            checkUserAuthIfApplicable(userConversation);
            return restBotEngine.isRedoAvailable(
                    userConversation.getEnvironment(),
                    userConversation.getBotId(),
                    userConversation.getConversationId());
        } else {
            return false;
        }
    }

    @Override
    public Response redo(String intent, String userId) {
        var userConversation = getUserConversation(intent, userId);
        if (userConversation != null) {
            checkUserAuthIfApplicable(userConversation);
            return restBotEngine.redo(
                    userConversation.getEnvironment(),
                    userConversation.getBotId(),
                    userConversation.getConversationId());
        } else {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
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
        try {
            userConversationStore.deleteUserConversation(intent, userId);
        } catch (IResourceStore.ResourceStoreException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException();
        }
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
            var locationUri = URI.create(botResponse.getHeaders().get("location").getFirst().toString());
            var resourceId = RestUtilities.extractResourceId(locationUri);
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

        storeUserConversation(userConversation);

        return userConversation;
    }

    private BotDeployment getRandom(List<BotDeployment> botDeployments) {
        return botDeployments.get(new Random().nextInt(botDeployments.size()));
    }

    private BotTriggerConfiguration getBotTrigger(String intent) {
        return restBotManagementStore.readBotTrigger(intent);
    }

    private UserConversation getUserConversation(String intent, String userId) {
        try {
            return userConversationStore.readUserConversation(intent, userId);
        } catch (IResourceStore.ResourceStoreException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException();
        }
    }

    private void storeUserConversation(UserConversation userConversation) {
        try {
            userConversationStore.createUserConversation(userConversation);
        } catch (IResourceStore.ResourceAlreadyExistsException | IResourceStore.ResourceStoreException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException();
        }
    }

    private void checkUserAuthIfApplicable(UserConversation userConversation) throws UnauthorizedException {
        if (checkForUserAuthentication &&
                !unrestricted.equals(userConversation.getEnvironment()) &&
                identity.isAnonymous()) {
            throw new UnauthorizedException();
        }
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
