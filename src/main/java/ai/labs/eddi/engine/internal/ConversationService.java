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
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.audit.AuditLedgerService;
import ai.labs.eddi.engine.events.HitlResumeCompletedEvent;
import ai.labs.eddi.engine.gdpr.GdprComplianceService;
import ai.labs.eddi.engine.gdpr.ProcessingRestrictedException;
import ai.labs.eddi.engine.hitl.HitlSchedules;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import ai.labs.eddi.engine.lifecycle.model.ToolCallDecision;
import ai.labs.eddi.engine.memory.model.PendingToolCallBatch;
import ai.labs.eddi.engine.tenancy.QuotaExceededException;
import ai.labs.eddi.engine.tenancy.TenantQuotaService;
import ai.labs.eddi.engine.tenancy.model.QuotaCheckResult;
import ai.labs.eddi.engine.caching.ICache;
import ai.labs.eddi.engine.caching.ICacheFactory;
import ai.labs.eddi.engine.lifecycle.ConversationEventSink;
import ai.labs.eddi.engine.lifecycle.IConversation;
import ai.labs.eddi.engine.lifecycle.TaskId;
import ai.labs.eddi.engine.lifecycle.exceptions.ConversationPauseException;
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
import static ai.labs.eddi.utils.LogSanitizer.sanitize;
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
    private final IJsonSerialization jsonSerialization;
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
    // Task 10 — tool-level HITL metrics registry (new meters, never re-tag
    // existing).
    private final MeterRegistry meterRegistry;

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
            IJsonSerialization jsonSerialization,
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
        this.jsonSerialization = jsonSerialization;
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
        // Task 10 — tool-level HITL meters are registered lazily via the registry (tags
        // vary per emission: verdict on resume, guard name on guard activation).
        this.meterRegistry = meterRegistry;

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
        // Default actor for callers that cannot attribute (background/scheduled
        // cleanup, private group-member teardown). REST/admin callers pass an actor
        // via the overload so a pause-terminating end is attributable in the audit
        // trail (G4).
        endConversation(conversationId, "system:end");
    }

    @Override
    public void endConversation(String conversationId, String endedBy) {
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
        // Disarm the timeout UNCONDITIONALLY (idempotent, no-ops when absent): a resume
        // in flight may have already flipped AWAITING_HUMAN->IN_PROGRESS and deferred
        // its
        // own schedule delete, so gating this on AWAITING_HUMAN would miss that window
        // and leave a stale one-shot timer armed against the now-ended conversation.
        deleteHitlTimeoutSchedule(conversationId);
        if (previousState == ConversationState.AWAITING_HUMAN) {
            // G4: an end that terminates a pending approval is an oversight decision
            // too — audit it with the actor (EU AI Act; parity with cancel) so every
            // pause-terminating path is attributed. G5: notify channel observers
            // (Slack, …) with a null verdict + terminal snapshot so the originating
            // surface can render the outcome (mirrors cancel/timeout).
            auditHitlCancellation(conversationId, null, endedBy);
            fireHitlResumeCompletedTerminal(conversationId, endedBy);
            try {
                conversationMemoryStore.clearHitlBookmark(conversationId);
            } catch (Exception e) {
                LOGGER.warnf("Failed to clear HITL bookmark while ending %s: %s", conversationId, e.getMessage());
            }
            LOGGER.warnf("Conversation %s was ended by %s while awaiting human approval — the pending approval is terminated",
                    conversationId, endedBy);
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
            ConversationState loadedStateForUndo = conversationMemory.getConversationState();
            if (loadedStateForUndo == ConversationState.AWAITING_HUMAN
                    || loadedStateForUndo == ConversationState.IN_PROGRESS) {
                LOGGER.warnf("Undo rejected: conversation %s is in state %s", conversationId, loadedStateForUndo);
                return false;
            }

            if (conversationMemory.isUndoAvailable()) {
                conversationMemory.undoLastStep();
                // undo/redo run on the REST thread, NOT through the per-conversation
                // coordinator, so a say turn on this conversation can commit a fresh
                // pause between the state check above and this store. An unconditional
                // full-document replace would then clobber that just-persisted
                // AWAITING_HUMAN snapshot — destroying the pending approval and
                // orphaning its armed timer. CAS from the loaded (pre-undo) state so
                // the store lands only if nothing moved the DB state meanwhile; on a
                // miss the concurrent writer wins and undo reports no-op.
                if (!storeConversationMemoryIfState(conversationMemory, environment, loadedStateForUndo)) {
                    LOGGER.warnf("Undo of conversation %s aborted: state changed concurrently (was %s)",
                            conversationId, loadedStateForUndo);
                    return false;
                }
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
            ConversationState loadedStateForRedo = conversationMemory.getConversationState();
            if (loadedStateForRedo == ConversationState.AWAITING_HUMAN
                    || loadedStateForRedo == ConversationState.IN_PROGRESS) {
                LOGGER.warnf("Redo rejected: conversation %s is in state %s", conversationId, loadedStateForRedo);
                return false;
            }

            if (conversationMemory.isRedoAvailable()) {
                conversationMemory.redoLastStep();
                // Same second-writer race as undo (see above): CAS from the loaded
                // state so a concurrent say-turn pause commit is not clobbered.
                if (!storeConversationMemoryIfState(conversationMemory, environment, loadedStateForRedo)) {
                    LOGGER.warnf("Redo of conversation %s aborted: state changed concurrently (was %s)",
                            conversationId, loadedStateForRedo);
                    return false;
                }
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
            // be executing), or the conversation may have been terminally resolved
            // (ENDED via endConversation) in the meantime. Skip the turn entirely —
            // executing it against the stale snapshot would end with a full-document
            // store that silently overwrites the pause (destroying the pending
            // approval and orphaning its timeout schedule) or RESURRECTS a terminated
            // conversation to READY with post-termination side effects. The skip
            // notifier completes the caller's response handler with the persisted
            // state, so the client gets a prompt, honest answer instead of a watchdog
            // timeout.
            //
            // EXECUTION_INTERRUPTED is deliberately NOT skipped: unlike ENDED it is a
            // RECOVERABLE marker meaning "the previous turn did not finish" (an
            // agentTimeout watchdog expiry, or HitlCrashRecoveryObserver parking a
            // stuck IN_PROGRESS conversation with the explicit intent to "unlock
            // say()"). A fresh say must run a new turn to self-heal the conversation
            // back to READY — mirroring the pre-HITL behavior where a retry after an
            // interrupt executed normally. Skipping it would strand the conversation's
            // input forever, since nothing else transitions EXECUTION_INTERRUPTED back
            // to READY.
            ConversationState persistedState = conversationMemoryStore.getConversationState(conversationId);
            if (persistedState == ConversationState.AWAITING_HUMAN || persistedState == ConversationState.IN_PROGRESS
                    || persistedState == ConversationState.ENDED) {
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
            // Carry the agent-level tool-approval config onto memory BEFORE the
            // pipeline (LlmTask) runs, so the tool-approval gate can resolve its
            // effective config. Transient — never persisted; re-resolved each turn.
            populateToolApprovalsConfig(conversationMemory);
            try {
                runGuardedConversationStep(loggingContext, conversationId, environment, conversationMemory,
                        executeConversation, memoryStateAtSubmit, persistedState);
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
                                            Callable<Void> executeConversation, ConversationState memoryStateAtSubmit,
                                            ConversationState preTurnPersistedState) {
        waitForExecutionFinishOrTimeout(loggingContext, conversationId,
                runtime.submitCallable(executeConversation, new IRuntime.IFinishedExecution<>() {
                    @Override
                    public void onComplete(Void result) {
                        try {
                            // #2 (say path parity with resume): a concurrent cancel/end
                            // may have signalled this in-flight memory (e.g. a group
                            // handleMemberPause → cancelConversation that lost both
                            // state CAS races because this turn's pause was not yet
                            // persisted). Honor the cooperative-cancel flag: never
                            // persist AWAITING_HUMAN, arm a timeout, or count a pause
                            // nobody wants — that strands the approval. Persist
                            // EXECUTION_INTERRUPTED via CAS from the (non-terminal)
                            // running state so a cross-pod terminal writer (ENDED) still
                            // wins and a resurrected READY never lingers.
                            if (conversationMemory.isCancelled()) {
                                contextLogger.setLoggingContext(loggingContext);
                                LOGGER.infof("Turn of conversation %s completed after a cancel signal — "
                                        + "discarding its outcome (no pause persisted/armed)", conversationId);
                                ConversationState runningState = conversationMemoryStore.getConversationState(conversationId);
                                if (runningState == ConversationState.READY || runningState == ConversationState.IN_PROGRESS) {
                                    if (conversationMemoryStore.compareAndSetState(conversationId,
                                            runningState, ConversationState.EXECUTION_INTERRUPTED)) {
                                        cacheConversationState(conversationId, ConversationState.EXECUTION_INTERRUPTED);
                                    }
                                }
                                return;
                            }
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
                                // #3 (say-path parity with resume): a fresh pause commits
                                // the FULL AWAITING_HUMAN snapshot. The up-front
                                // isCancelled() check above narrows but does not close the
                                // race with a concurrent end/cancel (REST/admin thread, NOT
                                // serialized by the per-conversation coordinator): a terminal
                                // write (ENDED / EXECUTION_INTERRUPTED) can land between that
                                // check and this store, and an unconditional full-document
                                // replace would then overwrite the terminal state with a
                                // pending approval — resurrecting a terminated conversation
                                // with a live timeout. Persist ONLY while the conversation
                                // still holds the non-terminal state this turn started in —
                                // the say path never CAS's the DB state (Conversation.runStep
                                // sets IN_PROGRESS in-memory only), so the persisted state is
                                // whatever it was when the queued-say guard let this turn
                                // through: READY normally, or ERROR / EXECUTION_INTERRUPTED on
                                // a retry after a failed or interrupted prior turn (both are
                                // legal say-start states the guard deliberately admits). CAS
                                // from that exact baseline so a concurrent end/cancel that
                                // moved the state to a terminal value still wins; on a CAS
                                // miss, discard the pause outcome (no counter, no schedule) so
                                // the terminal state stands — mirrors the resume path's
                                // storeConversationMemoryIfState guard.
                                boolean persisted = storeConversationMemoryIfState(
                                        conversationMemory, environment, preTurnPersistedState);
                                if (!persisted) {
                                    contextLogger.setLoggingContext(loggingContext);
                                    LOGGER.infof("Pause of conversation %s not persisted: a concurrent end/cancel moved "
                                            + "it off %s — discarding the pause outcome so the terminal state wins",
                                            conversationId, preTurnPersistedState);
                                    return;
                                }
                                // M2: close the cancel-during-commit window. A cancel that
                                // signalled setCancelled(true) AFTER the top-of-onComplete
                                // check but BEFORE the store above returned CANCELLED to its
                                // caller via cancelConversation's in-flight-signal branch
                                // WITHOUT flipping our (still-non-terminal) DB state — so the
                                // pause we just committed would strand as a live approval with
                                // an armed timer on a conversation the caller was told is
                                // cancelled. Now that the pause is durable, re-check the flag:
                                // if signalled, convert the committed AWAITING_HUMAN pause to
                                // EXECUTION_INTERRUPTED and skip the counter/schedule so nothing
                                // is armed and no approval is stranded.
                                if (conversationMemory.isCancelled()) {
                                    contextLogger.setLoggingContext(loggingContext);
                                    if (conversationMemoryStore.compareAndSetState(conversationId,
                                            ConversationState.AWAITING_HUMAN, ConversationState.EXECUTION_INTERRUPTED)) {
                                        cacheConversationState(conversationId, ConversationState.EXECUTION_INTERRUPTED);
                                        try {
                                            conversationMemoryStore.clearHitlBookmark(conversationId);
                                        } catch (Exception e) {
                                            LOGGER.warnf("Failed to clear HITL bookmark on cancel-raced pause of %s: %s",
                                                    conversationId, e.getMessage());
                                        }
                                    }
                                    LOGGER.infof("Cancel raced the pause commit for conversation %s — converted the "
                                            + "committed pause to EXECUTION_INTERRUPTED (no counter, no timeout armed)",
                                            conversationId);
                                    return;
                                }
                                // M1: a durably-committed pause is counted and its timeout armed.
                                counterHitlPause.increment();
                                scheduleHitlTimeout(conversationId, conversationMemory);
                            } else {
                                // Non-pause completion is unchanged: the normal say turn
                                // settles to READY/ENDED/… and persists the full snapshot.
                                storeConversationMemory(conversationMemory, environment);
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

    /**
     * Persist the full memory snapshot only while the conversation is still in
     * {@code expectedState} — an atomic compare-and-store. Used by the resume path
     * so a resumed outcome cannot clobber an ENDED/EXECUTION_INTERRUPTED state
     * written concurrently by end/cancel.
     */
    private boolean storeConversationMemoryIfState(IConversationMemory conversationMemory, Environment environment,
                                                   ConversationState expectedState)
            throws ResourceStoreException {
        var memorySnapshot = convertConversationMemory(conversationMemory);
        memorySnapshot.setEnvironment(environment);
        return conversationMemoryStore.storeConversationMemorySnapshotIfState(memorySnapshot, expectedState);
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
                // audit the cancellation (EU AI Act — cancels are decisions too), G5
                // notify channel observers with a null verdict + terminal snapshot so
                // the originating surface (Slack, …) renders the outcome, then remove
                // the stale bookmark (it would otherwise round-trip forever and
                // confuse approval-status + crash recovery). The event is fired BEFORE
                // the bookmark clear so any observer that inspects the reason still
                // sees it, and the snapshot already carries the terminal state.
                auditHitlCancellation(conversationId, mode, cancelledBy);
                fireHitlResumeCompletedTerminal(conversationId, cancelledBy);
                try {
                    conversationMemoryStore.clearHitlBookmark(conversationId);
                } catch (Exception e) {
                    LOGGER.warnf("Failed to clear HITL bookmark on cancel of %s: %s", conversationId, e.getMessage());
                }
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

    /**
     * Sentinel for the DELIBERATE agent-not-deployed resume rejection, which has
     * already restored the pause + dropped the in-flight entry before throwing.
     * Being an {@link IllegalStateException} it still maps to REST 409, but its
     * distinct type lets the resume catch re-throw it WITHOUT a second (double)
     * restore, while any OTHER IllegalStateException (e.g. one bubbling out of
     * continueConversation) falls through to the restore-and-wrap path (review
     * carve-out narrowing).
     */
    private static final class AgentNotDeployedForResumeException extends IllegalStateException {
        AgentNotDeployedForResumeException(String message) {
            super(message);
        }
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
        // Task 7: per-tool-call verdicts (toolDecisions) MUST be validated BEFORE the
        // AWAITING_HUMAN->IN_PROGRESS CAS below — a malformed body must never consume
        // the pause (mirrors GroupConversationService#resumeDiscussion's
        // validate-before-mutate precedent for taskApprovals: IllegalArgumentException
        // maps to REST 400 at the adapter). This is the ONLY pre-CAS snapshot read on
        // this path (the 404 check above uses the cheaper getConversationState), and
        // it is skipped entirely when toolDecisions is absent so the overwhelmingly
        // common plain-verdict resume incurs no extra load.
        if (decision != null && decision.getToolDecisions() != null && !decision.getToolDecisions().isEmpty()) {
            var preCasSnapshot = conversationMemoryStore.loadConversationMemorySnapshot(conversationId);
            if (preCasSnapshot != null) {
                validateToolDecisions(decision, preCasSnapshot);
            }
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
        cacheConversationState(conversationId, ConversationState.IN_PROGRESS);

        // From here on the pause's STATE has been consumed
        // (AWAITING_HUMAN->IN_PROGRESS):
        // EVERY failure path must restore it, or the approval is wedged IN_PROGRESS.
        // The timeout schedule is deliberately NOT deleted yet (see below): a failure
        // before that point — a transient snapshot-load hiccup or an undeployed agent —
        // then leaves the original finite-policy timeout armed, so it still fires on
        // time instead of silently degrading to wait-forever until the next restart.
        final IConversationMemory memory;
        final String agentId;
        final Integer agentVersion;
        final Environment environment;
        // Task 10 — capture the PRE-resume tool-pause identity BEFORE the async resume
        // mutates the live memory. Used for (a) the argsDigest audit detail (the batch
        // the human/timeout decision applies to) and (b) the no-progress guard's
        // same-turn same-fingerprint comparison in onComplete.
        final boolean prePauseWasToolCall;
        final String prePauseFingerprint;
        final int prePauseAutoApproveCount;
        // The pending batch the DECISION applies to — captured here so the audit reads
        // it deterministically even though the async resume may mutate the live memory.
        final PendingToolCallBatch prePauseBatch;
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
            prePauseWasToolCall = ConversationPauseException.PauseOrigin.TOOL_CALL.name().equals(memory.getHitlPauseType());
            prePauseBatch = memory.getHitlPendingToolCalls();
            prePauseFingerprint = prePauseBatch != null ? prePauseBatch.getFingerprint() : null;
            prePauseAutoApproveCount = prePauseBatch != null ? prePauseBatch.getAutoApproveCount() : 0;
        } catch (ResourceNotFoundException e) {
            throw e; // genuinely deleted — nothing to restore into
        } catch (Exception e) {
            // transient store failure loading the snapshot — restore the pause. No
            // re-arm needed: the timeout schedule is deleted only after this load
            // succeeds, so it is still armed here and the finite policy still fires.
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
                // approval — restore the pause instead of flipping to ERROR. No
                // re-arm needed: the delete happens only below (after this check), so
                // the original timeout schedule is still armed and the finite policy
                // continues to fire on time; redeploy + retry works either way.
                inFlightConversations.remove(conversationId, memory);
                restorePauseAfterFailedResume(conversationId, memory, false);
                throw new AgentNotDeployedForResumeException("Agent not deployed for resume (agentId=" + agentId + ", version=" + agentVersion
                        + ") — the conversation remains AWAITING_HUMAN; redeploy the agent and retry");
            }

            // MAJOR-3: now that the snapshot has loaded and the agent is confirmed
            // deployed — i.e. we are committed to executing the resume — disarm the
            // stale timeout schedule. Deferring the delete to here (rather than right
            // after the CAS) means a pre-execution failure above leaves the original
            // finite-policy timeout armed, so an AUTO_REJECT/AUTO_APPROVE/ABORT policy
            // is never silently dropped by a transient load hiccup. The AWAITING_HUMAN
            // ->IN_PROGRESS CAS already prevents the timeout from firing concurrently
            // (its own CAS would fail), so nothing races on this window.
            deleteHitlTimeoutSchedule(conversationId);

            // #15/4c: Set the audit collector (same as say path) so resume
            // operations are recorded in the audit ledger.
            if (auditLedgerService.isEnabled()) {
                String envName = environment.toString();
                memory.setAuditCollector(entry -> auditLedgerService.submit(entry.withEnvironment(envName)));
            }

            // Carry the agent-level tool-approval config onto memory BEFORE the resumed
            // pipeline runs — parity with the say path. The resumed tool loop resolves
            // its effective gate from it, and Task 10's no-progress guard reads
            // onNoProgress / maxAutoApprovalsPerTurn from it in onComplete.
            populateToolApprovalsConfig(memory);

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
                        boolean rePaused = memory.getConversationState() == ConversationState.AWAITING_HUMAN;
                        if (rePaused) {
                            populateHitlTimeoutBookmark(memory);
                        }
                        // Task 10 — no-progress guard: a TOOL_CALL pause that RE-PAUSES in
                        // the same turn with an IDENTICAL fingerprint after a system
                        // decision is a wedged auto-approval loop. Resolve the carried
                        // autoApproveCount and, once the budget is spent, apply onNoProgress
                        // (WAIT_FOR_HUMAN demotes / AUTO_REJECT resumes reject-all / ABORT
                        // cancels). Runs BEFORE the persist so a demotion is reflected in the
                        // stored bookmark and the schedule below.
                        NoProgressOutcome noProgress = NoProgressOutcome.NONE;
                        if (rePaused && prePauseWasToolCall) {
                            noProgress = evaluateAndApplyNoProgressGuard(conversationId, agentId, agentVersion,
                                    memory, environment, decision, prePauseFingerprint, prePauseAutoApproveCount);
                            if (noProgress == NoProgressOutcome.ABORT) {
                                // Cancel owns the terminal state — do NOT persist a re-pause.
                                cancelConversation(conversationId,
                                        ai.labs.eddi.engine.lifecycle.model.ControlSignal.CANCEL_GRACEFUL,
                                        "system:no-progress");
                                return;
                            }
                        }
                        // Persist ONLY while we still own IN_PROGRESS — the state the
                        // AWAITING_HUMAN->IN_PROGRESS CAS set at resume start. The
                        // up-front isCancelled() check above narrows but does not close
                        // the race with a concurrent end/cancel: a terminal write can
                        // land between that check and this store, and an unconditional
                        // full-document replace would then overwrite ENDED/
                        // EXECUTION_INTERRUPTED with READY — resurrecting a terminated
                        // conversation that then accepts new say() input. The atomic
                        // compare-and-store lets the terminal writer win; when it does,
                        // discard the resumed outcome (no persist, no schedule/notify).
                        boolean persisted = storeConversationMemoryIfState(memory, environment, ConversationState.IN_PROGRESS);
                        if (!persisted) {
                            LOGGER.infof("Resume of conversation %s not persisted: a concurrent end/cancel moved it off "
                                    + "IN_PROGRESS — discarding the resumed outcome so the terminal state wins", conversationId);
                            return;
                        }
                        cacheConversationState(conversationId, memory.getConversationState());
                        // Task 10 — AUTO_REJECT no-progress: the re-pause is now durably
                        // persisted (AWAITING_HUMAN); break the loop with a reject-all resume
                        // through the SAME resume path (journal-bearing, no shortcut). The
                        // coordinator serializes this follow-up after the current callable.
                        if (noProgress == NoProgressOutcome.RESUME_REJECT_ALL) {
                            counterHitlPause.increment();
                            counterToolPause(prePauseWasToolCall);
                            issueNoProgressRejectAllResume(conversationId);
                            return;
                        }
                        // #3 (schedule) + metric: a re-pause is a pause
                        if (memory.getConversationState() == ConversationState.AWAITING_HUMAN) {
                            counterHitlPause.increment();
                            counterToolPause(prePauseWasToolCall);
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
            auditHitlDecision(conversationId, agentId, agentVersion, memory.getUserId(), environment, decision,
                    prePauseWasToolCall, prePauseBatch);
            // Task 10 — tool-resume metric, tagged by aggregate verdict.
            if (prePauseWasToolCall) {
                recordToolResumeMetric(decision, prePauseBatch);
            }
        } catch (AgentNotDeployedForResumeException e) {
            // The ONLY IllegalStateException that already removed the in-flight entry
            // and restored the pause above — re-throw as-is (REST 409) WITHOUT a
            // double restore. Any OTHER ISE (e.g. one bubbling out of
            // continueConversation) is NOT this type and falls through to the
            // restore-and-wrap path below, so an unexpected ISE never strands the
            // pause IN_PROGRESS (review carve-out narrowing).
            throw e;
        } catch (ServiceException | InstantiationException | IllegalAccessException | RuntimeException e) {
            // #7 + review: transient OR unexpected failures anywhere between the CAS
            // and submitInOrder (e.g. an unchecked exception — including an unexpected
            // IllegalStateException — from continueConversation) must restore the pause
            // and drop the in-flight registration — otherwise the conversation is left
            // stuck IN_PROGRESS with a leaked registry entry.
            inFlightConversations.remove(conversationId, memory);
            restorePauseAfterFailedResume(conversationId, memory, true);
            throw new ResourceStoreException("Failed to resume conversation: " + e.getLocalizedMessage(), e);
        }
    }

    /**
     * Task 7: validates {@link HitlDecision#getToolDecisions()} against the pending
     * TOOL_CALL batch BEFORE the resume CAS runs. Throws
     * {@link IllegalArgumentException} (the same type
     * {@code GroupConversationService#resumeDiscussion} uses for its taskApprovals
     * validate-before-mutate precedent — {@code RestAgentEngine} maps it to REST
     * 400) on the first violation found; callers must not have mutated any state
     * yet when this is invoked.
     * <p>
     * Semantics: calls not listed in {@code toolDecisions} inherit the top-level
     * {@link HitlDecision#getVerdict()} — they are not required to appear here.
     */
    private void validateToolDecisions(HitlDecision decision, ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot snapshot) {
        Map<String, ToolCallDecision> toolDecisions = decision.getToolDecisions();

        if (!"TOOL_CALL".equals(snapshot.getHitlPauseType())) {
            throw new IllegalArgumentException("toolDecisions is only valid for tool-call pauses");
        }

        PendingToolCallBatch batch = snapshot.getHitlPendingToolCalls();
        List<PendingToolCallBatch.PendingToolCall> pendingCalls = batch != null && batch.getCalls() != null
                ? batch.getCalls()
                : List.of();
        Map<String, PendingToolCallBatch.PendingToolCall> pendingById = new LinkedHashMap<>();
        for (var call : pendingCalls) {
            pendingById.put(call.getCallId(), call);
        }

        // Top-level REJECTED + any per-call APPROVED is contradictory (mirrors the
        // group taskApprovals semantics) — checked up front so it fails regardless
        // of per-call iteration order.
        if (decision.getVerdict() == HitlDecision.HitlVerdict.REJECTED) {
            boolean anyPerCallApproved = toolDecisions.values().stream()
                    .anyMatch(d -> d.getVerdict() == HitlDecision.HitlVerdict.APPROVED);
            if (anyPerCallApproved) {
                throw new IllegalArgumentException(
                        "top-level verdict is REJECTED but toolDecisions contains an APPROVED call; "
                                + "set the top-level verdict to APPROVED to mix per-call outcomes");
            }
        }

        for (var entry : toolDecisions.entrySet()) {
            String callId = entry.getKey();
            ToolCallDecision toolDecision = entry.getValue();

            var pendingCall = pendingById.get(callId);
            if (pendingCall == null) {
                throw new IllegalArgumentException(
                        "no pending tool call '" + callId + "'; pending: " + pendingById.keySet());
            }

            if (toolDecision.getVerdict() == null) {
                throw new IllegalArgumentException(
                        "toolDecisions['" + callId + "'].verdict is required (APPROVED or REJECTED)");
            }

            if (toolDecision.getNote() != null && toolDecision.getNote().length() > ToolCallDecision.MAX_NOTE_LENGTH) {
                throw new IllegalArgumentException(
                        "toolDecisions['" + callId + "'].note exceeds the maximum length of "
                                + ToolCallDecision.MAX_NOTE_LENGTH + " characters");
            }

            String amendedArguments = toolDecision.getAmendedArguments();
            if (amendedArguments != null) {
                if (toolDecision.getVerdict() == HitlDecision.HitlVerdict.REJECTED) {
                    throw new IllegalArgumentException(
                            "toolDecisions['" + callId + "'].amendedArguments is only valid for an APPROVED call");
                }
                if (pendingCall.isArgsTruncated()) {
                    throw new IllegalArgumentException(
                            "call '" + callId + "' was truncated at pause time and cannot be amended; "
                                    + "approve or reject it as-is");
                }
                if (amendedArguments.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > PendingToolCallBatch.AMENDED_ARGS_MAX_BYTES) {
                    throw new IllegalArgumentException(
                            "toolDecisions['" + callId + "'].amendedArguments exceeds the maximum size of "
                                    + PendingToolCallBatch.AMENDED_ARGS_MAX_BYTES + " bytes");
                }
                Object parsed;
                try {
                    parsed = jsonSerialization.deserialize(amendedArguments);
                } catch (Exception e) {
                    throw new IllegalArgumentException(
                            "toolDecisions['" + callId + "'].amendedArguments is not valid JSON: " + e.getMessage());
                }
                if (!(parsed instanceof Map)) {
                    throw new IllegalArgumentException(
                            "toolDecisions['" + callId + "'].amendedArguments must be a JSON object");
                }
            }
        }
    }

    /**
     * Default max consecutive system auto-approvals per turn (mirrors
     * ToolApprovalsConfig doc).
     */
    private static final int DEFAULT_MAX_AUTO_APPROVALS_PER_TURN = 2;

    /**
     * Outcome of the no-progress guard, driving the onComplete persistence branch.
     */
    private enum NoProgressOutcome {
        /** No wedged loop detected — proceed with the normal re-pause. */
        NONE,
        /**
         * WAIT_FOR_HUMAN: the re-pause was demoted to WAIT_INDEFINITELY (no schedule).
         */
        DEMOTED_WAIT,
        /**
         * AUTO_REJECT: persist the re-pause, then resume reject-all
         * (system:no-progress).
         */
        RESUME_REJECT_ALL,
        /** ABORT: cancel the conversation via the existing cancel path. */
        ABORT
    }

    /**
     * Task 10 — no-progress guard. Runs in the resume onComplete AFTER a TOOL_CALL
     * pause has RE-PAUSED in the same turn. Carries the {@code autoApproveCount}
     * across the re-pause and, once the auto-approval budget is spent, applies the
     * configured {@code onNoProgress} policy. A HUMAN decision (decidedBy not
     * {@code system:*}) resets the counter to 0 (group fresh-budget convention).
     * <p>
     * For DEMOTED_WAIT it mutates the live memory bookmark IN PLACE (policy →
     * WAIT_INDEFINITELY) so the subsequent persist + schedule see the demotion; for
     * RESUME_REJECT_ALL / ABORT it records the guard (metric + audit) and returns
     * the outcome for the caller to enact. The new batch's autoApproveCount is
     * always updated so it is persisted with the re-pause.
     */
    private NoProgressOutcome evaluateAndApplyNoProgressGuard(String conversationId, String agentId,
                                                              Integer agentVersion, IConversationMemory memory, Environment environment,
                                                              ai.labs.eddi.engine.lifecycle.model.HitlDecision decision,
                                                              String prePauseFingerprint, int prePauseAutoApproveCount) {
        PendingToolCallBatch newBatch = memory.getHitlPendingToolCalls();
        if (newBatch == null) {
            return NoProgressOutcome.NONE;
        }
        boolean systemDecision = decision.getDecidedBy() != null && decision.getDecidedBy().startsWith("system:");
        boolean sameFingerprint = prePauseFingerprint != null
                && prePauseFingerprint.equals(newBatch.getFingerprint());

        if (!systemDecision) {
            // A human broke into the loop — reset the fresh budget.
            newBatch.setAutoApproveCount(0);
            return NoProgressOutcome.NONE;
        }
        if (!sameFingerprint) {
            // Progress was made (a different batch) — the loop counter does not carry.
            newBatch.setAutoApproveCount(0);
            return NoProgressOutcome.NONE;
        }

        // System decision + identical fingerprint → a wedged loop. Carry + increment.
        int carried = prePauseAutoApproveCount + 1;
        newBatch.setAutoApproveCount(carried);

        // Fix #1: resolve max-auto-approvals + onNoProgress from the config that gated
        // the RE-PAUSE batch (task-scoped override when present), falling back to the
        // agent-level default for a legacy/null-field batch.
        ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig noProgressCfg = newBatch.getEffectiveToolApprovals() != null
                ? newBatch.getEffectiveToolApprovals()
                : memory.getAgentToolApprovalsConfig();
        int maxAutoApprovals = resolveMaxAutoApprovals(noProgressCfg);
        boolean hardThreshold = carried >= 2;
        boolean capReached = carried >= maxAutoApprovals;
        boolean budgetSpent = hardThreshold || capReached;
        if (!budgetSpent) {
            // Still within budget — allow the re-pause to proceed with the carried count.
            return NoProgressOutcome.NONE;
        }

        // The auto-approval CAP being reached is its own guard signal (distinct from
        // the
        // fixed >=2 no-progress threshold) — record it so operators can tell a
        // config-limited stop from the built-in loop breaker.
        if (capReached) {
            recordGuard("auto_approve_cap");
            auditGuard(conversationId, agentId, agentVersion, memory.getUserId(), environment,
                    "auto_approve_cap", newBatch.getFingerprint(), "system:no-progress");
        }

        String onNoProgress = resolveOnNoProgress(noProgressCfg);
        switch (onNoProgress) {
            case "AUTO_REJECT" -> {
                recordGuard("no_progress");
                auditGuard(conversationId, agentId, agentVersion, memory.getUserId(), environment,
                        "no_progress", newBatch.getFingerprint(), "system:no-progress");
                return NoProgressOutcome.RESUME_REJECT_ALL;
            }
            case "ABORT" -> {
                recordGuard("no_progress");
                auditGuard(conversationId, agentId, agentVersion, memory.getUserId(), environment,
                        "no_progress", newBatch.getFingerprint(), "system:no-progress");
                return NoProgressOutcome.ABORT;
            }
            default -> {
                // WAIT_FOR_HUMAN (default): demote the new pause so no finite timeout can
                // auto-decide it again — a human MUST break the loop.
                memory.setHitlTimeoutPolicy(HitlTimeoutPolicy.WAIT_INDEFINITELY);
                recordGuard("no_progress");
                auditGuard(conversationId, agentId, agentVersion, memory.getUserId(), environment,
                        "no_progress", newBatch.getFingerprint(), "system:no-progress");
                return NoProgressOutcome.DEMOTED_WAIT;
            }
        }
    }

    /**
     * Effective max consecutive system auto-approvals per turn (default 2, clamped
     * 0..10).
     */
    private static int resolveMaxAutoApprovals(ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig cfg) {
        if (cfg == null || cfg.getMaxAutoApprovalsPerTurn() == null) {
            return DEFAULT_MAX_AUTO_APPROVALS_PER_TURN;
        }
        return Math.max(0, Math.min(10, cfg.getMaxAutoApprovalsPerTurn()));
    }

    /** Effective onNoProgress policy (WAIT_FOR_HUMAN default). */
    private static String resolveOnNoProgress(ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig cfg) {
        if (cfg == null || isNullOrEmpty(cfg.getOnNoProgress())) {
            return "WAIT_FOR_HUMAN";
        }
        return cfg.getOnNoProgress();
    }

    /**
     * Issues the reject-all follow-up resume that breaks a no-progress loop. Routed
     * through the standard {@link #resumeConversation} path (journal-bearing — no
     * shortcut) with {@code decidedBy=system:no-progress}; the coordinator
     * serializes it after the current callable. Best-effort: a failure is logged,
     * leaving the (now WAIT_INDEFINITELY, since we could not break it) pause for a
     * human.
     */
    private void issueNoProgressRejectAllResume(String conversationId) {
        var reject = new ai.labs.eddi.engine.lifecycle.model.HitlDecision();
        reject.setVerdict(ai.labs.eddi.engine.lifecycle.model.HitlDecision.HitlVerdict.REJECTED);
        reject.setDecidedBy("system:no-progress");
        reject.setNote("Automatic reject-all: repeated identical tool-call pause made no progress");
        try {
            resumeConversation(conversationId, reject, null);
        } catch (Exception e) {
            LOGGER.warnf("No-progress reject-all resume failed for %s: %s — the pause remains for a human",
                    conversationId, e.getMessage());
        }
    }

    /**
     * Increments the tool-pause metric alongside the generic pause counter (Task
     * 10).
     */
    private void counterToolPause(boolean toolCallPause) {
        if (toolCallPause) {
            meterRegistry.counter("eddi_hitl_tool_pause_count").increment();
        }
    }

    /** Task 10 — increments the per-guard metric tagged by guard name. */
    private void recordGuard(String guard) {
        meterRegistry.counter("eddi_hitl_tool_guard_count", "guard", guard).increment();
    }

    /**
     * Task 10 — writes a per-guard audit-ledger entry ({@code hitl.tool.<guard>})
     * recording the guard name, the fingerprint (for no_progress), and
     * {@code decidedBy}. Mirrors {@link #auditHitlDecision}'s HMAC/context; the raw
     * arguments are NEVER included (only the fingerprint).
     */
    private void auditGuard(String conversationId, String agentId, Integer agentVersion, String userId,
                            Environment environment, String guard, String fingerprint, String decidedBy) {
        if (!auditLedgerService.isEnabled()) {
            return;
        }
        try {
            var detail = new LinkedHashMap<String, Object>();
            detail.put("guard", guard);
            detail.put("decidedBy", decidedBy != null ? decidedBy : "unknown");
            detail.put("automated", decidedBy != null && decidedBy.startsWith("system:"));
            if (fingerprint != null) {
                detail.put("fingerprint", fingerprint);
            }
            auditLedgerService.submit(new ai.labs.eddi.engine.audit.model.AuditEntry(
                    UUID.randomUUID().toString(), conversationId, agentId, agentVersion, userId,
                    environment != null ? environment.toString() : null, -1,
                    "hitl.tool." + guard, "hitl", -1, 0L,
                    Map.of(), detail, null, null, List.of(), 0.0,
                    Instant.now(), null, null));
        } catch (Exception e) {
            LOGGER.warnf("Failed to submit HITL tool-guard audit entry for %s: %s", conversationId, e.getMessage());
        }
    }

    /**
     * Submits an {@code hitl.approval} audit entry for a HITL decision. Covers both
     * human decisions and automated timeout decisions
     * ({@code decidedBy=system:timeout}).
     */
    private void auditHitlDecision(String conversationId, String agentId, Integer agentVersion,
                                   String userId, Environment environment,
                                   ai.labs.eddi.engine.lifecycle.model.HitlDecision decision,
                                   boolean toolCallPause, PendingToolCallBatch pendingBatch) {
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
            // Task 10 — TOOL_CALL pauses carry a per-call decision summary. Each entry
            // records the callId, resolved verdict, whether the args were amended, and
            // the tool name — plus a SHA-256 argsDigest so the ledger can prove WHICH
            // arguments were approved WITHOUT ever storing the raw (possibly sensitive)
            // arguments.
            if (toolCallPause) {
                detail.put("pauseType", ConversationPauseException.PauseOrigin.TOOL_CALL.name());
                detail.put("toolDecisions", buildToolDecisionSummary(decision, pendingBatch));
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
     * Task 10 — builds the {@code toolDecisions} audit summary for a TOOL_CALL
     * resume: one entry per gated call with {@code callId}, resolved
     * {@code verdict}, {@code amended} flag, {@code toolName}, and a per-call
     * SHA-256 {@code argsDigest}. The digest is over the raw arguments the decision
     * applies to (the amended arguments when a per-call amendment was supplied,
     * otherwise the persisted raw args) so the ledger can bind a decision to
     * exactly the arguments approved — the raw arguments themselves are NEVER
     * written.
     */
    private List<Map<String, Object>> buildToolDecisionSummary(
                                                               ai.labs.eddi.engine.lifecycle.model.HitlDecision decision,
                                                               PendingToolCallBatch pendingBatch) {
        List<Map<String, Object>> summary = new ArrayList<>();
        if (pendingBatch == null || pendingBatch.getCalls() == null) {
            return summary;
        }
        Map<String, ToolCallDecision> perCall = decision.getToolDecisions() != null
                ? decision.getToolDecisions()
                : Map.of();
        for (PendingToolCallBatch.PendingToolCall call : pendingBatch.getCalls()) {
            ToolCallDecision cd = perCall.get(call.getCallId());
            var verdict = cd != null && cd.getVerdict() != null ? cd.getVerdict() : decision.getVerdict();
            String amended = cd != null ? cd.getAmendedArguments() : null;
            String argsForDigest = amended != null ? amended : call.getArgumentsRaw();
            var entry = new LinkedHashMap<String, Object>();
            entry.put("callId", call.getCallId());
            entry.put("verdict", verdict != null ? verdict.name() : "UNKNOWN");
            entry.put("amended", amended != null);
            entry.put("toolName", call.getToolName());
            entry.put("argsDigest", sha256Hex(argsForDigest != null ? argsForDigest : ""));
            summary.add(entry);
        }
        return summary;
    }

    /** SHA-256 hex of the given string (lower-case, 64 chars). */
    private static String sha256Hex(String s) {
        try {
            var md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed present on every JVM; unreachable.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Aggregate verdict for the tool-resume metric: approved | rejected | mixed.
     */
    private void recordToolResumeMetric(ai.labs.eddi.engine.lifecycle.model.HitlDecision decision,
                                        PendingToolCallBatch pendingBatch) {
        String verdict = aggregateVerdict(decision, pendingBatch);
        meterRegistry.counter("eddi_hitl_tool_resume_count", "verdict", verdict).increment();
    }

    /**
     * Resolves the aggregate verdict across all gated calls: {@code approved} when
     * every call resolves APPROVED, {@code rejected} when every call resolves
     * REJECTED, {@code mixed} otherwise (per-call verdicts diverge).
     */
    private static String aggregateVerdict(ai.labs.eddi.engine.lifecycle.model.HitlDecision decision,
                                           PendingToolCallBatch pendingBatch) {
        var top = decision.getVerdict();
        Map<String, ToolCallDecision> perCall = decision.getToolDecisions() != null
                ? decision.getToolDecisions()
                : Map.of();
        if ((pendingBatch == null || pendingBatch.getCalls() == null || pendingBatch.getCalls().isEmpty())
                || perCall.isEmpty()) {
            return top == HitlDecision.HitlVerdict.REJECTED ? "rejected" : "approved";
        }
        boolean anyApproved = false;
        boolean anyRejected = false;
        for (PendingToolCallBatch.PendingToolCall call : pendingBatch.getCalls()) {
            ToolCallDecision cd = perCall.get(call.getCallId());
            var v = cd != null && cd.getVerdict() != null ? cd.getVerdict() : top;
            if (v == HitlDecision.HitlVerdict.REJECTED) {
                anyRejected = true;
            } else {
                anyApproved = true;
            }
        }
        if (anyApproved && anyRejected) {
            return "mixed";
        }
        return anyRejected ? "rejected" : "approved";
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
     * G5: fires {@link HitlResumeCompletedEvent} with a {@code null} verdict and
     * the TERMINAL conversation snapshot after a pending approval is resolved
     * WITHOUT a human decision — cancel/ABORT/end/timeout-abort. Channel observers
     * (Slack, …) render these outcomes on the originating surface just like a
     * resume; the null verdict distinguishes a cancellation/end from an
     * APPROVE/REJECT. Best-effort and fully isolated: any failure loading the
     * snapshot, converting it, or firing is logged and swallowed — the terminal
     * state has already been persisted and must not be affected by delivery-side
     * concerns.
     */
    private void fireHitlResumeCompletedTerminal(String conversationId, String decidedBy) {
        try {
            var stored = conversationMemoryStore.loadConversationMemorySnapshot(conversationId);
            if (stored == null) {
                return; // deleted concurrently — nothing to render
            }
            var memory = convertConversationMemorySnapshot(stored);
            // The column is the source of truth for the state; reflect the terminal
            // state the caller just persisted so observers never see a stale pause.
            var terminalState = conversationMemoryStore.getConversationState(conversationId);
            if (terminalState != null) {
                memory.setConversationState(terminalState);
            }
            var snapshot = convertSimpleConversationMemorySnapshot(memory, false, true, List.of());
            snapshot.setEnvironment(stored.getEnvironment());
            hitlResumeCompletedEvent.fireAsync(new HitlResumeCompletedEvent(
                    conversationId, null, decidedBy, snapshot));
        } catch (Exception e) {
            LOGGER.warnf("Failed to fire terminal HITL resume-completed event for %s: %s", conversationId, e.getMessage());
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
            memory.setHitlTimeoutPolicy(HitlTimeoutPolicy.WAIT_INDEFINITELY);
            AgentConfiguration agentConfig = readAgentConfigPinned(memory.getAgentId(), memory.getAgentVersion());
            if (agentConfig == null)
                return;
            AgentConfiguration.HitlConfig hitlConfig = agentConfig.getHitlConfig();
            if (hitlConfig == null)
                return;
            // Designer-supplied pause reason answers "what am I approving?" in the
            // approval inbox; the generic reason set at pause time stays otherwise.
            // Scope: apply the agent-level override ONLY for a rule pause (null/RULE
            // pause type). A TOOL_CALL pause keeps its tool-specific reason (built at
            // gate time, including the gated tool names) — the agent-level generic
            // reason would erase that context.
            String pauseType = memory.getHitlPauseType();
            boolean isToolCallPause = ConversationPauseException.PauseOrigin.TOOL_CALL.name().equals(pauseType);
            if (!isToolCallPause && !isNullOrEmpty(hitlConfig.getPauseReason())) {
                memory.setHitlPauseReason(hitlConfig.getPauseReason());
            }
            if (isToolCallPause) {
                // Tool-pause timeout policy has its OWN scoping (Task 10): a TOOL_CALL
                // pause resolves from toolApprovals when it carries an explicit override,
                // otherwise it inherits the outer hitlConfig — EXCEPT an inherited
                // AUTO_APPROVE is demoted to WAIT_INDEFINITELY. Auto-approving a gated
                // tool call on a silent timeout is exactly the un-reviewed side effect
                // the gate exists to prevent, so an inherited AUTO_APPROVE (which the
                // designer set for RULE pauses, warned about at save time in Task 3) must
                // NOT auto-execute the tool. An EXPLICIT toolApprovals.timeoutPolicy=
                // AUTO_APPROVE is honored — the designer opted in at the tool level.
                applyEffectiveToolTimeoutPolicy(memory, hitlConfig);
                return;
            }
            if (hitlConfig.getTimeoutPolicy() == null)
                return;
            memory.setHitlTimeoutPolicy(hitlConfig.getTimeoutPolicy());
            memory.setHitlApprovalTimeout(hitlConfig.getApprovalTimeout());
        } catch (Exception e) {
            LOGGER.warnf("Could not populate HITL timeout bookmark for %s: %s",
                    memory.getConversationId(), e.getMessage());
        }
    }

    /**
     * Task 10 — resolves the EFFECTIVE tool-pause timeout policy and writes it into
     * the memory bookmark. Precedence:
     * <ol>
     * <li>an explicit {@code toolApprovals.timeoutPolicy} (with its own
     * {@code approvalTimeout}) wins verbatim — an explicit AUTO_APPROVE is
     * honored;</li>
     * <li>otherwise inherit the outer {@code hitlConfig.timeoutPolicy}/
     * {@code approvalTimeout}, but demote an inherited {@code AUTO_APPROVE} to
     * {@code WAIT_INDEFINITELY} so a silent timeout never auto-executes a gated
     * tool call.</li>
     * </ol>
     * Absent config on both levels leaves the default WAIT_INDEFINITELY already set
     * by the caller.
     */
    private void applyEffectiveToolTimeoutPolicy(IConversationMemory memory, AgentConfiguration.HitlConfig hitlConfig) {
        // Fix #1: the TOOL-LEVEL source is the config that ACTUALLY gated the paused
        // batch — the task-level override rides on the batch (persisted by
        // AgentOrchestrator.buildPendingBatch). Prefer it; fall back to the
        // agent-level hitlConfig.toolApprovals for a legacy batch (null field) or a
        // null batch. The inherit-from-outer fallback and the AUTO_APPROVE-demotion
        // below still read the OUTER hitlConfig — Task 10 semantics unchanged.
        ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig toolApprovals = effectiveToolApprovals(memory, hitlConfig);
        HitlTimeoutPolicy effectivePolicy;
        String effectiveTimeout;
        if (toolApprovals != null && toolApprovals.getTimeoutPolicy() != null) {
            // Explicit tool-level override — honored verbatim (AUTO_APPROVE included).
            effectivePolicy = toolApprovals.getTimeoutPolicy();
            effectiveTimeout = !isNullOrEmpty(toolApprovals.getApprovalTimeout())
                    ? toolApprovals.getApprovalTimeout()
                    : hitlConfig.getApprovalTimeout();
        } else {
            // Inherit the outer policy, demoting AUTO_APPROVE to WAIT_INDEFINITELY.
            effectivePolicy = hitlConfig.getTimeoutPolicy();
            if (effectivePolicy == HitlTimeoutPolicy.AUTO_APPROVE) {
                effectivePolicy = HitlTimeoutPolicy.WAIT_INDEFINITELY;
            }
            // A tool-level approvalTimeout may still be set even without a tool-level
            // policy — prefer it, else inherit the outer timeout.
            effectiveTimeout = toolApprovals != null && !isNullOrEmpty(toolApprovals.getApprovalTimeout())
                    ? toolApprovals.getApprovalTimeout()
                    : hitlConfig.getApprovalTimeout();
        }
        if (effectivePolicy == null) {
            effectivePolicy = HitlTimeoutPolicy.WAIT_INDEFINITELY;
        }
        memory.setHitlTimeoutPolicy(effectivePolicy);
        memory.setHitlApprovalTimeout(effectiveTimeout);
    }

    /**
     * Fix #1: resolves the TASK-SCOPED effective tool-approval config for a paused
     * batch. Returns the config the batch carries (persisted at gate time — the
     * task-level override when the paused task set one, else the agent-level
     * default). Falls back to the agent-level {@code hitlConfig.toolApprovals} when
     * the batch is null (never populated) or carries a null effective config (a
     * legacy pre-fix batch) — so those paths resolve EXACTLY as before the fix.
     */
    private static ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig effectiveToolApprovals(
                                                                                              IConversationMemory memory,
                                                                                              AgentConfiguration.HitlConfig hitlConfig) {
        PendingToolCallBatch batch = memory.getHitlPendingToolCalls();
        if (batch != null && batch.getEffectiveToolApprovals() != null) {
            return batch.getEffectiveToolApprovals();
        }
        return hitlConfig != null ? hitlConfig.getToolApprovals() : null;
    }

    /**
     * Carries the agent-level {@code hitlConfig.toolApprovals} config onto the live
     * memory as a transient carrier (never persisted) so the tool-approval gate in
     * {@code LlmTask}/{@code AgentOrchestrator} can resolve its effective config
     * before the pipeline runs. Reads the PINNED agent version (parity with
     * {@link #populateHitlTimeoutBookmark}). Absent config leaves the carrier null
     * (gate inert — byte-identical to the pre-HITL path).
     */
    private void populateToolApprovalsConfig(IConversationMemory memory) {
        try {
            AgentConfiguration agentConfig = readAgentConfigPinned(memory.getAgentId(), memory.getAgentVersion());
            if (agentConfig == null || agentConfig.getHitlConfig() == null) {
                memory.setAgentToolApprovalsConfig(null);
                return;
            }
            memory.setAgentToolApprovalsConfig(agentConfig.getHitlConfig().getToolApprovals());
        } catch (Exception e) {
            LOGGER.warnf("Could not populate tool-approval config for %s: %s",
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
     * Minimum delay before a past-due re-armed timeout fires (mirrors crash
     * recovery).
     */
    private static final Duration HITL_REARM_GRACE = Duration.ofMinutes(2);

    /**
     * Creates a one-shot schedule that fires the {@link HitlTimeoutHandler} when
     * the configured approval timeout expires. Reads the policy from the memory's
     * HITL BOOKMARK (populated by {@link #populateHitlTimeoutBookmark} just before
     * — single config resolution per pause, and bookmark and schedule can never
     * diverge). No-ops without a finite policy + timeout.
     * <p>
     * G7: the deadline is anchored to the ORIGINAL pause time ({@code pausedAt +
     * timeout}) when the bookmark carries a pausedAt — so a
     * restore-after-failed-resume re-arms at the same absolute due time
     * approval-status reports, instead of silently extending it by another full
     * timeout. A past-due deadline is clamped to {@code now + grace} (mirrors crash
     * recovery's rearmSchedule). A fresh pause has pausedAt ≈ now, so this reduces
     * to now + timeout.
     */
    private void scheduleHitlTimeout(String conversationId, IConversationMemory memory) {
        try {
            String timeoutStr = memory.getHitlApprovalTimeout();
            HitlTimeoutPolicy policy = memory.getHitlTimeoutPolicy();
            if (timeoutStr == null || timeoutStr.isBlank() || policy == null
                    || policy == HitlTimeoutPolicy.WAIT_INDEFINITELY) {
                return;
            }

            Duration timeout = Duration.parse(timeoutStr);
            Instant pausedAt = memory.getHitlPausedAt();
            Instant now = Instant.now();
            Instant fireAt = pausedAt != null ? pausedAt.plus(timeout) : now.plus(timeout);
            // Clamp ONLY a past-due deadline (a restore-after-failed-resume or crash
            // recovery re-arm whose original pausedAt+timeout already elapsed) up to a
            // small grace window. A FRESH pause has pausedAt ≈ now, so fireAt is in the
            // future and must be honored as configured — clamping it to the grace floor
            // would silently raise any approvalTimeout shorter than the grace (e.g.
            // PT30S, which HitlConfigValidation explicitly permits) to 2 minutes.
            if (fireAt.isBefore(now)) {
                fireAt = now.plus(HITL_REARM_GRACE);
            }

            var schedule = new ScheduleConfiguration();
            schedule.setName(HitlSchedules.regularTimeoutScheduleName(conversationId));
            schedule.setAgentId(memory.getAgentId());
            schedule.setOneTimeAt(fireAt.toString());
            schedule.setEnabled(true);
            schedule.setNextFire(fireAt);
            schedule.setCreatedAt(Instant.now());
            schedule.setMetadata(Map.of(
                    HitlSchedules.METADATA_TYPE_KEY, HitlSchedules.METADATA_TYPE_TIMEOUT,
                    HitlSchedules.METADATA_POLICY_KEY, policy.name(),
                    HitlSchedules.METADATA_SURFACE_KEY, HitlSchedules.SURFACE_REGULAR,
                    HitlSchedules.METADATA_CONVERSATION_ID_KEY, conversationId));
            scheduleStore.createSchedule(schedule);
            LOGGER.infof("Scheduled HITL timeout for conversation %s at %s (policy: %s)",
                    sanitize(conversationId), fireAt, policy.name());
        } catch (Exception e) {
            LOGGER.warnf("Failed to schedule HITL timeout for conversation %s: %s",
                    sanitize(conversationId), e.getMessage());
        }
    }

    /**
     * Deletes any existing HITL timeout schedule for the given conversation. Called
     * on resume and cancel to prevent stale fires and duplicate schedules.
     */
    private void deleteHitlTimeoutSchedule(String conversationId) {
        try {
            int deleted = scheduleStore.deleteSchedulesByName(HitlSchedules.regularTimeoutScheduleName(conversationId));
            if (deleted > 0) {
                LOGGER.infof("Cleaned up %d HITL timeout schedule(s) for conversation %s", deleted, sanitize(conversationId));
            }
        } catch (Exception e) {
            LOGGER.warnf("Failed to delete HITL timeout schedule for conversation %s: %s",
                    sanitize(conversationId), e.getMessage());
        }
    }
}
