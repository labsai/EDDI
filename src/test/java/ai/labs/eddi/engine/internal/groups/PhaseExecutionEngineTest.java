/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal.groups;

import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ContextScope;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DiscussionPhase;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.GroupMember;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.PhaseType;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ProtocolConfig;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ProtocolConfig.MemberFailurePolicy;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ProtocolConfig.MemberUnavailablePolicy;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.OptionsSource;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.TiePolicy;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.TurnOrder;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.VoteConfig;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.VoteMethod;
import ai.labs.eddi.configs.groups.model.DiscussionStylePresets;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.DecisionRecord;
import ai.labs.eddi.configs.groups.model.GroupConversation.DecisionType;
import ai.labs.eddi.configs.groups.model.GroupConversation.Dissent;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntry;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntryType;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Focused unit tests for {@link PhaseExecutionEngine}, extracted from
 * {@code GroupConversationService} during the Wave R (R1 step 5) refactor.
 * Mocks {@link MemberTurnExecutor} and {@link GroupContextBuilder} rather than
 * constructing real ones — their own behavior is covered by their dedicated
 * test classes; these tests verify the phase-execution turn-order logic
 * (sequential budget/ordering, parallel fan-out/fan-in, peer-targeted
 * self-exclusion) in isolation.
 *
 * @author tests
 */
class PhaseExecutionEngineTest {

    private static final String GROUP_ID = "group-1";

    private MemberTurnExecutor memberTurnExecutor;
    private GroupContextBuilder contextBuilder;

    private PhaseExecutionEngine engine() {
        memberTurnExecutor = mock(MemberTurnExecutor.class);
        contextBuilder = mock(GroupContextBuilder.class);
        // The 7-ARG overload — the only one PhaseExecutionEngine calls. Stubbing the
        // 6-arg one left every turn running with a null input, so a regression that
        // dropped the phase rendering entirely and passed the raw question through
        // would not have failed a single test here.
        when(contextBuilder.buildPhaseInput(any(), any(), any(), any(), anyInt(), any(), any()))
                .thenReturn("rendered-input");
        return new PhaseExecutionEngine(memberTurnExecutor, contextBuilder,
                Executors.newVirtualThreadPerTaskExecutor(), new CallerIdentityContext(null, null));
    }

    private GroupMember member(String id) {
        return new GroupMember(id, id, 1, null);
    }

    private DiscussionPhase phase(TurnOrder turnOrder) {
        return new DiscussionPhase("P", PhaseType.OPINION, "ALL", turnOrder, ContextScope.FULL, false, null, 1, false);
    }

    private ProtocolConfig protocol() {
        return new ProtocolConfig(5, MemberFailurePolicy.SKIP, 0, MemberUnavailablePolicy.SKIP);
    }

    private GroupConversation gc() {
        var g = new GroupConversation();
        g.setId("gc-1");
        g.setGroupId(GROUP_ID);
        return g;
    }

    private TranscriptEntry opinionEntry(String agentId) {
        return new TranscriptEntry(agentId, agentId, "said something", 0, "P", TranscriptEntryType.OPINION, Instant.now(), null, null);
    }

    @Test
    void sequentialPhase_visitsEachSpeaker_inOrder_untilBudgetExhausted() throws Exception {
        var engine = engine();
        when(memberTurnExecutor.executeAgentTurn(any(), any(), any(), any(), anyInt(), any(), any(), any()))
                .thenAnswer(inv -> opinionEntry(((GroupMember) inv.getArgument(0)).agentId()));
        var speakers = List.of(member("a"), member("b"), member("c"));
        var gc = gc();
        var turnCounter = new AtomicInteger(0);

        engine.executeSequentialPhase(gc, new AgentGroupConfiguration(), speakers, phase(TurnOrder.SEQUENTIAL), protocol(), "Q?", 0, null,
                turnCounter, 2);

        // maxTurns=2 caps it at the first two speakers, in order
        assertEquals(2, gc.getTranscript().size());
        assertEquals("a", gc.getTranscript().get(0).speakerAgentId());
        assertEquals("b", gc.getTranscript().get(1).speakerAgentId());
        assertEquals(2, turnCounter.get());
        // eq("rendered-input"), not any(), in the INPUT position. Every verification
        // here used any() there, so nothing observed what the member was actually
        // prompted with — an implementation that skipped GroupContextBuilder and
        // passed the raw question through (no role, no transcript scope, no debate
        // framing) passed this whole class.
        verify(memberTurnExecutor, times(2))
                .executeAgentTurn(any(), eq(gc), eq("rendered-input"), any(), eq(0), any(), isNull(), isNull());
    }

