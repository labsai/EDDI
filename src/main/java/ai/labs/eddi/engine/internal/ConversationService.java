package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.properties.IPropertiesStore;
import ai.labs.eddi.configs.properties.model.Properties;
import ai.labs.eddi.datastore.IResourceStore.ResourceNotFoundException;
import ai.labs.eddi.datastore.IResourceStore.ResourceStoreException;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.audit.AuditLedgerService;
import ai.labs.eddi.engine.tenancy.QuotaExceededException;
import ai.labs.eddi.engine.tenancy.TenantQuotaService;
import ai.labs.eddi.engine.tenancy.model.QuotaCheckResult;
import ai.labs.eddi.engine.caching.ICache;
import ai.labs.eddi.engine.caching.ICacheFactory;
import ai.labs.eddi.engine.lifecycle.ConversationEventSink;
import ai.labs.eddi.engine.lifecycle.IConversation;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.memory.ConversationLogGenerator;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.IPropertiesHandler;
import ai.labs.eddi.engine.memory.descriptor.IConversationDescriptorStore;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.model.ConversationState;
import ai.labs.eddi.model.Deployment.Environment;
import ai.labs.eddi.engine.model.InputData;
import ai.labs.eddi.engine.runtime.IBot;
import ai.labs.eddi.engine.runtime.IBotFactory;
import ai.labs.eddi.engine.runtime.IConversationCoordinator;
import ai.labs.eddi.engine.runtime.IRuntime;
import ai.labs.eddi.engine.runtime.service.ServiceException;
import ai.labs.eddi.engine.runtime.IConversationSetup;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.*;
import java.util.concurrent.*;

import static ai.labs.eddi.engine.memory.ConversationMemoryUtilities.*;
import static ai.labs.eddi.utils.RestUtilities.createURI;
import static ai.labs.eddi.utils.RuntimeUtilities.checkNotNull;
import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;
import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;
import static jakarta.ws.rs.core.MediaType.TEXT_PLAIN;

/**
 * Core conversation lifecycle service — extracted from RestBotEngine.
 * Contains all business logic for conversation management, metrics, and
 * caching.
 * No JAX-RS dependencies — results are returned as domain objects or via
 * callbacks.
 *
 * @author ginccc
 */
@ApplicationScoped
public class ConversationService implements IConversationService {

    private static final String RESOURCE_URI = "eddi://ai.labs.conversation/conversationstore/conversations/";
    private static final String CACHE_NAME_CONVERSATION_STATE = "conversationState";
    private static final String USER_ID = "userId";

    private final IBotFactory botFactory;
    private final IConversationMemoryStore conversationMemoryStore;
    private final IConversationDescriptorStore conversationDescriptorStore;
    private final IPropertiesStore propertiesStore;
    private final IConversationCoordinator conversationCoordinator;
    private final IRuntime runtime;
    private final IContextLogger contextLogger;
    private final AuditLedgerService auditLedgerService;
    private final TenantQuotaService tenantQuotaService;
    private final int botTimeout;
    private final IConversationSetup conversationSetup;
    private final ICache<String, ConversationState> conversationStateCache;

    // Metrics
    private final Timer timerConversationStart;
    private final Timer timerConversationEnd;
    private final Timer timerConversationLoad;
    private final Timer timerConversationProcessing;
    private final Timer timerConversationUndo;
    private final Timer timerConversationRedo;
    private final Counter counterConversationStart;
    private final Counter counterConversationEnd;
    private final Counter counterConversationLoad;
    private final Counter counterConversationProcessing;
    private final Counter counterConversationUndo;
    private final Counter counterConversationRedo;

    private final List<String> processingConversationReferences;

    private static final Logger LOGGER = Logger.getLogger(ConversationService.class);

