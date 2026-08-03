/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal.groups;

import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DiscussionPhase;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.GroupMember;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.PhaseType;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ProtocolConfig;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntry;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntryType;
import ai.labs.eddi.engine.memory.MemoryKeys;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;

import java.time.Instant;
import java.util.Locale;

/**
 * Harvests per-member dollar cost into a {@link GroupConversation}'s
 * {@code memberCosts}/{@code totalCost} (Wave 0, F5), and enforces
 * {@code ProtocolConfig#maxCostPerDiscussion} against it (I1).
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

    /**
     * The pre-turn/pre-wave ceiling check (I1). If
     * {@code protocol.maxCostPerDiscussion()} is set and {@code gc.getTotalCost()}
     * has passed it, records a {@code SKIPPED} transcript entry naming the ceiling
     * and the policy, sets {@code gc.getCostCeilingOutcome()} to
     * {@code protocol.onCostExceeded()} for the caller's own phase loop to act on
     * (jump to synthesis, or fail the discussion), and returns {@code true} so the
     * caller stops scheduling more turns/waves for this phase. A {@code null}
     * ceiling (the default, unlimited) always returns {@code false} without
     * touching the transcript.
     * <p>
     * Deliberately no try/catch: {@code gc.getTotalCost()} is a plain in-memory
     * field read (F5 already accumulated it turn-by-turn; there is no separate
     * fallible read at check time), so there is nothing here that can throw. A
     * cost-tracking failure that genuinely needs guarding against would be inside
     * {@link #accumulateMemberCost} itself, which already no-ops defensively on
     * missing/malformed cost data rather than throwing.
     */
    public static boolean enforceCeiling(GroupConversation gc, ProtocolConfig protocol, int phaseIdx, DiscussionPhase phase) {
        Double ceiling = protocol.maxCostPerDiscussion();
        // `<=`, so the ceiling is a budget to spend rather than a value to stop
        // short of — EXCEPT at exactly 0, which a nested child inherits when its
        // parent has already spent its whole budget. Treating that as "one free
        // turn" would let every nested group overspend a fully-consumed parent by a
        // turn (and, under SYNTHESIZE_NOW's synthesis exemption, by two).
        if (ceiling == null || (gc.getTotalCost() <= ceiling && ceiling > 0.0)) {
            return false;
        }
        // SYNTHESIZE_NOW promises a concluding answer despite the overspend, so the
        // synthesis turn it jumps to must itself be exempt — otherwise this gate
        // blocks the very phase the policy exists to reach, and SYNTHESIZE_NOW
        // degenerates into "stop everything", i.e. exactly ABORT minus the FAILED
        // state. The overshoot is bounded (one synthesis phase) and deliberate.
        // ABORT gets no exemption: it fails the discussion outright.
        if (protocol.onCostExceeded() == ProtocolConfig.CostPolicy.SYNTHESIZE_NOW && phase.type() == PhaseType.SYNTHESIS) {
            return false;
        }
        gc.setCostCeilingOutcome(protocol.onCostExceeded());
        // Locale.ROOT, not the default: this string goes into an audit transcript and
        // (via the operator-facing log) into incident triage. A default-locale format
        // renders "$1,50" on a German JVM and "$1.50" on a US one for the same spend,
        // which makes the same message unparseable and un-greppable across a fleet.
        String message = String.format(Locale.ROOT, "Cost ceiling reached: $%.2f of $%.2f — %s",
                gc.getTotalCost(), ceiling, protocol.onCostExceeded());
        gc.getTranscript().add(new TranscriptEntry(
                null, "System", null, phaseIdx, phase.name(),
                TranscriptEntryType.SKIPPED, Instant.now(), message, null));
        return true;
    }

    private static void recordAndResum(GroupConversation gc, String agentId, double cost) {
        synchronized (gc.getMemberCosts()) {
            gc.getMemberCosts().put(agentId, cost);
            gc.setTotalCost(gc.getMemberCosts().values().stream().mapToDouble(Double::doubleValue).sum());
        }
    }
}
