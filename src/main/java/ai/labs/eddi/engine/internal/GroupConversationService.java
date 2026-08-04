/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.agents.AgentSigningService;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.engine.audit.AuditLedgerService;
import ai.labs.eddi.configs.agents.crypto.NonceCacheService;
import ai.labs.eddi.utils.LogSanitizer;
import ai.labs.eddi.configs.deployment.IDeploymentStore;
import ai.labs.eddi.configs.groups.IAgentGroupStore;

import ai.labs.eddi.configs.groups.IGroupConversationStore;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ContextScope;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DiscussionPhase;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DiscussionStyle;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.GroupMember;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.PhaseType;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ProtocolConfig;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.TurnOrder;
import ai.labs.eddi.configs.hitl.HitlGranularity;
import ai.labs.eddi.configs.hitl.HitlTimeoutPolicy;
import ai.labs.eddi.configs.groups.model.DiscussionStylePresets;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.GroupConversationState;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntry;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntryType;
import ai.labs.eddi.configs.groups.model.SharedTaskList.TaskItem;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IGroupConversationService;
import ai.labs.eddi.engine.attachments.IAttachmentStore;
import ai.labs.eddi.engine.internal.groups.DebateVerdictParser;
import ai.labs.eddi.engine.internal.groups.GroupAttachmentBinder;
import ai.labs.eddi.engine.internal.groups.GroupContextBuilder;
import ai.labs.eddi.engine.internal.groups.GroupHitlCoordinator;
import ai.labs.eddi.engine.internal.groups.LiveDiscussionRegistry;
import ai.labs.eddi.engine.internal.groups.GroupLifecycleOps;
import ai.labs.eddi.engine.internal.groups.GroupSigningGuard;
import ai.labs.eddi.engine.internal.groups.MemberTurnExecutor;
import ai.labs.eddi.engine.internal.groups.PhaseExecutionEngine;
import ai.labs.eddi.engine.internal.groups.PhaseOutcome;
import ai.labs.eddi.engine.internal.groups.TaskForceEngine;
import ai.labs.eddi.engine.memory.model.Attachment;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.engine.runtime.internal.GracefulShutdownService;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import ai.labs.eddi.engine.lifecycle.GroupConversationEventSink;
import ai.labs.eddi.engine.lifecycle.model.ControlSignal;
import ai.labs.eddi.engine.lifecycle.model.DiscussionControlToken;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.DoubleAdder;

/**
 * Phase-based orchestrator for multi-agent group conversations. Supports 6
 * discussion styles (ROUND_TABLE, PEER_REVIEW, DEVIL_ADVOCATE, DELPHI, DEBATE,
 * TASK_FORCE) plus fully custom phase definitions.
 * <p>
 * Agents participate through their normal pipelines via
 * {@link IConversationService#say}. The orchestrator constructs phase-specific
 * input messages with appropriate context and collects responses into a
 * transcript.
 *
 * @author ginccc
 */
@ApplicationScoped
public class GroupConversationService implements IGroupConversationService {

    private static final Logger LOGGER = Logger.getLogger(GroupConversationService.class);
    private static final Environment DEFAULT_ENV = Environment.production;

    /**
     * Default per-agent-turn timeout (seconds) when not configured via
     * {@code protocol.agentTimeoutSeconds}. 180s covers thinking models (e.g.
     * claude-sonnet-5) and synthesis phases comfortably. Was 60s, which caused
     * timeouts on synthesis with extended thinking.
     */
    private static final int DEFAULT_AGENT_TIMEOUT_SECONDS = 180;

    /**
     * Default number of retries per member turn when not configured via
     * {@code protocol.maxRetries}. Shared by the retry loop in
     * {@code executeAgentTurn} and the batch budget a parallel phase derives from
     * it, so the two cannot drift apart.
     */
    private static final int DEFAULT_MAX_RETRIES = 2;

    /**
     * Slack added on top of a member's own budget when a parallel phase arms its
     * batch deadline. A member reaches its {@code responseFuture.get(timeout)} only
     * after agent lookup, conversation start, attachment grants and prior-entry
     * verification — several store round trips — so without a grace the
     * orchestrator's deadline, armed the instant the batch is dispatched, expires
     * while the member is still legitimately inside its own budget.
     * <p>
     * The floor is absolute, but it cannot be ONLY absolute: setup cost does not
     * shrink with a short configured {@code agentTimeoutSeconds}, so a flat second
     * is a large fraction of a 2s budget and a rounding error against a 180s one.
     * The grace is therefore {@code max(floor, timeout * fraction)} — see
     * {@link #parallelBatchGraceSeconds}.
     */
    private static final int PARALLEL_BATCH_GRACE_FLOOR_SECONDS = 1;

    /**
     * Fraction of a member's per-attempt budget also allowed for setup, so the
     * grace scales instead of being swamped by a large timeout or dominating a
     * small one.
     */
    private static final double PARALLEL_BATCH_GRACE_FRACTION = 0.1;

    /**
     * Ceiling on the derived parallel-batch budget, so an absurd
     * {@code agentTimeoutSeconds} × {@code maxRetries} combination cannot overflow
     * the nanosecond deadline into the past.
     */
    private static final long MAX_PARALLEL_BATCH_BUDGET_SECONDS = TimeUnit.HOURS.toSeconds(24);

    /**
     * How long an aborting orchestrator waits for cooperatively cancelled member
     * turns to unwind before reclaiming their tasks.
     * <p>
     * This is NOT merely a safety bound, which is what an earlier version of this
     * comment claimed. Cancellation releases the turn promptly at
     * {@code responseFuture.get(...)}, but that is not a member turn's only await
     * point, and the others do not observe the token:
     * <ul>
     * <li>{@code tryResolveMemberToolPause} blocks on a {@code resumeFuture} that
     * was never registered against the cancellation token;</li>
     * <li>a {@code MemberType.GROUP} member is dispatched into a nested synchronous
     * {@code discuss(...)} with no token at all, under its own {@code activeTokens}
     * entry.</li>
     * </ul>
     * For a turn parked at either of those, this timeout is the mechanism rather
     * than the backstop: the orchestrator reclaims the task once it expires while
     * the child work carries on. Making those paths cancellation-aware is tracked
     * separately — until then this is a bound that can genuinely be hit, not an
     * unreachable guard.
     */
    private static final int MEMBER_TURN_CANCEL_DRAIN_SECONDS = 5;

    private final IAgentGroupStore groupStore;
    private final IGroupConversationStore conversationStore;
    private final IConversationService conversationService;
    private final IAgentFactory agentFactory;
    private final ITemplatingEngine templatingEngine;
    private final IJsonSerialization jsonSerialization;
    private final int maxDepth;
    private final CallerIdentityContext callerIdentityContext;
    private final ExecutorService executorService;
    private final AgentSigningService agentSigningService;
    private final IAgentStore agentStore;
    private final IScheduleStore scheduleStore;
    private final NonceCacheService nonceCacheService;
    private final AuditLedgerService auditLedgerService;
    private final String defaultTenantId;
    private final GroupContextBuilder contextBuilder;
    private final GroupSigningGuard signingGuard;
    private final MemberTurnExecutor memberTurnExecutor;
    private final PhaseExecutionEngine phaseExecutionEngine;
    private final TaskForceEngine taskForceEngine;
    private final GroupHitlCoordinator hitlCoordinator;

    // Field-injected so the direct-construction unit tests stay unchanged; used to
    // materialize and share discussion attachments with member conversations.
    @Inject
    IAttachmentStore attachmentStore;

    // Same reason. Ephemeral cleanup deletes the Agent directly, not via
    // RestAgentStore, so it has to retire the deployment record itself.
    @Inject
    IDeploymentStore deploymentStore;

    /**
     * Graceful-shutdown gate (R1 step 10). Same reasoning and field-injection
     * pattern as {@link #attachmentStore}: {@code null} in the direct-construction
     * unit tests, which then never reject.
     */
    @Inject
    GracefulShutdownService gracefulShutdownService;

    /**
     * The live in-memory instances of currently-running discussions (Wave 0, F1).
     * Same field-injection pattern and null-safety as {@link #attachmentStore} —
     * {@code null} in the direct-construction unit tests, which then register and
     * unregister against nothing (a no-op, not an error).
     */
    @Inject
    LiveDiscussionRegistry liveDiscussionRegistry;

    // In-node fast-fail guard for concurrent post-discussion operations
    // (follow-up, continue, close) on the same conversation: a second
    // operation on the same gcId is rejected rather than queued. The Set is
    // single-node only, but it is NOT the cluster-wide safety mechanism:
    // cross-node races are prevented by conversationStore.compareAndSetState,
    // which performs an atomic storage-layer conditional update (Mongo
    // updateOne / Postgres UPDATE filtered on the current state field). The
    // Set only avoids redundant work within a single node.
    private final Set<String> operationsInProgress = ConcurrentHashMap.newKeySet();

    // Metrics
    private final Timer timerGroupDiscussion;
    private final Counter counterGroupDiscussion;
    private final Counter counterGroupFailure;
    private final Counter counterGroupHitlPause;
    private final Counter counterGroupHitlResume;
    private final Counter counterGroupMemberPauseSkipped;
    /**
     * Post-discussion operations (follow-up / continue / close) — REST and MCP
     * surfaces.
     */
    private final Counter counterGroupFollowUp;
    private final Counter counterGroupContinue;
    private final Counter counterGroupClose;
    /**
     * I1: times a discussion's cost ceiling stopped it scheduling further turns.
     */
    private final Counter counterGroupCostCeilingHit;
    /**
     * I1: lifetime dollars attributed across all discussions this instance ran.
     * Cumulative, not a live in-flight sum — mirrors {@code ToolCostTracker}'s
     * {@code eddi.tool.costs.total} gauge, which is the closest existing pattern.
     */
    private final DoubleAdder groupCostDollars = new DoubleAdder();

