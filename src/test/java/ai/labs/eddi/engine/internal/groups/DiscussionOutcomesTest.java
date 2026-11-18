/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal.groups;

import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ContextScope;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DiscussionPhase;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.PhaseType;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.TurnOrder;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.DiscussionOutcome;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntry;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntryType;
import ai.labs.eddi.configs.groups.model.GroupConversation.TurnOutcome;
import ai.labs.eddi.configs.groups.model.DiscussionStylePresets;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DiscussionStyle;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How a round ends (K1, K3) and how the turn cap keeps the synthesis turn.
 */
class DiscussionOutcomesTest {

    private static DiscussionPhase phase(String name, PhaseType type, int repeats) {
        return new DiscussionPhase(name, type, "ALL", TurnOrder.SEQUENTIAL, ContextScope.FULL, false, null, repeats, false);
    }

    private static final List<DiscussionPhase> OPINION_THEN_SYNTHESIS = List.of(phase("Opinions", PhaseType.OPINION, 1),
            phase("Synthesis", PhaseType.SYNTHESIS, 1));

    private static GroupConversation gc(TranscriptEntry... entries) {
        var gc = new GroupConversation();
        gc.setId("gc-1");
        gc.getTranscript().add(new TranscriptEntry("user", "User", "Q?", -1, "Question", TranscriptEntryType.QUESTION, Instant.now(), null,
                null));
        for (var e : entries) {
            gc.getTranscript().add(e);
        }
        return gc;
    }

    private static TranscriptEntry opinion(String agent) {
        return new TranscriptEntry(agent, agent, "my view", 0, "Opinions", TranscriptEntryType.OPINION, Instant.now(), null, null);
    }

    private static TranscriptEntry failed(String agent, int phaseIdx, String phaseName) {
        return TranscriptEntry.notAnswered(agent, agent, phaseIdx, phaseName, TranscriptEntryType.SKIPPED, TurnOutcome.FAILED,
                MemberTurnExecutor.REASON_ERROR_STATE, null);
    }

    @Test
    void everyMemberFailed_isAFailedRoundWithoutAnswer() {
        var gc = gc(failed("a", 0, "Opinions"), failed("b", 0, "Opinions"), failed("mod", 1, "Synthesis"));
        gc.setSynthesizedAnswer("should not survive");

        DiscussionOutcomes.settle(gc, OPINION_THEN_SYNTHESIS, false);

        assertEquals(DiscussionOutcome.FAILED, gc.getOutcome());
        assertEquals(DiscussionOutcomes.NO_MEMBER_ANSWERED, gc.getOutcomeReason());
        assertNull(gc.getSynthesizedAnswer());
    }

    /** M1: the old placeholder is a failed turn, not a contribution. */
    @Test
    void legacyPlaceholder_isNotAContribution() throws Exception {
        String legacy = """
                {"transcript":[{"speakerAgentId":"b","speakerDisplayName":"B","phaseIndex":0,"phaseName":"Opinions","type":"OPINION",
                "content":"[Agent failed to produce output — conversation entered ERROR state]"}],
                "synthesizedAnswer":"[Agent failed to produce output — conversation entered ERROR state]"}
                """;
        var gc = new ObjectMapper().findAndRegisterModules().readValue(legacy, GroupConversation.class);

        assertEquals(TurnOutcome.FAILED, gc.getTranscript().getFirst().outcome());
        assertNull(gc.getTranscript().getFirst().content());
        assertNull(gc.getSynthesizedAnswer(), "a stored placeholder answer is no answer");

        DiscussionOutcomes.settle(gc, OPINION_THEN_SYNTHESIS, false);

        assertEquals(DiscussionOutcome.FAILED, gc.getOutcome());
        assertEquals(DiscussionOutcomes.NO_MEMBER_ANSWERED, gc.getOutcomeReason());
    }

    /** M2: a NONE decision (dissents only, no quorum, a tie) is not an answer. */
    @Test
    void noneDecision_isNotAnAnswer() {
        var gc = gc(opinion("a"), failed("mod", 1, "Synthesis"));
        gc.setDecision(new GroupConversation.DecisionRecord(GroupConversation.DecisionType.NONE, "no decision", null, null, List.of(),
                "dissent", "Synthesis", null));

        DiscussionOutcomes.settle(gc, OPINION_THEN_SYNTHESIS, false);

        assertEquals(DiscussionOutcome.NO_ANSWER, gc.getOutcome());
        assertEquals(DiscussionOutcomes.SYNTHESIS_FAILED, gc.getOutcomeReason());
    }

