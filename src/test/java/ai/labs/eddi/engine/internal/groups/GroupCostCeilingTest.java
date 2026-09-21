/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal.groups;

import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ContextScope;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DiscussionPhase;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.PhaseType;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ProtocolConfig;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ProtocolConfig.CostPolicy;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ProtocolConfig.MemberFailurePolicy;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ProtocolConfig.MemberUnavailablePolicy;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.TurnOrder;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntryType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link GroupCostLedger#enforceCeiling} — the I1 pre-turn/pre-wave
 * cost gate, in isolation from the phase loop that acts on its verdict.
 *
 * @author tests
 */
class GroupCostCeilingTest {

    private GroupConversation gc(double totalCost) {
        var gc = new GroupConversation();
        gc.setId("gc-1");
        gc.setGroupId("group-1");
        gc.setTotalCost(totalCost);
        return gc;
    }

    private ProtocolConfig protocol(Double ceiling, CostPolicy policy) {
        return new ProtocolConfig(60, MemberFailurePolicy.SKIP, 2, MemberUnavailablePolicy.SKIP, 50, ceiling, policy);
    }

    private DiscussionPhase phase() {
        return new DiscussionPhase("Opinion", PhaseType.OPINION, "ALL", TurnOrder.SEQUENTIAL, ContextScope.FULL, false, null, 1, false);
    }

    @Test
    void nullCeiling_neverFires() {
        var gc = gc(999.0);

        assertFalse(GroupCostLedger.enforceCeiling(gc, protocol(null, CostPolicy.SYNTHESIZE_NOW), 0, phase()));
        assertNull(gc.getCostCeilingOutcome());
        assertTrue(gc.getTranscript().isEmpty(), "an unlimited discussion must not gain a SKIPPED entry");
    }

    @Test
    void costBelowCeiling_doesNotFire() {
        var gc = gc(0.5);

        assertFalse(GroupCostLedger.enforceCeiling(gc, protocol(1.0, CostPolicy.SYNTHESIZE_NOW), 0, phase()));
        assertNull(gc.getCostCeilingOutcome());
        assertTrue(gc.getTranscript().isEmpty());
    }

    @Test
    void costExactlyAtCeiling_doesNotFire() {
        var gc = gc(1.0);

        assertFalse(GroupCostLedger.enforceCeiling(gc, protocol(1.0, CostPolicy.SYNTHESIZE_NOW), 0, phase()),
                "the ceiling is the budget, not a value to stop before — only exceeding it stops the discussion");
        assertNull(gc.getCostCeilingOutcome());
    }

    @Test
    void costAboveCeiling_firesWithPolicyAndSkippedEntry() {
        var gc = gc(1.5);

        assertTrue(GroupCostLedger.enforceCeiling(gc, protocol(1.0, CostPolicy.SYNTHESIZE_NOW), 3, phase()));

        assertEquals(CostPolicy.SYNTHESIZE_NOW, gc.getCostCeilingOutcome());
        assertEquals(1, gc.getTranscript().size());
        var entry = gc.getTranscript().get(0);
        assertEquals(TranscriptEntryType.SKIPPED, entry.type());
        assertEquals(3, entry.phaseIndex());
        assertEquals("Opinion", entry.phaseName());
        assertTrue(entry.errorReason().contains("Cost ceiling reached"), () -> "unexpected: " + entry.errorReason());
        assertTrue(entry.errorReason().contains("1.50"), () -> "must name the spend: " + entry.errorReason());
        assertTrue(entry.errorReason().contains("1.00"), () -> "must name the ceiling: " + entry.errorReason());
        assertTrue(entry.errorReason().contains("SYNTHESIZE_NOW"), () -> "must name the policy: " + entry.errorReason());
    }

    @Test
    void abortPolicy_isCarriedThroughOnTheOutcome() {
        var gc = gc(2.0);

        assertTrue(GroupCostLedger.enforceCeiling(gc, protocol(1.0, CostPolicy.ABORT), 0, phase()));

        assertEquals(CostPolicy.ABORT, gc.getCostCeilingOutcome());
        assertTrue(gc.getTranscript().get(0).errorReason().contains("ABORT"));
    }

    @Test
    void synthesizeNow_exemptsTheSynthesisPhaseItself() {
        var gc = gc(99.0);
        var synthesisPhase = new DiscussionPhase("Summary", PhaseType.SYNTHESIS, "ALL", TurnOrder.SEQUENTIAL, ContextScope.FULL,
                false, null, 1, false);

        assertFalse(GroupCostLedger.enforceCeiling(gc, protocol(1.0, CostPolicy.SYNTHESIZE_NOW), 2, synthesisPhase),
                "SYNTHESIZE_NOW exists to still produce an answer — gating its own synthesis phase would make it identical to ABORT");
        assertTrue(gc.getTranscript().isEmpty(), "the exempt phase must not gain a SKIPPED entry either");
    }

    @Test
    void abort_doesNotExemptTheSynthesisPhase() {
        var gc = gc(99.0);
        var synthesisPhase = new DiscussionPhase("Summary", PhaseType.SYNTHESIS, "ALL", TurnOrder.SEQUENTIAL, ContextScope.FULL,
                false, null, 1, false);

        assertTrue(GroupCostLedger.enforceCeiling(gc, protocol(1.0, CostPolicy.ABORT), 2, synthesisPhase),
                "ABORT fails the discussion outright — it has no answer to preserve");
    }

    @Test
    void zeroCeiling_stopsEvenAtZeroSpend() {
        // The value a nested child inherits when its parent's budget is fully
        // consumed. `totalCost <= ceiling` alone would pass here (0 <= 0) and grant
        // a free turn on an already-exhausted budget.
        var gc = gc(0.0);

        assertTrue(GroupCostLedger.enforceCeiling(gc, protocol(0.0, CostPolicy.ABORT), 0, phase()),
                "a fully-consumed inherited budget must stop the very first turn");
    }

    @Test
    void nullPolicy_normalizesToSynthesizeNow() {
        var gc = gc(2.0);

        // ProtocolConfig's canonical constructor coalesces a null policy, so no
        // reader ever has to null-check it.
        assertTrue(GroupCostLedger.enforceCeiling(gc, protocol(1.0, null), 0, phase()));

        assertEquals(CostPolicy.SYNTHESIZE_NOW, gc.getCostCeilingOutcome());
    }
}
