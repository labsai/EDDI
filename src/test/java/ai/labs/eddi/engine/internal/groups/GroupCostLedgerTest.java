/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal.groups;

import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ContextScope;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DiscussionPhase;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.GroupMember;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.PhaseType;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ProtocolConfig;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ProtocolConfig.CostPolicy;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ProtocolConfig.MemberFailurePolicy;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ProtocolConfig.MemberUnavailablePolicy;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.TurnOrder;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.engine.memory.MemoryKeys;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot.ConversationStepData;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot.SimpleConversationStep;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link GroupCostLedger} (Wave 0, F5). Snapshot-building helper
 * mirrors {@code DynamicAgentTrackingPropagationTest}'s pattern for the same
 * {@code SimpleConversationMemorySnapshot} shape.
 *
 * @author tests
 */
class GroupCostLedgerTest {

    private static final GroupMember AGENT_A = new GroupMember("agent-a", "Agent A", 1, null);
    private static final GroupMember AGENT_B = new GroupMember("agent-b", "Agent B", 2, null);

    private GroupConversation gc() {
        return new GroupConversation();
    }

    private SimpleConversationMemorySnapshot buildSnapshot(ConversationStepData... entries) {
        var snapshot = new SimpleConversationMemorySnapshot();
        var step = new SimpleConversationStep();
        for (var entry : entries) {
            step.getConversationStep().add(entry);
        }
        snapshot.getConversationSteps().add(step);
        return snapshot;
    }

    // =================================================================
    // accumulateMemberCost — null / empty guards
    // =================================================================

    @Test
    void accumulateMemberCost_nullSnapshot_noOp() {
        var gc = gc();
        GroupCostLedger.accumulateMemberCost(gc, AGENT_A, null);
        assertTrue(gc.getMemberCosts().isEmpty());
        assertEquals(0.0, gc.getTotalCost());
    }

    @Test
    void accumulateMemberCost_nullConversationSteps_noOp() {
        var gc = gc();
        var snapshot = new SimpleConversationMemorySnapshot();
        snapshot.setConversationSteps(null);
        GroupCostLedger.accumulateMemberCost(gc, AGENT_A, snapshot);
        assertTrue(gc.getMemberCosts().isEmpty());
    }

    @Test
    void accumulateMemberCost_emptyConversationSteps_noOp() {
        var gc = gc();
        var snapshot = new SimpleConversationMemorySnapshot();
        GroupCostLedger.accumulateMemberCost(gc, AGENT_A, snapshot);
        assertTrue(gc.getMemberCosts().isEmpty());
    }

    @Test
    void accumulateMemberCost_noCostKeyPresent_noOp() {
        var gc = gc();
        var snapshot = buildSnapshot(new ConversationStepData("output:text", "hello", null, null));
        GroupCostLedger.accumulateMemberCost(gc, AGENT_A, snapshot);
        assertTrue(gc.getMemberCosts().isEmpty(),
                "a turn with no audit:cost key (non-cascade, no priced tool — the common case per V1) must not fabricate a cost entry");
    }

    @Test
    void accumulateMemberCost_nonNumberCostValue_noOp() {
        var gc = gc();
        var snapshot = buildSnapshot(new ConversationStepData(MemoryKeys.AUDIT_COST, "not-a-number", null, null));
        GroupCostLedger.accumulateMemberCost(gc, AGENT_A, snapshot);
        assertTrue(gc.getMemberCosts().isEmpty());
    }

    // =================================================================
    // accumulateMemberCost — real values
    // =================================================================

    @Test
    void accumulateMemberCost_costPresent_recordsAndSumsTotal() {
        var gc = gc();
        var snapshot = buildSnapshot(new ConversationStepData(MemoryKeys.AUDIT_COST, 0.05, null, null));

        GroupCostLedger.accumulateMemberCost(gc, AGENT_A, snapshot);

        assertEquals(0.05, gc.getMemberCosts().get("agent-a"));
        assertEquals(0.05, gc.getTotalCost());
    }