    @Test
    void sequentialPhase_zeroBudget_noTurnsRun() throws Exception {
        var engine = engine();
        var gc = gc();

        engine.executeSequentialPhase(gc, new AgentGroupConfiguration(), List.of(member("a")), phase(TurnOrder.SEQUENTIAL), protocol(), "Q?", 0,
                null, new AtomicInteger(0), 0);

        assertTrue(gc.getTranscript().isEmpty());
        verifyNoInteractions(memberTurnExecutor);
    }

    @Test
    void sequentialPhase_withStartSpeakerIdx_resumesOnlyRemainingSpeakers() throws Exception {
        var engine = engine();
        when(memberTurnExecutor.executeAgentTurn(any(), any(), any(), any(), anyInt(), any(), any(), any()))
                .thenAnswer(inv -> opinionEntry(((GroupMember) inv.getArgument(0)).agentId()));
        var speakers = List.of(member("a"), member("b"), member("c"));
        var gc = gc();
        var turnCounter = new AtomicInteger(0);

        // F2: a pause bookmarked speaker index 1 ("b") — resume must skip "a"
        // entirely (already spoke before the pause) and run only "b" and "c".
        engine.executeSequentialPhase(gc, new AgentGroupConfiguration(), speakers, phase(TurnOrder.SEQUENTIAL), protocol(), "Q?", 0, null,
                turnCounter, 10, 1);

        assertEquals(2, gc.getTranscript().size());
        assertEquals("b", gc.getTranscript().get(0).speakerAgentId());
        assertEquals("c", gc.getTranscript().get(1).speakerAgentId());
        assertEquals(2, turnCounter.get());
        verify(memberTurnExecutor, never()).executeAgentTurn(argThat(m -> "a".equals(m.agentId())), any(), any(), any(), anyInt(), any(), any(),
                any());
    }

    @Test
    void sequentialPhase_startSpeakerIdxAtOrBeyondSize_clampsToNoTurns() throws Exception {
        var engine = engine();
        var gc = gc();
        var turnCounter = new AtomicInteger(0);

        // F2 Javadoc contract: an out-of-range offset (config edited to remove
        // members while paused) clamps to speakers.size() — zero turns, not an
        // IndexOutOfBoundsException. GroupHitlCoordinator's drift guard is what
        // is meant to catch this before it gets here; this only proves the
        // fallback itself is safe.
        engine.executeSequentialPhase(gc, new AgentGroupConfiguration(), List.of(member("a"), member("b")), phase(TurnOrder.SEQUENTIAL),
                protocol(), "Q?", 0, null, turnCounter, 10, 5);

        assertTrue(gc.getTranscript().isEmpty());
        assertEquals(0, turnCounter.get());
        verifyNoInteractions(memberTurnExecutor);
    }

    // =================================================================
    // I1 — cost ceiling gates
    // =================================================================

    private ProtocolConfig protocolWithCeiling(double ceiling) {
        return new ProtocolConfig(5, MemberFailurePolicy.SKIP, 0, MemberUnavailablePolicy.SKIP, 50, ceiling, null);
    }

    @Test
    void sequentialPhase_ceilingAlreadyExceeded_runsNoTurns() throws Exception {
        var engine = engine();
        var gc = gc();
        gc.setTotalCost(5.0);
        var turnCounter = new AtomicInteger(0);

        engine.executeSequentialPhase(gc, new AgentGroupConfiguration(), List.of(member("a"), member("b")), phase(TurnOrder.SEQUENTIAL),
                protocolWithCeiling(1.0), "Q?", 0, null, turnCounter, 10);

        verifyNoInteractions(memberTurnExecutor);
        assertEquals(0, turnCounter.get(), "a turn blocked by the ceiling must not consume turn budget either");
        assertEquals(1, gc.getTranscript().size(), "exactly one SKIPPED entry — the loop breaks, it does not re-check per speaker");
        assertEquals(TranscriptEntryType.SKIPPED, gc.getTranscript().get(0).type());
    }