    @Inject
    public GroupConversationService(IAgentGroupStore groupStore, IGroupConversationStore conversationStore, IConversationService conversationService,
            IAgentFactory agentFactory, ITemplatingEngine templatingEngine, IJsonSerialization jsonSerialization, MeterRegistry meterRegistry,
            AgentSigningService agentSigningService, IAgentStore agentStore, IScheduleStore scheduleStore,
            NonceCacheService nonceCacheService, AuditLedgerService auditLedgerService, CallerIdentityContext callerIdentityContext,
            @ConfigProperty(name = "eddi.tenant.default-id", defaultValue = "default") String defaultTenantId,
            @ConfigProperty(name = "eddi.groups.max-depth", defaultValue = "3") int maxDepth) {
        this.groupStore = groupStore;
        this.callerIdentityContext = callerIdentityContext;
        this.conversationStore = conversationStore;
        this.conversationService = conversationService;
        this.agentFactory = agentFactory;
        this.templatingEngine = templatingEngine;
        this.jsonSerialization = jsonSerialization;
        this.maxDepth = maxDepth;
        this.agentSigningService = agentSigningService;
        this.agentStore = agentStore;
        this.scheduleStore = scheduleStore;
        this.nonceCacheService = nonceCacheService;
        this.auditLedgerService = auditLedgerService;
        this.defaultTenantId = defaultTenantId;
        this.contextBuilder = new GroupContextBuilder(templatingEngine);
        this.signingGuard = new GroupSigningGuard(agentStore, agentSigningService, nonceCacheService, defaultTenantId);
        // Virtual threads — lightweight, no pool sizing, ideal for parallel agent calls
        this.executorService = Executors.newVirtualThreadPerTaskExecutor();

        this.timerGroupDiscussion = meterRegistry.timer("eddi_group_discussion_duration");
        this.counterGroupDiscussion = meterRegistry.counter("eddi_group_discussion_count");
        this.counterGroupFailure = meterRegistry.counter("eddi_group_discussion_failure_count");
        this.counterGroupHitlPause = meterRegistry.counter("eddi_hitl_pause_count", "surface", "group");
        this.counterGroupHitlResume = meterRegistry.counter("eddi_hitl_resume_count", "surface", "group");
        this.counterGroupMemberPauseSkipped = meterRegistry.counter("eddi_group_member_pause_skipped_count");
        this.counterGroupFollowUp = meterRegistry.counter("eddi_group_followup_count");
        this.counterGroupContinue = meterRegistry.counter("eddi_group_continue_count");
        this.counterGroupClose = meterRegistry.counter("eddi_group_close_count");
        this.counterGroupCostCeilingHit = meterRegistry.counter("eddi_group_cost_ceiling_hit_total");
        meterRegistry.gauge("eddi_group_cost_dollars", groupCostDollars, DoubleAdder::sum);
        // Constructed last: needs counterGroupMemberPauseSkipped (just above) and
        // passes `this` for MemberTurnExecutor's nested-GROUP-member discuss()/
        // cancelDiscussion() calls. Safe here because MemberTurnExecutor's own
        // constructor only stores the reference — it never invokes a method on it
        // before this constructor (and therefore full field initialization)
        // completes.
        this.memberTurnExecutor = new MemberTurnExecutor(conversationService, agentFactory, signingGuard, contextBuilder, this,
                counterGroupMemberPauseSkipped, DEFAULT_AGENT_TIMEOUT_SECONDS, DEFAULT_MAX_RETRIES);
        this.phaseExecutionEngine = new PhaseExecutionEngine(memberTurnExecutor, contextBuilder, executorService, callerIdentityContext);
        this.taskForceEngine = new TaskForceEngine(memberTurnExecutor, templatingEngine, jsonSerialization, executorService, callerIdentityContext,
                activeTokens, DEFAULT_AGENT_TIMEOUT_SECONDS, MEMBER_TURN_CANCEL_DRAIN_SECONDS);
        // Constructed last, same reasoning as memberTurnExecutor above: needs `this`
        // for the executeDiscussion/resolvePhases/cleanupEphemeralAgents callbacks
        // resumeDiscussion and cleanupAfterTerminalState make back into the facade.
        this.hitlCoordinator = new GroupHitlCoordinator(groupStore, conversationStore, scheduleStore, auditLedgerService,
                signingGuard, activeTokens, executorService, callerIdentityContext, this,
                counterGroupHitlPause, counterGroupHitlResume, counterGroupFailure);
    }

