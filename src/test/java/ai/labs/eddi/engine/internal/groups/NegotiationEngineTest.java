/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal.groups;

import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ContextScope;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DiscussionPhase;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.GroupMember;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.PhaseType;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.TurnOrder;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.DecisionType;
import ai.labs.eddi.configs.groups.model.GroupConversation.Proposal;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntry;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntryType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * I11 — {@link NegotiationEngine}: the three-tier bargain parse, the proposal
 * table (creation, implicit self-signature, supersession), the concession
 * ledger's must-name-a-return rule, unanimous-acceptance detection with signed
 * entry indices, arbitration recording, and the ledger rendering every turn is
 * held accountable to. Pure unit tests, no mocks — the engine is deliberately
 * static and side-effect-free beyond the conversation it is handed.
 *
 * @author tests
 */
class NegotiationEngineTest {

    private static final GroupMember ALICE = new GroupMember("a1", "Alice", 1, null);
    private static final GroupMember BOB = new GroupMember("a2", "Bob", 2, null);
    private static final GroupMember MOD = new GroupMember("mod", "Moderator", 0, "MODERATOR");

    private GroupConversation gc() {
        var gc = new GroupConversation();
        gc.setId("gc-1");
        gc.setGroupId("group-1");
        return gc;
    }

    private DiscussionPhase bargainPhase() {
        return new DiscussionPhase("Bargaining", PhaseType.BARGAIN, "ALL", TurnOrder.SEQUENTIAL, ContextScope.FULL,
                false, null, 3, false);
    }

    private TranscriptEntry proposal(String agentId, String terms) {
        return new TranscriptEntry(agentId, agentId, terms, 1, "Opening Proposals", TranscriptEntryType.PROPOSAL,
                Instant.now(), null, null);
    }

    private TranscriptEntry bargain(String agentId, String json) {
        return new TranscriptEntry(agentId, agentId, json, 2, "Bargaining", TranscriptEntryType.BARGAIN,
                Instant.now(), null, null);
    }

    // =================================================================
    // parsing — three tiers
    // =================================================================

    @Test
    @DisplayName("strict JSON, fenced JSON, and JSON-with-reasoning all parse; pure prose is a non-move")
    void parse_tiers() {
        assertNotNull(NegotiationEngine.parseBargain("{\"accept\": \"p1\"}"));
        assertNotNull(NegotiationEngine.parseBargain("My move:\n```json\n{\"proposal\": {\"terms\": \"50/50 split\"}}\n```"));
        assertNotNull(NegotiationEngine.parseBargain(
                "{\"accept\": null, \"proposal\": {\"terms\": \"60/40\"}, \"concessions\": []} I offer this because…"));
        assertNull(NegotiationEngine.parseBargain("I think we should probably find some middle ground."),
                "prose is never guessed into a move");
        assertNull(NegotiationEngine.parseBargain("{\"accept\": null, \"proposal\": null, \"concessions\": []}"),
                "a JSON turn carrying no move is a non-move");
        assertNull(NegotiationEngine.parseBargain(null));
    }

    @Test
    @DisplayName("a concession that names nothing in return is NOT recorded — the rule IS the structure")
    void parse_concessionRequiresReturn() {
        var move = NegotiationEngine.parseBargain(
                "{\"concessions\": [{\"gaveUp\": \"weekend support\", \"inReturnFor\": \"\"}, "
                        + "{\"gaveUp\": \"launch date\", \"inReturnFor\": \"extra QA week\"}]}");

        assertNotNull(move);
        assertEquals(1, move.concessions().size());
        assertEquals("launch date", move.concessions().get(0).gaveUp());
    }

    // =================================================================
    // proposal table
    // =================================================================

    @Test
    @DisplayName("a PROPOSAL turn puts terms on the table, signed implicitly by its author")
    void apply_proposalTurn() {
        var gc = gc();

        NegotiationEngine.applyRepeat(gc, bargainPhase(), List.of(proposal("a1", "We split revenue 50/50.")), 10, 0);

        var proposals = gc.getNegotiation().getProposals();
        assertEquals(1, proposals.size());
        Proposal p = proposals.get(0);
        assertEquals("p1", p.id());
        assertEquals("a1", p.byAgentId());
        assertEquals(GroupConversation.PROPOSAL_OPEN, p.status());
        assertEquals(List.of("a1"), p.acceptedBy(), "the proposer signs their own terms");
        assertEquals(Map.of("a1", 10), p.acceptanceEntryIndices(), "the authoring entry IS the signature");
    }

