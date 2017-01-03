package io.sls.core.ui.rest.internal;

import ai.labs.lifecycle.IConversation;
import ai.labs.lifecycle.LifecycleException;
import io.sls.core.rest.IRestBotEngine;
import io.sls.memory.IConversationMemory;
import io.sls.memory.IConversationMemoryStore;
import io.sls.memory.impl.ConversationMemoryUtilities;
import io.sls.memory.model.ConversationMemorySnapshot;
import io.sls.memory.model.ConversationState;
import io.sls.memory.model.Deployment;
import io.sls.memory.model.SimpleConversationMemorySnapshot;
import io.sls.persistence.IResourceStore;
import io.sls.runtime.IBot;
import io.sls.runtime.IBotFactory;
import io.sls.runtime.SystemRuntime;
import io.sls.runtime.service.ServiceException;
import io.sls.utilities.RestUtilities;
import io.sls.utilities.RuntimeUtilities;
import lombok.extern.slf4j.Slf4j;
import org.jboss.resteasy.spi.NoLogWebApplicationException;

import javax.inject.Inject;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.core.Response;
import java.net.URI;
import java.util.concurrent.Callable;

/**
 * @author ginccc
 */
@Slf4j
public class RestBotEngine implements IRestBotEngine {
    final String resourceURI = "resource://io.sls.conversation/conversationstore/conversations/";
    private final IBotFactory botFactory;
    private final IConversationMemoryStore conversationMemoryStore;

    @Inject
    public RestBotEngine(IBotFactory botFactory,
                         IConversationMemoryStore conversationMemoryStore) {
        this.botFactory = botFactory;
        this.conversationMemoryStore = conversationMemoryStore;
    }

