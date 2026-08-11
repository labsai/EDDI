/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal.groups;

import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ConvergenceConfig;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntry;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntryType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ConvergenceDetector} (I2) — the judgment logic, isolated
 * from the LLM call and the discussion loop.
 * <p>
 * The parse tests are the important ones. Every failure mode must land on "not
 * converged": converging on a verdict we could not read would silently truncate
 * a discussion the operator asked for and paid for, whereas failing to converge
 * costs one extra round. Each case below pins one way the judge can misbehave.
 *
 * @author tests
 */
class ConvergenceDetectorTest {

    private TranscriptEntry entry(String agentId, String content, TranscriptEntryType type) {
        return new TranscriptEntry(agentId, agentId, content, 0, "P", type, Instant.now(), null, null);
    }

    // =================================================================
    // Deterministic: all participants abstained
    // =================================================================

    @Test
    void allAbstained_matchingParticipantCount_converges() {
        var entries = List.of(
                entry("a", null, TranscriptEntryType.ABSTAINED),
                entry("b", null, TranscriptEntryType.ABSTAINED));

        assertTrue(ConvergenceDetector.allParticipantsAbstained(entries, 2));
    }

    @Test
    void someAbstained_doesNotConverge() {
        var entries = List.of(
                entry("a", null, TranscriptEntryType.ABSTAINED),
                entry("b", "I still disagree", TranscriptEntryType.OPINION));

        assertFalse(ConvergenceDetector.allParticipantsAbstained(entries, 2));
    }

    @Test
    void abstentionsFewerThanParticipants_doesNotConverge() {
        // The slice can be short — an errored member turn produces no entry, and a
        // mid-phase HITL resume only covers this leg. "Every entry we happen to see
        // is a PASS" is not unanimity among 3 members.
        var entries = List.of(entry("a", null, TranscriptEntryType.ABSTAINED));

        assertFalse(ConvergenceDetector.allParticipantsAbstained(entries, 3),
                "1 abstention out of 3 participants must not read as unanimous");
    }

    @Test
    void zeroParticipants_neverConverges() {
        assertFalse(ConvergenceDetector.allParticipantsAbstained(List.of(), 0),
                "an empty roster cannot be unanimous — a misconfigured phase must not exit as though it achieved something");
    }

    @Test
    void nullOrEmptyEntries_doNotConverge() {
        assertFalse(ConvergenceDetector.allParticipantsAbstained(null, 2));
        assertFalse(ConvergenceDetector.allParticipantsAbstained(List.of(), 2));
    }

    // =================================================================
    // Semantic: judge verdict parsing
    // =================================================================

    @Test
    void cleanJson_aboveThreshold_converges() {
        var verdict = ConvergenceDetector.parseJudgeVerdict(
                "{\"agreementScore\": 0.92, \"converged\": true, \"summary\": \"Both now favor option B\"}", 0.8);

        assertTrue(verdict.converged());
        assertEquals(0.92, verdict.agreementScore(), 1e-9);
        assertTrue(verdict.summary().contains("Both now favor option B"));
    }

    @Test
    void scoreExactlyAtThreshold_converges() {
        // An operator configuring 0.8 means "0.8 is good enough".
        var verdict = ConvergenceDetector.parseJudgeVerdict("{\"agreementScore\": 0.8, \"converged\": false}", 0.8);

        assertTrue(verdict.converged(), "the threshold is inclusive");
    }

    @Test
    void scoreBelowThreshold_doesNotConverge() {
        var verdict = ConvergenceDetector.parseJudgeVerdict("{\"agreementScore\": 0.79, \"converged\": true}", 0.8);

        assertFalse(verdict.converged(),
                "the operator's threshold is authoritative — a judge's own 'converged: true' must not override it");
    }

    @Test
    void jsonWrappedInProseOrFence_isStillParsed() {
        var verdict = ConvergenceDetector.parseJudgeVerdict(
                "Here is my assessment:\n```json\n{\"agreementScore\": 0.9, \"converged\": true}\n```\nHope that helps!", 0.8);

        assertTrue(verdict.converged(), "tier 2 brace extraction must handle a chatty judge");
    }

    @Test
    void unparseableOutput_doesNotConverge() {
        var verdict = ConvergenceDetector.parseJudgeVerdict("They seem to agree, mostly.", 0.8);

        assertFalse(verdict.converged());
        assertEquals(-1, verdict.agreementScore());
    }

    @Test
    void missingScore_doesNotConverge() {
        var verdict = ConvergenceDetector.parseJudgeVerdict("{\"converged\": true, \"summary\": \"all agreed\"}", 0.8);

        assertFalse(verdict.converged(), "a 'converged' flag with no score is not a verdict we can apply a threshold to");
    }

    @Test
    void nonNumericScore_doesNotConverge() {
        var verdict = ConvergenceDetector.parseJudgeVerdict("{\"agreementScore\": \"high\", \"converged\": true}", 0.8);

        assertFalse(verdict.converged());
    }