    @Test
    void sequentialPhase_ceilingExceededMidPhase_stopsAtThatSpeaker() throws Exception {
        var engine = engine();
        var gc = gc();
        // Each turn pushes the running total up; the ceiling trips before speaker 3.
        when(memberTurnExecutor.executeAgentTurn(any(), any(), any(), any(), anyInt(), any(), any(), any()))
                .thenAnswer(inv -> {
                    gc.setTotalCost(gc.getTotalCost() + 0.6);
                    return opinionEntry(((GroupMember) inv.getArgument(0)).agentId());
                });
        var turnCounter = new AtomicInteger(0);

        engine.executeSequentialPhase(gc, new AgentGroupConfiguration(), List.of(member("a"), member("b"), member("c")),
                phase(TurnOrder.SEQUENTIAL), protocolWithCeiling(1.0), "Q?", 0, null, turnCounter, 10);

        // a runs (cost 0 -> 0.6), b runs (0.6 <= 1.0, cost -> 1.2), c is blocked (1.2 >
        // 1.0)
        verify(memberTurnExecutor, times(2)).executeAgentTurn(any(), any(), any(), any(), anyInt(), any(), any(), any());
        assertEquals(2, turnCounter.get());
        var lastEntry = gc.getTranscript().get(gc.getTranscript().size() - 1);
        assertEquals(TranscriptEntryType.SKIPPED, lastEntry.type());
        assertTrue(lastEntry.errorReason().contains("Cost ceiling reached"));
    }

    @Test
    void parallelPhase_ceilingAlreadyExceeded_dispatchesNoBatch() throws Exception {
        var engine = engine();
        var gc = gc();
        gc.setTotalCost(5.0);
        var turnCounter = new AtomicInteger(0);

        engine.executeParallelPhase(gc, new AgentGroupConfiguration(), List.of(member("a"), member("b")), phase(TurnOrder.PARALLEL),
                protocolWithCeiling(1.0), "Q?", 0, null, turnCounter, 10);

        verifyNoInteractions(memberTurnExecutor);
        assertEquals(0, turnCounter.get());
        assertEquals(1, gc.getTranscript().size());
        assertEquals(TranscriptEntryType.SKIPPED, gc.getTranscript().get(0).type());
    }

    @Test
    void peerTargetedPhase_ceilingAlreadyExceeded_runsNoTurns() throws Exception {
        var engine = engine();
        var gc = gc();
        gc.setTotalCost(5.0);
        var a = member("a");
        var b = member("b");
        var config = new AgentGroupConfiguration();
        config.setMembers(List.of(a, b));
        var turnCounter = new AtomicInteger(0);

        engine.executePeerTargetedPhase(gc, config, List.of(a, b), phase(TurnOrder.SEQUENTIAL), protocolWithCeiling(1.0), "Q?", 0, null,
                turnCounter, 10);

        verifyNoInteractions(memberTurnExecutor);
        assertEquals(0, turnCounter.get());
    }

    @Test
    void parallelPhase_allSpeakers_produceEntries() throws Exception {
        var engine = engine();
        when(memberTurnExecutor.executeAgentTurn(any(), any(), any(), any(), anyInt(), any(), any(), any(), any()))
                .thenAnswer(inv -> opinionEntry(((GroupMember) inv.getArgument(0)).agentId()));
        var speakers = List.of(member("a"), member("b"), member("c"));
        var gc = gc();
        var turnCounter = new AtomicInteger(0);

        engine.executeParallelPhase(gc, new AgentGroupConfiguration(), speakers, phase(TurnOrder.PARALLEL), protocol(), "Q?", 0, null, turnCounter,
                10);

        assertEquals(3, gc.getTranscript().size());
        assertEquals(3, turnCounter.get());
        var speakerIds = gc.getTranscript().stream().map(TranscriptEntry::speakerAgentId).sorted().toList();
        assertEquals(List.of("a", "b", "c"), speakerIds);
    }

