/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal.groups;

import ai.labs.eddi.configs.groups.model.GroupConversation.DecisionRecord;
import ai.labs.eddi.configs.groups.model.GroupConversation.DecisionType;
import ai.labs.eddi.configs.groups.model.GroupConversation.Dissent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link DebateVerdictParser} (I3).
 * <p>
 * The failure cases carry the weight. A verdict is the one part of a discussion
 * a caller is expected to branch on, so a parser that guesses — picking the
 * first of two verdicts, clamping a nonsense score, reading an unrecognized
 * winner as a side — produces a confident, wrong, machine-readable answer where
 * prose would at least have been honest about being prose.
 *
 * @author tests
 */
class DebateVerdictParserTest {

    private static final String PHASE = "Judgment";

    // =================================================================
    // Happy path
    // =================================================================

    @Test
    void cleanJson_producesVerdict() {
        String json = """
                {"winner": "PRO", "scores": {"PRO": 8, "CON": 5}, "reasoning": "Better evidence."}""";

        DecisionRecord decision = DebateVerdictParser.parse(json, PHASE, null);

        assertEquals(DecisionType.VERDICT, decision.type());
        assertEquals("PRO", decision.winner());
        assertEquals(DebateVerdictParser.METHOD, decision.method());
        assertEquals(PHASE, decision.decidedAtPhase());
        assertEquals(8.0, decision.tally().get("PRO"));
        assertEquals(5.0, decision.tally().get("CON"));
        assertEquals("PRO wins (PRO 8/10, CON 5/10) — Better evidence.", decision.outcome());
        // The judge's own words are kept whatever the parse did with them.
        assertEquals(json, decision.raw());
    }

    @Test
    void tie_hasNoWinnerButSaysSoInTheOutcome() {
        // DecisionRecord's contract: winner is null for a tie. The tie must not
        // become invisible as a result — a null winner alone reads the same as a
        // failed parse.
        DecisionRecord decision = DebateVerdictParser.parse(
                """
                        {"winner": "TIE", "scores": {"PRO": 7, "CON": 7}, "reasoning": "Evenly matched."}""", PHASE, null);

        assertEquals(DecisionType.VERDICT, decision.type());
        assertNull(decision.winner());
        assertTrue(decision.outcome().startsWith("Tie ("), decision.outcome());
    }

    @Test
    void lowercaseAndPaddedWinner_isNormalized() {
        DecisionRecord decision = DebateVerdictParser.parse("""
                {"winner": " con "}""", PHASE, null);

        assertEquals(DecisionType.VERDICT, decision.type());
        assertEquals("CON", decision.winner());
    }

    @Test
    void fractionalScores_renderWithoutTrailingZeroNoise() {
        DecisionRecord decision = DebateVerdictParser.parse("""
                {"winner": "PRO", "scores": {"PRO": 7.5, "CON": 6}}""", PHASE, null);

        // "7.5/10, CON 6/10" — not "7.5/10, CON 6.0/10", and locale-independent
        // (a comma decimal separator here would be a defect on a German JVM).
        assertEquals("PRO wins (PRO 7.5/10, CON 6/10)", decision.outcome());
    }

    // =================================================================
    // Tier 2 — JSON embedded in prose or a fence
    // =================================================================

    @Test
    void jsonInsideMarkdownFence_isExtracted() {
        DecisionRecord decision = DebateVerdictParser.parse("""
                Here is my judgment:
                ```json
                {"winner": "CON", "scores": {"PRO": 3, "CON": 9}, "reasoning": "PRO cited nothing."}
                ```
                Hope that helps!""", PHASE, null);

        assertEquals(DecisionType.VERDICT, decision.type());
        assertEquals("CON", decision.winner());
        assertEquals(9.0, decision.tally().get("CON"));
    }

    // =================================================================
    // Tier 3 — prose-only fallbacks
    // =================================================================

    @Test
    void prose_fallsBackToNoneKeepingTheText() {
        String prose = "After weighing both sides, I think PRO made the stronger case.";

        DecisionRecord decision = DebateVerdictParser.parse(prose, PHASE, null);

        assertEquals(DecisionType.NONE, decision.type());
        assertNull(decision.winner());
        assertNull(decision.outcome());
        // Losing the source material would leave the discussion with no conclusion
        // at all — worse than the prose it started with.
        assertEquals(prose, decision.raw());
    }

    @Test
    void garbage_fallsBackToNone() {
        assertEquals(DecisionType.NONE, DebateVerdictParser.parse("}{ not json at all {", PHASE, null).type());
        assertEquals(DecisionType.NONE, DebateVerdictParser.parse("", PHASE, null).type());
        assertEquals(DecisionType.NONE, DebateVerdictParser.parse(null, PHASE, null).type());
    }

    @Test
    void unrecognizedWinner_fallsBackToNone() {
        // "AGENT-A" is not one of the three contract values. Recording it as a
        // winner would put a side into the audit record that the debate never had.
        DecisionRecord decision = DebateVerdictParser.parse("""
                {"winner": "AGENT-A", "reasoning": "They argued well."}""", PHASE, null);

        assertEquals(DecisionType.NONE, decision.type());
    }

    @Test
    void missingWinner_fallsBackToNone() {
        DecisionRecord decision = DebateVerdictParser.parse("""
                {"scores": {"PRO": 8, "CON": 5}, "reasoning": "Close one."}""", PHASE, null);

        assertEquals(DecisionType.NONE, decision.type());
    }