    @Inject
    public ConversationService(IBotFactory botFactory,
            IConversationMemoryStore conversationMemoryStore,
            IConversationDescriptorStore conversationDescriptorStore,
            IPropertiesStore propertiesStore,
            IConversationCoordinator conversationCoordinator,
            IConversationSetup conversationSetup,
            ICacheFactory cacheFactory,
            IRuntime runtime,
            IContextLogger contextLogger,
            AuditLedgerService auditLedgerService,
            TenantQuotaService tenantQuotaService,
            MeterRegistry meterRegistry,
            @ConfigProperty(name = "systemRuntime.botTimeoutInSeconds") int botTimeout) {
        this.botFactory = botFactory;
        this.conversationMemoryStore = conversationMemoryStore;
        this.conversationDescriptorStore = conversationDescriptorStore;
        this.propertiesStore = propertiesStore;
        this.conversationCoordinator = conversationCoordinator;
        this.conversationSetup = conversationSetup;
        this.conversationStateCache = cacheFactory.getCache(CACHE_NAME_CONVERSATION_STATE);
        this.runtime = runtime;
        this.contextLogger = contextLogger;
        this.auditLedgerService = auditLedgerService;
        this.tenantQuotaService = tenantQuotaService;
        this.botTimeout = botTimeout;
        this.processingConversationReferences = new ArrayList<>();

        this.timerConversationStart = meterRegistry.timer("eddi_conversation_start_duration");
        this.timerConversationEnd = meterRegistry.timer("eddi_conversation_end_duration");
        this.timerConversationLoad = meterRegistry.timer("eddi_conversation_load_duration");
        this.timerConversationProcessing = meterRegistry.timer("eddi_conversation_processing_duration");
        this.timerConversationUndo = meterRegistry.timer("eddi_conversation_undo_duration");
        this.timerConversationRedo = meterRegistry.timer("eddi_conversation_redo_duration");

        this.counterConversationStart = meterRegistry.counter("eddi_conversation_start_count");
        this.counterConversationEnd = meterRegistry.counter("eddi_conversation_end_count");
        this.counterConversationLoad = meterRegistry.counter("eddi_conversation_load_count");
        this.counterConversationProcessing = meterRegistry.counter("eddi_conversation_processing_count");
        this.counterConversationUndo = meterRegistry.counter("eddi_conversation_undo_count");
        this.counterConversationRedo = meterRegistry.counter("eddi_conversation_redo_count");

        meterRegistry.gaugeCollectionSize("eddi_processing_conversation_count",
                Tags.empty(), processingConversationReferences);
    }

    @Override
    public ConversationResult startConversation(Environment environment, String botId,
            String userId, Map<String, Context> context)
            throws BotNotReadyException, ResourceStoreException, ResourceNotFoundException {

        long startTime = System.nanoTime();
        checkNotNull(environment, "environment");
        checkNotNull(botId, "botId");
        if (context == null) {
            context = new LinkedHashMap<>();
        }

        try {
            // Tenant quota check — conversation start
            QuotaCheckResult quotaCheck = tenantQuotaService.checkConversationQuota();
            if (!quotaCheck.allowed()) {
                throw new QuotaExceededException(quotaCheck.reason());
            }

            IBot latestBot = botFactory.getLatestReadyBot(environment, botId);
            if (latestBot == null) {
                String message = "No version of bot (botId=%s) ready for interaction (environment=%s)!";
                message = String.format(message, botId, environment);
                throw new BotNotReadyException(message);
            }

            userId = conversationSetup.computeAnonymousUserIdIfEmpty(userId, context.get(USER_ID));
            IConversation conversation = latestBot.startConversation(userId, context,
                    createPropertiesHandler(userId), null);

            var conversationMemory = conversation.getConversationMemory();
            var conversationId = storeConversationMemory(conversationMemory, environment);
            cacheConversationState(conversationId, conversationMemory.getConversationState());
            tenantQuotaService.recordConversationStart();
            var conversationUri = createURI(RESOURCE_URI, conversationId);

            conversationSetup.createConversationDescriptor(botId, latestBot, userId, conversationId, conversationUri);

            return new ConversationResult(conversationId, conversationUri);
        } catch (BotNotReadyException e) {
            throw e;
        } catch (ServiceException | InstantiationException | LifecycleException | IllegalAccessException e) {
            contextLogger.setLoggingContext(contextLogger.createLoggingContext(environment, botId, null, userId));
            LOGGER.error(e.getLocalizedMessage(), e);
            throw new ResourceStoreException(e.getLocalizedMessage(), e);
        } finally {
            recordMetrics(timerConversationStart, counterConversationStart, startTime);
        }
    }