    @Test
    void decidingDecision_isAnAnswer() {
        var gc = gc(opinion("a"));
        gc.setDecision(new GroupConversation.DecisionRecord(GroupConversation.DecisionType.VOTE, "A wins", "A", null, List.of(), "majority",
                "Vote", null));

        DiscussionOutcomes.settle(gc, OPINION_THEN_SYNTHESIS, false);

        assertEquals(DiscussionOutcome.ANSWERED, gc.getOutcome());
    }

    /**
     * S7: members all skipped and the synthesis failed — the synthesis is the
     * reason.
     */
    @Test
    void membersSkippedAndSynthesisFailed_isSynthesisFailed() {
        var gc = gc(new TranscriptEntry("a", "a", null, 0, "Opinions", TranscriptEntryType.SKIPPED, Instant.now(),
                MemberTurnExecutor.REASON_NOT_DEPLOYED, null), failed("mod", 1, "Synthesis"));

        DiscussionOutcomes.settle(gc, OPINION_THEN_SYNTHESIS, false);

        assertEquals(DiscussionOutcome.NO_ANSWER, gc.getOutcome());
        assertEquals(DiscussionOutcomes.SYNTHESIS_FAILED, gc.getOutcomeReason());
    }

    /** S7: everyone skipped by the turn cap or an inherited budget of 0. */
    @Test
    void everyoneSkippedByTheTurnBudget_isTurnLimitReached() {
        var gc = gc(new TranscriptEntry("sub", "Sub", null, 0, "Opinions", TranscriptEntryType.SKIPPED, Instant.now(),
                MemberTurnExecutor.REASON_TURN_BUDGET, null));

        DiscussionOutcomes.settle(gc, OPINION_THEN_SYNTHESIS, false);

        assertEquals(DiscussionOutcome.NO_ANSWER, gc.getOutcome());
        assertEquals(DiscussionOutcomes.TURN_LIMIT_REACHED, gc.getOutcomeReason());
    }

    /** M3: only a discussion that never answered ends FAILED. */
    @Test
    void failedLaterRound_doesNotFailTheDiscussion() {
        var gc = gc(failed("a", 0, "Opinions"));
        gc.setAnsweredRounds(1);
        gc.setRound(2);

        DiscussionOutcomes.settle(gc, OPINION_THEN_SYNTHESIS, false);

        assertEquals(DiscussionOutcome.FAILED, gc.getOutcome());
        assertFalse(DiscussionOutcomes.failsDiscussion(gc), "round 1 answered: the discussion stays COMPLETED");

        var first = gc(failed("a", 0, "Opinions"));
        first.setAnsweredRounds(0);
        DiscussionOutcomes.settle(first, OPINION_THEN_SYNTHESIS, false);
        assertTrue(DiscussionOutcomes.failsDiscussion(first));

        var legacyContinued = gc(failed("a", 0, "Opinions"));
        legacyContinued.setRound(3);
        DiscussionOutcomes.settle(legacyContinued, OPINION_THEN_SYNTHESIS, false);
        assertFalse(DiscussionOutcomes.failsDiscussion(legacyContinued), "a continued legacy document had answered rounds");
    }

    @Test
    void everyMemberSkipped_isNoAnswerNotAFailure() {
        var gc = gc(new TranscriptEntry("a", "a", null, 0, "Opinions", TranscriptEntryType.SKIPPED, Instant.now(),
                MemberTurnExecutor.REASON_NOT_DEPLOYED, null));

        DiscussionOutcomes.settle(gc, OPINION_THEN_SYNTHESIS, false);

        assertEquals(DiscussionOutcome.NO_ANSWER, gc.getOutcome());
        assertEquals(DiscussionOutcomes.NO_MEMBER_ANSWERED, gc.getOutcomeReason());
    }

    @Test
    void healthyMembersButFailedSynthesis_isNoAnswer() {
        var gc = gc(opinion("a"), opinion("b"), failed("mod", 1, "Synthesis"));

        DiscussionOutcomes.settle(gc, OPINION_THEN_SYNTHESIS, false);

        assertEquals(DiscussionOutcome.NO_ANSWER, gc.getOutcome());
        assertEquals(DiscussionOutcomes.SYNTHESIS_FAILED, gc.getOutcomeReason());
    }

