/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal.groups;

import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.OptionsSource;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.TiePolicy;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.VoteConfig;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.VoteMethod;
import ai.labs.eddi.configs.groups.model.GroupConversation.DecisionType;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntry;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntryType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * I14 — {@link VoteTallyEngine}: the three-tier ballot parse, option
 * resolution, weighted tallying, quorum arithmetic (abstentions count against
 * it), and the losing side's statements becoming dissents. Pure unit tests, no
 * mocks — the engine is deliberately static and side-effect-free.
 *
 * @author tests
 */
class VoteTallyEngineTest {

    private static final List<String> OPTIONS = List.of("Adopt PostgreSQL", "Stay on MongoDB");

    private static TranscriptEntry ballot(String agentId, String content) {
        return new TranscriptEntry(agentId, agentId, content, 0, "Vote", TranscriptEntryType.VOTE, Instant.now(), null, null);
    }

    private static VoteConfig config() {
        return new VoteConfig(VoteMethod.MAJORITY, OptionsSource.EXPLICIT, OPTIONS, 0.5, Map.of(), false, TiePolicy.NO_DECISION);
    }

    // =================================================================
    // ballot parsing — three tiers
    // =================================================================

    @Test
    @DisplayName("tier 1: a strict-JSON ballot parses with confidence and statement")
    void parse_strictJson() {
        var b = VoteTallyEngine.parseBallot(
                ballot("a1", "{\"vote\": \"Adopt PostgreSQL\", \"confidence\": 0.8, \"statement\": \"pgvector matters\"}"),
                OPTIONS, VoteMethod.MAJORITY);

        assertNotNull(b);
        assertEquals(List.of("Adopt PostgreSQL"), b.votes());
        assertEquals(0.8, b.confidence());
        assertEquals("pgvector matters", b.statement());
    }

    @Test
    @DisplayName("tier 1: JSON embedded in prose or a fence still parses")
    void parse_embeddedJson() {
        var b = VoteTallyEngine.parseBallot(
                ballot("a1", "Here is my ballot:\n```json\n{\"vote\": \"Stay on MongoDB\"}\n```"),
                OPTIONS, VoteMethod.MAJORITY);

        assertNotNull(b);
        assertEquals(List.of("Stay on MongoDB"), b.votes());
        assertNull(b.confidence());
    }

    @Test
    @DisplayName("APPROVAL ballots may approve several options; duplicates collapse")
    void parse_approval() {
        var b = VoteTallyEngine.parseBallot(
                ballot("a1", "{\"votes\": [\"Adopt PostgreSQL\", \"Stay on MongoDB\", \"Adopt PostgreSQL\"]}"),
                OPTIONS, VoteMethod.APPROVAL);

        assertNotNull(b);
        assertEquals(2, b.votes().size());
    }

    @Test
    @DisplayName("a ballot may vote by 'Option A' label — resolved positionally")
    void parse_optionLabel() {
        var b = VoteTallyEngine.parseBallot(ballot("a1", "{\"vote\": \"Option B\"}"), OPTIONS, VoteMethod.MAJORITY);

        assertNotNull(b);
        assertEquals(List.of("Stay on MongoDB"), b.votes());
    }

    @Test
    @DisplayName("tier 2: exactly one option's text in prose is a ballot; two is ambiguous and is not")
    void parse_exactScan() {
        var single = VoteTallyEngine.parseBallot(ballot("a1", "I think we should Adopt PostgreSQL, honestly."), OPTIONS,
                VoteMethod.MAJORITY);
        assertNotNull(single);
        assertEquals(List.of("Adopt PostgreSQL"), single.votes());

        assertNull(VoteTallyEngine.parseBallot(
                ballot("a1", "Between Adopt PostgreSQL and Stay on MongoDB I really cannot say."), OPTIONS, VoteMethod.MAJORITY),
                "an ambiguous reply must count against quorum, never be picked by position");
    }

