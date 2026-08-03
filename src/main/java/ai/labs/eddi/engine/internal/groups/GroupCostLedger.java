/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal.groups;

import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.GroupMember;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.engine.memory.MemoryKeys;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;

/**
 * Harvests per-member dollar cost into a {@link GroupConversation}'s
 * {@code memberCosts}/{@code totalCost} (Wave 0, F5). No feature reads these
 * fields yet — I1 (cost ceiling + attribution) is the eventual consumer.
 * <p>
 * <b>Known gap (V1, confirmed):</b> {@link MemoryKeys#AUDIT_COST} — the value
 * this class reads — is written by {@code LlmTask.accumulateCost} from
 * {@code cascadeCostUsd + toolCostUsd} only. There is no dollar price table for
 * a non-cascade model completion anywhere in this codebase today
 * ({@code LlmTask}'s own Javadoc says so directly), so a member's own turn
 * contributes {@code $0} here unless the group's LLM config uses the
 * multi-model cascade (priced) or the turn called a priced tool. This is a
 * real, known undercount for the common case, not a bug in this class — I1 is
 * where model-call cost recording gets added, reusing whatever price source it
 * settles on. Until then, {@code totalCost} is a lower bound, not the true
 * total.
 * <p>
 * Both {@code memberCosts} and the recomputed {@code totalCost} are set, never
 * incremented by a delta — each call replaces the member's entry with its
 * latest known cumulative cost and resums the whole map. That makes a duplicate
 * call for the same turn idempotent (unlike accumulating a delta twice, which
 * would double-count) and keeps {@code totalCost} from drifting out of sync
 * with {@code memberCosts} under concurrent updates from a PARALLEL phase's
 * simultaneous member turns.
 *
 * @author ginccc
 */
public final class GroupCostLedger {

    private GroupCostLedger() {
    }

    /**
     * Reads {@link MemoryKeys#AUDIT_COST} — the member's private conversation's own
     * cumulative tracked cost as of this turn — off the last step of
     * {@code snapshot} and records it as that member's attribution. A no-op if the
     * snapshot has no steps or the key is absent (a normal outcome today, per this
     * class's own Javadoc — not an error).
     */
    public static void accumulateMemberCost(GroupConversation gc, GroupMember member, SimpleConversationMemorySnapshot snapshot) {
        if (snapshot == null || snapshot.getConversationSteps() == null || snapshot.getConversationSteps().isEmpty()) {
            return;
        }
        var lastStep = snapshot.getConversationSteps().get(snapshot.getConversationSteps().size() - 1);
        if (lastStep == null || lastStep.getConversationStep() == null) {
            return;
        }
        for (var stepData : lastStep.getConversationStep()) {
            if (stepData != null && MemoryKeys.AUDIT_COST.equals(stepData.getKey()) && stepData.getValue() instanceof Number cost) {
                recordAndResum(gc, member.agentId(), cost.doubleValue());
                return;
            }
        }
    }

    /**
     * Rolls a nested {@code MemberType.GROUP} member's child discussion up into
     * this group's attribution, whole — the child's own {@code totalCost} already
     * reflects everything its own members accumulated (recursively, via this same
     * method for individual members).
     */
    public static void accumulateNestedGroupCost(GroupConversation gc, GroupMember member, GroupConversation subConversation) {
        if (subConversation == null) {
            return;
        }
        recordAndResum(gc, member.agentId(), subConversation.getTotalCost());
    }

    private static void recordAndResum(GroupConversation gc, String agentId, double cost) {
        synchronized (gc.getMemberCosts()) {
            gc.getMemberCosts().put(agentId, cost);
            gc.setTotalCost(gc.getMemberCosts().values().stream().mapToDouble(Double::doubleValue).sum());
        }
    }
}