    @Override
    public void endConversation(String conversationId) {
        long startTime = System.nanoTime();
        setConversationState(conversationId, ConversationState.ENDED);
        recordMetrics(timerConversationEnd, counterConversationEnd, startTime);
    }

    @Override
    public ConversationState getConversationState(Environment environment, String conversationId) {
        checkNotNull(environment, "environment");
        checkNotNull(conversationId, "conversationId");

        ConversationState conversationState = conversationStateCache.get(conversationId);
        if (conversationState == null) {
            conversationState = conversationMemoryStore.getConversationState(conversationId);
            cacheConversationState(conversationId, conversationState);
        }

        if (conversationState == null) {
            String message = "No conversation found! (conversationId=%s)";
            message = String.format(message, conversationId);
            throw new ConversationNotFoundException(message);
        }

        return conversationState;
    }

    @Override
    public SimpleConversationMemorySnapshot readConversation(Environment environment, String botId,
            String conversationId,
            Boolean returnDetailed,
            Boolean returnCurrentStepOnly,
            List<String> returningFields)
            throws BotMismatchException, ResourceStoreException, ResourceNotFoundException {

        long startTime = System.nanoTime();
        validateParams(environment, botId, conversationId);
        Map<String, String> loggingContext = contextLogger.createLoggingContext(environment, botId, conversationId,
                null);
        contextLogger.setLoggingContext(loggingContext);

        try {
            var conversationMemorySnapshot = conversationMemoryStore.loadConversationMemorySnapshot(conversationId);
            loggingContext.put(USER_ID, conversationMemorySnapshot.getUserId());
            contextLogger.setLoggingContext(loggingContext);

            if (!botId.equals(conversationMemorySnapshot.getBotId())) {
                String message = "conversationId: '%s' does not belong to bot with conversationId: '%s'. " +
                        "(provided botId='%s', botId in ConversationMemory='%s')";
                message = String.format(message, conversationId, botId, botId, conversationMemorySnapshot.getBotId());
                throw new BotMismatchException(message);
            }

            return convertSimpleConversationMemorySnapshot(
                    conversationMemorySnapshot,
                    returnDetailed,
                    returnCurrentStepOnly,
                    returningFields);
        } finally {
            recordMetrics(timerConversationLoad, counterConversationLoad, startTime);
        }
    }

    @Override
    public ConversationLogResult readConversationLog(String conversationId, String outputType, Integer logSize)
            throws ResourceStoreException, ResourceNotFoundException {

        var memorySnapshot = conversationMemoryStore.loadConversationMemorySnapshot(conversationId);
        var conversationLog = new ConversationLogGenerator(memorySnapshot).generate(logSize);
        outputType = outputType.toLowerCase();

        if (isNullOrEmpty(outputType) || outputType.equals("string") || outputType.equals("text")) {
            return new ConversationLogResult(conversationLog.toString(), TEXT_PLAIN);
        } else {
            return new ConversationLogResult(conversationLog.toObject(), APPLICATION_JSON);
        }
    }

