/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.datastore.IResourceStore.ResourceNotFoundException;
import ai.labs.eddi.datastore.IResourceStore.ResourceStoreException;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IConversationService.*;
import ai.labs.eddi.engine.gdpr.ProcessingRestrictedException;
import ai.labs.eddi.engine.api.IRestAgentEngine;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.memory.model.ConversationState;
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
 * {@link IConversationService}. v6: simplified paths — conversation-scoped
 * operations use only conversationId.
 */
@ApplicationScoped
public class RestAgentEngine implements IRestAgentEngine {

    private final IConversationService conversationService;
    private final int agentTimeout;

    private static final Logger LOGGER = Logger.getLogger(RestAgentEngine.class);

    @Inject
    public RestAgentEngine(IConversationService conversationService, @ConfigProperty(name = "systemRuntime.agentTimeoutInSeconds") int agentTimeout) {
        this.conversationService = conversationService;
        this.agentTimeout = agentTimeout;
    }

    @Override
    public Response startConversation(String agentId, Environment environment, String userId) {
        return startConversationWithContext(agentId, environment, userId, Collections.emptyMap());
    }

    @Override
    public Response startConversationWithContext(String agentId, Environment environment, String userId, Map<String, Context> context) {
        try {
            var result = conversationService.startConversation(environment, agentId, userId, context);
            return Response.created(result.conversationUri()).build();
        } catch (ProcessingRestrictedException e) {
            LOGGER.warnf("GDPR processing restricted for user: %s", e.getMessage());
            return Response.status(Response.Status.FORBIDDEN).type(TEXT_PLAIN).entity(e.getMessage()).build();
        } catch (AgentNotReadyException e) {
            LOGGER.warn("Agent not ready: " + e.getMessage());
            return Response.status(Response.Status.NOT_FOUND).type(TEXT_PLAIN).entity("Agent is not deployed or not ready").build();
        } catch (ResourceStoreException | ResourceNotFoundException e) {
            LOGGER.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException("Failed to start conversation");
        }
    }

    @Override
    public Response endConversation(String conversationId) {
        conversationService.endConversation(conversationId);
        return Response.ok().build();
    }

    @Override
    public SimpleConversationMemorySnapshot readConversation(String conversationId, Boolean returnDetailed, Boolean returnCurrentStepOnly,
                                                             List<String> returningFields) {
        try {
            return conversationService.readConversation(conversationId, returnDetailed, returnCurrentStepOnly, returningFields);
        } catch (ResourceStoreException e) {
            LOGGER.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException("Failed to read conversation");
        } catch (ResourceNotFoundException e) {
            throw sneakyThrow(e);
        }
    }

    @Override
    public Response readConversationLog(String conversationId, String outputType, Integer logSize) {
        try {
            var result = conversationService.readConversationLog(conversationId, outputType, logSize);
            return Response.ok(result.content(), result.mediaType()).build();
        } catch (ResourceNotFoundException e) {
            throw sneakyThrow(e);
        } catch (ResourceStoreException e) {
            LOGGER.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException("Failed to read conversation log");
        }
    }

    @Override
    public ConversationState getConversationState(String conversationId) {
        return conversationService.getConversationState(conversationId);
    }

    @Override
    public void rerunLastConversationStep(String conversationId, String language, Boolean returnDetailed, Boolean returnCurrentStepOnly,
                                          List<String> returningFields, final AsyncResponse response) {
        checkNotNull(conversationId, "conversationId");
        checkNotEmpty(language, "language");

        sayInternal(conversationId, returnDetailed, returnCurrentStepOnly, returningFields,
                new InputData("", Map.of(KEY_LANG, new Context(string, language))), true, response);
    }

    @Override
    public void say(final String conversationId, final Boolean returnDetailed, final Boolean returnCurrentStepOnly,
                    final List<String> returningFields, final String message, final AsyncResponse response) {

        sayWithinContext(conversationId, returnDetailed, returnCurrentStepOnly, returningFields, new InputData(message, new HashMap<>()), response);
    }

    @Override
    public void sayWithinContext(final String conversationId, final Boolean returnDetailed, final Boolean returnCurrentStepOnly,
                                 final List<String> returningFields, final InputData inputData, final AsyncResponse response) {

        checkNotNull(conversationId, "conversationId");
        checkNotNull(inputData, "inputData");
        checkNotNull(inputData.getInput(), "inputData.input");

        sayInternal(conversationId, returnDetailed, returnCurrentStepOnly, returningFields, inputData, false, response);
    }

    private void sayInternal(String conversationId, Boolean returnDetailed, Boolean returnCurrentStepOnly, List<String> returningFields,
                             InputData inputData, boolean rerunOnly, AsyncResponse response) {

        response.setTimeout(agentTimeout, TimeUnit.SECONDS);
        response.setTimeoutHandler(asyncResp -> asyncResp.resume(Response.status(Response.Status.REQUEST_TIMEOUT).build()));

        try {
            conversationService.say(conversationId, returnDetailed, returnCurrentStepOnly, returningFields, inputData, rerunOnly, response::resume);
        } catch (AgentMismatchException e) {
            LOGGER.warn("Agent mismatch for conversation " + conversationId + ": " + e.getMessage());
            response.resume(Response.status(Response.Status.CONFLICT).type(TEXT_PLAIN).entity("Agent version mismatch").build());
        } catch (AgentNotReadyException e) {
            LOGGER.warn("Agent not ready for conversation " + conversationId + ": " + e.getMessage());
            response.resume(new NotFoundException("Agent is not deployed or not ready"));
        } catch (ConversationEndedException e) {
            response.resume(Response.status(Response.Status.GONE).entity("Conversation has ended").build());
        } catch (ProcessingRestrictedException e) {
            LOGGER.warnf("GDPR processing restricted: %s", e.getMessage());
            response.resume(Response.status(Response.Status.FORBIDDEN).type(TEXT_PLAIN).entity(e.getMessage()).build());
        } catch (ResourceNotFoundException e) {
            response.resume(new NotFoundException());
        } catch (Exception e) {
            LOGGER.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException("An internal error occurred");
        }
    }

    @Override
    public Boolean isUndoAvailable(String conversationId) {
        try {
            return conversationService.isUndoAvailable(conversationId);
        } catch (ResourceStoreException e) {
            LOGGER.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException("Failed to check undo availability");
        } catch (ResourceNotFoundException e) {
            throw sneakyThrow(e);
        }
    }

    @Override
    public Response undo(final String conversationId) {
        try {
            boolean performed = conversationService.undo(conversationId);
            return performed ? Response.ok().build() : Response.status(Response.Status.CONFLICT).build();
        } catch (ResourceNotFoundException e) {
            throw sneakyThrow(e);
        } catch (ResourceStoreException e) {
            LOGGER.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException("Failed to undo");
        }
    }

    @Override
    public Boolean isRedoAvailable(final String conversationId) {
        try {
            return conversationService.isRedoAvailable(conversationId);
        } catch (ResourceStoreException e) {
            LOGGER.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException("Failed to check redo availability");
        } catch (ResourceNotFoundException e) {
            throw sneakyThrow(e);
        }
    }

    @Override
    public Response redo(final String conversationId) {
        try {
            boolean performed = conversationService.redo(conversationId);
            return performed ? Response.ok().build() : Response.status(Response.Status.CONFLICT).build();
        } catch (ResourceNotFoundException e) {
            throw sneakyThrow(e);
        } catch (ResourceStoreException e) {
            LOGGER.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException("Failed to redo");
        }
    }
}