    @Override
    public Response startConversation(Deployment.Environment environment, String botId) {
        RuntimeUtilities.checkNotNull(environment, "environment");
        RuntimeUtilities.checkNotNull(botId, "botId");

        try {
            IBot latestBot = botFactory.getLatestBot(environment, botId);
            if (latestBot == null) {
                String message = "No instance of bot (botId=%s) deployed!";
                message = String.format(message, botId);
                throw new BotNotFoundException(message);
            }

            IConversation conversation = latestBot.startConversation(null);
            String conversationId = storeConversationMemory(conversation.getConversationMemory(), environment);
            URI createdUri = RestUtilities.createURI(resourceURI, conversationId);
            return Response.created(createdUri).entity(createdUri).build();
        } catch (ServiceException |
                IResourceStore.ResourceStoreException |
                BotNotFoundException |
                InstantiationException |
                LifecycleException |
                IllegalAccessException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);
        }
    }

    @Override
    public SimpleConversationMemorySnapshot readConversationLog(Deployment.Environment environment, String botId, String conversationId) {
        RuntimeUtilities.checkNotNull(environment, "environment");
        RuntimeUtilities.checkNotNull(botId, "botId");
        RuntimeUtilities.checkNotNull(conversationId, "conversationId");
        try {
            ConversationMemorySnapshot conversationMemorySnapshot = conversationMemoryStore.loadConversationMemorySnapshot(conversationId);
            if (!botId.equals(conversationMemorySnapshot.getBotId())) {
                String message = "conversationId: %s does not belong to bot with id: %s";
                message = String.format(message, conversationId, botId);
                throw new IllegalAccessException(message);
            }
            SimpleConversationMemorySnapshot simpleConversationMemorySnapshot = ConversationMemoryUtilities.convertSimpleConversationMemory(conversationMemorySnapshot);
            return simpleConversationMemorySnapshot;
        } catch (IResourceStore.ResourceStoreException | IllegalAccessException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NoLogWebApplicationException(Response.Status.NOT_FOUND);
        }
    }

    @Override
    public ConversationState getConversationState(Deployment.Environment environment, String conversationId) {
        RuntimeUtilities.checkNotNull(environment, "environment");
        RuntimeUtilities.checkNotNull(conversationId, "conversationId");

        ConversationState conversationState = conversationMemoryStore.getConversationState(conversationId);
        if (conversationState == null) {
            String message = "No conversation found! (conversationId=%s)";
            message = String.format(message, conversationId);
            throw new NoLogWebApplicationException(new Throwable(message), Response.Status.NOT_FOUND);
        }

        return conversationState;
    }

    @Override
    public Response say(final Deployment.Environment environment, String botId, final String conversationId, final String message) {
        RuntimeUtilities.checkNotNull(environment, "environment");
        RuntimeUtilities.checkNotNull(botId, "botId");
        RuntimeUtilities.checkNotNull(conversationId, "conversationId");
        RuntimeUtilities.checkNotNull(message, "message");

        try {
            final IConversationMemory conversationMemory = loadConversationMemory(conversationId);
            if (conversationMemory == null) {
                String msg = "No converation found with id: %s";
                msg = String.format(msg, conversationId);
                throw new IllegalAccessException(msg);
            }
            if (!botId.equals(conversationMemory.getBotId())) {
                throw new IllegalAccessException("Supplied botId is incompatible to conversationId");
            }

            setConversationState(conversationId, ConversationState.IN_PROGRESS);

            IBot bot = botFactory.getBot(environment, conversationMemory.getBotId(), conversationMemory.getBotVersion());
            if (bot == null) {
                String msg = "No Version of bot %s deployed.";
                msg = String.format(msg, conversationMemory.getBotId());
                throw new Exception(msg);
            }
            final IConversation conversation = bot.continueConversation(conversationMemory, null);
            if (conversation.isEnded()) {
                throw new NoLogWebApplicationException(new Throwable("Conversation has ended!"), Response.Status.GONE);
            }

            if (conversation.isInProgress()) {
                throw new NoLogWebApplicationException(new Throwable("Conversation is in Progress!"), Response.Status.FORBIDDEN);
            }

            Callable<Void> processUserInput = () -> {
                try {
                    conversation.say(message);
                    storeConversationMemory(conversationMemory, environment);
                } catch (Exception e) {
                    setConversationState(conversationId, ConversationState.ERROR);
                    log.error("Error while processing user input", e);
                    throw e;
                }

                return null;
            };

            SystemRuntime.getRuntime().submitCallable(processUserInput, null);
            return Response.accepted().build();
        } catch (InstantiationException | IllegalAccessException e) {
            String errorMsg = "Error while processing message!";
            log.error(errorMsg, e);
            throw new InternalServerErrorException(errorMsg, e);
        } catch (IResourceStore.ResourceStoreException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NoLogWebApplicationException(Response.Status.NOT_FOUND);
        } catch (Exception e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);
        }
    }

    @Override
    public Boolean isUndoAvailable(Deployment.Environment environment, String botId, String conversationId) throws Exception {
        RuntimeUtilities.checkNotNull(environment, "environment");
        RuntimeUtilities.checkNotNull(botId, "botId");
        RuntimeUtilities.checkNotNull(conversationId, "conversationId");
        final IConversationMemory conversationMemory = loadConversationMemory(conversationId);
        return conversationMemory.isUndoAvailable();
    }

    @Override
    public Response undo(final Deployment.Environment environment, String botId, final String conversationId) throws Exception {
        RuntimeUtilities.checkNotNull(environment, "environment");
        RuntimeUtilities.checkNotNull(botId, "botId");
        RuntimeUtilities.checkNotNull(conversationId, "conversationId");

        try {
            final IConversationMemory conversationMemory = loadConversationMemory(conversationId);
            if (conversationMemory == null) {
                String message = "No converation found with id: %s";
                message = String.format(message, conversationId);
                throw new IllegalAccessException(message);
            }

            if (!botId.equals(conversationMemory.getBotId())) {
                throw new IllegalAccessException("Supplied botId is incompatible to conversationId");
            }

            Callable<Void> processUserInput = () -> {

                try {
                    if (conversationMemory.isUndoAvailable()) {
                        conversationMemory.undoLastStep();
                        storeConversationMemory(conversationMemory, environment);
                    }
                } catch (Exception e) {
                    log.error("Error while Undo!", e);
                    throw e;
                }

                return null;
            };

            SystemRuntime.getRuntime().submitCallable(processUserInput, null);

            return Response.accepted().build();
        } catch (IllegalAccessException e) {
            String errorMsg = "Error while processing message!";
            log.error(errorMsg, e);
            throw new InternalServerErrorException(errorMsg, e);
        } catch (IResourceStore.ResourceStoreException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NoLogWebApplicationException(Response.Status.NOT_FOUND);
        } catch (Exception e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);
        }
    }

    @Override
    public Boolean isRedoAvailable(final Deployment.Environment environment, String botId, String conversationId) throws Exception {
        RuntimeUtilities.checkNotNull(environment, "environment");
        RuntimeUtilities.checkNotNull(botId, "botId");
        RuntimeUtilities.checkNotNull(conversationId, "conversationId");
        final IConversationMemory conversationMemory = loadConversationMemory(conversationId);
        return conversationMemory.isRedoAvailable();
    }

    @Override
    public Response redo(final Deployment.Environment environment, String botId, final String conversationId) throws Exception {
        RuntimeUtilities.checkNotNull(environment, "environment");
        RuntimeUtilities.checkNotNull(botId, "botId");
        RuntimeUtilities.checkNotNull(conversationId, "conversationId");

        try {
            final IConversationMemory conversationMemory = loadConversationMemory(conversationId);
            if (conversationMemory == null) {
                String message = "No converation found with id: %s";
                message = String.format(message, conversationId);
                throw new IllegalAccessException(message);
            }

            if (!botId.equals(conversationMemory.getBotId())) {
                throw new IllegalAccessException("Supplied botId is incompatible to conversationId");
            }

            Callable<Void> processUserInput = () -> {

                try {
                    if (conversationMemory.isRedoAvailable()) {
                        conversationMemory.redoLastStep();
                        storeConversationMemory(conversationMemory, environment);
                    }
                } catch (Exception e) {
                    log.error("Error while Redo!", e);
                    throw e;
                }

                return null;
            };

            SystemRuntime.getRuntime().submitCallable(processUserInput, null);
            return Response.accepted().build();
        } catch (IllegalAccessException e) {
            String errorMsg = "Error while processing message!";
            log.error(errorMsg, e);
            throw new InternalServerErrorException(errorMsg, e);
        } catch (IResourceStore.ResourceStoreException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NoLogWebApplicationException(Response.Status.NOT_FOUND);
        } catch (Exception e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);
        }
    }

    private IConversationMemory loadConversationMemory(String conversationId) throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {
        ConversationMemorySnapshot conversationMemorySnapshot = conversationMemoryStore.loadConversationMemorySnapshot(conversationId);
        return ConversationMemoryUtilities.convertConversationMemorySnapshot(conversationMemorySnapshot);
    }

    private void setConversationState(String conversationId, ConversationState conversationState) {
        conversationMemoryStore.setConversationState(conversationId, conversationState);
    }

    private String storeConversationMemory(IConversationMemory conversationMemory, Deployment.Environment environment) throws IResourceStore.ResourceStoreException {
        ConversationMemorySnapshot memorySnapshot = ConversationMemoryUtilities.convertConversationMemory(conversationMemory);
        memorySnapshot.setEnvironment(environment);
        return conversationMemoryStore.storeConversationMemorySnapshot(memorySnapshot);
    }

    private class BotNotFoundException extends Throwable {
        public BotNotFoundException(String message) {
            super(message);
        }
    }
}
