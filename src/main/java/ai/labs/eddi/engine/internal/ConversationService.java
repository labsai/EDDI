/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.datastore.IResourceStore.ResourceNotFoundException;
import ai.labs.eddi.datastore.IResourceStore.ResourceStoreException;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.attachments.IAttachmentStore;
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
import ai.labs.eddi.engine.lifecycle.model.ControlSignal;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import ai.labs.eddi.engine.memory.ConversationLogGenerator;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.IPropertiesHandler;
import ai.labs.eddi.engine.memory.descriptor.IConversationDescriptorStore;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.engine.model.InputData;
import ai.labs.eddi.engine.model.PendingApprovalSummary;
import ai.labs.eddi.engine.runtime.IAgent;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.engine.runtime.IConversationCoordinator;
import ai.labs.eddi.engine.runtime.IDiscardableTask;
import ai.labs.eddi.engine.runtime.IRuntime;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.engine.runtime.service.ServiceException;
import ai.labs.eddi.engine.runtime.IConversationSetup;
import ai.labs.eddi.engine.runtime.internal.GracefulShutdownService;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.security.ConversationAccessGuard;
import jakarta.enterprise.context.ContextNotActiveException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
    final IContextLogger contextLogger;
    final CallerIdentityContext callerIdentityContext;
    private final AuditLedgerService auditLedgerService;
    private final GdprComplianceService gdprComplianceService;
    private final TenantQuotaService tenantQuotaService;
    private final int agentTimeout;
    private final IConversationSetup conversationSetup;
    private final IScheduleStore scheduleStore;
    private final IAgentStore agentStore;
    private final IJsonSerialization jsonSerialization;
    private final ICache<String, ConversationState> conversationStateCache;

    // Field-injected so the numerous direct-construction unit tests need no change;
    // used only to resolve stored-attachment metadata at conversation init.
    @Inject
    IAttachmentStore attachmentStore;

    @ConfigProperty(name = "eddi.attachments.max-per-turn", defaultValue = "5")
    int maxAttachmentsPerTurn;

    /**
     * Conversation-ownership gate for the conversationId-only entry points — the
     * ones every external adapter (REST, streaming SSE, MCP) funnels through.
     * <p>
     * The check lives HERE, not only in the adapters, so a new adapter cannot
     * re-open the hole by forgetting it: driving a turn executes under the TARGET
     * conversation's userId (its long-term memories are loaded into the prompt and
     * its tools run in its context), so an unchecked adapter is an account-takeover
     * primitive for anyone who learns a conversationId. The adapters keep their own
     * check as defence in depth.
     * <p>
     * Field-injected for the same reason as {@link #attachmentStore}: the numerous
     * direct-construction unit tests need no change.
     */
    @Inject
    ConversationAccessGuard conversationAccessGuard;

    /**
     * Graceful-shutdown gate (B3). New turns are refused once a
     * {@code ShutdownEvent} has been observed, so a rolling deploy drains what is
     * already in flight instead of racing fresh work against the JVM exit.
     * <p>
     * Field-injected for the same reason as {@link #attachmentStore}: the numerous
     * direct-construction unit tests need no change.
     */
    @Inject
    GracefulShutdownService gracefulShutdownService;

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
    final Counter counterHitlPause;
    private final Counter counterHitlResume;
    // Task 10 — tool-level HITL metrics registry (new meters, never re-tag
    // existing).
    private final MeterRegistry meterRegistry;

    /**
     * Number of turns currently being processed on this pod — the backing value of
     * the {@code eddi_processing_conversation_count} gauge.
     * <p>
     * C11: this used to be a {@code CopyOnWriteArrayList} of
     * {@code agentId:conversationId} strings, mutated twice per turn purely to feed
     * the gauge. That reference string is IDENTICAL for two concurrent turns on the
     * same conversation, so a failing turn deleted a HEALTHY concurrent turn's
     * entry; and a turn that never reached its completion consumer (watchdog
     * timeout, inner future cancelled before starting) leaked its entry for the
     * JVM's lifetime. A counter released exactly once per turn from a
     * {@code finally} can do neither.
     */
    private final AtomicInteger processingConversationCount = new AtomicInteger();

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
            MeterRegistry meterRegistry, Event<HitlResumeCompletedEvent> hitlResumeCompletedEvent, CallerIdentityContext callerIdentityContext,
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
        this.callerIdentityContext = callerIdentityContext;
        this.auditLedgerService = auditLedgerService;
        this.gdprComplianceService = gdprComplianceService;
        this.tenantQuotaService = tenantQuotaService;
        this.agentTimeout = agentTimeout;
        this.hitlResumeCompletedEvent = hitlResumeCompletedEvent;

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

        meterRegistry.gauge("eddi_processing_conversation_count", Tags.empty(), processingConversationCount, AtomicInteger::doubleValue);

        // Constructed here rather than in a @PostConstruct: ~34 test classes build
        // this service with `new` and never run the CDI lifecycle, so a
        // container-initialised collaborator would be null for every one of them —
        // which is exactly what the plan's rule 3.0-4 (collaborators are plain
        // classes the facade constructs) exists to avoid. Everything it needs is a
        // constructor parameter or a field initializer, so there is nothing to wait
        // for. Passing `this` is safe: the constructor only stores it.
        this.conversationStepRunner = new ConversationStepRunner(this, conversationMemoryStore,
                conversationDescriptorStore, runtime, conversationStateCache, inFlightConversations, agentTimeout);
        this.conversationHitlService = new ConversationHitlService(this, conversationMemoryStore,
                conversationCoordinator, runtime, contextLogger, callerIdentityContext, auditLedgerService,
                scheduleStore, agentStore, jsonSerialization, hitlResumeCompletedEvent, counterHitlPause,
                counterHitlResume, meterRegistry, inFlightConversations);
    }

    /**
     * One-shot release token for the in-flight-turn gauge (C11). Created when a
     * turn is admitted, released exactly once no matter which of the many exit
     * paths the turn takes — completion, skip, watchdog timeout, pipeline error or
     * a pre-submission throw. Idempotent, so the turn callable's {@code finally}
     * can act as a safety net behind the normal completion path.
     */
    static final class ProcessingTurn {
        private final AtomicInteger counter;
        private final AtomicBoolean released = new AtomicBoolean(false);

        private ProcessingTurn(AtomicInteger counter) {
            this.counter = counter;
            counter.incrementAndGet();
        }

        void release() {
            if (released.compareAndSet(false, true)) {
                counter.decrementAndGet();
            }
        }
    }

    /**
     * Rejects new work once {@link GracefulShutdownService} has observed a
     * {@code ShutdownEvent} (B3). Turns already queued or in flight are drained by
     * the shutdown observer; admitting new ones during the drain would either be
     * dropped by the JVM exit or extend the drain indefinitely.
     * <p>
     * Applied on every entry point that enqueues a turn: startConversation, say,
     * sayStreaming AND resumeConversation.
     * <p>
     * A {@code null} gate means the bean was constructed outside CDI (only the
     * direct-construction unit tests do that) and never rejects.
     */
    /** The HITL half, extracted to {@link ConversationHitlService} (R3 step 1). */
    final ConversationHitlService conversationHitlService;

    /** Turn execution, extracted to {@link ConversationStepRunner} (R3 step 2). */
    private final ConversationStepRunner conversationStepRunner;

    void rejectIfShuttingDown() {
        if (gracefulShutdownService != null && gracefulShutdownService.isShuttingDown()) {
            throw new RejectedExecutionException(
                    "This node is shutting down and no longer accepts new conversation turns — retry against another node");
        }
    }

    @Override
    public ConversationResult startConversation(Environment environment, String agentId, String userId, Map<String, Context> context)
            throws AgentNotReadyException, ResourceStoreException, ResourceNotFoundException {

        long startTime = System.nanoTime();
        checkNotNull(environment, "environment");
        checkNotNull(agentId, "agentId");
        rejectIfShuttingDown();
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
                conversationHitlService.populateHitlTimeoutBookmark(conversationMemory);
            }
            var conversationId = storeConversationMemory(conversationMemory, environment);
            cacheConversationState(conversationId, conversationMemory.getConversationState());
            if (conversationMemory.getConversationState() == ConversationState.AWAITING_HUMAN) {
                counterHitlPause.increment();
                conversationHitlService.scheduleHitlTimeout(conversationId, conversationMemory);
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
        conversationHitlService.deleteHitlTimeoutSchedule(conversationId);
        if (previousState == ConversationState.AWAITING_HUMAN) {
            // G4: an end that terminates a pending approval is an oversight decision
            // too — audit it with the actor (EU AI Act; parity with cancel) so every
            // pause-terminating path is attributed. G5: notify channel observers
            // (Slack, …) with a null verdict + terminal snapshot so the originating
            // surface can render the outcome (mirrors cancel/timeout).
            conversationHitlService.auditHitlCancellation(conversationId, null, endedBy);
            conversationHitlService.fireHitlResumeCompletedTerminal(conversationId, endedBy);
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
        rejectIfShuttingDown();
        // Assigned inside the try; the catch blocks need it, and the lambdas below
        // need an effectively-final alias (processingTurn).
        ProcessingTurn admittedTurn = null;
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

            admittedTurn = new ProcessingTurn(processingConversationCount);
            final ProcessingTurn processingTurn = admittedTurn;

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
                        processingTurn.release();
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
                processingTurn.release();
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
                    executeConversation, notifySkipped, processingTurn);

            conversationCoordinator.submitInOrder(conversationId, processUserInput);
        } catch (ProcessingRestrictedException | QuotaExceededException | ConversationAwaitingApprovalException e) {
            releaseTurn(admittedTurn); // all three are thrown before the turn is admitted
            throw e;
        } catch (AgentMismatchException | AgentNotReadyException | ConversationEndedException e) {
            releaseTurn(admittedTurn);
            throw e;
        } catch (Exception e) {
            LOGGER.error(e.getLocalizedMessage(), e);
            releaseTurn(admittedTurn);
            throw e;
        }
    }

    static void releaseTurn(ProcessingTurn turn) {
        if (turn != null) {
            turn.release();
        }
    }

    @Override
    public void sayStreaming(Environment environment, String agentId, String conversationId, Boolean returnDetailed, Boolean returnCurrentStepOnly,
                             List<String> returningFields, InputData inputData, StreamingResponseHandler streamingHandler)
            throws Exception {

        long startTime = System.nanoTime();
        rejectIfShuttingDown();
        // See say(): assigned inside the try, aliased for the lambdas below.
        ProcessingTurn admittedTurn = null;
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

            admittedTurn = new ProcessingTurn(processingConversationCount);
            final ProcessingTurn processingTurn = admittedTurn;

            // Create event sink that delegates to the streaming handler
            var eventSink = new ConversationEventSink() {
                @Override
                public void onTaskStart(TaskId taskId, String taskType, int index) {
                    streamingHandler.onTaskStart(taskId, taskType, index);
                }

                @Override
                public void onToolCall(String toolName) {
                    streamingHandler.onToolCall(toolName);
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
                public void onCascadeStepStart(int stepIndex, String modelType, String modelName, int totalSteps) {
                    streamingHandler.onCascadeStepStart(stepIndex, modelType, modelName, totalSteps);
                }

                @Override
                public void onCascadeEscalation(int fromStep, int toStep, double confidence, double threshold, String reason, long durationMs) {
                    streamingHandler.onCascadeEscalation(fromStep, toStep, confidence, threshold, reason, durationMs);
                }

                @Override
                public void onComplete() {
                    // Handled separately after memory conversion
                }

                @Override
                public void onError(Throwable error) {
                    streamingHandler.onError(error);
                }

                @Override
                public void onTaskFailed(TaskId taskId, String taskType, long durationMs,
                                         String errorType, String errorSummary) {
                    streamingHandler.onTaskFailed(taskId, taskType, durationMs, errorType, errorSummary);
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
                        processingTurn.release();
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
                processingTurn.release();
                streamingHandler.onSkipped(memorySnapshot);
            };

            Callable<Void> processUserInput = processConversationStep(environment, conversationMemory, conversationId, loggingContext,
                    executeConversation, notifySkipped, processingTurn);

            conversationCoordinator.submitInOrder(conversationId, processUserInput);
        } catch (ProcessingRestrictedException | QuotaExceededException | ConversationAwaitingApprovalException e) {
            releaseTurn(admittedTurn); // all three are thrown before the turn is admitted
            throw e;
        } catch (AgentMismatchException | AgentNotReadyException | ConversationEndedException e) {
            releaseTurn(admittedTurn);
            throw e;
        } catch (Exception e) {
            LOGGER.error(e.getLocalizedMessage(), e);
            releaseTurn(admittedTurn);
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

        requireConversationAccess(conversationId);
        var snapshot = conversationMemoryStore.loadConversationMemorySnapshot(conversationId);
        say(snapshot.getEnvironment(), snapshot.getAgentId(), conversationId, returnDetailed, returnCurrentStepOnly, returningFields, inputData,
                rerunOnly, responseHandler);
    }

    @Override
    public void sayStreaming(String conversationId, Boolean returnDetailed, Boolean returnCurrentStepOnly, List<String> returningFields,
                             InputData inputData, StreamingResponseHandler streamingHandler)
            throws Exception {

        requireConversationAccess(conversationId);
        var snapshot = conversationMemoryStore.loadConversationMemorySnapshot(conversationId);
        sayStreaming(snapshot.getEnvironment(), snapshot.getAgentId(), conversationId, returnDetailed, returnCurrentStepOnly, returningFields,
                inputData, streamingHandler);
    }

    /**
     * Asserts the caller may drive this conversation, throwing
     * {@code ForbiddenException} (403) otherwise. See
     * {@link #conversationAccessGuard} for why the gate sits at this layer.
     * <p>
     * Two deliberate pass-throughs, neither of which weakens an authenticated
     * request:
     * <ul>
     * <li>a {@code null} guard means the bean was constructed outside CDI — only
     * the direct-construction unit tests do that; CDI always injects it in
     * production</li>
     * <li>no active request context means a server-internal caller (e.g. the Slack
     * webhook worker thread) with no principal to compare against — ownership is
     * simply not decidable there, and it was never checked before. Every
     * request-scoped caller (REST, streaming, MCP) still is.</li>
     * </ul>
     */
    private void requireConversationAccess(String conversationId) {
        if (conversationAccessGuard == null) {
            return;
        }
        try {
            conversationAccessGuard.requireConversationOwner(conversationId);
        } catch (ContextNotActiveException e) {
            LOGGER.debugf("No active request context — skipping ownership check for conversation %s", sanitize(conversationId));
        }
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

            @Override
            public IAttachmentStore getAttachmentStore() {
                return attachmentStore;
            }

            @Override
            public int getMaxAttachmentsPerTurn() {
                return maxAttachmentsPerTurn;
            }
        };
    }

    IAgent getAgent(Environment environment, String agentId, Integer agentVersion) throws ServiceException, IllegalAccessException {

        IAgent agent = agentFactory.getAgent(environment, agentId, agentVersion);
        if (agent == null) {
            agentFactory.deployAgent(environment, agentId, agentVersion, null);
            agent = agentFactory.getAgent(environment, agentId, agentVersion);
        }

        return agent;
    }

    // ─── Turn execution ───
    //
    // Bodies moved to ConversationStepRunner (R3 step 2). Declared delegators kept:
    // the characterization suites reach several of these through getDeclaredMethod
    // on this class, and ConversationHitlService calls the rest by name.

    private IDiscardableTask processConversationStep(Environment environment, IConversationMemory conversationMemory, String conversationId,
                                                     Map<String, String> loggingContext, Callable<Void> executeConversation,
                                                     Consumer<IConversationMemory> skipNotifier, ProcessingTurn processingTurn) {
        return conversationStepRunner.processConversationStep(environment, conversationMemory, conversationId,
                loggingContext, executeConversation, skipNotifier, processingTurn);
    }

    void waitForExecutionFinishOrTimeout(Map<String, String> loggingContext, String conversationId, Future<Void> future) {
        conversationStepRunner.waitForExecutionFinishOrTimeout(loggingContext, conversationId, future);
    }

    void logConversationError(Map<String, String> loggingContext, String conversationId, Throwable t) {
        conversationStepRunner.logConversationError(loggingContext, conversationId, t);
    }

    private IConversationMemory loadAndValidateConversationMemory(String agentId, String conversationId)
            throws ResourceStoreException, ResourceNotFoundException, AgentMismatchException {
        return conversationStepRunner.loadAndValidateConversationMemory(agentId, conversationId);
    }

    private IConversationMemory loadConversationMemory(String conversationId) throws ResourceStoreException, ResourceNotFoundException {
        return conversationStepRunner.loadConversationMemory(conversationId);
    }

    private void setConversationState(String conversationId, ConversationState conversationState) {
        conversationStepRunner.setConversationState(conversationId, conversationState);
    }

    @Override
    public void resetConversationState(String conversationId, ConversationState targetState) {
        setConversationState(conversationId, targetState);
    }

    void cacheConversationState(String conversationId, ConversationState conversationState) {
        conversationStepRunner.cacheConversationState(conversationId, conversationState);
    }

    private String storeConversationMemory(IConversationMemory conversationMemory, Environment environment) throws ResourceStoreException {
        return conversationStepRunner.storeConversationMemory(conversationMemory, environment);
    }

    boolean storeConversationMemoryIfState(IConversationMemory conversationMemory, Environment environment,
                                           ConversationState expectedState)
            throws ResourceStoreException {
        return conversationStepRunner.storeConversationMemoryIfState(conversationMemory, environment, expectedState);
    }

    private static void checkConversationMemoryNotNull(IConversationMemory conversationMemory, String conversationId) {
        ConversationStepRunner.checkConversationMemoryNotNull(conversationMemory, conversationId);
    }

    static void validateParams(Environment environment, String agentId, String conversationId) {
        checkNotNull(environment, "environment");
        checkNotNull(agentId, "agentId");
        checkNotNull(conversationId, "conversationId");
    }

    void recordMetrics(Timer timer, Counter counter, long startTime) {
        counter.increment();
        timer.record(System.nanoTime() - startTime, TimeUnit.NANOSECONDS);
    }

    // ─── HITL surface ───
    //
    // Bodies moved to ConversationHitlService (R3 step 1). These stay as declared
    // delegators: cancelConversation, resumeConversation,
    // getConversationMemorySnapshot
    // and listPendingApprovals are IConversationService contract methods, and the
    // cluster's internals are reached by characterization tests through
    // getDeclaredMethod on this class.

    @Override
    public CancelOutcome cancelConversation(String conversationId,
                                            ControlSignal mode,
                                            String cancelledBy)
            throws ResourceStoreException {
        return conversationHitlService.cancelConversation(conversationId, mode, cancelledBy);
    }

    @Override
    public void resumeConversation(String conversationId,
                                   HitlDecision decision,
                                   ConversationResponseHandler handler)
            throws ResourceStoreException, ResourceNotFoundException {
        conversationHitlService.resumeConversation(conversationId, decision, handler);
    }

    @Override
    public ConversationMemorySnapshot getConversationMemorySnapshot(String conversationId)
            throws ResourceStoreException, ResourceNotFoundException {
        return conversationHitlService.getConversationMemorySnapshot(conversationId);
    }

    @Override
    public List<PendingApprovalSummary> listPendingApprovals(int limit)
            throws ResourceStoreException {
        return conversationHitlService.listPendingApprovals(limit);
    }

    @Override
    public List<PendingApprovalSummary> listPendingApprovals(String ownerUserId, int limit)
            throws ResourceStoreException {
        return conversationHitlService.listPendingApprovals(ownerUserId, limit);
    }
}
