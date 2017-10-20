package ai.labs.core.rest.internal;

import ai.labs.lifecycle.IConversation;
import ai.labs.lifecycle.LifecycleException;
import ai.labs.lifecycle.model.Context;
import ai.labs.memory.IConversationMemory;
import ai.labs.memory.IConversationMemoryStore;
import ai.labs.memory.model.ConversationMemorySnapshot;
import ai.labs.memory.model.ConversationState;
import ai.labs.memory.model.Deployment;
import ai.labs.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.persistence.IResourceStore;
import ai.labs.rest.model.InputData;
import ai.labs.rest.rest.IRestBotEngine;
import ai.labs.runtime.IBot;
import ai.labs.runtime.IBotFactory;
import ai.labs.runtime.IConversationCoordinator;
import ai.labs.runtime.SystemRuntime;
import ai.labs.runtime.SystemRuntime.IRuntime.IFinishedExecution;
import ai.labs.runtime.service.ServiceException;
import ai.labs.utilities.RestUtilities;
import ai.labs.utilities.RuntimeUtilities;
import lombok.extern.slf4j.Slf4j;
import org.jboss.resteasy.spi.NoLogWebApplicationException;

import javax.inject.Inject;
import javax.inject.Named;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.container.AsyncResponse;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.stream.Collectors;

import static ai.labs.memory.ConversationMemoryUtilities.*;

/**
 * @author ginccc
 */
@Slf4j
public class RestBotEngine implements IRestBotEngine {
    private static final String resourceURI = "eddi://ai.labs.conversation/conversationstore/conversations/";
    private final IBotFactory botFactory;
    private final IConversationMemoryStore conversationMemoryStore;
    private final IConversationCoordinator conversationCoordinator;
    private final SystemRuntime.IRuntime runtime;
    private final int botTimeout;