    @Test
    @DisplayName("tier 3: out-of-contract votes and empty replies are non-ballots")
    void parse_nonBallots() {
        assertNull(VoteTallyEngine.parseBallot(ballot("a1", "{\"vote\": \"Use MySQL\"}"), OPTIONS, VoteMethod.MAJORITY),
                "a vote outside the contract makes the ballot unreadable, not a write-in");
        assertNull(VoteTallyEngine.parseBallot(ballot("a1", "   "), OPTIONS, VoteMethod.MAJORITY));
        assertNull(VoteTallyEngine.parseBallot(null, OPTIONS, VoteMethod.MAJORITY));
    }

    // =================================================================
    // option resolution
    // =================================================================

    @Test
    @DisplayName("LAST_SYNTHESIS extracts Option lines from the NEWEST synthesis, colon or dash form")
    void resolveOptions_lastSynthesis() {
        var older = new TranscriptEntry("mod", "Mod", "Option A: Old first\nOption B: Old second", 0, "S",
                TranscriptEntryType.SYNTHESIS, Instant.now(), null, null);
        var newer = new TranscriptEntry("mod", "Mod", "Summary...\nOption A: Adopt PostgreSQL\nOption 2 - Stay on MongoDB\nMore prose.",
                1, "S", TranscriptEntryType.SYNTHESIS, Instant.now(), null, null);
        var config = new VoteConfig(VoteMethod.MAJORITY, OptionsSource.LAST_SYNTHESIS, List.of(), 0.5, Map.of(), false,
                TiePolicy.NO_DECISION);

        List<String> options = VoteTallyEngine.resolveOptions(config, List.of(older, newer));

        assertEquals(List.of("Adopt PostgreSQL", "Stay on MongoDB"), options);
    }

    @Test
    @DisplayName("LAST_SYNTHESIS also reads the shapes models write: emphasis, list markers, other separators, any case")
    void resolveOptions_lastSynthesis_markdownShapes() {
        var synthesis = new TranscriptEntry("mod", "Mod", """
                ## Recommendation
                1. **Option A:** Adopt PostgreSQL
                - **Option B**: Stay on MongoDB
                * option c) Run both behind a flag
                Option D. Defer the decision **
                We considered Option E but rejected it.
                """, 0, "S", TranscriptEntryType.SYNTHESIS, Instant.now(), null, null);
        var config = new VoteConfig(VoteMethod.MAJORITY, OptionsSource.LAST_SYNTHESIS, List.of(), 0.5, Map.of(), false,
                TiePolicy.NO_DECISION);

        assertEquals(List.of("Adopt PostgreSQL", "Stay on MongoDB", "Run both behind a flag", "Defer the decision"),
                VoteTallyEngine.resolveOptions(config, List.of(synthesis)));
    }

    @Test
    @DisplayName("no synthesis on the transcript → no options → the vote cannot run")
    void resolveOptions_noSynthesis() {
        var config = new VoteConfig(VoteMethod.MAJORITY, OptionsSource.LAST_SYNTHESIS, List.of(), 0.5, Map.of(), false,
                TiePolicy.NO_DECISION);

        assertTrue(VoteTallyEngine.resolveOptions(config, List.of()).isEmpty());
    }

    // =================================================================
    // tally
    // =================================================================

    @Test
    @DisplayName("weighted majority: weights multiply ballots, the heavier side wins")
    void tally_weightedMajority() {
        var config = new VoteConfig(VoteMethod.MAJORITY, OptionsSource.EXPLICIT, OPTIONS, 0.5,
                Map.of("a1", 3.0), false, TiePolicy.NO_DECISION);
        var entries = List.of(
                ballot("a1", "{\"vote\": \"Stay on MongoDB\"}"),
                ballot("a2", "{\"vote\": \"Adopt PostgreSQL\"}"),
                ballot("a3", "{\"vote\": \"Adopt PostgreSQL\"}"));

        var outcome = VoteTallyEngine.tally(entries, 3, OPTIONS, config, "Vote");

        assertTrue(outcome.unresolvedOptions().isEmpty());
        assertEquals(DecisionType.VOTE, outcome.decision().type());
        assertEquals("Stay on MongoDB", outcome.decision().winner(), "3.0 beats 1.0 + 1.0");
    }

