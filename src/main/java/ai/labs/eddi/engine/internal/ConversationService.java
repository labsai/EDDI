/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.agents.AgentConfigurationCache;
import ai.labs.eddi.engine.cluster.rpc.IClusterRpc;
import ai.labs.eddi.engine.cluster.lease.IConversationLeaseManager;
import ai.labs.eddi.engine.cluster.KvKeys;
import ai.labs.eddi.engine.internal.groups.LiveDiscussionRegistry;
import ai.labs.eddi.engine.memory.ConversationGroups;
import ai.labs.eddi.engine.memory.ConversationMemory;
import ai.labs.eddi.configs.properties.model.UserMemoryEntry;
import ai.labs.eddi.configs.properties.model.Property.Visibility;
import ai.labs.eddi.configs.properties.model.Property;
import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.datastore.IResourceStore.ResourceNotFoundException;
import ai.labs.eddi.datastore.IResourceStore.ResourceStoreException;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.agenttests.AgentTestConversations;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.attachments.IAttachmentStore;
import ai.labs.eddi.engine.audit.AuditLedgerService;
import ai.labs.eddi.engine.events.HitlPauseCommittedEvent;
import ai.labs.eddi.engine.events.HitlResumeCompletedEvent;
import ai.labs.eddi.engine.gdpr.GdprComplianceService;
import ai.labs.eddi.engine.gdpr.ProcessingRestrictedException;
import ai.labs.eddi.engine.gdpr.ProcessingRestrictionUnavailableException;
import ai.labs.eddi.engine.gdpr.UserErasureParticipant;
import ai.labs.eddi.engine.tenancy.QuotaAccountingUnavailableException;
import ai.labs.eddi.engine.tenancy.QuotaExceededException;
import ai.labs.eddi.engine.tenancy.TenantQuotaService;
import ai.labs.eddi.engine.tenancy.TurnCostMeter;
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
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.MemoryKeys;
import ai.labs.eddi.engine.memory.descriptor.ConversationDescriptorMemo;
import ai.labs.eddi.engine.memory.descriptor.IConversationDescriptorStore;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.engine.model.InputData;
import ai.labs.eddi.modules.nlp.expressions.utilities.ExpressionLimits;
import ai.labs.eddi.modules.nlp.expressions.utilities.ExpressionLimits.ExpressionLimitExceededException;
import ai.labs.eddi.engine.model.TurnError;
import ai.labs.eddi.engine.model.PendingApprovalSummary;
import ai.labs.eddi.configs.shared.TurnDeadline;
import ai.labs.eddi.engine.runtime.IAgent;
import ai.labs.eddi.secrets.sanitize.SecretRedactionFilter;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.engine.runtime.IAgentDeploymentManagement;
import ai.labs.eddi.engine.runtime.IConversationCoordinator;
import ai.labs.eddi.engine.runtime.IDiscardableTask;
import ai.labs.eddi.engine.runtime.IRuntime;
import ai.labs.eddi.engine.security.CallerIdentity;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.engine.security.ResolutionPrincipal;
import ai.labs.eddi.engine.security.ResolutionPrincipalContext;
import ai.labs.eddi.engine.runtime.service.ServiceException;
import ai.labs.eddi.engine.runtime.IConversationSetup;
import ai.labs.eddi.engine.runtime.ITurnAbandonment;
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
import jakarta.enterprise.inject.Instance;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
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
public class ConversationService implements IConversationService, UserErasureParticipant, ITurnAbandonment {

    private static final String RESOURCE_URI = "eddi://ai.labs.conversation/conversationstore/conversations/";

    /**
     * Actor for ending a legacy soft-deleted conversation on its next turn attempt.
     */
    static final String SOFT_DELETED_ACTOR = "system:delete";
    private static final String CACHE_NAME_CONVERSATION_STATE = "conversationState";
    /**
     * How long a cached conversation state is served before the store is asked
     * again. The cache used to keep entries until size eviction, and several
     * writers never touch it — another instance, the HITL timeout and crash
     * recovery paths, a narrow state write — so a stale entry could be served for
     * the lifetime of the process. Short enough to bound that staleness, long
     * enough to absorb a client polling the state of a running turn.
     */
    public static final Duration CONVERSATION_STATE_CACHE_TTL = Duration.ofSeconds(30);
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
     * Longest turn input, in characters, accepted from a caller. {@code <= 0}
     * disables the limit (which is also what a directly constructed instance in a
     * unit test gets). Enforced on the conversationId entry points — REST,
     * streaming, managed conversations, MCP, the OpenAI adapter, Slack — and on
     * A2A, but not on the agent-id overloads the engine itself drives for group
     * members and sub-agents, whose input legitimately carries whole transcripts.
     */
    @ConfigProperty(name = "eddi.conversations.max-input-chars", defaultValue = "200000")
    int maxInputChars;

