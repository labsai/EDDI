/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.agents.AgentSigningService;
import ai.labs.eddi.engine.tenancy.QuotaExceededException;
import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.engine.audit.AuditLedgerService;
import ai.labs.eddi.configs.agents.crypto.AgentPublicKey;
import ai.labs.eddi.configs.agents.crypto.NonceCacheService;
import ai.labs.eddi.configs.agents.crypto.SignedEnvelope;
import ai.labs.eddi.utils.LogSanitizer;
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
import ai.labs.eddi.engine.memory.ConversationOutputExtractor;
import ai.labs.eddi.engine.memory.model.ConversationState;
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

    private final IAgentGroupStore groupStore;
    private final IGroupConversationStore conversationStore;
    private final IConversationService conversationService;
    private final IAgentFactory agentFactory;
    private final ITemplatingEngine templatingEngine;
    private final IJsonSerialization jsonSerialization;
    private final int maxDepth;
    private final ExecutorService executorService;
    private final AgentSigningService agentSigningService;
    private final IAgentStore agentStore;
    private final IScheduleStore scheduleStore;
    private final NonceCacheService nonceCacheService;
    private final AuditLedgerService auditLedgerService;
    private final String defaultTenantId;

    // Incremental peer verification: tracks the last verified transcript index
    // per group conversation ID, so we only verify new entries each turn (O(N)
    // amortized instead of O(N²)). Cleaned up when conversations complete.
    private final ConcurrentHashMap<String, Integer> lastVerifiedIndex = new ConcurrentHashMap<>();

    // Metrics
    private final Timer timerGroupDiscussion;
    private final Counter counterGroupDiscussion;
    private final Counter counterGroupFailure;
    private final Counter counterGroupHitlPause;
    private final Counter counterGroupHitlResume;

    @Inject
    public GroupConversationService(IAgentGroupStore groupStore, IGroupConversationStore conversationStore, IConversationService conversationService,
            IAgentFactory agentFactory, ITemplatingEngine templatingEngine, IJsonSerialization jsonSerialization, MeterRegistry meterRegistry,
            AgentSigningService agentSigningService, IAgentStore agentStore, IScheduleStore scheduleStore,
            NonceCacheService nonceCacheService, AuditLedgerService auditLedgerService,
            @ConfigProperty(name = "eddi.tenant.default-id", defaultValue = "default") String defaultTenantId,
            @ConfigProperty(name = "eddi.groups.max-depth", defaultValue = "3") int maxDepth) {
        this.groupStore = groupStore;
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
        // Virtual threads — lightweight, no pool sizing, ideal for parallel agent calls
        this.executorService = Executors.newVirtualThreadPerTaskExecutor();

        this.timerGroupDiscussion = meterRegistry.timer("eddi_group_discussion_duration");
        this.counterGroupDiscussion = meterRegistry.counter("eddi_group_discussion_count");
        this.counterGroupFailure = meterRegistry.counter("eddi_group_discussion_failure_count");
        this.counterGroupHitlPause = meterRegistry.counter("eddi_hitl_pause_count", "surface", "group");
        this.counterGroupHitlResume = meterRegistry.counter("eddi_hitl_resume_count", "surface", "group");
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
            throw new IResourceStore.ResourceNotFoundException("Group not found: " + groupId);
        }
        AgentGroupConfiguration config = groupStore.read(groupId, currentGroupId.getVersion());
        if (config == null) {
            throw new IResourceStore.ResourceNotFoundException("Group configuration not found: " + groupId);
        }

        // Resolve phases
        List<DiscussionPhase> phases = resolvePhases(config);
        if (phases.isEmpty()) {
            throw new GroupDiscussionException("No phases defined for group: " + groupId);
        }

        GroupConversation gc = createGroupConversation(groupId, question, userId, depth);
        return executeDiscussion(gc, config, phases, question, listener, 0);
    }

    @Override
    public GroupConversation startAndDiscussAsync(String groupId, String question, String userId, GroupDiscussionEventListener listener)
            throws GroupDiscussionException, IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {

        if (groupId == null) {
            throw new IllegalArgumentException("groupId must not be null");
        }

        // Validate early — so errors are returned synchronously
        IResourceStore.IResourceId currentGroupId = groupStore.getCurrentResourceId(groupId);
        if (currentGroupId == null) {
            throw new IResourceStore.ResourceNotFoundException("Group not found: " + groupId);
        }
        AgentGroupConfiguration config = groupStore.read(groupId, currentGroupId.getVersion());
        if (config == null) {
            throw new IResourceStore.ResourceNotFoundException("Group configuration not found: " + groupId);
        }

        List<DiscussionPhase> phases = resolvePhases(config);
        if (phases.isEmpty()) {
            throw new GroupDiscussionException("No phases defined for group: " + groupId);
        }

        // Create the conversation synchronously so we can return its ID
        GroupConversation gc = createGroupConversation(groupId, question, userId, 0);

        // Register the control token BEFORE submitting: the caller already has the
        // conversation ID, so a cancel can arrive before the executor thread runs —
        // it must find a signalable token instead of racing the DB state.
        activeTokens.put(gc.getId(), new DiscussionControlToken());

        // Run the discussion in a virtual thread — reuse the same gc (no duplicate
        // creation)
        try {
            executorService.submit(() -> {
                try {
                    executeDiscussion(gc, config, phases, question, listener, 0);
                } catch (Exception e) {
                    LOGGER.errorf("Async group discussion failed for %s: %s", groupId, e.getMessage());
                    if (listener != null) {
                        listener.onGroupError(new GroupConversationEventSink.GroupErrorEvent(e.getMessage()));
                    }
                }
            });
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

        // AtomicInteger: shared across the phase loop; parallel phases increment
        // from virtual threads. Seed from pausedTurnCount to preserve budget across
        // resumes (M3).
        var turnCounter = new java.util.concurrent.atomic.AtomicInteger(
                gc.getPausedTurnCount() > 0 ? gc.getPausedTurnCount() : 0);

        // Resolve HITL granularity from group config
        boolean taskLevelHitl = config.getHitlConfig() != null
                && config.getHitlConfig().getGranularity() == AgentGroupConfiguration.HitlGranularity.TASK;

        // MAJOR-5: Register control token so cancelDiscussion can signal in-flight.
        // computeIfAbsent — startAndDiscussAsync/resumeDiscussion pre-register the
        // token before submitting, and a cancel signal set on it in that window
        // must NOT be wiped by a fresh token here.
        activeTokens.computeIfAbsent(gc.getId(), k -> new DiscussionControlToken());

        // MINOR-1: Only fire GROUP_START on fresh discussion, not resume
        if (startPhaseIndex == 0 && listener != null) {
            listener.onGroupStart(new GroupConversationEventSink.GroupStartEvent(gc.getId(), gc.getGroupId(), question,
                    config.getStyle() != null ? config.getStyle().name() : "ROUND_TABLE", phases.size(),
                    config.getMembers().stream().map(GroupMember::agentId).toList()));
        }

        try {
            // Execute each phase
            for (int phaseIdx = startPhaseIndex; phaseIdx < phases.size(); phaseIdx++) {
                DiscussionPhase phase = phases.get(phaseIdx);

                // NEW-3: Check control token at top of phase loop
                var token = activeTokens.get(gc.getId());
                if (token != null && token.shouldStop()) {
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
                        executePeerTargetedPhase(gc, config, speakers, phase, protocol, question, phaseIdx, listener, turnCounter, maxTurns);
                    } else if (phase.turnOrder() == TurnOrder.PARALLEL) {
                        executeParallelPhase(gc, config, speakers, phase, protocol, question, phaseIdx, listener, turnCounter, maxTurns);
                    } else {
                        executeSequentialPhase(gc, config, speakers, phase, protocol, question, phaseIdx, listener, turnCounter, maxTurns);
                    }

                    gc.setLastModified(Instant.now());
                    conversationStore.update(gc);

                    if (listener != null) {
                        listener.onPhaseComplete(new GroupConversationEventSink.PhaseCompleteEvent(phaseIdx, phase.name()));
                    }
                }

                // R1: Check for cancel BEFORE the HITL gate. After the wave loop
                // breaks on a cancel signal, control reaches here before the next
                // phase-loop iteration's shouldStop() check. Using isCancelled()
                // (not shouldStop()) so a real PAUSE still routes to commitPause.
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
            gc.setState(GroupConversationState.COMPLETED);
            gc.setPausedTurnCount(0); // Clear turn budget state on successful completion
            gc.setLastModified(Instant.now());
            conversationStore.update(gc);

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
            failConversation(gc);
            if (listener != null) {
                listener.onGroupError(new GroupConversationEventSink.GroupErrorEvent(e.getMessage()));
            }
            throw e;
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
            failConversation(gc);
            if (listener != null) {
                listener.onGroupError(new GroupConversationEventSink.GroupErrorEvent(e.getMessage()));
            }
            throw new GroupDiscussionException("Group discussion failed: " + e.getMessage(), e);
        } finally {
            timerGroupDiscussion.record(System.nanoTime() - startTime, TimeUnit.NANOSECONDS);
            // NEW-2: Always remove the control token — paused conversations have no
            // running thread, so a lingering token causes cancel-of-paused to take
            // the no-op signal branch. Resume re-registers a fresh token.
            activeTokens.remove(gc.getId());
            // Only clean up ephemeral agents when the discussion is truly done
            if (gc.getState() != GroupConversationState.AWAITING_APPROVAL) {
                lastVerifiedIndex.remove(gc.getId());
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
                    ? hitlConfig.getTimeoutPolicy().name()
                    : AgentGroupConfiguration.HitlTimeoutPolicy.WAIT_INDEFINITELY.name());
            gc.setHitlApprovalTimeout(hitlConfig.getApprovalTimeout());
        } else {
            gc.setHitlTimeoutPolicy(AgentGroupConfiguration.HitlTimeoutPolicy.WAIT_INDEFINITELY.name());
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
     * Converts a just-committed pause into a cancellation when a cancel signal
     * landed while the pause was being written. cancelDiscussion saw the live
     * token, signalled it, and reported success — but the running leg had already
     * passed its pre-gate cancel check, so without this the pause would survive a
     * "successful" cancel and the token signal would be dropped by the finally
     * block.
     */
    private void convertPauseToCancelIfSignalled(GroupConversation gc, GroupDiscussionEventListener listener) {
        var token = activeTokens.get(gc.getId());
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
        } catch (Exception e) {
            gc.setState(GroupConversationState.AWAITING_APPROVAL);
            LOGGER.warnf("Failed to convert just-committed pause of GC %s to CANCELLED: %s",
                    gc.getId(), e.getMessage());
        }
    }

    /**
     * Creates a one-shot schedule for group HITL timeout. Reads the pause bookmark
     * fields already set on the conversation (by commitPause/restoreGroupPause) —
     * NOT the group config, so the schedule always matches what approval-status
     * reports even if the config changed since the pause. No-ops if not configured
     * or WAIT_INDEFINITELY.
     */
    private void scheduleGroupHitlTimeout(GroupConversation gc) {
        try {
            String timeoutStr = gc.getHitlApprovalTimeout();
            String policy = gc.getHitlTimeoutPolicy();
            if (timeoutStr == null || timeoutStr.isBlank()
                    || policy == null
                    || AgentGroupConfiguration.HitlTimeoutPolicy.WAIT_INDEFINITELY.name().equals(policy)) {
                return;
            }

            java.time.Duration timeout = java.time.Duration.parse(timeoutStr);
            Instant fireAt = Instant.now().plus(timeout);

            var schedule = new ScheduleConfiguration();
            schedule.setName("hitl-timeout-group-" + gc.getId());
            schedule.setEnabled(true);
            schedule.setOneTimeAt(fireAt.toString());
            schedule.setNextFire(fireAt);
            schedule.setCreatedAt(Instant.now());
            schedule.setMetadata(java.util.Map.of(
                    "hitlType", "hitl_timeout",
                    "policy", policy,
                    "surface", "group",
                    "conversationId", gc.getId()));
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
    public void deleteGroupConversation(String groupConversationId) throws IResourceStore.ResourceStoreException {
        try {
            GroupConversation gc = conversationStore.read(groupConversationId);
            for (String privateConvId : gc.getMemberConversationIds().values()) {
                try {
                    conversationService.endConversation(privateConvId);
                } catch (Exception e) {
                    LOGGER.warnf("Failed to end private conversation %s: %s", privateConvId, e.getMessage());
                }
            }
            conversationStore.delete(groupConversationId);
        } catch (IResourceStore.ResourceNotFoundException e) {
            LOGGER.warnf("Group conversation %s not found for deletion", groupConversationId);
        }
    }

    @Override
    public List<GroupConversation> listGroupConversations(String groupId, int index, int limit) throws IResourceStore.ResourceStoreException {
        return conversationStore.listByGroupId(groupId, index, limit);
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
                            gc.getHitlPauseReason(), gc.getHitlTimeoutPolicy());
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
                    LOGGER.infof("Ephemeral cleanup: deleted agent '%s'", agentId);
                }
            } catch (Exception e) {
                LOGGER.warnf("Ephemeral cleanup failed for agent '%s': %s", agentId, e.getMessage());
            }
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
    // Task-oriented phase execution (TASK_FORCE style)
    // =================================================================

    /**
     * Dispatches task-oriented phases (PLAN, EXECUTE, VERIFY) to their specific
     * handlers. These phases are structurally different from debate phases — they
     * operate on a shared task list rather than iterating speakers with a common
     * question.
     */
    private void executeTaskPhase(GroupConversation gc, AgentGroupConfiguration config, List<GroupMember> speakers,
                                  DiscussionPhase phase, ProtocolConfig protocol, String question, int phaseIdx,
                                  GroupDiscussionEventListener listener, java.util.concurrent.atomic.AtomicInteger turnCounter, int maxTurns)
            throws GroupDiscussionException {

        switch (phase.type()) {
            case PLAN -> executeTaskPlanPhase(gc, config, speakers, phase, protocol, question, phaseIdx, listener, turnCounter, maxTurns);
            case EXECUTE -> executeTaskExecutionPhase(gc, config, speakers, phase, protocol, question, phaseIdx, listener, turnCounter, maxTurns);
            case VERIFY -> executeTaskVerificationPhase(gc, config, speakers, phase, protocol, question, phaseIdx, listener, turnCounter, maxTurns);
            default -> LOGGER.warnf("Unexpected phase type %s routed to executeTaskPhase", phase.type());
        }
    }

    /**
     * PLAN phase: Decompose the goal into tasks. If pre-configured tasks exist in
     * the group config, uses those directly (skipping LLM planning). Otherwise, the
     * moderator agent decomposes the goal via its pipeline and the output is parsed
     * with three-tier fallback (JSON → Markdown → single task).
     */
    private void executeTaskPlanPhase(GroupConversation gc, AgentGroupConfiguration config, List<GroupMember> speakers,
                                      DiscussionPhase phase, ProtocolConfig protocol, String question, int phaseIdx,
                                      GroupDiscussionEventListener listener, java.util.concurrent.atomic.AtomicInteger turnCounter, int maxTurns)
            throws GroupDiscussionException {

        if (gc.getTaskList() == null) {
            gc.setTaskList(new SharedTaskList());
        }

        boolean preConfigured = config.getTasks() != null && !config.getTasks().isEmpty();

        if (preConfigured) {
            // Config-driven tasks — skip LLM planning
            // First pass: create all TaskItems
            List<TaskItem> createdItems = new ArrayList<>();
            for (TaskDefinition td : config.getTasks()) {
                TaskItem task = new TaskItem(td.subject(), td.description(), td.priority());
                gc.getTaskList().addTask(task);
                createdItems.add(task);
            }

            // Second pass: resolve dependsOn subjects to task IDs
            for (int i = 0; i < config.getTasks().size(); i++) {
                TaskDefinition td = config.getTasks().get(i);
                TaskItem original = createdItems.get(i);
                if (td.dependsOn() != null && !td.dependsOn().isEmpty()) {
                    List<String> resolvedDepIds = td.dependsOn().stream()
                            .map(depSubject -> createdItems.stream()
                                    .filter(ci -> ci.subject().equalsIgnoreCase(depSubject))
                                    .map(TaskItem::id)
                                    .findFirst().orElse(null))
                            .filter(java.util.Objects::nonNull)
                            .toList();
                    if (!resolvedDepIds.isEmpty()) {
                        // Replace with dependency-aware TaskItem
                        TaskItem withDeps = new TaskItem(
                                original.id(), original.subject(), original.description(),
                                original.status(), original.assignedAgentId(), original.assignedDisplayName(),
                                resolvedDepIds, original.result(), original.verificationNote(),
                                original.verified(), original.priority(), original.createdAt(), original.completedAt());
                        gc.getTaskList().updateTask(withDeps); // replace with dependency-aware version
                    }
                }
            }

            // Third pass: resolve assignments with round-robin for "ALL"
            for (int i = 0; i < createdItems.size(); i++) {
                TaskItem task = createdItems.get(i);
                TaskDefinition td = config.getTasks().get(i);
                String assignedAgentId = resolveTaskAssignment(
                        td.assignToRole(), config.getMembers(), config.getModeratorAgentId(), i);
                if (assignedAgentId != null) {
                    GroupMember assignedMember = findMember(config.getMembers(), assignedAgentId);
                    String displayName = assignedMember != null ? assignedMember.displayName() : assignedAgentId;
                    gc.getTaskList().assignTask(task.id(), assignedAgentId, displayName);
                } else {
                    LOGGER.warnf("Could not resolve assignment for task '%s' with role '%s'",
                            task.subject(), td.assignToRole());
                }
            }

            gc.getTranscript().add(new TranscriptEntry(
                    "system", "System",
                    "Pre-configured task plan: " + config.getTasks().size() + " tasks",
                    phaseIdx, phase.name(), TranscriptEntryType.PLAN,
                    Instant.now(), null, null));

        } else {
            // LLM-driven planning via moderator
            if (speakers.isEmpty()) {
                throw new GroupDiscussionException("PLAN phase requires a moderator but no speakers resolved");
            }

            GroupMember planner = speakers.getFirst();
            turnCounter.incrementAndGet();

            if (listener != null) {
                listener.onSpeakerStart(
                        new GroupConversationEventSink.SpeakerStartEvent(planner.agentId(), planner.displayName(), phaseIdx, phase.name()));
            }

            // Build planning input with member info
            String planTemplate = DiscussionStylePresets.defaultTemplate(PhaseType.PLAN);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("question", question);
            data.put("displayName", planner.displayName());
            List<Map<String, Object>> memberList = config.getMembers().stream()
                    .filter(m -> !m.agentId().equals(planner.agentId()) || config.getMembers().size() == 1)
                    .map(m -> {
                        Map<String, Object> md = new LinkedHashMap<>();
                        md.put("agentId", m.agentId());
                        md.put("displayName", m.displayName());
                        md.put("capabilities", m.role() != null ? m.role() : "");
                        return md;
                    }).collect(Collectors.toList());
            data.put("members", memberList);

            String planInput;
            try {
                planInput = templatingEngine.processTemplate(planTemplate, data, ITemplatingEngine.TemplateMode.TEXT);
            } catch (ITemplatingEngine.TemplateEngineException e) {
                planInput = "Decompose this goal into tasks for your team: " + question;
            }

            TranscriptEntry planEntry = executeAgentTurn(planner, gc, planInput, protocol, phaseIdx, phase, null);
            gc.getTranscript().add(planEntry);

            if (listener != null) {
                listener.onSpeakerComplete(new GroupConversationEventSink.SpeakerCompleteEvent(
                        planner.agentId(), planner.displayName(), planEntry.content(), phaseIdx, phase.name()));
            }

            // Parse the plan output
            List<TaskListParser.ParsedTask> parsedTasks = TaskListParser.parse(planEntry.content(), config.getMembers());

            for (int i = 0; i < parsedTasks.size(); i++) {
                TaskListParser.ParsedTask pt = parsedTasks.get(i);
                TaskItem task = new TaskItem(pt.subject(), pt.description(), pt.priority());
                gc.getTaskList().addTask(task);

                // Resolve assignment — null-safe (C4 fix)
                String agentId = TaskListParser.resolveAgent(pt.assignedTo(), config.getMembers());
                if (agentId == null) {
                    agentId = TaskListParser.roundRobinAssign(i, config.getMembers());
                    LOGGER.debugf("Could not resolve assignee '%s', round-robin assigning to %s", pt.assignedTo(), agentId);
                }
                if (agentId != null) {
                    GroupMember member = findMember(config.getMembers(), agentId);
                    String displayName = member != null ? member.displayName() : agentId;
                    gc.getTaskList().assignTask(task.id(), agentId, displayName);
                } else {
                    LOGGER.warnf("Task '%s' has no assignable agent, will be skipped during execution", pt.subject());
                }
            }
        }

        // Validate no circular dependencies (covers both pre-configured and LLM-planned
        // paths)
        List<String> cycles = gc.getTaskList().detectCycles();
        if (!cycles.isEmpty()) {
            throw new GroupDiscussionException(
                    "Circular task dependencies detected: " + String.join(" → ", cycles));
        }

        // Emit task plan event
        if (listener != null) {
            List<GroupConversationEventSink.TaskSummary> summaries = gc.getTaskList().all().stream()
                    .map(t -> new GroupConversationEventSink.TaskSummary(t.id(), t.subject(), t.assignedDisplayName(), t.priority()))
                    .toList();
            listener.onTaskPlanCreated(new GroupConversationEventSink.TaskPlanCreatedEvent(summaries, preConfigured));
        }
    }

    /**
     * EXECUTE phase: Run each assigned task by sending it to the responsible
     * agent's pipeline. Tasks for different agents execute in parallel; tasks for
     * the same agent execute sequentially within a single CompletableFuture.
     */
    private void executeTaskExecutionPhase(GroupConversation gc, AgentGroupConfiguration config, List<GroupMember> speakers,
                                           DiscussionPhase phase, ProtocolConfig protocol, String question, int phaseIdx,
                                           GroupDiscussionEventListener listener, java.util.concurrent.atomic.AtomicInteger turnCounter, int maxTurns)
            throws GroupDiscussionException {

        if (gc.getTaskList() == null || gc.getTaskList().isEmpty()) {
            LOGGER.warn("EXECUTE phase: no tasks to execute");
            return;
        }

        // Resolve HITL TASK-level flag locally (not available from executeDiscussion
        // scope)
        boolean taskLevelHitl = config.getHitlConfig() != null
                && config.getHitlConfig().getGranularity() == AgentGroupConfiguration.HitlGranularity.TASK;

        // Note: unlike executeParallelPhase, no transcript snapshot is needed here
        // because agents receive task-specific input via buildTaskExecutionInput(),
        // not transcript context.

        List<GroupDiscussionException> errors = Collections.synchronizedList(new ArrayList<>());
        int timeout = protocol.agentTimeoutSeconds() > 0 ? protocol.agentTimeoutSeconds() : 60;
        int maxWaves = 100; // safety cap to prevent infinite loops

        // Wave loop: re-query executable tasks after each wave completes.
        // Tasks that become executable when their dependencies finish are picked up
        // in the next wave. This handles dependsOn chains across any depth.
        for (int wave = 0; wave < maxWaves; wave++) {
            // NEW-3: Check control token at top of wave loop
            var token = activeTokens.get(gc.getId());
            if (token != null && token.shouldStop()) {
                LOGGER.infof("EXECUTE wave loop cancelled via control token at wave %d", wave);
                break;
            }

            Map<String, List<TaskItem>> tasksByAgent = gc.getTaskList().findExecutableTasks().stream()
                    .filter(t -> t.assignedAgentId() != null)
                    .collect(Collectors.groupingBy(TaskItem::assignedAgentId));

            if (tasksByAgent.isEmpty()) {
                if (wave == 0) {
                    LOGGER.warn("EXECUTE phase: no assigned tasks found");
                }
                break; // no more executable tasks — all waves complete
            }

            LOGGER.debugf("EXECUTE phase wave %d: %d agents, %d tasks",
                    wave + 1, tasksByAgent.size(),
                    tasksByAgent.values().stream().mapToInt(List::size).sum());

            // Execute agents in parallel, tasks per agent sequentially
            List<CompletableFuture<Void>> futures = new ArrayList<>();

            for (Map.Entry<String, List<TaskItem>> agentEntry : tasksByAgent.entrySet()) {
                String agentId = agentEntry.getKey();
                List<TaskItem> agentTasks = agentEntry.getValue();
                GroupMember member = findMemberIncludingDynamic(config.getMembers(), gc, agentId);

                if (member == null) {
                    LOGGER.warnf("Task assigned to unknown agent '%s', skipping", agentId);
                    continue;
                }

                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    for (TaskItem task : agentTasks) {
                        if (turnCounter.get() >= maxTurns) {
                            break;
                        }
                        try {
                            turnCounter.incrementAndGet();
                            gc.getTaskList().startTask(task.id());

                            if (listener != null) {
                                listener.onSpeakerStart(new GroupConversationEventSink.SpeakerStartEvent(
                                        member.agentId(), member.displayName(), phaseIdx, phase.name()));
                            }

                            // Build task-specific input
                            String taskInput = buildTaskExecutionInput(task, question, phase, gc);
                            TranscriptEntry entry = executeAgentTurn(member, gc, taskInput, protocol, phaseIdx, phase, null);

                            synchronized (gc.getTranscript()) {
                                gc.getTranscript().add(entry);
                            }

                            // HITL TASK-level: submit for approval only when BOTH
                            // taskLevelHitl AND this phase requires approval. Otherwise
                            // auto-complete. Without this check, TASK_FORCE phases
                            // (requiresApproval=false) strand tasks in AWAITING_APPROVAL.
                            if (taskLevelHitl && phase.requiresApproval()) {
                                gc.getTaskList().submitForApproval(task.id(), entry.content());
                            } else {
                                gc.getTaskList().completeTask(task.id(), entry.content());
                            }

                            if (listener != null) {
                                listener.onSpeakerComplete(new GroupConversationEventSink.SpeakerCompleteEvent(
                                        member.agentId(), member.displayName(), entry.content(), phaseIdx, phase.name()));
                            }

                        } catch (GroupDiscussionException e) {
                            // Quota errors are non-retryable — abort all tasks immediately
                            if (e.getCause() instanceof QuotaExceededException) {
                                errors.add(e);
                                return; // exit the entire agent's CompletableFuture
                            }
                            handleTaskFailure(gc, task, member, e.getMessage(), phaseIdx, phase, listener, errors, e);
                            if (protocol.onAgentFailure() == ProtocolConfig.MemberFailurePolicy.ABORT) {
                                break;
                            }
                        } catch (IllegalStateException e) {
                            // H5 fix: catch status transition errors (e.g., double completion)
                            LOGGER.warnf("Task state error for '%s': %s", task.subject(), e.getMessage());
                            handleTaskFailure(gc, task, member, e.getMessage(), phaseIdx, phase, listener, errors,
                                    new GroupDiscussionException(e.getMessage(), e));
                        }
                    }
                }, executorService);
                futures.add(future);
            }

            // Wait for this wave — timeout based on max tasks per agent (H2 fix)
            int maxTasksPerAgent = tasksByAgent.values().stream().mapToInt(List::size).max().orElse(1);
            try {
                var allOf = CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
                // NEW-3: Register the blocking future so IMMEDIATE cancel can interrupt
                if (token != null) {
                    token.setActiveFuture(allOf);
                }
                allOf.get(timeout * (long) maxTasksPerAgent, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                LOGGER.warnf("Task execution timed out for group %s (wave %d)",
                        LogSanitizer.sanitize(gc.getGroupId()), wave + 1);
                futures.forEach(f -> f.cancel(true));
                resetStrandedInProgressTasks(gc, "wave timeout");
                break;
            } catch (java.util.concurrent.CancellationException e) {
                // R2: CANCEL_IMMEDIATE fires allOf.cancel(true) → CancellationException.
                // Forward-cancel all source agent futures (allOf.cancel doesn't propagate).
                LOGGER.infof("Wave cancelled via CANCEL_IMMEDIATE for group %s (wave %d)",
                        LogSanitizer.sanitize(gc.getGroupId()), wave + 1);
                futures.forEach(f -> f.cancel(true));
                resetStrandedInProgressTasks(gc, "wave cancellation");
                break;
            } catch (ExecutionException | InterruptedException e) {
                LOGGER.warnf("Task execution error for group %s: %s",
                        LogSanitizer.sanitize(gc.getGroupId()), LogSanitizer.sanitize(e.getMessage()));
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                // R2: Forward-cancel remaining source futures on any error
                futures.forEach(f -> f.cancel(true));
                resetStrandedInProgressTasks(gc, "wave error");
                break;
            }

            // Quota errors always abort, regardless of onAgentFailure policy
            for (GroupDiscussionException error : errors) {
                if (error.getCause() instanceof QuotaExceededException) {
                    throw error;
                }
            }

            // If ABORT policy and there were errors, stop further waves
            if (protocol.onAgentFailure() == ProtocolConfig.MemberFailurePolicy.ABORT && !errors.isEmpty()) {
                throw errors.getFirst();
            }

            if (turnCounter.get() >= maxTurns) {
                break;
            }
        }

        // Final error propagation after all waves (ABORT policy)
        if (protocol.onAgentFailure() == ProtocolConfig.MemberFailurePolicy.ABORT && !errors.isEmpty()) {
            throw errors.getFirst();
        }
    }

    /**
     * Resets tasks stranded IN_PROGRESS by an aborted wave back to ASSIGNED.
     * Without this, a TASK-level pause committed after the abort persists tasks
     * that {@code findExecutableTasks} can never pick up again — they and their
     * dependents would silently never execute after resume (F11).
     */
    private void resetStrandedInProgressTasks(GroupConversation gc, String cause) {
        if (gc.getTaskList() == null) {
            return;
        }
        gc.getTaskList().all().stream()
                .filter(t -> t.status() == SharedTaskList.TaskStatus.IN_PROGRESS)
                .forEach(t -> {
                    try {
                        gc.getTaskList().resetToAssigned(t.id());
                        LOGGER.infof("Reset stranded task '%s' to ASSIGNED after %s", t.id(), cause);
                    } catch (Exception ex) {
                        LOGGER.warnf("Failed to reset task '%s': %s", t.id(), ex.getMessage());
                    }
                });
    }

    /**
     * VERIFY phase: The moderator reviews all completed tasks and provides
     * pass/fail assessments. Results are parsed and applied to the task list.
     */
    private void executeTaskVerificationPhase(GroupConversation gc, AgentGroupConfiguration config, List<GroupMember> speakers,
                                              DiscussionPhase phase, ProtocolConfig protocol, String question, int phaseIdx,
                                              GroupDiscussionEventListener listener, java.util.concurrent.atomic.AtomicInteger turnCounter,
                                              int maxTurns)
            throws GroupDiscussionException {

        if (gc.getTaskList() == null || gc.getTaskList().isEmpty()) {
            LOGGER.warn("VERIFY phase: no tasks to verify");
            return;
        }

        List<TaskItem> completedTasks = gc.getTaskList().all().stream()
                .filter(t -> t.status() == TaskStatus.COMPLETED)
                .toList();

        if (completedTasks.isEmpty()) {
            LOGGER.warn("VERIFY phase: no completed tasks to verify");
            gc.getTranscript().add(new TranscriptEntry(
                    "system", "System", "No completed tasks to verify",
                    phaseIdx, phase.name(), TranscriptEntryType.VERIFICATION,
                    Instant.now(), null, null));
            return;
        }

        if (speakers.isEmpty()) {
            LOGGER.warn("VERIFY phase: no verifier available");
            return;
        }

        GroupMember verifier = speakers.getFirst();
        turnCounter.incrementAndGet();

        if (listener != null) {
            listener.onSpeakerStart(
                    new GroupConversationEventSink.SpeakerStartEvent(verifier.agentId(), verifier.displayName(), phaseIdx, phase.name()));
        }

        // Build verification input
        String verifyTemplate = DiscussionStylePresets.defaultTemplate(PhaseType.VERIFY);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("question", question);
        data.put("displayName", verifier.displayName());
        List<Map<String, Object>> taskData = completedTasks.stream().map(t -> {
            Map<String, Object> td = new LinkedHashMap<>();
            td.put("subject", t.subject());
            td.put("description", t.description());
            td.put("assignedDisplayName", t.assignedDisplayName());
            td.put("result", t.result() != null ? t.result() : "(no result)");
            return td;
        }).collect(Collectors.toList());
        data.put("completedTasks", taskData);

        String verifyInput;
        try {
            verifyInput = templatingEngine.processTemplate(verifyTemplate, data, ITemplatingEngine.TemplateMode.TEXT);
        } catch (ITemplatingEngine.TemplateEngineException e) {
            verifyInput = "Review the task results and provide pass/fail for each task.";
        }

        TranscriptEntry verifyEntry = executeAgentTurn(verifier, gc, verifyInput, protocol, phaseIdx, phase, null);
        gc.getTranscript().add(verifyEntry);

        if (listener != null) {
            listener.onSpeakerComplete(new GroupConversationEventSink.SpeakerCompleteEvent(
                    verifier.agentId(), verifier.displayName(), verifyEntry.content(), phaseIdx, phase.name()));
        }

        // Parse verification results — same three-tier fallback
        parseAndApplyVerification(gc, completedTasks, verifyEntry.content(), listener);
    }

    /**
     * Builds the input message for a task execution phase, respecting the
     * configured context scope.
     */
    private String buildTaskExecutionInput(TaskItem task, String question, DiscussionPhase phase, GroupConversation gc) {
        String template = DiscussionStylePresets.defaultTemplate(PhaseType.EXECUTE);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("question", question);
        data.put("taskSubject", task.subject());
        data.put("taskDescription", task.description());

        // Add dependency results if scope is TASK_WITH_DEPS
        if (phase.contextScope() == ContextScope.TASK_WITH_DEPS && gc.getTaskList() != null) {
            List<Map<String, Object>> depResults = task.dependsOnIds().stream()
                    .map(depId -> gc.getTaskList().findById(depId))
                    .filter(dep -> dep != null && dep.result() != null)
                    .map(dep -> {
                        Map<String, Object> dr = new LinkedHashMap<>();
                        dr.put("subject", dep.subject());
                        dr.put("result", dep.result());
                        return dr;
                    }).collect(Collectors.toList());
            if (!depResults.isEmpty()) {
                data.put("dependencyResults", depResults);
            }
        }

        String input;
        try {
            input = templatingEngine.processTemplate(template, data, ITemplatingEngine.TemplateMode.TEXT);
        } catch (ITemplatingEngine.TemplateEngineException e) {
            LOGGER.warnf("Template processing failed for task execution, using plain text: %s", e.getMessage());
            input = "Task: " + task.subject() + "\n" + task.description();
        }
        // RETRY rejection policy: surface the reviewer's rejection feedback so the
        // re-executing agent addresses it instead of reproducing the same output
        if (task.verificationNote() != null && !task.verificationNote().isBlank()) {
            input += "\n\nReviewer feedback on the previous attempt (address this): " + task.verificationNote();
        }
        return input;
    }

    /**
     * Parses verification output and applies pass/fail to the task list. Falls back
     * to marking all tasks as passed if parsing fails (safe default).
     */
    private void parseAndApplyVerification(GroupConversation gc, List<TaskItem> completedTasks,
                                           String verifyContent, GroupDiscussionEventListener listener) {
        // H4 fix: dedicated verification parser that reads 'passed' boolean from JSON
        try {
            if (verifyContent != null && verifyContent.contains("[")) {
                if (tryParseVerificationJson(gc, completedTasks, verifyContent, listener)) {
                    return;
                }
            }
        } catch (Exception e) {
            LOGGER.debugf("Failed to parse verification output, marking all as passed: %s", e.getMessage());
        }

        // Fallback: mark all completed tasks as verified (safe default)
        for (TaskItem task : completedTasks) {
            if (task.status() == TaskStatus.COMPLETED) {
                gc.getTaskList().verifyTask(task.id(), true, "Auto-verified (verification parse failed)");
                if (listener != null) {
                    listener.onTaskVerified(new GroupConversationEventSink.TaskVerifiedEvent(
                            task.id(), task.subject(), true, "Auto-verified"));
                }
            }
        }
    }

    /**
     * Attempts to parse verification results from JSON. The expected schema is:
     * {@code [{"subject": "...", "passed": true, "feedback": "..."}]}
     *
     * @return true if parsing succeeded and at least one task was verified
     */
    @SuppressWarnings("unchecked")
    private boolean tryParseVerificationJson(GroupConversation gc, List<TaskItem> completedTasks,
                                             String content, GroupDiscussionEventListener listener) {
        try {
            // Extract JSON array from content (may be wrapped in markdown fences)
            int jsonStart = content.indexOf('[');
            int jsonEnd = content.lastIndexOf(']');
            if (jsonStart < 0 || jsonEnd <= jsonStart) {
                return false;
            }
            String json = content.substring(jsonStart, jsonEnd + 1);

            var items = jsonSerialization.deserialize(json, List.class);
            if (items == null || items.isEmpty()) {
                return false;
            }

            boolean anyVerified = false;
            for (Object item : items) {
                if (item instanceof Map<?, ?> map) {
                    String subject = map.containsKey("subject") ? String.valueOf(map.get("subject")) : null;
                    // Read 'passed' boolean directly from JSON
                    boolean passed = true; // default to passed
                    if (map.containsKey("passed")) {
                        Object passedVal = map.get("passed");
                        passed = Boolean.TRUE.equals(passedVal) || "true".equalsIgnoreCase(String.valueOf(passedVal));
                    }
                    String feedback = map.containsKey("feedback") ? String.valueOf(map.get("feedback")) : null;

                    if (subject != null) {
                        for (TaskItem task : completedTasks) {
                            if (task.subject().equalsIgnoreCase(subject) && task.status() == TaskStatus.COMPLETED) {
                                gc.getTaskList().verifyTask(task.id(), passed, feedback);
                                if (listener != null) {
                                    listener.onTaskVerified(new GroupConversationEventSink.TaskVerifiedEvent(
                                            task.id(), task.subject(), passed, feedback));
                                }
                                anyVerified = true;
                                break;
                            }
                        }
                    }
                }
            }
            return anyVerified;
        } catch (Exception e) {
            LOGGER.debugf("Verification JSON parse failed: %s", e.getMessage());
            return false;
        }
    }

    // --- Task assignment helpers ---

    /**
     * Resolves task assignment. For "ALL" role, uses round-robin across
     * non-moderator members to distribute tasks evenly (H3 fix).
     *
     * @param taskIndex
     *            index of the task in the list, used for round-robin distribution
     */
    private String resolveTaskAssignment(String assignToRole, List<GroupMember> members,
                                         String moderatorAgentId, int taskIndex) {
        if (assignToRole == null || "ALL".equalsIgnoreCase(assignToRole)) {
            // Round-robin across non-moderator members (H3 fix)
            List<GroupMember> eligible = members.stream()
                    .filter(m -> !m.agentId().equals(moderatorAgentId))
                    .toList();
            if (eligible.isEmpty()) {
                return members.isEmpty() ? null : members.getFirst().agentId();
            }
            return eligible.get(taskIndex % eligible.size()).agentId();
        }
        if (assignToRole.toUpperCase().startsWith("ROLE:")) {
            String role = assignToRole.substring(5).trim();
            return members.stream()
                    .filter(m -> role.equalsIgnoreCase(m.role()))
                    .map(GroupMember::agentId)
                    .findFirst()
                    .orElse(null);
        }
        // Direct agentId reference
        return TaskListParser.resolveAgent(assignToRole, members);
    }

    /**
     * Centralized error handling for task failures during EXECUTE phase (H6 fix).
     * Marks the task as failed, adds an error transcript entry, emits SSE events,
     * and collects the error for potential ABORT propagation.
     */
    private void handleTaskFailure(GroupConversation gc, TaskItem task, GroupMember member,
                                   String errorMessage, int phaseIdx, DiscussionPhase phase,
                                   GroupDiscussionEventListener listener,
                                   List<GroupDiscussionException> errors, GroupDiscussionException ex) {
        try {
            gc.getTaskList().failTask(task.id(), errorMessage);
        } catch (IllegalStateException ise) {
            LOGGER.debugf("Could not fail task '%s' (already terminal): %s", task.id(), ise.getMessage());
        }

        // Add error transcript entry
        synchronized (gc.getTranscript()) {
            gc.getTranscript().add(new TranscriptEntry(
                    member.agentId(), member.displayName(),
                    "[ERROR] Task '%s' failed: %s".formatted(task.subject(), errorMessage),
                    phaseIdx, phase.name(), TranscriptEntryType.TASK_RESULT,
                    Instant.now(), null, null));
        }

        // Emit error event so SSE clients see the failure
        if (listener != null) {
            listener.onSpeakerComplete(new GroupConversationEventSink.SpeakerCompleteEvent(
                    member.agentId(), member.displayName(),
                    "[ERROR] " + errorMessage, phaseIdx, phase.name()));
        }

        errors.add(ex);
    }

    private GroupMember findMember(List<GroupMember> members, String agentId) {
        if (agentId == null)
            return null;
        return members.stream()
                .filter(m -> agentId.equals(m.agentId()))
                .findFirst()
                .orElse(null);
    }

    /**
     * Find a member by agentId, searching both static config members and
     * dynamically added members from the conversation.
     */
    private GroupMember findMemberIncludingDynamic(List<GroupMember> configMembers, GroupConversation gc, String agentId) {
        GroupMember member = findMember(configMembers, agentId);
        if (member == null && gc.getDynamicMembers() != null) {
            List<GroupMember> dynamicMembers = gc.getDynamicMembers();
            synchronized (dynamicMembers) {
                member = findMember(dynamicMembers, agentId);
            }
        }
        return member;
    }

    // =================================================================
    // Phase execution (debate styles)
    // =================================================================

    private void executeSequentialPhase(GroupConversation gc, AgentGroupConfiguration config, List<GroupMember> speakers, DiscussionPhase phase,
                                        ProtocolConfig protocol, String question, int phaseIdx, GroupDiscussionEventListener listener,
                                        java.util.concurrent.atomic.AtomicInteger turnCounter, int maxTurns)
            throws GroupDiscussionException {
        for (GroupMember speaker : speakers) {
            if (turnCounter.get() >= maxTurns) {
                break;
            }
            turnCounter.incrementAndGet();
            if (listener != null) {
                listener.onSpeakerStart(
                        new GroupConversationEventSink.SpeakerStartEvent(speaker.agentId(), speaker.displayName(), phaseIdx, phase.name()));
            }
            String input = buildPhaseInput(phase, speaker, question, gc.getTranscript(), phaseIdx, null);
            TranscriptEntry entry = executeAgentTurn(speaker, gc, input, protocol, phaseIdx, phase, null);
            gc.getTranscript().add(entry);
            if (listener != null) {
                listener.onSpeakerComplete(new GroupConversationEventSink.SpeakerCompleteEvent(speaker.agentId(), speaker.displayName(),
                        entry.content(), phaseIdx, phase.name()));
            }
        }
    }

    private void executeParallelPhase(GroupConversation gc, AgentGroupConfiguration config, List<GroupMember> speakers, DiscussionPhase phase,
                                      ProtocolConfig protocol, String question, int phaseIdx, GroupDiscussionEventListener listener,
                                      java.util.concurrent.atomic.AtomicInteger turnCounter, int maxTurns)
            throws GroupDiscussionException {

        // Cap batch size to remaining turn budget
        int remainingTurns = maxTurns > 0 ? Math.max(0, maxTurns - turnCounter.get()) : speakers.size();
        if (remainingTurns == 0) {
            return;
        }
        List<GroupMember> batchSpeakers = maxTurns > 0
                ? speakers.subList(0, Math.min(speakers.size(), remainingTurns))
                : speakers;

        // SAFETY: Snapshot the transcript so parallel tasks each see a consistent view.
        List<TranscriptEntry> snapshotTranscript = List.copyOf(gc.getTranscript());

        // Notify all speakers starting (parallel)
        if (listener != null) {
            for (GroupMember speaker : batchSpeakers) {
                listener.onSpeakerStart(
                        new GroupConversationEventSink.SpeakerStartEvent(speaker.agentId(), speaker.displayName(), phaseIdx, phase.name()));
            }
        }

        List<CompletableFuture<TranscriptEntry>> futures = batchSpeakers.stream().map(speaker -> CompletableFuture.supplyAsync(() -> {
            try {
                String input = buildPhaseInput(phase, speaker, question, snapshotTranscript, phaseIdx, null);
                return executeAgentTurn(speaker, gc, input, protocol, phaseIdx, phase, null);
            } catch (GroupDiscussionException e) {
                if (e.getCause() instanceof QuotaExceededException) {
                    throw new java.util.concurrent.CompletionException(e);
                }
                LOGGER.errorf("Parallel phase failed for %s: %s", speaker.agentId(), e.getMessage());
                return errorEntry(speaker, phaseIdx, phase, e.getMessage());
            } catch (Exception e) {
                LOGGER.errorf("Parallel phase failed for %s: %s", speaker.agentId(), e.getMessage());
                return errorEntry(speaker, phaseIdx, phase, e.getMessage());
            }
        }, executorService)).toList();

        int timeout = protocol.agentTimeoutSeconds() > 0 ? protocol.agentTimeoutSeconds() : 60;
        for (int i = 0; i < futures.size(); i++) {
            try {
                TranscriptEntry entry = futures.get(i).get(timeout, TimeUnit.SECONDS);
                gc.getTranscript().add(entry);
                if (listener != null) {
                    listener.onSpeakerComplete(new GroupConversationEventSink.SpeakerCompleteEvent(entry.speakerAgentId(), entry.speakerDisplayName(),
                            entry.content(), phaseIdx, phase.name()));
                }
            } catch (TimeoutException e) {
                futures.get(i).cancel(true);
                gc.getTranscript().add(new TranscriptEntry("unknown", "Unknown", null, phaseIdx, phase.name(), TranscriptEntryType.SKIPPED,
                        Instant.now(), "Timeout", null));
            } catch (ExecutionException e) {
                // Unwrap: CompletionException → GroupDiscussionException →
                // QuotaExceededException
                Throwable cause = e.getCause();
                if (cause instanceof java.util.concurrent.CompletionException ce) {
                    cause = ce.getCause();
                }
                if (cause instanceof GroupDiscussionException gde
                        && gde.getCause() instanceof QuotaExceededException) {
                    // Cancel remaining futures and propagate
                    for (int j = i + 1; j < futures.size(); j++) {
                        futures.get(j).cancel(true);
                    }
                    throw gde;
                }
                gc.getTranscript().add(errorEntry(null, phaseIdx, phase, e.getMessage()));
            } catch (Exception e) {
                gc.getTranscript().add(errorEntry(null, phaseIdx, phase, e.getMessage()));
            }
        }
        // Count all completed turns for this batch (parallel turns are atomic batches)
        turnCounter.addAndGet(batchSpeakers.size());
    }

    /**
     * Peer-targeted phase: each speaker addresses each OTHER speaker individually
     * (N×(N-1) turns). Used for CRITIQUE style.
     */
    private void executePeerTargetedPhase(GroupConversation gc, AgentGroupConfiguration config, List<GroupMember> speakers, DiscussionPhase phase,
                                          ProtocolConfig protocol, String question, int phaseIdx, GroupDiscussionEventListener listener,
                                          java.util.concurrent.atomic.AtomicInteger turnCounter, int maxTurns)
            throws GroupDiscussionException {

        // Collect all non-moderator members as targets
        List<GroupMember> allMembers = config.getMembers().stream()
                .sorted(Comparator.comparing(m -> m.speakingOrder() != null ? m.speakingOrder() : Integer.MAX_VALUE)).toList();

        outer : for (GroupMember speaker : speakers) {
            for (GroupMember target : allMembers) {
                if (speaker.agentId().equals(target.agentId())) {
                    continue; // Don't critique yourself
                }
                if (turnCounter.get() >= maxTurns) {
                    break outer;
                }
                turnCounter.incrementAndGet();
                if (listener != null) {
                    listener.onSpeakerStart(
                            new GroupConversationEventSink.SpeakerStartEvent(speaker.agentId(), speaker.displayName(), phaseIdx, phase.name()));
                }
                String input = buildPhaseInput(phase, speaker, question, gc.getTranscript(), phaseIdx, target);
                TranscriptEntry entry = executeAgentTurn(speaker, gc, input, protocol, phaseIdx, phase, target.agentId());
                gc.getTranscript().add(entry);
                if (listener != null) {
                    listener.onSpeakerComplete(new GroupConversationEventSink.SpeakerCompleteEvent(speaker.agentId(), speaker.displayName(),
                            entry.content(), phaseIdx, phase.name(), target.agentId(), target.displayName()));
                }
            }
        }
    }

    // =================================================================
    // Agent turn execution
    // =================================================================

    private TranscriptEntry executeAgentTurn(GroupMember member, GroupConversation gc, String input, ProtocolConfig protocol, int phaseIdx,
                                             DiscussionPhase phase, String targetAgentId)
            throws GroupDiscussionException {

        TranscriptEntryType entryType = mapPhaseToEntryType(phase.type());

        // --- GROUP member: delegate to a nested sub-group discussion ---
        if (member.memberType() == AgentGroupConfiguration.MemberType.GROUP) {
            return executeGroupMemberTurn(member, gc, input, protocol, phaseIdx, phase, entryType, targetAgentId);
        }

        // Check agent availability
        try {
            var agent = agentFactory.getLatestReadyAgent(DEFAULT_ENV, member.agentId());
            if (agent == null) {
                if (protocol.onMemberUnavailable() == ProtocolConfig.MemberUnavailablePolicy.FAIL) {
                    throw new GroupDiscussionException("Agent %s is not deployed and onMemberUnavailable=FAIL".formatted(member.agentId()));
                }
                return new TranscriptEntry(member.agentId(), member.displayName(), null, phaseIdx, phase.name(), TranscriptEntryType.SKIPPED,
                        Instant.now(), "Agent not deployed", targetAgentId);
            }
        } catch (GroupDiscussionException e) {
            throw e;
        } catch (Exception e) {
            if (protocol.onMemberUnavailable() == ProtocolConfig.MemberUnavailablePolicy.FAIL) {
                throw new GroupDiscussionException("Cannot reach agent %s: %s".formatted(member.agentId(), e.getMessage()), e);
            }
            return new TranscriptEntry(member.agentId(), member.displayName(), null, phaseIdx, phase.name(), TranscriptEntryType.SKIPPED,
                    Instant.now(), "Agent unavailable: " + e.getMessage(), targetAgentId);
        }

        // Get or create private conversation
        String privateConvId = gc.getMemberConversationIds().get(member.agentId());
        if (privateConvId == null) {
            try {
                Map<String, Context> groupContext = new LinkedHashMap<>();
                groupContext.put("groupId", new Context(Context.ContextType.string, gc.getGroupId()));
                groupContext.put("groupConversationId", new Context(Context.ContextType.string, gc.getId()));
                groupContext.put("groupDepth", new Context(Context.ContextType.string, String.valueOf(gc.getDepth())));
                var result = conversationService.startConversation(DEFAULT_ENV, member.agentId(), gc.getUserId(), groupContext);
                privateConvId = result.conversationId();
                gc.getMemberConversationIds().put(member.agentId(), privateConvId);
            } catch (QuotaExceededException qe) {
                throw new GroupDiscussionException("Tenant quota exceeded: " + qe.getMessage(), qe);
            } catch (Exception e) {
                return handleAgentFailure(member, phaseIdx, phase, protocol, e, "Failed to start conversation", targetAgentId);
            }
        }

        // Build InputData with context
        InputData inputData = new InputData();
        inputData.setInput(input);
        Map<String, Context> context = new LinkedHashMap<>();
        context.put("groupTranscript", new Context(Context.ContextType.object, gc.getTranscript()));
        context.put("groupId", new Context(Context.ContextType.string, gc.getGroupId()));
        context.put("groupConversationId", new Context(Context.ContextType.string, gc.getId()));
        context.put("groupDepth", new Context(Context.ContextType.string, String.valueOf(gc.getDepth())));

        // Pass group-level dynamic agent guardrails to member agents so that
        // AgentOrchestrator can enforce caps, allowed providers/models, etc.
        if (gc.getDynamicAgentConfig() != null) {
            context.put("dynamicAgentConfig", new Context(Context.ContextType.object, gc.getDynamicAgentConfig()));
        }

        // Wave 6: Peer verification — if the receiving agent requires it,
        // verify all signed entries from prior speakers before sending context
        verifyPriorEntriesIfRequired(member.agentId(), gc);

        inputData.setContext(context);

        // Call through ConversationService with retry
        int retries = 0;
        int maxRetries = protocol.maxRetries() > 0 ? protocol.maxRetries() : 2;
        int timeout = protocol.agentTimeoutSeconds() > 0 ? protocol.agentTimeoutSeconds() : 60;

        while (true) {
            try {
                CompletableFuture<String> responseFuture = new CompletableFuture<>();
                final String convId = privateConvId;

                conversationService.say(DEFAULT_ENV, member.agentId(), convId, true, true, null, inputData, false, snapshot -> {
                    String response = extractResponse(snapshot);
                    // When the agent pipeline fails (e.g. LLM unreachable), extractResponse
                    // returns empty because there are no output keys — only pipeline metadata.
                    // Surface the failure as explicit content so the transcript entry is not empty.
                    if ((response == null || response.isEmpty()) && snapshot != null
                            && snapshot.getConversationState() == ConversationState.ERROR) {
                        response = "[Agent failed to produce output — conversation entered ERROR state]";
                    }

                    // Propagate dynamic agent tracking data from the member's conversation
                    // memory to the GroupConversation for lifecycle cleanup.
                    propagateDynamicAgentTracking(snapshot, gc);

                    responseFuture.complete(response);
                });

                String response = responseFuture.get(timeout, TimeUnit.SECONDS);

                // Wave 6: Sign inter-agent messages with full envelope if configured
                String signature = null;
                String signatureNonce = null;
                Long signatureTimestampMs = null;
                Integer signatureKeyVersion = null;
                // Skip signing if crypto infrastructure is not injected
                if (agentStore != null && agentSigningService != null && nonceCacheService != null) {
                    try {
                        var resourceId = agentStore.getCurrentResourceId(member.agentId());
                        var agentConfig = agentStore.read(member.agentId(), resourceId.getVersion());
                        if (agentConfig.getSecurity() != null
                                && agentConfig.getSecurity().isSignInterAgentMessages()
                                && response != null) {
                            // Create SignedEnvelope with nonce for replay protection
                            var envelope = SignedEnvelope.forSigning(
                                    member.agentId(), gc.getGroupId(),
                                    Map.of("content", response, "phase", phase.name()));
                            int keyVersion = 0;
                            if (agentConfig.getIdentity() != null
                                    && agentConfig.getIdentity().getKeys() != null
                                    && !agentConfig.getIdentity().getKeys().isEmpty()) {
                                keyVersion = agentConfig.getIdentity().getKeys().stream()
                                        .mapToInt(AgentPublicKey::version)
                                        .max().orElse(0);
                            }
                            var signedEnvelope = agentSigningService.signEnvelope(
                                    defaultTenantId, member.agentId(), envelope, keyVersion);

                            // Immediate self-verification: sanity-check the signature.
                            // If this fails, the signature is broken — do NOT store it.
                            String publicKey = agentConfig.getIdentity() != null
                                    ? agentConfig.getIdentity()
                                            .getKeyValidAt(signedEnvelope.timestampMs())
                                    : null;
                            if (publicKey != null) {
                                boolean valid = agentSigningService.verifyEnvelope(
                                        signedEnvelope, publicKey);
                                if (!valid) {
                                    LOGGER.errorf("SELF-VERIFY FAILED for agent '%s' "
                                            + "— key mismatch or signing error. "
                                            + "Falling back to unsigned entry.",
                                            member.agentId());
                                    // Fall back to unsigned: do NOT store broken signature
                                    signedEnvelope = null;
                                }
                            }

                            // Nonce validation: register nonce to prevent replay.
                            // If validation fails (stale/skewed), discard the signature.
                            if (signedEnvelope != null) {
                                var nonceResult = nonceCacheService.validate(
                                        signedEnvelope.nonce(), signedEnvelope.timestampMs());
                                if (nonceResult != NonceCacheService.NonceValidation.VALID) {
                                    LOGGER.warnf("Nonce validation failed for agent '%s': %s "
                                            + "— falling back to unsigned entry",
                                            member.agentId(), nonceResult);
                                    signedEnvelope = null;
                                }
                            }

                            // Store full envelope data for peer verification
                            if (signedEnvelope != null) {
                                signature = signedEnvelope.signature();
                                signatureNonce = signedEnvelope.nonce();
                                signatureTimestampMs = signedEnvelope.timestampMs();
                                signatureKeyVersion = signedEnvelope.keyVersion();

                                LOGGER.debugf("Signed inter-agent envelope from '%s' "
                                        + "(nonce=%s, keyV=%d, sig=%s...)",
                                        member.agentId(), signatureNonce,
                                        signatureKeyVersion,
                                        signature.length() > 16
                                                ? signature.substring(0, 16)
                                                : signature);
                            }
                        }
                    } catch (Exception sigEx) {
                        LOGGER.warnf("Failed to sign message from agent '%s': %s",
                                member.agentId(), sigEx.getMessage());
                    }
                }

                var entry = new TranscriptEntry(
                        member.agentId(), member.displayName(), response,
                        phaseIdx, phase.name(), entryType, Instant.now(),
                        null, targetAgentId, signature,
                        signatureNonce, signatureTimestampMs, signatureKeyVersion);
                return entry;

            } catch (TimeoutException e) {
                if (protocol.onAgentFailure() == ProtocolConfig.MemberFailurePolicy.RETRY && retries < maxRetries) {
                    retries++;
                    LOGGER.warnf("Agent %s timed out (attempt %d/%d), retrying...", member.agentId(), retries, maxRetries);
                    continue;
                }
                if (protocol.onAgentFailure() == ProtocolConfig.MemberFailurePolicy.ABORT) {
                    throw new GroupDiscussionException("Agent %s timed out and onAgentFailure=ABORT".formatted(member.agentId()));
                }
                return new TranscriptEntry(member.agentId(), member.displayName(), null, phaseIdx, phase.name(), TranscriptEntryType.SKIPPED,
                        Instant.now(), "Timeout after " + timeout + "s", targetAgentId);

            } catch (Exception e) {
                Throwable cause = e instanceof ExecutionException ? e.getCause() : e;
                // Quota errors are non-retryable and affect all agents — abort immediately
                if (cause instanceof QuotaExceededException) {
                    throw new GroupDiscussionException("Tenant quota exceeded: " + cause.getMessage(), cause);
                }
                if (protocol.onAgentFailure() == ProtocolConfig.MemberFailurePolicy.RETRY && retries < maxRetries) {
                    retries++;
                    LOGGER.warnf("Agent %s failed (attempt %d/%d): %s", member.agentId(), retries, maxRetries, cause.getMessage());
                    continue;
                }
                return handleAgentFailure(member, phaseIdx, phase, protocol, cause, "Agent execution failed", targetAgentId);
            }
        }
    }

    // =================================================================
    // Phase-specific input construction
    // =================================================================

    private String buildPhaseInput(DiscussionPhase phase, GroupMember speaker, String question, List<TranscriptEntry> transcript, int phaseIdx,
                                   GroupMember target) {

        String template = phase.inputTemplate() != null ? phase.inputTemplate() : selectDefaultTemplate(phase, transcript, phaseIdx);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("question", question);
        data.put("displayName", speaker.displayName());
        data.put("phaseIndex", phaseIdx);
        data.put("phaseName", phase.name());

        // Phase-type specific variables
        switch (phase.type()) {
            case OPINION -> {
                List<Map<String, Object>> prev = filterByScope(transcript, phase.contextScope(), phaseIdx, speaker);
                data.put("previousResponses", prev);
            }
            case CRITIQUE -> {
                if (target != null) {
                    data.put("targetName", target.displayName());
                    String targetResponse = findLatestResponse(transcript, target.agentId());
                    data.put("targetResponse", targetResponse != null ? targetResponse : "(no response)");
                }
            }
            case REVISION -> {
                String originalResponse = findLatestResponse(transcript, speaker.agentId());
                data.put("originalResponse", originalResponse != null ? originalResponse : "(no response)");
                // Feedback addressed TO this speaker
                List<Map<String, Object>> feedback = transcript.stream()
                        .filter(e -> e.type() == TranscriptEntryType.CRITIQUE && speaker.agentId().equals(e.targetAgentId())).map(e -> {
                            Map<String, Object> fb = new LinkedHashMap<>();
                            fb.put("reviewer", e.speakerDisplayName());
                            fb.put("content", e.content());
                            return fb;
                        }).collect(Collectors.toList());
                data.put("feedbackReceived", feedback);
            }
            case CHALLENGE -> {
                List<Map<String, Object>> opinions = transcript.stream().filter(e -> e.type() == TranscriptEntryType.OPINION && e.content() != null)
                        .map(e -> {
                            Map<String, Object> o = new LinkedHashMap<>();
                            o.put("speaker", e.speakerDisplayName());
                            o.put("content", e.content());
                            return o;
                        }).collect(Collectors.toList());
                data.put("allOpinions", opinions);
            }
            case DEFENSE -> {
                String originalResponse = findLatestResponse(transcript, speaker.agentId());
                data.put("originalResponse", originalResponse != null ? originalResponse : "(no response)");
                List<Map<String, Object>> challenges = transcript.stream()
                        .filter(e -> e.type() == TranscriptEntryType.CHALLENGE && e.content() != null).map(e -> {
                            Map<String, Object> c = new LinkedHashMap<>();
                            c.put("speaker", e.speakerDisplayName());
                            c.put("content", e.content());
                            return c;
                        }).collect(Collectors.toList());
                data.put("challenges", challenges);
            }
            case ARGUE, REBUTTAL -> {
                String role = speaker.role();
                data.put("teamSide", "PRO".equalsIgnoreCase(role) ? "FOR" : "AGAINST");
                // Opposing arguments (filtered by different speaker, not role label)
                List<Map<String, Object>> opposing = transcript.stream()
                        .filter(e -> (e.type() == TranscriptEntryType.ARGUMENT || e.type() == TranscriptEntryType.REBUTTAL) && e.content() != null)
                        .filter(e -> !e.speakerAgentId().equals(speaker.agentId())).map(e -> {
                            Map<String, Object> a = new LinkedHashMap<>();
                            a.put("speaker", e.speakerDisplayName());
                            a.put("content", e.content());
                            return a;
                        }).collect(Collectors.toList());
                data.put("opposingArguments", opposing);
            }
            case SYNTHESIS -> {
                List<Map<String, Object>> fullTranscript = transcript.stream()
                        .filter(e -> e.content() != null && e.type() != TranscriptEntryType.ERROR && e.type() != TranscriptEntryType.SKIPPED
                                && e.type() != TranscriptEntryType.QUESTION)
                        .map(e -> {
                            Map<String, Object> t = new LinkedHashMap<>();
                            t.put("speaker", e.speakerDisplayName());
                            t.put("content", e.content());
                            t.put("phaseName", e.phaseName() != null ? e.phaseName() : "");
                            return t;
                        }).collect(Collectors.toList());
                data.put("transcript", fullTranscript);
                data.put("totalPhases", phaseIdx);
            }
            case PLAN -> {
                // Provide member list for planning template
                List<Map<String, Object>> memberList = new ArrayList<>();
                // Note: speaker list should be the full member list for planning
                data.put("members", memberList); // populated by caller via template data
            }
            case EXECUTE -> {
                // Task-specific context populated by executeTaskPhase
            }
            case VERIFY -> {
                // Completed tasks populated by executeTaskPhase
            }
            default -> {
                // All PhaseType values handled above; default required by checkstyle
            }
        }

        try {
            return templatingEngine.processTemplate(template, data, ITemplatingEngine.TemplateMode.TEXT);
        } catch (ITemplatingEngine.TemplateEngineException e) {
            LOGGER.warnf("Template processing failed for phase '%s', " + "using plain text: %s", phase.name(), e.getMessage());
            return buildPlainTextFallback(phase, speaker, question, transcript);
        }
    }

    private String selectDefaultTemplate(DiscussionPhase phase, List<TranscriptEntry> transcript, int phaseIdx) {
        if (phase.type() == PhaseType.OPINION) {
            // Use independent template if no context, or context template if
            // there are prior responses
            if (phase.contextScope() == ContextScope.NONE) {
                return DiscussionStylePresets.TEMPLATE_OPINION_INDEPENDENT;
            }
            if (phase.contextScope() == ContextScope.ANONYMOUS) {
                return DiscussionStylePresets.TEMPLATE_OPINION_ANONYMOUS;
            }
            return DiscussionStylePresets.TEMPLATE_OPINION_WITH_CONTEXT;
        }
        return DiscussionStylePresets.defaultTemplate(phase.type());
    }

    // =================================================================
    // Context filtering by scope
    // =================================================================

    private List<Map<String, Object>> filterByScope(List<TranscriptEntry> transcript, ContextScope scope, int currentPhaseIdx, GroupMember speaker) {
        if (scope == null || scope == ContextScope.NONE) {
            return List.of();
        }

        return transcript.stream().filter(e -> e.content() != null && e.type() != TranscriptEntryType.ERROR && e.type() != TranscriptEntryType.SKIPPED
                && e.type() != TranscriptEntryType.QUESTION).filter(e -> switch (scope) {
                    case FULL -> true;
                    case LAST_PHASE -> e.phaseIndex() >= currentPhaseIdx - 1;
                    case ANONYMOUS -> true; // Content included, attribution stripped
                    case OWN_FEEDBACK -> speaker.agentId().equals(e.targetAgentId());
                    case NONE -> false;
                    case TASK_ONLY, TASK_WITH_DEPS -> false; // Handled by task-specific logic
                }).map(e -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    if (scope == ContextScope.ANONYMOUS) {
                        entry.put("speaker", "Anonymous");
                    } else {
                        entry.put("speaker", e.speakerDisplayName());
                    }
                    entry.put("content", e.content());
                    entry.put("phaseName", e.phaseName() != null ? e.phaseName() : "");
                    return entry;
                }).collect(Collectors.toList());
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

    private String findLatestResponse(List<TranscriptEntry> transcript, String agentId) {
        return transcript.stream()
                .filter(e -> agentId.equals(e.speakerAgentId()) && e.content() != null && e.type() != TranscriptEntryType.ERROR
                        && e.type() != TranscriptEntryType.SKIPPED)
                .reduce((first, second) -> second) // last match
                .map(TranscriptEntry::content).orElse(null);
    }

    private TranscriptEntryType mapPhaseToEntryType(PhaseType type) {
        return switch (type) {
            case OPINION -> TranscriptEntryType.OPINION;
            case CRITIQUE -> TranscriptEntryType.CRITIQUE;
            case REVISION -> TranscriptEntryType.REVISION;
            case CHALLENGE -> TranscriptEntryType.CHALLENGE;
            case DEFENSE -> TranscriptEntryType.DEFENSE;
            case ARGUE -> TranscriptEntryType.ARGUMENT;
            case REBUTTAL -> TranscriptEntryType.REBUTTAL;
            case SYNTHESIS -> TranscriptEntryType.SYNTHESIS;
            case PLAN -> TranscriptEntryType.PLAN;
            case EXECUTE -> TranscriptEntryType.TASK_RESULT;
            case VERIFY -> TranscriptEntryType.VERIFICATION;
        };
    }

    /**
     * Executes a GROUP member's turn by running a nested sub-group discussion. The
     * sub-group's synthesized answer (or full transcript if no moderator) becomes
     * this member's response in the parent group.
     */
    private TranscriptEntry executeGroupMemberTurn(GroupMember member, GroupConversation gc, String input, ProtocolConfig protocol, int phaseIdx,
                                                   DiscussionPhase phase, TranscriptEntryType entryType, String targetAgentId)
            throws GroupDiscussionException {
        try {
            // member.agentId() is actually a groupId for GROUP members
            String subGroupId = member.agentId();
            int nextDepth = gc.getDepth() + 1;

            LOGGER.infof("Executing sub-group '%s' (depth %d) as member of parent group '%s'", subGroupId, nextDepth, gc.getGroupId());

            GroupConversation subConversation = discuss(subGroupId, input, gc.getUserId(), nextDepth);

            // Phase 5d: Nested group HITL guard — if the sub-group paused for
            // approval, don't extract a partial answer. Nested HITL is not
            // supported in v1; cancel the stranded sub-pause (releases its timeout
            // schedule and removes it from pending-approval listings) and return a
            // SKIPPED entry with explanation.
            if (subConversation.getState() == GroupConversationState.AWAITING_APPROVAL) {
                LOGGER.warnf("Sub-group '%s' is awaiting approval — nested HITL not supported in v1; cancelling sub-pause",
                        subGroupId);
                try {
                    cancelDiscussion(subConversation.getId(), ControlSignal.CANCEL_GRACEFUL);
                } catch (Exception cancelEx) {
                    // best-effort cleanup — still return the SKIPPED entry below
                    LOGGER.warnf("Failed to cancel stranded sub-group pause %s: %s",
                            subConversation.getId(), cancelEx.getMessage());
                }
                return new TranscriptEntry(member.agentId(), member.displayName(), null, phaseIdx, phase.name(),
                        TranscriptEntryType.SKIPPED, Instant.now(),
                        "Sub-group awaiting approval — nested HITL not supported in v1", targetAgentId);
            }

            // Extract the synthesized answer, or concatenate all responses
            String response = subConversation.getSynthesizedAnswer();
            if (response == null || response.isBlank()) {
                response = subConversation.getTranscript().stream().filter(e -> e.content() != null)
                        .map(e -> "%s: %s".formatted(e.speakerDisplayName(), e.content())).collect(Collectors.joining("\n\n"));
            }

            return new TranscriptEntry(member.agentId(), member.displayName(), response, phaseIdx, phase.name(), entryType, Instant.now(), null,
                    targetAgentId);

        } catch (GroupDepthExceededException e) {
            return new TranscriptEntry(member.agentId(), member.displayName(), null, phaseIdx, phase.name(), TranscriptEntryType.SKIPPED,
                    Instant.now(), "Sub-group depth exceeded: " + e.getMessage(), targetAgentId);
        } catch (Exception e) {
            return handleAgentFailure(member, phaseIdx, phase, protocol, e, "Sub-group discussion failed", targetAgentId);
        }
    }

    private TranscriptEntry handleAgentFailure(GroupMember member, int phaseIdx, DiscussionPhase phase, ProtocolConfig protocol, Throwable cause,
                                               String prefix, String targetAgentId)
            throws GroupDiscussionException {

        if (protocol.onAgentFailure() == ProtocolConfig.MemberFailurePolicy.ABORT) {
            throw new GroupDiscussionException(
                    "%s for agent %s and onAgentFailure=ABORT: %s".formatted(prefix, member.agentId(), cause.getMessage()));
        }
        return new TranscriptEntry(member.agentId(), member.displayName(), null, phaseIdx, phase.name(), TranscriptEntryType.SKIPPED, Instant.now(),
                prefix + ": " + cause.getMessage(), targetAgentId);
    }

    private TranscriptEntry errorEntry(GroupMember member, int phaseIdx, DiscussionPhase phase, String message) {
        String agentId = member != null ? member.agentId() : "unknown";
        String displayName = member != null ? member.displayName() : "Unknown";
        return new TranscriptEntry(agentId, displayName, null, phaseIdx, phase.name(), TranscriptEntryType.ERROR, Instant.now(), message, null);
    }

    private void failConversation(GroupConversation gc) {
        gc.setState(GroupConversationState.FAILED);
        gc.setLastModified(Instant.now());
        try {
            conversationStore.update(gc);
        } catch (Exception e) {
            LOGGER.warnf("Failed to update group conversation state to FAILED: %s", e.getMessage());
        }
        counterGroupFailure.increment();
    }

    /**
     * Reads dynamic agent tracking data from the member's conversation snapshot and
     * propagates it to the group conversation's tracking lists. This bridges the
     * gap between per-turn tool-local tracking lists and the group-level lifecycle
     * tracking in {@link GroupConversation}.
     */
    static void propagateDynamicAgentTracking(
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
     * Delegates to the shared {@link ConversationOutputExtractor} utility.
     */
    private String extractResponse(ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot snapshot) {
        String result = ConversationOutputExtractor.extractResponse(snapshot);
        // Convert null to empty string for backward compatibility with GCS callers
        // that check for empty-string (pipeline metadata-only snapshots still return
        // null).
        return result != null ? result : "";
    }

    private String buildPlainTextFallback(DiscussionPhase phase, GroupMember speaker, String question, List<TranscriptEntry> transcript) {
        var sb = new StringBuilder();
        sb.append("Discussion phase: ").append(phase.name()).append("\n\n");
        sb.append("Question: \"").append(question).append("\"\n\n");
        sb.append("As ").append(speaker.displayName());
        sb.append(", please contribute to this phase of the discussion.");
        return sb.toString();
    }

    /**
     * Verify signed transcript entries from prior speakers if the receiving agent
     * has {@code requirePeerVerification=true}.
     * <p>
     * For each signed entry with full envelope data, this method:
     * <ol>
     * <li>Reconstructs the
     * {@link ai.labs.eddi.configs.agents.crypto.SignedEnvelope} from stored
     * fields</li>
     * <li>Loads the speaker's public key from the agent config</li>
     * <li>Verifies the signature against the canonical envelope form</li>
     * </ol>
     * Invalid signatures are logged as security warnings. This is defense-in-depth:
     * the signing code already self-verifies at creation time, so failures here
     * indicate either key rotation issues or data corruption.
     *
     * @param receivingAgentId
     *            the agent about to receive the transcript
     * @param gc
     *            the group conversation containing the transcript
     */
    private void verifyPriorEntriesIfRequired(String receivingAgentId, GroupConversation gc) {
        // Skip if crypto infrastructure is not injected
        if (agentStore == null || agentSigningService == null) {
            return;
        }
        try {
            var resourceId = agentStore.getCurrentResourceId(receivingAgentId);
            if (resourceId == null) {
                return;
            }
            var receiverConfig = agentStore.read(receivingAgentId, resourceId.getVersion());
            if (receiverConfig.getSecurity() == null
                    || !receiverConfig.getSecurity().isRequirePeerVerification()) {
                return;
            }

            List<TranscriptEntry> transcript = gc.getTranscript();
            int totalEntries = transcript.size();

            // Incremental verification: only verify entries added since last check
            int startIdx = lastVerifiedIndex.getOrDefault(gc.getId(), 0);
            if (startIdx >= totalEntries) {
                return; // Nothing new to verify
            }

            LOGGER.debugf("Peer verification for agent '%s' — verifying entries %d..%d (of %d total)",
                    receivingAgentId, startIdx, totalEntries - 1, totalEntries);

            int verified = 0;
            int failed = 0;
            int unsigned = 0;

            // Cache public keys per speaker to avoid redundant agentStore reads
            Map<String, String> publicKeyCache = new HashMap<>();

            for (int i = startIdx; i < totalEntries; i++) {
                TranscriptEntry entry = transcript.get(i);
                // Skip non-agent entries (user questions, errors, etc.)
                if ("user".equals(entry.speakerAgentId()) || entry.content() == null) {
                    continue;
                }

                if (!entry.hasEnvelopeData()) {
                    unsigned++;
                    LOGGER.warnf("UNSIGNED entry from agent '%s' in group '%s' — "
                            + "peer verification required but entry has no envelope data",
                            entry.speakerAgentId(), LogSanitizer.sanitize(gc.getGroupId()));
                    continue;
                }

                // Reconstruct envelope for verification
                var envelope = new SignedEnvelope(
                        entry.speakerAgentId(), gc.getGroupId(),
                        Map.of("content", entry.content(), "phase", entry.phaseName()),
                        entry.signatureNonce(), entry.signatureTimestampMs(),
                        entry.signature(), entry.signatureKeyVersion());

                // Get speaker's public key (cached per speaker)
                try {
                    String publicKey = publicKeyCache.computeIfAbsent(entry.speakerAgentId(), agentId -> {
                        try {
                            var speakerResourceId = agentStore.getCurrentResourceId(agentId);
                            if (speakerResourceId == null) {
                                return null;
                            }
                            var speakerConfig = agentStore.read(agentId, speakerResourceId.getVersion());
                            return speakerConfig.getIdentity() != null
                                    ? speakerConfig.getIdentity()
                                            .getKeyValidAt(entry.signatureTimestampMs())
                                    : null;
                        } catch (Exception e) {
                            LOGGER.warnf("Error loading public key for agent '%s': %s",
                                    agentId, e.getMessage());
                            return null;
                        }
                    });

                    if (publicKey == null) {
                        LOGGER.warnf("No public key found for agent '%s' — cannot verify signature",
                                entry.speakerAgentId());
                        failed++;
                        continue;
                    }

                    boolean valid = agentSigningService.verifyEnvelope(envelope, publicKey);
                    if (valid) {
                        verified++;
                    } else {
                        failed++;
                        LOGGER.errorf("SIGNATURE VERIFICATION FAILED for entry from agent '%s' "
                                + "(nonce=%s, keyV=%d) — potential tampering or key rotation issue",
                                entry.speakerAgentId(), entry.signatureNonce(),
                                entry.signatureKeyVersion());
                    }
                } catch (Exception e) {
                    failed++;
                    LOGGER.warnf("Error verifying entry from agent '%s': %s",
                            entry.speakerAgentId(), e.getMessage());
                }
            }

            // Update the cursor for this conversation
            lastVerifiedIndex.put(gc.getId(), totalEntries);

            LOGGER.infof("Peer verification for agent '%s': %d verified, %d failed, %d unsigned (range %d..%d)",
                    receivingAgentId, verified, failed, unsigned, startIdx, totalEntries - 1);
        } catch (Exception e) {
            LOGGER.warnf("Peer verification check failed for agent '%s': %s",
                    receivingAgentId, e.getMessage());
        }
    }

    // =================================================================
    // HITL lifecycle — cancel & resume
    // =================================================================

    private final ConcurrentHashMap<String, DiscussionControlToken> activeTokens = new ConcurrentHashMap<>();

    @Override
    public boolean cancelDiscussion(String conversationId, ControlSignal mode)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {
        // MAJOR-3: Delete stale HITL timeout schedule
        deleteGroupHitlTimeoutSchedule(conversationId);

        var token = activeTokens.get(conversationId);
        if (token != null) {
            if (mode == ControlSignal.CANCEL_IMMEDIATE) {
                token.setSignal(ControlSignal.CANCEL_IMMEDIATE);
                token.cancelActiveFuture();
            } else {
                token.setSignal(ControlSignal.CANCEL_GRACEFUL);
            }
            return true; // in-flight leg signalled — it will persist CANCELLED
        }
        // Not actively running — update DB with a state-CAS (#9): a plain
        // read-modify-write would race a concurrent approve/resume and could
        // resurrect a terminal state.
        var gc = conversationStore.read(conversationId);
        var state = gc.getState();
        // Only cancel from non-terminal states — guard against
        // overwriting COMPLETED or FAILED after a race.
        if (state == GroupConversationState.COMPLETED
                || state == GroupConversationState.CANCELLED
                || state == GroupConversationState.FAILED) {
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
            LOGGER.infof("Cancel of group conversation %s lost a concurrent state race — not overwriting", conversationId);
            return false;
        }
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
                    retryOnReject = config.getHitlConfig().getOnTaskRejection() == AgentGroupConfiguration.HitlRejectionPolicy.RETRY;
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

        conversationStore.updateIfState(gc, GroupConversationState.AWAITING_APPROVAL);
        // Delete timeout schedule only after CAS succeeds (Phase 5e) — if CAS
        // fails, the schedule is preserved so the timeout can still fire.
        deleteGroupHitlTimeoutSchedule(groupConversationId);
        counterGroupHitlResume.increment();
        auditHitlDecision(gc, decision);

        // Resume execution in background thread
        var groupId = gc.getGroupId();
        var question = gc.getOriginalQuestion();
        // BLOCKER fix: TASK pauses mid-phase → re-enter at same phase (idempotent).
        // PHASE pauses after phase completes → resume at +1.
        int startFromPhase = (pauseType == GroupConversation.HitlPauseType.TASK)
                ? resumePhaseIndex
                : resumePhaseIndex + 1;

        // Saved bookmark fields for pause restoration on transient failures
        final Instant savedPausedAt = Instant.now();
        final int savedPhaseIndex = resumePhaseIndex;
        final String savedPhaseName = pausedPhaseName;
        final var savedPauseType = pauseType;

        // Register the control token BEFORE submitting: a cancel landing between
        // the CAS above and the executor thread reaching executeDiscussion must
        // find a signalable token, not fall through to the DB branch and be
        // overwritten by the resumed leg's unconditional updates.
        activeTokens.put(gc.getId(), new DiscussionControlToken());

        Runnable resumeWork = () -> {
            AgentGroupConfiguration groupConfig;
            List<DiscussionPhase> phases;
            try {
                IResourceStore.IResourceId currentGroupId = groupStore.getCurrentResourceId(groupId);
                if (currentGroupId == null) {
                    throw new IResourceStore.ResourceNotFoundException("Group not found: " + groupId);
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
                        restoreGroupPause(gc, savedPhaseIndex, savedPhaseName, savedPauseType, savedPausedAt, groupConfig);
                        // A cancel signalled in this window must win over the restore
                        convertPauseToCancelIfSignalled(gc, listener);
                        activeTokens.remove(gc.getId());
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
                restoreGroupPause(gc, savedPhaseIndex, savedPhaseName, savedPauseType, savedPausedAt, null);
                // A cancel signalled in this window must win over the restore
                convertPauseToCancelIfSignalled(gc, listener);
                activeTokens.remove(gc.getId());
                if (listener != null) {
                    listener.onGroupError(new GroupConversationEventSink.GroupErrorEvent(
                            "Resume failed: " + e.getMessage() + " — the discussion remains awaiting approval; retry"));
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
            executorService.submit(resumeWork);
        } catch (RuntimeException e) {
            // Executor saturated/shut down — no thread will run the resume. The CAS
            // above already consumed the pause; restore it so the approval remains
            // actionable instead of leaving an IN_PROGRESS zombie.
            activeTokens.remove(gc.getId());
            restoreGroupPause(gc, savedPhaseIndex, savedPhaseName, savedPauseType, savedPausedAt, null);
            throw new IResourceStore.ResourceStoreException(
                    "Failed to submit resumed group discussion: " + e.getMessage(), e);
        }

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
                                   AgentGroupConfiguration configOrNull) {
        try {
            gc.setState(GroupConversationState.AWAITING_APPROVAL);
            gc.setPausedAt(pausedAt);
            gc.setPausedAtPhaseIndex(phaseIndex);
            gc.setPausedPhaseName(phaseName);
            gc.setHitlPauseType(pauseType != null ? pauseType : GroupConversation.HitlPauseType.PHASE);
            gc.setHitlPauseReason("Pause restored after failed resume");
            var config = configOrNull;
            if (config == null) {
                try {
                    var resId = groupStore.getCurrentResourceId(gc.getGroupId());
                    config = resId != null ? groupStore.read(gc.getGroupId(), resId.getVersion()) : null;
                } catch (Exception ignored) {
                    // bookmark policy fields stay as previously persisted
                }
            }
            if (config != null && config.getHitlConfig() != null) {
                gc.setHitlTimeoutPolicy(config.getHitlConfig().getTimeoutPolicy() != null
                        ? config.getHitlConfig().getTimeoutPolicy().name()
                        : AgentGroupConfiguration.HitlTimeoutPolicy.WAIT_INDEFINITELY.name());
                gc.setHitlApprovalTimeout(config.getHitlConfig().getApprovalTimeout());
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
     * without this, ephemeral dynamic agents stay deployed and lastVerifiedIndex
     * entries leak forever on every paused-then-terminal path.
     */
    private void cleanupAfterTerminalState(GroupConversation gc) {
        lastVerifiedIndex.remove(gc.getId());
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
            int deleted = scheduleStore.deleteSchedulesByName("hitl-timeout-group-" + groupConversationId);
            if (deleted > 0) {
                LOGGER.infof("Cleaned up %d group HITL timeout schedule(s) for %s", deleted, groupConversationId);
            }
        } catch (Exception e) {
            LOGGER.warnf("Failed to delete group HITL timeout schedule for %s: %s",
                    groupConversationId, e.getMessage());
        }
    }
}
