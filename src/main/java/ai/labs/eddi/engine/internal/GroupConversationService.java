package ai.labs.eddi.engine.internal;

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
import ai.labs.eddi.configs.groups.model.DiscussionStylePresets;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.GroupConversationState;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntry;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntryType;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IGroupConversationService;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.model.Deployment.Environment;
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
 * Phase-based orchestrator for multi-agent group conversations. Supports 5
 * discussion styles (ROUND_TABLE, PEER_REVIEW, DEVIL_ADVOCATE, DELPHI, DEBATE)
 * plus fully custom phase definitions.
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

    // Metrics
    private final Timer timerGroupDiscussion;
    private final Counter counterGroupDiscussion;
    private final Counter counterGroupFailure;

    @Inject
    public GroupConversationService(IAgentGroupStore groupStore, IGroupConversationStore conversationStore, IConversationService conversationService,
            IAgentFactory agentFactory, ITemplatingEngine templatingEngine, IJsonSerialization jsonSerialization, MeterRegistry meterRegistry,
            @ConfigProperty(name = "eddi.groups.max-depth", defaultValue = "3") int maxDepth) {
        this.groupStore = groupStore;
        this.conversationStore = conversationStore;
        this.conversationService = conversationService;
        this.agentFactory = agentFactory;
        this.templatingEngine = templatingEngine;
        this.jsonSerialization = jsonSerialization;
        this.maxDepth = maxDepth;
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

        // Load group config
        AgentGroupConfiguration config = groupStore.read(groupId, groupStore.getCurrentResourceId(groupId).getVersion());
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

        // Validate early — so errors are returned synchronously
        AgentGroupConfiguration config = groupStore.read(groupId, groupStore.getCurrentResourceId(groupId).getVersion());
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

                    if (phase.targetEachPeer()) {
                        executePeerTargetedPhase(gc, config, speakers, phase, protocol, question, phaseIdx, listener);
                    } else if (phase.turnOrder() == TurnOrder.PARALLEL) {
                        executeParallelPhase(gc, config, speakers, phase, protocol, question, phaseIdx, listener);
                    } else {
                        executeSequentialPhase(gc, config, speakers, phase, protocol, question, phaseIdx, listener);
                    }

                    gc.setLastModified(Instant.now());
                    conversationStore.update(gc);

                    if (listener != null) {
                        listener.onPhaseComplete(new GroupConversationEventSink.PhaseCompleteEvent(phaseIdx, phase.name()));
                    }
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
    // Phase execution
    // =================================================================

    private void executeSequentialPhase(GroupConversation gc, AgentGroupConfiguration config, List<GroupMember> speakers, DiscussionPhase phase,
                                        ProtocolConfig protocol, String question, int phaseIdx, GroupDiscussionEventListener listener)
            throws GroupDiscussionException {
        for (GroupMember speaker : speakers) {
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
                                      ProtocolConfig protocol, String question, int phaseIdx, GroupDiscussionEventListener listener)
            throws GroupDiscussionException {

        // SAFETY: Snapshot the transcript so parallel tasks each see a consistent view.
        List<TranscriptEntry> snapshotTranscript = List.copyOf(gc.getTranscript());

        // Notify all speakers starting (parallel)
        if (listener != null) {
            for (GroupMember speaker : speakers) {
                listener.onSpeakerStart(
                        new GroupConversationEventSink.SpeakerStartEvent(speaker.agentId(), speaker.displayName(), phaseIdx, phase.name()));
            }
        }

        List<CompletableFuture<TranscriptEntry>> futures = speakers.stream().map(speaker -> CompletableFuture.supplyAsync(() -> {
            try {
                String input = buildPhaseInput(phase, speaker, question, snapshotTranscript, phaseIdx, null);
                return executeAgentTurn(speaker, gc, input, protocol, phaseIdx, phase, null);
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
            } catch (Exception e) {
                gc.getTranscript().add(errorEntry(null, phaseIdx, phase, e.getMessage()));
            }
        }
    }

    /**
     * Peer-targeted phase: each speaker addresses each OTHER speaker individually
     * (N×(N-1) turns). Used for CRITIQUE style.
     */
    private void executePeerTargetedPhase(GroupConversation gc, AgentGroupConfiguration config, List<GroupMember> speakers, DiscussionPhase phase,
                                          ProtocolConfig protocol, String question, int phaseIdx, GroupDiscussionEventListener listener)
            throws GroupDiscussionException {

        // Collect all non-moderator members as targets
        List<GroupMember> allMembers = config.getMembers().stream()
                .sorted(Comparator.comparing(m -> m.speakingOrder() != null ? m.speakingOrder() : Integer.MAX_VALUE)).toList();

        for (GroupMember speaker : speakers) {
            for (GroupMember target : allMembers) {
                if (speaker.agentId().equals(target.agentId())) {
                    continue; // Don't critique yourself
                }
                if (listener != null) {
                    listener.onSpeakerStart(
                            new GroupConversationEventSink.SpeakerStartEvent(speaker.agentId(), speaker.displayName(), phaseIdx, phase.name()));
                }
                String input = buildPhaseInput(phase, speaker, question, gc.getTranscript(), phaseIdx, target);
                TranscriptEntry entry = executeAgentTurn(speaker, gc, input, protocol, phaseIdx, phase, target.agentId());
                gc.getTranscript().add(entry);
                if (listener != null) {
                    listener.onSpeakerComplete(new GroupConversationEventSink.SpeakerCompleteEvent(speaker.agentId(), speaker.displayName(),
                            entry.content(), phaseIdx, phase.name()));
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
                    responseFuture.complete(response);
                });

                String response = responseFuture.get(timeout, TimeUnit.SECONDS);

                return new TranscriptEntry(member.agentId(), member.displayName(), response, phaseIdx, phase.name(), entryType, Instant.now(), null,
                        targetAgentId);

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

        // Format 1: Nested "output" array — [{type: "text", text: "...", delay: 0}]
        Object outputArray = lastOutput.get("output");
        if (outputArray instanceof List<?> list) {
            for (var item : list) {
                if (item instanceof String s) {
                    texts.add(s);
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
}
