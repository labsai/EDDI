/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.agents.AgentSigningService;
import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.crypto.NonceCacheService;
import ai.labs.eddi.configs.groups.IAgentGroupStore;
import ai.labs.eddi.configs.groups.IGroupConversationStore;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ContextScope;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DiscussionPhase;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DiscussionStyle;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.GroupMember;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.PhaseType;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ProtocolConfig;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ProtocolConfig.MemberFailurePolicy;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ProtocolConfig.MemberUnavailablePolicy;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.TurnOrder;
import ai.labs.eddi.configs.groups.model.GroupConversation.DecisionType;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntryType;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * End-to-end tests for I4 (abstention + minority report) through the real
 * discussion loop. The token-matching rules are unit-tested in
 * {@code AbstentionDetectorTest}; this class covers what only the loop shows —
 * that a PASS becomes an ABSTAINED entry rather than a contribution, that
 * unanimous abstention feeds I2's convergence exit, and that the dissent round
 * populates both the transcript and the DecisionRecord.
 *
 * @author tests
 */
class GroupConversationServiceAbstentionTest {

    @Mock
    private IAgentGroupStore groupStore;
    @Mock
    private IGroupConversationStore conversationStore;
    @Mock
    private IConversationService conversationService;
    @Mock
    private IAgentFactory agentFactory;
    @Mock
    private ITemplatingEngine templatingEngine;
    @Mock
    private IJsonSerialization jsonSerialization;
    @Mock
    private AgentSigningService agentSigningService;
    @Mock
    private IAgentStore agentStore;
    @Mock
    private NonceCacheService nonceCacheService;
    @Mock
    private IScheduleStore scheduleStore;

    private GroupConversationService service;