    @Test
    void parallelPhase_zeroRemainingBudget_noop() throws Exception {
        var engine = engine();
        var gc = gc();
        var turnCounter = new AtomicInteger(5);

        engine.executeParallelPhase(gc, new AgentGroupConfiguration(), List.of(member("a")), phase(TurnOrder.PARALLEL), protocol(), "Q?", 0, null,
                turnCounter, 5);

        assertTrue(gc.getTranscript().isEmpty());
        verifyNoInteractions(memberTurnExecutor);
    }

    @Test
    void peerTargetedPhase_excludesSelf_targetsEveryOtherConfiguredMember() throws Exception {
        var engine = engine();
        when(memberTurnExecutor.executeAgentTurn(any(), any(), any(), any(), anyInt(), any(), any(), any()))
                .thenAnswer(inv -> new TranscriptEntry(((GroupMember) inv.getArgument(0)).agentId(), ((GroupMember) inv.getArgument(0)).agentId(),
                        "said something", 0, "P", TranscriptEntryType.OPINION, Instant.now(), null, (String) inv.getArgument(6)));
        var a = member("a");
        var b = member("b");
        var config = new AgentGroupConfiguration();
        config.setMembers(List.of(a, b));
        var gc = gc();
        var turnCounter = new AtomicInteger(0);

        engine.executePeerTargetedPhase(gc, config, List.of(a, b), phase(TurnOrder.SEQUENTIAL), protocol(), "Q?", 0, null, turnCounter, 10);

        // a→b and b→a: 2 turns, never a speaker targeting themselves
        assertEquals(2, gc.getTranscript().size());
        assertEquals("b", gc.getTranscript().get(0).targetAgentId());
        assertEquals("a", gc.getTranscript().get(1).targetAgentId());
    }

    // =================================================================
    // I3 — recordDebateVerdict
    // =================================================================

    private DiscussionPhase judgmentPhase(String inputTemplate) {
        return new DiscussionPhase("Judgment", PhaseType.SYNTHESIS, "MODERATOR", TurnOrder.SEQUENTIAL,
                ContextScope.FULL, false, inputTemplate, 1, false);
    }

    private GroupConversation gcWithSynthesis(String content) {
        var gc = gc();
        gc.getTranscript().add(new TranscriptEntry("judge", "Judge", content, 1, "Judgment",
                TranscriptEntryType.SYNTHESIS, Instant.now(), null, null));
        return gc;
    }

    private AgentGroupConfiguration debateConfig() {
        var config = new AgentGroupConfiguration();
        config.setMembers(List.of(new GroupMember("pro", "Pro", 1, "PRO"), new GroupMember("con", "Con", 2, "CON")));
        return config;
    }

    private List<GroupMember> judgeAsSpeaker() {
        return List.of(new GroupMember("mod", "Moderator", 0, "MODERATOR"));
    }

    /** Makes the engine's shared predicate report a debate judgment. */
    private void stubIsJudgment(boolean value) {
        when(contextBuilder.isDebateJudgment(any(), any(), any(), anyInt(), any())).thenReturn(value);
    }

    @Test
    void recordDebateVerdict_afterAJudgment_populatesTheDecision() {
        var engine = engine();
        stubIsJudgment(true);
        var gc = gcWithSynthesis("""
                {"winner": "CON", "scores": {"PRO": 4, "CON": 9}, "reasoning": "PRO cited nothing."}""");

        assertTrue(engine.recordDebateVerdict(gc, debateConfig(), judgmentPhase(null), 1, judgeAsSpeaker()));

        assertEquals(DecisionType.VERDICT, gc.getDecision().type());
        assertEquals("CON", gc.getDecision().winner());
    }

