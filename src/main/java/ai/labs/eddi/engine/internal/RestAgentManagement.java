package ai.labs.eddi.engine.internal;

import ai.labs.eddi.engine.triggermanagement.IRestAgentTriggerStore;
import ai.labs.eddi.engine.triggermanagement.IUserConversationStore;
import ai.labs.eddi.configs.properties.model.Property;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.IResourceStore.ResourceAlreadyExistsException;
import ai.labs.eddi.engine.api.IRestAgentEngine;
import ai.labs.eddi.engine.api.IRestAgentManagement;
import ai.labs.eddi.engine.model.*;
import ai.labs.eddi.engine.triggermanagement.model.AgentTriggerConfiguration;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.triggermanagement.model.UserConversation;
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
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import static ai.labs.eddi.engine.exception.SneakyThrow.sneakyThrow;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static ai.labs.eddi.engine.model.Deployment.Environment.production;

@ApplicationScoped
public class RestAgentManagement implements IRestAgentManagement {
    public static final String KEY_LANG = "lang";
    private final IRestAgentEngine restAgentEngine;
    private final IUserConversationStore userConversationStore;
    private final IRestAgentTriggerStore restAgentManagementStore;
    private final boolean checkForUserAuthentication;

    @Inject
    SecurityIdentity identity;

    private static final Logger log = Logger.getLogger(RestAgentManagement.class);

