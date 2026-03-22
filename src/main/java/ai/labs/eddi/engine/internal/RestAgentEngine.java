package ai.labs.eddi.engine.internal;

import ai.labs.eddi.datastore.IResourceStore.ResourceNotFoundException;
import ai.labs.eddi.datastore.IResourceStore.ResourceStoreException;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IConversationService.*;
import ai.labs.eddi.engine.api.IRestAgentEngine;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.model.Deployment;
import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.engine.model.InputData;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.container.AsyncResponse;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import static ai.labs.eddi.engine.exception.SneakyThrow.sneakyThrow;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static ai.labs.eddi.engine.internal.RestAgentManagement.KEY_LANG;
import static ai.labs.eddi.engine.model.Context.ContextType.string;
import static ai.labs.eddi.utils.RuntimeUtilities.checkNotEmpty;
import static ai.labs.eddi.utils.RuntimeUtilities.checkNotNull;
import static jakarta.ws.rs.core.MediaType.TEXT_PLAIN;

/**
 * Thin REST adapter — delegates all business logic to
 * {@link IConversationService}.
 *
 * @author ginccc
 */
@ApplicationScoped
public class RestAgentEngine implements IRestAgentEngine {

    private final IConversationService conversationService;
    private final int agentTimeout;

    private static final Logger LOGGER = Logger.getLogger(RestAgentEngine.class);

    @Inject
    public RestAgentEngine(IConversationService conversationService,
            @ConfigProperty(name = "systemRuntime.agentTimeoutInSeconds") int agentTimeout) {
        this.conversationService = conversationService;
        this.agentTimeout = agentTimeout;
    }

    @Override
    public Response startConversation(Environment environment, String agentId, String userId) {
        return startConversationWithContext(environment, agentId, userId, Collections.emptyMap());
    }

