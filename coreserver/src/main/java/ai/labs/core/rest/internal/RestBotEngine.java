package ai.labs.core.rest.internal;

import ai.labs.lifecycle.IConversation;
import ai.labs.lifecycle.LifecycleException;
import ai.labs.memory.ConversationMemoryUtilities;
import ai.labs.memory.IConversationMemory;
import ai.labs.memory.IConversationMemoryStore;
import ai.labs.memory.model.ConversationMemorySnapshot;
import ai.labs.memory.model.ConversationState;
import ai.labs.memory.model.Deployment;
import ai.labs.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.persistence.IResourceStore;
import ai.labs.rest.rest.IRestBotEngine;
import ai.labs.runtime.IBot;
import ai.labs.runtime.IBotFactory;
import ai.labs.runtime.IConversationCoordinator;
import ai.labs.runtime.SystemRuntime;
import ai.labs.runtime.service.ServiceException;
import ai.labs.utilities.RestUtilities;
import ai.labs.utilities.RuntimeUtilities;
import lombok.extern.slf4j.Slf4j;
import org.jboss.resteasy.spi.NoLogWebApplicationException;

import javax.inject.Inject;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.container.AsyncResponse;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.net.URI;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

/**
 * @author ginccc
 */
@Slf4j
public class RestBotEngine implements IRestBotEngine {
    private static final String resourceURI = "eddi://ai.labs.conversation/conversationstore/conversations/";
    private final IBotFactory botFactory;
    private final IConversationMemoryStore conversationMemoryStore;
    private final IConversationCoordinator conversationCoordinator;

    @Inject
    public RestBotEngine(IBotFactory botFactory,
                         IConversationMemoryStore conversationMemoryStore,
                         IConversationCoordinator conversationCoordinator) {
        this.botFactory = botFactory;
        this.conversationMemoryStore = conversationMemoryStore;
        this.conversationCoordinator = conversationCoordinator;
    }

    @Override
    public Response startConversation(Deployment.Environment environment, String botId) {
        RuntimeUtilities.checkNotNull(environment, "environment");
        RuntimeUtilities.checkNotNull(botId, "botId");

        try {
            IBot latestBot = botFactory.getLatestBot(environment, botId);
            if (latestBot == null) {
                String message = "No instance of bot (botId=%s) deployed in environment (environment=%s)!";
                message = String.format(message, botId, environment);
                return Response.status(Response.Status.NOT_FOUND).type(MediaType.TEXT_PLAIN).entity(message).build();
            }

            IConversation conversation = latestBot.startConversation(null);
            String conversationId = storeConversationMemory(conversation.getConversationMemory(), environment);
            URI createdUri = RestUtilities.createURI(resourceURI, conversationId);
            return Response.created(createdUri).build();
        } catch (ServiceException |
                IResourceStore.ResourceStoreException |
                InstantiationException |
                LifecycleException |
                IllegalAccessException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);
        }
    }

    @Override
    public SimpleConversationMemorySnapshot readConversation(Deployment.Environment environment, String botId, String conversationId, Boolean includeAll) {
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
            return ConversationMemoryUtilities.convertSimpleConversationMemory(conversationMemorySnapshot, includeAll);
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
    public void say(final Deployment.Environment environment,
                    final String botId, final String conversationId,
                    final String message, final AsyncResponse response) {
        RuntimeUtilities.checkNotNull(environment, "environment");
        RuntimeUtilities.checkNotNull(botId, "botId");
        RuntimeUtilities.checkNotNull(conversationId, "conversationId");
        RuntimeUtilities.checkNotNull(message, "message");

        response.setTimeout(60, TimeUnit.SECONDS);

        try {
            final IConversationMemory conversationMemory = loadConversationMemory(conversationId);
            if (conversationMemory == null) {
                String msg = "No conversation found with id: %s";
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
            final IConversation conversation = bot.continueConversation(conversationMemory,
                    conversationStep -> {
                        SimpleConversationMemorySnapshot memorySnapshot = ConversationMemoryUtilities.
                                convertSimpleConversationMemory(
                                        (ConversationMemorySnapshot) conversationMemory, true);

                        response.resume(memorySnapshot);
                    });

            if (conversation.isEnded()) {
                throw new NoLogWebApplicationException(
                        new Throwable("Conversation has ended!"), Response.Status.GONE);
            }

            Callable<Void> processUserInput =
                    processUserInput(environment, conversationId, message, conversationMemory, conversation);
            conversationCoordinator.submitInOrder(conversationId, processUserInput);
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

    private Callable<Void> processUserInput(Deployment.Environment environment,
                                            String conversationId, String message,
                                            IConversationMemory conversationMemory,
                                            IConversation conversation) {
        return () -> {
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
    }

    @Override
    public Boolean isUndoAvailable(Deployment.Environment environment, String botId, String conversationId) {
        RuntimeUtilities.checkNotNull(environment, "environment");
        RuntimeUtilities.checkNotNull(botId, "botId");
        RuntimeUtilities.checkNotNull(conversationId, "conversationId");
        final IConversationMemory conversationMemory;
        try {
            conversationMemory = loadConversationMemory(conversationId);
            return conversationMemory.isUndoAvailable();
        } catch (IResourceStore.ResourceStoreException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException();
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NoLogWebApplicationException(Response.Status.NOT_FOUND);
        }
    }

    @Override
    public Response undo(final Deployment.Environment environment, String botId, final String conversationId) {
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
    public Boolean isRedoAvailable(final Deployment.Environment environment, String botId, String conversationId) {
        RuntimeUtilities.checkNotNull(environment, "environment");
        RuntimeUtilities.checkNotNull(botId, "botId");
        RuntimeUtilities.checkNotNull(conversationId, "conversationId");
        final IConversationMemory conversationMemory;
        try {
            conversationMemory = loadConversationMemory(conversationId);
            return conversationMemory.isRedoAvailable();
        } catch (IResourceStore.ResourceStoreException e) {
            throw new NoLogWebApplicationException(Response.Status.NOT_FOUND);
        } catch (IResourceStore.ResourceNotFoundException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException();
        }
    }

    @Override
    public Response redo(final Deployment.Environment environment, String botId, final String conversationId) {
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
}