    @Test
    void recordDebateVerdict_notAJudgment_writesNoDecisionAtAll() {
        // The engine must not record a "failed parse" for a synthesis that was
        // never asked to produce JSON — that would put a NONE verdict on every
        // ordinary discussion and make a real parse failure indistinguishable.
        var engine = engine();
        stubIsJudgment(false);
        var gc = gcWithSynthesis("Both sides made good points.");

        assertFalse(engine.recordDebateVerdict(gc, debateConfig(), judgmentPhase(null), 1, judgeAsSpeaker()));

        assertNull(gc.getDecision());
    }

    @Test
    void recordDebateVerdict_asksThePredicateWithTheResolvedSynthesizerAndRoster() {
        // The whole point of taking speakers + config rather than re-deriving: this
        // call must see exactly what buildPhaseInput saw, or the two can disagree
        // about whether a verdict was ever requested.
        var engine = engine();
        stubIsJudgment(false);
        var config = debateConfig();
        var speakers = judgeAsSpeaker();
        var gc = gcWithSynthesis("prose");

        engine.recordDebateVerdict(gc, config, judgmentPhase(null), 1, speakers);

        verify(contextBuilder).isDebateJudgment(any(), eq(speakers.get(0)), eq(gc.getTranscript()), eq(1), eq(config.getMembers()));
    }

    @Test
    void recordDebateVerdict_unparseableJudgment_recordsNoneAndKeepsTheText() {
        var engine = engine();
        stubIsJudgment(true);
        var gc = gcWithSynthesis("I award this to PRO, narrowly.");

        assertFalse(engine.recordDebateVerdict(gc, debateConfig(), judgmentPhase(null), 1, judgeAsSpeaker()),
                "a failed parse is not a verdict");

        assertEquals(DecisionType.NONE, gc.getDecision().type());
        assertEquals("I award this to PRO, narrowly.", gc.getDecision().raw());
    }

    @Test
    void recordDebateVerdict_preservesDissentsAlreadyRecorded() {
        // Ordering insurance: the dissent round normally runs after this, but if a
        // config ever produces dissents first they must not be dropped by the
        // verdict replacing the record wholesale.
        var engine = engine();
        stubIsJudgment(true);
        var gc = gcWithSynthesis("""
                {"winner": "PRO"}""");
        var dissent = new Dissent("a", "A", "I disagree");
        gc.setDecision(new DecisionRecord(DecisionType.NONE, null, null, null,
                List.of(dissent), "dissent-round", "Judgment", null));

        engine.recordDebateVerdict(gc, debateConfig(), judgmentPhase(null), 1, judgeAsSpeaker());

        assertEquals(DecisionType.VERDICT, gc.getDecision().type());
        assertEquals(List.of(dissent), gc.getDecision().dissents());
    }

    @Test
    void recordDebateVerdict_noSynthesisEntry_writesNoDecision() {
        // A synthesizer that was skipped, errored, or ran out of turn budget leaves
        // nothing to judge. Recording a phantom NONE record here would also flip
        // recordDissents onto its merge-onto-existing branch.
        var engine = engine();
        stubIsJudgment(true);
        var gc = gc();

        assertFalse(engine.recordDebateVerdict(gc, debateConfig(), judgmentPhase(null), 1, judgeAsSpeaker()));

        assertNull(gc.getDecision());
    }

    @Test
    void recordDebateVerdict_blankSynthesis_writesNoDecision() {
        var engine = engine();
        stubIsJudgment(true);
        var gc = gcWithSynthesis("   ");

        assertFalse(engine.recordDebateVerdict(gc, debateConfig(), judgmentPhase(null), 1, judgeAsSpeaker()));

        assertNull(gc.getDecision());
    }

    @Test
    void recordDebateVerdict_noSpeakers_stillAsksThePredicateWithANullSpeaker() {
        var engine = engine();
        stubIsJudgment(false);
        var gc = gcWithSynthesis("prose");

        assertFalse(engine.recordDebateVerdict(gc, debateConfig(), judgmentPhase(null), 1, List.of()));

        verify(contextBuilder).isDebateJudgment(any(), isNull(), any(), anyInt(), any());
    }

    // =================================================================
    // Branch review — recruits must be first-class in roster-keyed paths
    // =================================================================