    @Test
    void outOfRangeScore_doesNotConverge() {
        // A returned 4.2 is as likely to be a 0-10 scale as a runaway 1.0 — clamping
        // would invent a verdict the judge did not give.
        var verdict = ConvergenceDetector.parseJudgeVerdict("{\"agreementScore\": 4.2, \"converged\": true}", 0.8);

        assertFalse(verdict.converged());
        assertFalse(verdict.summary().isBlank());
    }

    @Test
    void negativeScore_doesNotConverge() {
        var verdict = ConvergenceDetector.parseJudgeVerdict("{\"agreementScore\": -0.5}", 0.8);

        assertFalse(verdict.converged());
    }

    @Test
    void nullOrBlankOutput_doesNotConverge() {
        assertFalse(ConvergenceDetector.parseJudgeVerdict(null, 0.8).converged());
        assertFalse(ConvergenceDetector.parseJudgeVerdict("   ", 0.8).converged());
    }

    @Test
    void singleObjectWrappedInAnArray_isRecovered() {
        // Tier 2 exists to recover the verdict object from whatever the judge
        // wrapped it in — a markdown fence, prose, or (here) a one-element array.
        // The score is unambiguous; rejecting it would cost a round for a
        // formatting quirk. Contrast with the multi-verdict case below.
        var verdict = ConvergenceDetector.parseJudgeVerdict("[{\"agreementScore\": 0.9}]", 0.8);

        assertTrue(verdict.converged());
    }

    @Test
    void multipleVerdictObjects_doNotConverge() {
        // The dangerous shape: which verdict would we be applying? Brace extraction
        // spans the first '{' to the last '}', which is not valid JSON here, so it
        // fails to parse and lands on "not converged" — the safe default.
        var verdict = ConvergenceDetector.parseJudgeVerdict(
                "[{\"agreementScore\": 0.9}, {\"agreementScore\": 0.1}]", 0.8);

        assertFalse(verdict.converged(),
                "an ambiguous multi-verdict response must never pick one and truncate the discussion");
    }

    // =================================================================
    // Judge prompt
    // =================================================================

    @Test
    void judgeInput_carriesBothRoundsAndTheAntiSycophancyRule() {
        var previous = List.of(entry("a", "I prefer option A", TranscriptEntryType.OPINION));
        var current = List.of(entry("a", "I now prefer option B", TranscriptEntryType.OPINION));

        String input = ConvergenceDetector.buildJudgeInput(previous, current);

        assertTrue(input.contains("I prefer option A"));
        assertTrue(input.contains("I now prefer option B"));
        assertTrue(input.contains("politeness"),
                "the anti-sycophancy instruction is the point of the template — without it a judge scores courtesy as agreement");
    }

    @Test
    void judgeInput_emptyRound_rendersAPlaceholderRatherThanNothing() {
        String input = ConvergenceDetector.buildJudgeInput(List.of(), List.of());

        assertTrue(input.contains("(no contributions)"));
    }

    @Test
    void judgeInput_skipsEntriesWithoutContent() {
        var current = List.of(
                entry("a", null, TranscriptEntryType.ABSTAINED),
                entry("b", "a real position", TranscriptEntryType.OPINION));

        String input = ConvergenceDetector.buildJudgeInput(List.of(), current);

        assertTrue(input.contains("a real position"));
        assertFalse(input.contains("null"), "a content-less entry must not render as the literal 'null'");
    }

    // =================================================================
    // ConvergenceConfig normalization
    // =================================================================

    @Test
    void config_normalizesPartialSettings() {
        // The common case: JSON naming only 'enabled'.
        var config = new ConvergenceConfig(true, 0, 0, null);

        assertEquals(ConvergenceConfig.MIN_COMPARABLE_REPEATS, config.minRepeats(),
                "minRepeats below 2 is meaningless — there is no previous round to compare against");
        assertEquals(ConvergenceConfig.DEFAULT_THRESHOLD, config.threshold());
        assertEquals(ConvergenceConfig.JUDGE_MODERATOR, config.judge());
    }

    @Test
    void config_preservesValidSettings() {
        var config = new ConvergenceConfig(true, 4, 0.65, "service");

        assertEquals(4, config.minRepeats());
        assertEquals(0.65, config.threshold());
        assertEquals(ConvergenceConfig.JUDGE_SERVICE, config.judge(), "judge is normalized to upper case");
    }

    @Test
    void config_rejectsOutOfRangeThreshold() {
        assertEquals(ConvergenceConfig.DEFAULT_THRESHOLD, new ConvergenceConfig(true, 2, 1.5, null).threshold());
        assertEquals(ConvergenceConfig.DEFAULT_THRESHOLD, new ConvergenceConfig(true, 2, -1, null).threshold());
    }
}