    @Override
    public void say(Environment environment, String botId, String conversationId,
            Boolean returnDetailed, Boolean returnCurrentStepOnly,
            List<String> returningFields, InputData inputData,
            boolean rerunOnly, ConversationResponseHandler responseHandler)
            throws Exception {

        long startTime = System.nanoTime();
        try {
            // Tenant quota check — API call
            QuotaCheckResult quotaCheck = tenantQuotaService.checkApiCallQuota();
            if (!quotaCheck.allowed()) {
                throw new QuotaExceededException(quotaCheck.reason());
            }
            tenantQuotaService.recordApiCall();

            processingConversationReferences.add(createReferenceForMetrics(botId, conversationId));
            final IConversationMemory conversationMemory = loadConversationMemory(conversationId);
            checkConversationMemoryNotNull(conversationMemory, conversationId);
            var loggingContext = contextLogger.createLoggingContext(environment, botId,
                    conversationId, conversationMemory.getUserId());
            Integer botVersion = conversationMemory.getBotVersion();
            loggingContext.put("botVersion", botVersion.toString());
            contextLogger.setLoggingContext(loggingContext);

            if (!botId.equals(conversationMemory.getBotId())) {
                String message = "Supplied botId (%s) is incompatible with conversationId (%s)";
                message = String.format(message, botId, conversationId);
                throw new BotMismatchException(message);
            }

            IBot bot = getBot(environment, botId, botVersion);
            if (bot == null) {
                String msg = "Bot not deployed (environment=%s, conversationId=%s, version=%s)";
                msg = String.format(msg, environment, conversationMemory.getBotId(), botVersion);
                throw new BotNotReadyException(msg);
            }

            // Set the audit collector on memory (if auditing is enabled)
            if (auditLedgerService.isEnabled()) {
                String envName = environment.toString();
                conversationMemory.setAuditCollector(entry ->
                        auditLedgerService.submit(entry.withEnvironment(envName)));
            }

            final IConversation conversation = bot.continueConversation(conversationMemory,
                    createPropertiesHandler(conversationMemory.getUserId()),
                    returnConversationMemory -> {
                        SimpleConversationMemorySnapshot memorySnapshot = convertSimpleConversationMemorySnapshot(
                                returnConversationMemory,
                                returnDetailed,
                                returnCurrentStepOnly,
                                returningFields);
                        memorySnapshot.setEnvironment(environment);
                        cacheConversationState(conversationId, memorySnapshot.getConversationState());
                        conversationDescriptorStore.updateTimeStamp(conversationId);
                        recordMetrics(timerConversationProcessing, counterConversationProcessing, startTime);
                        processingConversationReferences.remove(createReferenceForMetrics(botId, conversationId));
                        responseHandler.onComplete(memorySnapshot);
                    });

            if (conversation.isEnded()) {
                throw new ConversationEndedException("Conversation has ended!");
            }

            Callable<Void> executeConversation;
            if (rerunOnly) {
                executeConversation = () -> {
                    try {
                        contextLogger.setLoggingContext(loggingContext);
                        conversation.rerun(inputData.getContext());
                    } catch (LifecycleException | IConversation.ConversationNotReadyException e) {
                        LOGGER.error(e.getLocalizedMessage(), e);
                    }
                    return null;
                };
            } else {
                executeConversation = () -> {
                    try {
                        contextLogger.setLoggingContext(loggingContext);
                        conversation.say(inputData.getInput(), inputData.getContext());
                    } catch (LifecycleException | IConversation.ConversationNotReadyException e) {
                        LOGGER.error(e.getLocalizedMessage(), e);
                    }
                    return null;
                };
            }

            Callable<Void> processUserInput = processConversationStep(environment,
                    conversationMemory,
                    conversationId,
                    loggingContext, executeConversation);

            conversationCoordinator.submitInOrder(conversationId, processUserInput);
        } catch (BotMismatchException | BotNotReadyException | ConversationEndedException e) {
            processingConversationReferences.remove(createReferenceForMetrics(botId, conversationId));
            throw e;
        } catch (Exception e) {
            LOGGER.error(e.getLocalizedMessage(), e);
            processingConversationReferences.remove(createReferenceForMetrics(botId, conversationId));
            throw e;
        }
    }