    @Test
    void dissentRound_includesRecruitedMembers() throws Exception {
        // A recruit could speak in every phase but was structurally unable to
        // register a minority view, because runDissentRound read the CONFIG roster
        // rather than the effective one. That is the single thing the minority
        // report exists to capture.
        var engine = engine();
        when(memberTurnExecutor.executeAgentTurn(any(), any(), any(), any(), anyInt(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> new TranscriptEntry(((GroupMember) inv.getArgument(0)).agentId(),
                        ((GroupMember) inv.getArgument(0)).agentId(), "I disagree", 1, "Judgment",
                        TranscriptEntryType.OPINION, Instant.now(), null, null));
        var config = new AgentGroupConfiguration();
        config.setMembers(List.of(member("configured")));
        var gc = gcWithSynthesis("A synthesis everyone reacts to.");
        gc.addDynamicMember(new GroupMember("recruit", "Recruit", Integer.MAX_VALUE, null));

        int dissents = engine.runDissentRound(gc, config, judgmentPhase(null), protocol(), 1,
                List.of(member("synthesizer")), null, new AtomicInteger(0), 10);

        assertEquals(2, dissents, "the recruit gets a dissent turn like any other member");
        assertTrue(gc.getTranscript().stream()
                .anyMatch(e -> e.type() == TranscriptEntryType.DISSENT && "recruit".equals(e.speakerAgentId())));
    }

    @Test
    void aLaterRoundNeverAdoptsAnEarlierRoundsConclusion() {
        // Round 2 of a DEBATE whose judge fails (undeployed, timed out, abstained,
        // or the cost ceiling fired) leaves a SKIPPED entry with null content. An
        // unscoped scan then found ROUND 1's judgment and recorded it as round 2's
        // verdict, ran the dissent round against round 1's conclusion, and reported
        // COMPLETED with an answer to a question round 2 never answered.
        var engine = engine();
        stubIsJudgment(true);
        var gc = gcWithSynthesis("""
                {"winner": "PRO", "scores": {"PRO": 9, "CON": 2}}""");
        // A continuation begins here: everything above belongs to round 1.
        gc.setRound(2);
        gc.setRoundStartTranscriptIndex(gc.getTranscript().size());
        gc.getTranscript().add(new TranscriptEntry(null, "System", null, 1, "Judgment",
                TranscriptEntryType.SKIPPED, Instant.now(), "Timeout", null));

        assertFalse(engine.recordDebateVerdict(gc, debateConfig(), judgmentPhase(null), 1, judgeAsSpeaker()));

        assertNull(gc.getDecision(), "round 1's verdict must not be reported as round 2's");
    }

    @Test
    void aFirstRoundStillSeesItsOwnSynthesis() {
        // roundStartTranscriptIndex is 0 for a first round, i.e. the whole
        // transcript — the scoping must not break the ordinary case.
        var engine = engine();
        stubIsJudgment(true);
        var gc = gcWithSynthesis("""
                {"winner": "CON", "scores": {"PRO": 3, "CON": 8}}""");

        assertTrue(engine.recordDebateVerdict(gc, debateConfig(), judgmentPhase(null), 1, judgeAsSpeaker()));
        assertEquals("CON", gc.getDecision().winner());
    }

    @Test
    void peerTargetedDenominatorMatchesTheLoopEvenWithARecruit() {
        // executePeerTargetedPhase iterates config.getMembers() for TARGETS while
        // speakers come from the recruit-inclusive roster. Passing the recruit
        // roster as targets made the denominator 4x4-4=12 where the loop runs
        // 4x3-3=9, so I4's unanimous-abstention exit became arithmetically
        // unreachable for any peer-targeted phase once a recruit existed.
        var config = new AgentGroupConfiguration();
        config.setMembers(List.of(member("a"), member("b"), member("c")));
        var gc = gc();
        gc.addDynamicMember(new GroupMember("recruit", "Recruit", Integer.MAX_VALUE, null));
        var speakers = List.of(member("a"), member("b"), member("c"), member("recruit"));
        var phase = new DiscussionPhase("P", PhaseType.CRITIQUE, "ALL", TurnOrder.SEQUENTIAL,
                ContextScope.FULL, true, null, 1, false, null, true);

        // 9 abstentions is what the loop actually produces; the exit must fire on it.
        var entries = new java.util.ArrayList<TranscriptEntry>();
        for (int i = 0; i < 9; i++) {
            entries.add(new TranscriptEntry("a", "a", null, 0, "P", TranscriptEntryType.ABSTAINED, Instant.now(), null, null));
        }

        var outcome = engine().checkConvergence(gc, config, phase, protocol(), 0, 0, speakers, entries, null, null,
                new AtomicInteger(0), 10);

        assertFalse(outcome.isContinue(), "every scheduled turn abstained — the phase must stop");
    }

    // =================================================================
    // I14 review round 1 — the moderator tiebreak is budget-gated
    // =================================================================

    private DiscussionPhase votePhase() {
        var voteConfig = new VoteConfig(VoteMethod.MAJORITY, OptionsSource.EXPLICIT,
                List.of("Adopt PostgreSQL", "Stay on MongoDB"), 0.5, java.util.Map.of(), false, TiePolicy.MODERATOR_DECIDES);
        return new DiscussionPhase("Ballot", PhaseType.VOTE, "ALL", TurnOrder.PARALLEL, ContextScope.NONE,
                false, null, 1, false, null, false, voteConfig);
    }

    private TranscriptEntry voteEntry(String agentId, String json) {
        return new TranscriptEntry(agentId, agentId, json, 0, "Ballot", TranscriptEntryType.VOTE, Instant.now(), null, null);
    }

    @Test
    void voteTiebreak_turnBudgetExhausted_keepsNoDecision_andSpendsNothing() throws Exception {
        var engine = engine();
        var config = new AgentGroupConfiguration();
        config.setModeratorAgentId("mod");
        var ballots = List.of(
                voteEntry("a", "{\"vote\": \"Adopt PostgreSQL\"}"),
                voteEntry("b", "{\"vote\": \"Stay on MongoDB\"}"));
        var turnCounter = new AtomicInteger(5);

        engine.recordVoteDecision(gc(), config, votePhase(), protocol(), 0, ballots,
                List.of(member("a"), member("b")), null, turnCounter, 5);

        verify(memberTurnExecutor, never()).executeAgentTurn(any(), any(), any(), any(), anyInt(), any(), any(), any(), any(), any());
        assertEquals(5, turnCounter.get(), "a blocked tiebreak must not consume a turn");
    }

    @Test
    void voteTiebreak_withinBudget_countsItsTurn_andCarriesTheLosersDissent() throws Exception {
        var engine = engine();
        var config = new AgentGroupConfiguration();
        config.setModeratorAgentId("mod");
        when(memberTurnExecutor.executeAgentTurn(any(), any(), any(), any(), anyInt(), any(), any(), any(), any(), any()))
                .thenReturn(voteEntry("mod", "Adopt PostgreSQL"));
        var ballots = List.of(
                voteEntry("a", "{\"vote\": \"Adopt PostgreSQL\", \"statement\": \"pgvector\"}"),
                voteEntry("b", "{\"vote\": \"Stay on MongoDB\", \"statement\": \"migration risk is real\"}"));
        var gc = gc();
        var turnCounter = new AtomicInteger(0);

        engine.recordVoteDecision(gc, config, votePhase(), protocol(), 0, ballots,
                List.of(member("a"), member("b")), null, turnCounter, 10);

        assertEquals(1, turnCounter.get(), "the tiebreak is a real LLM turn and must be counted");
        DecisionRecord decision = gc.getDecision();
        assertEquals(DecisionType.VOTE, decision.type());
        assertEquals("Adopt PostgreSQL", decision.winner());
        assertEquals(VoteTallyEngine.METHOD_TIEBREAK, decision.method());
        // The review defect: reusing the unresolved record's empty dissent list
        // dropped the minority report for exactly the closest votes.
        assertEquals(1, decision.dissents().size(), "the losing side's statement must survive the tiebreak");
        assertEquals("migration risk is real", decision.dissents().get(0).position());
    }
}
