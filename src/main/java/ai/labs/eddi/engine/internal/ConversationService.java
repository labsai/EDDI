package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.properties.IUserMemoryStore;
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
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.engine.model.InputData;
import ai.labs.eddi.engine.runtime.IAgent;
import ai.labs.eddi.engine.runtime.IAgentFactory;
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
 * Core conversation lifecycle service — extracted from RestAgentEngine.
 * Contains all business logic for conversation management, metrics, and
 * caching. No JAX-RS dependencies — results are returned as domain objects or
 * via callbacks.
 *
 * @author ginccc
 */
@ApplicationScoped
public class ConversationService implements IConversationService {

    private static final String RESOURCE_URI = "eddi://ai.labs.conversation/conversationstore/conversations/";
    private static final String CACHE_NAME_CONVERSATION_STATE = "conversationState";
    private static final String USER_ID = "userId";

    private final IAgentFactory agentFactory;
    private final IConversationMemoryStore conversationMemoryStore;
    private final IConversationDescriptorStore conversationDescriptorStore;
    private final IUserMemoryStore userMemoryStore;
    private final IConversationCoordinator conversationCoordinator;
    private final IRuntime runtime;
    private final IContextLogger contextLogger;
    private final AuditLedgerService auditLedgerService;
    private final TenantQuotaService tenantQuotaService;
    private final int agentTimeout;
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
    public ConversationService(IAgentFactory agentFactory, IConversationMemoryStore conversationMemoryStore,
            IConversationDescriptorStore conversationDescriptorStore, IUserMemoryStore userMemoryStore,
            IConversationCoordinator conversationCoordinator, IConversationSetup conversationSetup, ICacheFactory cacheFactory, IRuntime runtime,
            IContextLogger contextLogger, AuditLedgerService auditLedgerService, TenantQuotaService tenantQuotaService, MeterRegistry meterRegistry,
            @ConfigProperty(name = "systemRuntime.agentTimeoutInSeconds") int agentTimeout) {
        this.agentFactory = agentFactory;
        this.conversationMemoryStore = conversationMemoryStore;
        this.conversationDescriptorStore = conversationDescriptorStore;
        this.userMemoryStore = userMemoryStore;
        this.conversationCoordinator = conversationCoordinator;
        this.conversationSetup = conversationSetup;
        this.conversationStateCache = cacheFactory.getCache(CACHE_NAME_CONVERSATION_STATE);
        this.runtime = runtime;
        this.contextLogger = contextLogger;
        this.auditLedgerService = auditLedgerService;
        this.tenantQuotaService = tenantQuotaService;
        this.agentTimeout = agentTimeout;
        this.processingConversationReferences = new CopyOnWriteArrayList<>();

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

        meterRegistry.gaugeCollectionSize("eddi_processing_conversation_count", Tags.empty(), processingConversationReferences);
    }