    /**
     * Bounds for {@code context} entries of type {@code expressions}, applied with
     * {@link #maxInputChars} on the same entry points. Field-injected for the same
     * reason as {@link #attachmentStore}; a directly constructed instance falls
     * back to {@link ExpressionLimits#defaults()}.
     */
    @Inject
    ExpressionLimits expressionLimits;

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
     * Lets {@link #isSoftDeleted} reuse the descriptor the ownership check of the
     * same request read (see {@link ConversationDescriptorMemo}). Field-injected
     * for the same reason as {@link #attachmentStore}; {@code null} means every
     * check reads the store.
     */
    @Inject
    Instance<ConversationDescriptorMemo> descriptorMemoInstance;

    /**
     * Serves the pinned agent configuration {@link ConversationHitlService} reads
     * on every turn from memory instead of the store. Field-injected for the same
     * reason as {@link #attachmentStore}; {@code null} means every read goes to the
     * store.
     */
    @Inject
    AgentConfigurationCache agentConfigurationCache;

    /**
     * Idempotent turns ({@code Idempotency-Key}). Field-injected for the same
     * reason as {@link #attachmentStore}; {@code null} (a directly constructed
     * instance) means the feature is off.
     */
    @Inject
    TurnIdempotencyService turnIdempotency;

    /**
     * Charges each settled turn's priced spend to the tenant's monthly cost counter
     * (see {@link TurnCostMeter}). Field-injected for the same reason as
     * {@link #attachmentStore}; {@code null} (a directly constructed instance)
     * meters nothing.
     */
    @Inject
    TurnCostMeter turnCostMeter;

    /**
     * {@link HitlPauseCommittedEvent}, fired once a pause is durable (see
     * {@link #firePauseCommitted}). Field-injected for the same reason as
     * {@link #attachmentStore}; {@code null} (a directly constructed instance)
     * fires nothing.
     */
    @Inject
    Event<HitlPauseCommittedEvent> hitlPauseCommittedEvent;

    /**
     * Verifies a {@code groupId} found only on an earlier step before group memory
     * is scoped to it (see {@code ConversationGroups}). Field-injected, so directly
     * constructed tests see {@code null} and only the current step's value counts.
     */
    @Inject
    LiveDiscussionRegistry liveDiscussionRegistry;

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
     * Binds {@link ResolutionPrincipal} — the conversation's owner plus how that
     * owner was established — around every turn, so that a credential decision
     * taken deep inside the pipeline reads the CONVERSATION's identity rather than
     * whoever happens to be driving the current HTTP request. On a HITL resume
     * those are different people by design.
     * <p>
     * Field-injected for the same reason as {@link #attachmentStore}: the numerous
     * direct-construction unit tests need no change. A {@code null} context means
     * no principal is ever bound, and every {@code PER_USER} resolution then fails
     * closed — which is the safe direction for a bean built outside CDI.
     */
    @Inject
    ResolutionPrincipalContext resolutionPrincipalContext;

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
    private final Counter counterAgentVersionSwitch;
    final Counter counterHitlPause;
    private final Counter counterHitlResume;
    /**
     * Turns whose persist was refused because another writer had already committed
     * to the same conversation document (an optimistic-concurrency conflict). Every
     * increment here used to be an invisible data loss: the stale write replaced
     * the winner and the turn disappeared with no error, no 409 and nothing in the
     * log.
     */
    final Counter counterConversationStoreConflict;

    /**
     * Whether a dead letter records the turn's input and context so an operator can
     * replay it. Field-injected with its default so the many tests that build this
     * service with {@code new} keep the documented behaviour.
     */
    @ConfigProperty(name = "eddi.coordinator.dead-letter.capture-input", defaultValue = "true")
    boolean captureDeadLetterInput = true;

    /**
     * Turns this node runs at once, queued or executing; one more is refused with
     * 503 {@code capacity_exceeded}. Turns run on virtual threads, so the worker
     * pool no longer bounds them — this does, and it is what keeps memory per
     * in-flight turn times the number of turns inside the heap. {@code auto} (the
     * default) derives it from the maximum heap, see {@link InFlightTurnLimit}; a
     * number sets it, {@code <= 0} disables it. Field-injected with the default in
     * the initializer for the same reason as {@link #captureDeadLetterInput}.
     */
    @ConfigProperty(name = "eddi.conversation.max-in-flight-turns", defaultValue = InFlightTurnLimit.AUTO)
    String maxInFlightTurnsSetting = InFlightTurnLimit.AUTO;

    /**
     * Whether a turn still queued when its caller stopped waiting is dropped
     * instead of run ({@link ProcessingTurn#callerHasGivenUp}). Its caller has its
     * {@code 408} already, so running it spends a model call and the database on an
     * answer nobody receives. Under overload that is every queued turn, which is
     * how goodput fell to zero. Off runs such turns as before.
     */
    @ConfigProperty(name = "eddi.conversation.skip-turns-of-timed-out-callers", defaultValue = "true")
    boolean skipTurnsOfTimedOutCallers = true;

    /** Heap budget per in-flight turn for {@code max-in-flight-turns=auto}. */
    @ConfigProperty(name = "eddi.conversation.in-flight-turn-memory-mb", defaultValue = "2")
    int inFlightTurnMemoryMb = 2;

    /** Marks {@link #maxInFlightTurns} as not resolved yet. */
    static final int LIMIT_UNRESOLVED = Integer.MIN_VALUE;

    /**
     * The resolved limit. Set once from {@link #maxInFlightTurnsSetting}, by the
     * {@code @PostConstruct} under CDI or on first use in tests that construct the
     * service with {@code new}; tests may also set it directly.
     */
    volatile int maxInFlightTurns = LIMIT_UNRESOLVED;
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
     * Turns holding an {@code eddi.conversation.max-in-flight-turns} permit: the
     * gauge's count plus the turns already answered but still persisting. See
     * {@link ProcessingTurn#finish()}.
     */
    private final AtomicInteger inFlightTurnPermits = new AtomicInteger();

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
        this.conversationStateCache = cacheFactory.getCache(CACHE_NAME_CONVERSATION_STATE, CONVERSATION_STATE_CACHE_TTL);
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
        this.counterAgentVersionSwitch = meterRegistry.counter("eddi_conversation_agent_version_switch_count");
        this.counterConversationStoreConflict = meterRegistry.counter("eddi_conversation_store_conflict_count");
        this.counterHitlPause = meterRegistry.counter("eddi_hitl_pause_count", "surface", "regular");
        this.counterHitlResume = meterRegistry.counter("eddi_hitl_resume_count", "surface", "regular");
        // (timeout fires are counted in HitlTimeoutHandler, tagged by surface)
        // Task 10 — tool-level HITL meters are registered lazily via the registry (tags
        // vary per emission: verdict on resume, guard name on guard activation).
        this.meterRegistry = meterRegistry;

        meterRegistry.gauge("eddi_processing_conversation_count", Tags.empty(), processingConversationCount, AtomicInteger::doubleValue);
        // The limit eddi_processing_conversation_count runs into; with the default
        // max-in-flight-turns=auto it depends on the heap, so it is exported rather
        // than left for operators to work out.
        meterRegistry.gauge("eddi.conversation.max_in_flight_turns", Tags.empty(), this, ConversationService::inFlightTurnLimit);

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
        meterRegistry.gauge("eddi_conversation_abandoned_running", Tags.empty(),
                conversationStepRunner.abandonedTurnsRunning, AtomicInteger::doubleValue);
    }

    /**
     * One-shot release token for the in-flight-turn gauge (C11). Created when a
     * turn is admitted, released exactly once no matter which of the many exit
     * paths the turn takes — completion, skip, watchdog timeout, pipeline error or
     * a pre-submission throw. Idempotent, so the turn callable's {@code finally}
     * can act as a safety net behind the normal completion path.
     * <p>
     * It also carries the turn's {@code eddi.conversation.max-in-flight-turns}
     * permit, which has its own, later release. {@link #release()} fires as soon as
     * the client has its answer, but the turn is still running then: it is
     * persisted afterwards, on the same thread. Releasing the permit there let new
     * turns in while earlier ones were still writing, so the node ran past its
     * limit (1029 active at a limit of 1000 in the load test). The permit is
     * returned by {@link #finish()}, which only the turn's own exits call: the turn
     * task's {@code finally}, its discard hook and the pre-submission rollback.
     */
    static final class ProcessingTurn {
        private final AtomicInteger counter;
        private final AtomicInteger capacity;
        private final AtomicBoolean released = new AtomicBoolean(false);
        private final AtomicBoolean finished = new AtomicBoolean(false);
        /** Set when an abandoned body that is still running took over the permit. */
        private final AtomicBoolean permitHeldByBody = new AtomicBoolean(false);
        /**
         * The client-visible completion of a turn that ended AWAITING_HUMAN, held back
         * until the pause is persisted — see {@link DeferredDelivery}.
         */
        final DeferredDelivery deferredDelivery = new DeferredDelivery();
        /**
         * When the caller stops waiting (epoch ms); {@code 0} = it waits for the turn.
         */
        private volatile long callerGivesUpAtEpochMs;
        /** Set when the turn was dropped because its caller had given up. */
        private final AtomicBoolean droppedForCaller = new AtomicBoolean(false);

        /**
         * A token outside any limit (tests build it reflectively). The counter has
         * already been incremented for this turn.
         */
        private ProcessingTurn(AtomicInteger counter) {
            this(counter, new AtomicInteger(1));
        }

        /** Both counters have already been incremented for this turn. */
        private ProcessingTurn(AtomicInteger counter, AtomicInteger capacity) {
            this.counter = counter;
            this.capacity = capacity;
        }

        /**
         * Admits a turn against the in-flight limit, or refuses it. The limit is
         * checked and the permit taken in one CAS, so concurrent admissions can never
         * overshoot it (unlike the coordinator's deliberately soft conversation cap).
         *
         * @param counter
         *            backs {@code eddi_processing_conversation_count}; released early
         *            by {@link #release()}
         * @param capacity
         *            the permits; held until {@link #finish()}
         * @param limit
         *            {@code eddi.conversation.max-in-flight-turns}; {@code <= 0} admits
         *            everything
         */
        static ProcessingTurn admit(AtomicInteger counter, AtomicInteger capacity, int limit) {
            while (true) {
                int current = capacity.get();
                if (limit > 0 && current >= limit) {
                    throw new TurnCapacityExceededException("This node is already running " + limit
                            + " conversation turns (eddi.conversation.max-in-flight-turns) — retry shortly or against another node");
                }
                if (capacity.compareAndSet(current, current + 1)) {
                    counter.incrementAndGet();
                    return new ProcessingTurn(counter, capacity);
                }
            }
        }

        /**
         * The client-visible end of the turn: takes it off the gauge. The capacity
         * permit stays held — see {@link #finish()}.
         */
        void release() {
            if (released.compareAndSet(false, true)) {
                counter.decrementAndGet();
            }
        }

        /**
         * The turn task is done — run, skipped, discarded or never submitted. Releases
         * the gauge if that has not happened yet and gives the capacity permit back,
         * both exactly once.
         */
        void finish() {
            release();
            if (!permitHeldByBody.get()) {
                returnPermit();
            }
        }

        /**
         * The watchdog gave up on a turn whose body is still running (its interrupt was
         * swallowed). That body is still doing work — model calls, tool calls, writes —
         * so it keeps the permit: {@link #finish()} then only drops the gauge, and the
         * body returns the permit through {@link #returnPermit()} when it really stops.
         * Otherwise repeated timeouts would admit new turns on top of work that never
         * stopped. Must be called before the turn task's {@code finish()}.
         */
        void handPermitToBody() {
            permitHeldByBody.set(true);
        }

        /**
         * When the caller stops waiting for this turn's answer; {@code null} or
         * non-positive means it waits for as long as the turn takes.
         */
        void callerGivesUpAt(Long epochMs) {
            callerGivesUpAtEpochMs = epochMs != null && epochMs > 0 ? epochMs : 0;
        }

        /**
         * Whether the caller had stopped waiting by {@code nowEpochMs}. Asked once,
         * when the queued turn is about to run; a {@code true} answer marks the turn as
         * {@linkplain #wasDroppedForCaller() dropped for its caller}.
         */
        boolean callerHasGivenUp(long nowEpochMs) {
            long givesUpAt = callerGivesUpAtEpochMs;
            if (givesUpAt <= 0 || nowEpochMs < givesUpAt) {
                return false;
            }
            droppedForCaller.set(true);
            return true;
        }

        boolean wasDroppedForCaller() {
            return droppedForCaller.get();
        }

        /** Gives the capacity permit back, exactly once. */
        void returnPermit() {
            if (finished.compareAndSet(false, true)) {
                capacity.decrementAndGet();
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

        ProcessingTurn startTurn = null;
        try {
            // GDPR Art. 18 — processing restriction check
            userId = conversationSetup.computeAnonymousUserIdIfEmpty(userId, context.get(USER_ID));
            if (userId != null && gdprComplianceService.isProcessingRestricted(userId)) {
                throw new ProcessingRestrictedException(
                        "Processing is restricted for this user (GDPR Art. 18)");
            }

            IAgent latestAgent = agentFactory.getLatestReadyAgent(environment, agentId);
            if (latestAgent == null) {
                final String requestedAgent = agentId;
                latestAgent = awaitClusterDeployment(environment, agentId, () -> agentFactory.getLatestReadyAgent(environment, requestedAgent));
            }
            if (latestAgent == null) {
                String message = "No version of agent (agentId=%s) ready for interaction (environment=%s)!";
                message = String.format(message, agentId, environment);
                throw new AgentNotReadyException(message);
            }

            // The CONVERSATION_START turn runs inline below and can call an LLM like any
            // other turn, so it takes an in-flight permit too — before the quota slot, as
            // say() does, so a capacity refusal spends no quota. Finished in the finally.
            startTurn = admitTurn("(new conversation of agent " + agentId + ")");

            // Tenant quota — atomic slot acquisition AFTER cheap validations
            // (avoids burning quota on GDPR-restricted or agent-not-ready failures)
            QuotaCheckResult quotaCheck = tenantQuotaService.acquireConversationSlot();
            if (!quotaCheck.allowed()) {
                throw quotaCheck.accountingUnavailable()
                        // A store that could not answer is a 503, not a 429: the
                        // tenant is not over anything, and a client that backs off
                        // for a minute on the strength of a Retry-After is reacting
                        // to the wrong signal.
                        ? new QuotaAccountingUnavailableException(quotaCheck.reason())
                        : new QuotaExceededException(quotaCheck.reason());
            }

            // Decided here, at the only moment it CAN be decided: this is still the
            // request thread, so there is an authenticated caller to compare the
            // conversation's userId against. Every later turn — a say tomorrow, a HITL
            // resume next week — runs on a request that proves nothing about this
            // conversation's owner, which is why the answer is persisted below rather
            // than re-derived.
            // Captured once, here, while this is still the request thread: the same
            // identity decides the principal's provenance below AND runs the start
            // turn. Every later turn is dispatched to a pool thread through
            // ConversationStepRunner, which captures and binds the caller itself; the
            // CONVERSATION_START turn is the one that runs inline, and it used to run
            // with no caller bound at all — so ${caller:token} and every
            // CALLER_SUPPLIED connection failed closed on turn 0 with a message
            // blaming a "scheduled run".
            CallerIdentity startCaller = callerIdentityContext == null ? null : callerIdentityContext.captureOrCurrent();
            ResolutionPrincipal resolutionPrincipal = deriveResolutionPrincipal(userId, startCaller);

            IConversation conversation;
            // Bound around the call, not merely recorded after it: a behavior rule can
            // fire tool calls on the CONVERSATION_START turn, and that turn executes
            // inside startConversation before there is a memory to read anything from.
            ResolutionPrincipal previousPrincipal = currentResolutionPrincipal();
            CallerIdentity previousCaller = callerIdentityContext == null ? null : callerIdentityContext.current();
            bindResolutionPrincipal(resolutionPrincipal);
            bindCallerIdentity(startCaller);
            try {
                conversation = latestAgent.startConversation(userId, context,
                        createPropertiesHandler(userId, latestAgent.getUserMemoryConfig(), latestAgent.isMemoryToolsEnabled()), null);
            } finally {
                // Restore rather than clear — this can be a sub-agent conversation
                // started from inside a parent's pipeline turn, whose bindings must
                // survive.
                bindResolutionPrincipal(previousPrincipal);
                bindCallerIdentity(previousCaller);
            }

            var conversationMemory = conversation.getConversationMemory();
            conversationMemory.setResolutionProvenance(resolutionPrincipal.provenance());
            // Settle point of the CONVERSATION_START turn, which runs inline and never
            // reaches admitTurn's completion callback (an LLM greeting costs too).
            meterTurnCost(conversationMemory);
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

            createDescriptorOrDiscard(agentId, latestAgent, userId, conversationId, conversationUri);
            // Announced only once the conversation is final: a failed descriptor write
            // discards it again, and an observer (the email notifier) must not have
            // been told about a pause that no longer exists.
            if (conversationMemory.getConversationState() == ConversationState.AWAITING_HUMAN) {
                firePauseCommitted(conversationId, conversationMemory);
            }

            return new ConversationResult(conversationId, conversationUri);
        } catch (AgentNotReadyException e) {
            throw e;
        } catch (ServiceException | InstantiationException | LifecycleException | IllegalAccessException e) {
            contextLogger.setLoggingContext(contextLogger.createLoggingContext(environment, agentId, null, userId));
            LOGGER.error(e.getLocalizedMessage(), e);
            throw new ResourceStoreException(e.getLocalizedMessage(), e);
        } finally {
            releaseTurn(startTurn);
            recordMetrics(timerConversationStart, counterConversationStart, startTime);
        }
    }

    /**
     * Writes the new conversation's descriptor, and removes the just-stored memory
     * again if that fails.
     * <p>
     * The descriptor is where the conversation's owner is recorded, and it can only
     * be written after the memory is stored (the store assigns the id). If it
     * failed, the caller got an error but the snapshot stayed behind with no owner
     * on record: a conversation nobody could be checked against. Discarding it
     * keeps "every conversation has a descriptor" true, which is what
     * {@code ConversationAccessGuard} relies on to deny everyone but admins access
     * to a conversation without one.
     */
    private void createDescriptorOrDiscard(String agentId, IAgent latestAgent, String userId, String conversationId, URI conversationUri)
            throws ResourceStoreException, ResourceNotFoundException {
        try {
            conversationSetup.createConversationDescriptor(agentId, latestAgent, userId, conversationId, conversationUri);
        } catch (ResourceStoreException | ResourceNotFoundException | RuntimeException e) {
            LOGGER.errorf("Could not write the descriptor of new conversation %s — discarding its memory: %s",
                    sanitize(conversationId), e.getMessage());
            try {
                // A pause on the CONVERSATION_START turn armed a timeout already.
                conversationHitlService.deleteHitlTimeoutSchedule(conversationId);
                conversationMemoryStore.deleteConversationMemorySnapshot(conversationId);
                conversationStateCache.remove(conversationId);
            } catch (Exception cleanupFailure) {
                LOGGER.errorf(cleanupFailure, "Could not discard the memory of conversation %s after its descriptor failed",
                        sanitize(conversationId));
            }
            throw e;
        }
    }

    /**
     * Announces a pause that is now durable ({@link HitlPauseCommittedEvent}) to
     * asynchronous observers — the HITL email notifier today. Called at each place
     * a pause is committed, after the commit; never throws, since the pause is
     * already persisted and an observer's trouble is not the turn's.
     */
    void firePauseCommitted(String conversationId, IConversationMemory memory) {
        if (hitlPauseCommittedEvent == null) {
            return;
        }
        try {
            String pauseId = HitlDecision.pauseIdOf(memory.getHitlPausedAt());
            if (pauseId == null) {
                return; // no bookmark time: nothing an observer could match against
            }
            hitlPauseCommittedEvent.fireAsync(new HitlPauseCommittedEvent(conversationId, pauseId));
        } catch (RuntimeException e) {
            LOGGER.warnf("Could not announce the committed pause of conversation %s: %s", sanitize(conversationId), e.getMessage());
        }
    }

    @Override
    public String erasureStepName() {
        return "inFlightConversations";
    }

    // ─── Cluster control (eddi.messaging.type=nats) ───

    /**
     * Cluster mode: the lease manager (who runs a conversation's turn) and the node
     * RPC that reaches that node. Field-injected so the many tests that build this
     * service with {@code new} keep the single-node behaviour (both null).
     */
    @Inject
    IConversationLeaseManager leaseManager;

    @Inject
    IClusterRpc clusterRpc;

    @Inject
    Instance<IAgentDeploymentManagement> deploymentManagement;

    /** How long a request waits for an agent another node has just deployed. */
    static final Duration CLUSTER_DEPLOY_WAIT = Duration.ofSeconds(15);

    /** A resolution step that may throw, for {@link #awaitClusterDeployment}. */
    @FunctionalInterface
    interface AgentLookup<T> {
        T find() throws Exception;
    }

    /**
     * Cluster mode: the agent is not deployed on THIS node yet, though another node
     * has just deployed it — this node's sweep has not caught up. Deploys it here
     * on demand and waits (bounded) instead of answering 404. Single node: null at
     * once.
     */
    <T> T awaitClusterDeployment(Environment environment, String agentId, AgentLookup<T> lookup) {
        if (deploymentManagement == null || !deploymentManagement.isResolvable() || leaseManager == null || !leaseManager.isClustered()) {
            return null;
        }
        try {
            return deploymentManagement.get().awaitClusterDeployment(environment, agentId, () -> {
                try {
                    return lookup.find();
                } catch (Exception e) {
                    return null;
                }
            }, CLUSTER_DEPLOY_WAIT);
        } catch (RuntimeException e) {
            // The deployment store could not be read (or the deploy failed): the caller's
            // own not-ready handling answers, as it does when nothing is deployed.
            LOGGER.warnf("On-demand deployment of agent %s failed: %s", sanitize(agentId), e.getMessage());
            return null;
        }
    }

    /**
     * Signals the turn of {@code conversationId} running on THIS node, if any — the
     * cooperative flag the pipeline checks at task boundaries.
     *
     * @return true when a running turn was signalled
     */
    public boolean signalLocalInFlight(String conversationId) {
        var inFlightMemory = conversationId == null ? null : inFlightConversations.get(conversationId);
        if (inFlightMemory == null) {
            return false;
        }
        inFlightMemory.setCancelled(true);
        return true;
    }

    /**
     * Cluster mode: when the conversation's turn runs on ANOTHER node (it holds the
     * lease), asks that node to signal it. Single node: always false.
     *
     * @return true when the executing node confirmed it signalled a running turn
     */
    boolean signalRemoteInFlight(String conversationId) {
        if (leaseManager == null || clusterRpc == null || !leaseManager.isClustered() || !clusterRpc.isClustered()) {
            return false;
        }
        var holder = leaseManager.peek(conversationId);
        if (holder.isEmpty()) {
            return false;
        }
        var reply = clusterRpc.call(holder.get().node(), IClusterRpc.CONVERSATION_CANCEL, Map.of("conversationId", conversationId));
        boolean signalled = reply.map(r -> Boolean.TRUE.equals(r.get("signalled"))).orElse(false);
        if (signalled) {
            LOGGER.infof("Signalled the turn of conversation %s running on node %s", sanitize(conversationId), holder.get().node());
        }
        return signalled;
    }

    /**
     * GDPR stop on behalf of another node: the erased user arrives as a hash, so
     * the user id never travels between nodes.
     */
    @Override
    public int stopInFlightWorkByHash(String userIdHash) {
        if (userIdHash == null) {
            return 0;
        }
        int signalled = 0;
        for (IConversationMemory memory : inFlightConversations.values()) {
            if (memory.getUserId() != null && userIdHash.equals(KvKeys.sha256(memory.getUserId()))) {
                memory.setCancelled(true);
                signalled++;
            }
        }
        return signalled;
    }

    /**
     * {@inheritDoc} Signals the turn through
     * {@link ConversationStepRunner#abandonTurn}.
     */
    @Override
    public boolean abandonInFlightTurn(String conversationId) {
        IConversationMemory memory = conversationId == null ? null : inFlightConversations.get(conversationId);
        if (memory == null) {
            return false;
        }
        ConversationStepRunner.abandonTurn(memory);
        LOGGER.infof("Abandoned the in-flight turn of conversation %s", sanitize(conversationId));
        return true;
    }

    /**
     * GDPR erasure: signals every turn running on this node for {@code userId} to
     * stop, through the same cooperative flag {@link #cancelConversation} sets. A
     * cancelled turn skips its longTerm write-back to user memory
     * ({@code Conversation.isTurnDiscarded}) and its snapshot is discarded. The
     * audit entries it still flushes while unwinding are pseudonymised by the
     * ledger ({@code AuditLedgerService.markUserErased}). Matched on the live
     * memory's user, not on a stored lookup, so a turn whose conversation the
     * cascade has not reached yet — or one started a moment ago — is caught too.
     */
    @Override
    public int stopInFlightWork(String userId) {
        if (userId == null) {
            return 0;
        }
        int signalled = 0;
        for (IConversationMemory memory : inFlightConversations.values()) {
            if (userId.equals(memory.getUserId())) {
                memory.setCancelled(true);
                signalled++;
            }
        }
        return signalled;
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
        endConversation(conversationId, endedBy, null);
    }

    @Override
    public void endConversation(String conversationId, String endedBy, String endReason) {
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
        } else {
            // Cluster mode: the turn may be running on the node that holds the lease.
            signalRemoteInFlight(conversationId);
        }
        // Ending a PAUSED conversation terminally resolves its pending approval:
        // disarm the timeout schedule (a stale fire would log spurious errors and
        // leave a dead schedule row forever) and clear the persisted bookmark.
        ConversationState previousState = conversationMemoryStore.getConversationState(conversationId);
        setConversationState(conversationId, ConversationState.ENDED);
        if (endReason != null) {
            try {
                conversationMemoryStore.setConversationEndReason(conversationId, endReason);
            } catch (RuntimeException e) {
                LOGGER.warnf("Conversation %s ended, but its end reason '%s' could not be recorded: %s",
                        sanitize(conversationId), sanitize(endReason), e.getMessage());
            }
        }
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

    /**
     * Environment-qualified twin of {@link #getConversationState(String)}; internal
     * and tests only.
     */
    ConversationState getConversationState(Environment environment, String conversationId) {
        checkNotNull(environment, "environment");
        return getConversationState(conversationId);
    }

    SimpleConversationMemorySnapshot readConversation(Environment environment, String agentId, String conversationId, Boolean returnDetailed,
                                                      Boolean returnCurrentStepOnly, List<String> returningFields)
            throws AgentMismatchException, ResourceStoreException, ResourceNotFoundException {

        long startTime = System.nanoTime();
        validateParams(environment, agentId, conversationId);
        Map<String, String> loggingContext = contextLogger.createLoggingContext(environment, agentId, conversationId, null);
        contextLogger.setLoggingContext(loggingContext);

        try {
            var conversationMemorySnapshot = requireSnapshot(conversationId);
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

        var memorySnapshot = requireSnapshot(conversationId);
        var conversationLog = new ConversationLogGenerator(memorySnapshot).generate(logSize != null ? logSize : -1);
        // Null-safe: the REST layer hands an empty "?outputType=" through as null
        // (Quarkus treats it like an absent parameter but skips the @DefaultValue),
        // and lowercasing first turned that into a 500.
        outputType = outputType == null ? "" : outputType.trim().toLowerCase(Locale.ROOT);

        if (outputType.isEmpty() || outputType.equals("string") || outputType.equals("text")) {
            return new ConversationLogResult(conversationLog.toString(), TEXT_PLAIN);
        } else {
            return new ConversationLogResult(conversationLog.toObject(), APPLICATION_JSON);
        }
    }

    /**
     * Sets this turn's deadline on the memory: the agent's {@code turnDeadlineMs}
     * capped by the caller's header, counted from the request's arrival. Always
     * assigns — a memory reused across turns must not carry the previous turn's
     * deadline, nor keep one the next request does not ask for.
     */
    static void applyTurnDeadline(IConversationMemory memory, IAgent agent, InputData inputData, Clock clock, long arrivalEpochMs) {
        memory.setTurnDeadline(TurnDeadline.forTurn(clock, arrivalEpochMs, agent.getTurnDeadlineMs(), agent.getTurnDeadlineReserveMs(),
                inputData != null ? inputData.getRequestedTurnDeadlineMs() : null));
    }

    /**
     * Charge the current step's not-yet-metered priced spend to the tenant. Runs
     * from the completion callback, before the turn is persisted, so the step's
     * {@code audit:metered_cost} marker is stored with the cost it describes. Never
     * throws.
     */
    void meterTurnCost(IConversationMemory memory) {
        if (turnCostMeter != null) {
            turnCostMeter.meterSettledStep(memory);
        }
    }

    /**
     * What a finished turn tells its caller and its own step: the {@code error}
     * object when it failed, or the idempotency record when it settled.
     * <p>
     * The record is written to the step BEFORE the turn is persisted (this runs
     * from the pipeline's completion callback, the persist follows), so the key is
     * durable with the answer it belongs to. A failed turn is deliberately not
     * recorded: its caller is told to retry, and the retry must run again.
     */
    void recordTurnOutcome(IConversationMemory memory, SimpleConversationMemorySnapshot snapshot, InputData inputData) {
        if (snapshot.getConversationState() == ConversationState.ERROR) {
            snapshot.setError(turnErrorOf(memory));
            return;
        }
        String key = inputData != null ? inputData.getIdempotencyKey() : null;
        if (key != null && memory.getCurrentStep() != null && TurnIdempotencyService.isReplayable(snapshot)) {
            memory.getCurrentStep().storeData(new Data<>(TurnIdempotencyService.STEP_KEY, key));
        }
    }

    /**
     * The reason the turn failed, from the {@code taskErrors} entry the failed task
     * left in the step's output; a generic error when there is none (strict write
     * rolled it back, or the failure was not a task's).
     */
    static TurnError turnErrorOf(IConversationMemory memory) {
        var step = memory.getCurrentStep();
        Object entries = step == null || step.getConversationOutput() == null ? null : step.getConversationOutput().get(MemoryKeys.TASK_ERRORS);
        if (entries instanceof List<?> list && !list.isEmpty()) {
            TurnError found = TurnError.fromTaskError(list.getLast());
            if (found != null) {
                return found;
            }
        }
        return new TurnError(TurnError.TURN_FAILED, false, null, "The turn failed");
    }

    @Override
    public void say(Environment environment, String agentId, String conversationId, Boolean returnDetailed, Boolean returnCurrentStepOnly,
                    List<String> returningFields, InputData inputData, boolean rerunOnly, ConversationResponseHandler responseHandler)
            throws Exception {
        say(environment, agentId, conversationId, returnDetailed, returnCurrentStepOnly, returningFields, inputData, rerunOnly, responseHandler,
                null);
    }

    /**
     * @param preloaded
     *            the conversation as the caller has just loaded it, so the turn
     *            does not load it a second time; {@code null} to load it here
     */
    private void say(Environment environment, String agentId, String conversationId, Boolean returnDetailed, Boolean returnCurrentStepOnly,
                     List<String> returningFields, InputData inputData, boolean rerunOnly, ConversationResponseHandler responseHandler,
                     ConversationMemorySnapshot preloaded)
            throws Exception {
        admitTurn(environment, agentId, conversationId, returnDetailed, returnCurrentStepOnly, returningFields, inputData, rerunOnly, preloaded,
                new TurnListener() {
                    @Override
                    public void completed(SimpleConversationMemorySnapshot snapshot) {
                        responseHandler.onComplete(snapshot);
                    }

                    @Override
                    public void skipped(SimpleConversationMemorySnapshot snapshot) {
                        responseHandler.onSkipped(snapshot);
                    }

                    @Override
                    public Consumer<String> toolCallObserver() {
                        return responseHandler instanceof ToolCallObserving observing ? observing.toolCallObserver() : null;
                    }

                    @Override
                    public void callerGone(SimpleConversationMemorySnapshot snapshot) {
                        responseHandler.onCallerGone(snapshot);
                    }
                });
    }

    /**
     * Environment-qualified twin of
     * {@link #sayStreaming(String, Boolean, Boolean, List, InputData, StreamingResponseHandler)}
     * — for callers that address an agent (A2A streaming), and tests.
     */
    @Override
    public void sayStreaming(Environment environment, String agentId, String conversationId, Boolean returnDetailed, Boolean returnCurrentStepOnly,
                             List<String> returningFields, InputData inputData, StreamingResponseHandler streamingHandler)
            throws Exception {
        sayStreaming(environment, agentId, conversationId, returnDetailed, returnCurrentStepOnly, returningFields, inputData, streamingHandler,
                null);
    }

    /**
     * As
     * {@link #say(Environment, String, String, Boolean, Boolean, List, InputData, boolean, ConversationResponseHandler, ConversationMemorySnapshot)}.
     */
    private void sayStreaming(Environment environment, String agentId, String conversationId, Boolean returnDetailed, Boolean returnCurrentStepOnly,
                              List<String> returningFields, InputData inputData, StreamingResponseHandler streamingHandler,
                              ConversationMemorySnapshot preloaded)
            throws Exception {
        ConversationEventSink eventSink = eventSinkFor(streamingHandler);
        admitTurn(environment, agentId, conversationId, returnDetailed, returnCurrentStepOnly, returningFields, inputData, false, preloaded,
                new TurnListener() {
                    @Override
                    public void completed(SimpleConversationMemorySnapshot snapshot) {
                        streamingHandler.onComplete(snapshot);
                    }

                    @Override
                    public void skipped(SimpleConversationMemorySnapshot snapshot) {
                        streamingHandler.onSkipped(snapshot);
                    }

                    @Override
                    public ConversationEventSink eventSink() {
                        return eventSink;
                    }

                    @Override
                    public void pipelineFailed(Exception e) {
                        streamingHandler.onError(e);
                    }

                    @Override
                    public void leaseLost(SimpleConversationMemorySnapshot snapshot, Long fence) {
                        // Tokens of the discarded turn may already be on the wire: end the
                        // stream with an error, not with a done event for a turn that is not stored.
                        streamingHandler.onError(new ConversationStepRunner.TurnLeaseLostException(conversationId, fence));
                    }
                });
    }

    /**
     * What differs between a plain and a streamed turn: where the outcome goes and
     * whether progress events are emitted. Everything else — validation, quota, the
     * turn builder, submission and the release of the processing gauge — is one
     * code path, {@link #admitTurn}. It used to be two copies of 170 lines that had
     * already started to drift.
     */
    private interface TurnListener {
        void completed(SimpleConversationMemorySnapshot snapshot);

        void skipped(SimpleConversationMemorySnapshot snapshot);

        /**
         * The turn was dropped unrun because its caller had stopped waiting
         * ({@link ProcessingTurn#callerHasGivenUp}); answered as skipped unless the
         * listener knows better.
         */
        default void callerGone(SimpleConversationMemorySnapshot snapshot) {
            skipped(snapshot);
        }

        /** Progress events for a streamed turn; {@code null} for a plain one. */
        default ConversationEventSink eventSink() {
            return null;
        }

        /** Name-only tool-call observer for a plain turn; {@code null} for none. */
        default Consumer<String> toolCallObserver() {
            return null;
        }

        /** The pipeline failed inside the turn (after admission). */
        default void pipelineFailed(Exception e) {
        }

        /**
         * Cluster mode: the turn was stopped because this node lost the conversation's
         * lease (see {@link #stoppedByLeaseLoss}); the snapshot is marked
         * {@code IN_PROGRESS}. A plain turn is answered as skipped — busy, 409 with
         * {@code Retry-After} over REST.
         */
        default void leaseLost(SimpleConversationMemorySnapshot snapshot, Long fence) {
            skipped(snapshot);
        }
    }

    /** Forwards the pipeline's progress events to a streaming handler. */
    private static ConversationEventSink eventSinkFor(StreamingResponseHandler streamingHandler) {
        return new ConversationEventSink() {
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
            public void onLlmRetry(String reason, int attempt) {
                streamingHandler.onLlmRetry(reason, attempt);
            }

            @Override
            public void onGuardrailTriggered(String type, String phase, String action, String taskId) {
                streamingHandler.onGuardrailTriggered(type, phase, action, taskId);
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
    }

    /**
     * Validates, admits and queues one turn — the single path behind {@code say}
     * and {@code sayStreaming}.
     *
     * @param rerunOnly
     *            re-execute the last step instead of consuming new input (never set
     *            for a streamed turn); a rerun is not rebuilt over a newer memory
     */
    private void admitTurn(Environment environment, String agentId, String conversationId, Boolean returnDetailed,
                           Boolean returnCurrentStepOnly, List<String> returningFields, InputData inputData, boolean rerunOnly,
                           ConversationMemorySnapshot preloaded, TurnListener listener)
            throws Exception {

        long startTime = System.nanoTime();
        // The request's arrival when the REST layer stamped it: time spent waiting
        // for a request thread counts against a turn deadline too.
        final long arrivalEpochMs = inputData != null && inputData.getRequestArrivalEpochMs() != null
                ? inputData.getRequestArrivalEpochMs()
                : System.currentTimeMillis();
        rejectIfShuttingDown();
        // Assigned inside the try; the catch blocks need it, and the lambdas below
        // need an effectively-final alias (processingTurn).
        ProcessingTurn admittedTurn = null;
        try {
            // The conversation-id entry points have just loaded the conversation to find
            // its agent and run the pre-turn checks; loading it again here was a second
            // full-document read on every turn.
            final IConversationMemory conversationMemory = preloaded != null
                    ? convertConversationMemorySnapshot(preloaded)
                    : loadConversationMemory(conversationId);
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

            rejectIfEnded(conversationMemory);

            // HITL fast-fail: a paused conversation cannot consume input — reject
            // promptly (REST: 409) instead of dropping the turn into the 60s
            // watchdog. Checked BEFORE quota/reference bookkeeping so nothing
            // leaks. The queued-say guard below remains as the race backstop.
            if (conversationMemory.getConversationState() == ConversationState.AWAITING_HUMAN) {
                throw new ConversationAwaitingApprovalException(
                        "Conversation is awaiting human approval — a reviewer must resolve it via"
                                + " POST /agents/" + conversationId + "/resume (or cancel) before new input is accepted");
            }

            IAgent resolvedAgent = resolveConversationAgent(environment, conversationMemory);
            if (resolvedAgent == null) {
                final IConversationMemory memoryToResolve = conversationMemory;
                resolvedAgent = awaitClusterDeployment(environment, conversationMemory.getAgentId(),
                        () -> resolveConversationAgent(environment, memoryToResolve));
            }
            IAgent agent = resolvedAgent;
            if (agent != null && !Objects.equals(agent.getAgentVersion(), agentVersion)) {
                // The turn runs on another, compatible version: log it as that one.
                loggingContext.put("agentVersion", String.valueOf(agent.getAgentVersion()));
                contextLogger.setLoggingContext(loggingContext);
            }
            if (agent == null) {
                String msg = "Agent not deployed (environment=%s, conversationId=%s, version=%s)";
                msg = String.format(msg, environment, conversationMemory.getAgentId(), agentVersion);
                throw new AgentNotReadyException(msg);
            }

            // In-flight admission BEFORE the quota slot: a turn refused for capacity
            // must not have spent the tenant's api-call quota. Every exit below
            // releases it through releaseTurn / ProcessingTurn.release (C11).
            admittedTurn = admitTurn(conversationId);
            final ProcessingTurn processingTurn = admittedTurn;
            if (skipTurnsOfTimedOutCallers && inputData != null) {
                processingTurn.callerGivesUpAt(inputData.getCallerGivesUpAtEpochMs());
            }

            // Tenant quota — atomic slot acquisition AFTER cheap validations
            // (avoids burning quota on not-found, GDPR-restricted, agent-mismatch, or
            // agent-not-ready failures)
            QuotaCheckResult quotaCheck = tenantQuotaService.acquireApiCallSlot();
            if (!quotaCheck.allowed()) {
                throw quotaCheck.accountingUnavailable()
                        // A store that could not answer is a 503, not a 429: the
                        // tenant is not over anything, and a client that backs off
                        // for a minute on the strength of a Retry-After is reacting
                        // to the wrong signal.
                        ? new QuotaAccountingUnavailableException(quotaCheck.reason())
                        : new QuotaExceededException(quotaCheck.reason());
            }

            // Handler contract: a skipped turn (pause/busy committed by the time the
            // queued turn executed) must still complete the response — with the
            // persisted state and WITHOUT the metrics reference leaking. For a stream
            // this is what terminates it instead of leaving it open.
            Consumer<IConversationMemory> notifySkipped = skippedMemory -> {
                SimpleConversationMemorySnapshot memorySnapshot = convertSimpleConversationMemorySnapshot(skippedMemory,
                        returnDetailed, returnCurrentStepOnly, returningFields);
                memorySnapshot.setEnvironment(environment);
                recordMetrics(timerConversationProcessing, counterConversationProcessing, startTime);
                processingTurn.release();
                if (processingTurn.wasDroppedForCaller()) {
                    listener.callerGone(memorySnapshot);
                } else {
                    listener.skipped(memorySnapshot);
                }
            };

            // Everything bound to one memory instance lives in the builder, so a queued
            // turn can be rebuilt over the current document when it finally runs (H13a).
            final String idempotencyKey = idempotencyKeyOf(inputData, rerunOnly);
            ConversationStepRunner.TurnBuilder turnBuilder = memory -> {
                // Rebuilt over a newer document: a turn committed while this one queued. If
                // that turn carried this request's Idempotency-Key, it WAS this request —
                // sent again to another node while the first ran there, where the in-flight
                // registry of the running node cannot see it. Checked here, under the
                // conversation's lease, so the duplicate never runs a second time; it is
                // answered as skipped (409 + Retry-After) and its retry is served the
                // stored answer by the persisted lookup.
                if (memory != conversationMemory && idempotencyKey != null
                        && turnIdempotency.isRecordedIn(convertConversationMemory(memory), idempotencyKey)) {
                    throw new TurnIdempotencyService.TurnAlreadyRecordedException(conversationId);
                }
                Integer storedVersion = memory == conversationMemory ? agentVersion : memory.getAgentVersion();
                adoptResolvedAgentVersion(memory, agent);
                applyTurnDeadline(memory, agent, inputData, Clock.systemUTC(), arrivalEpochMs);
                // A streamed turn reports its progress through the sink on memory, which
                // LifecycleManager and the tasks read.
                if (listener.eventSink() != null) {
                    memory.setEventSink(listener.eventSink());
                }
                // Always assigned: a memory reused across turns must not keep the
                // previous turn's observer.
                memory.setToolCallObserver(listener.toolCallObserver());

                // Set the audit collector on memory (if auditing is enabled)
                if (auditLedgerService.isEnabled()) {
                    String envName = environment.toString();
                    memory.setAuditCollector(entry -> auditLedgerService.submit(entry.withEnvironment(envName)));
                }

                final IConversation conversation = agent.continueConversation(memory,
                        createPropertiesHandler(memory.getUserId(), agent.getUserMemoryConfig(), agent.isMemoryToolsEnabled()),
                        returnConversationMemory -> {
                            SimpleConversationMemorySnapshot memorySnapshot = convertSimpleConversationMemorySnapshot(returnConversationMemory,
                                    returnDetailed, returnCurrentStepOnly, returningFields);
                            memorySnapshot.setEnvironment(environment);
                            if (stoppedByLeaseLoss(conversationId, returnConversationMemory, memorySnapshot)) {
                                recordMetrics(timerConversationProcessing, counterConversationProcessing, startTime);
                                processingTurn.release();
                                listener.leaseLost(memorySnapshot, returnConversationMemory.getFenceToken());
                                return;
                            }
                            // Settle point: charge the turn's priced spend to the tenant.
                            // After the lease check — a turn stopped by lease loss stores
                            // nothing, and the node that took the conversation over runs
                            // (and meters) it again. A replayed idempotent turn never gets
                            // here: it is answered from the stored outcome.
                            meterTurnCost(returnConversationMemory);
                            recordTurnOutcome(returnConversationMemory, memorySnapshot, inputData);
                            conversationDescriptorStore.updateTimeStamp(conversationId);
                            recordAgentVersionMove(returnConversationMemory, storedVersion);
                            recordMetrics(timerConversationProcessing, counterConversationProcessing, startTime);
                            completeTurn(processingTurn, conversationId, memorySnapshot, listener::completed);
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
                            listener.pipelineFailed(e);
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
                            listener.pipelineFailed(e);
                        }
                        return null;
                    };
                }
                return withResolutionPrincipal(memory, executeConversation);
            };

            Callable<Void> processUserInput = processConversationStep(environment, conversationMemory, conversationId, loggingContext,
                    turnBuilder, !rerunOnly, notifySkipped, processingTurn, inputData, rerunOnly, agent);

            conversationCoordinator.submitInOrder(conversationId, processUserInput);
        } catch (ProcessingRestrictedException | ProcessingRestrictionUnavailableException | QuotaExceededException
                | QuotaAccountingUnavailableException | ConversationAwaitingApprovalException | TurnCapacityExceededException e) {
            // All five are thrown before the turn is admitted, and none is an internal
            // fault of this class: the generic handler below logs a full ERROR stack
            // trace, which for the restriction-unavailable case meant every turn of
            // every user wrote two of them (here and again in RestAgentEngine) for the
            // duration of a store failover. The REST layer reports each one with its
            // own status.
            //
            // QuotaAccountingUnavailableException is listed even though it extends
            // RejectedExecutionException: it is thrown two lines after the
            // QuotaExceededException it replaces on the same denial, so leaving it out
            // put exactly the log flood this multi-catch removes back on the
            // quota-store outage path — one ERROR stack trace per turn, on top of
            // TenantQuotaService.recordDenial's own.
            releaseTurn(admittedTurn);
            throw e;
        } catch (AgentMismatchException | AgentNotReadyException | ConversationEndedException | IllegalArgumentException e) {
            // IllegalArgumentException: a malformed conversation id, refused by the store
            // before any lookup. A client mistake (the REST layer answers 400), not a
            // fault worth an ERROR stack trace.
            releaseTurn(admittedTurn);
            throw e;
        } catch (Exception e) {
            LOGGER.error(e.getLocalizedMessage(), e);
            releaseTurn(admittedTurn);
            throw e;
        }
    }

    /**
     * An ended conversation is refused before its agent is looked up.
     * <p>
     * The check further down ({@code conversation.isEnded()}) comes after the agent
     * lookup, so an ended conversation whose agent version had since been
     * undeployed — the normal state after
     * {@code undeploy?endAllActiveConversations=true} — answered "agent not ready"
     * instead of "ended". Clients that recover from an ended conversation by
     * starting a new one (Slack threads, managed conversations, {@code /v1}) never
     * saw the signal they recover on, and retried a conversation that could never
     * answer again.
     */
    static void rejectIfEnded(IConversationMemory conversationMemory) throws ConversationEndedException {
        if (conversationMemory.getConversationState() == ConversationState.ENDED) {
            throw new ConversationEndedException("Conversation has ended!");
        }
    }

    /**
     * The pre-submission rollback: the turn task never ran, so this is its end —
     * gauge and capacity permit both.
     */
    static void releaseTurn(ProcessingTurn turn) {
        if (turn != null) {
            turn.finish();
        }
    }

    /**
     * Admits one turn against {@code eddi.conversation.max-in-flight-turns}; a
     * refusal is counted as {@code eddi.turn.rejected{reason="in_flight_limit"}}
     * and never logged as an ERROR — it is backpressure, not a fault. Only at DEBUG
     * here: the surface answering the 503 already logs it once (RestAgentEngine at
     * WARN), and under overload a second line per refused turn would only double
     * the flood. The counter is the signal.
     */
    ProcessingTurn admitTurn(String conversationId) {
        int limit = inFlightTurnLimit();
        try {
            return ProcessingTurn.admit(processingConversationCount, inFlightTurnPermits, limit);
        } catch (TurnCapacityExceededException e) {
            countRefusal(conversationId, limit);
            throw e;
        }
    }

    /**
     * Refuses a turn before anything is read for it when the node is already at
     * {@code eddi.conversation.max-in-flight-turns}. {@link #admitTurn} stays the
     * authoritative check (this one is not atomic); this exists so that, at the
     * limit, a turn that will be refused does not first spend the database round
     * trips of checking ownership and loading its conversation.
     */
    @Override
    public void refuseIfAtCapacity(String conversationId) {
        int limit = inFlightTurnLimit();
        if (limit > 0 && inFlightTurnPermits.get() >= limit) {
            countRefusal(conversationId, limit);
            throw new TurnCapacityExceededException("This node is already running " + limit
                    + " conversation turns (eddi.conversation.max-in-flight-turns) — retry shortly or against another node");
        }
    }

    private void countRefusal(String conversationId, int limit) {
        meterRegistry.counter("eddi.turn.rejected", "reason", "in_flight_limit").increment();
        LOGGER.debugf("Turn of conversation %s refused: %d turns already in flight (eddi.conversation.max-in-flight-turns)",
                sanitize(conversationId), limit);
    }

    /**
     * Resolves the limit at startup, so a setting that is neither {@code auto} nor
     * a number fails the boot instead of the first turn, and the value the node
     * actually runs with is in the log.
     */
    @PostConstruct
    void resolveInFlightTurnLimit() {
        int limit = inFlightTurnLimit();
        LOGGER.infof("Conversation turns in flight per node: %s (eddi.conversation.max-in-flight-turns=%s, max heap %d MB, %d MB per turn)",
                limit > 0 ? String.valueOf(limit) : "unlimited", maxInFlightTurnsSetting, Runtime.getRuntime().maxMemory() / (1024 * 1024),
                inFlightTurnMemoryMb);
    }

    /** The resolved {@code eddi.conversation.max-in-flight-turns}. */
    int inFlightTurnLimit() {
        int limit = maxInFlightTurns;
        if (limit == LIMIT_UNRESOLVED) {
            limit = InFlightTurnLimit.resolve(maxInFlightTurnsSetting, Runtime.getRuntime().maxMemory(), inFlightTurnMemoryMb);
            maxInFlightTurns = limit;
        }
        return limit;
    }

    /**
     * Environment-qualified twin of {@link #isUndoAvailable(String)}; internal and
     * tests only.
     */
    Boolean isUndoAvailable(Environment environment, String agentId, String conversationId)
            throws ResourceStoreException, ResourceNotFoundException {

        validateParams(environment, agentId, conversationId);
        final IConversationMemory conversationMemory = loadConversationMemory(conversationId);
        return conversationMemory.isUndoAvailable();
    }

    /**
     * Environment-qualified twin of {@link #undo(String)}; internal and tests only.
     */
    boolean undo(Environment environment, String agentId, String conversationId)
            throws ResourceStoreException, ResourceNotFoundException, AgentMismatchException {

        validateParams(environment, agentId, conversationId);
        long startTime = System.nanoTime();
        try {
            return undoLoaded(environment, loadAndValidateConversationMemory(agentId, conversationId));
        } finally {
            recordMetrics(timerConversationUndo, counterConversationUndo, startTime);
        }
    }

    private boolean undoLoaded(Environment environment, IConversationMemory conversationMemory) throws ResourceStoreException {
        String conversationId = conversationMemory.getConversationId();
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
                LOGGER.warnf("Undo of conversation %s aborted: its state or revision changed concurrently (state was %s)",
                        conversationId, loadedStateForUndo);
                return false;
            }
            // The undone step is now on top of the redo cache.
            var undone = conversationMemory.getRedoCache().peek();
            applyAgentMemoryConfig(environment, conversationMemory, undone);
            syncLongTermChanges(conversationMemory, undone, true);
            return true;
        } else {
            return false;
        }
    }

    /**
     * Environment-qualified twin of {@link #isRedoAvailable(String)}; internal and
     * tests only.
     */
    Boolean isRedoAvailable(Environment environment, String agentId, String conversationId)
            throws ResourceStoreException, ResourceNotFoundException {

        validateParams(environment, agentId, conversationId);
        var conversationMemory = loadConversationMemory(conversationId);
        return conversationMemory.isRedoAvailable();
    }

    /**
     * Environment-qualified twin of {@link #redo(String)}; internal and tests only.
     */
    boolean redo(Environment environment, String agentId, String conversationId)
            throws ResourceStoreException, ResourceNotFoundException, AgentMismatchException {

        validateParams(environment, agentId, conversationId);
        long startTime = System.nanoTime();
        try {
            return redoLoaded(environment, loadAndValidateConversationMemory(agentId, conversationId));
        } finally {
            recordMetrics(timerConversationRedo, counterConversationRedo, startTime);
        }
    }

    private boolean redoLoaded(Environment environment, IConversationMemory conversationMemory) throws ResourceStoreException {
        String conversationId = conversationMemory.getConversationId();
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
                LOGGER.warnf("Redo of conversation %s aborted: its state or revision changed concurrently (state was %s)",
                        conversationId, loadedStateForRedo);
                return false;
            }
            applyAgentMemoryConfig(environment, conversationMemory, conversationMemory.getCurrentStep());
            syncLongTermChanges(conversationMemory, conversationMemory.getCurrentStep(), false);
            return true;
        } else {
            return false;
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
            var snapshot = requireSnapshot(conversationId);
            var loggingContext = contextLogger.createLoggingContext(snapshot.getEnvironment(), snapshot.getAgentId(), conversationId,
                    snapshot.getUserId());
            contextLogger.setLoggingContext(loggingContext);

            return convertSimpleConversationMemorySnapshot(snapshot, returnDetailed, returnCurrentStepOnly, returningFields);
        } finally {
            recordMetrics(timerConversationLoad, counterConversationLoad, startTime);
        }
    }

    /**
     * The snapshot, or a 404 — never a {@code null} for the caller to dereference.
     *
     * <p>
     * {@code loadConversationMemorySnapshot} answers {@code null} for a
     * conversation that is not there, and the read paths went straight on to call
     * {@code getEnvironment()} on it. So a deleted or mistyped conversation id
     * produced a {@link NullPointerException}, which reached the client as
     * {@code 500 Internal Server Error} plus an error id — from endpoints whose own
     * {@code @APIResponse} promised a 404, and which the troubleshooting
     * documentation tells people to call precisely when something has already gone
     * wrong.
     * </p>
     *
     * <p>
     * Throws {@link ConversationNotFoundException} rather than the checked
     * {@code ResourceNotFoundException} that {@code RestConversationStore}'s twin
     * uses: this is the exception {@link #getConversationState} already raises for
     * the same condition, and it needs no signature change on {@code say} /
     * {@code sayStreaming}. Both map to 404.
     * </p>
     */
    private ConversationMemorySnapshot requireSnapshot(String conversationId) throws ResourceStoreException, ResourceNotFoundException {
        var snapshot = conversationMemoryStore.loadConversationMemorySnapshot(conversationId);
        if (snapshot == null) {
            throw new ConversationNotFoundException(String.format("No conversation found! (conversationId=%s)", sanitize(conversationId)));
        }
        return snapshot;
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
        requireInputWithinLimit(inputData);
        var snapshot = requireSnapshot(conversationId);
        refuseIfSoftDeleted(conversationId, snapshot);

        String key = idempotencyKeyOf(inputData, rerunOnly);
        ConversationResponseHandler handler = responseHandler;
        if (key != null) {
            var sink = new TurnIdempotencyService.SnapshotSink() {
                @Override
                public void done(SimpleConversationMemorySnapshot answer) {
                    responseHandler.onComplete(answer);
                }

                @Override
                public void skipped(SimpleConversationMemorySnapshot answer) {
                    responseHandler.onSkipped(answer);
                }
            };
            if (!turnIdempotency.admitOrServe(conversationId, key, inputData.getRequestedTurnDeadlineMs(),
                    () -> storedOutcome(snapshot, key, returnDetailed, returnCurrentStepOnly, returningFields), sink,
                    () -> currentSnapshotOf(conversationId, returnDetailed, returnCurrentStepOnly, returningFields),
                    () -> say(conversationId, returnDetailed, returnCurrentStepOnly, returningFields, inputData, rerunOnly, responseHandler))) {
                return;
            }
            // First sight of the key: this request runs the turn, and everything it
            // hands back is remembered for the duplicates.
            handler = new ConversationResponseHandler() {
                @Override
                public void onComplete(SimpleConversationMemorySnapshot answer) {
                    turnIdempotency.complete(conversationId, key, new TurnIdempotencyService.Outcome(answer, false));
                    responseHandler.onComplete(answer);
                }

                @Override
                public void onSkipped(SimpleConversationMemorySnapshot answer) {
                    turnIdempotency.complete(conversationId, key, new TurnIdempotencyService.Outcome(answer, true));
                    responseHandler.onSkipped(answer);
                }

                @Override
                public void onCallerGone(SimpleConversationMemorySnapshot answer) {
                    // Released, not remembered: the turn never ran, so a retry with the
                    // same key has to run it rather than be told it was skipped.
                    turnIdempotency.abandon(conversationId, key,
                            new IllegalStateException("The turn was not run: its caller had stopped waiting for it"));
                    responseHandler.onCallerGone(answer);
                }
            };
        }
        try {
            say(snapshot.getEnvironment(), snapshot.getAgentId(), conversationId, returnDetailed, returnCurrentStepOnly, returningFields, inputData,
                    rerunOnly, handler, snapshot);
        } catch (Exception | Error e) {
            if (key != null) {
                turnIdempotency.abandon(conversationId, key, e);
            }
            throw e;
        }
    }

    /**
     * The request's idempotency key, or {@code null} when none applies: no header,
     * a rerun (which is not a new input), or the feature switched off.
     */
    private String idempotencyKeyOf(InputData inputData, boolean rerunOnly) {
        if (rerunOnly || inputData == null || turnIdempotency == null || !turnIdempotency.isEnabled()) {
            return null;
        }
        return inputData.getIdempotencyKey();
    }

    /**
     * The stored answer to a turn that already ran under {@code key}: the
     * conversation up to and including the step that recorded it, so a duplicate
     * that arrives after later turns still gets that turn's answer.
     */
    private TurnIdempotencyService.Outcome storedOutcome(ConversationMemorySnapshot snapshot, String key, Boolean returnDetailed,
                                                         Boolean returnCurrentStepOnly, List<String> returningFields) {
        int stepIndex = turnIdempotency.findStoredStep(snapshot, key);
        if (stepIndex < 0) {
            return null;
        }
        var allSteps = snapshot.getConversationSteps();
        var allOutputs = snapshot.getConversationOutputs();
        try {
            if (stepIndex < allSteps.size() - 1) {
                snapshot.setConversationSteps(new ArrayList<>(allSteps.subList(0, stepIndex + 1)));
                snapshot.setConversationOutputs(new ArrayList<>(allOutputs.subList(0, Math.min(allOutputs.size(), stepIndex + 1))));
            }
            var stored = convertSimpleConversationMemorySnapshot(snapshot, returnDetailed, returnCurrentStepOnly, returningFields);
            stored.setEnvironment(snapshot.getEnvironment());
            return new TurnIdempotencyService.Outcome(stored, false);
        } finally {
            snapshot.setConversationSteps(allSteps);
            snapshot.setConversationOutputs(allOutputs);
        }
    }

    private SimpleConversationMemorySnapshot currentSnapshotOf(String conversationId, Boolean returnDetailed, Boolean returnCurrentStepOnly,
                                                               List<String> returningFields)
            throws ResourceStoreException, ResourceNotFoundException {
        var current = requireSnapshot(conversationId);
        var simple = convertSimpleConversationMemorySnapshot(current, returnDetailed, returnCurrentStepOnly, returningFields);
        simple.setEnvironment(current.getEnvironment());
        return simple;
    }

    @Override
    public void requireInputWithinLimit(InputData inputData) {
        if (inputData == null) {
            return;
        }
        if (maxInputChars > 0 && inputData.getInput() != null) {
            int length = inputData.getInput().length();
            if (length > maxInputChars) {
                throw new InputTooLargeException(length, maxInputChars);
            }
        }
        requireExpressionContextWithinLimits(inputData.getContext());
    }

    /**
     * A {@code context} entry of type {@code expressions} is parsed by the
     * property-setter and by every behavior rule with a {@code contextmatcher}, so
     * its length and nesting are bounded like the input text — here, before a turn
     * is paid for, with the same refusal a too-long input gets. The parser enforces
     * the same {@link ExpressionLimits} again, so a surface that skips this check
     * only moves the failure into the turn.
     */
    private void requireExpressionContextWithinLimits(Map<String, Context> context) {
        if (context == null || context.isEmpty()) {
            return;
        }
        ExpressionLimits limits = expressionLimits != null ? expressionLimits : ExpressionLimits.defaults();
        for (var entry : context.entrySet()) {
            Context value = entry.getValue();
            if (value == null || value.getType() != Context.ContextType.expressions || value.getValue() == null) {
                continue;
            }
            try {
                limits.check(value.getValue().toString());
            } catch (ExpressionLimitExceededException e) {
                throw new InputTooLargeException("Context '" + entry.getKey() + "': " + e.getMessage(), e.getMeasured(), e.getLimit());
            }
        }
    }

    @Override
    public void sayStreaming(String conversationId, Boolean returnDetailed, Boolean returnCurrentStepOnly, List<String> returningFields,
                             InputData inputData, StreamingResponseHandler streamingHandler)
            throws Exception {

        requireConversationAccess(conversationId);
        requireInputWithinLimit(inputData);
        var snapshot = requireSnapshot(conversationId);
        refuseIfSoftDeleted(conversationId, snapshot);

        String key = idempotencyKeyOf(inputData, false);
        StreamingResponseHandler handler = streamingHandler;
        if (key != null) {
            var sink = new TurnIdempotencyService.SnapshotSink() {
                @Override
                public void done(SimpleConversationMemorySnapshot answer) {
                    streamingHandler.onComplete(answer);
                }

                @Override
                public void skipped(SimpleConversationMemorySnapshot answer) {
                    streamingHandler.onSkipped(answer);
                }
            };
            // A duplicate has no tokens to replay: it is answered with the final
            // snapshot, as the stream's terminal event.
            if (!turnIdempotency.admitOrServe(conversationId, key, inputData.getRequestedTurnDeadlineMs(),
                    () -> storedOutcome(snapshot, key, returnDetailed, returnCurrentStepOnly, returningFields), sink,
                    () -> currentSnapshotOf(conversationId, returnDetailed, returnCurrentStepOnly, returningFields),
                    () -> sayStreaming(conversationId, returnDetailed, returnCurrentStepOnly, returningFields, inputData, streamingHandler))) {
                return;
            }
            handler = new IdempotentStreamingHandler(streamingHandler, turnIdempotency, conversationId, key);
        }
        try {
            sayStreaming(snapshot.getEnvironment(), snapshot.getAgentId(), conversationId, returnDetailed, returnCurrentStepOnly, returningFields,
                    inputData, handler, snapshot);
        } catch (Exception | Error e) {
            if (key != null) {
                turnIdempotency.abandon(conversationId, key, e);
            }
            throw e;
        }
    }

    /**
     * Forwards every event to the caller's stream and reports the terminal one to
     * the idempotency registry, so duplicates of this turn can be answered.
     */
    private static final class IdempotentStreamingHandler implements StreamingResponseHandler {
        private final StreamingResponseHandler delegate;
        private final TurnIdempotencyService idempotency;
        private final String conversationId;
        private final String key;

        IdempotentStreamingHandler(StreamingResponseHandler delegate, TurnIdempotencyService idempotency, String conversationId, String key) {
            this.delegate = delegate;
            this.idempotency = idempotency;
            this.conversationId = conversationId;
            this.key = key;
        }

        @Override
        public void onTaskStart(TaskId taskId, String taskType, int index) {
            delegate.onTaskStart(taskId, taskType, index);
        }

        @Override
        public void onTaskComplete(TaskId taskId, String taskType, long durationMs, Map<String, Object> summary) {
            delegate.onTaskComplete(taskId, taskType, durationMs, summary);
        }

        @Override
        public void onToken(String token) {
            delegate.onToken(token);
        }

        @Override
        public void onCascadeStepStart(int stepIndex, String modelType, String modelName, int totalSteps) {
            delegate.onCascadeStepStart(stepIndex, modelType, modelName, totalSteps);
        }

        @Override
        public void onCascadeEscalation(int fromStep, int toStep, double confidence, double threshold, String reason, long durationMs) {
            delegate.onCascadeEscalation(fromStep, toStep, confidence, threshold, reason, durationMs);
        }

        @Override
        public void onLlmRetry(String reason, int attempt) {
            delegate.onLlmRetry(reason, attempt);
        }

        @Override
        public void onGuardrailTriggered(String type, String phase, String action, String taskId) {
            delegate.onGuardrailTriggered(type, phase, action, taskId);
        }

        @Override
        public void onComplete(SimpleConversationMemorySnapshot snapshot) {
            idempotency.complete(conversationId, key, new TurnIdempotencyService.Outcome(snapshot, false));
            delegate.onComplete(snapshot);
        }

        @Override
        public void onError(Throwable error) {
            // A no-op when onComplete already settled the key (the pipeline reports the
            // failed turn's snapshot first, then the error).
            idempotency.abandon(conversationId, key, error);
            delegate.onError(error);
        }

        @Override
        public void onTaskFailed(TaskId taskId, String taskType, long durationMs, String errorType, String errorSummary) {
            delegate.onTaskFailed(taskId, taskType, durationMs, errorType, errorSummary);
        }

        @Override
        public void onToolCall(String toolName) {
            delegate.onToolCall(toolName);
        }

        @Override
        public void onSkipped(SimpleConversationMemorySnapshot snapshot) {
            idempotency.complete(conversationId, key, new TurnIdempotencyService.Outcome(snapshot, true));
            delegate.onSkipped(snapshot);
        }
    }

    /**
     * Refuses a turn on a conversation that was soft-deleted but is not ENDED, and
     * ends it on the way.
     * <p>
     * Soft delete ends the conversation since this was fixed, but conversations
     * soft-deleted by earlier releases were left READY: their owner (the
     * conversation guard resolves the owner from the archived descriptor) could
     * still drive them, and the retention sweep — which only looks at ENDED
     * conversations — never removed them. This is the lazy migration for those: the
     * first attempt to continue one ends it (HITL-aware, like the delete itself)
     * and answers as for any ended conversation. It costs one descriptor read on
     * the conversation-id entry points (REST, SSE, MCP, Slack, {@code /v1}); the
     * internal agent-driven overloads are not affected.
     */
    private void refuseIfSoftDeleted(String conversationId, ConversationMemorySnapshot snapshot)
            throws ConversationEndedException, ResourceStoreException {
        if (snapshot.getConversationState() == ConversationState.ENDED || !isSoftDeleted(conversationId)) {
            return;
        }
        LOGGER.infof("Conversation %s was deleted before soft delete ended conversations — ending it now", sanitize(conversationId));
        endConversation(conversationId, SOFT_DELETED_ACTOR);
        throw new ConversationEndedException("Conversation has ended!");
    }

    /**
     * No live descriptor, but an archived one: the conversation was soft-deleted.
     */
    private boolean isSoftDeleted(String conversationId) throws ResourceStoreException {
        // The ownership check of this request has usually resolved the descriptor
        // already.
        return ConversationDescriptorMemo.resolve(conversationDescriptorStore, descriptorMemoInstance, conversationId).archived();
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

    // The four below read the conversation ONCE. They used to load the snapshot to
    // learn its environment and agent, then hand both to the qualified overload,
    // which loaded the same document a second time.

    @Override
    public Boolean isUndoAvailable(String conversationId) throws ResourceStoreException, ResourceNotFoundException {
        // requireSnapshot, not the raw load: a missing conversation was a null
        // dereference here, i.e. a 500 where every sibling endpoint answers 404.
        return convertConversationMemorySnapshot(requireSnapshot(conversationId)).isUndoAvailable();
    }

    @Override
    public boolean undo(String conversationId) throws ResourceStoreException, ResourceNotFoundException {
        long startTime = System.nanoTime();
        try {
            var snapshot = requireSnapshot(conversationId);
            return undoLoaded(snapshot.getEnvironment(), convertConversationMemorySnapshot(snapshot));
        } finally {
            recordMetrics(timerConversationUndo, counterConversationUndo, startTime);
        }
    }

    @Override
    public Boolean isRedoAvailable(String conversationId) throws ResourceStoreException, ResourceNotFoundException {
        return convertConversationMemorySnapshot(requireSnapshot(conversationId)).isRedoAvailable();
    }

    @Override
    public boolean redo(String conversationId) throws ResourceStoreException, ResourceNotFoundException {
        long startTime = System.nanoTime();
        try {
            var snapshot = requireSnapshot(conversationId);
            return redoLoaded(snapshot.getEnvironment(), convertConversationMemorySnapshot(snapshot));
        } finally {
            recordMetrics(timerConversationRedo, counterConversationRedo, startTime);
        }
    }

    // --- Internal helpers ---

    /**
     * Carries an undo ({@code revert}) or redo of {@code step}'s {@code longTerm}
     * property changes into the user memory store, which the conversation-memory
     * undo cannot reach: a slot the undone turn filled stayed filled in every later
     * conversation.
     * <p>
     * Deliberately conservative. The store is shared with the user's other
     * conversations and agents, so only the entry this step wrote is rewritten: the
     * one at the identity the turn persisted it under (the shared {@code global}
     * row, or this agent's own row — the two can coexist for one key), still
     * holding this step's value, and last written by this conversation. Anything
     * else is left alone, including when that cannot be told apart. The replacement
     * is persisted the way a turn persists it
     * ({@link ConversationGroups#persistedVisibility}), so an undo that reverts a
     * visibility change reverts it in the store too. An entry is only recreated
     * when none exists for the key. Best effort: a store failure is logged, the
     * undo itself stands.
     */
    void syncLongTermChanges(IConversationMemory memory, IConversationMemory.IConversationStep step, boolean revert) {
        // Agent-test conversations never wrote user memory, so an undo has nothing to
        // revert.
        if (userMemoryStore == null || step == null || AgentTestConversations.isTestUserId(memory.getUserId())) {
            return;
        }
        var changes = ConversationMemory.propertyChanges(step);
        if (changes.isEmpty()) {
            return;
        }
        String userId = memory.getUserId();
        String agentId = memory.getAgentId();
        String conversationId = memory.getConversationId();
        AgentConfiguration.UserMemoryConfig config = memory.getUserMemoryConfig();
        List<String> groupIds = ConversationGroups.resolveGroupIds(memory, groupMembershipCheck());
        changes.forEach((key, beforeAfter) -> {
            Property stored = revert ? beforeAfter[1] : beforeAfter[0];
            Property target = revert ? beforeAfter[0] : beforeAfter[1];
            boolean storedIsLongTerm = stored != null && stored.getScope() == Property.Scope.longTerm;
            boolean targetIsLongTerm = target != null && target.getScope() == Property.Scope.longTerm;
            if (!storedIsLongTerm && !targetIsLongTerm) {
                return;
            }
            // GDPR bookkeeping is never written from a conversation — the store refuses
            // it, as the turn boundary does (Conversation.storePropertiesPermanently).
            if (IUserMemoryStore.isReservedKey(key)) {
                return;
            }
            try {
                List<UserMemoryEntry> sameKey = userMemoryStore.getAllEntries(userId).stream()
                        .filter(e -> key.equals(e.key()))
                        .filter(e -> e.visibility() == Visibility.global || Objects.equals(agentId, e.sourceAgentId()))
                        .toList();
                if (storedIsLongTerm) {
                    UserMemoryEntry current = entryWrittenByStep(sameKey, stored, userId, agentId, conversationId, config, groupIds);
                    if (current == null) {
                        return; // changed since this step, or not distinguishable — not ours to rewrite
                    }
                    if (!targetIsLongTerm) {
                        userMemoryStore.deleteEntry(current.id());
                        return;
                    }
                    Visibility visibility = restoredVisibility(target, config, groupIds, current.visibility());
                    List<String> groups = visibility == Visibility.group ? groupIds : List.of();
                    boolean sameIdentity = (visibility == Visibility.global) == (current.visibility() == Visibility.global);
                    if (sameIdentity) {
                        Object value = UserMemoryEntry.fromProperty(target, userId, agentId, null, Visibility.self).value();
                        userMemoryStore.upsert(new UserMemoryEntry(current.id(), userId, key, value, current.category(), visibility,
                                current.sourceAgentId(), groups, conversationId, false, current.accessCount(), current.createdAt(),
                                current.updatedAt()));
                    } else if (sameKey.stream().noneMatch(e -> (e.visibility() == Visibility.global) == (visibility == Visibility.global))) {
                        // The entry moves between the shared global row and this agent's
                        // row, which are different documents.
                        userMemoryStore.deleteEntry(current.id());
                        userMemoryStore.upsert(UserMemoryEntry.fromProperty(target, userId, agentId, conversationId, visibility, groups));
                    }
                    // else: an entry this step did not write already sits where the value
                    // would go — leave both rather than overwrite it.
                } else if (sameKey.isEmpty()) {
                    // The step removed (or never stored) the key; recreate it only when
                    // nothing has taken its place.
                    Visibility visibility = restoredVisibility(target, config, groupIds, Visibility.self);
                    userMemoryStore.upsert(UserMemoryEntry.fromProperty(target, userId, agentId, conversationId, visibility,
                            visibility == Visibility.group ? groupIds : List.of()));
                }
            } catch (Exception e) {
                LOGGER.warnf("Could not %s long-term property '%s' for conversation %s: %s", revert ? "revert" : "re-apply",
                        sanitize(key), sanitize(conversationId), e.getMessage());
            }
        });
    }

    /**
     * The entry {@code step} left for {@code stored}: its value, last written by
     * this conversation, at the identity the turn persisted it under. {@code null}
     * when there is none — or when the identity is unknown (no visibility on the
     * property, no agent config to read the default from) and both rows qualify.
     */
    private static UserMemoryEntry entryWrittenByStep(List<UserMemoryEntry> sameKey, Property stored, String userId, String agentId,
                                                      String conversationId, AgentConfiguration.UserMemoryConfig config,
                                                      List<String> groupIds) {
        Object expected = UserMemoryEntry.fromProperty(stored, userId, agentId, null, Visibility.self).value();
        List<UserMemoryEntry> candidates = sameKey.stream()
                .filter(e -> Objects.equals(e.value(), expected))
                .filter(e -> Objects.equals(conversationId, e.sourceConversationId()))
                .toList();
        if (stored.getVisibility() != null || config != null) {
            boolean global = ConversationGroups.persistedVisibility(stored, config, groupIds) == Visibility.global;
            return candidates.stream().filter(e -> (e.visibility() == Visibility.global) == global).findFirst().orElse(null);
        }
        return candidates.size() == 1 ? candidates.get(0) : null;
    }

    /**
     * Where {@code target} is persisted: as a turn would, when its visibility is
     * known. A property without one follows the agent's default — and when that
     * config is unavailable, {@code fallback} is used rather than a guess: the
     * scope the entry already has, or {@code self} for a new one, which never
     * widens who can read it.
     */
    private static Visibility restoredVisibility(Property target, AgentConfiguration.UserMemoryConfig config, List<String> groupIds,
                                                 Visibility fallback) {
        if (target.getVisibility() == null && config == null) {
            return fallback == Visibility.group && groupIds.isEmpty() ? Visibility.self : fallback;
        }
        return ConversationGroups.persistedVisibility(target, config, groupIds);
    }

    /**
     * Undo and redo run outside a turn, so the loaded memory has no
     * {@code userMemoryConfig} (it is applied per turn and never persisted). Reads
     * it from the agent version that ran {@code step} — recorded on the step, since
     * a conversation can move between compatible versions whose defaults differ —
     * so {@link #syncLongTermChanges} persists the way that turn did. A step from
     * before versions were recorded uses the conversation's version. When that
     * version is not deployed it stays unset and the sync falls back to never
     * widening a scope: another version's policy is not the one that governed the
     * step.
     */
    void applyAgentMemoryConfig(Environment environment, IConversationMemory memory, IConversationMemory.IConversationStep step) {
        Integer version = memory.getAgentVersion();
        IData<Integer> ranOn = step == null ? null : step.getLatestData(MemoryKeys.AGENT_VERSION);
        if (ranOn != null && ranOn.getResult() != null) {
            version = ranOn.getResult();
        }
        try {
            IAgent agent = agentFactory.getAgent(environment, memory.getAgentId(), version);
            if (agent != null) {
                memory.setUserMemoryConfig(agent.getUserMemoryConfig());
            }
        } catch (Exception e) {
            LOGGER.debugf("Agent config for conversation %s unavailable for the long-term undo sync: %s",
                    sanitize(memory.getConversationId()), e.getMessage());
        }
    }

    IPropertiesHandler createPropertiesHandler(final String userId, final AgentConfiguration.UserMemoryConfig memoryConfig) {
        return createPropertiesHandler(userId, memoryConfig, memoryConfig != null);
    }

    IPropertiesHandler createPropertiesHandler(final String userId, final AgentConfiguration.UserMemoryConfig memoryConfig,
                                               final boolean memoryToolsEnabled) {
        // An agent-test conversation neither reads nor writes long-term user memory:
        // without a store, Conversation skips loadUserProperties at init and
        // storePropertiesPermanently at every turn end. A test must not change what
        // real users have stored, nor pass or fail because of it.
        final IUserMemoryStore memoryStore = AgentTestConversations.isTestUserId(userId) ? null : userMemoryStore;
        return new IPropertiesHandler() {
            @Override
            public IUserMemoryStore getUserMemoryStore() {
                return memoryStore;
            }

            @Override
            public AgentConfiguration.UserMemoryConfig getUserMemoryConfig() {
                return memoryConfig;
            }

            @Override
            public boolean isMemoryToolsEnabled() {
                return memoryToolsEnabled;
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

            @Override
            public ConversationGroups.MembershipCheck getGroupMembershipCheck() {
                return groupMembershipCheck();
            }
        };
    }

    /**
     * Confirms an earlier step's {@code groupId} against the running discussion, or
     * {@code null} when no registry is wired (direct construction in tests), in
     * which case only the current step's {@code groupId} counts.
     */
    private ConversationGroups.MembershipCheck groupMembershipCheck() {
        LiveDiscussionRegistry registry = liveDiscussionRegistry;
        return registry != null ? registry::isLiveMember : null;
    }

    /**
     * Works out how much this conversation's user id is worth, without changing
     * {@link #startConversation}'s signature — a dozen call sites would otherwise
     * have to answer a question only two of them could.
     * <p>
     * Two sources, in order:
     * <ul>
     * <li><b>A principal already bound to this thread</b> means the conversation is
     * being spawned from inside a running pipeline turn (a sub-agent, a delegate, a
     * group member). Such a thread has no request to judge by, so deriving would
     * wrongly downgrade every child; the child inherits the parent's provenance
     * instead. Only for the SAME user — a child opened under a different id is a
     * different subject, and inheriting a verification that was never about them is
     * how one user's grant would be reachable from another's conversation.</li>
     * <li><b>Otherwise the authenticated caller</b>, and only when their principal
     * name IS this conversation's user id. A caller who opens a conversation on
     * behalf of some other id has asserted that id, not proved it — which is
     * exactly what the {@code /v1} adapter does with {@code X-OpenWebUI-User-Id},
     * since that surface authenticates a shared key rather than a person.</li>
     * </ul>
     */
    private ResolutionPrincipal deriveResolutionPrincipal(String conversationUserId, CallerIdentity caller) {
        ResolutionPrincipal inherited = currentResolutionPrincipal();
        if (inherited != null) {
            boolean sameSubject = conversationUserId != null && conversationUserId.equals(inherited.userId());
            return new ResolutionPrincipal(conversationUserId,
                    sameSubject ? inherited.provenance() : ResolutionPrincipal.Provenance.SELF_ASSERTED);
        }
        boolean verified = caller != null && caller.userId() != null && !caller.userId().isBlank()
                && caller.userId().equals(conversationUserId);
        return new ResolutionPrincipal(conversationUserId,
                verified ? ResolutionPrincipal.Provenance.VERIFIED : ResolutionPrincipal.Provenance.SELF_ASSERTED);
    }

    private ResolutionPrincipal currentResolutionPrincipal() {
        return resolutionPrincipalContext == null ? null : resolutionPrincipalContext.current();
    }

    private void bindResolutionPrincipal(ResolutionPrincipal principal) {
        if (resolutionPrincipalContext != null) {
            resolutionPrincipalContext.bind(principal);
        }
    }

    /**
     * Binds (or, with {@code null}, unbinds) the caller for the synchronous
     * CONVERSATION_START turn. The counterpart of {@link #bindResolutionPrincipal}:
     * the two bindings answer different questions — who is driving the request,
     * whose conversation this is — and both have to be live while that turn's tools
     * resolve credentials.
     */
    private void bindCallerIdentity(CallerIdentity caller) {
        if (callerIdentityContext != null) {
            callerIdentityContext.bind(caller);
        }
    }

    /**
     * Wraps a turn so the conversation's own principal is bound while it executes,
     * and unbound the moment it stops.
     * <p>
     * The pipeline runs on a pooled thread, so this is the only thing standing
     * between one conversation's owner and the next turn to land on that thread.
     * {@code ResolutionPrincipalContext} restores the previous binding in a
     * {@code finally} for exactly that reason.
     * <p>
     * Both halves come from the STORED memory rather than from the request that
     * triggered the turn. A request tells you who is driving; only the conversation
     * tells you whose credentials the turn may spend, and the two are different
     * people whenever somebody acts on another user's conversation.
     */
    private Callable<Void> withResolutionPrincipal(IConversationMemory conversationMemory, Callable<Void> executeConversation) {
        if (resolutionPrincipalContext == null) {
            return executeConversation;
        }
        var principal = new ResolutionPrincipal(conversationMemory.getUserId(), conversationMemory.getResolutionProvenance());
        return resolutionPrincipalContext.withPrincipal(principal, executeConversation);
    }

    /**
     * The agent version the next turn of this conversation runs on.
     * <p>
     * A conversation with a compatibility generation runs on the highest version of
     * that generation that is {@code READY} on this node, moving to it if that is
     * not where it is — a newer compatible version as soon as it is deployed, or an
     * older one when the newer one was undeployed (a rollback) or has not reached
     * this node yet. "Compatible" is declared in both directions for exactly that
     * reason.
     * <p>
     * Everything else takes today's path, {@link #getAgent}: a conversation without
     * a generation (pinned), a paused one (a resume must finish on the version that
     * paused it; {@code say} refuses those anyway), and one whose generation has no
     * version ready here — which deploys its own version on demand, as it always
     * did.
     */
    IAgent resolveConversationAgent(Environment environment, IConversationMemory memory) throws ServiceException, IllegalAccessException {
        Integer generation = memory.getCompatibilityGeneration();
        if (generation != null && memory.getConversationState() != ConversationState.AWAITING_HUMAN) {
            IAgent candidate = agentFactory.getLatestReadyAgentOfGeneration(environment, memory.getAgentId(), generation);
            if (candidate != null) {
                if (!Objects.equals(candidate.getAgentVersion(), memory.getAgentVersion())) {
                    moveToAgentVersion(memory, candidate.getAgentVersion(), generation);
                }
                return candidate;
            }
        }
        return getAgent(environment, memory.getAgentId(), memory.getAgentVersion());
    }

    private void moveToAgentVersion(IConversationMemory memory, Integer toVersion, Integer generation) {
        Integer fromVersion = memory.getAgentVersion();
        memory.switchAgentVersion(toVersion);
        LOGGER.infof("Conversation %s moves from version %s to version %s of agent %s (compatibility generation %s)",
                sanitize(memory.getConversationId()), fromVersion, toVersion, sanitize(memory.getAgentId()), generation);
    }

    /**
     * A queued turn is rebuilt over the reloaded document when another turn
     * committed while it waited (see {@code ConversationStepRunner.TurnBuilder}).
     * That document holds the version it was stored on, not the one resolved for
     * this turn, so it moves there too — the agent that runs the turn is the one
     * resolved for it. Only within the conversation's own generation: a
     * conversation that does not follow versions was resolved to its own.
     */
    static void adoptResolvedAgentVersion(IConversationMemory memory, IAgent agent) {
        Integer resolved = agent.getAgentVersion();
        Integer generation = memory.getCompatibilityGeneration();
        if (resolved != null && !Objects.equals(resolved, memory.getAgentVersion()) && generation != null
                && generation.equals(agent.getCompatibilityGeneration())) {
            memory.switchAgentVersion(resolved);
        }
    }

    /**
     * Once a turn has run on another version than the conversation was stored on:
     * count the move, and point the descriptor — which names the agent version too,
     * and which conversation listings filter on — at the version it ran on.
     * <p>
     * After the turn rather than when the version is resolved: a turn refused
     * between the two (quota, a queued turn skipped) must not leave the descriptor
     * naming a version the conversation never ran on.
     * <p>
     * A failed descriptor update is remembered on the memory, which this turn
     * persists after this runs, and retried by later turns until it succeeds —
     * otherwise every later turn, already on the new version, would see nothing to
     * do and the listings would name the old version for good. The conversation
     * runs on the right version either way.
     */
    void recordAgentVersionMove(IConversationMemory memory, Integer storedVersion) {
        Integer ranOn = memory.getAgentVersion();
        if (ranOn == null) {
            return;
        }
        if (!Objects.equals(ranOn, storedVersion)) {
            counterAgentVersionSwitch.increment();
        }
        Integer stale = memory.getStaleDescriptorAgentVersion();
        Integer named = stale != null ? stale : storedVersion;
        if (Objects.equals(ranOn, named)) {
            memory.setStaleDescriptorAgentVersion(null);
            return;
        }
        try {
            conversationSetup.updateConversationAgentVersion(memory.getConversationId(), memory.getAgentId(), ranOn);
            memory.setStaleDescriptorAgentVersion(null);
        } catch (Exception e) {
            memory.setStaleDescriptorAgentVersion(named);
            LOGGER.warnf("Conversation %s runs on version %s of agent %s, but its descriptor still names version %s; the next turn retries: %s",
                    sanitize(memory.getConversationId()), ranOn, sanitize(memory.getAgentId()), named, e.getMessage());
        }
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
                                                     Map<String, String> loggingContext, ConversationStepRunner.TurnBuilder turnBuilder,
                                                     boolean rebuildWhenSuperseded, Consumer<IConversationMemory> skipNotifier,
                                                     ProcessingTurn processingTurn, InputData inputData, boolean rerun, IAgent agent)
            throws Exception {
        return conversationStepRunner.processConversationStep(environment, conversationMemory, conversationId,
                loggingContext, turnBuilder, rebuildWhenSuperseded, skipNotifier, processingTurn,
                describeTurn(environment, conversationMemory, conversationId, inputData, rerun, agent));
    }

    /**
     * What a dead letter of this turn records, so an operator can replay it as a
     * new turn: the conversation, agent and environment, the user, and — unless
     * {@code eddi.coordinator.dead-letter.capture-input=false} — the input and its
     * context.
     * <p>
     * The capture happens <em>before</em> the pipeline runs, so the
     * {@code scope: secret} scrub a property-setter step would apply cannot have
     * happened yet. Two measures stand in for it (L6 of the 2026-10-15 security
     * review): a turn is recorded as {@code secretInput} — without input or context
     * — not only when the client flagged it so but also whenever the agent's
     * workflows carry any {@code scope: secret} instruction, because such an agent
     * expects secrets in its input and the client cannot be relied on to flag each
     * one; and the input that is kept passes through {@link SecretRedactionFilter},
     * so a credential-shaped token in an ordinary turn is masked rather than
     * retained for the dead letter's lifetime.
     *
     * @param agent
     *            the agent the turn runs on, as {@code say} resolved it — no second
     *            lookup here, this is the hot path. {@code null} is treated as an
     *            agent that expects secrets: a dead letter that cannot be replayed
     *            beats one that holds what it should not
     */
    Map<String, Object> describeTurn(Environment environment, IConversationMemory memory, String conversationId, InputData inputData,
                                     boolean rerun, IAgent agent) {
        Map<String, Object> turn = new LinkedHashMap<>();
        turn.put("conversationId", conversationId);
        turn.put("agentId", memory.getAgentId());
        turn.put("agentVersion", memory.getAgentVersion());
        turn.put("environment", environment == null ? null : environment.toString());
        turn.put("userId", memory.getUserId());
        turn.put("rerun", rerun);
        if (captureDeadLetterInput && inputData != null) {
            if (isSecretInput(inputData) || agent == null || agent.hasSecretPropertyInstructions()) {
                // A turn the client flagged secretInput, or one addressed to an agent
                // with a scope: secret instruction: its input must not sit in a dead
                // letter for days. Recorded as such, without input or context — so it can
                // be discarded but not replayed.
                turn.put("secretInput", true);
                return turn;
            }
            turn.put("input", SecretRedactionFilter.redact(inputData.getInput()));
            if (inputData.getContext() != null && !inputData.getContext().isEmpty()) {
                turn.put("context", redactContext(inputData.getContext()));
            }
        }
        return turn;
    }

    private static boolean isSecretInput(InputData inputData) {
        var context = inputData.getContext();
        Context flag = context == null ? null : context.get("secretInput");
        return flag != null && "true".equals(String.valueOf(flag.getValue()));
    }

    /**
     * The turn's context, fit for a dead letter. An entry the client marked
     * {@code secret}, or whose key names a credential ({@code password},
     * {@code token}, …), is dropped outright: the filter sees only the value and an
     * ordinary password has no shape it can recognise. Every other entry has its
     * string leaves — including those inside a structured (object) value — run
     * through {@link SecretRedactionFilter}, with the same credential-key rule
     * applied inside maps. The live turn is never touched: a changed entry is a
     * copy.
     */
    private static Map<String, Context> redactContext(Map<String, Context> context) {
        Map<String, Context> redacted = new LinkedHashMap<>();
        context.forEach((key, value) -> {
            if (value == null) {
                redacted.put(key, null);
                return;
            }
            if (Boolean.TRUE.equals(value.getSecret()) || SecretRedactionFilter.isCredentialFieldName(key)) {
                return;
            }
            Object clean = redactValue(value.getValue());
            if (clean == value.getValue()) {
                redacted.put(key, value);
            } else {
                Context copy = new Context(value.getType(), clean);
                copy.setServerGenerated(value.isServerGenerated());
                copy.setSecret(value.getSecret());
                redacted.put(key, copy);
            }
        });
        return redacted;
    }

    /**
     * Redacts the string leaves of a context value, structured or not. Returns the
     * very same object when nothing changed, so the caller can tell a copy from the
     * original without comparing contents.
     */
    private static Object redactValue(Object value) {
        if (value instanceof String text) {
            String clean = SecretRedactionFilter.redact(text);
            return clean.equals(text) ? value : clean;
        }
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            boolean changed = false;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                Object entryValue = entry.getValue();
                Object clean;
                if (entry.getKey() instanceof String name && SecretRedactionFilter.isCredentialFieldName(name)) {
                    clean = SecretRedactionFilter.REDACTED;
                } else {
                    clean = redactValue(entryValue);
                }
                changed |= clean != entryValue;
                copy.put(entry.getKey(), clean);
            }
            return changed ? copy : value;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            boolean changed = false;
            for (Object element : list) {
                Object clean = redactValue(element);
                changed |= clean != element;
                copy.add(clean);
            }
            return changed ? copy : value;
        }
        return value;
    }

    /**
     * Cluster mode: the turn was stopped because this node lost the conversation's
     * lease while it ran. It is never stored (another node holds the conversation)
     * and is dead-lettered, so its caller must not get the reply of a turn that
     * will not exist: it is answered as not processed, busy (409 with
     * {@code Retry-After} over REST), like a turn that never got the lease; a
     * stream ends with an error event instead of {@code done}. The snapshot is
     * marked {@code IN_PROGRESS} for that, and the conversation state is not cached
     * from it.
     */
    boolean stoppedByLeaseLoss(String conversationId, IConversationMemory memory, SimpleConversationMemorySnapshot snapshot) {
        if (!memory.isCancelled() || !conversationStepRunner.isLeaseLost(conversationId)) {
            return false;
        }
        snapshot.setConversationState(ConversationState.IN_PROGRESS);
        return true;
    }

    /** A turn's write was refused by the cluster fence (cluster mode only). */
    void counterFenceRejected() {
        meterRegistry.counter("eddi.cluster.fence.rejected").increment();
    }

    boolean waitForExecutionFinishOrTimeout(Map<String, String> loggingContext, String conversationId,
                                            IConversationMemory memory, Future<Void> future) {
        return conversationStepRunner.waitForExecutionFinishOrTimeout(loggingContext, conversationId, memory, future);
    }

    ConversationState committedStateOrError(String conversationId) {
        return conversationStepRunner.committedStateOrError(conversationId);
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

    /**
     * A queued turn was dropped because its caller had stopped waiting
     * ({@code eddi_turn_skipped_total{reason="caller_timed_out"}}).
     */
    void countTurnSkippedForCaller() {
        meterRegistry.counter("eddi.turn.skipped", "reason", "caller_timed_out").increment();
    }

    /**
     * Completes a turn towards the client — the single place that decides WHEN the
     * sync response / streaming {@code done} may be sent.
     * <p>
     * <b>Ordering contract.</b>
     * <ul>
     * <li>A turn that ends {@code AWAITING_HUMAN} is NOT delivered here. Its pause
     * is only written afterwards by the runtime's {@code onComplete}
     * ({@code ConversationStepRunner.runGuardedConversationStep}), and a client
     * told "paused" before that write reads {@code approval-status} /
     * {@code pending-approvals} and finds no pause (or one with no details). The
     * delivery is deferred and runs from that persistence step, with the state the
     * store really holds — so a pause that was not committed (lost CAS, cancel
     * race, store failure) is reported as what it became, never as a phantom pause.
     * The state cache is updated from the same committed state.</li>
     * <li>Every other turn is delivered immediately, exactly as before: its
     * snapshot already matches what the store will hold (or a revision-conflict
     * handler corrects it), and the next turn of the conversation is serialized
     * behind this one by the coordinator, so holding it back would add latency for
     * no consistency gain.</li>
     * </ul>
     * The delivery runs at most once; the turn's {@code finally} and its failure
     * callback flush a still-pending one, so the stream always terminates.
     */
    private void completeTurn(ProcessingTurn processingTurn, String conversationId, SimpleConversationMemorySnapshot memorySnapshot,
                              Consumer<SimpleConversationMemorySnapshot> clientCompletion) {
        if (memorySnapshot.getConversationState() == ConversationState.AWAITING_HUMAN) {
            processingTurn.deferredDelivery.defer(committedState -> {
                if (committedState != null) {
                    memorySnapshot.setConversationState(committedState);
                    cacheConversationState(conversationId, committedState);
                }
                processingTurn.release();
                clientCompletion.accept(memorySnapshot);
            });
            return;
        }
        cacheConversationState(conversationId, memorySnapshot.getConversationState());
        processingTurn.release();
        clientCompletion.accept(memorySnapshot);
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
    public CancelOutcome cancelPausedConversation(String conversationId, String cancelledBy) throws ResourceStoreException {
        return conversationHitlService.cancelPausedConversation(conversationId, cancelledBy);
    }

    @Override
    public void resumeConversation(String conversationId,
                                   HitlDecision decision,
                                   ConversationResponseHandler handler)
            throws ResourceStoreException, ResourceNotFoundException {
        conversationHitlService.resumeConversation(conversationId, decision, handler);
    }

    @Override
    public void resumeConversation(String conversationId,
                                   HitlDecision decision,
                                   ConversationResponseHandler handler,
                                   Boolean returnDetailed,
                                   Boolean returnCurrentStepOnly,
                                   List<String> returningFields)
            throws ResourceStoreException, ResourceNotFoundException {
        conversationHitlService.resumeConversation(conversationId, decision, handler, returnDetailed, returnCurrentStepOnly, returningFields);
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