    @Test
    void synthesisPresent_isAnswered() {
        var gc = gc(opinion("a"), failed("b", 0, "Opinions"));
        gc.setSynthesizedAnswer("the answer");

        DiscussionOutcomes.settle(gc, OPINION_THEN_SYNTHESIS, false);

        assertEquals(DiscussionOutcome.ANSWERED, gc.getOutcome());
        assertNull(gc.getOutcomeReason());
        assertEquals("the answer", gc.getSynthesizedAnswer());
    }

    @Test
    void turnCapStoppedBeforeTheSynthesis_isNoAnswerWithTheReason() {
        var gc = gc(opinion("a"));
        TurnBudget.noteCut(gc, 0, "Opinions");

        DiscussionOutcomes.settle(gc, OPINION_THEN_SYNTHESIS, false);

        assertEquals(DiscussionOutcome.NO_ANSWER, gc.getOutcome());
        assertEquals(DiscussionOutcomes.TURN_LIMIT_REACHED, gc.getOutcomeReason());
    }

    @Test
    void costCeilingWithAnAnswer_isAnsweredButSaysItWasCutShort() {
        var gc = gc(opinion("a"));
        gc.setSynthesizedAnswer("partial answer");

        DiscussionOutcomes.settle(gc, OPINION_THEN_SYNTHESIS, true);

        assertEquals(DiscussionOutcome.ANSWERED, gc.getOutcome());
        assertEquals(DiscussionOutcomes.COST_CEILING_REACHED, gc.getOutcomeReason());
    }

    @Test
    void noSynthesisPhaseConfigured_isAnsweredOnceAMemberContributed() {
        var gc = gc(opinion("a"));

        DiscussionOutcomes.settle(gc, List.of(phase("Opinions", PhaseType.OPINION, 2)), false);

        assertEquals(DiscussionOutcome.ANSWERED, gc.getOutcome());
    }

    @Test
    void onlyThisRoundCounts() {
        var gc = gc(opinion("a"));
        gc.setRoundStartTranscriptIndex(gc.getTranscript().size());
        gc.getTranscript().add(failed("a", 0, "Opinions"));

        DiscussionOutcomes.settle(gc, OPINION_THEN_SYNTHESIS, false);

        assertEquals(DiscussionOutcome.FAILED, gc.getOutcome(), "round 1's opinion does not answer round 2");
    }

    // --- TurnBudget ---

    /** The live finding GR2: 10 members × maxRounds 5 = the default 50 turns. */
    @Test
    void defaultTurnCap_keepsOneTurnForTheSynthesis() {
        var phases = DiscussionStylePresets.expand(DiscussionStyle.ROUND_TABLE, 5);
        int last = phases.size() - 1;

        assertEquals(PhaseType.SYNTHESIS, phases.get(last).type());
        assertEquals(50, TurnBudget.phaseCap(50, phases, last), "the synthesis phase has the whole budget");
        for (int i = 0; i < last; i++) {
            assertEquals(49, TurnBudget.phaseCap(50, phases, i), "work phases stop one turn before the cap");
        }
    }

    @Test
    void phaseCap_reservesOneTurnPerRemainingSynthesis_neverBelowOne() {
        var phases = List.of(phase("A", PhaseType.OPINION, 1), phase("S1", PhaseType.SYNTHESIS, 1), phase("B", PhaseType.OPINION, 1),
                phase("S2", PhaseType.SYNTHESIS, 1));

        assertEquals(8, TurnBudget.phaseCap(10, phases, 0));
        assertEquals(9, TurnBudget.phaseCap(10, phases, 2));
        assertEquals(1, TurnBudget.phaseCap(1, phases, 0), "a one-turn budget is not reserved away");
    }

    @Test
    void noteCut_isWrittenOncePerRound() {
        var gc = gc();
        assertFalse(TurnBudget.wasCut(gc));

        TurnBudget.noteCut(gc, 0, "Opinions");
        TurnBudget.noteCut(gc, 1, "Synthesis");

        assertTrue(TurnBudget.wasCut(gc));
        assertEquals(1, gc.getTranscript().stream().filter(e -> e.errorReason() != null
                && e.errorReason().startsWith(TurnBudget.CUT_NOTE_PREFIX)).count());
    }
}