    @Test
    @DisplayName("an exact tie is unresolved — never a winner by list position")
    void tally_exactTie() {
        var entries = List.of(
                ballot("a1", "{\"vote\": \"Adopt PostgreSQL\"}"),
                ballot("a2", "{\"vote\": \"Stay on MongoDB\"}"));

        var outcome = VoteTallyEngine.tally(entries, 2, OPTIONS, config(), "Vote");

        assertEquals(DecisionType.NONE, outcome.decision().type());
        assertEquals(2, outcome.unresolvedOptions().size());
        assertTrue(outcome.quorumReached());
    }

    @Test
    @DisplayName("confidence weighting multiplies only when enabled")
    void tally_confidenceWeighting() {
        var entries = List.of(
                ballot("a1", "{\"vote\": \"Adopt PostgreSQL\", \"confidence\": 0.2}"),
                ballot("a2", "{\"vote\": \"Stay on MongoDB\", \"confidence\": 0.9}"));

        var unweighted = VoteTallyEngine.tally(entries, 2, OPTIONS, config(), "Vote");
        assertEquals(DecisionType.NONE, unweighted.decision().type(), "1.0 vs 1.0 without confidence weighting — a tie");

        var weightedConfig = new VoteConfig(VoteMethod.MAJORITY, OptionsSource.EXPLICIT, OPTIONS, 0.5, Map.of(), true,
                TiePolicy.NO_DECISION);
        var weighted = VoteTallyEngine.tally(entries, 2, OPTIONS, weightedConfig, "Vote");
        assertEquals("Stay on MongoDB", weighted.decision().winner(), "0.9 beats 0.2 with confidence weighting on");
    }

    @Test
    @DisplayName("quorum counts VALID ballots against everyone asked — abstentions and garbage count against it")
    void tally_quorum() {
        // 5 participants, only 2 valid ballots (one garbage reply) → 2/5 < 0.5.
        var entries = List.of(
                ballot("a1", "{\"vote\": \"Adopt PostgreSQL\"}"),
                ballot("a2", "{\"vote\": \"Adopt PostgreSQL\"}"),
                ballot("a3", "I abstain from voting on this and name no choice at all."));

        var outcome = VoteTallyEngine.tally(entries, 5, OPTIONS, config(), "Vote");

        assertFalse(outcome.quorumReached());
        assertEquals(DecisionType.NONE, outcome.decision().type());
        assertTrue(outcome.decision().outcome().contains("2 of 5"), outcome.decision().outcome());
        assertEquals(OPTIONS.size(), outcome.unresolvedOptions().size(), "a tie policy may still decide among ALL options");
    }

    @Test
    @DisplayName("losing-side statements become the decision's dissents; the tally carries the raw ballots")
    void tally_dissentsAndAudit() {
        var entries = List.of(
                ballot("a1", "{\"vote\": \"Adopt PostgreSQL\", \"statement\": \"pgvector\"}"),
                ballot("a2", "{\"vote\": \"Adopt PostgreSQL\"}"),
                ballot("a3", "{\"vote\": \"Stay on MongoDB\", \"statement\": \"migration risk is real\"}"));

        var outcome = VoteTallyEngine.tally(entries, 3, OPTIONS, config(), "Vote");

        assertEquals("Adopt PostgreSQL", outcome.decision().winner());
        assertEquals(1, outcome.decision().dissents().size());
        assertEquals("migration risk is real", outcome.decision().dissents().get(0).position());
        assertEquals(3, ((List<?>) outcome.decision().tally().get("ballots")).size(), "every raw ballot is auditable");
        assertEquals(2.0, ((Map<?, ?>) outcome.decision().tally().get("totals")).get("Adopt PostgreSQL"));
    }

