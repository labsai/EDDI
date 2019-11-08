package ai.labs.core.rest.internal;

import ai.labs.caching.ICache;
import ai.labs.caching.ICacheFactory;
import ai.labs.core.rest.utilities.IConversationSetup;
import ai.labs.lifecycle.IConversation;
import ai.labs.lifecycle.LifecycleException;
import ai.labs.memory.IConversationMemory;
import ai.labs.memory.IConversationMemoryStore;
import ai.labs.memory.IPropertiesHandler;
import ai.labs.memory.descriptor.IConversationDescriptorStore;
import ai.labs.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.models.Context;
import ai.labs.models.ConversationState;
import ai.labs.models.Deployment.Environment;
import ai.labs.models.InputData;
import ai.labs.resources.rest.properties.IPropertiesStore;
import ai.labs.resources.rest.properties.model.Properties;
import ai.labs.rest.restinterfaces.IRestBotEngine;
import ai.labs.runtime.IBot;
import ai.labs.runtime.IBotFactory;
import ai.labs.runtime.IConversationCoordinator;
import ai.labs.runtime.SystemRuntime;
import ai.labs.runtime.SystemRuntime.IRuntime.IFinishedExecution;
import ai.labs.runtime.service.ServiceException;
import ai.labs.utilities.RuntimeUtilities;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
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
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

import static ai.labs.memory.ConversationMemoryUtilities.*;
import static ai.labs.persistence.IResourceStore.ResourceNotFoundException;
import static ai.labs.persistence.IResourceStore.ResourceStoreException;
import static ai.labs.utilities.RestUtilities.createURI;
import static ai.labs.utilities.RuntimeUtilities.isNullOrEmpty;

/**
 * @author ginccc
 */
@Slf4j
public class RestBotEngine implements IRestBotEngine {
    private static final String resourceURI = "eddi://ai.labs.conversation/conversationstore/conversations/";
    private static final String CACHE_NAME_CONVERSATION_STATE = "conversationState";
    private static final String USER_ID = "userId";
    private final IBotFactory botFactory;
    private final IConversationMemoryStore conversationMemoryStore;
    private final IConversationDescriptorStore conversationDescriptorStore;
    private final IPropertiesStore propertiesStore;
    private final IConversationCoordinator conversationCoordinator;
    private final SystemRuntime.IRuntime runtime;
    private final IContextLogger contextLogger;
    private final int botTimeout;
    private final IConversationSetup conversationSetup;
    private final ICache<String, ConversationState> conversationStateCache;
    private final Timer timerConversationStart;
    private final Timer timerConversationRead;
    private final Timer timerConversationSay;