    @Test
    void twoVerdictsInOneResponse_areNotResolvedByPosition() {
        // FAIL_ON_TRAILING_TOKENS. Jackson's default parses the first value and
        // discards the rest, which would silently award the debate to whichever
        // verdict the model happened to emit first.
        DecisionRecord decision = DebateVerdictParser.parse("""
                {"winner": "PRO"} {"winner": "CON"}""", PHASE, null);

        assertEquals(DecisionType.NONE, decision.type());
    }

    // =================================================================
    // Scores
    // =================================================================

    @Test
    void outOfRangeScore_dropsTheScoreboardNotTheVerdict() {
        DecisionRecord decision = DebateVerdictParser.parse("""
                {"winner": "PRO", "scores": {"PRO": 87, "CON": 5}, "reasoning": "Clear."}""", PHASE, null);

        assertEquals(DecisionType.VERDICT, decision.type(), "a bad score must not cost the verdict");
        assertEquals("PRO", decision.winner());
        assertNull(decision.tally());
        assertEquals("PRO wins — Clear.", decision.outcome());
    }

    @Test
    void halfAScoreboard_isNoScoreboard() {
        // "PRO wins (PRO 8/10)" invites the reader to assume CON scored lower when
        // the judge never said so.
        DecisionRecord decision = DebateVerdictParser.parse("""
                {"winner": "PRO", "scores": {"PRO": 8}}""", PHASE, null);

        assertEquals(DecisionType.VERDICT, decision.type());
        assertNull(decision.tally());
        assertEquals("PRO wins", decision.outcome());
    }

    @Test
    void nonNumericScore_isIgnored() {
        DecisionRecord decision = DebateVerdictParser.parse("""
                {"winner": "CON", "scores": {"PRO": "high", "CON": 9}}""", PHASE, null);

        assertEquals(DecisionType.VERDICT, decision.type());
        assertNull(decision.tally());
    }

    @Test
    void scoreBoundsAreInclusive() {
        // 0 and 10 are exactly the endpoints the template asks the judge for, so a
        // decisive verdict is the most likely place an exclusive bound would show
        // up — and because half a scoreboard is dropped entirely, one bad endpoint
        // silently deletes both scores.
        DecisionRecord decision = DebateVerdictParser.parse("""
                {"winner": "PRO", "scores": {"PRO": 10, "CON": 0}}""", PHASE, null);

        assertEquals(DecisionType.VERDICT, decision.type());
        assertEquals(10.0, decision.tally().get("PRO"));
        assertEquals(0.0, decision.tally().get("CON"));
        assertEquals("PRO wins (PRO 10/10, CON 0/10)", decision.outcome());
    }

    @Test
    void lowercaseScoreKeys_areReadNotDropped() {
        // normalizeWinner already accepts "pro", so a judge writing lowercase
        // throughout is one consistent style. Accepting half of it used to drop the
        // whole scoreboard while keeping the winner.
        DecisionRecord decision = DebateVerdictParser.parse("""
                {"winner": "pro", "scores": {"pro": 8, "con": 6}, "reasoning": "Clear."}""", PHASE, null);

        assertEquals("PRO", decision.winner());
        assertEquals(8.0, decision.tally().get("PRO"));
        assertEquals(6.0, decision.tally().get("CON"));
    }

    @Test
    void scoresContradictingTheWinner_keepTheDeclaredWinner() {
        // The judge answered the question asked; the scoreboard is supporting
        // detail. Silently flipping the winner to match the numbers would override
        // an explicit judgment with an inference.
        DecisionRecord decision = DebateVerdictParser.parse("""
                {"winner": "PRO", "scores": {"PRO": 2, "CON": 9}}""", PHASE, null);

        assertEquals("PRO", decision.winner());
        assertEquals(2.0, decision.tally().get("PRO"));
    }

    // =================================================================
    // Dissents carried across
    // =================================================================

    @Test
    void existingDissents_surviveBothParseOutcomes() {
        var dissents = List.of(new Dissent("a", "A", "I disagree with the framing"));

        assertEquals(dissents, DebateVerdictParser.parse("""
                {"winner": "PRO"}""", PHASE, dissents).dissents());
        assertEquals(dissents, DebateVerdictParser.parse("not json", PHASE, dissents).dissents());
    }

    @Test
    void nullDissents_becomeEmptyNotNull() {
        assertEquals(List.of(), DebateVerdictParser.parse("""
                {"winner": "PRO"}""", PHASE, null).dissents());
    }

    // =================================================================
    // isRenderedFrom — the answer-substitution guard
    // =================================================================

    @Test
    void isRenderedFrom_matchesOnlyItsOwnSourceText() {
        String json = """
                {"winner": "PRO", "reasoning": "Solid."}""";
        DecisionRecord decision = DebateVerdictParser.parse(json, PHASE, null);

        assertTrue(DebateVerdictParser.isRenderedFrom(decision, json));
        // A later SYNTHESIS phase superseded the judgment: its own words must be
        // the answer, not a verdict rendered from an earlier phase's text.
        assertFalse(DebateVerdictParser.isRenderedFrom(decision, "A later, plain-prose synthesis."));
    }

    @Test
    void isRenderedFrom_rejectsNonVerdicts() {
        assertFalse(DebateVerdictParser.isRenderedFrom(null, "anything"));

        String prose = "No JSON here.";
        assertFalse(DebateVerdictParser.isRenderedFrom(DebateVerdictParser.parse(prose, PHASE, null), prose),
                "a NONE record has no outcome to substitute");

        var foreign = new DecisionRecord(DecisionType.VERDICT, "Someone wins", "PRO", null, List.of(),
                "some-other-mechanism", PHASE, "raw");
        assertFalse(DebateVerdictParser.isRenderedFrom(foreign, "raw"),
                "another feature's VERDICT is not this parser's to render");
    }
}
