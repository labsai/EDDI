/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.agents.AgentSigningService;
import ai.labs.eddi.engine.tenancy.QuotaExceededException;
import ai.labs.eddi.configs.agents.IAgentStore;
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
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.modules.output.model.OutputItem;
import ai.labs.eddi.engine.model.InputData;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import ai.labs.eddi.engine.lifecycle.GroupConversationEventSink;
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
    private final NonceCacheService nonceCacheService;
    private final String defaultTenantId;

    // Incremental peer verification: tracks the last verified transcript index
    // per group conversation ID, so we only verify new entries each turn (O(N)
    // amortized instead of O(N²)). Cleaned up when conversations complete.
    private final ConcurrentHashMap<String, Integer> lastVerifiedIndex = new ConcurrentHashMap<>();

    // Metrics
    private final Timer timerGroupDiscussion;
    private final Counter counterGroupDiscussion;
    private final Counter counterGroupFailure;

    @Inject
    public GroupConversationService(IAgentGroupStore groupStore, IGroupConversationStore conversationStore, IConversationService conversationService,
            IAgentFactory agentFactory, ITemplatingEngine templatingEngine, IJsonSerialization jsonSerialization, MeterRegistry meterRegistry,
            AgentSigningService agentSigningService, IAgentStore agentStore,
            NonceCacheService nonceCacheService,
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
        this.nonceCacheService = nonceCacheService;
        this.defaultTenantId = defaultTenantId;
        // Virtual threads — lightweight, no pool sizing, ideal for parallel agent calls
        this.executorService = Executors.newVirtualThreadPerTaskExecutor();

        this.timerGroupDiscussion = meterRegistry.timer("eddi_group_discussion_duration");
        this.counterGroupDiscussion = meterRegistry.counter("eddi_group_discussion_count");
        this.counterGroupFailure = meterRegistry.counter("eddi_group_discussion_failure_count");
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
        return executeDiscussion(gc, config, phases, question, listener);
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

        // Run the discussion in a virtual thread — reuse the same gc (no duplicate
        // creation)
        executorService.submit(() -> {
            try {
                executeDiscussion(gc, config, phases, question, listener);
            } catch (Exception e) {
                LOGGER.errorf("Async group discussion failed for %s: %s", groupId, e.getMessage());
                if (listener != null) {
                    listener.onGroupError(new GroupConversationEventSink.GroupErrorEvent(e.getMessage()));
                }
            }
        });

        return gc;
    }

    /**
     * Core discussion execution loop. Shared by both synchronous {@link #discuss}
     * and asynchronous {@link #startAndDiscussAsync} to avoid duplicate
     * conversation creation. Also fixes C2: emits phase_start before
     * synthesis_start for correct semantic ordering.
     */
    private GroupConversation executeDiscussion(GroupConversation gc, AgentGroupConfiguration config, List<DiscussionPhase> phases, String question,
                                                GroupDiscussionEventListener listener)
            throws GroupDiscussionException, IResourceStore.ResourceStoreException {

        long startTime = System.nanoTime();
        counterGroupDiscussion.increment();

        ProtocolConfig protocol = resolveProtocol(config);
        int maxTurns = protocol.maxTurns() > 0 ? protocol.maxTurns() : 50;

        // AtomicInteger: shared across the phase loop; parallel phases increment
        // from virtual threads. Mutable counter avoids passing & returning counts.
        var turnCounter = new java.util.concurrent.atomic.AtomicInteger(0);

        if (listener != null) {
            listener.onGroupStart(new GroupConversationEventSink.GroupStartEvent(gc.getId(), gc.getGroupId(), question,
                    config.getStyle() != null ? config.getStyle().name() : "ROUND_TABLE", phases.size(),
                    config.getMembers().stream().map(GroupMember::agentId).toList()));
        }

        try {
            // Execute each phase
            for (int phaseIdx = 0; phaseIdx < phases.size(); phaseIdx++) {
                DiscussionPhase phase = phases.get(phaseIdx);

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

                // Check again after inner repeat loop in case maxTurns was hit mid-repeat
                if (turnCounter.get() >= maxTurns) {
                    break;
                }
            }

            // Extract synthesis from the last SYNTHESIS phase entry
            gc.getTranscript().stream().filter(e -> e.type() == TranscriptEntryType.SYNTHESIS && e.content() != null)
                    .reduce((first, second) -> second) // last one
                    .ifPresent(e -> gc.setSynthesizedAnswer(e.content()));

            gc.setState(GroupConversationState.COMPLETED);
            gc.setLastModified(Instant.now());
            conversationStore.update(gc);

            if (listener != null) {
                listener.onGroupComplete(new GroupConversationEventSink.GroupCompleteEvent(gc.getState(), gc.getSynthesizedAnswer()));
            }

            return gc;

        } catch (GroupDiscussionException e) {
            failConversation(gc);
            if (listener != null) {
                listener.onGroupError(new GroupConversationEventSink.GroupErrorEvent(e.getMessage()));
            }
            throw e;
        } catch (Exception e) {
            failConversation(gc);
            if (listener != null) {
                listener.onGroupError(new GroupConversationEventSink.GroupErrorEvent(e.getMessage()));
            }
            throw new GroupDiscussionException("Group discussion failed: " + e.getMessage(), e);
        } finally {
            timerGroupDiscussion.record(System.nanoTime() - startTime, TimeUnit.NANOSECONDS);
            // Clean up incremental verification cursor — conversation is done
            lastVerifiedIndex.remove(gc.getId());
            // Ephemeral agent cleanup — undeploy/delete agents created during this
            // discussion
            cleanupEphemeralAgents(gc, config);
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

                            gc.getTaskList().completeTask(task.id(), entry.content());

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
                CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                        .get(timeout * (long) maxTasksPerAgent, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                LOGGER.warnf("Task execution timed out for group %s (wave %d)",
                        LogSanitizer.sanitize(gc.getGroupId()), wave + 1);
                futures.forEach(f -> f.cancel(true));
                break;
            } catch (ExecutionException | InterruptedException e) {
                LOGGER.warnf("Task execution error for group %s: %s",
                        LogSanitizer.sanitize(gc.getGroupId()), LogSanitizer.sanitize(e.getMessage()));
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
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

        try {
            return templatingEngine.processTemplate(template, data, ITemplatingEngine.TemplateMode.TEXT);
        } catch (ITemplatingEngine.TemplateEngineException e) {
            LOGGER.warnf("Template processing failed for task execution, using plain text: %s", e.getMessage());
            return "Task: " + task.subject() + "\n" + task.description();
        }
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

                conversationService.say(DEFAULT_ENV, member.agentId(), convId, false, true, null, inputData, false, snapshot -> {
                    String response = extractResponse(snapshot);
                    // When the agent pipeline fails (e.g. LLM unreachable), extractResponse
                    // returns null because there are no output keys — only pipeline metadata.
                    // Surface the failure as explicit content so the transcript entry is not empty.
                    if (response == null && snapshot != null
                            && snapshot.getConversationState() == ConversationState.ERROR) {
                        response = "[Agent failed to produce output — conversation entered ERROR state]";
                    }
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
     * Extracts the human-readable text from a conversation memory snapshot. Looks
     * for the {@code output} array in the last ConversationOutput map and
     * concatenates all text entries (same logic as the Manager's
     * {@code extractOutput()}).
     */
    private String extractResponse(ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot snapshot) {
        if (snapshot == null || snapshot.getConversationOutputs() == null) {
            return "";
        }
        var outputs = snapshot.getConversationOutputs();
        if (outputs.isEmpty()) {
            return "";
        }
        var lastOutput = outputs.get(outputs.size() - 1);
        if (lastOutput == null) {
            return "";
        }

        var texts = new ArrayList<String>();

        // Format 1: Nested "output" array — may contain TextOutputItem POJOs or Maps
        Object outputArray = lastOutput.get("output");
        if (outputArray instanceof List<?> list) {
            for (var item : list) {
                if (item instanceof String s) {
                    texts.add(s);
                } else if (item instanceof OutputItem oi && oi.toString() != null) {
                    // TextOutputItem.toString() returns the text field
                    texts.add(oi.toString());
                } else if (item instanceof Map<?, ?> map) {
                    Object text = map.get("text");
                    if (text instanceof String s) {
                        texts.add(s);
                    }
                }
            }
            if (!texts.isEmpty()) {
                return String.join("\n", texts);
            }
        }

        // Format 2: Flat keys like "output:text:*"
        for (var entry : lastOutput.entrySet()) {
            if (entry.getKey() instanceof String key && key.startsWith("output:text:")) {
                Object val = entry.getValue();
                if (val instanceof String s) {
                    texts.add(s);
                } else if (val instanceof List<?> list) {
                    for (var item : list) {
                        if (item instanceof String s)
                            texts.add(s);
                        else if (item instanceof Map<?, ?> map && map.get("text") instanceof String s)
                            texts.add(s);
                    }
                } else if (val instanceof Map<?, ?> map && map.get("text") instanceof String s) {
                    texts.add(s);
                }
            }
        }

        if (!texts.isEmpty()) {
            return String.join("\n", texts);
        }

        // Check if the output contains any actual LLM-generated content.
        // Output keys follow patterns like "output", "output:text:*", "reply".
        // If none are present, the map only contains pipeline metadata
        // (e.g. "actions", "input", "context") — return null to avoid
        // serializing raw metadata as a group discussion response.
        boolean hasAnyOutput = lastOutput.keySet().stream()
                .anyMatch(k -> k instanceof String s &&
                        (s.startsWith("output") || s.startsWith("reply")));
        if (!hasAnyOutput) {
            return null;
        }

        // Fallback: serialize the entire output map (backward compat)
        try {
            return jsonSerialization.serialize(lastOutput);
        } catch (Exception e) {
            LOGGER.warnf("Failed to serialize conversation output, falling back to toString(): %s", e.getMessage());
            return lastOutput.toString();
        }
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
}