    @Override
    public ConversationResult startConversation(Environment environment, String agentId, String userId, Map<String, Context> context)
            throws AgentNotReadyException, ResourceStoreException, ResourceNotFoundException {

        long startTime = System.nanoTime();
        checkNotNull(environment, "environment");
        checkNotNull(agentId, "agentId");
        if (context == null) {
            context = new LinkedHashMap<>();
        }

        try {
            // Tenant quota check — conversation start
            QuotaCheckResult quotaCheck = tenantQuotaService.checkConversationQuota();
            if (!quotaCheck.allowed()) {
                throw new QuotaExceededException(quotaCheck.reason());
            }

            IAgent latestAgent = agentFactory.getLatestReadyAgent(environment, agentId);
            if (latestAgent == null) {
                String message = "No version of agent (agentId=%s) ready for interaction (environment=%s)!";
                message = String.format(message, agentId, environment);
                throw new AgentNotReadyException(message);
            }

            userId = conversationSetup.computeAnonymousUserIdIfEmpty(userId, context.get(USER_ID));
            IConversation conversation = latestAgent.startConversation(userId, context,
                    createPropertiesHandler(userId, latestAgent.getUserMemoryConfig()), null);

            var conversationMemory = conversation.getConversationMemory();
            var conversationId = storeConversationMemory(conversationMemory, environment);
            cacheConversationState(conversationId, conversationMemory.getConversationState());
            tenantQuotaService.recordConversationStart();
            var conversationUri = createURI(RESOURCE_URI, conversationId);

            conversationSetup.createConversationDescriptor(agentId, latestAgent, userId, conversationId, conversationUri);

            return new ConversationResult(conversationId, conversationUri);
        } catch (AgentNotReadyException e) {
            throw e;
        } catch (ServiceException | InstantiationException | LifecycleException | IllegalAccessException e) {
            contextLogger.setLoggingContext(contextLogger.createLoggingContext(environment, agentId, null, userId));
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
    public SimpleConversationMemorySnapshot readConversation(Environment environment, String agentId, String conversationId, Boolean returnDetailed,
                                                             Boolean returnCurrentStepOnly, List<String> returningFields)
            throws AgentMismatchException, ResourceStoreException, ResourceNotFoundException {

        long startTime = System.nanoTime();
        validateParams(environment, agentId, conversationId);
        Map<String, String> loggingContext = contextLogger.createLoggingContext(environment, agentId, conversationId, null);
        contextLogger.setLoggingContext(loggingContext);

        try {
            var conversationMemorySnapshot = conversationMemoryStore.loadConversationMemorySnapshot(conversationId);
            loggingContext.put(USER_ID, conversationMemorySnapshot.getUserId());
            contextLogger.setLoggingContext(loggingContext);

            if (!agentId.equals(conversationMemorySnapshot.getAgentId())) {
                String message = "conversationId: '%s' does not belong to Agent with conversationId: '%s'. "
                        + "(provided agentId='%s', agentId in ConversationMemory='%s')";
                message = String.format(message, conversationId, agentId, agentId, conversationMemorySnapshot.getAgentId());
                throw new AgentMismatchException(message);
            }

            return convertSimpleConversationMemorySnapshot(conversationMemorySnapshot, returnDetailed, returnCurrentStepOnly, returningFields);
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
    public void say(Environment environment, String agentId, String conversationId, Boolean returnDetailed, Boolean returnCurrentStepOnly,
                    List<String> returningFields, InputData inputData, boolean rerunOnly, ConversationResponseHandler responseHandler)
            throws Exception {

        long startTime = System.nanoTime();
        try {
            // Tenant quota check — API call
            QuotaCheckResult quotaCheck = tenantQuotaService.checkApiCallQuota();
            if (!quotaCheck.allowed()) {
                throw new QuotaExceededException(quotaCheck.reason());
            }
            tenantQuotaService.recordApiCall();

            processingConversationReferences.add(createReferenceForMetrics(agentId, conversationId));
            final IConversationMemory conversationMemory = loadConversationMemory(conversationId);
            checkConversationMemoryNotNull(conversationMemory, conversationId);
            var loggingContext = contextLogger.createLoggingContext(environment, agentId, conversationId, conversationMemory.getUserId());
            Integer agentVersion = conversationMemory.getAgentVersion();
            loggingContext.put("agentVersion", agentVersion.toString());
            contextLogger.setLoggingContext(loggingContext);

            if (!agentId.equals(conversationMemory.getAgentId())) {
                String message = "Supplied agentId (%s) is incompatible with conversationId (%s)";
                message = String.format(message, agentId, conversationId);
                throw new AgentMismatchException(message);
            }

            IAgent agent = getAgent(environment, agentId, agentVersion);
            if (agent == null) {
                String msg = "Agent not deployed (environment=%s, conversationId=%s, version=%s)";
                msg = String.format(msg, environment, conversationMemory.getAgentId(), agentVersion);
                throw new AgentNotReadyException(msg);
            }

            // Set the audit collector on memory (if auditing is enabled)
            if (auditLedgerService.isEnabled()) {
                String envName = environment.toString();
                conversationMemory.setAuditCollector(entry -> auditLedgerService.submit(entry.withEnvironment(envName)));
            }

            final IConversation conversation = agent.continueConversation(conversationMemory,
                    createPropertiesHandler(conversationMemory.getUserId(), agent.getUserMemoryConfig()), returnConversationMemory -> {
                        SimpleConversationMemorySnapshot memorySnapshot = convertSimpleConversationMemorySnapshot(returnConversationMemory,
                                returnDetailed, returnCurrentStepOnly, returningFields);
                        memorySnapshot.setEnvironment(environment);
                        cacheConversationState(conversationId, memorySnapshot.getConversationState());
                        conversationDescriptorStore.updateTimeStamp(conversationId);
                        recordMetrics(timerConversationProcessing, counterConversationProcessing, startTime);
                        processingConversationReferences.remove(createReferenceForMetrics(agentId, conversationId));
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

            Callable<Void> processUserInput = processConversationStep(environment, conversationMemory, conversationId, loggingContext,
                    executeConversation);

            conversationCoordinator.submitInOrder(conversationId, processUserInput);
        } catch (AgentMismatchException | AgentNotReadyException | ConversationEndedException e) {
            processingConversationReferences.remove(createReferenceForMetrics(agentId, conversationId));
            throw e;
        } catch (Exception e) {
            LOGGER.error(e.getLocalizedMessage(), e);
            processingConversationReferences.remove(createReferenceForMetrics(agentId, conversationId));
            throw e;
        }
    }

    @Override
    public void sayStreaming(Environment environment, String agentId, String conversationId, Boolean returnDetailed, Boolean returnCurrentStepOnly,
                             List<String> returningFields, InputData inputData, StreamingResponseHandler streamingHandler)
            throws Exception {

        long startTime = System.nanoTime();
        try {
            // Tenant quota check — API call (streaming)
            QuotaCheckResult quotaCheck = tenantQuotaService.checkApiCallQuota();
            if (!quotaCheck.allowed()) {
                throw new QuotaExceededException(quotaCheck.reason());
            }
            tenantQuotaService.recordApiCall();

            processingConversationReferences.add(createReferenceForMetrics(agentId, conversationId));
            final IConversationMemory conversationMemory = loadConversationMemory(conversationId);
            checkConversationMemoryNotNull(conversationMemory, conversationId);
            var loggingContext = contextLogger.createLoggingContext(environment, agentId, conversationId, conversationMemory.getUserId());
            Integer agentVersion = conversationMemory.getAgentVersion();
            loggingContext.put("agentVersion", agentVersion.toString());
            contextLogger.setLoggingContext(loggingContext);

            if (!agentId.equals(conversationMemory.getAgentId())) {
                String message = "Supplied agentId (%s) is incompatible with conversationId (%s)";
                message = String.format(message, agentId, conversationId);
                throw new AgentMismatchException(message);
            }

            IAgent agent = getAgent(environment, agentId, agentVersion);
            if (agent == null) {
                String msg = "Agent not deployed (environment=%s, conversationId=%s, version=%s)";
                msg = String.format(msg, environment, conversationMemory.getAgentId(), agentVersion);
                throw new AgentNotReadyException(msg);
            }

            // Create event sink that delegates to the streaming handler
            var eventSink = new ConversationEventSink() {
                @Override
                public void onTaskStart(String taskId, String taskType, int index) {
                    streamingHandler.onTaskStart(taskId, taskType, index);
                }

                @Override
                public void onTaskComplete(String taskId, String taskType, long durationMs, Map<String, Object> summary) {
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
                conversationMemory.setAuditCollector(entry -> auditLedgerService.submit(entry.withEnvironment(envName)));
            }

            final IConversation conversation = agent.continueConversation(conversationMemory,
                    createPropertiesHandler(conversationMemory.getUserId(), agent.getUserMemoryConfig()), returnConversationMemory -> {
                        SimpleConversationMemorySnapshot memorySnapshot = convertSimpleConversationMemorySnapshot(returnConversationMemory,
                                returnDetailed, returnCurrentStepOnly, returningFields);
                        memorySnapshot.setEnvironment(environment);
                        cacheConversationState(conversationId, memorySnapshot.getConversationState());
                        conversationDescriptorStore.updateTimeStamp(conversationId);
                        recordMetrics(timerConversationProcessing, counterConversationProcessing, startTime);
                        processingConversationReferences.remove(createReferenceForMetrics(agentId, conversationId));
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

            Callable<Void> processUserInput = processConversationStep(environment, conversationMemory, conversationId, loggingContext,
                    executeConversation);

            conversationCoordinator.submitInOrder(conversationId, processUserInput);
        } catch (AgentMismatchException | AgentNotReadyException | ConversationEndedException e) {
            processingConversationReferences.remove(createReferenceForMetrics(agentId, conversationId));
            throw e;
        } catch (Exception e) {
            LOGGER.error(e.getLocalizedMessage(), e);
            processingConversationReferences.remove(createReferenceForMetrics(agentId, conversationId));
            throw e;
        }
    }

    @Override
    public Boolean isUndoAvailable(Environment environment, String agentId, String conversationId)
            throws ResourceStoreException, ResourceNotFoundException {

        validateParams(environment, agentId, conversationId);
        final IConversationMemory conversationMemory = loadConversationMemory(conversationId);
        return conversationMemory.isUndoAvailable();
    }

    @Override
    public boolean undo(Environment environment, String agentId, String conversationId)
            throws ResourceStoreException, ResourceNotFoundException, AgentMismatchException {

        validateParams(environment, agentId, conversationId);
        long startTime = System.nanoTime();
        try {
            IConversationMemory conversationMemory = loadAndValidateConversationMemory(agentId, conversationId);

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
    public Boolean isRedoAvailable(Environment environment, String agentId, String conversationId)
            throws ResourceStoreException, ResourceNotFoundException {

        validateParams(environment, agentId, conversationId);
        var conversationMemory = loadConversationMemory(conversationId);
        return conversationMemory.isRedoAvailable();
    }

    @Override
    public boolean redo(Environment environment, String agentId, String conversationId)
            throws ResourceStoreException, ResourceNotFoundException, AgentMismatchException {

        validateParams(environment, agentId, conversationId);
        long startTime = System.nanoTime();
        try {
            IConversationMemory conversationMemory = loadAndValidateConversationMemory(agentId, conversationId);

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

    // --- Conversation-only overloads (resolve agentId + environment from stored
    // record) ---

    @Override
    public SimpleConversationMemorySnapshot readConversation(String conversationId, Boolean returnDetailed, Boolean returnCurrentStepOnly,
                                                             List<String> returningFields)
            throws ResourceStoreException, ResourceNotFoundException {

        long startTime = System.nanoTime();
        checkNotNull(conversationId, "conversationId");

        try {
            var snapshot = conversationMemoryStore.loadConversationMemorySnapshot(conversationId);
            var loggingContext = contextLogger.createLoggingContext(snapshot.getEnvironment(), snapshot.getAgentId(), conversationId,
                    snapshot.getUserId());
            contextLogger.setLoggingContext(loggingContext);

            return convertSimpleConversationMemorySnapshot(snapshot, returnDetailed, returnCurrentStepOnly, returningFields);
        } finally {
            recordMetrics(timerConversationLoad, counterConversationLoad, startTime);
        }
    }

    @Override
    public ConversationState getConversationState(String conversationId) {
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
    public void say(String conversationId, Boolean returnDetailed, Boolean returnCurrentStepOnly, List<String> returningFields, InputData inputData,
                    boolean rerunOnly, ConversationResponseHandler responseHandler)
            throws Exception {

        var snapshot = conversationMemoryStore.loadConversationMemorySnapshot(conversationId);
        say(snapshot.getEnvironment(), snapshot.getAgentId(), conversationId, returnDetailed, returnCurrentStepOnly, returningFields, inputData,
                rerunOnly, responseHandler);
    }

    @Override
    public void sayStreaming(String conversationId, Boolean returnDetailed, Boolean returnCurrentStepOnly, List<String> returningFields,
                             InputData inputData, StreamingResponseHandler streamingHandler)
            throws Exception {

        var snapshot = conversationMemoryStore.loadConversationMemorySnapshot(conversationId);
        sayStreaming(snapshot.getEnvironment(), snapshot.getAgentId(), conversationId, returnDetailed, returnCurrentStepOnly, returningFields,
                inputData, streamingHandler);
    }

    @Override
    public Boolean isUndoAvailable(String conversationId) throws ResourceStoreException, ResourceNotFoundException {
        var snapshot = conversationMemoryStore.loadConversationMemorySnapshot(conversationId);
        return isUndoAvailable(snapshot.getEnvironment(), snapshot.getAgentId(), conversationId);
    }

    @Override
    public boolean undo(String conversationId) throws ResourceStoreException, ResourceNotFoundException {
        var snapshot = conversationMemoryStore.loadConversationMemorySnapshot(conversationId);
        try {
            return undo(snapshot.getEnvironment(), snapshot.getAgentId(), conversationId);
        } catch (AgentMismatchException e) {
            // Cannot happen when agentId comes from the stored snapshot
            throw new ResourceStoreException("Unexpected agent mismatch", e);
        }
    }

    @Override
    public Boolean isRedoAvailable(String conversationId) throws ResourceStoreException, ResourceNotFoundException {
        var snapshot = conversationMemoryStore.loadConversationMemorySnapshot(conversationId);
        return isRedoAvailable(snapshot.getEnvironment(), snapshot.getAgentId(), conversationId);
    }

    @Override
    public boolean redo(String conversationId) throws ResourceStoreException, ResourceNotFoundException {
        var snapshot = conversationMemoryStore.loadConversationMemorySnapshot(conversationId);
        try {
            return redo(snapshot.getEnvironment(), snapshot.getAgentId(), conversationId);
        } catch (AgentMismatchException e) {
            // Cannot happen when agentId comes from the stored snapshot
            throw new ResourceStoreException("Unexpected agent mismatch", e);
        }
    }

    // --- Internal helpers ---

    IPropertiesHandler createPropertiesHandler(final String userId, final AgentConfiguration.UserMemoryConfig memoryConfig) {
        return new IPropertiesHandler() {
            @Override
            public IUserMemoryStore getUserMemoryStore() {
                return userMemoryStore;
            }

            @Override
            public AgentConfiguration.UserMemoryConfig getUserMemoryConfig() {
                return memoryConfig;
            }

            @Override
            public String getUserId() {
                return userId;
            }
        };
    }

    private IAgent getAgent(Environment environment, String agentId, Integer agentVersion) throws ServiceException, IllegalAccessException {

        IAgent agent = agentFactory.getAgent(environment, agentId, agentVersion);
        if (agent == null) {
            agentFactory.deployAgent(environment, agentId, agentVersion, null);
            agent = agentFactory.getAgent(environment, agentId, agentVersion);
        }

        return agent;
    }

    private Callable<Void> processConversationStep(Environment environment, IConversationMemory conversationMemory, String conversationId,
                                                   Map<String, String> loggingContext, Callable<Void> executeConversation) {
        return () -> {
            waitForExecutionFinishOrTimeout(loggingContext, conversationId,
                    runtime.submitCallable(executeConversation, new IRuntime.IFinishedExecution<>() {
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

    private void waitForExecutionFinishOrTimeout(Map<String, String> loggingContext, String conversationId, Future<Void> future) {
        try {
            future.get(agentTimeout, TimeUnit.SECONDS);
        } catch (TimeoutException | InterruptedException e) {
            setConversationState(conversationId, ConversationState.EXECUTION_INTERRUPTED);
            String errorMessage = "Execution of Workflows interrupted or timed out.";
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

    private IConversationMemory loadAndValidateConversationMemory(String agentId, String conversationId)
            throws ResourceStoreException, ResourceNotFoundException, AgentMismatchException {
        var conversationMemory = loadConversationMemory(conversationId);
        checkConversationMemoryNotNull(conversationMemory, conversationId);

        if (!agentId.equals(conversationMemory.getAgentId())) {
            throw new AgentMismatchException("Supplied agentId is incompatible to conversationId");
        }

        return conversationMemory;
    }

    private IConversationMemory loadConversationMemory(String conversationId) throws ResourceStoreException, ResourceNotFoundException {
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

    private String storeConversationMemory(IConversationMemory conversationMemory, Environment environment) throws ResourceStoreException {
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

    private static void validateParams(Environment environment, String agentId, String conversationId) {
        checkNotNull(environment, "environment");
        checkNotNull(agentId, "agentId");
        checkNotNull(conversationId, "conversationId");
    }

    private void recordMetrics(Timer timer, Counter counter, long startTime) {
        counter.increment();
        timer.record(System.nanoTime() - startTime, TimeUnit.NANOSECONDS);
    }

    private static String createReferenceForMetrics(String agentId, String conversationId) {
        return agentId.concat(":").concat(conversationId);
    }
}