    @Test
    void accumulateMemberCost_secondTurn_replacesRatherThanAdds() {
        var gc = gc();
        GroupCostLedger.accumulateMemberCost(gc, AGENT_A, buildSnapshot(new ConversationStepData(MemoryKeys.AUDIT_COST, 0.05, null, null)));

        // AUDIT_COST is cumulative per LlmTask.accumulateCost — a later turn's
        // value already includes the earlier turn's, so this must REPLACE, not add.
        GroupCostLedger.accumulateMemberCost(gc, AGENT_A, buildSnapshot(new ConversationStepData(MemoryKeys.AUDIT_COST, 0.12, null, null)));

        assertEquals(0.12, gc.getMemberCosts().get("agent-a"), "must replace with the new cumulative value, not add deltas");
        assertEquals(0.12, gc.getTotalCost());
    }

    @Test
    void accumulateMemberCost_multipleMembers_totalIsSumOfAll() {
        var gc = gc();
        GroupCostLedger.accumulateMemberCost(gc, AGENT_A, buildSnapshot(new ConversationStepData(MemoryKeys.AUDIT_COST, 0.05, null, null)));
        GroupCostLedger.accumulateMemberCost(gc, AGENT_B, buildSnapshot(new ConversationStepData(MemoryKeys.AUDIT_COST, 0.03, null, null)));

        assertEquals(0.05, gc.getMemberCosts().get("agent-a"));
        assertEquals(0.03, gc.getMemberCosts().get("agent-b"));
        assertEquals(0.08, gc.getTotalCost(), 1e-9);
    }

    @Test
    void accumulateMemberCost_readsOnlyLastStep() {
        var gc = gc();
        var snapshot = new SimpleConversationMemorySnapshot();
        var step1 = new SimpleConversationStep();
        step1.getConversationStep().add(new ConversationStepData(MemoryKeys.AUDIT_COST, 0.99, null, null));
        snapshot.getConversationSteps().add(step1);
        var step2 = new SimpleConversationStep();
        step2.getConversationStep().add(new ConversationStepData(MemoryKeys.AUDIT_COST, 0.10, null, null));
        snapshot.getConversationSteps().add(step2);

        GroupCostLedger.accumulateMemberCost(gc, AGENT_A, snapshot);

        assertEquals(0.10, gc.getMemberCosts().get("agent-a"), "must read the LAST step's value, not an earlier one");
    }

    // =================================================================
    // accumulateNestedGroupCost
    // =================================================================

    @Test
    void accumulateNestedGroupCost_nullSubConversation_noOp() {
        var gc = gc();
        GroupCostLedger.accumulateNestedGroupCost(gc, AGENT_A, null);
        assertTrue(gc.getMemberCosts().isEmpty());
    }

    @Test
    void accumulateNestedGroupCost_rollsUpChildTotal() {
        var gc = gc();
        var subConversation = gc();
        subConversation.setTotalCost(1.23);

        GroupCostLedger.accumulateNestedGroupCost(gc, AGENT_A, subConversation);

        assertEquals(1.23, gc.getMemberCosts().get("agent-a"));
        assertEquals(1.23, gc.getTotalCost());
    }

    @Test
    void accumulateNestedGroupCost_multipleChildDiscussions_sumRatherThanOverwrite() {
        var gc = gc();
        // Two turns of the same GROUP member = two FRESH child discussions, each
        // starting its own totalCost at 0. Keyed by agentId alone, child 2's $0.50
        // would replace child 1's $1.00 and the group would under-report by half.
        var firstChild = gc();
        firstChild.setId("child-1");
        firstChild.setTotalCost(1.00);
        var secondChild = gc();
        secondChild.setId("child-2");
        secondChild.setTotalCost(0.50);

        GroupCostLedger.accumulateNestedGroupCost(gc, AGENT_A, firstChild);
        GroupCostLedger.accumulateNestedGroupCost(gc, AGENT_A, secondChild);

        assertEquals(1.50, gc.getTotalCost(), 1e-9,
                "each child discussion is separate spend — a member's later child must not erase its earlier one");
    }