    private static final String GROUP_ID = "group-abstain";
    private static final String USER_ID = "user-abstain";
    private static final String AGENT_A = "agent-a";
    private static final String AGENT_B = "agent-b";
    private static final String AGENT_C = "agent-c";
    private static final String MODERATOR = "mod-agent";

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new GroupConversationService(
                groupStore, conversationStore, conversationService,
                agentFactory, templatingEngine, jsonSerialization,
                new SimpleMeterRegistry(), agentSigningService, agentStore,
                scheduleStore, nonceCacheService, null, new CallerIdentityContext(null, null), "default", 3);
    }

    private AgentGroupConfiguration buildConfig(List<DiscussionPhase> phases, boolean recordDissents) {
        var config = new AgentGroupConfiguration();
        config.setName("Abstention Test Group");
        config.setStyle(DiscussionStyle.CUSTOM);
        config.setPhases(phases);
        config.setMembers(List.of(
                new GroupMember(AGENT_A, "Agent A", 1, null),
                new GroupMember(AGENT_B, "Agent B", 2, null)));
        config.setModeratorAgentId(MODERATOR);
        config.setProtocol(new ProtocolConfig(60, MemberFailurePolicy.SKIP, 2, MemberUnavailablePolicy.SKIP, 50));
        config.setRecordDissents(recordDissents);
        return config;
    }

    private IResourceStore.IResourceId mockResourceId() {
        return new IResourceStore.IResourceId() {
            @Override
            public String getId() {
                return GROUP_ID;
            }

            @Override
            public Integer getVersion() {
                return 1;
            }
        };
    }

    private DiscussionPhase phase(String name, PhaseType type, String participants, int repeats, boolean allowAbstention) {
        return new DiscussionPhase(name, type, participants, TurnOrder.SEQUENTIAL, ContextScope.FULL,
                false, null, repeats, false, null, allowAbstention);
    }

    /** Routes each agent's reply through {@code responder}, keyed by agent id. */
    private void stubTurns(Function<String, String> responder) throws Exception {
        doReturn(mock(ai.labs.eddi.engine.runtime.IAgent.class)).when(agentFactory).getLatestReadyAgent(any(), anyString());
        doReturn(new IConversationService.ConversationResult("member-conv", null))
                .when(conversationService).startConversation(any(), any(), any(), any());
        doAnswer(inv -> {
            String agentId = inv.getArgument(1);
            IConversationService.ConversationResponseHandler handler = inv.getArgument(8);
            if (handler != null) {
                var snapshot = new SimpleConversationMemorySnapshot();
                var output = new ConversationOutput();
                output.put("output", List.of(responder.apply(agentId)));
                snapshot.setConversationOutputs(new ArrayList<>(List.of(output)));
                handler.onComplete(snapshot);
            }
            return null;
        }).when(conversationService).say(any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    @DisplayName("a PASS becomes an ABSTAINED entry with no content, not a contribution")
    void pass_becomesAnAbstainedEntry() throws Exception {
        var phases = List.of(phase("Opinion", PhaseType.OPINION, "ALL", 1, true));
        var config = buildConfig(phases, false);
        doReturn(mockResourceId()).when(groupStore).getCurrentResourceId(GROUP_ID);
        doReturn(config).when(groupStore).read(GROUP_ID, 1);
        doReturn("gc-pass").when(conversationStore).create(any());
        stubTurns(agentId -> AGENT_A.equals(agentId) ? "PASS" : "I think we should ship it.");

        var gc = service.discuss(GROUP_ID, "Q?", USER_ID, 0, null);

        var byAgent = gc.getTranscript().stream()
                .filter(e -> e.speakerAgentId() != null)
                .collect(java.util.stream.Collectors.toMap(e -> e.speakerAgentId(), e -> e, (a, b) -> a));
        assertEquals(TranscriptEntryType.ABSTAINED, byAgent.get(AGENT_A).type());
        assertNull(byAgent.get(AGENT_A).content(),
                "recording 'PASS' as content would put a non-answer into the record that peers and the synthesizer read as a position");
        assertEquals(TranscriptEntryType.OPINION, byAgent.get(AGENT_B).type(), "the other member's real contribution is untouched");
        assertEquals("I think we should ship it.", byAgent.get(AGENT_B).content());
    }

    @Test
    @DisplayName("a response merely containing 'pass' is kept as a real contribution")
    void substringPass_isNotAnAbstention() throws Exception {
        var phases = List.of(phase("Opinion", PhaseType.OPINION, "ALL", 1, true));
        var config = buildConfig(phases, false);
        doReturn(mockResourceId()).when(groupStore).getCurrentResourceId(GROUP_ID);
        doReturn(config).when(groupStore).read(GROUP_ID, 1);
        doReturn("gc-substring").when(conversationStore).create(any());
        stubTurns(agentId -> "I'll pass on the first point, but I disagree about the timeline.");

        var gc = service.discuss(GROUP_ID, "Q?", USER_ID, 0, null);

        assertTrue(gc.getTranscript().stream().noneMatch(e -> e.type() == TranscriptEntryType.ABSTAINED),
                "a stated position must never be silently deleted as an abstention");
        assertTrue(gc.getTranscript().stream()
                .anyMatch(e -> e.content() != null && e.content().contains("disagree about the timeline")));
    }

    @Test
    @DisplayName("abstention is off by default: PASS is recorded as an ordinary contribution")
    void abstentionDisabled_passIsJustText() throws Exception {
        var phases = List.of(phase("Opinion", PhaseType.OPINION, "ALL", 1, false));
        var config = buildConfig(phases, false);
        doReturn(mockResourceId()).when(groupStore).getCurrentResourceId(GROUP_ID);
        doReturn(config).when(groupStore).read(GROUP_ID, 1);
        doReturn("gc-off").when(conversationStore).create(any());
        stubTurns(agentId -> "PASS");

        var gc = service.discuss(GROUP_ID, "Q?", USER_ID, 0, null);

        assertTrue(gc.getTranscript().stream().noneMatch(e -> e.type() == TranscriptEntryType.ABSTAINED),
                "an opt-in feature must change nothing when it is off");
        assertTrue(gc.getTranscript().stream().anyMatch(e -> "PASS".equals(e.content())));
    }

    @Test
    @DisplayName("unanimous abstention ends the phase's repeats early via I2's convergence hook")
    void allAbstained_triggersConvergenceExit() throws Exception {
        // This is the I2 hook the plan describes: with every participant abstaining,
        // ConvergenceDetector.allParticipantsAbstained fires with no LLM judge call.
        var phases = List.of(phase("Discussion", PhaseType.OPINION, "ALL", 4, true));
        var config = buildConfig(phases, false);
        doReturn(mockResourceId()).when(groupStore).getCurrentResourceId(GROUP_ID);
        doReturn(config).when(groupStore).read(GROUP_ID, 1);
        doReturn("gc-all-pass").when(conversationStore).create(any());
        stubTurns(agentId -> "PASS");

        var gc = service.discuss(GROUP_ID, "Q?", USER_ID, 0, null);

        long abstentions = gc.getTranscript().stream().filter(e -> e.type() == TranscriptEntryType.ABSTAINED).count();
        assertEquals(2, abstentions, "only the first repeat runs — both members abstain, then the phase stops");
        assertTrue(gc.getTranscript().stream().anyMatch(e -> e.type() == TranscriptEntryType.CONVERGENCE),
                "the early exit must be recorded, not silent");
        // No moderator turn: the deterministic path costs nothing.
        verify(conversationService, never()).say(any(), eq(MODERATOR), any(), any(), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    @DisplayName("partial abstention does not converge — the remaining repeats still run")
    void partialAbstention_doesNotConverge() throws Exception {
        var phases = List.of(phase("Discussion", PhaseType.OPINION, "ALL", 2, true));
        var config = buildConfig(phases, false);
        doReturn(mockResourceId()).when(groupStore).getCurrentResourceId(GROUP_ID);
        doReturn(config).when(groupStore).read(GROUP_ID, 1);
        doReturn("gc-partial").when(conversationStore).create(any());
        stubTurns(agentId -> AGENT_A.equals(agentId) ? "PASS" : "I still disagree.");

        var gc = service.discuss(GROUP_ID, "Q?", USER_ID, 0, null);

        long contributions = gc.getTranscript().stream()
                .filter(e -> e.type() == TranscriptEntryType.OPINION)
                .count();
        assertEquals(2, contributions, "one member still had something to say, so both repeats must run");
    }

    /**
     * THREE members, deliberately. With two, {@code n} and {@code n×(n-1)} are both
     * 2, so a peer-targeted phase's turn count and speaker count coincide and the
     * denominator bug is invisible — a test built on two members passes either way.
     * Three is the smallest roster where 6 turns ≠ 3 speakers.
     */
    private AgentGroupConfiguration buildThreeMemberConfig(List<DiscussionPhase> phases) {
        var config = buildConfig(phases, false);
        config.setMembers(List.of(
                new GroupMember(AGENT_A, "Agent A", 1, null),
                new GroupMember(AGENT_B, "Agent B", 2, null),
                new GroupMember(AGENT_C, "Agent C", 3, null)));
        return config;
    }

    private DiscussionPhase peerTargetedPhase(int repeats) {
        return new DiscussionPhase("Critique", PhaseType.CRITIQUE, "ALL", TurnOrder.SEQUENTIAL,
                ContextScope.FULL, true, null, repeats, false, null, true);
    }

    @Test
    @DisplayName("a peer-targeted round counts turns, not speakers, when deciding unanimity")
    void peerTargeted_partialAbstention_doesNotFalselyConverge() throws Exception {
        // 3 speakers × 2 others = 6 turns. A passes on both its targets → 2 of 6
        // abstentions. Against the OLD speakers.size() denominator that is 2 vs 3 —
        // close enough that one more abstention would have read as unanimity and
        // ended a round that produced four real critiques.
        var config = buildThreeMemberConfig(List.of(peerTargetedPhase(2)));
        doReturn(mockResourceId()).when(groupStore).getCurrentResourceId(GROUP_ID);
        doReturn(config).when(groupStore).read(GROUP_ID, 1);
        doReturn("gc-peer").when(conversationStore).create(any());
        stubTurns(agentId -> AGENT_A.equals(agentId) ? "PASS" : "Your point ignores the cost.");

        var gc = service.discuss(GROUP_ID, "Q?", USER_ID, 0, null);

        assertTrue(gc.getTranscript().stream().noneMatch(e -> e.type() == TranscriptEntryType.CONVERGENCE),
                "four real critiques means the round did not converge");
        assertEquals(8, gc.getTranscript().stream().filter(e -> e.type() == TranscriptEntryType.CRITIQUE).count(),
                "both repeats run: 4 real critiques each");
    }

    @Test
    @DisplayName("a peer-targeted round where every scheduled turn abstains does converge")
    void peerTargeted_fullAbstention_converges() throws Exception {
        // The inverse half of the same bug: 6 abstentions against the old
        // speakers.size() denominator of 3 never matched, so a genuinely unanimous
        // peer-targeted round could not use the free exit at all.
        var config = buildThreeMemberConfig(List.of(peerTargetedPhase(3)));
        doReturn(mockResourceId()).when(groupStore).getCurrentResourceId(GROUP_ID);
        doReturn(config).when(groupStore).read(GROUP_ID, 1);
        doReturn("gc-peer-all").when(conversationStore).create(any());
        stubTurns(agentId -> "PASS");

        var gc = service.discuss(GROUP_ID, "Q?", USER_ID, 0, null);

        assertTrue(gc.getTranscript().stream().anyMatch(e -> e.type() == TranscriptEntryType.CONVERGENCE),
                "every one of the 6 scheduled turns abstained — the phase must exit");
        assertEquals(6, gc.getTranscript().stream().filter(e -> e.type() == TranscriptEntryType.ABSTAINED).count(),
                "only the first repeat's 6 turns run");
    }

    @Test
    @DisplayName("a task phase never abstains, even with allowAbstention set")
    void taskPhase_passIsNotAnAbstention() throws Exception {
        // "PASS" is a natural verdict word for VERIFY. Treating it as an abstention
        // yields null content, which parseAndApplyVerification reads as its
        // mark-everything-passed fallback — silently verifying unchecked tasks.
        var phases = List.of(phase("Verify", PhaseType.VERIFY, "ALL", 1, true));
        var config = buildConfig(phases, false);
        doReturn(mockResourceId()).when(groupStore).getCurrentResourceId(GROUP_ID);
        doReturn(config).when(groupStore).read(GROUP_ID, 1);
        doReturn("gc-verify").when(conversationStore).create(any());
        stubTurns(agentId -> "PASS");

        var gc = service.discuss(GROUP_ID, "Q?", USER_ID, 0, null);

        assertTrue(gc.getTranscript().stream().noneMatch(e -> e.type() == TranscriptEntryType.ABSTAINED),
                "abstention must be inert on task phases, whose inputs never carry the instruction");
    }

    @Test
    @DisplayName("a synthesis phase with repeats > 1 runs the dissent round once, not per repeat")
    void dissentRound_runsOnceForRepeatingSynthesis() throws Exception {
        var phases = List.of(phase("Summary", PhaseType.SYNTHESIS, "MODERATOR", 3, false));
        var config = buildConfig(phases, true);
        doReturn(mockResourceId()).when(groupStore).getCurrentResourceId(GROUP_ID);
        doReturn(config).when(groupStore).read(GROUP_ID, 1);
        doReturn("gc-dissent-repeats").when(conversationStore).create(any());
        stubTurns(agentId -> MODERATOR.equals(agentId) ? "We recommend option B." : "I disagree on cost.");

        var gc = service.discuss(GROUP_ID, "Q?", USER_ID, 0, null);

        assertEquals(2, gc.getTranscript().stream().filter(e -> e.type() == TranscriptEntryType.DISSENT).count(),
                "two dissenters, one round — not two per repeat");
        assertEquals(2, gc.getDecision().dissents().size(),
                "and the DecisionRecord must not accumulate a copy per repeat");
    }

    @Test
    @DisplayName("the dissent round records non-PASS replies in the transcript and the DecisionRecord")
    void dissentRound_recordsMinorityReport() throws Exception {
        var phases = List.of(phase("Summary", PhaseType.SYNTHESIS, "MODERATOR", 1, false));
        var config = buildConfig(phases, true);
        doReturn(mockResourceId()).when(groupStore).getCurrentResourceId(GROUP_ID);
        doReturn(config).when(groupStore).read(GROUP_ID, 1);
        doReturn("gc-dissent").when(conversationStore).create(any());
        stubTurns(agentId -> switch (agentId) {
            case MODERATOR -> "We recommend option B.";
            case AGENT_A -> "PASS";
            default -> "Option B ignores the migration cost.";
        });

        var gc = service.discuss(GROUP_ID, "Q?", USER_ID, 0, null);

        var dissentEntries = gc.getTranscript().stream()
                .filter(e -> e.type() == TranscriptEntryType.DISSENT)
                .toList();
        assertEquals(1, dissentEntries.size(), "only the non-PASS reply becomes a dissent");
        assertEquals(AGENT_B, dissentEntries.get(0).speakerAgentId());
        assertTrue(dissentEntries.get(0).content().contains("migration cost"));

        assertNotNull(gc.getDecision(), "a dissent with no other decision creates a NONE-type record");
        assertEquals(DecisionType.NONE, gc.getDecision().type());
        assertEquals(1, gc.getDecision().dissents().size());
        assertEquals(AGENT_B, gc.getDecision().dissents().get(0).agentId());
    }

    @Test
    @DisplayName("a dissent round where everyone passes produces nothing at all")
    void dissentRound_allPass_producesNothing() throws Exception {
        var phases = List.of(phase("Summary", PhaseType.SYNTHESIS, "MODERATOR", 1, false));
        var config = buildConfig(phases, true);
        doReturn(mockResourceId()).when(groupStore).getCurrentResourceId(GROUP_ID);
        doReturn(config).when(groupStore).read(GROUP_ID, 1);
        doReturn("gc-no-dissent").when(conversationStore).create(any());
        stubTurns(agentId -> MODERATOR.equals(agentId) ? "We recommend option B." : "PASS");

        var gc = service.discuss(GROUP_ID, "Q?", USER_ID, 0, null);

        assertTrue(gc.getTranscript().stream().noneMatch(e -> e.type() == TranscriptEntryType.DISSENT));
        assertNull(gc.getDecision(),
                "unanimous agreement must not fabricate an empty DecisionRecord — absence of dissent is not a decision");
    }

    @Test
    @DisplayName("recordDissents is off by default: no extra turns are spent")
    void dissentRound_disabled_costsNothing() throws Exception {
        var phases = List.of(phase("Summary", PhaseType.SYNTHESIS, "MODERATOR", 1, false));
        var config = buildConfig(phases, false);
        doReturn(mockResourceId()).when(groupStore).getCurrentResourceId(GROUP_ID);
        doReturn(config).when(groupStore).read(GROUP_ID, 1);
        doReturn("gc-dissent-off").when(conversationStore).create(any());
        stubTurns(agentId -> MODERATOR.equals(agentId) ? "We recommend option B." : "I disagree strongly.");

        var gc = service.discuss(GROUP_ID, "Q?", USER_ID, 0, null);

        assertTrue(gc.getTranscript().stream().noneMatch(e -> e.type() == TranscriptEntryType.DISSENT));
        verify(conversationService, never()).say(any(), eq(AGENT_A), any(), any(), any(), any(), any(), anyBoolean(), any());
        verify(conversationService, never()).say(any(), eq(AGENT_B), any(), any(), any(), any(), any(), anyBoolean(), any());
        assertNull(gc.getDecision());
    }

    @Test
    @DisplayName("dissenters run in their own conversations, never their member ones")
    void dissentRound_usesSeparateConversations() throws Exception {
        // A "critique the synthesis" exchange left in a member's own conversation
        // becomes recent context a later round reads back as its own prior position.
        var phases = List.of(phase("Summary", PhaseType.SYNTHESIS, "MODERATOR", 1, false));
        var config = buildConfig(phases, true);
        doReturn(mockResourceId()).when(groupStore).getCurrentResourceId(GROUP_ID);
        doReturn(config).when(groupStore).read(GROUP_ID, 1);
        doReturn("gc-dissent-conv").when(conversationStore).create(any());
        stubTurns(agentId -> MODERATOR.equals(agentId) ? "We recommend option B." : "I disagree.");

        var gc = service.discuss(GROUP_ID, "Q?", USER_ID, 0, null);

        Map<String, String> keys = gc.getMemberConversationIds();
        assertTrue(keys.containsKey("__dissent__" + AGENT_A), "expected a dedicated dissent conversation: " + keys.keySet());
        assertTrue(keys.containsKey("__dissent__" + AGENT_B), "expected a dedicated dissent conversation: " + keys.keySet());
        assertFalse(keys.containsKey(AGENT_A),
                "member A never spoke in this discussion, so it must have no member conversation: " + keys.keySet());
    }
}