    @Override
    public Response startConversationWithContext(Environment environment,
            String agentId,
            String userId,
            Map<String, Context> context) {
        try {
            var result = conversationService.startConversation(environment, agentId, userId, context);
            return Response.created(result.conversationUri()).build();
        } catch (AgentNotReadyException e) {
            return Response.status(Response.Status.NOT_FOUND).type(TEXT_PLAIN).entity(e.getMessage()).build();
        } catch (ResourceStoreException | ResourceNotFoundException e) {
            LOGGER.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);
        }
    }

    @Override
    public Response endConversation(String conversationId) {
        conversationService.endConversation(conversationId);
        return Response.ok().build();
    }

    @Override
    public SimpleConversationMemorySnapshot readConversation(Deployment.Environment environment,
            String agentId,
            String conversationId,
            Boolean returnDetailed,
            Boolean returnCurrentStepOnly,
            List<String> returningFields) {
        try {
            return conversationService.readConversation(environment, agentId, conversationId,
                    returnDetailed, returnCurrentStepOnly, returningFields);
        } catch (AgentMismatchException e) {
            LOGGER.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);
        } catch (ResourceStoreException e) {
            LOGGER.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);
        } catch (ResourceNotFoundException e) {
            throw sneakyThrow(e);
        }
    }

    @Override
    public Response readConversationLog(String conversationId, String outputType, Integer logSize) {
        try {
            var result = conversationService.readConversationLog(conversationId, outputType, logSize);
            return Response.ok(result.content(), result.mediaType()).build();
        } catch (ResourceStoreException | ResourceNotFoundException e) {
            LOGGER.error(e.getLocalizedMessage(), e);
            throw sneakyThrow(e);
        }
    }

    @Override
    public ConversationState getConversationState(Environment environment, String conversationId) {
        return conversationService.getConversationState(environment, conversationId);
    }

    @Override
    public void rerunLastConversationStep(Environment environment,
            String agentId,
            String conversationId,
            String language,
            Boolean returnDetailed,
            Boolean returnCurrentStepOnly,
            List<String> returningFields,
            final AsyncResponse response) {
        checkNotNull(environment, "environment");
        checkNotNull(agentId, "agentId");
        checkNotNull(conversationId, "conversationId");
        checkNotEmpty(language, "language");

        sayInternal(environment, agentId, conversationId, returnDetailed, returnCurrentStepOnly,
                returningFields,
                new InputData("", Map.of(KEY_LANG, new Context(string, language))),
                true, response);
    }

    @Override
    public void say(final Environment environment,
            final String agentId, final String conversationId,
            final Boolean returnDetailed, final Boolean returnCurrentStepOnly,
            final List<String> returningFields, final String message, final AsyncResponse response) {

        sayWithinContext(environment, agentId, conversationId, returnDetailed, returnCurrentStepOnly,
                returningFields, new InputData(message, new HashMap<>()), response);
    }

    @Override
    public void sayWithinContext(final Environment environment,
            final String agentId, final String conversationId,
            final Boolean returnDetailed, final Boolean returnCurrentStepOnly,
            final List<String> returningFields, final InputData inputData,
            final AsyncResponse response) {

        checkNotNull(environment, "environment");
        checkNotNull(agentId, "agentId");
        checkNotNull(conversationId, "conversationId");
        checkNotNull(inputData, "inputData");
        checkNotNull(inputData.getInput(), "inputData.input");

        sayInternal(environment, agentId, conversationId, returnDetailed,
                returnCurrentStepOnly, returningFields, inputData, false, response);
    }

    private void sayInternal(Environment environment, String agentId, String conversationId,
            Boolean returnDetailed, Boolean returnCurrentStepOnly,
            List<String> returningFields, InputData inputData,
            boolean rerunOnly, AsyncResponse response) {

        response.setTimeout(agentTimeout, TimeUnit.SECONDS);
        response.setTimeoutHandler(
                (asyncResp) -> asyncResp.resume(Response.status(Response.Status.REQUEST_TIMEOUT).build()));

        try {
            conversationService.say(environment, agentId, conversationId,
                    returnDetailed, returnCurrentStepOnly, returningFields,
                    inputData, rerunOnly, response::resume);
        } catch (AgentMismatchException e) {
            response.resume(Response.status(Response.Status.CONFLICT).type(TEXT_PLAIN).entity(e.getMessage()).build());
        } catch (AgentNotReadyException e) {
            response.resume(new NotFoundException(e.getMessage()));
        } catch (ConversationEndedException e) {
            response.resume(Response.status(Response.Status.GONE).entity(e.getMessage()).build());
        } catch (ResourceNotFoundException e) {
            response.resume(new NotFoundException());
        } catch (Exception e) {
            LOGGER.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e.getLocalizedMessage());
        }
    }

    @Override
    public Boolean isUndoAvailable(Environment environment, String agentId, String conversationId) {
        try {
            return conversationService.isUndoAvailable(environment, agentId, conversationId);
        } catch (ResourceStoreException e) {
            LOGGER.error(e.getLocalizedMessage(), e);
            throw sneakyThrow(e);
        } catch (ResourceNotFoundException e) {
            throw sneakyThrow(e);
        }
    }

    @Override
    public Response undo(final Environment environment, String agentId, final String conversationId) {
        try {
            boolean performed = conversationService.undo(environment, agentId, conversationId);
            return performed ? Response.ok().build() : Response.status(Response.Status.CONFLICT).build();
        } catch (AgentMismatchException e) {
            LOGGER.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException("Error while processing message!", e);
        } catch (ResourceNotFoundException e) {
            throw sneakyThrow(e);
        } catch (ResourceStoreException e) {
            LOGGER.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);
        }
    }

    @Override
    public Boolean isRedoAvailable(final Environment environment, String agentId, String conversationId) {
        try {
            return conversationService.isRedoAvailable(environment, agentId, conversationId);
        } catch (ResourceStoreException e) {
            throw sneakyThrow(e);
        } catch (ResourceNotFoundException e) {
            LOGGER.error(e.getLocalizedMessage(), e);
            throw sneakyThrow(e);
        }
    }

    @Override
    public Response redo(final Environment environment, String agentId, final String conversationId) {
        try {
            boolean performed = conversationService.redo(environment, agentId, conversationId);
            return performed ? Response.ok().build() : Response.status(Response.Status.CONFLICT).build();
        } catch (AgentMismatchException e) {
            LOGGER.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException("Error while processing message!", e);
        } catch (ResourceNotFoundException e) {
            throw sneakyThrow(e);
        } catch (ResourceStoreException e) {
            LOGGER.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);
        }
    }
}