    @Test
    @DisplayName("mathematically equal totals that differ in the last floating-point bit are STILL a tie")
    void tally_floatingPointEqualTotals_isATie() {
        // 0.1 + 0.2 != 0.3 in doubles. An exact == compare would crown one side
        // of a genuinely tied vote on representation noise.
        var config = new VoteConfig(VoteMethod.MAJORITY, OptionsSource.EXPLICIT, OPTIONS, 0.5,
                Map.of("a1", 0.1, "a2", 0.2, "a3", 0.3), false, TiePolicy.NO_DECISION);
        var entries = List.of(
                ballot("a1", "{\"vote\": \"Adopt PostgreSQL\"}"),
                ballot("a2", "{\"vote\": \"Adopt PostgreSQL\"}"),
                ballot("a3", "{\"vote\": \"Stay on MongoDB\"}"));

        var outcome = VoteTallyEngine.tally(entries, 3, OPTIONS, config, "Vote");

        assertEquals(DecisionType.NONE, outcome.decision().type(), "0.1+0.2 vs 0.3 is a tie, not a winner");
        assertEquals(2, outcome.unresolvedOptions().size());
    }

    @Test
    @DisplayName("the outcome carries the parsed ballots, so a tie policy's choice still yields dissents")
    void tally_outcomeCarriesBallots_forTiePolicyDissents() {
        var entries = List.of(
                ballot("a1", "{\"vote\": \"Adopt PostgreSQL\", \"statement\": \"pgvector\"}"),
                ballot("a2", "{\"vote\": \"Stay on MongoDB\", \"statement\": \"migration risk is real\"}"));

        var outcome = VoteTallyEngine.tally(entries, 2, OPTIONS, config(), "Vote");

        assertEquals(DecisionType.NONE, outcome.decision().type(), "an exact tie");
        assertTrue(outcome.decision().dissents().isEmpty(), "no winner yet, so the record itself has no dissents");
        assertEquals(2, outcome.ballots().size());
        // What the moderator tiebreak computes after choosing: the loser's report.
        var dissents = VoteTallyEngine.losingDissents(outcome.ballots(), "Adopt PostgreSQL");
        assertEquals(1, dissents.size());
        assertEquals("migration risk is real", dissents.get(0).position());
    }

    // =================================================================
    // tiebreak choice resolution
    // =================================================================

    @Test
    @DisplayName("a tiebreaker's reply resolves by exact text, label, or single-option scan — ambiguity resolves nothing")
    void resolveChoice() {
        assertEquals("Adopt PostgreSQL", VoteTallyEngine.resolveChoice("Adopt PostgreSQL", OPTIONS));
        assertEquals("Stay on MongoDB", VoteTallyEngine.resolveChoice("Option B", OPTIONS));
        assertEquals("Adopt PostgreSQL", VoteTallyEngine.resolveChoice("I choose Adopt PostgreSQL for pgvector.", OPTIONS));
        assertNull(VoteTallyEngine.resolveChoice("Both Adopt PostgreSQL and Stay on MongoDB have merit.", OPTIONS));
        assertNull(VoteTallyEngine.resolveChoice(null, OPTIONS));
    }

    // =================================================================
    // M-G2: no substring votes, no fallback from a contract-shaped empty ballot
    // =================================================================

    @Test
    @DisplayName("M-G2: an empty contract ballot is a non-vote — its statement is never scanned into a vote")
    void emptyContractBallot_isNotAVote() {
        var options = List.of("Use Postgres", "Use Mongo");
        var entry = new TranscriptEntry("a1", "Alice", "{\"votes\": [], \"statement\": \"I lean towards Use Postgres\"}", 0,
                "Ballot", TranscriptEntryType.VOTE, Instant.now(), null, null);

        assertNull(VoteTallyEngine.parseBallot(entry, options, VoteMethod.APPROVAL),
                "the statement mentioned an option, and the fallback counted it as this member's vote");
        var nullVote = new TranscriptEntry("a1", "Alice", "{\"vote\": null, \"statement\": \"Use Mongo maybe\"}", 0,
                "Ballot", TranscriptEntryType.VOTE, Instant.now(), null, null);
        assertNull(VoteTallyEngine.parseBallot(nullVote, options, VoteMethod.MAJORITY));
    }

