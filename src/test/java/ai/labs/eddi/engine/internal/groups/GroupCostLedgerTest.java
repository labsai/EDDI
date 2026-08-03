/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal.groups;

import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.GroupMember;
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
    void accumulateNestedGroupCost_mixesWithIndividualMembers() {
        var gc = gc();
        GroupCostLedger.accumulateMemberCost(gc, AGENT_A, buildSnapshot(new ConversationStepData(MemoryKeys.AUDIT_COST, 0.10, null, null)));
        var subConversation = gc();
        subConversation.setTotalCost(0.50);

        GroupCostLedger.accumulateNestedGroupCost(gc, AGENT_B, subConversation);

        assertEquals(0.60, gc.getTotalCost(), 1e-9);
    }
}