    @Test
    @DisplayName("a new proposal by the same agent SUPERSEDES their open one — one live offer per agent")
    void apply_supersession() {
        var gc = gc();
        NegotiationEngine.applyRepeat(gc, bargainPhase(), List.of(proposal("a1", "50/50")), 0, 0);

        NegotiationEngine.applyRepeat(gc, bargainPhase(),
                List.of(bargain("a1", "{\"proposal\": {\"terms\": \"55/45 with support included\"}}")), 1, 1);

        var proposals = gc.getNegotiation().getProposals();
        assertEquals(2, proposals.size());
        assertEquals(GroupConversation.PROPOSAL_SUPERSEDED, proposals.get(0).status());
        assertEquals(GroupConversation.PROPOSAL_OPEN, proposals.get(1).status());
        assertEquals("p2", proposals.get(1).id());
    }

    @Test
    @DisplayName("accepting an unknown or superseded proposal is ignored; an unparseable turn is inert")
    void apply_badMovesAreInert() {
        var gc = gc();
        NegotiationEngine.applyRepeat(gc, bargainPhase(), List.of(proposal("a1", "50/50")), 0, 0);
        var before = List.copyOf(gc.getNegotiation().getProposals());

        NegotiationEngine.applyRepeat(gc, bargainPhase(), List.of(
                bargain("a2", "{\"accept\": \"p99\"}"),
                bargain("a2", "Honestly I just want to talk it through some more.")), 1, 1);

        assertEquals(before, gc.getNegotiation().getProposals(), "no guessed acceptances, no state drift");
        assertTrue(gc.getNegotiation().getConcessions().isEmpty());
    }

    @Test
    @DisplayName("the ledger accumulates across rounds, each line referencing the round it was made in")
    void apply_ledgerAccumulation() {
        var gc = gc();
        NegotiationEngine.applyRepeat(gc, bargainPhase(), List.of(
                bargain("a1", "{\"proposal\": {\"terms\": \"50/50\"}, "
                        + "\"concessions\": [{\"gaveUp\": \"exclusivity\", \"inReturnFor\": \"faster payout\"}]}")),
                0, 0);
        NegotiationEngine.applyRepeat(gc, bargainPhase(), List.of(
                bargain("a2", "{\"concessions\": [{\"gaveUp\": \"launch veto\", \"inReturnFor\": \"quarterly review\"}]}")), 1, 1);

        var ledger = gc.getNegotiation().getConcessions();
        assertEquals(2, ledger.size());
        assertEquals(0, ledger.get(0).round());
        assertEquals("p1", ledger.get(0).refProposalId(), "a concession made WITH a proposal references it");
        assertEquals(1, ledger.get(1).round());
        assertNull(ledger.get(1).refProposalId());
    }

    // =================================================================
    // agreement
    // =================================================================

    @Test
    @DisplayName("unanimous acceptance records the AGREEMENT with the signed entry indices — the co-signatures")
    void agreement_unanimousAcceptance() {
        var gc = gc();
        NegotiationEngine.applyRepeat(gc, bargainPhase(), List.of(proposal("a1", "We split revenue 50/50.")), 5, 0);
        NegotiationEngine.applyRepeat(gc, bargainPhase(), List.of(bargain("a2", "{\"accept\": \"p1\"}")), 7, 1);

        assertTrue(NegotiationEngine.checkAndRecordAgreement(gc, List.of(ALICE, BOB, MOD), "mod", "Bargaining"));

        var decision = gc.getDecision();
        assertEquals(DecisionType.AGREEMENT, decision.type());
        assertEquals(NegotiationEngine.METHOD_NEGOTIATION, decision.method());
        assertEquals("p1", decision.winner());
        @SuppressWarnings("unchecked")
        Map<String, Integer> signed = (Map<String, Integer>) decision.tally().get("signedAcceptances");
        assertEquals(Map.of("a1", 5, "a2", 7), signed,
                "the signed transcript entries ARE the co-signatures — their indices are the record");
    }

    @Test
    @DisplayName("partial acceptance is no agreement; the moderator's signature is not required")
    void agreement_partialIsNone() {
        var gc = gc();
        NegotiationEngine.applyRepeat(gc, bargainPhase(), List.of(
                proposal("a1", "50/50"),
                proposal("a2", "60/40")), 0, 0);

        assertFalse(NegotiationEngine.checkAndRecordAgreement(gc, List.of(ALICE, BOB, MOD), "mod", "Bargaining"),
                "two open proposals, each signed only by its author");
        assertNull(gc.getDecision());
    }

    // =================================================================
    // arbitration
    // =================================================================