    @Inject
    public RestAgentManagement(IRestAgentEngine restAgentEngine,
            IUserConversationStore userConversationStore,
            IRestAgentTriggerStore restAgentManagementStore,
            @ConfigProperty(name = "quarkus.oidc.tenant-enabled") boolean checkForUserAuthentication) {
        this.restAgentEngine = restAgentEngine;
        this.userConversationStore = userConversationStore;
        this.restAgentManagementStore = restAgentManagementStore;
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

            var memorySnapshot = restAgentEngine.readConversation(userConversation.getEnvironment(),
                    userConversation.getAgentId(),
                    userConversation.getConversationId(),
                    returnDetailed,
                    returnCurrentStepOnly, returningFields);

            Property languageProperty = extractLanguageProperty(memorySnapshot);
            if (!userConversationResult.isNewlyCreatedConversation() &&
                    (languageProperty != null && languageProperty.getValueString() != null &&
                            !languageProperty.getValueString().equals(language))) {
                restAgentEngine.rerunLastConversationStep(userConversation.getEnvironment(),
                        userConversation.getAgentId(),
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
            var userConversation = initUserConversation(intent, userId, extractLanguage(inputData))
                    .getUserConversation();

            checkUserAuthIfApplicable(userConversation);

            restAgentEngine.sayWithinContext(
                    userConversation.getEnvironment(),
                    userConversation.getAgentId(),
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
            try {
                userConversation = createNewConversation(intent, userId, language);
            } catch (CannotCreateConversationException e) {
                userConversation = getUserConversation(intent, userId);
            }
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
                restAgentEngine.endConversation(userConversation.getConversationId());
            }
            return Response.ok().build();
        } catch (IResourceStore.ResourceStoreException e) {
            throw sneakyThrow(e);
        }
    }

    @Override
    public Boolean isUndoAvailable(String intent, String userId) {
        var userConversation = getUserConversation(intent, userId);
        if (userConversation != null) {
            checkUserAuthIfApplicable(userConversation);
            return restAgentEngine.isUndoAvailable(
                    userConversation.getEnvironment(),
                    userConversation.getAgentId(),
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
            return restAgentEngine.undo(
                    userConversation.getEnvironment(),
                    userConversation.getAgentId(),
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
            return restAgentEngine.isRedoAvailable(
                    userConversation.getEnvironment(),
                    userConversation.getAgentId(),
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
            return restAgentEngine.redo(
                    userConversation.getEnvironment(),
                    userConversation.getAgentId(),
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
            throw sneakyThrow(e);
        }
    }

    private boolean isConversationEnded(UserConversation userConversation) {
        ConversationState conversationState = restAgentEngine.getConversationState(
                userConversation.getEnvironment(), userConversation.getConversationId());
        return conversationState.equals(ConversationState.ENDED);
    }

    private UserConversation createNewConversation(String intent, String userId, String language)
            throws CannotCreateConversationException {

        AgentTriggerConfiguration agentTriggerConfig = getAgentTrigger(intent);
        AgentDeployment agentDeployment = getRandom(agentTriggerConfig.getAgentDeployments());
        String agentId = agentDeployment.getAgentId();
        Map<String, Context> initialContext = agentDeployment.getInitialContext();
        initialContext.put(KEY_LANG, new Context(Context.ContextType.string, language));
        Response agentResponse = restAgentEngine.startConversationWithContext(agentDeployment.getEnvironment(),
                agentId,
                userId,
                initialContext);
        int responseHttpCode = agentResponse.getStatus();
        if (responseHttpCode == 201) {
            var locationUri = URI.create(agentResponse.getHeaders().get("location").getFirst().toString());
            var resourceId = RestUtilities.extractResourceId(locationUri);
            try {
                return createUserConversation(intent, userId, agentDeployment, resourceId.getId());
            } catch (ResourceAlreadyExistsException e) {
                throw new CannotCreateConversationException(
                        String.format("Cannot create conversation for agentId=%s in environment=%s (httpCode=%s), " +
                                "Conversation already exists",
                                agentId,
                                agentDeployment.getEnvironment(),
                                responseHttpCode));
            } catch (IResourceStore.ResourceStoreException e) {
                throw sneakyThrow(e);
            }
        } else {
            throw new CannotCreateConversationException(
                    String.format("Cannot create conversation for agentId=%s in environment=%s (httpCode=%s)",
                            agentId,
                            agentDeployment.getEnvironment(),
                            responseHttpCode));
        }
    }

    private UserConversation createUserConversation(String intent, String userId,
            AgentDeployment agentDeployment, String conversationId)
            throws ResourceAlreadyExistsException, IResourceStore.ResourceStoreException {

        UserConversation userConversation = new UserConversation(
                intent,
                userId,
                agentDeployment.getEnvironment(),
                agentDeployment.getAgentId(),
                conversationId);

        storeUserConversation(userConversation);

        return userConversation;
    }

    private AgentDeployment getRandom(List<AgentDeployment> agentDeployments) {
        return agentDeployments.get(new Random().nextInt(agentDeployments.size()));
    }

    private AgentTriggerConfiguration getAgentTrigger(String intent) {
        return restAgentManagementStore.readAgentTrigger(intent);
    }

    private UserConversation getUserConversation(String intent, String userId) {
        try {
            return userConversationStore.readUserConversation(intent, userId);
        } catch (IResourceStore.ResourceStoreException e) {
            throw sneakyThrow(e);
        }
    }

    private void storeUserConversation(UserConversation userConversation)
            throws ResourceAlreadyExistsException, IResourceStore.ResourceStoreException {

        userConversationStore.createUserConversation(userConversation);

    }

    private void checkUserAuthIfApplicable(UserConversation userConversation) throws UnauthorizedException {
        if (checkForUserAuthentication &&
                !production.equals(userConversation.getEnvironment()) &&
                identity.isAnonymous()) {
            throw new UnauthorizedException();
        }
    }

    private static class CannotCreateConversationException extends Exception {
        CannotCreateConversationException(String message) {
            super(message);
        }
    }

    public static class UserConversationResult {
        private boolean newlyCreatedConversation;
        private UserConversation userConversation;

        public UserConversationResult() {
        }

        public UserConversationResult(boolean newlyCreatedConversation, UserConversation userConversation) {
            this.newlyCreatedConversation = newlyCreatedConversation;
            this.userConversation = userConversation;
        }

        public boolean isNewlyCreatedConversation() {
            return newlyCreatedConversation;
        }

        public void setNewlyCreatedConversation(boolean newlyCreatedConversation) {
            this.newlyCreatedConversation = newlyCreatedConversation;
        }

        public UserConversation getUserConversation() {
            return userConversation;
        }

        public void setUserConversation(UserConversation userConversation) {
            this.userConversation = userConversation;
        }
    }
}