    @Test
    @DisplayName("M-G2: the prose fallback matches whole words — 'No' is not found inside 'not' or 'know'")
    void proseFallback_matchesWholeWordsOnly() {
        var options = List.of("Yes", "No");
        var prose = new TranscriptEntry("a1", "Alice", "I do not know yet, but Yes seems right.", 0, "Ballot",
                TranscriptEntryType.VOTE, Instant.now(), null, null);

        var ballot = VoteTallyEngine.parseBallot(prose, options, VoteMethod.MAJORITY);

        assertNotNull(ballot, "exactly one option is named as a word");
        assertEquals(List.of("Yes"), ballot.votes(),
                "'No' used to match inside 'not'/'know', making the reply ambiguous — or a vote for No");
        assertEquals("No", VoteTallyEngine.resolveChoice("No.", options));
        assertNull(VoteTallyEngine.resolveChoice("I cannot know", options));
    }

    private static TranscriptEntry vote(String content) {
        return new TranscriptEntry("a1", "Alice", content, 0, "Ballot", TranscriptEntryType.VOTE, Instant.now(), null, null);
    }

    @Test
    @DisplayName("M-G2: a ballot with no vote field at all is a non-vote — its statement is never read as one")
    void missingVoteField_isNotAVote() {
        assertNull(VoteTallyEngine.parseBallot(vote("{\"statement\": \"Use Postgres, clearly\"}"),
                List.of("Use Postgres", "Use Mongo"), VoteMethod.MAJORITY));
    }

    @Test
    @DisplayName("M-G2: a cast in another shape still counts — the approval array under MAJORITY, a string 'votes'")
    void castInOtherShape_stillCounts() {
        var options = List.of("Use Postgres", "Use Mongo");

        var arrayUnderMajority = VoteTallyEngine.parseBallot(
                vote("{\"votes\": [\"Use Postgres\"], \"statement\": \"Use Mongo would also work\"}"), options,
                VoteMethod.MAJORITY);
        assertNotNull(arrayUnderMajority, "the review's regression: a single-element array was dropped under MAJORITY");
        assertEquals(List.of("Use Postgres"), arrayUnderMajority.votes(), "the cast value decides, not the statement");
        assertEquals("Use Mongo would also work", arrayUnderMajority.statement());

        var stringUnderApproval = VoteTallyEngine.parseBallot(vote("{\"votes\": \"Use Mongo\"}"), options,
                VoteMethod.APPROVAL);
        assertNotNull(stringUnderApproval);
        assertEquals(List.of("Use Mongo"), stringUnderApproval.votes());

        assertNull(VoteTallyEngine.parseBallot(vote("{\"votes\": [\"Use Postgres\", \"Use Mongo\"]}"), options,
                VoteMethod.MAJORITY), "two different options cast under a single-choice method is ambiguous");
    }

    @Test
    @DisplayName("M-G2: scripts written without spaces still match — the boundary applies only between spaced words")
    void cjkOptions_matchWithoutSpaces() {
        var options = List.of("方案A", "方案B");

        var ballot = VoteTallyEngine.parseBallot(vote("我支持方案A。"), options, VoteMethod.MAJORITY);

        assertNotNull(ballot, "the preceding 持 is a letter, but Chinese has no spaces to form a boundary");
        assertEquals(List.of("方案A"), ballot.votes());
        assertEquals("方案B", VoteTallyEngine.resolveChoice("私は方案Bを選びます", options));
        assertTrue(VoteTallyEngine.mentionsAsWord("Yesです", "Yes"), "a Latin option glued to Japanese text still counts");
        assertFalse(VoteTallyEngine.mentionsAsWord("Yesterday", "Yes"), "but not glued to another Latin word");
    }
}