    @Test
    @DisplayName("arbitration records the moderator's conclusion as the VERDICT — and never overwrites a decision")
    void arbitration_recordsVerdictOnce() {
        var gc = gc();
        var arbitrationEntry = new TranscriptEntry("mod", "Moderator", "Split 55/45; support shared.", 3,
                "Arbitration", TranscriptEntryType.SYNTHESIS, Instant.now(), null, null);

        NegotiationEngine.recordArbitration(gc, List.of(arbitrationEntry), "Arbitration");

        assertEquals(DecisionType.VERDICT, gc.getDecision().type());
        assertEquals(NegotiationEngine.METHOD_ARBITRATION, gc.getDecision().method());
        assertEquals("Split 55/45; support shared.", gc.getDecision().outcome());

        var existing = gc.getDecision();
        NegotiationEngine.recordArbitration(gc, List.of(new TranscriptEntry("mod", "Moderator", "Other.", 3,
                "Arbitration", TranscriptEntryType.SYNTHESIS, Instant.now(), null, null)), "Arbitration");
        assertSame(existing, gc.getDecision(), "an existing decision is never overwritten");
    }

    // =================================================================
    // ledger rendering — the accountability mechanism
    // =================================================================

    @Test
    @DisplayName("the table renders open proposals (with ids and signatories) and the ledger into a BARGAIN turn")
    void render_tableIntoBargainTurn() {
        var gc = gc();
        NegotiationEngine.applyRepeat(gc, bargainPhase(), List.of(
                bargain("a1", "{\"proposal\": {\"terms\": \"50/50 split\"}, "
                        + "\"concessions\": [{\"gaveUp\": \"exclusivity\", \"inReturnFor\": \"faster payout\"}]}")),
                0, 0);

        String rendered = NegotiationEngine.appendStateIfRelevant("PROMPT", gc, bargainPhase());

        assertTrue(rendered.startsWith("PROMPT"));
        assertTrue(rendered.contains("[p1] by a1"), rendered);
        assertTrue(rendered.contains("50/50 split"), rendered);
        assertTrue(rendered.contains("gave up \"exclusivity\" in return for \"faster payout\""), rendered);
    }

    @Test
    @DisplayName("no negotiation state, or a non-negotiation phase, appends nothing")
    void render_noOpOtherwise() {
        var gc = gc();
        assertEquals("PROMPT", NegotiationEngine.appendStateIfRelevant("PROMPT", gc, bargainPhase()));

        NegotiationEngine.applyRepeat(gc, bargainPhase(), List.of(proposal("a1", "50/50")), 0, 0);
        var opinionPhase = new DiscussionPhase("Open", PhaseType.OPINION, "ALL", TurnOrder.SEQUENTIAL,
                ContextScope.FULL, false, null, 1, false);
        assertEquals("PROMPT", NegotiationEngine.appendStateIfRelevant("PROMPT", gc, opinionPhase));
    }

    // =================================================================
    // the scripted three-round bargain — living documentation of the protocol
    // =================================================================

    @Test
    @DisplayName("a scripted 3-round bargain converges in round 3: propose → counter+concede → sign")
    void scripted_threeRoundBargain() {
        var gc = gc();
        var phase = bargainPhase();
        var participants = List.of(ALICE, BOB, MOD);

        // Round 1 — both parties put opening terms on the table. No agreement.
        NegotiationEngine.applyRepeat(gc, phase, List.of(
                bargain("a1", "{\"proposal\": {\"terms\": \"Alice leads, 60/40 revenue\"}}"),
                bargain("a2", "{\"proposal\": {\"terms\": \"Shared lead, 50/50 revenue\"}}")), 0, 0);
        assertFalse(NegotiationEngine.checkAndRecordAgreement(gc, participants, "mod", phase.name()));

        // Round 2 — Alice counters toward Bob, naming her concession's return.
        // Her first proposal is superseded; still no unanimous signature.
        NegotiationEngine.applyRepeat(gc, phase, List.of(
                bargain("a1", "{\"proposal\": {\"terms\": \"Shared lead, 55/45 revenue\"}, "
                        + "\"concessions\": [{\"gaveUp\": \"sole lead\", \"inReturnFor\": \"the larger revenue share\"}]}"),
                bargain("a2", "{\"concessions\": [{\"gaveUp\": \"strict 50/50\", \"inReturnFor\": \"shared lead confirmed\"}]}")), 2, 1);
        assertFalse(NegotiationEngine.checkAndRecordAgreement(gc, participants, "mod", phase.name()));

        // Round 3 — Bob signs Alice's open counter. Unanimous; repeats would end here.
        NegotiationEngine.applyRepeat(gc, phase, List.of(
                bargain("a2", "{\"accept\": \"p3\"}")), 4, 2);
        assertTrue(NegotiationEngine.checkAndRecordAgreement(gc, participants, "mod", phase.name()),
                "round 3 converges");

        var decision = gc.getDecision();
        assertEquals(DecisionType.AGREEMENT, decision.type());
        assertEquals("p3", decision.winner());
        assertTrue(decision.outcome().contains("2 concession"), decision.outcome());
        assertEquals(2, gc.getNegotiation().getConcessions().size());
    }
}