    @Override
    public void sayStreaming(Environment environment, String botId, String conversationId,
            Boolean returnDetailed, Boolean returnCurrentStepOnly,
            List<String> returningFields, InputData inputData,
            StreamingResponseHandler streamingHandler) throws Exception {

        long startTime = System.nanoTime();
        try {
            // Tenant quota check — API call (streaming)
            QuotaCheckResult quotaCheck = tenantQuotaService.checkApiCallQuota();
            if (!quotaCheck.allowed()) {
                throw new QuotaExceededException(quotaCheck.reason());
            }
            tenantQuotaService.recordApiCall();

            processingConversationReferences.add(createReferenceForMetrics(botId, conversationId));
            final IConversationMemory conversationMemory = loadConversationMemory(conversationId);
            checkConversationMemoryNotNull(conversationMemory, conversationId);
            var loggingContext = contextLogger.createLoggingContext(environment, botId,
                    conversationId, conversationMemory.getUserId());
            Integer botVersion = conversationMemory.getBotVersion();
            loggingContext.put("botVersion", botVersion.toString());
            contextLogger.setLoggingContext(loggingContext);

            if (!botId.equals(conversationMemory.getBotId())) {
                String message = "Supplied botId (%s) is incompatible with conversationId (%s)";
                message = String.format(message, botId, conversationId);
                throw new BotMismatchException(message);
            }

            IBot bot = getBot(environment, botId, botVersion);
            if (bot == null) {
                String msg = "Bot not deployed (environment=%s, conversationId=%s, version=%s)";
                msg = String.format(msg, environment, conversationMemory.getBotId(), botVersion);
                throw new BotNotReadyException(msg);
            }

            // Create event sink that delegates to the streaming handler
            var eventSink = new ConversationEventSink() {
                @Override
                public void onTaskStart(String taskId, String taskType, int index) {
                    streamingHandler.onTaskStart(taskId, taskType, index);
                }

                @Override
                public void onTaskComplete(String taskId, String taskType, long durationMs,
                        Map<String, Object> summary) {
                    streamingHandler.onTaskComplete(taskId, taskType, durationMs, summary);
                }

                @Override
                public void onToken(String token) {
                    streamingHandler.onToken(token);
                }

                @Override
                public void onComplete() {
                    // Handled separately after memory conversion
                }

                @Override
                public void onError(Throwable error) {
                    streamingHandler.onError(error);
                }
            };

            // Set the event sink on memory so LifecycleManager and tasks can use it
            conversationMemory.setEventSink(eventSink);

            // Set the audit collector on memory (if auditing is enabled)
            if (auditLedgerService.isEnabled()) {
                String envName = environment.toString();
                conversationMemory.setAuditCollector(entry ->
                        auditLedgerService.submit(entry.withEnvironment(envName)));
            }

            final IConversation conversation = bot.continueConversation(conversationMemory,
                    createPropertiesHandler(conversationMemory.getUserId()),
                    returnConversationMemory -> {
                        SimpleConversationMemorySnapshot memorySnapshot = convertSimpleConversationMemorySnapshot(
                                returnConversationMemory,
                                returnDetailed,
                                returnCurrentStepOnly,
                                returningFields);
                        memorySnapshot.setEnvironment(environment);
                        cacheConversationState(conversationId, memorySnapshot.getConversationState());
                        conversationDescriptorStore.updateTimeStamp(conversationId);
                        recordMetrics(timerConversationProcessing, counterConversationProcessing, startTime);
                        processingConversationReferences.remove(createReferenceForMetrics(botId, conversationId));
                        streamingHandler.onComplete(memorySnapshot);
                    });

            if (conversation.isEnded()) {
                throw new ConversationEndedException("Conversation has ended!");
            }

            Callable<Void> executeConversation = () -> {
                try {
                    contextLogger.setLoggingContext(loggingContext);
                    conversation.say(inputData.getInput(), inputData.getContext());
                } catch (LifecycleException | IConversation.ConversationNotReadyException e) {
                    LOGGER.error(e.getLocalizedMessage(), e);
                    streamingHandler.onError(e);
                }
                return null;
            };

            Callable<Void> processUserInput = processConversationStep(environment,
                    conversationMemory,
                    conversationId,
                    loggingContext, executeConversation);

            conversationCoordinator.submitInOrder(conversationId, processUserInput);
        } catch (BotMismatchException | BotNotReadyException | ConversationEndedException e) {
            processingConversationReferences.remove(createReferenceForMetrics(botId, conversationId));
            throw e;
        } catch (Exception e) {
            LOGGER.error(e.getLocalizedMessage(), e);
            processingConversationReferences.remove(createReferenceForMetrics(botId, conversationId));
            throw e;
        }
    }

    @Override
    public Boolean isUndoAvailable(Environment environment, String botId, String conversationId)
            throws ResourceStoreException, ResourceNotFoundException {

        validateParams(environment, botId, conversationId);
        final IConversationMemory conversationMemory = loadConversationMemory(conversationId);
        return conversationMemory.isUndoAvailable();
    }