    @Test
    void accumulateNestedGroupCost_mixesWithIndividualMembers() {
        var gc = gc();
        GroupCostLedger.accumulateMemberCost(gc, AGENT_A, buildSnapshot(new ConversationStepData(MemoryKeys.AUDIT_COST, 0.10, null, null)));
        var subConversation = gc();
        subConversation.setTotalCost(0.50);

        GroupCostLedger.accumulateNestedGroupCost(gc, AGENT_B, subConversation);

        assertEquals(0.60, gc.getTotalCost(), 1e-9);
    }

    // =================================================================
    // wouldExceedCeiling — must agree with enforceCeiling, zero case and all
    // =================================================================

    private ProtocolConfig protocolWithCeiling(Double ceiling) {
        return new ProtocolConfig(60, MemberFailurePolicy.SKIP, 0, MemberUnavailablePolicy.SKIP, 50, ceiling, CostPolicy.ABORT);
    }

    private GroupConversation gcSpending(double totalCost) {
        var gc = gc();
        gc.setTotalCost(totalCost);
        return gc;
    }

    @Test
    void wouldExceedCeiling_noCeiling_isNeverExceeded() {
        assertFalse(GroupCostLedger.wouldExceedCeiling(gcSpending(999.0), protocolWithCeiling(null)));
        assertFalse(GroupCostLedger.wouldExceedCeiling(gcSpending(999.0), null));
    }

    @Test
    void wouldExceedCeiling_withinBudget_isNotExceeded() {
        // `<=` semantics: the ceiling is a budget to spend, not a value to stop
        // short of. Spending it exactly is allowed.
        assertFalse(GroupCostLedger.wouldExceedCeiling(gcSpending(0.99), protocolWithCeiling(1.0)));
        assertFalse(GroupCostLedger.wouldExceedCeiling(gcSpending(1.0), protocolWithCeiling(1.0)));
    }

    @Test
    void wouldExceedCeiling_overBudget_isExceeded() {
        assertTrue(GroupCostLedger.wouldExceedCeiling(gcSpending(1.01), protocolWithCeiling(1.0)));
    }

    @Test
    void wouldExceedCeiling_zeroCeiling_isAlreadyExceeded() {
        // A nested child inherits ceiling 0.0 when its parent has spent its whole
        // budget (MemberTurnExecutor's Math.max(0.0, remaining)). Reading 0.0 > 0.0
        // as "budget available" let the optional work this gate exists to skip —
        // I2's convergence judge, I4's whole dissent round — run anyway, one LLM
        // call per dissenter, against a budget already gone.
        assertTrue(GroupCostLedger.wouldExceedCeiling(gcSpending(0.0), protocolWithCeiling(0.0)));
        assertTrue(GroupCostLedger.wouldExceedCeiling(gcSpending(0.0), protocolWithCeiling(-1.0)));
    }

    @Test
    void wouldExceedCeiling_agreesWithEnforceCeiling() {
        // The two are asked the same question by different callers; they diverged
        // once already. Pinning them against each other is what stops that
        // recurring, since neither test alone would notice.
        // OPINION + ABORT: neither of enforceCeiling's carve-outs applies, so the
        // comparison is against its plain ceiling test.
        var phase = new DiscussionPhase("P", PhaseType.OPINION, "ALL", TurnOrder.SEQUENTIAL, ContextScope.FULL, false, null, 1, false);
        for (Double ceiling : new Double[]{null, -1.0, 0.0, 1.0, 5.0}) {
            for (double spent : new double[]{0.0, 0.99, 1.0, 1.01, 10.0}) {
                var protocol = protocolWithCeiling(ceiling);
                boolean enforced = GroupCostLedger.enforceCeiling(gcSpending(spent), protocol, 0, phase);
                boolean wouldExceed = GroupCostLedger.wouldExceedCeiling(gcSpending(spent), protocol);
                assertEquals(enforced, wouldExceed,
                        "ceiling=" + ceiling + " spent=" + spent + ": the two ceiling checks must agree");
            }
        }
    }
}
