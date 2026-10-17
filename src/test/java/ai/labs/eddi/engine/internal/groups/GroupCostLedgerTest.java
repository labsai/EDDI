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
import ai.labs.eddi.engine.memory.ConversationMemoryUtilities;
import ai.labs.eddi.engine.memory.MemoryKeys;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.ConversationStepSnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.ResultSnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.WorkflowRunSnapshot;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot.ConversationStepData;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot.SimpleConversationStep;
import org.junit.jupiter.api.Test;

import java.util.Date;

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

    private SimpleConversationMemorySnapshot withCost(Double conversationCost) {
        var snapshot = new SimpleConversationMemorySnapshot();
        snapshot.setConversationCost(conversationCost);
        return snapshot;
    }

    /**
     * A persisted member conversation whose steps each carry their own
     * {@code audit:cost}, as {@code LlmTask} writes it: one value per step, the
     * cost of that turn only.
     */
    private static ConversationMemorySnapshot memberConversation(double... perStepCosts) {
        var stored = new ConversationMemorySnapshot();
        stored.setConversationId("member-conv");
        stored.setAgentId("agent-a");
        stored.setAgentVersion(1);
        for (double cost : perStepCosts) {
            var output = new ConversationOutput();
            output.put("output", "a contribution");
            stored.getConversationOutputs().add(output);
            var workflow = new WorkflowRunSnapshot();
            workflow.getLifecycleTasks().add(new ResultSnapshot("output:text:a", "a contribution", null, new Date(), null, true));
            workflow.getLifecycleTasks().add(new ResultSnapshot(MemoryKeys.AUDIT_COST, cost, null, new Date(), null, false));
            var step = new ConversationStepSnapshot();
            step.getWorkflows().add(workflow);
            stored.getConversationSteps().add(step);
        }
        return stored;
    }

    /**
     * What a member turn's callback receives after the given turns, built through
     * the real projection with the flags {@code MemberTurnExecutor} passes
     * ({@code returnDetailed=true, returnCurrentStepOnly=true}).
     */
    private static SimpleConversationMemorySnapshot memberTurnSnapshot(double... perStepCosts) {
        return ConversationMemoryUtilities.convertSimpleConversationMemorySnapshot(memberConversation(perStepCosts), true, true, null);
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
    void accumulateMemberCost_noConversationCost_noOp() {
        var gc = gc();
        GroupCostLedger.accumulateMemberCost(gc, AGENT_A, withCost(null));
        assertTrue(gc.getMemberCosts().isEmpty(),
                "a conversation where nothing priced ran (non-cascade, no priced tool — the common case per V1) must not fabricate a cost entry");
    }

    @Test
    void accumulateMemberCost_nonFiniteOrNegativeCost_noOp() {
        var gc = gc();
        GroupCostLedger.accumulateMemberCost(gc, AGENT_A, withCost(Double.NaN));
        GroupCostLedger.accumulateMemberCost(gc, AGENT_A, withCost(Double.POSITIVE_INFINITY));
        GroupCostLedger.accumulateMemberCost(gc, AGENT_A, withCost(-1.0));
        assertTrue(gc.getMemberCosts().isEmpty(), "a poisoned value would disable every ceiling compared against totalCost");
        assertEquals(0.0, gc.getTotalCost());
    }

    @Test
    void accumulateMemberCost_ignoresStepData() {
        var gc = gc();
        // The step data is not the source: an audit:cost there is one step's value,
        // never the conversation's spend.
        var snapshot = withCost(null);
        var step = new SimpleConversationStep();
        step.getConversationStep().add(new ConversationStepData(MemoryKeys.AUDIT_COST, 0.99, null, null));
        snapshot.getConversationSteps().add(step);

        GroupCostLedger.accumulateMemberCost(gc, AGENT_A, snapshot);

        assertTrue(gc.getMemberCosts().isEmpty());
    }

    // =================================================================
    // accumulateMemberCost — real values
    // =================================================================

    @Test
    void accumulateMemberCost_costPresent_recordsAndSumsTotal() {
        var gc = gc();
        GroupCostLedger.accumulateMemberCost(gc, AGENT_A, withCost(0.05));

        assertEquals(0.05, gc.getMemberCosts().get("agent-a"));
        assertEquals(0.05, gc.getTotalCost());
    }

    @Test
    void accumulateMemberCost_sameTurnHarvestedTwice_isIdempotent() {
        var gc = gc();
        GroupCostLedger.accumulateMemberCost(gc, AGENT_A, withCost(0.12));
        GroupCostLedger.accumulateMemberCost(gc, AGENT_A, withCost(0.12));

        assertEquals(0.12, gc.getMemberCosts().get("agent-a"), "the value is the conversation's running total — replaced, never added");
        assertEquals(0.12, gc.getTotalCost());
    }

    @Test
    void accumulateMemberCost_multipleMembers_totalIsSumOfAll() {
        var gc = gc();
        GroupCostLedger.accumulateMemberCost(gc, AGENT_A, withCost(0.05));
        GroupCostLedger.accumulateMemberCost(gc, AGENT_B, withCost(0.03));

        assertEquals(0.05, gc.getMemberCosts().get("agent-a"));
        assertEquals(0.03, gc.getMemberCosts().get("agent-b"));
        assertEquals(0.08, gc.getTotalCost(), 1e-9);
    }

    // =================================================================
    // accumulateMemberCost — through the real member-turn projection
    // =================================================================

    @Test
    void twoTurnMember_isCountedInFull() {
        var gc = gc();
        // Turn 1 costs $0.05, turn 2 $0.07. audit:cost is per STEP, so the member's
        // spend is $0.12, not the $0.07 the latest step alone carries.
        GroupCostLedger.accumulateMemberCost(gc, AGENT_A, memberTurnSnapshot(0.05));
        assertEquals(0.05, gc.getTotalCost(), 1e-9);

        GroupCostLedger.accumulateMemberCost(gc, AGENT_A, memberTurnSnapshot(0.05, 0.07));

        assertEquals(0.12, gc.getMemberCosts().get("agent-a"), 1e-9);
        assertEquals(0.12, gc.getTotalCost(), 1e-9);
    }

    @Test
    void twoTurnMember_tripsTheCeilingItsTurnsJointlyExceed() {
        var gc = gc();
        var protocol = protocolWithCeiling(0.10);
        var phase = new DiscussionPhase("P", PhaseType.OPINION, "ALL", TurnOrder.SEQUENTIAL, ContextScope.FULL, false, null, 1, false);

        GroupCostLedger.accumulateMemberCost(gc, AGENT_A, memberTurnSnapshot(0.06));
        assertFalse(GroupCostLedger.enforceCeiling(gc, protocol, 0, phase), "$0.06 of $0.10 is within budget");

        GroupCostLedger.accumulateMemberCost(gc, AGENT_A, memberTurnSnapshot(0.06, 0.06));

        // Each turn alone is under the ceiling; together they are $0.12, over it.
        assertTrue(GroupCostLedger.wouldExceedCeiling(gc, protocol));
        assertTrue(GroupCostLedger.enforceCeiling(gc, protocol, 0, phase));
        assertEquals(CostPolicy.ABORT, gc.getCostCeilingOutcome());
    }

    @Test
    void memberTurnProjection_withholdsAuditCostFromStepData_yetCarriesTheTotal() {
        var snapshot = memberTurnSnapshot(0.05, 0.07);

        assertTrue(snapshot.getConversationSteps().stream()
                .flatMap(step -> step.getConversationStep().stream())
                .noneMatch(data -> MemoryKeys.AUDIT_COST.equals(data.getKey())),
                "returnDetailed withholds audit:* — the ledger cannot read the cost off the steps");
        assertEquals(0.12, snapshot.getConversationCost(), 1e-9);
    }

    // =================================================================
    // retireConversation — a member's ended conversation replaced
    // =================================================================

    @Test
    void retireConversation_keepsTheEndedConversationsSpend() {
        var gc = gc();
        GroupCostLedger.accumulateMemberCost(gc, AGENT_A, withCost(0.30));

        GroupCostLedger.retireConversation(gc, "agent-a", "conv-ended");
        // The fresh conversation starts its own running total from zero.
        GroupCostLedger.accumulateMemberCost(gc, AGENT_A, withCost(0.05));

        assertEquals(0.30, gc.getMemberCosts().get("agent-a:conv-ended"));
        assertEquals(0.05, gc.getMemberCosts().get("agent-a"));
        assertEquals(0.35, gc.getTotalCost(), 1e-9, "the fresh conversation must not overwrite the ended one's spend");
    }

    @Test
    void retireConversation_nothingRecorded_noOp() {
        var gc = gc();
        GroupCostLedger.retireConversation(gc, "agent-a", "conv-ended");
        assertTrue(gc.getMemberCosts().isEmpty());
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
        GroupCostLedger.accumulateMemberCost(gc, AGENT_A, withCost(0.10));
        var subConversation = gc();
        subConversation.setTotalCost(0.50);

        GroupCostLedger.accumulateNestedGroupCost(gc, AGENT_B, subConversation);

        assertEquals(0.60, gc.getTotalCost(), 1e-9);
    }

    // =================================================================
    // recordSystemCost (I9 summarizer spend)
    // =================================================================

    @Test
    void recordSystemCost_records_andDistinctKeysSum() {
        var gc = gc();
        GroupCostLedger.recordSystemCost(gc, "system:summarizer:full:10", 0.01);
        GroupCostLedger.recordSystemCost(gc, "system:summarizer:full:20", 0.02);
        // Idempotent re-run of the same operation replaces, never doubles.
        GroupCostLedger.recordSystemCost(gc, "system:summarizer:full:20", 0.02);

        assertEquals(0.03, gc.getTotalCost(), 1e-9);
    }

    @Test
    void recordSystemCost_rejectsNaNInfinityAndNonPositive() {
        var gc = gc();
        GroupCostLedger.recordSystemCost(gc, "k", Double.NaN);
        GroupCostLedger.recordSystemCost(gc, "k", Double.POSITIVE_INFINITY);
        GroupCostLedger.recordSystemCost(gc, "k", 0.0);
        GroupCostLedger.recordSystemCost(gc, "k", -1.0);
        GroupCostLedger.recordSystemCost(gc, null, 1.0);

        // One NaN in the map would make totalCost NaN, and every ceiling
        // comparison against NaN is false — the ceiling silently stops firing.
        assertTrue(gc.getMemberCosts().isEmpty());
        assertEquals(0.0, gc.getTotalCost());
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
