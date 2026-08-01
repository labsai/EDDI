/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.agents.AgentSigningService;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.engine.tenancy.QuotaExceededException;
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
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.TaskDefinition;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.TurnOrder;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.LifecyclePolicy;
import ai.labs.eddi.configs.hitl.HitlGranularity;
import ai.labs.eddi.configs.hitl.HitlRejectionPolicy;
import ai.labs.eddi.configs.hitl.HitlTimeoutPolicy;
import ai.labs.eddi.configs.groups.model.DiscussionStylePresets;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.GroupConversationState;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntry;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntryType;
import ai.labs.eddi.configs.groups.model.SharedTaskList;
import ai.labs.eddi.configs.groups.model.SharedTaskList.TaskItem;
import ai.labs.eddi.configs.groups.model.SharedTaskList.TaskStatus;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IGroupConversationService;
import ai.labs.eddi.engine.attachments.IAttachmentStore;
import ai.labs.eddi.engine.internal.groups.GroupAttachmentBinder;
import ai.labs.eddi.engine.internal.groups.GroupContextBuilder;
import ai.labs.eddi.engine.internal.groups.GroupSigningGuard;
import ai.labs.eddi.engine.internal.groups.MemberTurnExecutor;
import ai.labs.eddi.engine.internal.groups.PhaseExecutionEngine;
import ai.labs.eddi.engine.internal.groups.TaskForceEngine;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.Attachment;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.engine.model.InputData;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import ai.labs.eddi.engine.lifecycle.GroupConversationEventSink;
import ai.labs.eddi.engine.lifecycle.model.ControlSignal;
import ai.labs.eddi.engine.lifecycle.model.DiscussionControlToken;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration;
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
import java.util.stream.Collectors;

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

    // Field-injected so the direct-construction unit tests stay unchanged; used to
    // materialize and share discussion attachments with member conversations.
    @Inject
    IAttachmentStore attachmentStore;

    // Same reason. Ephemeral cleanup deletes the Agent directly, not via
    // RestAgentStore, so it has to retire the deployment record itself.
    @Inject
    IDeploymentStore deploymentStore;

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

        if (depth > maxDepth) {
            throw new GroupDepthExceededException("Maximum group discussion depth (%d) exceeded".formatted(maxDepth));
        }
        if (groupId == null) {
            throw new IllegalArgumentException("groupId must not be null");
        }

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
        return executeDiscussion(gc, config, phases, question, listener, 0);
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
    private GroupConversation executeDiscussion(GroupConversation gc, AgentGroupConfiguration config, List<DiscussionPhase> phases, String question,
                                                GroupDiscussionEventListener listener, int startPhaseIndex)
            throws GroupDiscussionException, IResourceStore.ResourceStoreException {

        long startTime = System.nanoTime();
        // MINOR-1: Only count/fire GROUP_START on fresh discussion, not resume
        if (startPhaseIndex == 0) {
            counterGroupDiscussion.increment();
        }

        ProtocolConfig protocol = resolveProtocol(config);
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

                    List<GroupMember> speakers = resolveParticipants(phase, config.getMembers(), config.getModeratorAgentId());

                    // --- Task-oriented phase routing ---
                    if (phase.type() == PhaseType.PLAN || phase.type() == PhaseType.EXECUTE || phase.type() == PhaseType.VERIFY) {
                        executeTaskPhase(gc, config, speakers, phase, protocol, question, phaseIdx, listener, turnCounter, maxTurns);
                    } else if (phase.targetEachPeer()) {
                        phaseExecutionEngine.executePeerTargetedPhase(gc, config, speakers, phase, protocol, question, phaseIdx, listener,
                                turnCounter,
                                maxTurns);
                    } else if (phase.turnOrder() == TurnOrder.PARALLEL) {
                        executeParallelPhase(gc, config, speakers, phase, protocol, question, phaseIdx, listener, turnCounter, maxTurns);
                    } else {
                        phaseExecutionEngine.executeSequentialPhase(gc, config, speakers, phase, protocol, question, phaseIdx, listener, turnCounter,
                                maxTurns);
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

                    gc.setLastModified(Instant.now());
                    conversationStore.update(gc);

                    if (listener != null) {
                        listener.onPhaseComplete(new GroupConversationEventSink.PhaseCompleteEvent(phaseIdx, phase.name()));
                    }
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
            }

            // Extract synthesis from the last SYNTHESIS phase entry
            gc.getTranscript().stream().filter(e -> e.type() == TranscriptEntryType.SYNTHESIS && e.content() != null)
                    .reduce((first, second) -> second) // last one
                    .ifPresent(e -> gc.setSynthesizedAnswer(e.content()));

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

    /**
     * Fires the cancelled event so SSE subscribers see the terminal state and can
     * close their streams (previously CancelledEvent existed but was never emitted,
     * leaving /discuss/stream clients hanging after a cancel).
     */
    private void notifyCancelled(GroupConversation gc, GroupDiscussionEventListener listener) {
        if (listener != null) {
            listener.onCancelled(new GroupConversationEventSink.CancelledEvent(
                    "Discussion cancelled", gc.getUserId()));
        }
    }

    /**
     * Cross-pod terminal-override check for a phase boundary (#27/#45). Group
     * control is per-pod (activeTokens is process-local), so a cancel/ABORT landing
     * on another pod flips only the persisted state — the running leg never sees it
     * and its next whole-document write would resurrect the running state and
     * clobber concurrent transcript writes. Re-reads the persisted state at the
     * boundary: if another writer moved it to a terminal state, the leg stops and
     * honors it (notifying the listener on a cancel). Best-effort: a store read
     * failure keeps the leg running (the local token path still applies).
     *
     * @return true if the persisted state is terminal and this leg should stop
     */
    private boolean persistedTerminalOverride(GroupConversation gc, GroupDiscussionEventListener listener) {
        try {
            var persistedState = conversationStore.read(gc.getId()).getState();
            if (persistedState == GroupConversationState.CANCELLED
                    || persistedState == GroupConversationState.FAILED
                    || persistedState == GroupConversationState.COMPLETED
                    // CLOSED is terminal too: without it, a leg that keeps running past a
                    // concurrent close would fall through to the unconditional whole-document
                    // write below and RESURRECT the closed conversation (its member
                    // conversations are already ended and its ephemeral agents deleted).
                    || persistedState == GroupConversationState.CLOSED) {
                // Align the in-memory state with the terminal value another pod/writer
                // committed so executeDiscussion's finally makes the correct ephemeral-
                // agent cleanup decision — this leg's gc is otherwise still a running
                // state (IN_PROGRESS/SYNTHESIZING) and cleanup would be skipped. (CLOSED
                // is deliberately NOT in the finally's cleanup set — close already
                // reclaimed the agents.)
                gc.setState(persistedState);
                LOGGER.infof("Group discussion %s was moved to %s elsewhere — stopping this leg at the phase boundary",
                        gc.getId(), persistedState);
                if (persistedState == GroupConversationState.CANCELLED) {
                    notifyCancelled(gc, listener);
                }
                return true;
            }
        } catch (Exception e) {
            LOGGER.debugf("Phase-boundary persisted-state re-check failed for %s: %s (continuing)",
                    gc.getId(), e.getMessage());
        }
        return false;
    }

    /**
     * Commits an HITL pause to the group conversation — sets AWAITING_APPROVAL,
     * records pause metadata, persists, and fires the SSE event.
     */
    private void commitPause(GroupConversation gc, int phaseIdx,
                             AgentGroupConfiguration.DiscussionPhase phase,
                             String granularity, int currentTurnCount,
                             GroupDiscussionEventListener listener,
                             AgentGroupConfiguration config)
            throws IResourceStore.ResourceStoreException {
        gc.setState(GroupConversationState.AWAITING_APPROVAL);
        gc.setPausedAt(Instant.now());
        gc.setPausedAtPhaseIndex(phaseIdx);
        gc.setPausedPhaseName(phase.name());
        gc.setPausedTurnCount(currentTurnCount);
        gc.setHitlPauseType(GroupConversation.HitlPauseType.valueOf(granularity));
        gc.setHitlPauseReason("Requires human approval (" + granularity + ") — phase: " + phase.name());
        // Phase 6d: Copy timeout config into bookmark for REST visibility (from the
        // already-loaded config — no extra store read)
        if (config != null && config.getHitlConfig() != null) {
            var hitlConfig = config.getHitlConfig();
            gc.setHitlTimeoutPolicy(hitlConfig.getTimeoutPolicy() != null
                    ? hitlConfig.getTimeoutPolicy()
                    : HitlTimeoutPolicy.WAIT_INDEFINITELY);
            gc.setHitlApprovalTimeout(hitlConfig.getApprovalTimeout());
        } else {
            gc.setHitlTimeoutPolicy(HitlTimeoutPolicy.WAIT_INDEFINITELY);
        }
        conversationStore.update(gc);

        // MAJOR-2: Schedule group timeout if configured
        scheduleGroupHitlTimeout(gc);
        counterGroupHitlPause.increment();

        if (listener != null) {
            listener.onHitlPause(new GroupConversationEventSink.HitlPauseEvent(
                    phaseIdx, phase.name(), gc.getHitlPauseReason(), granularity));
        }
    }

    /**
     * Fingerprint of the task state at a TASK-granularity pause (#4). Captures the
     * phase index plus every non-terminal task's id and status — deliberately NOT
     * the turn count, so a resume that burns turns without advancing any task
     * produces the SAME fingerprint and is detected as no-progress. Two pauses with
     * equal fingerprints mean nothing changed between them.
     */
    private String taskPauseFingerprint(GroupConversation gc, int phaseIdx) {
        var sb = new StringBuilder("phase=").append(phaseIdx).append(';');
        if (gc.getTaskList() != null) {
            gc.getTaskList().all().stream()
                    .filter(t -> t.status() != SharedTaskList.TaskStatus.COMPLETED
                            && t.status() != SharedTaskList.TaskStatus.VERIFIED
                            && t.status() != SharedTaskList.TaskStatus.FAILED)
                    .map(t -> t.id() + ":" + t.status())
                    .sorted()
                    .forEach(s -> sb.append(s).append(','));
        }
        return sb.toString();
    }

    /**
     * Fails a TASK-granularity discussion that cannot make progress (#4): a resume
     * re-paused at the same phase with an identical task-state fingerprint. Records
     * an actionable transcript entry, fires a terminal SSE event, releases
     * paused-state resources, and persists FAILED. Guarantees the
     * pause→approve→pause loop terminates.
     */
    private void failDiscussionNoProgress(GroupConversation gc, int phaseIdx, DiscussionPhase phase,
                                          GroupDiscussionEventListener listener)
            throws IResourceStore.ResourceStoreException {
        String msg = "Discussion failed: EXECUTE phase '" + phase.name() + "' cannot make progress — "
                + "the same task(s) remained executable across an approval cycle without advancing "
                + "(exhausted turn budget or tasks assigned to an agent that can no longer be resolved). "
                + "Increase protocol.maxTurns, fix the task assignments, or cancel the discussion.";
        LOGGER.warnf("No-progress TASK pause detected for GC %s at phase %d — failing to guarantee termination",
                gc.getId(), phaseIdx);
        gc.getTranscript().add(new TranscriptEntry(
                "system", "System", null, phaseIdx, phase.name(),
                TranscriptEntryType.ERROR, Instant.now(), msg, null));
        gc.setState(GroupConversationState.FAILED);
        gc.setPausedAt(null);
        gc.setHitlLastPauseFingerprint(null);
        gc.setLastModified(Instant.now());
        conversationStore.update(gc);
        counterGroupFailure.increment();
        deleteGroupHitlTimeoutSchedule(gc.getId());
        cleanupAfterTerminalState(gc);
        if (listener != null) {
            listener.onGroupError(new GroupConversationEventSink.GroupErrorEvent(msg));
        }
    }

    /**
     * Converts a just-committed pause into a cancellation when a cancel signal
     * landed while the pause was being written. cancelDiscussion saw the live
     * token, signalled it, and reported success — but the running leg had already
     * passed its pre-gate cancel check, so without this the pause would survive a
     * "successful" cancel and the token signal would be dropped by the finally
     * block.
     */
    private void convertPauseToCancelIfSignalled(GroupConversation gc, GroupDiscussionEventListener listener) {
        convertPauseToCancelIfSignalled(gc, listener, activeTokens.get(gc.getId()));
    }

    /**
     * Removes the control token AND re-checks the removed instance for a cancel
     * signal. A cancel that landed AFTER an in-leg
     * {@code convertPauseToCancelIfSignalled} check but BEFORE this remove would
     * otherwise be dropped with the discarded token, leaving a "cancelled"
     * discussion stuck AWAITING_APPROVAL with an armed timer (cancelDiscussion
     * reported success on the token path and did not touch the DB/schedule).
     * Re-checking the removed instance closes that window; signals arriving after
     * the remove take cancelDiscussion's DB-CAS path instead.
     */
    private void removeTokenAndConvertIfSignalled(GroupConversation gc, GroupDiscussionEventListener listener) {
        var removed = activeTokens.remove(gc.getId());
        if (removed != null && removed.isCancelled() && gc.getState() == GroupConversationState.AWAITING_APPROVAL) {
            convertPauseToCancelIfSignalled(gc, listener, removed);
        }
    }

    private void convertPauseToCancelIfSignalled(GroupConversation gc, GroupDiscussionEventListener listener,
                                                 DiscussionControlToken token) {
        if (token == null || !token.isCancelled()) {
            return;
        }
        try {
            gc.setState(GroupConversationState.CANCELLED);
            gc.setPausedAt(null);
            gc.setLastModified(Instant.now());
            conversationStore.updateIfState(gc, GroupConversationState.AWAITING_APPROVAL);
            deleteGroupHitlTimeoutSchedule(gc.getId());
            auditHitlCancellation(gc, token.getSignal());
            LOGGER.infof("Cancel signal landed while pausing GC %s — converted pause to CANCELLED", gc.getId());
            notifyCancelled(gc, listener);
        } catch (IResourceStore.ResourceModifiedException e) {
            // Someone else moved the state concurrently (approve/timeout) — restore
            // the in-memory state so the executeDiscussion finally block does not
            // release paused-state resources for a conversation still paused in DB.
            gc.setState(GroupConversationState.AWAITING_APPROVAL);
            LOGGER.infof("Pause→cancel conversion for GC %s lost a state race — leaving persisted state", gc.getId());
        } catch (IGroupConversationStore.GroupConversationGoneException e) {
            // deleted concurrently — nothing left to cancel
            LOGGER.infof("Pause→cancel conversion for GC %s skipped — conversation was deleted", gc.getId());
        } catch (Exception e) {
            gc.setState(GroupConversationState.AWAITING_APPROVAL);
            LOGGER.warnf("Failed to convert just-committed pause of GC %s to CANCELLED: %s",
                    gc.getId(), e.getMessage());
        }
    }

    /**
     * Minimum delay before a past-due re-armed group timeout fires (mirrors crash
     * recovery).
     */
    private static final java.time.Duration GROUP_HITL_REARM_GRACE = java.time.Duration.ofMinutes(2);

    /**
     * Creates a one-shot schedule for group HITL timeout. Reads the pause bookmark
     * fields already set on the conversation (by commitPause/restoreGroupPause) —
     * NOT the group config, so the schedule always matches what approval-status
     * reports even if the config changed since the pause. No-ops if not configured
     * or WAIT_INDEFINITELY.
     * <p>
     * G7: the deadline is anchored to the ORIGINAL pause time ({@code pausedAt +
     * timeout}) so a restore-after-failed-resume re-arms at the same absolute due
     * time approval-status reports, not now + another full timeout. A past-due
     * deadline is clamped to {@code now + grace} (mirrors crash recovery). A fresh
     * pause has pausedAt ≈ now, so this reduces to now + timeout.
     */
    private void scheduleGroupHitlTimeout(GroupConversation gc) {
        try {
            String timeoutStr = gc.getHitlApprovalTimeout();
            HitlTimeoutPolicy policy = gc.getHitlTimeoutPolicy();
            if (timeoutStr == null || timeoutStr.isBlank()
                    || policy == null
                    || policy == HitlTimeoutPolicy.WAIT_INDEFINITELY) {
                return;
            }

            java.time.Duration timeout = java.time.Duration.parse(timeoutStr);
            Instant pausedAt = gc.getPausedAt();
            Instant now = Instant.now();
            Instant fireAt = pausedAt != null ? pausedAt.plus(timeout) : now.plus(timeout);
            // Clamp ONLY a past-due deadline (crash recovery / restore-after-failed-
            // resume re-arm) up to the grace window. A FRESH pause has pausedAt ≈ now,
            // so honor its configured timeout as-is — clamping it to the grace floor
            // would silently raise any sub-2min approvalTimeout to 2 minutes (parity
            // with the regular surface's scheduleHitlTimeout fix).
            if (fireAt.isBefore(now)) {
                fireAt = now.plus(GROUP_HITL_REARM_GRACE);
            }

            var schedule = new ScheduleConfiguration();
            schedule.setName(ai.labs.eddi.engine.hitl.HitlSchedules.groupTimeoutScheduleName(gc.getId()));
            schedule.setEnabled(true);
            schedule.setOneTimeAt(fireAt.toString());
            schedule.setNextFire(fireAt);
            schedule.setCreatedAt(Instant.now());
            schedule.setMetadata(java.util.Map.of(
                    ai.labs.eddi.engine.hitl.HitlSchedules.METADATA_TYPE_KEY,
                    ai.labs.eddi.engine.hitl.HitlSchedules.METADATA_TYPE_TIMEOUT,
                    ai.labs.eddi.engine.hitl.HitlSchedules.METADATA_POLICY_KEY, policy.name(),
                    ai.labs.eddi.engine.hitl.HitlSchedules.METADATA_SURFACE_KEY,
                    ai.labs.eddi.engine.hitl.HitlSchedules.SURFACE_GROUP,
                    ai.labs.eddi.engine.hitl.HitlSchedules.METADATA_CONVERSATION_ID_KEY, gc.getId()));
            scheduleStore.createSchedule(schedule);
            LOGGER.infof("Scheduled group HITL timeout for %s at %s (policy: %s)",
                    gc.getId(), fireAt, policy);
        } catch (Exception e) {
            LOGGER.warnf("Failed to schedule group HITL timeout for %s: %s",
                    gc.getId(), e.getMessage());
        }
    }

    @Override
    public GroupConversation readGroupConversation(String groupConversationId)
            throws IResourceStore.ResourceNotFoundException, IResourceStore.ResourceStoreException {
        return conversationStore.read(groupConversationId);
    }

    @Override
    public void deleteGroupConversation(String groupConversationId)
            throws GroupDiscussionException, IResourceStore.ResourceStoreException {
        // Serialize against an in-flight follow-up/continue/close on the same
        // conversation
        // (single-node) — delete is terminal (it ends member conversations and reclaims
        // ephemeral agents), so racing an active discussion could tear those down
        // mid-run
        // and let a later update() resurrect a stale "zombie" document.
        if (!operationsInProgress.add(groupConversationId)) {
            // A retryable conflict, not a store failure — surfaces as 409, not 500.
            throw new GroupDiscussionException(
                    "Cannot delete: another operation is already in progress for this group conversation");
        }
        try {
            GroupConversation gc = conversationStore.read(groupConversationId);
            // #12: deleting a paused discussion must run the same cleanup as
            // cancel-of-paused. executeDiscussion's finally deliberately skipped
            // cleanup while AWAITING_APPROVAL, so without this the armed timeout
            // schedule fires against a deleted conversation, ephemeral dynamic
            // agents stay deployed forever, and the signing guard's verification
            // cursor leaks.
            if (gc.getState() == GroupConversationState.AWAITING_APPROVAL) {
                deleteGroupHitlTimeoutSchedule(groupConversationId);
                cleanupAfterTerminalState(gc);
            }
            for (String privateConvId : gc.getMemberConversationIds().values()) {
                try {
                    conversationService.endConversation(privateConvId);
                } catch (Exception e) {
                    LOGGER.warnf("Failed to end private conversation %s: %s", privateConvId, e.getMessage());
                }
            }
            // Ephemeral agent cleanup — deferred from executeDiscussion() to terminal
            // operations. Delete is terminal, so reclaim any dynamically-created agents
            // here; otherwise deleting a COMPLETED conversation would orphan them.
            cleanupEphemeralAgentsForGroup(gc);
            conversationStore.delete(groupConversationId);
        } catch (IResourceStore.ResourceNotFoundException e) {
            LOGGER.warnf("Group conversation %s not found for deletion", LogSanitizer.sanitize(groupConversationId));
        } finally {
            operationsInProgress.remove(groupConversationId);
        }
    }

    @Override
    public List<GroupConversation> listGroupConversations(String groupId, int index, int limit) throws IResourceStore.ResourceStoreException {
        return conversationStore.listByGroupId(groupId, index, limit);
    }

    @Override
    public GroupConversation followUpWithMember(String groupConversationId, String targetAgentId,
                                                String question)
            throws GroupDiscussionException, IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {

        // Validate BEFORE taking the guard or transitioning state: a null targetAgentId
        // would otherwise NPE deep in the member-resolution scan (500 instead of 400),
        // and a null question would be appended to the transcript and sent to the
        // agent.
        if (targetAgentId == null || targetAgentId.isBlank()) {
            throw new IllegalArgumentException("targetAgentId must not be null or blank");
        }
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("question must not be null or blank");
        }

        if (!operationsInProgress.add(groupConversationId)) {
            throw new GroupDiscussionException(
                    "Another operation is already in progress for this group conversation");
        }
        try {
            GroupConversation gc = conversationStore.read(groupConversationId);

            // Atomic state transition: COMPLETED → IN_PROGRESS
            if (!conversationStore.compareAndSetState(groupConversationId, GroupConversationState.COMPLETED, GroupConversationState.IN_PROGRESS)) {
                throw new GroupDiscussionException(
                        "Cannot follow up: conversation is not in COMPLETED state (current: %s)".formatted(gc.getState()));
            }

            boolean success = false;
            try {
                // Re-read after CAS to get the freshest transcript
                gc = conversationStore.read(groupConversationId);

                // Resolve targetAgentId — accept either agent ID or display name
                String resolvedAgentId = targetAgentId;
                String privateConvId = gc.getMemberConversationIds().get(targetAgentId);
                if (privateConvId == null) {
                    // Try resolving as display name
                    for (var entry : gc.getMemberDisplayNames().entrySet()) {
                        if (targetAgentId.equalsIgnoreCase(entry.getValue())) {
                            resolvedAgentId = entry.getKey();
                            privateConvId = gc.getMemberConversationIds().get(resolvedAgentId);
                            break;
                        }
                    }
                }
                if (privateConvId == null) {
                    throw new GroupMemberNotFoundException(
                            // The caller-supplied targetAgentId is deliberately NOT echoed.
                            // REST maps this to 404 with a curated body; keeping the id out
                            // keeps the message safe wherever it DOES surface — e.g. the MCP
                            // tools, which return it as their error string.
                            "The requested agent is not a member of this group conversation. Available members: %s"
                                    .formatted(gc.getMemberDisplayNames()));
                }

                // Resolve display name
                String displayName = gc.getMemberDisplayNames().getOrDefault(resolvedAgentId, resolvedAgentId);

                // Record the user's follow-up question on the transcript
                gc.getTranscript().add(new TranscriptEntry(
                        "user", "User", question, -1, "Follow-up",
                        TranscriptEntryType.FOLLOW_UP, Instant.now(), null, resolvedAgentId));

                // Call the agent's private conversation
                InputData inputData = new InputData();
                inputData.setInput(question);
                Map<String, Context> context = new LinkedHashMap<>();
                // Snapshot, never the live list: the member conversation serialises this
                // context on its own thread while this one keeps appending to the
                // transcript (see the same hand-off in executeAgentTurn).
                List<TranscriptEntry> followUpTranscript;
                synchronized (gc.getTranscript()) {
                    followUpTranscript = List.copyOf(gc.getTranscript());
                }
                context.put("groupTranscript", new Context(Context.ContextType.object, followUpTranscript));
                context.put("groupId", new Context(Context.ContextType.string, gc.getGroupId()));
                context.put("groupConversationId", new Context(Context.ContextType.string, gc.getId()));
                inputData.setContext(context);

                CompletableFuture<String> responseFuture = new CompletableFuture<>();
                try {
                    conversationService.say(DEFAULT_ENV, resolvedAgentId, privateConvId, true, true, null, inputData, false, snapshot -> {
                        String response = extractResponse(snapshot);
                        if ((response == null || response.isEmpty()) && snapshot != null
                                && snapshot.getConversationState() == ConversationState.ERROR) {
                            response = "[Agent failed to produce output — conversation entered ERROR state]";
                        }
                        responseFuture.complete(response);
                    });
                } catch (Exception e) {
                    throw new GroupExecutionException("Failed to call agent '%s': %s".formatted(resolvedAgentId, e.getMessage()), e);
                }

                int timeoutSeconds = resolveAgentTimeoutSeconds(gc);
                String response;
                try {
                    response = responseFuture.get(timeoutSeconds, TimeUnit.SECONDS);
                } catch (TimeoutException e) {
                    throw new GroupTimeoutException("Follow-up timed out for agent '%s'".formatted(resolvedAgentId), e);
                } catch (ExecutionException | InterruptedException e) {
                    if (e instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    throw new GroupExecutionException("Follow-up failed for agent '%s': %s".formatted(resolvedAgentId, e.getMessage()), e);
                }

                // Record the agent's response on the transcript
                gc.getTranscript().add(new TranscriptEntry(
                        resolvedAgentId, displayName, response, -1, "Follow-up",
                        TranscriptEntryType.FOLLOW_UP, Instant.now(), null, null));

                // Transition back to COMPLETED — atomically (CAS on IN_PROGRESS) so a
                // follow-up that races a cancel cannot resurrect a CANCELLED terminal
                // state via an unconditional whole-document write. Mirrors the CAS the
                // error path below already uses.
                gc.setState(GroupConversationState.COMPLETED);
                gc.setLastModified(Instant.now());
                try {
                    conversationStore.updateIfState(gc, GroupConversationState.IN_PROGRESS);
                } catch (IResourceStore.ResourceModifiedException
                        | IGroupConversationStore.GroupConversationGoneException e) {
                    // A concurrent cancel/delete moved the conversation out of
                    // IN_PROGRESS while the follow-up ran — do not overwrite that
                    // terminal state; the follow-up exchange is not applied.
                    throw new GroupDiscussionException(
                            "Follow-up could not be applied: the conversation was concurrently "
                                    + "cancelled or deleted",
                            e);
                }

                success = true;
                counterGroupFollowUp.increment();
                return gc;

            } finally {
                if (!success) {
                    // Restore COMPLETED state so the conversation remains usable.
                    // Wrap in try-catch to avoid masking the original exception.
                    try {
                        conversationStore.compareAndSetState(groupConversationId,
                                GroupConversationState.IN_PROGRESS, GroupConversationState.COMPLETED);
                    } catch (Exception recoveryEx) {
                        LOGGER.warnf("Failed to restore COMPLETED state after follow-up error for %s: %s",
                                LogSanitizer.sanitize(groupConversationId), recoveryEx.getMessage());
                    }
                }
            }
        } finally {
            operationsInProgress.remove(groupConversationId);
        }
    }

    @Override
    public GroupConversation continueDiscussion(String groupConversationId, String question,
                                                GroupDiscussionEventListener listener)
            throws GroupDiscussionException, IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {

        // Validate before taking the guard / transitioning state — a blank question
        // would
        // otherwise be appended to the transcript and drive every phase's agent input.
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("question must not be null or blank");
        }

        if (!operationsInProgress.add(groupConversationId)) {
            throw new GroupDiscussionException(
                    "Another operation is already in progress for this group conversation");
        }
        try {
            GroupConversation gc = conversationStore.read(groupConversationId);

            // Atomic state transition: COMPLETED → IN_PROGRESS
            if (!conversationStore.compareAndSetState(groupConversationId, GroupConversationState.COMPLETED, GroupConversationState.IN_PROGRESS)) {
                throw new GroupDiscussionException(
                        "Cannot continue: conversation is not in COMPLETED state (current: %s)".formatted(gc.getState()));
            }

            // Re-read after CAS
            gc = conversationStore.read(groupConversationId);

            // Increment round and append the new question. Persist the follow-up as the
            // run's resumeQuestion so that if a continuation round pauses at an HITL gate,
            // resumeDiscussion re-runs the remaining phases with THIS question rather than
            // the stale round-1 one. Uses a dedicated field (not originalQuestion, which
            // the UI shows as the conversation title) so continuations don't rewrite it.
            gc.setRound(gc.getRound() + 1);
            gc.setResumeQuestion(question);
            gc.getTranscript().add(new TranscriptEntry(
                    "user", "User", question, 0, "Question",
                    TranscriptEntryType.QUESTION, Instant.now(), null, null));
            gc.setLastModified(Instant.now());
            // Conditional write (CAS on IN_PROGRESS): an unconditional whole-document
            // update here would resurrect a conversation that a concurrent
            // cancel/close/delete moved to a terminal state in the window after our CAS
            // above. Terminal states must stay irreversible (mirrors followUpWithMember).
            try {
                conversationStore.updateIfState(gc, GroupConversationState.IN_PROGRESS);
            } catch (IResourceStore.ResourceModifiedException
                    | IGroupConversationStore.GroupConversationGoneException e) {
                throw new GroupDiscussionException(
                        "Cannot continue: the conversation was concurrently cancelled, closed or deleted", e);
            }
            counterGroupContinue.increment();

            // Pre-register the control token BEFORE the config-load window so a cancel
            // racing the gap between the CAS above and executeDiscussion's own token
            // registration takes the signal path (stops at the top-of-phase check)
            // rather than the DB branch, which would CAS to CANCELLED and then be
            // overwritten by this leg (mirrors startAndDiscussAsync / resumeDiscussion).
            activeTokens.put(groupConversationId, new DiscussionControlToken());

            // Load the group config and re-execute — wrapped in try-catch so that
            // failures before executeDiscussion() (which has its own failConversation
            // logic) still set the GC to FAILED rather than leaving it IN_PROGRESS.
            try {
                IResourceStore.IResourceId currentGroupId = groupStore.getCurrentResourceId(gc.getGroupId());
                if (currentGroupId == null) {
                    throw new IResourceStore.ResourceNotFoundException("Group not found.");
                }
                AgentGroupConfiguration config = groupStore.read(gc.getGroupId(), currentGroupId.getVersion());

                // Resolve phases and re-execute
                List<DiscussionPhase> phases = resolvePhases(config);
                if (phases.isEmpty()) {
                    // A group config with no phases is a server-side misconfiguration the
                    // caller cannot fix by retrying, not a conversation-state conflict.
                    throw new GroupExecutionException("No discussion phases are defined for this group.");
                }

                // Continuation re-runs the full protocol from the first phase.
                return executeDiscussion(gc, config, phases, question, listener, 0);
            } catch (Exception e) {
                // executeDiscussion handles its own failures, so this only catches
                // errors from config loading / phase resolution above. If it was never
                // reached, its finally never removed the pre-registered token — drop it
                // here (idempotent: a no-op if executeDiscussion already removed it).
                activeTokens.remove(groupConversationId);
                if (gc.getState() == GroupConversationState.IN_PROGRESS) {
                    failConversation(gc);
                }
                if (e instanceof GroupDiscussionException gde) {
                    throw gde;
                }
                if (e instanceof IResourceStore.ResourceNotFoundException rnfe) {
                    throw rnfe;
                }
                if (e instanceof IResourceStore.ResourceStoreException rse) {
                    throw rse;
                }
                throw new GroupExecutionException("Continue discussion failed: " + e.getMessage(), e);
            }
        } finally {
            operationsInProgress.remove(groupConversationId);
        }
    }

    @Override
    public GroupConversation closeGroupConversation(String groupConversationId)
            throws GroupDiscussionException, IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {

        if (!operationsInProgress.add(groupConversationId)) {
            throw new GroupDiscussionException(
                    "Another operation is already in progress for this group conversation");
        }
        try {
            GroupConversation gc = conversationStore.read(groupConversationId);

            // Atomic state transition: try COMPLETED → CLOSED, then FAILED → CLOSED,
            // then CANCELLED → CLOSED. CANCELLED is closeable so an operator can reclaim
            // the ephemeral agents of a discussion cancelled in a window where no running
            // leg cleaned them up (CANCELLED has no follow-up/continue path otherwise).
            boolean transitioned = conversationStore.compareAndSetState(
                    groupConversationId, GroupConversationState.COMPLETED, GroupConversationState.CLOSED);
            if (!transitioned) {
                transitioned = conversationStore.compareAndSetState(
                        groupConversationId, GroupConversationState.FAILED, GroupConversationState.CLOSED);
            }
            if (!transitioned) {
                transitioned = conversationStore.compareAndSetState(
                        groupConversationId, GroupConversationState.CANCELLED, GroupConversationState.CLOSED);
            }
            if (!transitioned) {
                throw new GroupDiscussionException(
                        "Cannot close: conversation is in %s state (expected COMPLETED, FAILED, or CANCELLED)".formatted(gc.getState()));
            }
            counterGroupClose.increment();

            // End all member conversations
            for (String privateConvId : gc.getMemberConversationIds().values()) {
                try {
                    conversationService.endConversation(privateConvId);
                } catch (Exception e) {
                    LOGGER.warnf("Failed to end private conversation %s during close: %s", privateConvId, e.getMessage());
                }
            }

            // Ephemeral agent cleanup (deferred from executeDiscussion)
            cleanupEphemeralAgentsForGroup(gc);

            LOGGER.infof("Group conversation %s closed — member conversations ended, ephemeral agents cleaned up",
                    LogSanitizer.sanitize(groupConversationId));

            // Re-read to return the final CLOSED state
            return conversationStore.read(groupConversationId);
        } finally {
            operationsInProgress.remove(groupConversationId);
        }
    }

    @Override
    public List<ai.labs.eddi.engine.model.PendingApprovalSummary> listGroupPendingApprovals(String groupId, int limit)
            throws IResourceStore.ResourceStoreException {
        // Bounded summaries — never hand full transcripts to a listing endpoint.
        // The groupId filter is applied in the QUERY (not post-limit), so a busy
        // deployment cannot push this group's items past the limit window.
        int clamped = Math.max(1, Math.min(limit, 1000));
        return conversationStore.findByState(GroupConversationState.AWAITING_APPROVAL, groupId, clamped).stream()
                .map(gc -> {
                    var summary = new ai.labs.eddi.engine.model.PendingApprovalSummary(
                            gc.getId(), null, gc.getUserId(), gc.getPausedAt(),
                            gc.getHitlPauseReason(),
                            gc.getHitlTimeoutPolicy() != null ? gc.getHitlTimeoutPolicy().name() : null);
                    summary.setGroupId(gc.getGroupId());
                    summary.setApprovalTimeout(gc.getHitlApprovalTimeout());
                    return summary;
                })
                .toList();
    }

    // =================================================================
    // Ephemeral agent cleanup
    // =================================================================

    /**
     * Clean up agents created during a discussion based on the lifecycle policy.
     * Called in the {@code executeDiscussion} finally block.
     */
    private void cleanupEphemeralAgents(GroupConversation gc, AgentGroupConfiguration config) {
        List<String> createdIds = gc.getCreatedAgentIds();
        if (createdIds == null || createdIds.isEmpty()) {
            return;
        }

        var dynamicConfig = config.getDynamicAgents();
        LifecyclePolicy policy = dynamicConfig != null ? dynamicConfig.getLifecyclePolicy() : LifecyclePolicy.EPHEMERAL;

        for (String agentId : createdIds) {
            // 'agent-decides': skip retained agents
            if (policy == LifecyclePolicy.AGENT_DECIDES && gc.getRetainedAgentIds().contains(agentId)) {
                LOGGER.infof("Ephemeral cleanup: agent '%s' retained by creator — skipping", agentId);
                continue;
            }

            // 'keep-deployed': no cleanup
            if (policy == LifecyclePolicy.KEEP_DEPLOYED) {
                continue;
            }

            try {
                boolean shouldDelete = policy == LifecyclePolicy.EPHEMERAL || policy == LifecyclePolicy.AGENT_DECIDES;
                agentFactory.undeployAgent(DEFAULT_ENV, agentId, null);
                LOGGER.infof("Ephemeral cleanup: undeployed agent '%s'", agentId);

                if (shouldDelete) {
                    agentStore.deleteAllPermanently(agentId);
                    retireDeploymentRecords(agentId);
                    LOGGER.infof("Ephemeral cleanup: deleted agent '%s'", agentId);
                }
            } catch (Exception e) {
                LOGGER.warnf("Ephemeral cleanup failed for agent '%s': %s", agentId, e.getMessage());
            }
        }
    }

    /**
     * A deployment record left behind by a deleted ephemeral agent makes the
     * runtime retry a doomed redeploy. Never fatal — the agent is already gone
     * either way, and the startup sweep in AgentDeploymentManagement retires
     * anything missed here.
     */
    private void retireDeploymentRecords(String agentId) {
        if (deploymentStore == null) {
            return;
        }
        try {
            deploymentStore.deleteDeploymentInfos(agentId);
        } catch (Exception e) {
            LOGGER.warnf("Ephemeral cleanup: could not clear deployment record(s) for agent '%s': %s", agentId, e.getMessage());
        }
    }

    // =================================================================
    // Phase resolution
    // =================================================================

    private List<DiscussionPhase> resolvePhases(AgentGroupConfiguration config) {
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
     */
    private int resolveAgentTimeoutSeconds(GroupConversation gc) {
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
     * Load the group config and run ephemeral-agent cleanup for a terminal
     * operation (close / delete). Tolerant of config-load failures.
     */
    private void cleanupEphemeralAgentsForGroup(GroupConversation gc) {
        try {
            IResourceStore.IResourceId currentGroupId = groupStore.getCurrentResourceId(gc.getGroupId());
            if (currentGroupId != null) {
                AgentGroupConfiguration config = groupStore.read(gc.getGroupId(), currentGroupId.getVersion());
                cleanupEphemeralAgents(gc, config);
            }
        } catch (Exception e) {
            LOGGER.warnf("Ephemeral agent cleanup failed for group conversation %s: %s",
                    LogSanitizer.sanitize(gc.getId()), e.getMessage());
        }
    }

    /**
     * Determines which members participate in a phase based on the
     * {@code participants} field: "ALL", "MODERATOR", or "ROLE:&lt;name&gt;".
     */
    private List<GroupMember> resolveParticipants(DiscussionPhase phase, List<GroupMember> allMembers, String moderatorAgentId) {
        String participants = phase.participants() != null ? phase.participants() : "ALL";

        if ("MODERATOR".equalsIgnoreCase(participants)) {
            if (moderatorAgentId == null || moderatorAgentId.isBlank()) {
                LOGGER.warnf("Phase '%s' requires MODERATOR but none configured, " + "falling back to ALL", phase.name());
                return allMembers;
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
        return allMembers.stream().sorted(Comparator.comparing(m -> m.speakingOrder() != null ? m.speakingOrder() : Integer.MAX_VALUE)).toList();
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

    /** Terminal states: no further transition may overwrite them. */
    private static boolean isTerminalState(GroupConversationState state) {
        return state == GroupConversationState.COMPLETED
                || state == GroupConversationState.FAILED
                || state == GroupConversationState.CANCELLED
                || state == GroupConversationState.CLOSED;
    }

    private void failConversation(GroupConversation gc) {
        // Never write unconditionally: conversationStore.update() is a whole-document
        // UPSERT, so it would RE-CREATE a conversation another pod deleted and would
        // clobber a terminal state (e.g. a cross-pod CANCELLED) with FAILED.
        //
        // The CAS expectation must come from the PERSISTED state, not the in-memory
        // one:
        // executeDiscussion flips gc to SYNTHESIZING in memory BEFORE the synthesis
        // phase
        // runs and only persists it afterwards, so a CAS on the in-memory value would
        // lose
        // the race and silently strand the conversation IN_PROGRESS forever.
        var inMemoryState = gc.getState();
        GroupConversationState expected;
        try {
            expected = conversationStore.read(gc.getId()).getState();
        } catch (Exception e) {
            expected = inMemoryState; // best effort — the re-read failed
        }
        if (expected == null) {
            expected = inMemoryState;
        }

        gc.setState(GroupConversationState.FAILED);
        gc.setLastModified(Instant.now());
        // Count the failure itself, never the outcome of the race — otherwise a lost
        // CAS
        // would hide the failure from operators entirely.
        counterGroupFailure.increment();

        if (expected == null || isTerminalState(expected)) {
            // Another writer already made it terminal — honor that. Align the in-memory
            // state with it (mirrors persistedTerminalOverride) so executeDiscussion's
            // finally makes the RIGHT ephemeral-agent decision: leaving a stale FAILED
            // here would undeploy the agents of a conversation that is actually COMPLETED
            // (whose agents a follow-up/continue must reuse) or already CLOSED.
            if (expected != null) {
                gc.setState(expected);
            }
            LOGGER.infof("Not failing group conversation %s — it is already terminal (%s)",
                    LogSanitizer.sanitize(gc.getId()), expected);
            return;
        }
        try {
            conversationStore.updateIfState(gc, expected);
        } catch (IResourceStore.ResourceModifiedException | IGroupConversationStore.GroupConversationGoneException e) {
            LOGGER.infof("Not failing group conversation %s — another writer made it terminal or deleted it",
                    LogSanitizer.sanitize(gc.getId()));
        } catch (Exception e) {
            LOGGER.warnf("Failed to update group conversation state to FAILED: %s", e.getMessage());
        }
    }

    /**
     * Reads dynamic agent tracking data from the member's conversation snapshot and
     * propagates it to the group conversation's tracking lists. This bridges the
     * gap between per-turn tool-local tracking lists and the group-level lifecycle
     * tracking in {@link GroupConversation}.
     * <p>
     * Public: called from
     * {@link ai.labs.eddi.engine.internal.groups.MemberTurnExecutor} (Wave R, R1
     * step 4) as well as from this class. Stays declared here rather than moving
     * into that class because {@code DynamicAgentTrackingPropagationTest} calls it
     * directly by class name — not via reflection — and it is slated to relocate to
     * {@code GroupLifecycleOps} in a later R1 step regardless.
     */
    public static void propagateDynamicAgentTracking(
                                                     ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot snapshot,
                                                     GroupConversation gc) {
        if (snapshot == null || snapshot.getConversationSteps() == null) {
            return;
        }
        var steps = snapshot.getConversationSteps();
        if (steps.isEmpty()) {
            return;
        }
        // Check the last step for tracking data
        var lastStep = steps.get(steps.size() - 1);
        if (lastStep == null || lastStep.getConversationStep() == null) {
            return;
        }
        for (var stepData : lastStep.getConversationStep()) {
            if (stepData == null || stepData.getKey() == null) {
                continue;
            }
            if ("dynamic:created_agent_ids"
                    .equals(stepData.getKey()) && stepData.getValue() instanceof java.util.Collection<?> ids) {
                for (Object id : ids) {
                    if (id instanceof String agentId && !gc.getCreatedAgentIds().contains(agentId)) {
                        gc.getCreatedAgentIds().add(agentId);
                        LOGGER.debugf("[DYNAMIC] Propagated created agent '%s' to group conversation", agentId);
                    }
                }
            } else if ("dynamic:retained_agent_ids"
                    .equals(stepData.getKey()) && stepData.getValue() instanceof java.util.Collection<?> ids) {
                for (Object id : ids) {
                    if (id instanceof String agentId) {
                        gc.getRetainedAgentIds().add(agentId);
                    }
                }
            }
        }
    }

    /**
     * Extracts the human-readable text from a conversation memory snapshot.
     * Delegates to {@link GroupContextBuilder}, kept as a declared method here (not
     * inlined at call sites) since a characterization test reaches it via
     * reflection.
     */
    private String extractResponse(ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot snapshot) {
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
    // HITL lifecycle — cancel & resume
    // =================================================================

    private final ConcurrentHashMap<String, DiscussionControlToken> activeTokens = new ConcurrentHashMap<>();

    @Override
    public boolean cancelDiscussion(String conversationId, ControlSignal mode)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {
        // #13: decide the cancel path BEFORE deleting the timeout schedule. The old
        // order deleted the schedule unconditionally up front, so a cancel racing a
        // fresh pause (token present, pause just committed) reported success yet
        // left the pause intact — stripped of its finite timeout, silently
        // degrading a bounded policy to WAIT_INDEFINITELY.
        var token = activeTokens.get(conversationId);
        if (token != null) {
            if (mode == ControlSignal.CANCEL_IMMEDIATE) {
                token.setSignal(ControlSignal.CANCEL_IMMEDIATE);
                token.cancelActiveFuture();
            } else {
                token.setSignal(ControlSignal.CANCEL_GRACEFUL);
            }
            // Do NOT delete the schedule here: a running leg has no armed schedule,
            // and if the leg is mid-pause-commit it converts the pause to CANCELLED
            // itself (convertPauseToCancelIfSignalled), deleting the schedule only
            // once the cancel actually wins.
            return true; // in-flight leg signalled — it will persist CANCELLED
        }
        // Not actively running — update DB with a state-CAS (#9): a plain
        // read-modify-write would race a concurrent approve/resume and could
        // resurrect a terminal state.
        var gc = conversationStore.read(conversationId);
        var state = gc.getState();
        // Only cancel from non-terminal states — guard against overwriting COMPLETED,
        // FAILED or CLOSED after a race. CLOSED is irreversible: without it here a
        // cancel
        // would CAS CLOSED → CANCELLED and un-terminalize an already-reclaimed
        // conversation.
        if (state == GroupConversationState.COMPLETED
                || state == GroupConversationState.CANCELLED
                || state == GroupConversationState.FAILED
                || state == GroupConversationState.CLOSED) {
            LOGGER.infof("Cancel skipped: GC %s already in terminal state %s", conversationId, state);
            return false;
        }
        boolean wasPaused = state == GroupConversationState.AWAITING_APPROVAL;
        gc.setState(GroupConversationState.CANCELLED);
        gc.setPausedAt(null); // keep isPaused() consistent with the terminal state
        gc.setLastModified(Instant.now());
        try {
            conversationStore.updateIfState(gc, state);
        } catch (IResourceStore.ResourceModifiedException e) {
            // CAS lost — leave the schedule alone: whoever won the race (a fresh
            // pause / approve / timeout) owns the schedule now. Report 409.
            LOGGER.infof("Cancel of group conversation %s lost a concurrent state race — not overwriting", conversationId);
            return false;
        }
        // Cancel won: delete the timeout schedule only now (MAJOR-3).
        deleteGroupHitlTimeoutSchedule(conversationId);
        if (wasPaused) {
            // Cancelling a pending approval is an HITL decision — audit it, and
            // release resources that were kept alive across the pause.
            auditHitlCancellation(gc, mode);
            cleanupAfterTerminalState(gc);
        }
        return true;
    }

    @Override
    public GroupConversation resumeDiscussion(String groupConversationId, GroupApprovalRequest request,
                                              GroupDiscussionEventListener listener)
            throws GroupDiscussionException, IResourceStore.ResourceStoreException,
            IResourceStore.ResourceNotFoundException, IResourceStore.ResourceModifiedException {

        var gc = conversationStore.read(groupConversationId);
        if (gc.getState() != GroupConversationState.AWAITING_APPROVAL) {
            throw new GroupDiscussionException("Group conversation is not awaiting approval");
        }

        // Apply task-level approvals if present
        // Phase 5a: Load rejection policy from config
        boolean retryOnReject = false;
        try {
            var resId = groupStore.getCurrentResourceId(gc.getGroupId());
            if (resId != null) {
                var config = groupStore.read(gc.getGroupId(), resId.getVersion());
                if (config.getHitlConfig() != null) {
                    retryOnReject = config.getHitlConfig().getOnTaskRejection() == HitlRejectionPolicy.RETRY;
                }
            }
        } catch (Exception e) {
            LOGGER.warnf("Could not load rejection policy for %s, defaulting to FAIL: %s",
                    groupConversationId, e.getMessage());
        }

        // An explicit empty map is treated exactly like an absent map (the
        // approve-all shortcut) — otherwise {} approves nothing and the resumed
        // phase instantly re-pauses.
        boolean hasTaskApprovals = request.getTaskApprovals() != null && !request.getTaskApprovals().isEmpty();
        // #30: a task-level body against a PHASE-paused (or task-list-less)
        // conversation must fail 400 — not be silently ignored and treated as a
        // plain phase approve. Reject before the CAS so the operator sees the error
        // instead of an unexpected full resume.
        if (hasTaskApprovals
                && (gc.getTaskList() == null || gc.getHitlPauseType() == GroupConversation.HitlPauseType.PHASE)) {
            throw new IllegalArgumentException(
                    "taskApprovals were provided but this conversation has no task-level approval to apply "
                            + "(it is " + (gc.getHitlPauseType() == GroupConversation.HitlPauseType.PHASE
                                    ? "paused at PHASE granularity"
                                    : "paused without a task list")
                            + "); omit taskApprovals to approve the phase");
        }
        if (hasTaskApprovals && gc.getTaskList() != null) {
            // #13: validate the WHOLE map up front — unknown taskIds, tasks not
            // awaiting approval, and unknown decision VALUES must fail as a
            // 400-class error BEFORE any mutation (partial application) and
            // BEFORE the CAS/schedule deletion.
            for (var entry : request.getTaskApprovals().entrySet()) {
                var task = gc.getTaskList().findById(entry.getKey());
                if (task == null) {
                    throw new IllegalArgumentException(
                            "Unknown taskId in taskApprovals: '" + entry.getKey() + "'");
                }
                if (task.status() != SharedTaskList.TaskStatus.AWAITING_APPROVAL) {
                    throw new IllegalArgumentException(
                            "Task '" + entry.getKey() + "' is not awaiting approval (status: " + task.status() + ")");
                }
                String value = entry.getValue();
                if (value == null
                        || (!"APPROVED".equalsIgnoreCase(value) && !"REJECTED".equalsIgnoreCase(value))) {
                    throw new IllegalArgumentException(
                            "Invalid taskApprovals value for '" + entry.getKey()
                                    + "': expected APPROVED or REJECTED (case-insensitive), got '" + value + "'");
                }
            }

            String reviewerNote = request.getDecision() != null && request.getDecision().getNote() != null
                    ? request.getDecision().getNote()
                    : "Rejected by human reviewer";
            for (var entry : request.getTaskApprovals().entrySet()) {
                if ("APPROVED".equalsIgnoreCase(entry.getValue())) {
                    gc.getTaskList().approveTask(entry.getKey());
                } else if (retryOnReject) {
                    // RETRY policy: reset to ASSIGNED with the reviewer's feedback so
                    // the re-executing agent knows what to fix (C-D)
                    gc.getTaskList().resetFromAnyToAssigned(entry.getKey(), reviewerNote);
                    LOGGER.infof("Task '%s' rejected with RETRY policy — reset to ASSIGNED", entry.getKey());
                } else {
                    // FAIL policy (default): permanently reject the task
                    gc.getTaskList().rejectTask(entry.getKey(), reviewerNote);
                }
            }
        }

        // AUTO_APPROVE fix: When TASK granularity + APPROVED verdict + no explicit
        // taskApprovals (e.g., from timeout handler), auto-approve all
        // AWAITING_APPROVAL
        // tasks. Without this, resume re-enters the same TASK phase, tasks are still
        // excluded by findExecutableTasks, and it re-pauses → infinite reschedule loop.
        var decision = request.getDecision();
        if (decision != null
                && decision.getVerdict() == ai.labs.eddi.engine.lifecycle.model.HitlDecision.HitlVerdict.APPROVED
                && !hasTaskApprovals
                && gc.getTaskList() != null
                && gc.getHitlPauseType() == GroupConversation.HitlPauseType.TASK) {
            gc.getTaskList().all().stream()
                    .filter(t -> t.status() == SharedTaskList.TaskStatus.AWAITING_APPROVAL)
                    .forEach(t -> gc.getTaskList().approveTask(t.id()));
        }

        // #4: On an EXPLICIT HUMAN approval of a TASK pause, grant a fresh turn
        // budget so the resume can actually drive the remaining executable tasks —
        // the preserved budget (seeded from pausedTurnCount) may already be
        // exhausted, which would otherwise re-pause immediately. AUTO_APPROVE
        // (decidedBy "system:...") deliberately does NOT get a fresh budget: it
        // must terminate via the no-progress fingerprint guard, never run
        // unattended forever. If the fresh budget still yields no task progress,
        // the fingerprint guard fails the discussion on the next pause.
        boolean humanDecision = decision != null
                && (decision.getDecidedBy() == null || !decision.getDecidedBy().startsWith("system:"));
        if (humanDecision
                && decision.getVerdict() == ai.labs.eddi.engine.lifecycle.model.HitlDecision.HitlVerdict.APPROVED
                && gc.getHitlPauseType() == GroupConversation.HitlPauseType.TASK
                && gc.getPausedTurnCount() > 0) {
            LOGGER.infof("Human approval of TASK pause for GC %s — granting a fresh turn budget", groupConversationId);
            gc.setPausedTurnCount(0);
        }

        // Apply phase-level decision
        if (decision != null && decision.getVerdict() == ai.labs.eddi.engine.lifecycle.model.HitlDecision.HitlVerdict.REJECTED) {
            gc.setState(GroupConversationState.FAILED);
            gc.setPausedAt(null);
            // MAJOR-4: Use CAS to prevent concurrent approve clobbering reject
            conversationStore.updateIfState(gc, GroupConversationState.AWAITING_APPROVAL);
            // Delete timeout schedule only after CAS succeeds (Phase 5e)
            deleteGroupHitlTimeoutSchedule(groupConversationId);
            auditHitlDecision(gc, decision);
            // A rejection is terminal: notify the (SSE) listener so streams close
            // instead of hanging, and release paused-state resources.
            if (listener != null) {
                listener.onGroupComplete(new GroupConversationEventSink.GroupCompleteEvent(
                        gc.getState(), gc.getSynthesizedAnswer()));
            }
            cleanupAfterTerminalState(gc);
            return gc;
        }

        // Save resume point and pause type before clearing
        int resumePhaseIndex = gc.getPausedAtPhaseIndex();
        String pausedPhaseName = gc.getPausedPhaseName(); // saved for config drift guard
        // BLOCKER: read hitlPauseType — TASK pauses mid-phase, so we must re-enter
        // at the SAME phase (findExecutableTasks is idempotent for approved tasks).
        // PHASE pauses after the phase completes, so resume at +1.
        var pauseType = gc.getHitlPauseType();
        // #29: capture the timeout-policy bookmark BEFORE it is nulled below, so a
        // failed resume whose config re-read also fails can restore the ORIGINAL
        // finite policy instead of silently disarming it (persisting null →
        // WAIT_INDEFINITELY). #35: capture the original pausedAt for the same reason
        // — a restore must not shift a re-armed timeout's due time forward.
        final HitlTimeoutPolicy savedTimeoutPolicy = gc.getHitlTimeoutPolicy();
        final String savedApprovalTimeout = gc.getHitlApprovalTimeout();
        final Instant originalPausedAt = gc.getPausedAt();

        // Clear pause state but preserve pausedTurnCount — it seeds turnCounter on
        // resume (M3)
        gc.setPausedAt(null);
        gc.setPausedAtPhaseIndex(-1);
        gc.setPausedPhaseName(null);
        gc.setHitlPauseType(null);
        gc.setHitlPauseReason(null);
        gc.setHitlTimeoutPolicy(null);
        gc.setHitlApprovalTimeout(null);
        gc.setState(GroupConversationState.IN_PROGRESS);

        // Zero-match outcomes are distinguished by the store: a concurrent DELETE
        // surfaces as (unchecked) GroupConversationGoneException → REST 404, a
        // genuine state race as ResourceModifiedException → REST 409.
        conversationStore.updateIfState(gc, GroupConversationState.AWAITING_APPROVAL);
        // O2: register the control token IMMEDIATELY after the resume CAS — before
        // deleting the schedule and notifying listeners. Otherwise a concurrent
        // cancelDiscussion landing between the CAS and a later put finds no token,
        // takes the DB branch, sees IN_PROGRESS (non-terminal), CAS's to CANCELLED,
        // and returns success — yet the resume then registers a FRESH non-cancelled
        // token and runs a full phase on a discussion the operator was told was
        // cancelled. With the token present here, that cancel takes the SIGNAL path
        // (setSignal) and executeDiscussion's top-of-phase isCancelled() check stops
        // before any member-agent work runs.
        activeTokens.put(gc.getId(), new DiscussionControlToken());
        // Delete timeout schedule only after CAS succeeds (Phase 5e) — if CAS
        // fails, the schedule is preserved so the timeout can still fire.
        deleteGroupHitlTimeoutSchedule(groupConversationId);
        // #35: the metric + audit entry are deliberately NOT written here — they are
        // deferred until the resume is actually enqueued (below), mirroring the
        // regular surface. A submit failure rolls the pause back, so a rolled-back
        // attempt must not pollute the resume metric or the EU-AI-Act audit trail.

        // The resume is committed — tell SSE subscribers the discussion is live
        // again (the stream stays open for the resumed discussion's events).
        if (listener != null) {
            listener.onHitlResume(new GroupConversationEventSink.HitlResumeEvent(
                    decision != null && decision.getVerdict() != null ? decision.getVerdict().name() : "APPROVED",
                    decision != null ? decision.getNote() : null,
                    decision != null ? decision.getDecidedBy() : null));
        }

        // Resume execution in background thread. Use the current run's resumeQuestion
        // (set by continueDiscussion for a continuation round) so the remaining phases
        // re-run with the follow-up question; fall back to originalQuestion for the
        // initial round and legacy documents that predate the field.
        var groupId = gc.getGroupId();
        var question = gc.getResumeQuestion() != null ? gc.getResumeQuestion() : gc.getOriginalQuestion();
        // BLOCKER fix: TASK pauses mid-phase → re-enter at same phase (idempotent).
        // PHASE pauses after phase completes → resume at +1.
        int startFromPhase = (pauseType == GroupConversation.HitlPauseType.TASK)
                ? resumePhaseIndex
                : resumePhaseIndex + 1;

        // Saved bookmark fields for pause restoration on transient failures.
        // #35: restore with the ORIGINAL pausedAt so a re-armed timeout keeps its
        // due time (pausedAt + approvalTimeout) instead of shifting forward to the
        // resume-attempt instant.
        final Instant savedPausedAt = originalPausedAt != null ? originalPausedAt : Instant.now();
        final int savedPhaseIndex = resumePhaseIndex;
        final String savedPhaseName = pausedPhaseName;
        final var savedPauseType = pauseType;

        // O2: the control token is registered right after the resume CAS above (not
        // here) so a cancel racing the window between the CAS and the executor thread
        // reaching executeDiscussion finds a signalable token and takes the SIGNAL
        // path, rather than falling through to the DB branch and being overwritten by
        // the resumed leg's unconditional updates.

        Runnable resumeWork = () -> {
            AgentGroupConfiguration groupConfig;
            List<DiscussionPhase> phases;
            try {
                IResourceStore.IResourceId currentGroupId = groupStore.getCurrentResourceId(groupId);
                if (currentGroupId == null) {
                    throw new IResourceStore.ResourceNotFoundException("Group not found.");
                }
                groupConfig = groupStore.read(groupId, currentGroupId.getVersion());
                phases = resolvePhases(groupConfig);

                // Phase 5f: Config drift guard — verify the phase at the bookmark
                // still matches what was paused. If the config was edited while the
                // discussion was awaiting approval, the phase list may have shifted.
                // Compare against resumePhaseIndex (the bookmarked phase), not
                // startFromPhase (which is +1 for PHASE pauses). The bookmarked
                // phase MUST exist — if the list shrank below it, that is drift too
                // (a silently-skipped guard would complete a discussion whose gated
                // phases never ran).
                if (savedPhaseName != null) {
                    String actualPhase = savedPhaseIndex < phases.size()
                            ? phases.get(savedPhaseIndex).name()
                            : null;
                    if (!savedPhaseName.equals(actualPhase)) {
                        LOGGER.warnf("Config drift detected for GC %s: expected phase '%s' at index %d but found '%s'",
                                groupConversationId, savedPhaseName, savedPhaseIndex, actualPhase);
                        String driftMessage = "Resume aborted: group config changed while paused (expected phase '"
                                + savedPhaseName + "' at index " + savedPhaseIndex
                                + " but found " + (actualPhase != null ? "'" + actualPhase + "'" : "no phase at that index")
                                + ") — the discussion remains awaiting approval; fix the config and retry, or cancel";
                        gc.getTranscript().add(new TranscriptEntry(
                                "system", "System",
                                driftMessage,
                                savedPhaseIndex, actualPhase != null ? actualPhase : "n/a",
                                TranscriptEntryType.ERROR, Instant.now(), driftMessage, null));
                        // Restore the pause instead of destroying the approval: the
                        // operator can fix the config and approve again, or cancel.
                        restoreGroupPause(gc, savedPhaseIndex, savedPhaseName, savedPauseType, savedPausedAt, groupConfig,
                                savedTimeoutPolicy, savedApprovalTimeout);
                        // A cancel signalled in this window must win over the restore —
                        // remove-and-recheck so a signal racing the remove is not dropped.
                        removeTokenAndConvertIfSignalled(gc, listener);
                        if (listener != null) {
                            listener.onGroupError(new GroupConversationEventSink.GroupErrorEvent(driftMessage));
                        }
                        return;
                    }
                }
            } catch (Exception e) {
                LOGGER.error("Failed to resume group discussion: " + groupConversationId, e);
                // Transient failure BEFORE executeDiscussion (store hiccup, config
                // unreadable): restore the pause instead of failing the discussion
                // terminally — symmetric with the regular surface.
                restoreGroupPause(gc, savedPhaseIndex, savedPhaseName, savedPauseType, savedPausedAt, null,
                        savedTimeoutPolicy, savedApprovalTimeout);
                // A cancel signalled in this window must win over the restore —
                // remove-and-recheck so a signal racing the remove is not dropped.
                removeTokenAndConvertIfSignalled(gc, listener);
                if (listener != null) {
                    // Curated: never push the raw exception text to an SSE client.
                    listener.onGroupError(new GroupConversationEventSink.GroupErrorEvent(
                            "Resume failed — the discussion remains awaiting approval; retry."));
                }
                return;
            }

            try {
                executeDiscussion(gc, groupConfig, phases, question, listener, startFromPhase);
            } catch (Exception e) {
                // executeDiscussion already persisted the terminal state (FAILED or
                // CANCELLED) and fired the listener — do NOT restore the pause or
                // fire a second error event here.
                LOGGER.errorf(e, "Resumed group discussion %s failed", groupConversationId);
            }
        };
        try {
            executorService.submit(callerIdentityContext.withIdentity(callerIdentityContext.captureOrCurrent(), resumeWork));
        } catch (RuntimeException e) {
            // Executor saturated/shut down — no thread will run the resume. The CAS
            // above already consumed the pause; restore it so the approval remains
            // actionable instead of leaving an IN_PROGRESS zombie.
            activeTokens.remove(gc.getId());
            restoreGroupPause(gc, savedPhaseIndex, savedPhaseName, savedPauseType, savedPausedAt, null,
                    savedTimeoutPolicy, savedApprovalTimeout);
            throw new IResourceStore.ResourceStoreException(
                    "Failed to submit resumed group discussion: " + e.getMessage(), e);
        }

        // #35: only NOW — after the resume was actually enqueued — count the resume
        // and write the audit entry, so a rolled-back submit does not pollute the
        // metric or the compliance trail (mirrors the regular surface).
        counterGroupHitlResume.increment();
        auditHitlDecision(gc, decision);

        // The live gc instance is being mutated by the background thread — hand
        // the HTTP layer a freshly-read copy instead (CME-safe serialization).
        try {
            return conversationStore.read(groupConversationId);
        } catch (Exception e) {
            LOGGER.debugf("Could not re-read group conversation %s for the response: %s",
                    groupConversationId, e.getMessage());
            return gc;
        }
    }

    /**
     * Restores a consumed group pause after a failed resume: re-sets the bookmark
     * fields, CAS-flips IN_PROGRESS back to AWAITING_APPROVAL, and re-arms the
     * timeout schedule. The human decision is lost (it was never executed) but the
     * approval remains actionable — a transient failure must not terminally FAIL a
     * multi-agent discussion.
     */
    private void restoreGroupPause(GroupConversation gc, int phaseIndex, String phaseName,
                                   GroupConversation.HitlPauseType pauseType, Instant pausedAt,
                                   AgentGroupConfiguration configOrNull,
                                   HitlTimeoutPolicy fallbackTimeoutPolicy, String fallbackApprovalTimeout) {
        try {
            gc.setState(GroupConversationState.AWAITING_APPROVAL);
            gc.setPausedAt(pausedAt);
            gc.setPausedAtPhaseIndex(phaseIndex);
            gc.setPausedPhaseName(phaseName);
            gc.setHitlPauseType(pauseType != null ? pauseType : GroupConversation.HitlPauseType.PHASE);
            gc.setHitlPauseReason("Pause restored after failed resume");
            // #29: resumeDiscussion already NULLED the in-memory timeout bookmark
            // before the CAS, so we must re-set it here or the restore persists a
            // disarmed policy. Prefer a fresh config read; if that fails, fall back
            // to the bookmark values captured BEFORE the clear — never leave null,
            // which parsePolicy treats as WAIT_INDEFINITELY (silently disarming a
            // finite AUTO_REJECT/ABORT policy).
            var config = configOrNull;
            if (config == null) {
                try {
                    var resId = groupStore.getCurrentResourceId(gc.getGroupId());
                    config = resId != null ? groupStore.read(gc.getGroupId(), resId.getVersion()) : null;
                } catch (Exception ignored) {
                    // fall through to the captured fallback below
                }
            }
            if (config != null && config.getHitlConfig() != null) {
                gc.setHitlTimeoutPolicy(config.getHitlConfig().getTimeoutPolicy() != null
                        ? config.getHitlConfig().getTimeoutPolicy()
                        : HitlTimeoutPolicy.WAIT_INDEFINITELY);
                gc.setHitlApprovalTimeout(config.getHitlConfig().getApprovalTimeout());
            } else {
                // Config unreadable — preserve the original bookmark so a finite
                // policy is not silently disarmed.
                gc.setHitlTimeoutPolicy(fallbackTimeoutPolicy);
                gc.setHitlApprovalTimeout(fallbackApprovalTimeout);
            }
            conversationStore.updateIfState(gc, GroupConversationState.IN_PROGRESS);
            scheduleGroupHitlTimeout(gc);
            LOGGER.warnf("Group resume of %s failed — pause restored (AWAITING_APPROVAL)", gc.getId());
        } catch (Exception e) {
            LOGGER.errorf(e, "Failed to restore group pause after failed resume: %s", gc.getId());
        }
    }

    /**
     * Submits an {@code hitl.approval} audit entry for a group HITL decision (#15,
     * EU AI Act). Covers human and automated timeout decisions.
     */
    private void auditHitlDecision(GroupConversation gc, ai.labs.eddi.engine.lifecycle.model.HitlDecision decision) {
        if (auditLedgerService == null || !auditLedgerService.isEnabled() || decision == null) {
            return;
        }
        try {
            var detail = new java.util.LinkedHashMap<String, Object>();
            detail.put("verdict", decision.getVerdict() != null ? decision.getVerdict().name() : "UNKNOWN");
            detail.put("decidedBy", decision.getDecidedBy() != null ? decision.getDecidedBy() : "unknown");
            detail.put("automated", decision.getDecidedBy() != null && decision.getDecidedBy().startsWith("system:"));
            detail.put("surface", "group");
            if (decision.getNote() != null) {
                detail.put("note", decision.getNote());
            }
            auditLedgerService.submit(new ai.labs.eddi.engine.audit.model.AuditEntry(
                    java.util.UUID.randomUUID().toString(), gc.getId(), gc.getGroupId(), null, gc.getUserId(),
                    null, -1, "hitl.approval", "hitl", -1, 0L,
                    java.util.Map.of(), detail, null, null, java.util.List.of(), 0.0,
                    Instant.now(), null, null));
        } catch (Exception e) {
            LOGGER.warnf("Failed to submit HITL audit entry for group conversation %s: %s",
                    gc.getId(), e.getMessage());
        }
    }

    /**
     * Submits an {@code hitl.approval} audit entry when a pending approval is
     * cancelled — a human (or timeout policy) decided NOT to let the gated work
     * proceed, which is just as much an HITL decision as approve/reject.
     */
    private void auditHitlCancellation(GroupConversation gc, ControlSignal mode) {
        if (auditLedgerService == null || !auditLedgerService.isEnabled()) {
            return;
        }
        try {
            var detail = new java.util.LinkedHashMap<String, Object>();
            detail.put("verdict", "CANCELLED");
            detail.put("mode", mode != null ? mode.name() : ControlSignal.CANCEL_GRACEFUL.name());
            detail.put("surface", "group");
            detail.put("pauseReason", gc.getHitlPauseReason() != null ? gc.getHitlPauseReason() : "");
            auditLedgerService.submit(new ai.labs.eddi.engine.audit.model.AuditEntry(
                    java.util.UUID.randomUUID().toString(), gc.getId(), gc.getGroupId(), null, gc.getUserId(),
                    null, -1, "hitl.approval", "hitl", -1, 0L,
                    java.util.Map.of(), detail, null, null, java.util.List.of(), 0.0,
                    Instant.now(), null, null));
        } catch (Exception e) {
            LOGGER.warnf("Failed to submit HITL cancellation audit entry for group conversation %s: %s",
                    gc.getId(), e.getMessage());
        }
    }

    /**
     * Releases resources held across an HITL pause once the conversation reaches a
     * terminal state OUTSIDE the executeDiscussion finally block (cancel-of-paused,
     * REJECTED resume). executeDiscussion skips cleanup while AWAITING_APPROVAL —
     * without this, ephemeral dynamic agents stay deployed and the signing guard's
     * verification cursor (see {@link GroupSigningGuard#forgetConversation}) leaks
     * forever on every paused-then-terminal path.
     */
    private void cleanupAfterTerminalState(GroupConversation gc) {
        signingGuard.forgetConversation(gc.getId());
        try {
            IResourceStore.IResourceId resId = groupStore.getCurrentResourceId(gc.getGroupId());
            if (resId == null) {
                LOGGER.warnf("Terminal cleanup: group config %s not found — ephemeral agents of GC %s not cleaned",
                        gc.getGroupId(), gc.getId());
                return;
            }
            var config = groupStore.read(gc.getGroupId(), resId.getVersion());
            cleanupEphemeralAgents(gc, config);
        } catch (Exception e) {
            LOGGER.warnf("Terminal cleanup failed for group conversation %s: %s", gc.getId(), e.getMessage());
        }
    }

    /**
     * Deletes any existing HITL timeout schedule for the given group conversation.
     * Called on resume and cancel to prevent stale fires.
     */
    private void deleteGroupHitlTimeoutSchedule(String groupConversationId) {
        try {
            int deleted = scheduleStore.deleteSchedulesByName(
                    ai.labs.eddi.engine.hitl.HitlSchedules.groupTimeoutScheduleName(groupConversationId));
            if (deleted > 0) {
                LOGGER.infof("Cleaned up %d group HITL timeout schedule(s) for %s", deleted, groupConversationId);
            }
        } catch (Exception e) {
            LOGGER.warnf("Failed to delete group HITL timeout schedule for %s: %s",
                    groupConversationId, e.getMessage());
        }
    }
}
