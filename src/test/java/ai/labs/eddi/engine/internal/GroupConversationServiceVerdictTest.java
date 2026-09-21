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
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.DecisionType;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntryType;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.model.InputData;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.runtime.IAgent;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * End-to-end tests for I3 (structured verdicts + deterministic synthesis)
 * through the real discussion loop. {@code DebateVerdictParserTest} pins the
 * parse rules and {@code GroupContextBuilderTest} pins template selection; this
 * class covers what only the loop shows — that a debate's conclusion reaches
 * {@code getDecision()} as structured data, that the JSON the judge was asked
 * for never becomes the user-facing answer, and that a moderator-less synthesis
 * has exactly one author.
 *
 * @author tests
 */
class GroupConversationServiceVerdictTest {

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

    private static final String GROUP_ID = "group-verdict";
    private static final String USER_ID = "user-verdict";
    private static final String AGENT_PRO = "agent-pro";
    private static final String AGENT_CON = "agent-con";
    private static final String MODERATOR = "mod-agent";

    private static final String VERDICT_JSON = """
            {"winner": "CON", "scores": {"PRO": 4, "CON": 9}, "reasoning": "PRO asserted; CON cited."}""";

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new GroupConversationService(
                groupStore, conversationStore, conversationService,
                agentFactory, templatingEngine, jsonSerialization,
                new SimpleMeterRegistry(), agentSigningService, agentStore,
                scheduleStore, nonceCacheService, null, new CallerIdentityContext(null, null), "default", 3);
    }

    private DiscussionPhase phase(String name, PhaseType type, String participants) {
        return new DiscussionPhase(name, type, participants, TurnOrder.SEQUENTIAL, ContextScope.FULL, false, null, 1, false);
    }

    /** ARGUE then a moderator-judged SYNTHESIS — the shape DEBATE expands to. */
    private List<DiscussionPhase> debatePhases() {
        return List.of(phase("Arguments", PhaseType.ARGUE, "ALL"), phase("Judgment", PhaseType.SYNTHESIS, "MODERATOR"));
    }

    private AgentGroupConfiguration buildConfig(List<DiscussionPhase> phases, List<GroupMember> members, String moderatorAgentId) {
        return buildConfig(phases, members, moderatorAgentId, false);
    }

    private AgentGroupConfiguration buildConfig(List<DiscussionPhase> phases, List<GroupMember> members, String moderatorAgentId,
                                                boolean recordDissents) {
        var config = new AgentGroupConfiguration();
        config.setName("Verdict Test Group");
        config.setStyle(DiscussionStyle.CUSTOM);
        config.setPhases(phases);
        config.setMembers(members);
        config.setModeratorAgentId(moderatorAgentId);
        config.setProtocol(new ProtocolConfig(60, MemberFailurePolicy.SKIP, 2, MemberUnavailablePolicy.SKIP, 50));
        config.setRecordDissents(recordDissents);
        return config;
    }

    private List<GroupMember> debaters() {
        return List.of(new GroupMember(AGENT_PRO, "Pro", 1, "PRO"), new GroupMember(AGENT_CON, "Con", 2, "CON"));
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

    private void stubTurns(Function<String, String> responder) throws Exception {
        doReturn(mock(IAgent.class)).when(agentFactory).getLatestReadyAgent(any(), anyString());
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

    private GroupConversation runDiscussion(AgentGroupConfiguration config, String conversationId,
                                            Function<String, String> responder)
            throws Exception {
        doReturn(mockResourceId()).when(groupStore).getCurrentResourceId(GROUP_ID);
        doReturn(config).when(groupStore).read(GROUP_ID, 1);
        doReturn(conversationId).when(conversationStore).create(any());
        stubTurns(responder);
        return service.discuss(GROUP_ID, "Should we ship on Friday?", USER_ID, 0, null);
    }

    // =================================================================
    // (b) Structured verdicts
    // =================================================================

    @Test
    @DisplayName("a debate judgment reaches getDecision() as a structured VERDICT")
    void debateJudgment_populatesTheDecisionRecord() throws Exception {
        var gc = runDiscussion(buildConfig(debatePhases(), debaters(), MODERATOR), "gc-verdict",
                agentId -> MODERATOR.equals(agentId) ? VERDICT_JSON : "My argument.");

        var decision = gc.getDecision();
        assertNotNull(decision, "a debate that produced a judgment must not leave the decision null");
        assertEquals(DecisionType.VERDICT, decision.type());
        assertEquals("CON", decision.winner());
        assertEquals("debate-judgment", decision.method());
        assertEquals("Judgment", decision.decidedAtPhase());
        assertEquals(9.0, decision.tally().get("CON"));
        assertEquals(VERDICT_JSON, decision.raw(), "the judge's own output is kept for audit");
    }

    @Test
    @DisplayName("the raw JSON never becomes the discussion's answer")
    void debateJudgment_answerIsReadableProse() throws Exception {
        var gc = runDiscussion(buildConfig(debatePhases(), debaters(), MODERATOR), "gc-answer",
                agentId -> MODERATOR.equals(agentId) ? VERDICT_JSON : "My argument.");

        String answer = gc.getSynthesizedAnswer();
        assertFalse(answer.contains("\"winner\""), "handing a caller raw JSON as the answer is the defect this replaces");
        assertEquals("CON wins (PRO 4/10, CON 9/10) — PRO asserted; CON cited.", answer);

        // The transcript keeps what the agent actually said — substituting there
        // would rewrite an agent's words under its own signature.
        var synthesisEntry = gc.getTranscript().stream()
                .filter(e -> e.type() == TranscriptEntryType.SYNTHESIS).reduce((a, b) -> b).orElseThrow();
        assertEquals(VERDICT_JSON, synthesisEntry.content());
    }

    @Test
    @DisplayName("an unparseable judgment degrades to prose, and never fails the discussion")
    void unparseableJudgment_keepsTheProseConclusion() throws Exception {
        var gc = runDiscussion(buildConfig(debatePhases(), debaters(), MODERATOR), "gc-prose",
                agentId -> MODERATOR.equals(agentId) ? "CON made the stronger case, narrowly." : "My argument.");

        assertEquals(GroupConversation.GroupConversationState.COMPLETED, gc.getState());
        assertEquals(DecisionType.NONE, gc.getDecision().type());
        assertEquals("CON made the stronger case, narrowly.", gc.getSynthesizedAnswer());
        assertEquals("CON made the stronger case, narrowly.", gc.getDecision().raw());
    }

    @Test
    @DisplayName("a non-debate discussion records no decision and keeps its prose synthesis")
    void nonDebateSynthesis_isUnaffected() throws Exception {
        // The opt-in check: a brainstorm's synthesis is still a summary, and its
        // author is never asked for a winner.
        var phases = List.of(phase("Opinions", PhaseType.OPINION, "ALL"), phase("Wrap-up", PhaseType.SYNTHESIS, "MODERATOR"));
        var gc = runDiscussion(buildConfig(phases, debaters(), MODERATOR), "gc-brainstorm",
                agentId -> MODERATOR.equals(agentId) ? "Both ideas are worth trying." : "My idea.");

        assertNull(gc.getDecision(), "no decision-producing feature ran — a NONE record here would be noise");
        assertEquals("Both ideas are worth trying.", gc.getSynthesizedAnswer());
    }

    @Test
    @DisplayName("a debate with no PRO/CON roles gets no fabricated winner")
    void debateWithoutSides_recordsNoVerdict() throws Exception {
        // create_group(style="DEBATE") with no memberRoles: "ROLE:PRO" resolves to
        // ALL, every speaker is mapped to the same side, and nobody ever argued
        // PRO. A judge asked to score PRO against CON would pick one anyway.
        var roleless = List.of(new GroupMember(AGENT_PRO, "One", 1, null), new GroupMember(AGENT_CON, "Two", 2, null));
        var gc = runDiscussion(buildConfig(debatePhases(), roleless, MODERATOR), "gc-noroles",
                agentId -> MODERATOR.equals(agentId) ? "Both made fair points." : "My argument.");

        assertNull(gc.getDecision());
        assertEquals("Both made fair points.", gc.getSynthesizedAnswer());
    }

    @Test
    @DisplayName("a moderator-less debate concludes in prose — a debater never judges its own debate")
    void moderatorlessDebate_recordsNoVerdict() throws Exception {
        // The sole synthesizer here is a debater whose own conversation holds
        // "argue the FOR side". Stamping its answer as DecisionRecord.winner would
        // present one side's opinion as the group's finding.
        var gc = runDiscussion(buildConfig(debatePhases(), debaters(), null), "gc-partisan",
                agentId -> AGENT_PRO.equals(agentId) ? "PRO clearly wins." : "My argument.");

        assertNull(gc.getDecision(), "a partisan judge must not produce a structured verdict");
        var synthesisEntries = gc.getTranscript().stream()
                .filter(e -> e.type() == TranscriptEntryType.SYNTHESIS).toList();
        assertEquals(1, synthesisEntries.size(), "still exactly one synthesizer — the (a) fix is independent");
        assertEquals(AGENT_PRO, synthesisEntries.get(0).speakerAgentId());
    }

    @Test
    @DisplayName("dissents merge onto the verdict, and the answer stays prose")
    void verdictThenDissents_bothSurvive() throws Exception {
        // The production ordering: recordDebateVerdict runs, then runDissentRound
        // rebuilds the record positionally through DecisionRecord's 8-arg
        // constructor. If `raw` is lost there, isRenderedFrom stops matching and
        // the user-facing answer silently becomes the raw JSON.
        var gc = runDiscussion(buildConfig(debatePhases(), debaters(), MODERATOR, true), "gc-dissent",
                agentId -> MODERATOR.equals(agentId) ? VERDICT_JSON : "I disagree with that reading.");

        var decision = gc.getDecision();
        assertEquals(DecisionType.VERDICT, decision.type(), "the dissent round must not downgrade the verdict");
        assertEquals("CON", decision.winner());
        assertEquals(VERDICT_JSON, decision.raw(), "raw must survive the rebuild or the answer reverts to JSON");
        assertFalse(decision.dissents().isEmpty(), "the debaters dissented");
        assertEquals("CON wins (PRO 4/10, CON 9/10) — PRO asserted; CON cited.", gc.getSynthesizedAnswer());

        // And the minority reacted to the readable conclusion, not to braces.
        var dissentInputs = dissentPromptsSeen();
        assertFalse(dissentInputs.isEmpty(), "no dissent turn ran");
        assertTrue(dissentInputs.stream().noneMatch(i -> i != null && i.contains("\"winner\"")),
                "asking a member to disagree with a JSON blob is not a minority report: " + dissentInputs);
    }

    /** Every prompt sent to a member during a dissent-round turn. */
    private List<String> dissentPromptsSeen() throws Exception {
        var captor = ArgumentCaptor.forClass(InputData.class);
        verify(conversationService, atLeastOnce()).say(any(), any(), any(), any(), any(), any(), captor.capture(), anyBoolean(), any());
        return captor.getAllValues().stream()
                .map(d -> d != null ? d.getInput() : null)
                .filter(i -> i != null && i.contains("materially disagree"))
                .toList();
    }

    // =================================================================
    // (a) Deterministic synthesis
    // =================================================================

    @Test
    @DisplayName("a moderator-less SYNTHESIS has exactly one author: first by speakingOrder")
    void moderatorlessSynthesis_hasOneDeterministicAuthor() throws Exception {
        // Before I3 every member spoke here and executeDiscussion took the LAST
        // SYNTHESIS entry as the answer — so the conclusion was decided by
        // speaking order. The roster is out of order in the list so "first"
        // cannot be satisfied by position alone.
        var members = List.of(new GroupMember("late", "Late", 9, null),
                new GroupMember("early", "Early", 0, null),
                new GroupMember("middle", "Middle", 4, null));
        var phases = List.of(phase("Opinions", PhaseType.OPINION, "ALL"), phase("Wrap-up", PhaseType.SYNTHESIS, "MODERATOR"));

        var gc = runDiscussion(buildConfig(phases, members, null), "gc-nomod", agentId -> "Summary by " + agentId);

        var synthesisEntries = gc.getTranscript().stream()
                .filter(e -> e.type() == TranscriptEntryType.SYNTHESIS).toList();
        assertEquals(1, synthesisEntries.size(), "three synthesizers means the last speaker silently wins");
        assertEquals("early", synthesisEntries.get(0).speakerAgentId());
        assertEquals("Summary by early", gc.getSynthesizedAnswer());
    }
}
