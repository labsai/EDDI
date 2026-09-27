/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.UUID;

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
    public RestAgentManagement(IRestAgentEngine restAgentEngine, IUserConversationStore userConversationStore,
            IRestAgentTriggerStore restAgentManagementStore,
            @ConfigProperty(name = "quarkus.oidc.tenant-enabled") boolean checkForUserAuthentication) {
        this.restAgentEngine = restAgentEngine;
        this.userConversationStore = userConversationStore;
        this.restAgentManagementStore = restAgentManagementStore;
        this.checkForUserAuthentication = checkForUserAuthentication;
    }

    @Override
    public void loadConversationMemory(String intent, String userId, String language, Boolean returnDetailed, Boolean returnCurrentStepOnly,
                                       List<String> returningFields, AsyncResponse asyncResponse) {

        try {
            // initUserConversation authorizes the caller before it creates, deletes or
            // replaces anything, so an UnauthorizedException here means nothing changed.
            var userConversationResult = initUserConversation(intent, userId, language);
            var userConversation = userConversationResult.getUserConversation();

            var memorySnapshot = restAgentEngine.readConversation(userConversation.getConversationId(), returnDetailed, returnCurrentStepOnly,
                    returningFields);

            Property languageProperty = extractLanguageProperty(memorySnapshot);
            if (!userConversationResult.isNewlyCreatedConversation() && (languageProperty != null && languageProperty.getValueString() != null
                    && !languageProperty.getValueString().equals(language))) {
                restAgentEngine.rerunLastConversationStep(userConversation.getConversationId(), language, returnDetailed, returnCurrentStepOnly,
                        returningFields, asyncResponse);
            } else {
                asyncResponse.resume(memorySnapshot);
            }
        } catch (CannotCreateConversationException e) {
            throw new InternalServerErrorException(logAndBuildOpaqueMessage("Failed to load conversation memory", e));
        }
    }

    private static Property extractLanguageProperty(SimpleConversationMemorySnapshot memorySnapshot) {
        var conversationProperties = memorySnapshot.getConversationProperties();
        return conversationProperties != null ? conversationProperties.get("lang") : null;
    }

    @Override
    public void sayWithinContext(String intent, String userId, Boolean returnDetailed, Boolean returnCurrentStepOnly, List<String> returningFields,
                                 InputData inputData, AsyncResponse response) {
        try {
            // Authorized inside initUserConversation, before any side effect.
            var userConversation = initUserConversation(intent, userId, extractLanguage(inputData)).getUserConversation();

            restAgentEngine.sayWithinContext(userConversation.getConversationId(), returnDetailed, returnCurrentStepOnly, returningFields, inputData,
                    response);

        } catch (UnauthorizedException e) {
            // Hand the exception to the JAX-RS exception mappers, exactly as a throw from
            // loadConversationMemory is: Quarkus answers 401 with its authentication
            // challenge. The generic branch below would turn it into an opaque 500.
            response.resume(e);
        } catch (Exception e) {
            int status = e instanceof WebApplicationException webApplicationException
                    ? webApplicationException.getResponse().getStatus()
                    : Response.Status.INTERNAL_SERVER_ERROR.getStatusCode();

            // A 4xx message is authored for the client (e.g. the HITL 409 tells the
            // caller how to resolve the pause) and stays. Anything else is a server
            // fault whose message describes the deployment, so it is replaced.
            String entity = status < 400 || status >= 500
                    ? logAndBuildOpaqueMessage("Failed to process input", e)
                    : e.getLocalizedMessage();

            response.resume(Response.status(status).type(MediaType.TEXT_PLAIN).entity(entity).build());
        }
    }

    /**
     * Logs the failure detail at ERROR under a fresh correlation id and returns the
     * only thing safe to hand back to the caller: a fixed message plus that id.
     * <p>
     * The exception here can originate deep in the store layer (sneaky-thrown
     * {@code ResourceStoreException}s reach this catch), and those messages name
     * collections, hosts, and replica-set members. Echoing them turns any failing
     * request into deployment reconnaissance.
     */
    private static String logAndBuildOpaqueMessage(String context, Exception e) {
        String correlationId = UUID.randomUUID().toString();
        log.errorf(e, "%s [correlationId=%s]: %s", context, correlationId, e.getLocalizedMessage());
        return "Internal server error (correlationId: " + correlationId + ")";
    }

    /**
     * Resolves the caller's managed conversation, creating it when none exists and
     * replacing it when it has ended — and authorizes the caller before each of
     * those side effects, never after.
     * <p>
     * The authorization check depends on the environment of the conversation the
     * request acts on. A conversation that is about to be created (or to replace an
     * ended one) does not exist yet, so its environment is decided up front: the
     * agent deployment is picked from the intent's trigger first, the caller is
     * authorized against that deployment's environment, and only then is the
     * conversation started with <em>that same</em> deployment. The ended
     * conversation's own environment is not consulted — it is not what the request
     * goes on to talk to. A live existing conversation is authorized against its
     * stored environment. Only reads (the user conversation, the engine's
     * conversation state, the trigger) happen before the check, so an
     * {@link UnauthorizedException} from here means nothing was created, deleted or
     * replaced.
     */
    private UserConversationResult initUserConversation(String intent, String userId, String language) throws CannotCreateConversationException {

        UserConversation userConversation;
        boolean newlyCreatedConversation = false;

        userConversation = getUserConversation(intent, userId);
        if (userConversation == null) {
            AgentDeployment agentDeployment = selectAgentDeployment(intent);
            checkUserAuthIfApplicable(agentDeployment.getEnvironment());
            try {
                userConversation = createNewConversation(intent, userId, language, agentDeployment);
            } catch (CannotCreateConversationException e) {
                // A concurrent request stored its conversation first; act on that one.
                userConversation = getUserConversation(intent, userId);
            }
            newlyCreatedConversation = true;
        }

        if (isConversationEnded(userConversation)) {
            AgentDeployment agentDeployment = selectAgentDeployment(intent);
            checkUserAuthIfApplicable(agentDeployment.getEnvironment());
            deleteUserConversation(intent, userId);
            userConversation = createNewConversation(intent, userId, language, agentDeployment);
            newlyCreatedConversation = true;
        }

        // The conversation the request acts on. For a live existing conversation this
        // is
        // the only check, and nothing has been mutated yet; for one this request
        // created
        // it repeats the check above; for one a concurrent request created it checks an
        // environment this request did not pick.
        checkUserAuthIfApplicable(userConversation);
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
            return restAgentEngine.isUndoAvailable(userConversation.getConversationId());
        } else {
            return false;
        }
    }

    @Override
    public Response undo(String intent, String userId) {
        var userConversation = getUserConversation(intent, userId);
        if (userConversation != null) {
            checkUserAuthIfApplicable(userConversation);
            return restAgentEngine.undo(userConversation.getConversationId());
        } else {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
    }

    @Override
    public Boolean isRedoAvailable(String intent, String userId) {
        var userConversation = getUserConversation(intent, userId);
        if (userConversation != null) {
            checkUserAuthIfApplicable(userConversation);
            return restAgentEngine.isRedoAvailable(userConversation.getConversationId());
        } else {
            return false;
        }
    }

    @Override
    public Response redo(String intent, String userId) {
        var userConversation = getUserConversation(intent, userId);
        if (userConversation != null) {
            checkUserAuthIfApplicable(userConversation);
            return restAgentEngine.redo(userConversation.getConversationId());
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
        ConversationState conversationState = restAgentEngine.getConversationState(userConversation.getConversationId());
        return conversationState.equals(ConversationState.ENDED);
    }

    /**
     * Picks the deployment a new conversation for {@code intent} will be started
     * with. Read-only: it is called before the caller is authorized, so the check
     * can use the environment the conversation will actually have.
     */
    private AgentDeployment selectAgentDeployment(String intent) {
        AgentTriggerConfiguration agentTriggerConfig = getAgentTrigger(intent);
        return getRandom(agentTriggerConfig.getAgentDeployments());
    }

    private UserConversation createNewConversation(String intent, String userId, String language, AgentDeployment agentDeployment)
            throws CannotCreateConversationException {

        String agentId = agentDeployment.getAgentId();
        // A per-request copy: the deployment belongs to the trigger held in the shared
        // agentTriggers cache, so writing this caller's language into its own map would
        // hand it to concurrent callers (and write a plain HashMap from several
        // threads).
        // The engine still receives a lang entry even when language is null, as before.
        Map<String, Context> triggerContext = agentDeployment.getInitialContext();
        Map<String, Context> initialContext = triggerContext != null ? new HashMap<>(triggerContext) : new HashMap<>();
        initialContext.put(KEY_LANG, new Context(Context.ContextType.string, language));
        Response agentResponse = restAgentEngine.startConversationWithContext(agentId, agentDeployment.getEnvironment(), userId, initialContext);
        int responseHttpCode = agentResponse.getStatus();
        if (responseHttpCode == 201) {
            var locationUri = URI.create(agentResponse.getHeaders().get("location").getFirst().toString());
            var resourceId = RestUtilities.extractResourceId(locationUri);
            try {
                return createUserConversation(intent, userId, agentDeployment, resourceId.getId());
            } catch (ResourceAlreadyExistsException e) {
                throw new CannotCreateConversationException(
                        String.format("Cannot create conversation for agentId=%s in environment=%s (httpCode=%s), " + "Conversation already exists",
                                agentId, agentDeployment.getEnvironment(), responseHttpCode));
            } catch (IResourceStore.ResourceStoreException e) {
                throw sneakyThrow(e);
            }
        } else {
            throw new CannotCreateConversationException(String.format("Cannot create conversation for agentId=%s in environment=%s (httpCode=%s)",
                    agentId, agentDeployment.getEnvironment(), responseHttpCode));
        }
    }

    private UserConversation createUserConversation(String intent, String userId, AgentDeployment agentDeployment, String conversationId)
            throws ResourceAlreadyExistsException, IResourceStore.ResourceStoreException {

        UserConversation userConversation = new UserConversation(intent, userId, agentDeployment.getEnvironment(), agentDeployment.getAgentId(),
                conversationId);

        storeUserConversation(userConversation);

        return userConversation;
    }

    private AgentDeployment getRandom(List<AgentDeployment> agentDeployments) {
        // ThreadLocalRandom: this picks a deployment per incoming request on an
        // application-scoped bean, so a per-call Random both allocates and shares
        // its seed lock across concurrent callers.
        return agentDeployments.get(ThreadLocalRandom.current().nextInt(agentDeployments.size()));
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
        checkUserAuthIfApplicable(userConversation.getEnvironment());
    }

    private void checkUserAuthIfApplicable(Deployment.Environment environment) throws UnauthorizedException {
        if (checkForUserAuthentication && !production.equals(environment) && identity.isAnonymous()) {
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