    @Inject
    public RestBotEngine(IBotFactory botFactory,
                         IConversationMemoryStore conversationMemoryStore,
                         IConversationCoordinator conversationCoordinator,
                         SystemRuntime.IRuntime runtime,
                         @Named("system.botTimeoutInSeconds") int botTimeout) {
        this.botFactory = botFactory;
        this.conversationMemoryStore = conversationMemoryStore;
        this.conversationCoordinator = conversationCoordinator;
        this.runtime = runtime;
        this.botTimeout = botTimeout;
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
    public SimpleConversationMemorySnapshot readConversation(Deployment.Environment environment,
                                                             String botId,
                                                             String conversationId,
                                                             Boolean returnDetailed) {
        RuntimeUtilities.checkNotNull(environment, "environment");
        RuntimeUtilities.checkNotNull(botId, "botId");
        RuntimeUtilities.checkNotNull(conversationId, "conversationId");
        try {
            ConversationMemorySnapshot conversationMemorySnapshot =
                    conversationMemoryStore.loadConversationMemorySnapshot(conversationId);
            if (!botId.equals(conversationMemorySnapshot.getBotId())) {
                String message = "conversationId: %s does not belong to bot with id: %s";
                message = String.format(message, conversationId, botId);
                throw new IllegalAccessException(message);
            }
            return convertSimpleConversationMemory(conversationMemorySnapshot, returnDetailed);
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
                    final Boolean returnDetailed, final Boolean returnCurrentStepOnly,
                    final String message, final AsyncResponse response) {
        sayWithinContext(environment, botId, conversationId, returnDetailed, returnCurrentStepOnly,
                new InputData(message, new HashMap<>()), response);
    }

    @Override
    public void sayWithinContext(final Deployment.Environment environment,
                                 final String botId, final String conversationId,
                                 final Boolean returnDetailed, final Boolean returnCurrentStepOnly,
                                 final InputData inputData, final AsyncResponse response) {
        RuntimeUtilities.checkNotNull(environment, "environment");
        RuntimeUtilities.checkNotNull(botId, "botId");
        RuntimeUtilities.checkNotNull(conversationId, "conversationId");
        RuntimeUtilities.checkNotNull(inputData, "inputData");
        RuntimeUtilities.checkNotNull(inputData.getInput(), "inputData.input");

        response.setTimeout(60, TimeUnit.SECONDS);
        response.setTimeoutHandler((asyncResp) ->
                asyncResp.resume(Response.status(Response.Status.REQUEST_TIMEOUT).build()));
        try {
            final IConversationMemory conversationMemory = loadConversationMemory(conversationId);
            checkConversationMemoryNotNull(conversationMemory, conversationId);
            if (!botId.equals(conversationMemory.getBotId())) {
                response.resume(new IllegalAccessException("Supplied botId is incompatible to conversationId"));
                return;
            }

            IBot bot = botFactory.getBot(environment,
                    conversationMemory.getBotId(), conversationMemory.getBotVersion());
            if (bot == null) {
                String msg = "Bot not deployed (environment=%s, id=%s, version=%s)";
                msg = String.format(msg, environment, conversationMemory.getBotId(), conversationMemory.getBotVersion());
                response.resume(new NotFoundException(msg));
                return;
            }
            final IConversation conversation = bot.continueConversation(conversationMemory,
                    returnConversationMemory -> {
                        SimpleConversationMemorySnapshot memorySnapshot =
                                getSimpleConversationMemorySnapshot(returnConversationMemory,
                                        returnDetailed,
                                        returnCurrentStepOnly);
                        memorySnapshot.setEnvironment(environment);
                        response.resume(memorySnapshot);
                    });

            if (conversation.isEnded()) {
                response.resume(Response.status(Response.Status.GONE).entity("Conversation has ended!").build());
                return;
            }

            Callable<Void> processUserInput =
                    processUserInput(environment,
                            conversationId,
                            inputData.getInput(),
                            inputData.getContext(),
                            conversationMemory,
                            conversation);

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
                                            Map<String, InputData.Context> inputDataContext,
                                            IConversationMemory conversationMemory,
                                            IConversation conversation) {
        return () -> {
            waitForExecutionFinishOrTimeout(conversationId, runtime.submitCallable(() -> {
                        conversation.say(message, convertContext(inputDataContext));
                        return null;
                    },
                    new IFinishedExecution<Void>() {
                        @Override
                        public void onComplete(Void result) {
                            try {
                                storeConversationMemory(conversationMemory, environment);
                            } catch (IResourceStore.ResourceStoreException e) {
                                logConversationError(conversationId, e);
                            }
                        }

                        @Override
                        public void onFailure(Throwable t) {
                            if (t instanceof LifecycleException.LifecycleInterruptedException) {
                                String errorMessage = "Conversation processing got interrupted! (conversationId=%s)";
                                errorMessage = String.format(errorMessage, conversationId);
                                log.warn(errorMessage, t);
                            } else if (t instanceof IConversation.ConversationNotReadyException) {
                                String msg = "Conversation not ready! (conversationId=%s)";
                                msg = String.format(msg, conversationId);
                                log.error(msg + "\n" + t.getLocalizedMessage(), t);
                            } else {
                                logConversationError(conversationId, t);
                            }
                        }
                    }, null));
            return null;
        };
    }

    private void waitForExecutionFinishOrTimeout(String conversationId, Future<Void> future) {
        try {
            future.get(botTimeout, TimeUnit.SECONDS);
        } catch (TimeoutException | InterruptedException e) {
            setConversationState(conversationId, ConversationState.EXECUTION_INTERRUPTED);
            String errorMessage = "Execution of Packages interrupted or timed out.";
            log.error(errorMessage, e);
            future.cancel(true);
        } catch (ExecutionException e) {
            logConversationError(conversationId, e);
        }
    }

    private void logConversationError(String conversationId, Throwable t) {
        setConversationState(conversationId, ConversationState.ERROR);
        String msg = "Error while processing user input (conversationId=%s , conversationState=%s)";
        msg = String.format(msg, conversationId, ConversationState.ERROR);
        log.error(msg, t);
    }

    private Map<String, Context> convertContext(Map<String, InputData.Context> inputDataContext) {
        if (inputDataContext == null) {
            return new HashMap<>();
        } else {
            return inputDataContext.entrySet()
                    .stream()
                    .collect(Collectors.toMap(Map.Entry::getKey,
                            e -> {
                                InputData.Context context = e.getValue();
                                return new Context(
                                        Context.ContextType.valueOf(context.getType().toString()),
                                        context.getValue());
                            }));
        }

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
            checkConversationMemoryNotNull(conversationMemory, conversationId);

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
            checkConversationMemoryNotNull(conversationMemory, conversationId);

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

    private IConversationMemory loadConversationMemory(String conversationId)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {
        ConversationMemorySnapshot conversationMemorySnapshot =
                conversationMemoryStore.loadConversationMemorySnapshot(conversationId);
        return convertConversationMemorySnapshot(conversationMemorySnapshot);
    }

    private void setConversationState(String conversationId, ConversationState conversationState) {
        conversationMemoryStore.setConversationState(conversationId, conversationState);
    }

    private String storeConversationMemory(IConversationMemory conversationMemory, Deployment.Environment environment)
            throws IResourceStore.ResourceStoreException {
        ConversationMemorySnapshot memorySnapshot = convertConversationMemory(conversationMemory);
        memorySnapshot.setEnvironment(environment);
        return conversationMemoryStore.storeConversationMemorySnapshot(memorySnapshot);
    }

    private SimpleConversationMemorySnapshot getSimpleConversationMemorySnapshot(
            IConversationMemory returnConversationMemory,
            Boolean returnDetailed,
            Boolean returnCurrentStepOnly) {
        SimpleConversationMemorySnapshot memorySnapshot = convertSimpleConversationMemory(
                convertConversationMemory(returnConversationMemory), returnDetailed);
        if (returnCurrentStepOnly) {
            List<SimpleConversationMemorySnapshot.SimpleConversationStep> conversationSteps =
                    memorySnapshot.getConversationSteps();
            SimpleConversationMemorySnapshot.SimpleConversationStep currentConversationStep =
                    conversationSteps.get(conversationSteps.size() - 1);
            conversationSteps.clear();
            conversationSteps.add(currentConversationStep);
        }
        return memorySnapshot;
    }

    private static void checkConversationMemoryNotNull(IConversationMemory conversationMemory, String conversationId)
            throws IllegalAccessException {
        if (conversationMemory == null) {
            String message = "No conversation found with id: %s";
            message = String.format(message, conversationId);
            throw new IllegalAccessException(message);
        }
    }
}