    @Override
    public boolean undo(Environment environment, String botId, String conversationId)
            throws ResourceStoreException, ResourceNotFoundException, BotMismatchException {

        validateParams(environment, botId, conversationId);
        long startTime = System.nanoTime();
        try {
            IConversationMemory conversationMemory = loadAndValidateConversationMemory(botId, conversationId);

            if (conversationMemory.isUndoAvailable()) {
                conversationMemory.undoLastStep();
                storeConversationMemory(conversationMemory, environment);
                return true;
            } else {
                return false;
            }
        } finally {
            recordMetrics(timerConversationUndo, counterConversationUndo, startTime);
        }
    }

    @Override
    public Boolean isRedoAvailable(Environment environment, String botId, String conversationId)
            throws ResourceStoreException, ResourceNotFoundException {

        validateParams(environment, botId, conversationId);
        var conversationMemory = loadConversationMemory(conversationId);
        return conversationMemory.isRedoAvailable();
    }

    @Override
    public boolean redo(Environment environment, String botId, String conversationId)
            throws ResourceStoreException, ResourceNotFoundException, BotMismatchException {

        validateParams(environment, botId, conversationId);
        long startTime = System.nanoTime();
        try {
            IConversationMemory conversationMemory = loadAndValidateConversationMemory(botId, conversationId);

            if (conversationMemory.isRedoAvailable()) {
                conversationMemory.redoLastStep();
                storeConversationMemory(conversationMemory, environment);
                return true;
            } else {
                return false;
            }
        } finally {
            recordMetrics(timerConversationRedo, counterConversationRedo, startTime);
        }
    }

    // --- Internal helpers ---

    IPropertiesHandler createPropertiesHandler(final String userId) {
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

    private IBot getBot(Environment environment, String botId, Integer botVersion)
            throws ServiceException, IllegalAccessException {

        IBot bot = botFactory.getBot(environment, botId, botVersion);
        if (bot == null) {
            botFactory.deployBot(environment, botId, botVersion, null);
            bot = botFactory.getBot(environment, botId, botVersion);
        }

        return bot;
    }

    private Callable<Void> processConversationStep(Environment environment,
            IConversationMemory conversationMemory,
            String conversationId,
            Map<String, String> loggingContext,
            Callable<Void> executeConversation) {
        return () -> {
            waitForExecutionFinishOrTimeout(loggingContext, conversationId,
                    runtime.submitCallable(executeConversation,
                            new IRuntime.IFinishedExecution<>() {
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
                                        LOGGER.warn(errorMessage, t);
                                    } else if (t instanceof IConversation.ConversationNotReadyException) {
                                        String msg = "Conversation not ready! (conversationId=%s)";
                                        msg = String.format(msg, conversationId);
                                        contextLogger.setLoggingContext(loggingContext);
                                        LOGGER.error(msg + "\n" + t.getLocalizedMessage(), t);
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
            LOGGER.error(errorMessage, e);
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
        LOGGER.error(msg, t);
    }

    private IConversationMemory loadAndValidateConversationMemory(String botId, String conversationId)
            throws ResourceStoreException, ResourceNotFoundException, BotMismatchException {
        var conversationMemory = loadConversationMemory(conversationId);
        checkConversationMemoryNotNull(conversationMemory, conversationId);

        if (!botId.equals(conversationMemory.getBotId())) {
            throw new BotMismatchException("Supplied botId is incompatible to conversationId");
        }

        return conversationMemory;
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

    private static void checkConversationMemoryNotNull(IConversationMemory conversationMemory, String conversationId) {
        if (conversationMemory == null) {
            String message = "No conversation found with conversationId: %s";
            message = String.format(message, conversationId);
            throw new ConversationNotFoundException(message);
        }
    }

    private static void validateParams(Environment environment, String botId, String conversationId) {
        checkNotNull(environment, "environment");
        checkNotNull(botId, "botId");
        checkNotNull(conversationId, "conversationId");
    }

    private void recordMetrics(Timer timer, Counter counter, long startTime) {
        counter.increment();
        timer.record(System.nanoTime() - startTime, TimeUnit.NANOSECONDS);
    }

    private static String createReferenceForMetrics(String botId, String conversationId) {
        return botId.concat(":").concat(conversationId);
    }
}