    @PreDestroy
    void shutdown() {
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(10, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Rejects new group work once {@link GracefulShutdownService} has observed a
     * {@code ShutdownEvent} (R1 step 10) — mirrors
     * {@code ConversationService#rejectIfShuttingDown}. Applied on every entry
     * point that starts, continues or resumes a discussion: {@code discuss},
     * {@code startAndDiscussAsync}, {@code resumeDiscussion},
     * {@code continueDiscussion} and {@code followUpWithMember}.
     * <p>
     * The last two matter more than they look. Both mutate persisted state
     * <em>before</em> doing any agent work — {@code continueDiscussion} CASes
     * {@code COMPLETED → IN_PROGRESS}, bumps the round and re-runs every phase from
     * index 0. Ungated during a drain, each member turn would then be refused by
     * {@code ConversationService.say}'s own gate and recorded as a {@code SKIPPED}
     * transcript entry under the default {@code onAgentFailure=SKIP}, so a healthy
     * COMPLETED conversation would come back COMPLETED but with a round of skipped
     * entries and a stale synthesized answer. A clean 503 is strictly better than
     * silently degrading a finished conversation.
     * <p>
     * A {@code null} gate means the bean was constructed outside CDI (only the
     * direct-construction unit tests do that) and never rejects.
     */
    private void rejectIfShuttingDown() {
        if (gracefulShutdownService != null && gracefulShutdownService.isShuttingDown()) {
            throw new RejectedExecutionException(
                    "This node is shutting down and no longer accepts new group discussion work — retry against another node");
        }
    }

    @Override
    public GroupConversation discuss(String groupId, String question, String userId, int depth)
            throws GroupDiscussionException, IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {
        return discuss(groupId, question, userId, depth, null);
    }

    @Override
    public GroupConversation discuss(String groupId, String question, String userId, int depth, GroupDiscussionEventListener listener)
            throws GroupDiscussionException, IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {
        return discuss(groupId, question, userId, depth, listener, null);
    }

    @Override
    public GroupConversation discuss(String groupId, String question, String userId, int depth,
                                     GroupDiscussionEventListener listener, List<Attachment> attachments)
            throws GroupDiscussionException, IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {
        return discuss(groupId, question, userId, depth, listener, attachments, null);
    }

    /**
     * Internal overload carrying a parent discussion's remaining cost budget into a
     * nested {@code MemberType.GROUP} member's own run (I1). Not on
     * {@link IGroupConversationService} deliberately: every external caller starts
     * at {@code depth=0} with no parent to inherit from, and the only caller that
     * has a parent — {@code MemberTurnExecutor}, which holds the concrete class —
     * is internal. Same precedent as {@code grantAndInjectAttachments} and
     * {@code resolveAgentTimeoutSeconds}.
     *
     * @param inheritedCostCeiling
     *            the parent's remaining budget, or {@code null} when the parent has
     *            no ceiling. The child runs under {@code min(own, inherited)} — see
     *            {@link #effectiveCostCeiling}.
     */
    public GroupConversation discuss(String groupId, String question, String userId, int depth,
                                     GroupDiscussionEventListener listener, List<Attachment> attachments, Double inheritedCostCeiling)
            throws GroupDiscussionException, IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {

        if (depth > maxDepth) {
            throw new GroupDepthExceededException("Maximum group discussion depth (%d) exceeded".formatted(maxDepth));
        }
        if (groupId == null) {
            throw new IllegalArgumentException("groupId must not be null");
        }
        rejectIfShuttingDown();

        // Load group config — null-safe: getCurrentResourceId may return null on
        // PostgreSQL
        IResourceStore.IResourceId currentGroupId = groupStore.getCurrentResourceId(groupId);
        if (currentGroupId == null) {
            throw new IResourceStore.ResourceNotFoundException("Group not found.");
        }
        AgentGroupConfiguration config = groupStore.read(groupId, currentGroupId.getVersion());
        if (config == null) {
            throw new IResourceStore.ResourceNotFoundException("Group configuration not found.");
        }

        // Resolve phases
        List<DiscussionPhase> phases = resolvePhases(config);
        if (phases.isEmpty()) {
            throw new GroupDiscussionException("No discussion phases are defined for this group.");
        }

        GroupConversation gc = createGroupConversation(groupId, question, userId, depth);
        materializeAttachments(gc, attachments);
        gc.setInheritedCostCeiling(inheritedCostCeiling);
        return executeDiscussion(gc, config, phases, question, listener, 0);
    }

    /**
     * The ceiling a discussion actually runs under (I1): the tighter of its own
     * configured {@code maxCostPerDiscussion} and whatever budget a parent
     * discussion had left when it dispatched this one. Either side may be
     * {@code null} (unlimited); {@code null} only wins when BOTH are null.
     */
    static Double effectiveCostCeiling(Double own, Double inherited) {
        if (own == null) {
            return inherited;
        }
        if (inherited == null) {
            return own;
        }
        return Math.min(own, inherited);
    }

    @Override
    public GroupConversation startAndDiscussAsync(String groupId, String question, String userId, GroupDiscussionEventListener listener)
            throws GroupDiscussionException, IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {
        return startAndDiscussAsync(groupId, question, userId, listener, null);
    }

    @Override
    public GroupConversation startAndDiscussAsync(String groupId, String question, String userId,
                                                  GroupDiscussionEventListener listener, List<Attachment> attachments)
            throws GroupDiscussionException, IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {

        if (groupId == null) {
            throw new IllegalArgumentException("groupId must not be null");
        }
        rejectIfShuttingDown();

        // Validate early — so errors are returned synchronously
        IResourceStore.IResourceId currentGroupId = groupStore.getCurrentResourceId(groupId);
        if (currentGroupId == null) {
            throw new IResourceStore.ResourceNotFoundException("Group not found.");
        }
        AgentGroupConfiguration config = groupStore.read(groupId, currentGroupId.getVersion());
        if (config == null) {
            throw new IResourceStore.ResourceNotFoundException("Group configuration not found.");
        }

        List<DiscussionPhase> phases = resolvePhases(config);
        if (phases.isEmpty()) {
            throw new GroupDiscussionException("No discussion phases are defined for this group.");
        }

        // Create the conversation synchronously so we can return its ID
        GroupConversation gc = createGroupConversation(groupId, question, userId, 0);
        materializeAttachments(gc, attachments);

        // Register the control token BEFORE submitting: the caller already has the
        // conversation ID, so a cancel can arrive before the executor thread runs —
        // it must find a signalable token instead of racing the DB state.
        activeTokens.put(gc.getId(), new DiscussionControlToken());

        // Run the discussion in a virtual thread — reuse the same gc (no duplicate
        // creation)
        // Captured on the REST thread: everything below runs on virtual threads with
        // no request context, so a member agent's ${caller:token} apicall would
        // otherwise fail closed for the whole discussion.
        final var discussionCaller = callerIdentityContext.captureOrCurrent();
        try {
            executorService.submit(callerIdentityContext.withIdentity(discussionCaller, () -> {
                try {
                    executeDiscussion(gc, config, phases, question, listener, 0);
                } catch (Exception e) {
                    LOGGER.errorf("Async group discussion failed for %s: %s", groupId, e.getMessage());
                    if (listener != null) {
                        // Curated: the raw exception text (LLM/DB/driver detail, and possibly the
                        // caller's own input) must never be pushed to an SSE client. Logged above.
                        listener.onGroupError(new GroupConversationEventSink.GroupErrorEvent(
                                "The group discussion could not be started."));
                    }
                }
            }));
        } catch (RuntimeException e) {
            // Executor saturated/shut down — no thread will ever run this
            // discussion. Fail it instead of leaving an IN_PROGRESS zombie.
            activeTokens.remove(gc.getId());
            failConversation(gc);
            throw new GroupDiscussionException("Failed to start group discussion: " + e.getMessage(), e);
        }

        return gc;
    }

    /**
     * Materialize discussion attachments and bind them to the group conversation.
     * Delegates to {@link GroupAttachmentBinder}; see its Javadoc for behavior.
     * Constructed per call (not cached) since {@link #attachmentStore} is a
     * field-injected, test-mutable dependency (see field comment).
     */
    void materializeAttachments(GroupConversation gc, List<Attachment> incoming) {
        attachmentBinder().materializeAttachments(gc, incoming);
    }

    /**
     * Re-hydrate a group conversation's shared attachments from the durable blob
     * store. Delegates to {@link GroupAttachmentBinder}; see its Javadoc for
     * behavior.
     */
    void rehydrateAttachmentsFromStore(GroupConversation gc) {
        attachmentBinder().rehydrateAttachmentsFromStore(gc);
    }

    /**
     * Grant a member conversation access to the group's stored attachments and
     * inject them as {@code attachment_*} context. Delegates to
     * {@link GroupAttachmentBinder}; see its Javadoc for behavior.
     * <p>
     * Public: called from
     * {@link ai.labs.eddi.engine.internal.groups.MemberTurnExecutor} (Wave R, R1
     * step 4), which needs the facade's per-call construction of the binder (see
     * this class's {@code attachmentStore} field comment) rather than duplicating
     * that field-injection dance itself.
     */
    public void grantAndInjectAttachments(GroupConversation gc, String memberConvId, Map<String, Context> context) {
        attachmentBinder().grantAndInjectAttachments(gc, memberConvId, context);
    }

    private GroupAttachmentBinder attachmentBinder() {
        return new GroupAttachmentBinder(attachmentStore, defaultTenantId);
    }

    /**
     * Core discussion execution loop. Shared by both synchronous {@link #discuss}
     * and asynchronous {@link #startAndDiscussAsync} to avoid duplicate
     * conversation creation. Also fixes C2: emits phase_start before
     * synthesis_start for correct semantic ordering.
     */
    // Public: called back from GroupHitlCoordinator.resumeDiscussion (Wave R, R1
    // step 7) to re-enter the phase loop after a successful resume.
    public GroupConversation executeDiscussion(GroupConversation gc, AgentGroupConfiguration config, List<DiscussionPhase> phases, String question,
                                               GroupDiscussionEventListener listener, int startPhaseIndex)
            throws GroupDiscussionException, IResourceStore.ResourceStoreException {

        long startTime = System.nanoTime();
        // I1: baseline for this leg's gauge contribution — see the finally block.
        final double costAtLegStart = gc.getTotalCost();
        // MINOR-1: Only count/fire GROUP_START on fresh discussion, not resume
        if (startPhaseIndex == 0) {
            counterGroupDiscussion.increment();
        }

        // F1: publish the live instance this leg runs with. Covers both a fresh
        // start and a resume re-entry (this method is the single entry point for
        // both), so a tool call landing mid-phase always resolves the exact
        // GroupConversation the loop is currently mutating — never a stale one from
        // before a resume. Removed unconditionally in the finally block below.
        if (liveDiscussionRegistry != null) {
            liveDiscussionRegistry.register(gc);
        }

        ProtocolConfig resolvedProtocol = resolveProtocol(config);
        // I1: a nested discussion runs under the tighter of its own ceiling and the
        // budget its parent had left. Applied here (once, at the top of the leg)
        // rather than at each check site, so every executor sees one already-resolved
        // ceiling and none of them has to know about nesting.
        Double effectiveCeiling = effectiveCostCeiling(resolvedProtocol.maxCostPerDiscussion(), gc.getInheritedCostCeiling());
        ProtocolConfig protocol = Objects.equals(effectiveCeiling, resolvedProtocol.maxCostPerDiscussion())
                ? resolvedProtocol
                : new ProtocolConfig(resolvedProtocol.agentTimeoutSeconds(), resolvedProtocol.onAgentFailure(), resolvedProtocol.maxRetries(),
                        resolvedProtocol.onMemberUnavailable(), resolvedProtocol.maxTurns(), effectiveCeiling,
                        resolvedProtocol.onCostExceeded());
        int maxTurns = protocol.maxTurns() > 0 ? protocol.maxTurns() : 50;

        // Store the group's DynamicAgentConfig on the GC so executeAgentTurn()
        // can pass it to member agents via context variables, allowing
        // AgentOrchestrator to enforce group-level guardrails on dynamic tools.
        gc.setDynamicAgentConfig(config.getDynamicAgents());

        // Populate member display name map (idempotent — safe on continuation rounds)
        if (gc.getMemberDisplayNames().isEmpty() && config.getMembers() != null) {
            for (var member : config.getMembers()) {
                if (member.displayName() != null) {
                    gc.addMemberDisplayName(member.agentId(), member.displayName());
                }
            }
        }

        // Re-hydrate shared attachments (transient like dynamicAgentConfig above) from
        // the durable blob store so a HITL resume doesn't silently drop them for a
        // member whose first turn lands after the resume. See the method comment.
        rehydrateAttachmentsFromStore(gc);

        // AtomicInteger: shared across the phase loop; parallel phases increment
        // from virtual threads. Seed from pausedTurnCount to preserve budget across
        // resumes (M3).
        var turnCounter = new java.util.concurrent.atomic.AtomicInteger(
                gc.getPausedTurnCount() > 0 ? gc.getPausedTurnCount() : 0);

        // Resolve HITL granularity from group config
        boolean taskLevelHitl = config.getHitlConfig() != null
                && config.getHitlConfig().getGranularity() == HitlGranularity.TASK;

        // MAJOR-5: Register control token so cancelDiscussion can signal in-flight.
        // computeIfAbsent — startAndDiscussAsync/resumeDiscussion pre-register the
        // token before submitting, and a cancel signal set on it in that window
        // must NOT be wiped by a fresh token here.
        activeTokens.computeIfAbsent(gc.getId(), k -> new DiscussionControlToken());

        // MINOR-1: Only fire a start event on fresh execution (startPhaseIndex == 0),
        // not on an HITL resume. Round 1 → GROUP_START; continuation rounds →
        // ROUND_START.
        if (startPhaseIndex == 0 && listener != null) {
            if (gc.getRound() <= 1) {
                listener.onGroupStart(new GroupConversationEventSink.GroupStartEvent(gc.getId(), gc.getGroupId(), question,
                        config.getStyle() != null ? config.getStyle().name() : "ROUND_TABLE", phases.size(),
                        config.getMembers().stream().map(GroupMember::agentId).toList()));
            } else {
                listener.onRoundStart(new GroupConversationEventSink.RoundStartEvent(
                        gc.getId(), gc.getRound(), question, phases.size()));
            }
        }

        // I1: set once the cost ceiling fires under SYNTHESIZE_NOW. From then on the
        // phase loop skips every non-SYNTHESIS phase but still runs any remaining
        // SYNTHESIS one, so the discussion concludes with an answer rather than
        // stopping mid-transcript. Deliberately NOT a break: "jump to the first
        // remaining SYNTHESIS phase" is a skip-ahead, not a stop.
        boolean costCeilingSynthesizeNow = false;

        // I2: set when a phase returns END_DISCUSSION. Nothing produces that signal
        // yet — I12's facilitator will — but the loop honors it now so adding the
        // producer later cannot have it silently degrade to "end this phase only".
        boolean endDiscussionEarly = false;

        try {
            // Execute each phase
            for (int phaseIdx = startPhaseIndex; phaseIdx < phases.size(); phaseIdx++) {
                DiscussionPhase phase = phases.get(phaseIdx);

                // NEW-3: Check control token at top of phase loop
                var token = activeTokens.get(gc.getId());
                if (token != null && token.isCancelled()) {
                    gc.setState(GroupConversationState.CANCELLED);
                    gc.setLastModified(Instant.now());
                    conversationStore.update(gc);
                    LOGGER.infof("Group discussion %s cancelled via control token at phase %d", gc.getId(), phaseIdx);
                    notifyCancelled(gc, listener);
                    return gc;
                }

                // I1 SYNTHESIZE_NOW skip-ahead. Placed AFTER the cancel check above so
                // a cancel arriving while winding down is still honored, and BEFORE
                // the HITL gate below: a phase this run has decided to abandon must
                // not also pause for a human approval it will never act on (which
                // would strand the discussion AWAITING_APPROVAL, and on resume trip
                // the same ceiling again).
                if (costCeilingSynthesizeNow && phase.type() != PhaseType.SYNTHESIS) {
                    continue;
                }

                // I2: the previous repeat's contributions, the baseline the
                // convergence judge compares against. Scoped to this phase — a
                // comparison across two different phases would be meaningless.
                List<TranscriptEntry> previousRepeatEntries = null;

                for (int repeat = 0; repeat < Math.max(phase.repeats(), 1); repeat++) {

                    // --- maxTurns safety cap ---
                    if (turnCounter.get() >= maxTurns) {
                        LOGGER.warnf("Max turns (%d) exceeded for group %s — skipping remaining phases",
                                maxTurns, gc.getGroupId());
                        gc.getTranscript().add(new TranscriptEntry(
                                null, "System", null, phaseIdx, phase.name(),
                                TranscriptEntryType.SKIPPED, Instant.now(),
                                "Max turns (%d) exceeded — remaining phases skipped".formatted(maxTurns),
                                null));
                        break;
                    }

                    gc.setCurrentPhaseIndex(phaseIdx);
                    gc.setCurrentPhaseName(phase.name());

                    // C2 fix: emit phase_start FIRST, then synthesis_start (correct semantic
                    // ordering)
                    if (listener != null) {
                        listener.onPhaseStart(
                                new GroupConversationEventSink.PhaseStartEvent(phaseIdx, phase.name(), phase.type().name(), phase.participants()));
                    }

                    if (phase.type() == PhaseType.SYNTHESIS) {
                        gc.setState(GroupConversationState.SYNTHESIZING);
                        if (listener != null) {
                            listener.onSynthesisStart(new GroupConversationEventSink.SynthesisStartEvent(config.getModeratorAgentId()));
                        }
                    }

                    List<GroupMember> speakers = resolveParticipants(phase, rosterWithRecruits(config, gc), config.getModeratorAgentId());

                    // F2: consume a mid-phase speaker bookmark, if this is the exact
                    // (phaseIdx, repeat) it names. Read-and-clear together so a stale
                    // offset can never bleed into a later phase/repeat within this same
                    // leg — the bookmark is only ever valid for the one speaker-list it
                    // was taken from. GroupHitlCoordinator's config-drift validation is
                    // what guarantees this matches on the very first (phaseIdx, repeat)
                    // this leg visits, before executeDiscussion is ever called.
                    int startSpeakerIdx = 0;
                    GroupConversation.ResumePoint resumePoint = gc.getResumePoint();
                    if (resumePoint != null) {
                        gc.setResumePoint(null);
                        if (resumePoint.phaseIdx() == phaseIdx && resumePoint.repeatIdx() == repeat) {
                            startSpeakerIdx = resumePoint.speakerIdx();
                        }
                    }

                    // I2: mark where this repeat's entries begin. TranscriptEntry
                    // carries phaseIndex but no repeat index, so with repeats > 1 the
                    // only way to say "what this repeat produced" is by position.
                    int transcriptSizeBeforeRepeat = gc.getTranscript().size();

                    // --- Task-oriented phase routing ---
                    if (phase.type() == PhaseType.PLAN || phase.type() == PhaseType.EXECUTE || phase.type() == PhaseType.VERIFY) {
                        executeTaskPhase(gc, config, speakers, phase, protocol, question, phaseIdx, listener, turnCounter, maxTurns);
                    } else if (phase.targetEachPeer()) {
                        phaseExecutionEngine.executePeerTargetedPhase(gc, config, speakers, phase, protocol, question, phaseIdx, listener,
                                turnCounter,
                                maxTurns);
                    } else if (phase.turnOrder() == TurnOrder.PARALLEL) {
                        // F2: PARALLEL never honors a speaker offset — see
                        // GroupConversation.ResumePoint's Javadoc for why a parallel
                        // resume always re-runs its whole fan-out instead.
                        executeParallelPhase(gc, config, speakers, phase, protocol, question, phaseIdx, listener, turnCounter, maxTurns);
                    } else {
                        phaseExecutionEngine.executeSequentialPhase(gc, config, speakers, phase, protocol, question, phaseIdx, listener, turnCounter,
                                maxTurns, startSpeakerIdx);
                    }

                    // I1: a phase executor hit the cost ceiling and stopped scheduling
                    // turns. Read-and-clear the signal it left, then act on the policy:
                    // ABORT fails the discussion here; SYNTHESIZE_NOW falls through to
                    // the phase loop's own skip-ahead guard, which jumps to the next
                    // remaining SYNTHESIS phase so the run still produces an answer.
                    if (gc.getCostCeilingOutcome() != null) {
                        counterGroupCostCeilingHit.increment();
                        var costPolicy = gc.getCostCeilingOutcome();
                        gc.setCostCeilingOutcome(null);
                        // %s, not %.2f: warnf formats with the JVM's default locale, so
                        // a decimal-comma locale would render the same spend
                        // differently across a fleet (same reason GroupCostLedger's
                        // transcript message pins Locale.ROOT).
                        LOGGER.warnf("Cost ceiling reached for group %s at phase %d (spend $%s) — policy %s",
                                gc.getGroupId(), phaseIdx, gc.getTotalCost(), costPolicy);
                        if (costPolicy == ProtocolConfig.CostPolicy.ABORT) {
                            failConversation(gc);
                            if (listener != null) {
                                listener.onGroupError(new GroupConversationEventSink.GroupErrorEvent(
                                        "Discussion aborted: cost ceiling reached"));
                            }
                            return gc;
                        }
                        costCeilingSynthesizeNow = true;
                        // Leave the REPEAT loop too, not just future phases: a phase
                        // with repeats > 1 (ROUND_TABLE's "Discussion" is repeats =
                        // rounds - 1 by default) would otherwise re-enter its executor
                        // for every remaining repeat, re-trip the same gate, and append
                        // one more identical SKIPPED entry and one more metric
                        // increment per repeat — reporting a single overspend as many.
                        break;
                    }

                    // #27/#45: a cross-pod cancel/ABORT flips the persisted state to
                    // CANCELLED/FAILED while this leg runs. The periodic write below
                    // is a whole-document store from this leg's in-memory copy — an
                    // unconditional write would resurrect IN_PROGRESS and also clobber
                    // transcript entries appended by the terminal writer. Honor a
                    // cross-pod terminal flip at this phase boundary instead.
                    if (persistedTerminalOverride(gc, listener)) {
                        return conversationStore.read(gc.getId());
                    }

                    // I2: convergence is decided BEFORE the persist below so the
                    // CONVERGENCE transcript entry it may write is included in the
                    // same document write, but the break happens AFTER it — the
                    // converged repeat is a real, completed repeat and must still
                    // persist and fire onPhaseComplete like any other. Deliberately
                    // different from the cost-ceiling break above, which skips both
                    // because that repeat did NOT complete.
                    PhaseOutcome outcome = PhaseOutcome.cont();
                    // Task phases rewrite entries in place rather than appending, so a
                    // positional slice does not describe them; convergence is an
                    // opinion-round concept anyway.
                    boolean sliceablePhase = phase.type() != PhaseType.PLAN && phase.type() != PhaseType.EXECUTE
                            && phase.type() != PhaseType.VERIFY;
                    List<TranscriptEntry> repeatEntries = List.of();
                    if (sliceablePhase) {
                        synchronized (gc.getTranscript()) {
                            int size = gc.getTranscript().size();
                            repeatEntries = transcriptSizeBeforeRepeat <= size
                                    ? List.copyOf(gc.getTranscript().subList(transcriptSizeBeforeRepeat, size))
                                    : List.of();
                        }
                        outcome = phaseExecutionEngine.checkConvergence(gc, config, phase, protocol, phaseIdx, repeat, speakers,
                                repeatEntries, previousRepeatEntries, listener, turnCounter, maxTurns);
                    }

                    // I4: the minority report. Placed AFTER the convergence slice above
                    // and BEFORE the persist below — after, so the DISSENT entries do
                    // not land inside repeatEntries where the convergence judge would
                    // read them as this round's contributions (and where a lone
                    // abstaining synthesizer would make "1 of 1 abstained" true);
                    // before, so the entries and the DecisionRecord share one document
                    // write. It also sits after the cost-ceiling block, so a discussion
                    // that blew its budget does not then pay for N more turns.
                    //
                    // Only on the LAST repeat: a synthesis phase with repeats > 1 would
                    // otherwise run the whole round once per repeat, duplicating every
                    // dissent in both the transcript and the DecisionRecord. Dissent is
                    // a reaction to the final synthesis, not to each draft of it.
                    // The LAST repeat, or an earlier one that is ending the phase anyway.
                    // Keying only off `lastRepeat` meant a phase that converged (or whose
                    // participants all abstained) on repeat 1 of 3 broke out below without
                    // ever recording its verdict or running its dissent round — and with no
                    // DecisionRecord, the answer extraction then handed the caller the raw
                    // judgment JSON, which is the exact defect I3's rendering exists to
                    // prevent.
                    boolean lastRepeat = repeat == Math.max(phase.repeats(), 1) - 1 || !outcome.isContinue();
                    if (phase.type() == PhaseType.SYNTHESIS && lastRepeat) {
                        // I3: read the judgment into DecisionRecord BEFORE the dissent
                        // round, so dissents merge onto the verdict instead of the
                        // verdict landing on top of a dissent-only record. Same
                        // last-repeat reasoning as below — a draft judgment is not the
                        // decision.
                        phaseExecutionEngine.recordDebateVerdict(gc, config, phase, phaseIdx, speakers);
                        if (config.isRecordDissents()) {
                            phaseExecutionEngine.runDissentRound(gc, config, phase, protocol, phaseIdx, speakers, listener,
                                    turnCounter, maxTurns);
                        }
                    }

                    gc.setLastModified(Instant.now());
                    conversationStore.update(gc);

                    if (listener != null) {
                        listener.onPhaseComplete(new GroupConversationEventSink.PhaseCompleteEvent(phaseIdx, phase.name()));
                    }

                    if (!outcome.isContinue()) {
                        LOGGER.infof("Phase '%s' of group %s ended early after repeat %d: %s",
                                phase.name(), gc.getGroupId(), repeat, outcome.reason());
                        if (outcome.signal() == PhaseOutcome.PhaseExitSignal.END_DISCUSSION) {
                            // Nothing produces this yet (I12's facilitator will). Handled
                            // rather than ignored so the signal cannot be added later and
                            // silently behave as END_REPEATS.
                            endDiscussionEarly = true;
                        }
                        break;
                    }
                    previousRepeatEntries = repeatEntries;
                }

                // R1: Check for cancel BEFORE the HITL gate. After the wave loop
                // breaks on a cancel signal, control reaches here before the next
                // phase-loop iteration's cancel check — without this, the pause
                // gate below would commit a pause for a cancelled discussion.
                {
                    var cancelToken = activeTokens.get(gc.getId());
                    if (cancelToken != null && cancelToken.isCancelled()) {
                        gc.setState(GroupConversationState.CANCELLED);
                        gc.setLastModified(Instant.now());
                        conversationStore.update(gc);
                        LOGGER.infof("Group discussion %s cancelled before HITL gate at phase %d", gc.getId(), phaseIdx);
                        notifyCancelled(gc, listener);
                        return gc;
                    }
                }

                // --- HITL gates: PHASE and TASK are mutually exclusive ---
                // MAJOR-1: Only check phase.requiresApproval() for the relevant granularity.
                // TASK-level: gate on requiresApproval() AND taskLevelHitl AND tasks awaiting.
                // PHASE-level: gate on requiresApproval() AND NOT taskLevelHitl.
                // Phase 5b: TASK granularity only applies to EXECUTE phases (they have
                // a SharedTaskList). Non-EXECUTE phases fall back to PHASE-style pause.
                if (phase.requiresApproval()) {
                    if (taskLevelHitl && phase.type() == PhaseType.EXECUTE) {
                        // TASK granularity: pause if tasks await approval — or if an
                        // aborted wave (timeout/error) left executable tasks behind.
                        // Falling through with unexecuted tasks would run VERIFY and
                        // synthesis over incomplete work and silently skip the rest.
                        boolean awaiting = gc.getTaskList() != null && gc.getTaskList().hasAwaitingApproval();
                        boolean unfinished = gc.getTaskList() != null && !gc.getTaskList().findExecutableTasks().isEmpty();
                        if (awaiting || unfinished) {
                            // #4: no-progress guard. A resume that re-pauses at the same
                            // phase with an identical task-state fingerprint made zero
                            // progress (exhausted turn budget leaving ASSIGNED tasks, or
                            // ASSIGNED tasks whose agentId no longer resolves). Re-pausing
                            // would loop forever — unbounded under AUTO_APPROVE. Fail
                            // instead, which guarantees termination.
                            String fingerprint = taskPauseFingerprint(gc, phaseIdx);
                            if (fingerprint.equals(gc.getHitlLastPauseFingerprint())) {
                                failDiscussionNoProgress(gc, phaseIdx, phase, listener);
                                return gc;
                            }
                            gc.setHitlLastPauseFingerprint(fingerprint);
                            if (!awaiting) {
                                LOGGER.warnf("EXECUTE phase %d of GC %s ended with executable task(s) left "
                                        + "(aborted wave) — pausing for human review instead of skipping them",
                                        phaseIdx, gc.getId());
                            }
                            commitPause(gc, phaseIdx, phase, "TASK", turnCounter.get(), listener, config);
                            convertPauseToCancelIfSignalled(gc, listener);
                            return gc;
                        }
                    } else {
                        // PHASE granularity (or non-EXECUTE with TASK config → fallback)
                        commitPause(gc, phaseIdx, phase, "PHASE", turnCounter.get(), listener, config);
                        convertPauseToCancelIfSignalled(gc, listener);
                        return gc;
                    }
                }

                // Check again after inner repeat loop in case maxTurns was hit mid-repeat
                if (turnCounter.get() >= maxTurns) {
                    break;
                }

                // I2: END_DISCUSSION ends the phase loop itself, unlike END_REPEATS
                // which only ended the repeat loop above. Placed after the HITL gate so
                // an approval that was already due is still honored.
                if (endDiscussionEarly) {
                    LOGGER.infof("Group discussion %s ending early after phase %d on an END_DISCUSSION signal", gc.getId(), phaseIdx);
                    break;
                }
            }

            // Extract synthesis from the last SYNTHESIS phase entry.
            //
            // I3: when that entry is a debate judgment, its content is the JSON the
            // judge was asked for — correct as the transcript's record of what the
            // agent said (and the only form the signature covers), but not something
            // to hand a caller as the discussion's answer. The rendered outcome is
            // substituted here rather than by rewriting the entry, so the transcript
            // keeps the agent's own words and the verifiable signature over them.
            gc.getTranscript().stream().filter(e -> e.type() == TranscriptEntryType.SYNTHESIS && e.content() != null)
                    .reduce((first, second) -> second) // last one
                    .ifPresent(e -> gc.setSynthesizedAnswer(
                            DebateVerdictParser.isRenderedFrom(gc.getDecision(), e.content())
                                    ? gc.getDecision().outcome()
                                    : e.content()));

            // I1: SYNTHESIZE_NOW promises the run still concludes with an answer, but
            // it can only deliver one if a SYNTHESIS phase actually remained after the
            // ceiling fired (a DELPHI-style config of pure OPINION rounds, or a resume
            // that had already passed its synthesis, has none). Completing silently
            // with a null answer would look like an ordinary success to every caller;
            // say so explicitly instead.
            if (costCeilingSynthesizeNow && gc.getSynthesizedAnswer() == null) {
                LOGGER.warnf("Group %s hit its cost ceiling with no remaining SYNTHESIS phase — completing without an answer", gc.getGroupId());
                gc.getTranscript().add(new TranscriptEntry(
                        null, "System", null, gc.getCurrentPhaseIndex(), gc.getCurrentPhaseName(),
                        TranscriptEntryType.ERROR, Instant.now(),
                        "Cost ceiling reached and no SYNTHESIS phase remained — the discussion ends without a synthesized answer",
                        null));
                if (listener != null) {
                    listener.onGroupError(new GroupConversationEventSink.GroupErrorEvent(
                            "Cost ceiling reached with no synthesis phase remaining — no answer was produced"));
                }
            }

            // Don't overwrite AWAITING_APPROVAL with COMPLETED
            if (gc.getState() == GroupConversationState.AWAITING_APPROVAL) {
                return gc;
            }
            // #27/#45: complete with a CAS on the running state this leg believes it
            // holds (IN_PROGRESS or SYNTHESIZING). If a cross-pod cancel/ABORT
            // already flipped the persisted state, the CAS fails and we honor the
            // terminal state instead of resurrecting a completed answer for work a
            // human tried to stop.
            var expectedRunningState = gc.getState();
            gc.setState(GroupConversationState.COMPLETED);
            gc.setPausedTurnCount(0); // Clear turn budget state on successful completion
            gc.setHitlLastPauseFingerprint(null); // #4: reset no-progress guard
            gc.setLastModified(Instant.now());
            try {
                conversationStore.updateIfState(gc, expectedRunningState);
            } catch (IResourceStore.ResourceModifiedException e) {
                LOGGER.infof("Group discussion %s was terminated elsewhere (expected %s) — not overwriting with COMPLETED",
                        gc.getId(), expectedRunningState);
                var persisted = conversationStore.read(gc.getId());
                // This leg optimistically set COMPLETED before the CAS; align the
                // in-memory state with the terminal value the racing writer committed so
                // the finally cleans up ephemeral agents for a CANCELLED/FAILED outcome.
                gc.setState(persisted.getState());
                if (listener != null && persisted.getState() == GroupConversationState.CANCELLED) {
                    notifyCancelled(persisted, listener);
                }
                return persisted;
            } catch (IGroupConversationStore.GroupConversationGoneException e) {
                // deleted while the leg was running — nothing to persist into
                LOGGER.infof("Group discussion %s was deleted while running — discarding its result", gc.getId());
                return gc;
            }

            if (listener != null) {
                listener.onGroupComplete(new GroupConversationEventSink.GroupCompleteEvent(gc.getState(), gc.getSynthesizedAnswer()));
            }

            return gc;

        } catch (GroupDiscussionException e) {
            // R2: If the exception was caused by a cancel, route to CANCELLED
            var cancelToken = activeTokens.get(gc.getId());
            if (cancelToken != null && cancelToken.isCancelled()) {
                gc.setState(GroupConversationState.CANCELLED);
                gc.setLastModified(Instant.now());
                conversationStore.update(gc);
                notifyCancelled(gc, listener);
                return gc;
            }
            LOGGER.errorf(e, "Group discussion %s failed", LogSanitizer.sanitize(gc.getId()));
            failConversation(gc);
            if (listener != null) {
                // Curated: the raw exception text (LLM/DB/driver detail, and possibly the
                // caller's own input) must never be pushed to an SSE client — it is logged
                // above and the exception is rethrown for the non-streaming callers.
                listener.onGroupError(new GroupConversationEventSink.GroupErrorEvent(
                        "The group discussion failed."));
            }
            // Every GroupDiscussionException thrown inside the phase loop is an execution
            // failure (agent unavailable/unreachable/timeout, quota, config) — never a
            // state/concurrency conflict. Re-throw as GroupExecutionException so it maps to
            // 5xx at REST, preserving a more specific subtype (e.g. GroupTimeoutException).
            if (e instanceof GroupExecutionException) {
                throw e;
            }
            throw new GroupExecutionException(e.getMessage(), e);
        } catch (Exception e) {
            // R2: If the exception was caused by a cancel, route to CANCELLED
            var cancelToken = activeTokens.get(gc.getId());
            if (cancelToken != null && cancelToken.isCancelled()) {
                gc.setState(GroupConversationState.CANCELLED);
                gc.setLastModified(Instant.now());
                conversationStore.update(gc);
                notifyCancelled(gc, listener);
                return gc;
            }
            LOGGER.errorf(e, "Group discussion %s failed", LogSanitizer.sanitize(gc.getId()));
            failConversation(gc);
            if (listener != null) {
                // Curated — see above.
                listener.onGroupError(new GroupConversationEventSink.GroupErrorEvent(
                        "The group discussion failed."));
            }
            throw new GroupExecutionException("Group discussion failed: " + e.getMessage(), e);
        } finally {
            timerGroupDiscussion.record(System.nanoTime() - startTime, TimeUnit.NANOSECONDS);
            // I1: fold this leg's spend into the lifetime gauge. Recorded as a delta
            // against what this leg started with, so a resumed leg (whose gc arrives
            // already carrying the pre-pause total) contributes only what it newly
            // spent rather than double-counting the earlier leg's.
            groupCostDollars.add(Math.max(0.0, gc.getTotalCost() - costAtLegStart));
            // F1: unconditional, like the control-token removal below — a paused
            // discussion must not stay resolvable as "live" once this leg has
            // decided to stop. commitPause() persists AWAITING_APPROVAL and then
            // returns immediately (every commitPause call site is followed by
            // `return gc;`), which triggers this finally before the method returns
            // to ITS caller — so by the time anything outside this call observes
            // the pause, the registry is already clean. There is a narrow window,
            // inside this same call, between commitPause's persist and this
            // unregister where the store says paused but the registry still says
            // running; harmless, because the phase loop has already produced every
            // member turn it is going to for this leg by the time commitPause runs
            // — nothing remains to look the registry up.
            if (liveDiscussionRegistry != null) {
                liveDiscussionRegistry.unregister(gc.getId());
            }
            // NEW-2: Always remove the control token — paused conversations have no
            // running thread, so a lingering token causes cancel-of-paused to take
            // the no-op signal branch. Resume re-registers a fresh token. Re-check the
            // removed token so a cancel that raced this remove is not silently dropped.
            removeTokenAndConvertIfSignalled(gc, listener);
            // Drop the incremental verification cursor once this leg ends, but keep it
            // across an HITL pause so a resume continues from where it left off.
            if (gc.getState() != GroupConversationState.AWAITING_APPROVAL) {
                signingGuard.forgetConversation(gc.getId());
            }
            // Defer ephemeral cleanup to closeGroupConversation()/deleteGroupConversation()
            // for COMPLETED rounds so follow-ups and continuations can reuse
            // dynamically-created agents; keep them alive while AWAITING_APPROVAL (the
            // discussion will resume). Clean up immediately only on terminal states with
            // no follow-up or close path (FAILED, CANCELLED).
            if (gc.getState() == GroupConversationState.FAILED
                    || gc.getState() == GroupConversationState.CANCELLED) {
                cleanupEphemeralAgents(gc, config);
            }
        }
    }

    // =================================================================
    // HITL pause/cancel/timeout helpers — kept as declared delegators (not
    // inlined at their call sites) since executeDiscussion, deleteGroupConversation
    // and characterization tests reach them via direct calls or reflection.
    // Moved into GroupHitlCoordinator (Wave R, R1 step 7).
    // =================================================================

    private void notifyCancelled(GroupConversation gc, GroupDiscussionEventListener listener) {
        hitlCoordinator.notifyCancelled(gc, listener);
    }

    private boolean persistedTerminalOverride(GroupConversation gc, GroupDiscussionEventListener listener) {
        return hitlCoordinator.persistedTerminalOverride(gc, listener);
    }

    private void commitPause(GroupConversation gc, int phaseIdx,
                             AgentGroupConfiguration.DiscussionPhase phase,
                             String granularity, int currentTurnCount,
                             GroupDiscussionEventListener listener,
                             AgentGroupConfiguration config)
            throws IResourceStore.ResourceStoreException {
        hitlCoordinator.commitPause(gc, phaseIdx, phase, granularity, currentTurnCount, listener, config);
    }

    private String taskPauseFingerprint(GroupConversation gc, int phaseIdx) {
        return hitlCoordinator.taskPauseFingerprint(gc, phaseIdx);
    }

    private void failDiscussionNoProgress(GroupConversation gc, int phaseIdx, DiscussionPhase phase,
                                          GroupDiscussionEventListener listener)
            throws IResourceStore.ResourceStoreException {
        hitlCoordinator.failDiscussionNoProgress(gc, phaseIdx, phase, listener);
    }

    private void convertPauseToCancelIfSignalled(GroupConversation gc, GroupDiscussionEventListener listener) {
        hitlCoordinator.convertPauseToCancelIfSignalled(gc, listener);
    }

    private void removeTokenAndConvertIfSignalled(GroupConversation gc, GroupDiscussionEventListener listener) {
        hitlCoordinator.removeTokenAndConvertIfSignalled(gc, listener);
    }

    private void convertPauseToCancelIfSignalled(GroupConversation gc, GroupDiscussionEventListener listener,
                                                 DiscussionControlToken token) {
        hitlCoordinator.convertPauseToCancelIfSignalled(gc, listener, token);
    }

    private void scheduleGroupHitlTimeout(GroupConversation gc) {
        hitlCoordinator.scheduleGroupHitlTimeout(gc);
    }

    // =================================================================
    // Post-discussion lifecycle ops — follow-up/continue/close/read/delete/list,
    // pending approvals, ephemeral cleanup. Kept as declared delegators (not
    // inlined) since they're the IGroupConversationService public surface (or, for
    // cleanupEphemeralAgents, reflected + called back by GroupHitlCoordinator).
    // Moved into GroupLifecycleOps (Wave R, R1 step 8); operationsInProgress and
    // activeTokens stay here, shared by reference — see the class Javadoc there.
    // =================================================================

    @Override
    public GroupConversation readGroupConversation(String groupConversationId)
            throws IResourceStore.ResourceNotFoundException, IResourceStore.ResourceStoreException {
        return lifecycleOps().readGroupConversation(groupConversationId);
    }

    @Override
    public void deleteGroupConversation(String groupConversationId)
            throws GroupDiscussionException, IResourceStore.ResourceStoreException {
        lifecycleOps().deleteGroupConversation(groupConversationId);
    }

    @Override
    public List<GroupConversation> listGroupConversations(String groupId, int index, int limit) throws IResourceStore.ResourceStoreException {
        return lifecycleOps().listGroupConversations(groupId, index, limit);
    }

    @Override
    public GroupConversation followUpWithMember(String groupConversationId, String targetAgentId,
                                                String question)
            throws GroupDiscussionException, IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {
        rejectIfShuttingDown();
        return lifecycleOps().followUpWithMember(groupConversationId, targetAgentId, question);
    }

    @Override
    public GroupConversation continueDiscussion(String groupConversationId, String question,
                                                GroupDiscussionEventListener listener)
            throws GroupDiscussionException, IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {
        rejectIfShuttingDown();
        return lifecycleOps().continueDiscussion(groupConversationId, question, listener);
    }

    @Override
    public GroupConversation closeGroupConversation(String groupConversationId)
            throws GroupDiscussionException, IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {
        return lifecycleOps().closeGroupConversation(groupConversationId);
    }

    @Override
    public List<ai.labs.eddi.engine.model.PendingApprovalSummary> listGroupPendingApprovals(String groupId, int limit)
            throws IResourceStore.ResourceStoreException {
        return lifecycleOps().listGroupPendingApprovals(groupId, limit);
    }

    // Public: called back from GroupHitlCoordinator.cleanupAfterTerminalState
    // (Wave R, R1 step 7).
    public void cleanupEphemeralAgents(GroupConversation gc, AgentGroupConfiguration config) {
        lifecycleOps().cleanupEphemeralAgents(gc, config);
    }

    private void failConversation(GroupConversation gc) {
        lifecycleOps().failConversation(gc);
    }

    private GroupLifecycleOps lifecycleOps() {
        return new GroupLifecycleOps(conversationStore, groupStore, conversationService, agentFactory, agentStore,
                deploymentStore, operationsInProgress, activeTokens, this,
                counterGroupFollowUp, counterGroupContinue, counterGroupClose, counterGroupFailure);
    }

    public static void propagateDynamicAgentTracking(
                                                     ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot snapshot,
                                                     GroupConversation gc) {
        GroupLifecycleOps.propagateDynamicAgentTracking(snapshot, gc);
    }

    // =================================================================
    // Phase resolution
    // =================================================================

    // Public: called back from GroupHitlCoordinator.resumeDiscussion (Wave R, R1
    // step 7).
    public List<DiscussionPhase> resolvePhases(AgentGroupConfiguration config) {
        // Custom phases take priority
        if (config.getPhases() != null && !config.getPhases().isEmpty()) {
            return config.getPhases();
        }

        // Expand style preset
        DiscussionStyle style = config.getStyle() != null ? config.getStyle() : DiscussionStyle.ROUND_TABLE;
        return DiscussionStylePresets.expand(style, config.getMaxRounds());
    }

    private ProtocolConfig resolveProtocol(AgentGroupConfiguration config) {
        return config.getProtocol() != null
                ? config.getProtocol()
                : new ProtocolConfig(60, ProtocolConfig.MemberFailurePolicy.SKIP, 2, ProtocolConfig.MemberUnavailablePolicy.SKIP);
    }

    /**
     * Resolve the per-agent timeout (seconds) for a follow-up turn from the group's
     * protocol config, so follow-ups honor the same configurable limit as
     * discussion turns. Defaults to 60 if the config cannot be loaded.
     * <p>
     * Public: called back from GroupLifecycleOps.followUpWithMember (Wave R, R1
     * step 8).
     */
    public int resolveAgentTimeoutSeconds(GroupConversation gc) {
        try {
            IResourceStore.IResourceId currentGroupId = groupStore.getCurrentResourceId(gc.getGroupId());
            if (currentGroupId != null) {
                AgentGroupConfiguration config = groupStore.read(gc.getGroupId(), currentGroupId.getVersion());
                if (config != null) {
                    int timeout = resolveProtocol(config).agentTimeoutSeconds();
                    if (timeout > 0) {
                        return timeout;
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.debugf("Could not resolve agent timeout for group %s, using default: %s",
                    LogSanitizer.sanitize(gc.getGroupId()), e.getMessage());
        }
        return 60;
    }

    /**
     * Determines which members participate in a phase based on the
     * {@code participants} field: "ALL", "MODERATOR", or "ROLE:&lt;name&gt;".
     */
    /**
     * The configured roster plus anyone recruited into the discussion at runtime
     * (I7).
     * <p>
     * Unioned at the call sites rather than inside {@link #resolveParticipants} on
     * purpose: that method is resolved by exact parameter types by the
     * characterization suite, and it is a pure function of its inputs — which is
     * what makes its ALL/MODERATOR/ROLE branches testable without a live
     * discussion. Recruits are appended after the configured members, and carry
     * {@code RECRUIT_SPEAKING_ORDER}, so every existing ordering places them last
     * without renumbering anyone.
     * <p>
     * Recruits therefore take effect from the NEXT phase iteration. Mutating a
     * roster mid-phase would desynchronise the speaker index F2's resume bookmark
     * points into, and move the denominator I2's convergence check and I4's
     * unanimity test already computed for the round in flight.
     */
    public List<GroupMember> rosterWithRecruits(AgentGroupConfiguration config, GroupConversation gc) {
        List<GroupMember> configured = config.getMembers() != null ? config.getMembers() : List.of();
        if (gc == null || gc.getDynamicMembers() == null || gc.getDynamicMembers().isEmpty()) {
            return configured;
        }
        var combined = new ArrayList<>(configured);
        synchronized (gc.getDynamicMembers()) {
            for (GroupMember dynamic : gc.getDynamicMembers()) {
                if (dynamic != null && dynamic.agentId() != null
                        && combined.stream().noneMatch(m -> dynamic.agentId().equals(m.agentId()))) {
                    combined.add(dynamic);
                }
            }
        }
        return combined;
    }

    public List<GroupMember> resolveParticipants(DiscussionPhase phase, List<GroupMember> allMembers, String moderatorAgentId) {
        String participants = phase.participants() != null ? phase.participants() : "ALL";

        if ("MODERATOR".equalsIgnoreCase(participants)) {
            if (moderatorAgentId == null || moderatorAgentId.isBlank()) {
                // I3(a): falling back to ALL used to make every member speak in the
                // synthesis phase, and executeDiscussion takes the LAST SYNTHESIS entry
                // as the answer — so the conclusion of a moderator-less discussion was
                // decided by speaking order, not by anything about the content. Whoever
                // happened to go last won, silently.
                //
                // One deterministic synthesizer instead: first by speakingOrder, the
                // same ordering every other phase already uses. This is a behavior
                // change, and deliberately so — the old behavior had no defensible
                // reading. Configs are not rejected at save time (old ones must keep
                // loading); AgentGroupStore logs a warning instead.
                List<GroupMember> ordered = orderedBySpeakingOrder(allMembers);
                if (ordered.isEmpty()) {
                    LOGGER.warnf("Phase '%s' requires MODERATOR but neither a moderator nor any member is configured", phase.name());
                    return List.of();
                }
                GroupMember synthesizer = ordered.get(0);
                LOGGER.warnf("Phase '%s' requires MODERATOR but none is configured — using '%s' (first by speakingOrder) as the sole "
                        + "synthesizer. Configure moderatorAgentId to choose deliberately.", phase.name(), synthesizer.agentId());
                return List.of(synthesizer);
            }
            return List.of(new GroupMember(moderatorAgentId, "Moderator", 0, "MODERATOR"));
        }

        if (participants.toUpperCase().startsWith("ROLE:")) {
            String role = participants.substring(5).trim();
            List<GroupMember> filtered = allMembers.stream().filter(m -> role.equalsIgnoreCase(m.role()))
                    .sorted(Comparator.comparing(m -> m.speakingOrder() != null ? m.speakingOrder() : Integer.MAX_VALUE)).toList();
            if (filtered.isEmpty()) {
                LOGGER.warnf("Phase '%s' requires ROLE:%s but no members " + "have that role, falling back to ALL", phase.name(), role);
                return allMembers;
            }
            return filtered;
        }

        // ALL
        return orderedBySpeakingOrder(allMembers);
    }

    /**
     * Members in speaking order, unset orders last. The one ordering the whole
     * engine uses, extracted so the MODERATOR fallback and ALL cannot disagree
     * about who "first" is.
     */
    private static List<GroupMember> orderedBySpeakingOrder(List<GroupMember> allMembers) {
        if (allMembers == null) {
            return List.of();
        }
        return allMembers.stream()
                .sorted(Comparator.comparing(m -> m.speakingOrder() != null ? m.speakingOrder() : Integer.MAX_VALUE))
                .toList();
    }

    // =================================================================
    // Cooperative cancellation of in-flight member turns
    // =================================================================

    /**
     * Cooperative cancellation handle shared by the member turns of a single
     * parallel batch (a debate phase batch or a task-execution wave).
     * <p>
     * {@link CompletableFuture#cancel(boolean)} does <em>not</em> interrupt the
     * body of a {@code runAsync}/{@code supplyAsync} task — the JDK documents
     * {@code mayInterruptIfRunning} as having no effect there. A "cancelled" member
     * thread would therefore keep running and keep mutating the group document
     * (transcript, task list, error list) long after the orchestrator gave up on it
     * and persisted the document. Cancellation must be cooperative instead: the
     * turn checks this token at its own await points and before every write.
     * <p>
     * The lever is the response future the member turn blocks on — completing it
     * exceptionally releases the turn immediately, without waiting for the agent's
     * own timeout.
     * <p>
     * Public (not package-private):
     * {@link ai.labs.eddi.engine.internal.groups.MemberTurnExecutor}, in the
     * {@code .groups} subpackage, checks/registers against this token on every
     * member turn.
     */
    public static final class MemberTurnCancellation {

        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final Set<CompletableFuture<?>> awaited = ConcurrentHashMap.newKeySet();

        public boolean isCancelled() {
            return cancelled.get();
        }

        /**
         * Register a future a member turn is about to block on. If cancellation already
         * happened, the future is released right away — closing the race between
         * {@link #cancel()} and a turn reaching its await point.
         */
        public void register(CompletableFuture<?> future) {
            awaited.add(future);
            if (cancelled.get()) {
                future.completeExceptionally(new MemberTurnCancelledException());
            }
        }

        public void unregister(CompletableFuture<?> future) {
            awaited.remove(future);
        }

        /** Signal cancellation and release every member turn currently waiting. */
        public void cancel() {
            cancelled.set(true);
            for (var future : awaited) {
                future.completeExceptionally(new MemberTurnCancelledException());
            }
        }
    }

    /**
     * Thrown out of a member turn that was cooperatively cancelled. It is never
     * retried and never converted into a transcript entry by the member thread —
     * the orchestrator owns the group document from the moment it cancels.
     * <p>
     * Public for the same reason as {@link MemberTurnCancellation}.
     */
    public static final class MemberTurnCancelledException extends RuntimeException {

        public MemberTurnCancelledException() {
            super("Member turn cancelled by the group orchestrator");
        }
    }

    // Moved to TaskForceEngine (its only real call site, inside
    // executeTaskExecutionPhase); kept as a declared static delegator here
    // because GroupConversationServiceConcurrencyTest reflects into it
    // directly via GroupConversationService.class.getDeclaredMethod(...) and
    // invokes it statically (target=null).
    private static boolean reserveTurn(AtomicInteger turnCounter, int maxTurns) {
        return TaskForceEngine.reserveTurn(turnCounter, maxTurns);
    }

    /**
     * Wall-clock budget a parallel batch gets before the orchestrator gives up on
     * the speakers still running.
     * <p>
     * It is derived from what ONE member turn may legitimately consume — its
     * per-attempt {@code agentTimeoutSeconds} multiplied by the number of attempts
     * {@code onAgentFailure} allows (only {@code RETRY} retries, and it retries at
     * most {@code maxRetries} times) — plus {@link #parallelBatchGraceSeconds}. The
     * normalisation of both protocol values is deliberately identical to
     * {@code executeAgentTurn}'s: if the orchestrator's deadline is shorter than
     * the member's own, the member's timeout handling (retry / abort / attributed
     * SKIP) becomes unreachable.
     *
     * @return the batch budget in seconds, capped at
     *         {@link #MAX_PARALLEL_BATCH_BUDGET_SECONDS}
     */
    public static long parallelBatchBudgetSeconds(ProtocolConfig protocol) {
        long timeout = protocol.agentTimeoutSeconds() > 0 ? protocol.agentTimeoutSeconds() : DEFAULT_AGENT_TIMEOUT_SECONDS;
        long attempts = protocol.onAgentFailure() == ProtocolConfig.MemberFailurePolicy.RETRY
                ? (protocol.maxRetries() > 0 ? protocol.maxRetries() : DEFAULT_MAX_RETRIES) + 1L
                : 1L;
        return Math.min(timeout * attempts + parallelBatchGraceSeconds(timeout), MAX_PARALLEL_BATCH_BUDGET_SECONDS);
    }

    /**
     * Setup slack for a parallel batch: the larger of an absolute floor and a
     * fraction of the member's per-attempt budget.
     * <p>
     * A purely absolute grace loses the race again whenever setup outruns it, which
     * is likeliest with a SHORT configured timeout — there one second is both a big
     * share of the budget and quite possibly less than the store round trips take.
     * Scaling with the timeout keeps the orchestrator's deadline behind the
     * member's own in both directions, which is the property that makes
     * {@code executeAgentTurn}'s retry / abort / attributed-SKIP branches reachable
     * at all.
     */
    static long parallelBatchGraceSeconds(long perAttemptTimeoutSeconds) {
        return Math.max(PARALLEL_BATCH_GRACE_FLOOR_SECONDS, (long) Math.ceil(perAttemptTimeoutSeconds * PARALLEL_BATCH_GRACE_FRACTION));
    }

    // =================================================================
    // Task-oriented phase execution (TASK_FORCE style) — delegates to
    // TaskForceEngine. Kept as declared delegators (not inlined at call
    // sites): characterization tests reach them via
    // GroupConversationService.class.getDeclaredMethod(...) reflection
    // (several through a third, file-local wrapper name — see the R1 step 6
    // changelog entry for why a plain grep for one calling convention isn't
    // enough when sweeping for these).
    // =================================================================

    private void executeTaskPhase(GroupConversation gc, AgentGroupConfiguration config, List<GroupMember> speakers,
                                  DiscussionPhase phase, ProtocolConfig protocol, String question, int phaseIdx,
                                  GroupDiscussionEventListener listener, java.util.concurrent.atomic.AtomicInteger turnCounter, int maxTurns)
            throws GroupDiscussionException {
        taskForceEngine.executeTaskPhase(gc, config, speakers, phase, protocol, question, phaseIdx, listener, turnCounter, maxTurns);
    }

    private void executeTaskExecutionPhase(GroupConversation gc, AgentGroupConfiguration config, List<GroupMember> speakers,
                                           DiscussionPhase phase, ProtocolConfig protocol, String question, int phaseIdx,
                                           GroupDiscussionEventListener listener, java.util.concurrent.atomic.AtomicInteger turnCounter, int maxTurns)
            throws GroupDiscussionException {
        taskForceEngine.executeTaskExecutionPhase(gc, config, speakers, phase, protocol, question, phaseIdx, listener, turnCounter, maxTurns);
    }

    private void resetStrandedInProgressTasks(GroupConversation gc, String cause) {
        taskForceEngine.resetStrandedInProgressTasks(gc, cause);
    }

    private void executeTaskVerificationPhase(GroupConversation gc, AgentGroupConfiguration config, List<GroupMember> speakers,
                                              DiscussionPhase phase, ProtocolConfig protocol, String question, int phaseIdx,
                                              GroupDiscussionEventListener listener, java.util.concurrent.atomic.AtomicInteger turnCounter,
                                              int maxTurns)
            throws GroupDiscussionException {
        taskForceEngine.executeTaskVerificationPhase(gc, config, speakers, phase, protocol, question, phaseIdx, listener, turnCounter, maxTurns);
    }

    private String buildTaskExecutionInput(TaskItem task, String question, DiscussionPhase phase, GroupConversation gc) {
        return taskForceEngine.buildTaskExecutionInput(task, question, phase, gc);
    }

    private void parseAndApplyVerification(GroupConversation gc, List<TaskItem> completedTasks,
                                           String verifyContent, GroupDiscussionEventListener listener) {
        taskForceEngine.parseAndApplyVerification(gc, completedTasks, verifyContent, listener);
    }

    private boolean tryParseVerificationJson(GroupConversation gc, List<TaskItem> completedTasks,
                                             String content, GroupDiscussionEventListener listener) {
        return taskForceEngine.tryParseVerificationJson(gc, completedTasks, content, listener);
    }

    private String formatVerificationForDisplay(String rawContent) {
        return taskForceEngine.formatVerificationForDisplay(rawContent);
    }

    private String resolveTaskAssignment(String assignToRole, List<GroupMember> members,
                                         String moderatorAgentId, int taskIndex) {
        return taskForceEngine.resolveTaskAssignment(assignToRole, members, moderatorAgentId, taskIndex);
    }

    private void recordTaskFailure(GroupConversation gc, TaskItem task, GroupMember member,
                                   String errorMessage, int phaseIdx, DiscussionPhase phase,
                                   List<GroupDiscussionException> errors, GroupDiscussionException ex) {
        taskForceEngine.recordTaskFailure(gc, task, member, errorMessage, phaseIdx, phase, errors, ex);
    }

    private GroupMember findMember(List<GroupMember> members, String agentId) {
        return taskForceEngine.findMember(members, agentId);
    }

    private GroupMember findMemberIncludingDynamic(List<GroupMember> configMembers, GroupConversation gc, String agentId) {
        return taskForceEngine.findMemberIncludingDynamic(configMembers, gc, agentId);
    }

    // =================================================================
    // Phase execution (debate styles) — delegates to PhaseExecutionEngine
    // =================================================================

    // executeSequentialPhase/executePeerTargetedPhase have no test dependency
    // and were inlined at their call sites. executeParallelPhase is kept as a
    // declared delegator: GroupConversationServiceConcurrencyTest reaches it
    // via reflection (its own local "phaseMethod" helper, not the "method"
    // helper most other test classes use — grep for the bare method name, not
    // just one calling convention, when sweeping for these).
    private void executeParallelPhase(GroupConversation gc, AgentGroupConfiguration config, List<GroupMember> speakers, DiscussionPhase phase,
                                      ProtocolConfig protocol, String question, int phaseIdx, GroupDiscussionEventListener listener,
                                      java.util.concurrent.atomic.AtomicInteger turnCounter, int maxTurns)
            throws GroupDiscussionException {
        phaseExecutionEngine.executeParallelPhase(gc, config, speakers, phase, protocol, question, phaseIdx, listener, turnCounter, maxTurns);
    }

    // =================================================================
    // Agent turn execution
    // =================================================================

    /**
     * Runs a member turn that cannot be cancelled — sequential phases, where the
     * orchestrator thread <em>is</em> the member turn. Delegates to
     * {@link MemberTurnExecutor}.
     */
    private TranscriptEntry executeAgentTurn(GroupMember member, GroupConversation gc, String input, ProtocolConfig protocol, int phaseIdx,
                                             DiscussionPhase phase, String targetAgentId, GroupDiscussionEventListener listener)
            throws GroupDiscussionException {
        return memberTurnExecutor.executeAgentTurn(member, gc, input, protocol, phaseIdx, phase, targetAgentId, listener);
    }

    /**
     * @param cancellation
     *            cooperative cancellation token for turns that run on a worker
     *            thread, or {@code null} for turns the orchestrator runs itself.
     *            When it is signalled the turn is released from its response wait
     *            and throws {@link MemberTurnCancelledException} instead of
     *            returning an entry — see {@link MemberTurnCancellation}.
     */
    private TranscriptEntry executeAgentTurn(GroupMember member, GroupConversation gc, String input, ProtocolConfig protocol, int phaseIdx,
                                             DiscussionPhase phase, String targetAgentId, GroupDiscussionEventListener listener,
                                             MemberTurnCancellation cancellation)
            throws GroupDiscussionException {
        return memberTurnExecutor.executeAgentTurn(member, gc, input, protocol, phaseIdx, phase, targetAgentId, listener, cancellation);
    }

    // tryResolveMemberToolPause/handleMemberPause/executeGroupMemberTurn/
    // handleAgentFailure/errorEntry kept as declared delegators (not inlined at
    // call sites): characterization tests reach them via
    // GroupConversationService.class.getDeclaredMethod(...) reflection, which
    // requires the method to be declared directly on this class.

    private TranscriptEntry tryResolveMemberToolPause(GroupMember member, GroupConversation gc, String convId,
                                                      String input, int timeoutSeconds, int phaseIdx,
                                                      DiscussionPhase phase, TranscriptEntryType entryType,
                                                      String targetAgentId) {
        return memberTurnExecutor.tryResolveMemberToolPause(member, gc, convId, input, timeoutSeconds, phaseIdx, phase, entryType, targetAgentId);
    }

    private TranscriptEntry handleMemberPause(GroupMember member, GroupConversation gc, String convId,
                                              int phaseIdx, DiscussionPhase phase, String targetAgentId,
                                              GroupDiscussionEventListener listener) {
        return memberTurnExecutor.handleMemberPause(member, gc, convId, phaseIdx, phase, targetAgentId, listener);
    }

    // =================================================================
    // Phase-specific input construction — delegates to GroupContextBuilder
    // =================================================================

    private String buildPhaseInput(DiscussionPhase phase, GroupMember speaker, String question, List<TranscriptEntry> transcript, int phaseIdx,
                                   GroupMember target) {
        return contextBuilder.buildPhaseInput(phase, speaker, question, transcript, phaseIdx, target);
    }

    // Kept as a declared delegator (not inlined into buildPhaseInput's call
    // site) since a characterization test reaches it via reflection.
    private String selectDefaultTemplate(DiscussionPhase phase, List<TranscriptEntry> transcript, int phaseIdx) {
        return contextBuilder.selectDefaultTemplate(phase, transcript, phaseIdx);
    }

    // =================================================================
    // Context filtering by scope — delegates to GroupContextBuilder
    // =================================================================

    private List<Map<String, Object>> filterByScope(List<TranscriptEntry> transcript, ContextScope scope, int currentPhaseIdx, GroupMember speaker) {
        return contextBuilder.filterByScope(transcript, scope, currentPhaseIdx, speaker);
    }

    // =================================================================
    // Helpers
    // =================================================================

    private GroupConversation createGroupConversation(String groupId, String question, String userId, int depth)
            throws IResourceStore.ResourceStoreException {

        GroupConversation gc = new GroupConversation();
        gc.setGroupId(groupId);
        gc.setUserId(userId);
        gc.setState(GroupConversationState.IN_PROGRESS);
        gc.setOriginalQuestion(question);
        gc.setCurrentPhaseIndex(0);
        gc.setDepth(depth);
        gc.setCreated(Instant.now());
        gc.setLastModified(Instant.now());

        gc.getTranscript().add(new TranscriptEntry("user", "User", question, 0, "Question", TranscriptEntryType.QUESTION, Instant.now(), null, null));

        String id = conversationStore.create(gc);
        gc.setId(id);
        return gc;
    }

    // findLatestResponse/mapPhaseToEntryType kept as thin delegators (not just
    // inlined at call sites): several characterization tests reach them via
    // GroupConversationService.class.getDeclaredMethod(...) reflection, which
    // requires the method to be declared directly on this class.

    private String findLatestResponse(List<TranscriptEntry> transcript, String agentId) {
        return contextBuilder.findLatestResponse(transcript, agentId);
    }

    private TranscriptEntryType mapPhaseToEntryType(PhaseType type) {
        return contextBuilder.mapPhaseToEntryType(type);
    }

    private TranscriptEntry executeGroupMemberTurn(GroupMember member, GroupConversation gc, String input, ProtocolConfig protocol, int phaseIdx,
                                                   DiscussionPhase phase, TranscriptEntryType entryType, String targetAgentId)
            throws GroupDiscussionException {
        return memberTurnExecutor.executeGroupMemberTurn(member, gc, input, protocol, phaseIdx, phase, entryType, targetAgentId);
    }

    private TranscriptEntry handleAgentFailure(GroupMember member, int phaseIdx, DiscussionPhase phase, ProtocolConfig protocol, Throwable cause,
                                               String prefix, String targetAgentId)
            throws GroupDiscussionException {
        return memberTurnExecutor.handleAgentFailure(member, phaseIdx, phase, protocol, cause, prefix, targetAgentId);
    }

    private TranscriptEntry errorEntry(GroupMember member, int phaseIdx, DiscussionPhase phase, String message) {
        return memberTurnExecutor.errorEntry(member, phaseIdx, phase, message);
    }

    /**
     * Extracts the human-readable text from a conversation memory snapshot.
     * Delegates to {@link GroupContextBuilder}, kept as a declared method here (not
     * inlined at call sites) since a characterization test reaches it via
     * reflection, and public since GroupLifecycleOps.followUpWithMember (Wave R, R1
     * step 8) calls it back.
     */
    public String extractResponse(ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot snapshot) {
        return contextBuilder.extractResponse(snapshot);
    }

    // buildPlainTextFallback's only real caller is GroupContextBuilder's own
    // buildPhaseInput (it's public there only because this cross-package
    // delegator needs to call it); kept here as a thin delegator only because
    // a characterization test reaches it via reflection.
    private String buildPlainTextFallback(DiscussionPhase phase, GroupMember speaker, String question, List<TranscriptEntry> transcript) {
        return contextBuilder.buildPlainTextFallback(phase, speaker, question, transcript);
    }

    // Kept as a declared delegator (not inlined at its call site) since a
    // characterization test reaches it via reflection.
    private void verifyPriorEntriesIfRequired(String receivingAgentId, GroupConversation gc) {
        signingGuard.verifyPriorEntriesIfRequired(receivingAgentId, gc);
    }

    // =================================================================
    // HITL lifecycle — cancel & resume. Kept as declared delegators (not
    // inlined) since deleteGroupConversation and characterization tests reach
    // several of these directly or via reflection. Moved into
    // GroupHitlCoordinator (Wave R, R1 step 7); activeTokens stays here,
    // shared by reference with the coordinator and TaskForceEngine.
    // =================================================================

    private final ConcurrentHashMap<String, DiscussionControlToken> activeTokens = new ConcurrentHashMap<>();

    @Override
    public boolean cancelDiscussion(String conversationId, ControlSignal mode)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {
        return hitlCoordinator.cancelDiscussion(conversationId, mode);
    }

    @Override
    public GroupConversation resumeDiscussion(String groupConversationId, GroupApprovalRequest request,
                                              GroupDiscussionEventListener listener)
            throws GroupDiscussionException, IResourceStore.ResourceStoreException,
            IResourceStore.ResourceNotFoundException, IResourceStore.ResourceModifiedException {
        rejectIfShuttingDown();
        return hitlCoordinator.resumeDiscussion(groupConversationId, request, listener);
    }

    private void restoreGroupPause(GroupConversation gc, int phaseIndex, String phaseName,
                                   GroupConversation.HitlPauseType pauseType, Instant pausedAt,
                                   AgentGroupConfiguration configOrNull,
                                   HitlTimeoutPolicy fallbackTimeoutPolicy, String fallbackApprovalTimeout) {
        hitlCoordinator.restoreGroupPause(gc, phaseIndex, phaseName, pauseType, pausedAt, configOrNull,
                fallbackTimeoutPolicy, fallbackApprovalTimeout);
    }

    private void auditHitlCancellation(GroupConversation gc, ControlSignal mode) {
        hitlCoordinator.auditHitlCancellation(gc, mode);
    }

    // Public: called back from GroupLifecycleOps.deleteGroupConversation (Wave R,
    // R1 step 8).
    public void cleanupAfterTerminalState(GroupConversation gc) {
        hitlCoordinator.cleanupAfterTerminalState(gc);
    }

    // Public: called back from GroupLifecycleOps.deleteGroupConversation (Wave R,
    // R1 step 8).
    public void deleteGroupHitlTimeoutSchedule(String groupConversationId) {
        hitlCoordinator.deleteGroupHitlTimeoutSchedule(groupConversationId);
    }
}
