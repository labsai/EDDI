/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.hitl.HitlTimeoutPolicy;
import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.IResourceStore.ResourceNotFoundException;
import ai.labs.eddi.datastore.IResourceStore.ResourceStoreException;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.audit.AuditLedgerService;
import ai.labs.eddi.engine.events.HitlResumeCompletedEvent;
import ai.labs.eddi.engine.gdpr.GdprComplianceService;
import ai.labs.eddi.engine.gdpr.ProcessingRestrictedException;
import ai.labs.eddi.engine.tenancy.QuotaExceededException;
import ai.labs.eddi.engine.tenancy.TenantQuotaService;
import ai.labs.eddi.engine.tenancy.model.QuotaCheckResult;
import ai.labs.eddi.engine.caching.ICache;
import ai.labs.eddi.engine.caching.ICacheFactory;
import ai.labs.eddi.engine.lifecycle.ConversationEventSink;
import ai.labs.eddi.engine.lifecycle.IConversation;
import ai.labs.eddi.engine.lifecycle.TaskId;
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
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

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
    private final GdprComplianceService gdprComplianceService;
    private final TenantQuotaService tenantQuotaService;
    private final int agentTimeout;
    private final IConversationSetup conversationSetup;
    private final IScheduleStore scheduleStore;
    private final IAgentStore agentStore;
    private final ICache<String, ConversationState> conversationStateCache;

    /**
     * Fires {@link HitlResumeCompletedEvent} when a resume settles to a non-paused
     * state. Async so a slow channel observer never blocks the engine; observer
     * failures are isolated from the resume. Delivery adapters (Slack, …) observe
     * this event to push the outcome to the originating surface.
     */
    private final Event<HitlResumeCompletedEvent> hitlResumeCompletedEvent;

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
    private final Counter counterHitlPause;
    private final Counter counterHitlResume;

    private final List<String> processingConversationReferences;

    /**
     * Live memories of conversations currently executing on THIS pod, keyed by
     * conversationId. Lets {@link #cancelConversation} set the cooperative
     * {@code cancelled} flag that {@code LifecycleManager} checks at task
     * boundaries. Cross-pod cancellation of an actively-executing turn is not
     * supported — the DB CAS handles the paused/persisted states.
     */
    private final ConcurrentHashMap<String, IConversationMemory> inFlightConversations = new ConcurrentHashMap<>();

    private static final Logger LOGGER = Logger.getLogger(ConversationService.class);

    @Inject
    public ConversationService(IAgentFactory agentFactory, IConversationMemoryStore conversationMemoryStore,
            IConversationDescriptorStore conversationDescriptorStore, IUserMemoryStore userMemoryStore,
            IConversationCoordinator conversationCoordinator, IConversationSetup conversationSetup, ICacheFactory cacheFactory, IRuntime runtime,
            IContextLogger contextLogger, AuditLedgerService auditLedgerService, GdprComplianceService gdprComplianceService,
            TenantQuotaService tenantQuotaService, IScheduleStore scheduleStore, IAgentStore agentStore,
            MeterRegistry meterRegistry, Event<HitlResumeCompletedEvent> hitlResumeCompletedEvent,
            @ConfigProperty(name = "systemRuntime.agentTimeoutInSeconds") int agentTimeout) {
        this.agentFactory = agentFactory;
        this.conversationMemoryStore = conversationMemoryStore;
        this.conversationDescriptorStore = conversationDescriptorStore;
        this.userMemoryStore = userMemoryStore;
        this.conversationCoordinator = conversationCoordinator;
        this.conversationSetup = conversationSetup;
        this.scheduleStore = scheduleStore;
        this.agentStore = agentStore;
        this.conversationStateCache = cacheFactory.getCache(CACHE_NAME_CONVERSATION_STATE);
        this.runtime = runtime;
        this.contextLogger = contextLogger;
        this.auditLedgerService = auditLedgerService;
        this.gdprComplianceService = gdprComplianceService;
        this.tenantQuotaService = tenantQuotaService;
        this.agentTimeout = agentTimeout;
        this.hitlResumeCompletedEvent = hitlResumeCompletedEvent;
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
        this.counterHitlPause = meterRegistry.counter("eddi_hitl_pause_count", "surface", "regular");
        this.counterHitlResume = meterRegistry.counter("eddi_hitl_resume_count", "surface", "regular");
        // (timeout fires are counted in HitlTimeoutHandler, tagged by surface)

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
            // GDPR Art. 18 — processing restriction check
            userId = conversationSetup.computeAnonymousUserIdIfEmpty(userId, context.get(USER_ID));
            if (userId != null && gdprComplianceService.isProcessingRestricted(userId)) {
                throw new ProcessingRestrictedException(
                        "Processing is restricted for this user (GDPR Art. 18)");
            }

            IAgent latestAgent = agentFactory.getLatestReadyAgent(environment, agentId);
            if (latestAgent == null) {
                String message = "No version of agent (agentId=%s) ready for interaction (environment=%s)!";
                message = String.format(message, agentId, environment);
                throw new AgentNotReadyException(message);
            }

            // Tenant quota — atomic slot acquisition AFTER cheap validations
            // (avoids burning quota on GDPR-restricted or agent-not-ready failures)
            QuotaCheckResult quotaCheck = tenantQuotaService.acquireConversationSlot();
            if (!quotaCheck.allowed()) {
                throw new QuotaExceededException(quotaCheck.reason());
            }

            IConversation conversation = latestAgent.startConversation(userId, context,
                    createPropertiesHandler(userId, latestAgent.getUserMemoryConfig()), null);

            var conversationMemory = conversation.getConversationMemory();
            // A behavior rule may pause on the CONVERSATION_START turn — this path
            // needs the same HITL bookkeeping as the say path (bookmark BEFORE the
            // store, then counter + timeout schedule) or a finite timeout policy
            // silently degrades to wait-forever for init pauses.
            if (conversationMemory.getConversationState() == ConversationState.AWAITING_HUMAN) {
                populateHitlTimeoutBookmark(conversationMemory);
            }
            var conversationId = storeConversationMemory(conversationMemory, environment);
            cacheConversationState(conversationId, conversationMemory.getConversationState());
            if (conversationMemory.getConversationState() == ConversationState.AWAITING_HUMAN) {
                counterHitlPause.increment();
                scheduleHitlTimeout(conversationId, conversationMemory);
            }
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
        // Signal any in-flight resume on this pod (mirrors cancelConversation): a
        // resume that already passed the AWAITING_HUMAN->IN_PROGRESS CAS would
        // otherwise finish and persist its snapshot back over the terminal ENDED
        // state. Setting the cooperative-cancel flag makes the resume's onComplete
        // skip persistence, so ENDED wins.
        var inFlightMemory = inFlightConversations.get(conversationId);
        if (inFlightMemory != null) {
            inFlightMemory.setCancelled(true);
            LOGGER.infof("Signalled in-flight resume to abort — conversation %s is being ended", conversationId);
        }
        // Ending a PAUSED conversation terminally resolves its pending approval:
        // disarm the timeout schedule (a stale fire would log spurious errors and
        // leave a dead schedule row forever) and clear the persisted bookmark.
        ConversationState previousState = conversationMemoryStore.getConversationState(conversationId);
        setConversationState(conversationId, ConversationState.ENDED);
        if (previousState == ConversationState.AWAITING_HUMAN) {
            deleteHitlTimeoutSchedule(conversationId);
            try {
                conversationMemoryStore.clearHitlBookmark(conversationId);
            } catch (Exception e) {
                LOGGER.warnf("Failed to clear HITL bookmark while ending %s: %s", conversationId, e.getMessage());
            }
            LOGGER.warnf("Conversation %s was ended while awaiting human approval — the pending approval is terminated", conversationId);
        }
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
        var conversationLog = new ConversationLogGenerator(memorySnapshot).generate(logSize != null ? logSize : -1);
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
            final IConversationMemory conversationMemory = loadConversationMemory(conversationId);
            checkConversationMemoryNotNull(conversationMemory, conversationId);

            // GDPR Art. 18 — processing restriction check
            if (conversationMemory.getUserId() != null
                    && gdprComplianceService.isProcessingRestricted(conversationMemory.getUserId())) {
                throw new ProcessingRestrictedException(
                        "Processing is restricted for this user (GDPR Art. 18)");
            }

            var loggingContext = contextLogger.createLoggingContext(environment, agentId, conversationId, conversationMemory.getUserId());
            Integer agentVersion = conversationMemory.getAgentVersion();
            loggingContext.put("agentVersion", agentVersion.toString());
            contextLogger.setLoggingContext(loggingContext);

            if (!agentId.equals(conversationMemory.getAgentId())) {
                String message = "Supplied agentId (%s) is incompatible with conversationId (%s)";
                message = String.format(message, agentId, conversationId);
                throw new AgentMismatchException(message);
            }

            // HITL fast-fail: a paused conversation cannot consume input — reject
            // promptly (REST: 409) instead of dropping the turn into the 60s
            // watchdog. Checked BEFORE quota/reference bookkeeping so nothing
            // leaks. The queued-say guard below remains as the race backstop.
            if (conversationMemory.getConversationState() == ConversationState.AWAITING_HUMAN) {
                throw new ConversationAwaitingApprovalException(
                        "Conversation is awaiting human approval — a reviewer must resolve it via"
                                + " POST /agents/" + conversationId + "/resume (or cancel) before new input is accepted");
            }

            IAgent agent = getAgent(environment, agentId, agentVersion);
            if (agent == null) {
                String msg = "Agent not deployed (environment=%s, conversationId=%s, version=%s)";
                msg = String.format(msg, environment, conversationMemory.getAgentId(), agentVersion);
                throw new AgentNotReadyException(msg);
            }

            // Tenant quota — atomic slot acquisition AFTER cheap validations
            // (avoids burning quota on not-found, GDPR-restricted, agent-mismatch, or
            // agent-not-ready failures)
            QuotaCheckResult quotaCheck = tenantQuotaService.acquireApiCallSlot();
            if (!quotaCheck.allowed()) {
                throw new QuotaExceededException(quotaCheck.reason());
            }

            processingConversationReferences.add(createReferenceForMetrics(agentId, conversationId));

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

            // Handler contract: a skipped turn (pause/busy committed by the time the
            // queued turn executed) must still complete the response — with the
            // persisted state and WITHOUT the metrics reference leaking.
            Consumer<IConversationMemory> notifySkipped = skippedMemory -> {
                SimpleConversationMemorySnapshot memorySnapshot = convertSimpleConversationMemorySnapshot(skippedMemory,
                        returnDetailed, returnCurrentStepOnly, returningFields);
                memorySnapshot.setEnvironment(environment);
                recordMetrics(timerConversationProcessing, counterConversationProcessing, startTime);
                processingConversationReferences.remove(createReferenceForMetrics(agentId, conversationId));
                responseHandler.onSkipped(memorySnapshot);
            };

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
                    executeConversation, notifySkipped);

            conversationCoordinator.submitInOrder(conversationId, processUserInput);
        } catch (ProcessingRestrictedException | QuotaExceededException | ConversationAwaitingApprovalException e) {
            throw e; // thrown before processingConversationReferences.add()
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
            final IConversationMemory conversationMemory = loadConversationMemory(conversationId);
            checkConversationMemoryNotNull(conversationMemory, conversationId);

            // GDPR Art. 18 — processing restriction check
            if (conversationMemory.getUserId() != null
                    && gdprComplianceService.isProcessingRestricted(conversationMemory.getUserId())) {
                throw new ProcessingRestrictedException(
                        "Processing is restricted for this user (GDPR Art. 18)");
            }

            var loggingContext = contextLogger.createLoggingContext(environment, agentId, conversationId, conversationMemory.getUserId());
            Integer agentVersion = conversationMemory.getAgentVersion();
            loggingContext.put("agentVersion", agentVersion.toString());
            contextLogger.setLoggingContext(loggingContext);

            if (!agentId.equals(conversationMemory.getAgentId())) {
                String message = "Supplied agentId (%s) is incompatible with conversationId (%s)";
                message = String.format(message, agentId, conversationId);
                throw new AgentMismatchException(message);
            }

            // HITL fast-fail (mirrors say()): reject input into a paused
            // conversation promptly instead of leaving the SSE stream dangling.
            if (conversationMemory.getConversationState() == ConversationState.AWAITING_HUMAN) {
                throw new ConversationAwaitingApprovalException(
                        "Conversation is awaiting human approval — a reviewer must resolve it via"
                                + " POST /agents/" + conversationId + "/resume (or cancel) before new input is accepted");
            }

            IAgent agent = getAgent(environment, agentId, agentVersion);
            if (agent == null) {
                String msg = "Agent not deployed (environment=%s, conversationId=%s, version=%s)";
                msg = String.format(msg, environment, conversationMemory.getAgentId(), agentVersion);
                throw new AgentNotReadyException(msg);
            }

            // Tenant quota — atomic slot acquisition AFTER cheap validations
            // (avoids burning quota on not-found, GDPR-restricted, agent-mismatch, or
            // agent-not-ready failures)
            QuotaCheckResult quotaCheck = tenantQuotaService.acquireApiCallSlot();
            if (!quotaCheck.allowed()) {
                throw new QuotaExceededException(quotaCheck.reason());
            }

            processingConversationReferences.add(createReferenceForMetrics(agentId, conversationId));

            // Create event sink that delegates to the streaming handler
            var eventSink = new ConversationEventSink() {
                @Override
                public void onTaskStart(TaskId taskId, String taskType, int index) {
                    streamingHandler.onTaskStart(taskId, taskType, index);
                }

                @Override
                public void onTaskComplete(TaskId taskId, String taskType, long durationMs, Map<String, Object> summary) {
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

            // Handler contract (mirrors say()): a skipped turn must terminate the
            // stream with the persisted state instead of leaving it open.
            Consumer<IConversationMemory> notifySkipped = skippedMemory -> {
                SimpleConversationMemorySnapshot memorySnapshot = convertSimpleConversationMemorySnapshot(skippedMemory,
                        returnDetailed, returnCurrentStepOnly, returningFields);
                memorySnapshot.setEnvironment(environment);
                recordMetrics(timerConversationProcessing, counterConversationProcessing, startTime);
                processingConversationReferences.remove(createReferenceForMetrics(agentId, conversationId));
                streamingHandler.onSkipped(memorySnapshot);
            };

            Callable<Void> processUserInput = processConversationStep(environment, conversationMemory, conversationId, loggingContext,
                    executeConversation, notifySkipped);

            conversationCoordinator.submitInOrder(conversationId, processUserInput);
        } catch (ProcessingRestrictedException | QuotaExceededException | ConversationAwaitingApprovalException e) {
            throw e; // thrown before processingConversationReferences.add()
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

            // #5: undo during AWAITING_HUMAN would corrupt the HITL bookmark, and
            // undo during IN_PROGRESS (a resume executing) would round-trip a
            // persisted IN_PROGRESS from outside the resume CAS — breaking the
            // invariant crash recovery relies on. Checked against the loaded
            // (DB-backed) state; false maps to 409 CONFLICT at the REST layer.
            if (conversationMemory.getConversationState() == ConversationState.AWAITING_HUMAN
                    || conversationMemory.getConversationState() == ConversationState.IN_PROGRESS) {
                LOGGER.warnf("Undo rejected: conversation %s is in state %s",
                        conversationId, conversationMemory.getConversationState());
                return false;
            }

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

            // #5: redo during AWAITING_HUMAN would corrupt the HITL bookmark;
            // redo during IN_PROGRESS races an executing resume (see undo).
            // DB-backed state check; false maps to 409 CONFLICT at the REST layer.
            if (conversationMemory.getConversationState() == ConversationState.AWAITING_HUMAN
                    || conversationMemory.getConversationState() == ConversationState.IN_PROGRESS) {
                LOGGER.warnf("Redo rejected: conversation %s is in state %s",
                        conversationId, conversationMemory.getConversationState());
                return false;
            }

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
                                                   Map<String, String> loggingContext, Callable<Void> executeConversation,
                                                   Consumer<IConversationMemory> skipNotifier) {
        return () -> {
            // Queued-say guard: this memory copy was loaded at REST-request time;
            // a previously queued turn may have committed a pause (or a resume may
            // be executing) in the meantime. Skip the turn entirely — executing it
            // against the stale snapshot would end with a full-document store that
            // silently overwrites the pause (destroying the pending approval and
            // orphaning its timeout schedule). The skip notifier completes the
            // caller's response handler with the persisted state, so the client
            // gets a prompt, honest answer instead of a watchdog timeout.
            ConversationState persistedState = conversationMemoryStore.getConversationState(conversationId);
            if (persistedState == ConversationState.AWAITING_HUMAN || persistedState == ConversationState.IN_PROGRESS) {
                conversationMemory.setConversationState(persistedState);
                contextLogger.setLoggingContext(loggingContext);
                LOGGER.warnf("Skipping queued turn for conversation %s: persisted state is %s (turn arrived before the state change)",
                        conversationId, persistedState);
                if (skipNotifier != null) {
                    skipNotifier.accept(conversationMemory);
                }
                return null;
            }

            // Zombie-pause guard: the state loaded WITH the snapshot at request
            // time — on a backend whose snapshot state diverged from the CAS'd
            // state column, this can still claim AWAITING_HUMAN even though the
            // pause was terminally resolved (persistedState above says otherwise).
            // Never execute against, persist, or re-arm a pause this turn did not
            // produce.
            final ConversationState memoryStateAtSubmit = conversationMemory.getConversationState();

            // #2: register the live memory so cancelConversation can signal the
            // running pipeline via setCancelled (checked at task boundaries).
            inFlightConversations.put(conversationId, conversationMemory);
            try {
                runGuardedConversationStep(loggingContext, conversationId, environment, conversationMemory,
                        executeConversation, memoryStateAtSubmit);
            } finally {
                // value-conditional: only the leg that registered this memory may
                // unregister — a plain remove(key) could evict a NEWER execution's
                // entry and defeat its cooperative cancel.
                inFlightConversations.remove(conversationId, conversationMemory);
            }
            return null;
        };
    }

    private void runGuardedConversationStep(Map<String, String> loggingContext, String conversationId,
                                            Environment environment, IConversationMemory conversationMemory,
                                            Callable<Void> executeConversation, ConversationState memoryStateAtSubmit) {
        waitForExecutionFinishOrTimeout(loggingContext, conversationId,
                runtime.submitCallable(executeConversation, new IRuntime.IFinishedExecution<>() {
                    @Override
                    public void onComplete(Void result) {
                        try {
                            boolean awaitingHuman = conversationMemory.getConversationState() == ConversationState.AWAITING_HUMAN;
                            if (awaitingHuman && memoryStateAtSubmit == ConversationState.AWAITING_HUMAN) {
                                // Stale pause carried in from the loaded snapshot —
                                // this turn did not pause (it was skipped/rejected
                                // by the pipeline). Persisting would resurrect a
                                // terminally resolved approval as a zombie and
                                // re-arm its timeout. Drop the result.
                                contextLogger.setLoggingContext(loggingContext);
                                LOGGER.warnf("Discarding turn result for conversation %s: snapshot carried a stale "
                                        + "AWAITING_HUMAN state this turn did not produce", conversationId);
                                return;
                            }
                            // #6: copy the agent's timeout policy into the bookmark
                            // BEFORE persisting so approval-status/pending-approvals
                            // report it and crash recovery can distinguish
                            // WAIT_INDEFINITELY pauses from lost-schedule ones.
                            if (awaitingHuman) {
                                populateHitlTimeoutBookmark(conversationMemory);
                            }
                            storeConversationMemory(conversationMemory, environment);
                            // M1: Schedule HITL timeout if conversation paused
                            if (awaitingHuman) {
                                counterHitlPause.increment();
                                scheduleHitlTimeout(conversationId, conversationMemory);
                            }
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
    }

    private void waitForExecutionFinishOrTimeout(Map<String, String> loggingContext, String conversationId, Future<Void> future) {
        try {
            future.get(agentTimeout, TimeUnit.SECONDS);
        } catch (TimeoutException | InterruptedException e) {
            // Guard: do not overwrite AWAITING_HUMAN with EXECUTION_INTERRUPTED (Invariant
            // 10)
            ConversationState currentState = conversationMemoryStore.getConversationState(conversationId);
            if (currentState == ConversationState.AWAITING_HUMAN) {
                return;
            }
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

    // --- HITL lifecycle ---

    @Override
    public CancelOutcome cancelConversation(String conversationId,
                                            ai.labs.eddi.engine.lifecycle.model.ControlSignal mode,
                                            String cancelledBy)
            throws ResourceStoreException {
        ConversationState currentState = conversationMemoryStore.getConversationState(conversationId);
        if (currentState == null) {
            return CancelOutcome.NOT_FOUND;
        }

        // MAJOR-3: Delete stale HITL timeout schedule before cancel
        deleteHitlTimeoutSchedule(conversationId);

        // #2: signal an in-flight execution on this pod. The pipeline checks the
        // flag at task boundaries and stops before the next lifecycle task.
        // CANCEL_IMMEDIATE currently degrades to graceful semantics on the
        // regular surface (no per-conversation future handle to interrupt).
        var inFlightMemory = inFlightConversations.get(conversationId);
        if (inFlightMemory != null) {
            inFlightMemory.setCancelled(true);
            LOGGER.infof("Signalled in-flight cancellation (%s) for conversation %s", mode, conversationId);
        }

        boolean pauseCancelled = conversationMemoryStore.compareAndSetState(conversationId,
                ConversationState.AWAITING_HUMAN, ConversationState.EXECUTION_INTERRUPTED);
        boolean changed = pauseCancelled;
        if (!changed) {
            changed = conversationMemoryStore.compareAndSetState(conversationId,
                    ConversationState.IN_PROGRESS, ConversationState.EXECUTION_INTERRUPTED);
        }
        if (changed) {
            cacheConversationState(conversationId, ConversationState.EXECUTION_INTERRUPTED);
            if (pauseCancelled) {
                // A pending human approval was terminally resolved outside resume:
                // remove the stale bookmark (it would otherwise round-trip forever
                // and confuse approval-status + crash recovery) and audit the
                // cancellation (EU AI Act — cancels are decisions too).
                try {
                    conversationMemoryStore.clearHitlBookmark(conversationId);
                } catch (Exception e) {
                    LOGGER.warnf("Failed to clear HITL bookmark on cancel of %s: %s", conversationId, e.getMessage());
                }
                auditHitlCancellation(conversationId, mode, cancelledBy);
            }
            return CancelOutcome.CANCELLED;
        }
        if (inFlightMemory != null) {
            // Nothing persisted to flip, but a running pipeline was signalled —
            // it will stop at the next task boundary and persist its own state.
            return CancelOutcome.CANCELLED;
        }
        // READY / ENDED / ERROR / EXECUTION_INTERRUPTED: nothing paused, nothing
        // running. Use endConversation to close a READY conversation.
        return CancelOutcome.NOTHING_TO_CANCEL;
    }

    @Override
    public void resumeConversation(String conversationId,
                                   ai.labs.eddi.engine.lifecycle.model.HitlDecision decision,
                                   ConversationResponseHandler handler)
            throws ResourceStoreException, ResourceNotFoundException {
        // #7: distinguish "unknown conversation" (404) from "wrong state" (409)
        // — compareAndSetState returns false for both.
        if (conversationMemoryStore.getConversationState(conversationId) == null) {
            throw new ResourceNotFoundException("Conversation not found: " + conversationId);
        }
        if (!conversationMemoryStore.compareAndSetState(conversationId,
                ConversationState.AWAITING_HUMAN, ConversationState.IN_PROGRESS)) {
            ConversationState current = conversationMemoryStore.getConversationState(conversationId);
            // IllegalStateException = wrong-state conflict (REST: 409);
            // ResourceStoreException = infrastructure failure (REST: 500)
            throw new IllegalStateException(
                    "Conversation is not in AWAITING_HUMAN state (current: " + current
                            + ") — it may have been resumed, cancelled, or timed out already");
        }
        // MAJOR-3: Delete stale HITL timeout schedule before resume
        deleteHitlTimeoutSchedule(conversationId);
        cacheConversationState(conversationId, ConversationState.IN_PROGRESS);

        // From here on the pause has been consumed: EVERY failure path must
        // restore it (or the approval is wedged IN_PROGRESS with no schedule).
        final IConversationMemory memory;
        final String agentId;
        final Integer agentVersion;
        final Environment environment;
        try {
            var snapshot = conversationMemoryStore.loadConversationMemorySnapshot(conversationId);
            if (snapshot == null) {
                throw new ResourceNotFoundException("Conversation not found: " + conversationId);
            }
            memory = convertConversationMemorySnapshot(snapshot);
            memory.setConversationState(ConversationState.AWAITING_HUMAN);
            agentId = snapshot.getAgentId();
            agentVersion = snapshot.getAgentVersion();
            environment = snapshot.getEnvironment();
        } catch (ResourceNotFoundException e) {
            throw e; // genuinely deleted — nothing to restore into
        } catch (Exception e) {
            // transient store failure loading the snapshot — restore the pause
            // (no memory available: schedule re-arm happens via crash recovery)
            restorePauseAfterFailedResume(conversationId, null, false);
            throw new ResourceStoreException("Failed to load conversation for resume: " + e.getLocalizedMessage(), e);
        }

        // #3: register the live memory IMMEDIATELY after the CAS so a concurrent
        // cancel can signal this resume before its callable starts. Removed in
        // guardedResume's finally, or in the failure paths below.
        inFlightConversations.put(conversationId, memory);

        try {
            IAgent agent = getAgent(environment, agentId, agentVersion);
            if (agent == null) {
                // #7: a transient deployment issue must not destroy the pending
                // approval — restore the pause instead of flipping to ERROR.
                // No schedule re-arm: an undeployed agent would otherwise loop
                // timeout→restore→re-arm forever; the policy resumes after the
                // next restart (crash recovery) or a manual resume.
                inFlightConversations.remove(conversationId, memory);
                restorePauseAfterFailedResume(conversationId, memory, false);
                throw new IllegalStateException("Agent not deployed for resume (agentId=" + agentId + ", version=" + agentVersion
                        + ") — the conversation remains AWAITING_HUMAN; redeploy the agent and retry");
            }

            // #15/4c: Set the audit collector (same as say path) so resume
            // operations are recorded in the audit ledger.
            if (auditLedgerService.isEnabled()) {
                String envName = environment.toString();
                memory.setAuditCollector(entry -> auditLedgerService.submit(entry.withEnvironment(envName)));
            }

            IConversation conversation = agent.continueConversation(memory,
                    createPropertiesHandler(memory.getUserId(), agent.getUserMemoryConfig()),
                    handler != null ? returnMemory -> {
                        var memorySnapshot = convertSimpleConversationMemorySnapshot(returnMemory, false, true, List.of());
                        memorySnapshot.setEnvironment(environment);
                        cacheConversationState(conversationId, memorySnapshot.getConversationState());
                        handler.onComplete(memorySnapshot);
                    } : null);

            Map<String, String> loggingContext = contextLogger.createLoggingContext(environment, agentId, conversationId, memory.getUserId());

            Callable<Void> resumeCallable = () -> {
                // #3: a cancel or a terminal end may have landed between the CAS
                // and this execution (flag on the registered memory, or DB-only in
                // the tiny pre-registration window). Abort without persisting —
                // an accepted resume must never resurrect a cancelled or ENDED
                // conversation.
                ConversationState persistedNow = conversationMemoryStore.getConversationState(conversationId);
                if (memory.isCancelled()
                        || persistedNow == ConversationState.EXECUTION_INTERRUPTED
                        || persistedNow == ConversationState.ENDED) {
                    memory.setCancelled(true);
                    LOGGER.infof("Resume of conversation %s aborted: cancelled/ended before execution (state=%s)",
                            conversationId, persistedNow);
                    return null;
                }
                try {
                    conversation.resume(decision);
                } catch (Exception e) {
                    LOGGER.error("Error during conversation resume: " + conversationId, e);
                    memory.setConversationState(ConversationState.ERROR);
                }
                return null;
            };

            // #2: persistence lives in onComplete — BaseRuntime routes callables
            // that complete after a watchdog cancellation to onFailure, so a
            // zombie resume can never overwrite state written after its timeout.
            IRuntime.IFinishedExecution<Void> resumeFinished = new IRuntime.IFinishedExecution<>() {
                @Override
                public void onComplete(Void result) {
                    if (memory.isCancelled()) {
                        // aborted before execution — the cancel path owns the state
                        return;
                    }
                    try {
                        // #6: keep the timeout-policy bookmark populated on re-pause
                        if (memory.getConversationState() == ConversationState.AWAITING_HUMAN) {
                            populateHitlTimeoutBookmark(memory);
                        }
                        storeConversationMemory(memory, environment);
                        cacheConversationState(conversationId, memory.getConversationState());
                        // #3 (schedule) + metric: a re-pause is a pause
                        if (memory.getConversationState() == ConversationState.AWAITING_HUMAN) {
                            counterHitlPause.increment();
                            scheduleHitlTimeout(conversationId, memory);
                        } else {
                            // Resume settled to a non-paused outcome — notify channel
                            // observers (Slack, …) so the originating surface can push
                            // the continuation without polling. Fired async and
                            // best-effort: a failing observer must never affect the
                            // persisted resume above.
                            fireHitlResumeCompleted(conversationId, environment, memory, decision);
                        }
                    } catch (ResourceStoreException e) {
                        logConversationError(loggingContext, conversationId, e);
                    }
                }

                @Override
                public void onFailure(Throwable t) {
                    if (t instanceof InterruptedException || t instanceof LifecycleException.LifecycleInterruptedException) {
                        // watchdog timeout / stale completion — EXECUTION_INTERRUPTED
                        // was already persisted by the watchdog; discard this result
                        contextLogger.setLoggingContext(loggingContext);
                        LOGGER.warnf("Resume execution of conversation %s interrupted/discarded: %s",
                                conversationId, t.getMessage());
                    } else {
                        logConversationError(loggingContext, conversationId, t);
                    }
                }
            };

            // #4: guard the resume with the same watchdog the say path uses — a
            // hung LLM call or crashed executor must not leave the conversation
            // stuck IN_PROGRESS forever.
            Callable<Void> guardedResume = () -> {
                try {
                    waitForExecutionFinishOrTimeout(loggingContext, conversationId,
                            runtime.submitCallable(resumeCallable, resumeFinished, null));
                } finally {
                    // value-conditional: never evict a newer execution's registration
                    inFlightConversations.remove(conversationId, memory);
                }
                return null;
            };

            try {
                conversationCoordinator.submitInOrder(conversationId, guardedResume);
            } catch (RuntimeException e) {
                // #5: coordinator saturation (RejectedExecutionException) or any
                // submit failure — the callable will never run; restore the pause.
                inFlightConversations.remove(conversationId, memory);
                restorePauseAfterFailedResume(conversationId, memory, true);
                throw new ResourceStoreException("Failed to enqueue resume for conversation " + conversationId
                        + ": " + e.getLocalizedMessage() + " — the conversation remains AWAITING_HUMAN; retry later", e);
            }

            // Only count and audit resumes that were actually accepted (#15,
            // metric drift): rolled-back attempts must not pollute the audit
            // trail or the counter.
            counterHitlResume.increment();
            auditHitlDecision(conversationId, agentId, agentVersion, memory.getUserId(), environment, decision);
        } catch (IllegalStateException e) {
            // Deliberately thrown for the agent-not-deployed case, which already
            // removed the in-flight entry and restored the pause above. Propagate
            // as-is so the REST layer maps it to 409 (not 500).
            throw e;
        } catch (ServiceException | InstantiationException | IllegalAccessException | RuntimeException e) {
            // #7 + review: transient OR unexpected failures anywhere between the CAS
            // and submitInOrder (e.g. an unchecked exception from continueConversation)
            // must restore the pause and drop the in-flight registration — otherwise
            // the conversation is left stuck IN_PROGRESS with a leaked registry entry.
            inFlightConversations.remove(conversationId, memory);
            restorePauseAfterFailedResume(conversationId, memory, true);
            throw new ResourceStoreException("Failed to resume conversation: " + e.getLocalizedMessage(), e);
        }
    }

    /**
     * Submits an {@code hitl.approval} audit entry for a HITL decision. Covers both
     * human decisions and automated timeout decisions
     * ({@code decidedBy=system:timeout}).
     */
    private void auditHitlDecision(String conversationId, String agentId, Integer agentVersion,
                                   String userId, Environment environment,
                                   ai.labs.eddi.engine.lifecycle.model.HitlDecision decision) {
        if (!auditLedgerService.isEnabled()) {
            return;
        }
        try {
            var detail = new LinkedHashMap<String, Object>();
            detail.put("verdict", decision.getVerdict() != null ? decision.getVerdict().name() : "UNKNOWN");
            detail.put("decidedBy", decision.getDecidedBy() != null ? decision.getDecidedBy() : "unknown");
            detail.put("automated", decision.getDecidedBy() != null && decision.getDecidedBy().startsWith("system:"));
            if (decision.getNote() != null) {
                detail.put("note", decision.getNote());
            }
            auditLedgerService.submit(new ai.labs.eddi.engine.audit.model.AuditEntry(
                    UUID.randomUUID().toString(), conversationId, agentId, agentVersion, userId,
                    environment != null ? environment.toString() : null, -1,
                    "hitl.approval", "hitl", -1, 0L,
                    Map.of(), detail, null, null, List.of(), 0.0,
                    Instant.now(), null, null));
        } catch (Exception e) {
            LOGGER.warnf("Failed to submit HITL audit entry for %s: %s", conversationId, e.getMessage());
        }
    }

    /**
     * Fires {@link HitlResumeCompletedEvent} asynchronously after a resume has been
     * persisted to a non-paused state. Building the snapshot and firing the event
     * are wrapped so any failure here (serialization, no observers, …) is logged
     * and swallowed — the resume itself has already succeeded and must not be
     * affected by delivery-side concerns.
     */
    private void fireHitlResumeCompleted(String conversationId, Environment environment,
                                         IConversationMemory memory,
                                         ai.labs.eddi.engine.lifecycle.model.HitlDecision decision) {
        try {
            var snapshot = convertSimpleConversationMemorySnapshot(memory, false, true, List.of());
            snapshot.setEnvironment(environment);
            hitlResumeCompletedEvent.fireAsync(new HitlResumeCompletedEvent(
                    conversationId,
                    decision != null ? decision.getVerdict() : null,
                    decision != null ? decision.getDecidedBy() : null,
                    snapshot));
        } catch (Exception e) {
            LOGGER.warnf("Failed to fire HITL resume-completed event for %s: %s", conversationId, e.getMessage());
        }
    }

    /**
     * Audits the termination of a pending human approval by cancel/ABORT — cancels
     * are HITL decisions too and must be attributable in the oversight trail.
     */
    private void auditHitlCancellation(String conversationId, ai.labs.eddi.engine.lifecycle.model.ControlSignal mode,
                                       String cancelledBy) {
        if (!auditLedgerService.isEnabled()) {
            return;
        }
        try {
            var detail = new LinkedHashMap<String, Object>();
            detail.put("verdict", "CANCELLED");
            detail.put("mode", mode != null ? mode.name() : "CANCEL_GRACEFUL");
            detail.put("decidedBy", cancelledBy != null ? cancelledBy : "unknown");
            detail.put("automated", cancelledBy != null && cancelledBy.startsWith("system:"));
            auditLedgerService.submit(new ai.labs.eddi.engine.audit.model.AuditEntry(
                    UUID.randomUUID().toString(), conversationId, null, null, null,
                    null, -1, "hitl.approval", "hitl", -1, 0L,
                    Map.of(), detail, null, null, List.of(), 0.0,
                    Instant.now(), null, null));
        } catch (Exception e) {
            LOGGER.warnf("Failed to submit HITL cancel audit entry for %s: %s", conversationId, e.getMessage());
        }
    }

    /**
     * Rolls a failed resume back to AWAITING_HUMAN so the pending approval survives
     * transient failures (undeployed agent, service errors, coordinator
     * saturation). Optionally re-arms the timeout schedule the resume attempt
     * deleted — callers pass {@code rearmSchedule=false} when re-arming would loop
     * (undeployed agent) or the bookmark is unavailable (crash recovery re-arms at
     * the next restart instead).
     */
    private void restorePauseAfterFailedResume(String conversationId, IConversationMemory memory, boolean rearmSchedule) {
        try {
            boolean restored = conversationMemoryStore.compareAndSetState(conversationId,
                    ConversationState.IN_PROGRESS, ConversationState.AWAITING_HUMAN);
            if (restored) {
                cacheConversationState(conversationId, ConversationState.AWAITING_HUMAN);
                if (rearmSchedule && memory != null) {
                    scheduleHitlTimeout(conversationId, memory);
                } else {
                    LOGGER.warnf("Pause restored for %s without re-arming the timeout schedule — "
                            + "a finite policy resumes after the next restart (crash recovery) or a manual decision",
                            conversationId);
                }
                LOGGER.warnf("Resume of conversation %s failed — pause restored (AWAITING_HUMAN)", conversationId);
            }
        } catch (Exception e) {
            LOGGER.errorf(e, "Failed to restore pause after failed resume: %s", conversationId);
        }
    }

    /**
     * Copies the agent's HITL timeout config into the memory bookmark so
     * approval-status / pending-approvals report the effective policy and crash
     * recovery can distinguish WAIT_INDEFINITELY pauses from ones whose timeout
     * schedule may have been lost. Reads the config at the conversation's PINNED
     * agentVersion (falling back to the current version only if the pinned one no
     * longer exists) — editing a draft config must not change the runtime behavior
     * of conversations pinned to older versions. Absent config is normalized to
     * WAIT_INDEFINITELY (the default).
     */
    private void populateHitlTimeoutBookmark(IConversationMemory memory) {
        try {
            memory.setHitlTimeoutPolicy(HitlTimeoutPolicy.WAIT_INDEFINITELY.name());
            AgentConfiguration agentConfig = readAgentConfigPinned(memory.getAgentId(), memory.getAgentVersion());
            if (agentConfig == null)
                return;
            AgentConfiguration.HitlConfig hitlConfig = agentConfig.getHitlConfig();
            if (hitlConfig == null)
                return;
            // Designer-supplied pause reason answers "what am I approving?" in the
            // approval inbox; the generic reason set at pause time stays otherwise.
            if (!isNullOrEmpty(hitlConfig.getPauseReason())) {
                memory.setHitlPauseReason(hitlConfig.getPauseReason());
            }
            if (hitlConfig.getTimeoutPolicy() == null)
                return;
            memory.setHitlTimeoutPolicy(hitlConfig.getTimeoutPolicy().name());
            memory.setHitlApprovalTimeout(hitlConfig.getApprovalTimeout());
        } catch (Exception e) {
            LOGGER.warnf("Could not populate HITL timeout bookmark for %s: %s",
                    memory.getConversationId(), e.getMessage());
        }
    }

    /** Reads the agent config at the pinned version, falling back to the latest. */
    private AgentConfiguration readAgentConfigPinned(String agentId, Integer agentVersion) {
        try {
            if (agentVersion != null && agentVersion > 0) {
                return agentStore.read(agentId, agentVersion);
            }
        } catch (Exception pinnedMiss) {
            LOGGER.debugf("Pinned agent config %s v%s unavailable, falling back to latest: %s",
                    agentId, agentVersion, pinnedMiss.getMessage());
        }
        try {
            IResourceStore.IResourceId currentId = agentStore.getCurrentResourceId(agentId);
            return currentId != null ? agentStore.read(agentId, currentId.getVersion()) : null;
        } catch (Exception e) {
            LOGGER.warnf("Could not read agent config %s: %s", agentId, e.getMessage());
            return null;
        }
    }

    @Override
    public ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot getConversationMemorySnapshot(String conversationId)
            throws ResourceStoreException, ResourceNotFoundException {
        var snapshot = conversationMemoryStore.loadConversationMemorySnapshot(conversationId);
        if (snapshot == null) {
            throw new ResourceNotFoundException("Conversation not found: " + conversationId);
        }
        return snapshot;
    }

    @Override
    public java.util.List<ai.labs.eddi.engine.model.PendingApprovalSummary> listPendingApprovals(int limit)
            throws ResourceStoreException {
        // #17: bounded, projection-based listing — never deserializes full
        // conversation documents on the Mongo backend.
        return conversationMemoryStore.findPendingApprovalSummaries(Math.max(1, Math.min(limit, 1000)));
    }

    @Override
    public java.util.List<ai.labs.eddi.engine.model.PendingApprovalSummary> listPendingApprovals(String ownerUserId, int limit)
            throws ResourceStoreException {
        // Owner filter is pushed into the query so the limit applies AFTER the
        // restriction — a non-admin inbox can't be starved by others' backlog.
        return conversationMemoryStore.findPendingApprovalSummaries(ownerUserId, Math.max(1, Math.min(limit, 1000)));
    }

    /**
     * Creates a one-shot schedule that fires the {@link HitlTimeoutHandler} when
     * the configured approval timeout expires. Reads the policy from the memory's
     * HITL BOOKMARK (populated by {@link #populateHitlTimeoutBookmark} just before
     * — single config resolution per pause, and bookmark and schedule can never
     * diverge). No-ops without a finite policy + timeout.
     */
    private void scheduleHitlTimeout(String conversationId, IConversationMemory memory) {
        try {
            String timeoutStr = memory.getHitlApprovalTimeout();
            String policyName = memory.getHitlTimeoutPolicy();
            if (timeoutStr == null || timeoutStr.isBlank() || policyName == null
                    || HitlTimeoutPolicy.WAIT_INDEFINITELY.name().equals(policyName)) {
                return;
            }

            Duration timeout = Duration.parse(timeoutStr);
            Instant fireAt = Instant.now().plus(timeout);

            var schedule = new ScheduleConfiguration();
            schedule.setName("hitl-timeout-" + conversationId);
            schedule.setAgentId(memory.getAgentId());
            schedule.setOneTimeAt(fireAt.toString());
            schedule.setEnabled(true);
            schedule.setNextFire(fireAt);
            schedule.setCreatedAt(Instant.now());
            schedule.setMetadata(Map.of(
                    "hitlType", "hitl_timeout",
                    "policy", policyName,
                    "surface", "regular",
                    "conversationId", conversationId));
            scheduleStore.createSchedule(schedule);
            LOGGER.infof("Scheduled HITL timeout for conversation %s at %s (policy: %s)",
                    conversationId, fireAt, policyName);
        } catch (Exception e) {
            LOGGER.warnf("Failed to schedule HITL timeout for conversation %s: %s",
                    conversationId, e.getMessage());
        }
    }

    /**
     * Deletes any existing HITL timeout schedule for the given conversation. Called
     * on resume and cancel to prevent stale fires and duplicate schedules.
     */
    private void deleteHitlTimeoutSchedule(String conversationId) {
        try {
            int deleted = scheduleStore.deleteSchedulesByName("hitl-timeout-" + conversationId);
            if (deleted > 0) {
                LOGGER.infof("Cleaned up %d HITL timeout schedule(s) for conversation %s", deleted, conversationId);
            }
        } catch (Exception e) {
            LOGGER.warnf("Failed to delete HITL timeout schedule for conversation %s: %s",
                    conversationId, e.getMessage());
        }
    }
}
