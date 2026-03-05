package ai.labs.eddi.engine.internal;

import ai.labs.eddi.datastore.IResourceStore.ResourceNotFoundException;
import ai.labs.eddi.datastore.IResourceStore.ResourceStoreException;
import ai.labs.eddi.engine.IConversationService;
import ai.labs.eddi.engine.IConversationService.*;
import ai.labs.eddi.engine.IRestBotEngine;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.model.ConversationState;
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

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static ai.labs.eddi.engine.internal.RestBotManagement.KEY_LANG;
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
public class RestBotEngine implements IRestBotEngine {

    private final IConversationService conversationService;
    private final int botTimeout;

    private static final Logger LOGGER = Logger.getLogger(RestBotEngine.class);

    @Inject
    public RestBotEngine(IConversationService conversationService,
            @ConfigProperty(name = "systemRuntime.botTimeoutInSeconds") int botTimeout) {
        this.conversationService = conversationService;
        this.botTimeout = botTimeout;
    }

    @Override
    public Response startConversation(Environment environment, String botId, String userId) {
        return startConversationWithContext(environment, botId, userId, Collections.emptyMap());
    }

    @Override
    public Response startConversationWithContext(Environment environment,
            String botId,
            String userId,
            Map<String, Context> context) {
        try {
            var result = conversationService.startConversation(environment, botId, userId, context);
            return Response.created(result.conversationUri()).build();
        } catch (BotNotReadyException e) {
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
            String botId,
            String conversationId,
            Boolean returnDetailed,
            Boolean returnCurrentStepOnly,
            List<String> returningFields) {
        try {
            return conversationService.readConversation(environment, botId, conversationId,
                    returnDetailed, returnCurrentStepOnly, returningFields);
        } catch (BotMismatchException e) {
            LOGGER.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);
        } catch (ResourceStoreException e) {
            LOGGER.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);
        } catch (ResourceNotFoundException e) {
            throw new NotFoundException();
        }
    }

    @Override
    public Response readConversationLog(String conversationId, String outputType, Integer logSize) {
        try {
            var result = conversationService.readConversationLog(conversationId, outputType, logSize);
            return Response.ok(result.content(), result.mediaType()).build();
        } catch (ResourceStoreException | ResourceNotFoundException e) {
            LOGGER.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException();
        }
    }

    @Override
    public ConversationState getConversationState(Environment environment, String conversationId) {
        return conversationService.getConversationState(environment, conversationId);
    }

    @Override
    public void rerunLastConversationStep(Environment environment,
            String botId,
            String conversationId,
            String language,
            Boolean returnDetailed,
            Boolean returnCurrentStepOnly,
            List<String> returningFields,
            final AsyncResponse response) {
        checkNotNull(environment, "environment");
        checkNotNull(botId, "botId");
        checkNotNull(conversationId, "conversationId");
        checkNotEmpty(language, "language");

        sayInternal(environment, botId, conversationId, returnDetailed, returnCurrentStepOnly,
                returningFields,
                new InputData("", Map.of(KEY_LANG, new Context(string, language))),
                true, response);
    }

    @Override
    public void say(final Environment environment,
            final String botId, final String conversationId,
            final Boolean returnDetailed, final Boolean returnCurrentStepOnly,
            final List<String> returningFields, final String message, final AsyncResponse response) {

        sayWithinContext(environment, botId, conversationId, returnDetailed, returnCurrentStepOnly,
                returningFields, new InputData(message, new HashMap<>()), response);
    }

    @Override
    public void sayWithinContext(final Environment environment,
            final String botId, final String conversationId,
            final Boolean returnDetailed, final Boolean returnCurrentStepOnly,
            final List<String> returningFields, final InputData inputData,
            final AsyncResponse response) {

        checkNotNull(environment, "environment");
        checkNotNull(botId, "botId");
        checkNotNull(conversationId, "conversationId");
        checkNotNull(inputData, "inputData");
        checkNotNull(inputData.getInput(), "inputData.input");

        sayInternal(environment, botId, conversationId, returnDetailed,
                returnCurrentStepOnly, returningFields, inputData, false, response);
    }

    private void sayInternal(Environment environment, String botId, String conversationId,
            Boolean returnDetailed, Boolean returnCurrentStepOnly,
            List<String> returningFields, InputData inputData,
            boolean rerunOnly, AsyncResponse response) {

        response.setTimeout(botTimeout, TimeUnit.SECONDS);
        response.setTimeoutHandler(
                (asyncResp) -> asyncResp.resume(Response.status(Response.Status.REQUEST_TIMEOUT).build()));

        try {
            conversationService.say(environment, botId, conversationId,
                    returnDetailed, returnCurrentStepOnly, returningFields,
                    inputData, rerunOnly, response::resume);
        } catch (BotMismatchException e) {
            response.resume(Response.status(Response.Status.CONFLICT).type(TEXT_PLAIN).entity(e.getMessage()).build());
        } catch (BotNotReadyException e) {
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
    public Boolean isUndoAvailable(Environment environment, String botId, String conversationId) {
        try {
            return conversationService.isUndoAvailable(environment, botId, conversationId);
        } catch (ResourceStoreException e) {
            LOGGER.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException();
        } catch (ResourceNotFoundException e) {
            throw new NotFoundException();
        }
    }

    @Override
    public Response undo(final Environment environment, String botId, final String conversationId) {
        try {
            boolean performed = conversationService.undo(environment, botId, conversationId);
            return performed ? Response.ok().build() : Response.status(Response.Status.CONFLICT).build();
        } catch (BotMismatchException e) {
            LOGGER.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException("Error while processing message!", e);
        } catch (ResourceNotFoundException e) {
            throw new NotFoundException();
        } catch (ResourceStoreException e) {
            LOGGER.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);
        }
    }

    @Override
    public Boolean isRedoAvailable(final Environment environment, String botId, String conversationId) {
        try {
            return conversationService.isRedoAvailable(environment, botId, conversationId);
        } catch (ResourceStoreException e) {
            throw new NotFoundException();
        } catch (ResourceNotFoundException e) {
            LOGGER.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException();
        }
    }

    @Override
    public Response redo(final Environment environment, String botId, final String conversationId) {
        try {
            boolean performed = conversationService.redo(environment, botId, conversationId);
            return performed ? Response.ok().build() : Response.status(Response.Status.CONFLICT).build();
        } catch (BotMismatchException e) {
            LOGGER.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException("Error while processing message!", e);
        } catch (ResourceNotFoundException e) {
            throw new NotFoundException();
        } catch (ResourceStoreException e) {
            LOGGER.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);
        }
    }
}
