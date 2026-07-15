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
import ai.labs.eddi.engine.memory.ConversationOutputExtractor;
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

    // Field-injected so the direct-construction unit tests stay unchanged; used to
    // materialize and share discussion attachments with member conversations.
    @Inject
    IAttachmentStore attachmentStore;

    // Incremental peer verification: tracks the last verified transcript index
    // per group conversation ID, so we only verify new entries each turn (O(N)
    // amortized instead of O(N²)). Cleaned up when conversations complete.
    private final ConcurrentHashMap<String, Integer> lastVerifiedIndex = new ConcurrentHashMap<>();

    // Guards against concurrent post-discussion operations (follow-up / continue /
    // close) on the same conversation within this node. Fail-fast: a second
    // operation on the same gcId is rejected rather than queued. NOTE: single-node
    // only — cluster-wide safety would require an atomic conditional update at the
    // storage layer (compareAndSetState is a best-effort read-check-update).
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
        this.counterGroupMemberPauseSkipped = meterRegistry.counter("eddi_group_member_pause_skipped_count");
        this.counterGroupFollowUp = meterRegistry.counter("eddi_group_followup_count");
        this.counterGroupContinue = meterRegistry.counter("eddi_group_continue_count");
        this.counterGroupClose = meterRegistry.counter("eddi_group_close_count");
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
        try {
            executorService.submit(() -> {
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
     * Materialize discussion attachments and bind them to the group conversation.
     * Inline base64 files are stored in the blob store owned by {@code gc.getId()}
     * (so they can be granted to members and reaped with the conversation); hosted
     * {@code url} references and pre-stored {@code storageRef}s pass through. The
     * resulting list is stashed on the {@link GroupConversation} for fan-out.
     */
    void materializeAttachments(GroupConversation gc, List<Attachment> incoming) {
        if (incoming == null || incoming.isEmpty()) {
            return;
        }
        List<Attachment> materialized = new ArrayList<>();
        for (Attachment a : incoming) {
            try {
                if (a.getUrl() != null && !a.getUrl().isBlank()) {
                    // Hosted URL — forwarded as-is; no blob store required.
                    materialized.add(a);
                } else if (a.getBase64Data() != null && !a.getBase64Data().isBlank()) {
                    if (attachmentStore == null) {
                        LOGGER.warn("Inline group attachment provided but no attachment store is configured; skipping it.");
                        continue;
                    }
                    byte[] bytes = Base64.getDecoder().decode(a.getBase64Data());
                    var stored = attachmentStore.store(bytes, a.getMimeType(), a.getFileName(), gc.getId(), defaultTenantId);
                    materialized.add(new Attachment(stored.mimeType(), stored.filename(), stored.sizeBytes(), stored.storageRef()));
                } else if (a.getStorageRef() != null && !a.getStorageRef().isBlank()) {
                    materialized.add(a);
                }
            } catch (Exception e) {
                LOGGER.warnf("Failed to materialize group attachment '%s': %s", a.getFileName(), e.getMessage());
            }
        }
        if (!materialized.isEmpty()) {
            gc.setAttachments(materialized);
            LOGGER.infof("Group conversation '%s' has %d shared attachment(s)", gc.getId(), materialized.size());
        }
    }

    /**
     * Re-hydrate a group conversation's shared attachments from the durable blob
     * store. {@link GroupConversation#getAttachments()} is {@code @JsonIgnore}
     * transient, so a GC reloaded on a HITL resume has lost them; without this, a
     * member whose first turn lands after the resume gets neither the blob grant
     * nor the {@code attachment_*} context from {@link #grantAndInjectAttachments}.
     * <p>
     * No-op when attachments are already present (fresh discussion — set by
     * {@link #materializeAttachments}) or when the store holds none. URL-only
     * attachments are not blob-backed and are intentionally not recovered here.
     */
    void rehydrateAttachmentsFromStore(GroupConversation gc) {
        if (attachmentStore == null || gc.getAttachments() != null && !gc.getAttachments().isEmpty()) {
            return;
        }
        try {
            var storedAttachments = attachmentStore.listByConversation(gc.getId());
            if (storedAttachments.isEmpty()) {
                return;
            }
            List<Attachment> rehydrated = new ArrayList<>();
            for (var stored : storedAttachments) {
                rehydrated.add(new Attachment(stored.mimeType(), stored.filename(),
                        stored.sizeBytes(), stored.storageRef()));
            }
            gc.setAttachments(rehydrated);
            LOGGER.infof("Re-hydrated %d shared attachment(s) for group conversation '%s' from the blob store",
                    rehydrated.size(), gc.getId());
        } catch (Exception e) {
            LOGGER.warnf("Failed to re-hydrate shared attachments for group conversation '%s': %s",
                    gc.getId(), e.getMessage());
        }
    }

    /**
     * Grant a member conversation access to the group's stored attachments and
     * inject them as {@code attachment_*} context on the member's first turn. URL
     * references are forwarded as-is (no grant needed).
     */
    void grantAndInjectAttachments(GroupConversation gc, String memberConvId, Map<String, Context> context) {
        List<Attachment> atts = gc.getAttachments();
        if (atts == null || atts.isEmpty()) {
            return;
        }
        int index = 0;
        for (Attachment a : atts) {
            Map<String, Object> value = new LinkedHashMap<>();
            if (a.getStorageRef() != null) {
                if (attachmentStore != null) {
                    try {
                        attachmentStore.grantAccess(a.getStorageRef(), memberConvId);
                    } catch (Exception e) {
                        LOGGER.warnf("Failed to grant attachment '%s' to member conversation '%s': %s",
                                a.getStorageRef(), memberConvId, e.getMessage());
                        continue;
                    }
                }
                value.put("storageRef", a.getStorageRef());
                if (a.getFileName() != null) {
                    value.put("fileName", a.getFileName());
                }
            } else if (a.getUrl() != null) {
                value.put("mimeType", a.getMimeType());
                value.put("url", a.getUrl());
                if (a.getFileName() != null) {
                    value.put("fileName", a.getFileName());
                }
            } else {
                continue;
            }
            context.put("attachment_" + index, new Context(Context.ContextType.object, value));
            index++;
        }
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
                        executePeerTargetedPhase(gc, config, speakers, phase, protocol, question, phaseIdx, listener, turnCounter, maxTurns);
                    } else if (phase.turnOrder() == TurnOrder.PARALLEL) {
                        executeParallelPhase(gc, config, speakers, phase, protocol, question, phaseIdx, listener, turnCounter, maxTurns);
                    } else {
                        executeSequentialPhase(gc, config, speakers, phase, protocol, question, phaseIdx, listener, turnCounter, maxTurns);
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
                lastVerifiedIndex.remove(gc.getId());
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
            // agents stay deployed forever, and the lastVerifiedIndex entry leaks.
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
                context.put("groupTranscript", new Context(Context.ContextType.object, gc.getTranscript()));
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

            TranscriptEntry planEntry = executeAgentTurn(planner, gc, planInput, protocol, phaseIdx, phase, null, listener);
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
                && config.getHitlConfig().getGranularity() == HitlGranularity.TASK;

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
            if (token != null && token.isCancelled()) {
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
                            TranscriptEntry entry = executeAgentTurn(member, gc, taskInput, protocol, phaseIdx, phase, null, listener);

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
                // NEW-3: Register the blocking future so IMMEDIATE cancel can interrupt.
                // Re-check the signal AFTER registering: a CANCEL_IMMEDIATE that landed
                // while this future was being built cancelled only the previous handle,
                // so cancel it here too — otherwise the wave blocks in get() until the
                // timeout despite the cancel already having been requested.
                if (token != null) {
                    token.setActiveFuture(allOf);
                    if (token.isCancelled()) {
                        allOf.cancel(true);
                    }
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

        TranscriptEntry verifyEntry = executeAgentTurn(verifier, gc, verifyInput, protocol, phaseIdx, phase, null, listener);
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
            TranscriptEntry entry = executeAgentTurn(speaker, gc, input, protocol, phaseIdx, phase, null, listener);
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
                return executeAgentTurn(speaker, gc, input, protocol, phaseIdx, phase, null, listener);
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
                TranscriptEntry entry = executeAgentTurn(speaker, gc, input, protocol, phaseIdx, phase, target.agentId(), listener);
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
                                             DiscussionPhase phase, String targetAgentId, GroupDiscussionEventListener listener)
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
        boolean firstMemberTurn = privateConvId == null;
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

        // Share discussion attachments with this member on its first turn: grant the
        // member conversation access to group-owned blobs and inject attachment_*.
        // Later phases rely on extraction-in-history and the readAttachment tool.
        if (firstMemberTurn) {
            grantAndInjectAttachments(gc, privateConvId, context);
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
                // #3: a member agent's own behavior rule may emit PAUSE_CONVERSATION,
                // pausing its private conversation (AWAITING_HUMAN). Member-level HITL
                // is not supported inside a group — flag it here and resolve it after
                // the say callback returns, rather than recording an empty contribution.
                final boolean[] memberPaused = {false};
                // Task 13: capture the paused snapshot so we can branch on its HITL
                // pause type after the callback returns — a TOOL_CALL pause is
                // auto-resolved gracefully (system:group REJECTED), a RULE pause needs
                // a real human and stays SKIP+cancel.
                final ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot[] pausedSnapshot = {null};

                conversationService.say(DEFAULT_ENV, member.agentId(), convId, true, true, null, inputData, false, snapshot -> {
                    if (snapshot != null && snapshot.getConversationState() == ConversationState.AWAITING_HUMAN) {
                        memberPaused[0] = true;
                        pausedSnapshot[0] = snapshot;
                    }

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

                // #3 / Task 13: member requested human approval mid-turn. A TOOL_CALL
                // pause is auto-resolved gracefully — the group rejects the gated
                // tool(s) (system:group REJECTED) via the NORMAL resume path so the
                // member's LLM receives rejection tool-results and produces a coherent
                // tool-less answer that becomes its contribution. Only if that resume
                // cannot complete (times out / re-pauses) do we fall back to the
                // RULE-pause behavior: cancel the stranded pause + record SKIPPED.
                if (memberPaused[0]) {
                    if (pausedSnapshot[0] != null && "TOOL_CALL".equals(pausedSnapshot[0].getHitlPauseType())) {
                        TranscriptEntry graceful = tryResolveMemberToolPause(
                                member, gc, convId, input, timeout, phaseIdx, phase, entryType, targetAgentId);
                        if (graceful != null) {
                            return graceful;
                        }
                    }
                    // RULE pause (needs a real human) or a TOOL_CALL graceful attempt
                    // that could not complete → existing SKIP + cancel handling.
                    return handleMemberPause(member, gc, convId, phaseIdx, phase, targetAgentId, listener);
                }

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
                    // A member-agent timeout — surface as GroupTimeoutException so it maps
                    // to 504 at REST (executeDiscussion's re-wrap preserves the subtype).
                    throw new GroupTimeoutException(
                            "Agent %s timed out and onAgentFailure=ABORT".formatted(member.agentId()), null);
                }
                return new TranscriptEntry(member.agentId(), member.displayName(), null, phaseIdx, phase.name(), TranscriptEntryType.SKIPPED,
                        Instant.now(), "Timeout after " + timeout + "s", targetAgentId);

            } catch (IConversationService.ConversationAwaitingApprovalException e) {
                // Member's private conversation is (or became) AWAITING_HUMAN before the say
                // callback ran — member-level HITL is unsupported inside a group. Cancel the
                // stranded pause and record SKIPPED (mirrors the memberPaused[0] path).
                // convId is try-scoped, so pass the method-level privateConvId (same value).
                return handleMemberPause(member, gc, privateConvId, phaseIdx, phase, targetAgentId, listener);
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

    /**
     * Note recorded on the member's tool-less contribution when a group
     * auto-rejects its gated tool call(s). Kept in one place so the transcript
     * entry and docs stay consistent.
     */
    static final String MEMBER_TOOL_REJECTION_NOTE = "tool approval is not available during group discussions in this version";

    /**
     * Task 13 — graceful resolution of a member TOOL_CALL pause inside a group. The
     * group has no human reviewer, so it auto-rejects the gated tool call(s) with a
     * {@code system:group} REJECTED decision routed through the NORMAL resume path:
     * the member's LLM receives rejection tool-results (Task 9) and produces a
     * coherent tool-less answer, which becomes this turn's contribution.
     * <p>
     * The resume is driven synchronously within the member-turn budget. If it
     * cannot complete in time, or the member re-pauses (its resumed snapshot is
     * still {@code AWAITING_HUMAN}), this returns {@code null} — the caller then
     * falls back to the existing member-pause handling (SKIP + cancel). A resume
     * infrastructure failure likewise returns {@code null} so the fallback still
     * terminates the turn cleanly.
     *
     * @return a real contribution entry on graceful success, or {@code null} to
     *         signal "fall back to SKIP + cancel"
     */
    private TranscriptEntry tryResolveMemberToolPause(GroupMember member, GroupConversation gc, String convId,
                                                      String input, int timeoutSeconds, int phaseIdx,
                                                      DiscussionPhase phase, TranscriptEntryType entryType,
                                                      String targetAgentId) {
        LOGGER.infof("Member agent '%s' TOOL_CALL-paused during group discussion %s (phase %d) — "
                + "auto-rejecting the gated tool call(s) (system:group) and resuming for a tool-less answer",
                member.agentId(), gc.getId(), phaseIdx);

        var decision = new ai.labs.eddi.engine.lifecycle.model.HitlDecision();
        decision.setVerdict(ai.labs.eddi.engine.lifecycle.model.HitlDecision.HitlVerdict.REJECTED);
        decision.setDecidedBy("system:group");
        decision.setNote(MEMBER_TOOL_REJECTION_NOTE);

        var resumeFuture = new CompletableFuture<ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot>();
        try {
            conversationService.resumeConversation(convId, decision,
                    new IConversationService.ConversationResponseHandler() {
                        @Override
                        public void onComplete(ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot snapshot) {
                            resumeFuture.complete(snapshot);
                        }

                        @Override
                        public void onSkipped(ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot snapshot) {
                            // Dropped without producing a fresh answer — treat as
                            // "could not complete" so the caller falls back.
                            resumeFuture.complete(null);
                        }
                    });
        } catch (Exception e) {
            LOGGER.warnf("Graceful tool-pause resume failed to start for member '%s' (conv %s): %s — falling back to SKIP+cancel",
                    member.agentId(), convId, e.getMessage());
            return null;
        }

        ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot resumed;
        try {
            resumed = resumeFuture.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            LOGGER.warnf("Graceful tool-pause resume did not complete within %ds for member '%s' (conv %s) — falling back to SKIP+cancel",
                    timeoutSeconds, member.agentId(), convId);
            return null;
        } catch (Exception e) {
            LOGGER.warnf("Graceful tool-pause resume errored for member '%s' (conv %s): %s — falling back to SKIP+cancel",
                    member.agentId(), convId, e instanceof ExecutionException && e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
            return null;
        }

        // Re-paused (still awaiting a human) → the graceful attempt did not resolve.
        if (resumed == null || resumed.getConversationState() == ConversationState.AWAITING_HUMAN) {
            LOGGER.warnf("Member '%s' re-paused after graceful tool rejection (conv %s) — falling back to SKIP+cancel",
                    member.agentId(), convId);
            return null;
        }

        propagateDynamicAgentTracking(resumed, gc);

        String response = extractResponse(resumed);
        if ((response == null || response.isEmpty())
                && resumed.getConversationState() == ConversationState.ERROR) {
            response = "[Agent failed to produce output — conversation entered ERROR state]";
        }

        LOGGER.infof("Member '%s' produced a tool-less contribution after group auto-rejection (conv %s)",
                member.agentId(), convId);
        return new TranscriptEntry(member.agentId(), member.displayName(), response,
                phaseIdx, phase.name(), entryType, Instant.now(),
                null, targetAgentId);
    }

    /**
     * Explanatory note for a member turn skipped because the member agent's own
     * conversation requested human approval — kept in one place so the transcript
     * entry, the SSE event, and docs stay consistent.
     */
    private static final String MEMBER_PAUSE_NOTE = "member agent requested human approval (PAUSE_CONVERSATION) — not supported inside group "
            + "discussions; configure group-level HITL via requiresApproval instead";

    /**
     * Resolves a member turn whose private conversation paused for human approval
     * (#3). Member-level HITL is unsupported inside a group in v1: leaving the
     * pause armed strands an approval no human can meaningfully resolve, and
     * recording the empty snapshot as a real contribution poisons later phases. So
     * we cancel the member's pause (disarming its timeout schedule and removing it
     * from the regular pending-approvals surface), count a metric, notify
     * observers, and return a SKIPPED entry with an actionable note.
     */
    private TranscriptEntry handleMemberPause(GroupMember member, GroupConversation gc, String convId,
                                              int phaseIdx, DiscussionPhase phase, String targetAgentId,
                                              GroupDiscussionEventListener listener) {
        LOGGER.warnf("Member agent '%s' paused for human approval during group discussion %s (phase %d) — "
                + "member-level HITL is unsupported inside a group; skipping the turn and cancelling the pause",
                member.agentId(), gc.getId(), phaseIdx);
        try {
            conversationService.cancelConversation(convId,
                    ai.labs.eddi.engine.lifecycle.model.ControlSignal.CANCEL_GRACEFUL, "system:group");
        } catch (Exception e) {
            // Best-effort — still record SKIPPED so the discussion terminates cleanly.
            LOGGER.warnf("Failed to cancel stranded member pause %s: %s", convId, e.getMessage());
        }
        counterGroupMemberPauseSkipped.increment();
        if (listener != null) {
            listener.onMemberPauseSkipped(new GroupConversationEventSink.MemberPauseSkippedEvent(
                    member.agentId(), member.displayName(), phaseIdx, phase.name(), MEMBER_PAUSE_NOTE));
        }
        return new TranscriptEntry(member.agentId(), member.displayName(), null, phaseIdx, phase.name(),
                TranscriptEntryType.SKIPPED, Instant.now(), MEMBER_PAUSE_NOTE, targetAgentId);
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

            // Propagate the parent's attachments to the nested group so its members
            // receive them too (each nested member conversation is granted in turn).
            GroupConversation subConversation = discuss(subGroupId, input, gc.getUserId(), nextDepth, null, gc.getAttachments());

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
            executorService.submit(resumeWork);
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