    @Inject
    public RestBotEngine(IBotFactory botFactory,
                         IConversationMemoryStore conversationMemoryStore,
                         IConversationDescriptorStore conversationDescriptorStore,
                         IPropertiesStore propertiesStore,
                         IConversationCoordinator conversationCoordinator,
                         IConversationSetup conversationSetup,
                         ICacheFactory cacheFactory,
                         SystemRuntime.IRuntime runtime,
                         IContextLogger contextLogger,
                         MeterRegistry meterRegistry,
                         @Named("system.botTimeoutInSeconds") int botTimeout) {
        this.botFactory = botFactory;
        this.conversationMemoryStore = conversationMemoryStore;
        this.conversationDescriptorStore = conversationDescriptorStore;
        this.propertiesStore = propertiesStore;
        this.conversationCoordinator = conversationCoordinator;
        this.conversationSetup = conversationSetup;
        this.conversationStateCache = cacheFactory.getCache(CACHE_NAME_CONVERSATION_STATE);
        this.runtime = runtime;
        this.contextLogger = contextLogger;
        this.botTimeout = botTimeout;

        this.timerConversationStart = meterRegistry.timer("conversation.start");
        this.timerConversationRead = meterRegistry.timer("conversation.load");
        this.timerConversationSay = meterRegistry.timer("conversation.processing");
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

        long startTime = System.nanoTime();

        RuntimeUtilities.checkNotNull(environment, "environment");
        RuntimeUtilities.checkNotNull(botId, "botId");
        if (context == null) {
            context = new LinkedHashMap<>();
        }

        try {
            IBot latestBot = botFactory.getLatestBot(environment, botId);
            if (latestBot == null) {
                String message = "No instance of bot (botId=%s) deployed in environment (environment=%s)!";
                message = String.format(message, botId, environment);
                return Response.status(Response.Status.NOT_FOUND).type(MediaType.TEXT_PLAIN).entity(message).build();
            }

            userId = conversationSetup.computeAnonymousUserIdIfEmpty(userId);
            IConversation conversation = latestBot.startConversation(userId, context,
                    createPropertiesHandler(userId), null);

            var conversationId = storeConversationMemory(conversation.getConversationMemory(), environment);
            cacheConversationState(conversationId, ConversationState.READY);
            var conversationUri = createURI(resourceURI, conversationId);

            URI userUri = conversationSetup.createConversationDescriptor(botId, latestBot, conversationId, conversationUri);
            conversationSetup.createPermissions(conversationId, userUri);

            return Response.created(conversationUri).build();
        } catch (ServiceException |
                ResourceStoreException |
                ResourceNotFoundException |
                InstantiationException |
                LifecycleException |
                IllegalAccessException e) {
            contextLogger.setLoggingContext(contextLogger.createLoggingContext(environment, botId, null, userId));
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);
        } finally {
            record(startTime, timerConversationStart);
        }
    }

    private IPropertiesHandler createPropertiesHandler(final String userId) {
        return new IPropertiesHandler() {
            @Override
            public Properties loadProperties() throws ResourceStoreException {
                Properties properties = null;
                if (!isNullOrEmpty(userId)) {
                    properties = propertiesStore.readProperties(userId);
                }

                if (properties == null) {
                    properties = new Properties();
                } else {
                    properties.remove("_id");
                }

                return properties;
            }

            @Override
            public void mergeProperties(Properties properties) throws ResourceStoreException {
                propertiesStore.mergeProperties(userId, properties);
            }
        };
    }

    @Override
    public Response endConversation(String conversationId) {
        setConversationState(conversationId, ConversationState.ENDED);
        return Response.ok().build();
    }

    @Override
    public SimpleConversationMemorySnapshot readConversation(Environment environment,
                                                             String botId,
                                                             String conversationId,
                                                             Boolean returnDetailed,
                                                             Boolean returnCurrentStepOnly,
                                                             List<String> returningFields) {

        long startTime = System.nanoTime();

        validateParams(environment, botId, conversationId);
        contextLogger.setLoggingContext(contextLogger.createLoggingContext(environment, botId, conversationId, null));
        try {
            var conversationMemorySnapshot = conversationMemoryStore.loadConversationMemorySnapshot(conversationId);
            if (!botId.equals(conversationMemorySnapshot.getBotId())) {
                String message = "conversationId: '%s' does not belong to bot with conversationId: '%s'. " +
                        "(provided botId='%s', botId in ConversationMemory='%s')";
                message = String.format(message, conversationId, botId, botId, conversationMemorySnapshot.getBotId());
                throw new IllegalAccessException(message);
            }
            return convertSimpleConversationMemorySnapshot(
                    conversationMemorySnapshot,
                    returnDetailed,
                    returnCurrentStepOnly,
                    returningFields);
        } catch (ResourceStoreException | IllegalAccessException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);
        } catch (ResourceNotFoundException e) {
            throw new NoLogWebApplicationException(Response.Status.NOT_FOUND);
        } finally {
            record(startTime, timerConversationRead);
        }
    }

    @Override
    public ConversationState getConversationState(Environment environment, String conversationId) {
        RuntimeUtilities.checkNotNull(environment, "environment");
        RuntimeUtilities.checkNotNull(conversationId, "conversationId");

        ConversationState conversationState = conversationStateCache.get(conversationId);
        if (conversationState == null) {
            conversationState = conversationMemoryStore.getConversationState(conversationId);
            cacheConversationState(conversationId, conversationState);
        }

        if (conversationState == null) {
            String message = "No conversation found! (conversationId=%s)";
            message = String.format(message, conversationId);
            throw new NoLogWebApplicationException(new Throwable(message), Response.Status.NOT_FOUND);
        }

        return conversationState;
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

        long startTime = System.nanoTime();

        validateParams(environment, botId, conversationId);
        RuntimeUtilities.checkNotNull(inputData, "inputData");
        RuntimeUtilities.checkNotNull(inputData.getInput(), "inputData.input");

        response.setTimeout(botTimeout, TimeUnit.SECONDS);
        response.setTimeoutHandler((asyncResp) ->
                asyncResp.resume(Response.status(Response.Status.REQUEST_TIMEOUT).build()));
        try {
            final IConversationMemory conversationMemory = loadConversationMemory(conversationId);
            checkConversationMemoryNotNull(conversationMemory, conversationId);
            var loggingContext = contextLogger.createLoggingContext(environment, botId, conversationId, conversationMemory.getUserId());
            Integer botVersion = conversationMemory.getBotVersion();
            loggingContext.put("botVersion", botVersion.toString());
            contextLogger.setLoggingContext(loggingContext);
            if (!botId.equals(conversationMemory.getBotId())) {
                String message = "Supplied botId (%s) is incompatible with conversationId (%s)";
                message = String.format(message, botId, conversationId);
                response.resume(Response.status(Response.Status.CONFLICT).type(MediaType.TEXT_PLAIN).
                        entity(message).build());
                return;
            }

            IBot bot = botFactory.getBot(environment, conversationMemory.getBotId(), botVersion);
            if (bot == null) {
                String msg = "Bot not deployed (environment=%s, conversationId=%s, version=%s)";
                msg = String.format(msg, environment, conversationMemory.getBotId(), botVersion);
                response.resume(new NotFoundException(msg));
                return;
            }

            final IConversation conversation = bot.continueConversation(conversationMemory,
                    createPropertiesHandler(conversationMemory.getUserId()),
                    returnConversationMemory -> {
                        SimpleConversationMemorySnapshot memorySnapshot =
                                convertSimpleConversationMemorySnapshot(returnConversationMemory,
                                        returnDetailed,
                                        returnCurrentStepOnly,
                                        returningFields);
                        memorySnapshot.setEnvironment(environment);
                        cacheConversationState(conversationId, memorySnapshot.getConversationState());
                        conversationDescriptorStore.updateTimeStamp(conversationId);
                        response.resume(memorySnapshot);
                        record(startTime, timerConversationSay);
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
                            conversation,
                            loggingContext);

            conversationCoordinator.submitInOrder(conversationId, processUserInput);
        } catch (InstantiationException | IllegalAccessException e) {
            String errorMsg = "Error while processing message!";
            log.error(errorMsg, e);
            throw new InternalServerErrorException(errorMsg, e);
        } catch (ResourceNotFoundException e) {
            throw new NoLogWebApplicationException(Response.Status.NOT_FOUND);
        } catch (Exception e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);
        }
    }

    private Callable<Void> processUserInput(Environment environment,
                                            String conversationId, String message,
                                            Map<String, Context> inputDataContext,
                                            IConversationMemory conversationMemory,
                                            IConversation conversation,
                                            Map<String, String> loggingContext) {
        return () -> {
            waitForExecutionFinishOrTimeout(loggingContext, conversationId, runtime.submitCallable(() -> {
                        try {
                            conversation.say(message, convertContext(inputDataContext));
                        } catch (LifecycleException | IConversation.ConversationNotReadyException e) {
                            contextLogger.setLoggingContext(loggingContext);
                            log.error(e.getLocalizedMessage(), e);
                        }
                        return null;
                    },
                    new IFinishedExecution<>() {
                        @Override
                        public void onComplete(Void result) {
                            try {
                                storeConversationMemory(conversationMemory, environment);
                            } catch (ResourceStoreException e) {
                                logConversationError(loggingContext, conversationId, e);
                            }
                        }

                        @Override
                        public void onFailure(Throwable t) {
                            if (t instanceof LifecycleException.LifecycleInterruptedException) {
                                String errorMessage = "Conversation processing got interrupted! (conversationId=%s)";
                                errorMessage = String.format(errorMessage, conversationId);
                                contextLogger.setLoggingContext(loggingContext);
                                log.warn(errorMessage, t);
                            } else if (t instanceof IConversation.ConversationNotReadyException) {
                                String msg = "Conversation not ready! (conversationId=%s)";
                                msg = String.format(msg, conversationId);
                                contextLogger.setLoggingContext(loggingContext);
                                log.error(msg + "\n" + t.getLocalizedMessage(), t);
                            } else {
                                logConversationError(loggingContext, conversationId, t);
                            }
                        }
                    }, null));
            return null;
        };
    }

    private void waitForExecutionFinishOrTimeout(Map<String, String> loggingContext,
                                                 String conversationId,
                                                 Future<Void> future) {
        try {
            future.get(botTimeout, TimeUnit.SECONDS);
        } catch (TimeoutException | InterruptedException e) {
            setConversationState(conversationId, ConversationState.EXECUTION_INTERRUPTED);
            String errorMessage = "Execution of Packages interrupted or timed out.";
            contextLogger.setLoggingContext(loggingContext);
            log.error(errorMessage, e);
            future.cancel(true);
        } catch (ExecutionException e) {
            logConversationError(loggingContext, conversationId, e);
        }
    }

    private void logConversationError(Map<String, String> loggingContext, String conversationId, Throwable t) {

        setConversationState(conversationId, ConversationState.ERROR);
        String msg = "Error while processing user input (conversationId=%s , conversationState=%s)";
        msg = String.format(msg, conversationId, ConversationState.ERROR);
        contextLogger.setLoggingContext(loggingContext);
        log.error(msg, t);
    }

    private Map<String, Context> convertContext(Map<String, Context> inputDataContext) {
        if (inputDataContext == null) {
            return new HashMap<>();
        } else {
            return inputDataContext.entrySet()
                    .stream()
                    .collect(Collectors.toMap(Map.Entry::getKey,
                            e -> {
                                Context context = e.getValue();
                                return new Context(
                                        //TODO proper error handling if null or unknown
                                        Context.ContextType.valueOf(context.getType().toString()),
                                        context.getValue());
                            }));
        }

    }

    @Override
    public Boolean isUndoAvailable(Environment environment, String botId, String conversationId) {
        validateParams(environment, botId, conversationId);
        var loggingContext = contextLogger.createLoggingContext(environment, botId, conversationId, null);
        final IConversationMemory conversationMemory;
        try {
            conversationMemory = loadConversationMemory(conversationId);
            return conversationMemory.isUndoAvailable();
        } catch (ResourceStoreException e) {
            contextLogger.setLoggingContext(loggingContext);
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException();
        } catch (ResourceNotFoundException e) {
            throw new NoLogWebApplicationException(Response.Status.NOT_FOUND);
        }
    }

    @Override
    public Response undo(final Environment environment, String botId, final String conversationId) {
        validateParams(environment, botId, conversationId);
        var loggingContext = contextLogger.createLoggingContext(environment, botId, conversationId, null);
        try {
            IConversationMemory conversationMemory = loadAndValidateConversationMemory(botId, conversationId);
            loggingContext.put(USER_ID, conversationMemory.getUserId());
            Callable<Void> processUserInput = () -> {

                try {
                    if (conversationMemory.isUndoAvailable()) {
                        conversationMemory.undoLastStep();
                        storeConversationMemory(conversationMemory, environment);
                    }
                } catch (Exception e) {
                    contextLogger.setLoggingContext(loggingContext);
                    log.error("Error while Undo!", e);
                    throw e;
                }

                return null;
            };

            SystemRuntime.getRuntime().submitCallable(processUserInput, null);

            return Response.accepted().build();
        } catch (IllegalAccessException e) {
            contextLogger.setLoggingContext(loggingContext);
            String errorMsg = "Error while processing message!";
            log.error(errorMsg, e);
            throw new InternalServerErrorException(errorMsg, e);
        } catch (ResourceNotFoundException e) {
            throw new NoLogWebApplicationException(Response.Status.NOT_FOUND);
        } catch (Exception e) {
            contextLogger.setLoggingContext(loggingContext);
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);
        }
    }

    private IConversationMemory loadAndValidateConversationMemory(String botId, String conversationId)
            throws ResourceStoreException, ResourceNotFoundException, IllegalAccessException {
        var conversationMemory = loadConversationMemory(conversationId);
        checkConversationMemoryNotNull(conversationMemory, conversationId);

        if (!botId.equals(conversationMemory.getBotId())) {
            throw new IllegalAccessException("Supplied botId is incompatible to conversationId");
        }

        return conversationMemory;
    }

    @Override
    public Boolean isRedoAvailable(final Environment environment, String botId, String conversationId) {
        validateParams(environment, botId, conversationId);
        var loggingContext = contextLogger.createLoggingContext(environment, botId, conversationId, null);

        try {
            var conversationMemory = loadConversationMemory(conversationId);
            loggingContext.put(USER_ID, conversationMemory.getUserId());
            return conversationMemory.isRedoAvailable();
        } catch (ResourceStoreException e) {
            throw new NoLogWebApplicationException(Response.Status.NOT_FOUND);
        } catch (ResourceNotFoundException e) {
            contextLogger.setLoggingContext(loggingContext);
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException();
        }
    }

    @Override
    public Response redo(final Environment environment, String botId, final String conversationId) {
        validateParams(environment, botId, conversationId);

        try {
            IConversationMemory conversationMemory = loadAndValidateConversationMemory(botId, conversationId);
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
        } catch (ResourceNotFoundException e) {
            throw new NoLogWebApplicationException(Response.Status.NOT_FOUND);
        } catch (Exception e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);
        }
    }

    private static void validateParams(Environment environment, String botId, String conversationId) {
        RuntimeUtilities.checkNotNull(environment, "environment");
        RuntimeUtilities.checkNotNull(botId, "botId");
        RuntimeUtilities.checkNotNull(conversationId, "conversationId");
    }

    private IConversationMemory loadConversationMemory(String conversationId)
            throws ResourceStoreException, ResourceNotFoundException {
        var conversationMemorySnapshot = conversationMemoryStore.loadConversationMemorySnapshot(conversationId);
        return convertConversationMemorySnapshot(conversationMemorySnapshot);
    }

    private void setConversationState(String conversationId, ConversationState conversationState) {
        conversationMemoryStore.setConversationState(conversationId, conversationState);
        cacheConversationState(conversationId, conversationState);
    }

    private void cacheConversationState(String conversationId, ConversationState conversationState) {
        conversationStateCache.put(conversationId, conversationState);
    }

    private String storeConversationMemory(IConversationMemory conversationMemory, Environment environment)
            throws ResourceStoreException {
        var memorySnapshot = convertConversationMemory(conversationMemory);
        memorySnapshot.setEnvironment(environment);
        return conversationMemoryStore.storeConversationMemorySnapshot(memorySnapshot);
    }

    private static void checkConversationMemoryNotNull(IConversationMemory conversationMemory, String conversationId)
            throws IllegalAccessException {
        if (conversationMemory == null) {
            String message = "No conversation found with conversationId: %s";
            message = String.format(message, conversationId);
            throw new IllegalAccessException(message);
        }
    }

    private static void record(long startTime, Timer timer) {
        timer.record(System.nanoTime() - startTime, TimeUnit.NANOSECONDS);
    }
}
