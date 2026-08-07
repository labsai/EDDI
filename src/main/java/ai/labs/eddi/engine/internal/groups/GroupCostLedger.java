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
 * <b>Coverage note:</b> {@link MemoryKeys#AUDIT_COST} — the value this class
 * reads — sums the member conversation's LLM token spend (cascade step pricing,
 * or the task-level {@code inputPricePer1M}/{@code outputPricePer1M} for plain
 * calls) and its tracked tool cost. Pricing is config-driven with no built-in
 * provider price table, so a member whose LLM config carries no prices still
 * contributes {@code $0} of token cost here — for such members
 * {@code totalCost} remains a lower bound.
 * <p>
 * Both {@code memberCosts} and the recomputed {@code totalCost} are set, never
 * incremented by a delta — each call replaces the member's entry with its
 * latest known cumulative cost and re-sums the whole map. That makes a
 * duplicate call for the same turn idempotent (unlike accumulating a delta
 * twice, which would double-count) and keeps {@code totalCost} from drifting
 * out of sync with {@code memberCosts} under concurrent updates from a PARALLEL
 * phase's simultaneous member turns.
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
        accumulateMemberCost(gc, member != null ? member.agentId() : null, snapshot);
    }

    /**
     * @param attributionKey
     *            the {@code memberCosts} key to record under — normally the
     *            member's agent id, but the <em>conversation</em> key when a turn
     *            runs an existing agent in a separate conversation (I2's
     *            convergence judge). Costs are per-conversation and recorded by
     *            replacement, so attributing a second conversation under the same
     *            key would overwrite the first's total rather than add to it.
     */
    public static void accumulateMemberCost(GroupConversation gc, String attributionKey, SimpleConversationMemorySnapshot snapshot) {
        if (attributionKey == null || snapshot == null || snapshot.getConversationSteps() == null
                || snapshot.getConversationSteps().isEmpty()) {
            return;
        }
        var lastStep = snapshot.getConversationSteps().get(snapshot.getConversationSteps().size() - 1);
        if (lastStep == null || lastStep.getConversationStep() == null) {
            return;
        }
        for (var stepData : lastStep.getConversationStep()) {
            if (stepData != null && MemoryKeys.AUDIT_COST.equals(stepData.getKey()) && stepData.getValue() instanceof Number cost) {
                recordAndReSum(gc, attributionKey, cost.doubleValue());
                return;
            }
        }
    }

    /**
     * Rolls a nested {@code MemberType.GROUP} member's child discussion up into
     * this group's attribution, whole — the child's own {@code totalCost} already
     * reflects everything its own members accumulated (recursively, via this same
     * method for individual members).
     * <p>
     * Keyed per <em>child discussion</em>, not per member: {@code memberCosts}
     * records by replacement, which is only idempotent when one key means one
     * conversation's cumulative cost. Every turn of a GROUP member spawns a fresh
     * child discussion whose {@code totalCost} starts at 0, so keying by agentId
     * alone replaced child N−1's whole spend with child N's and only the last child
     * survived the sum. The suffix restores the map's invariant; a child without an
     * id (not yet persisted) falls back to the plain agentId.
     */
    public static void accumulateNestedGroupCost(GroupConversation gc, GroupMember member, GroupConversation subConversation) {
        if (subConversation == null) {
            return;
        }
        String attributionKey = subConversation.getId() != null
                ? member.agentId() + ":" + subConversation.getId()
                : member.agentId();
        recordAndReSum(gc, attributionKey, subConversation.getTotalCost());
    }

    /**
     * Attribution for spend the discussion's own machinery incurs — today the I9
     * transcript summarizer. {@code key} must be unique per priced operation (e.g.
     * {@code "system:summarizer:full:42"}, suffixed with the boundary it covered
     * through): the map records by replacement, so a re-run of the same operation
     * is idempotent while distinct operations sum.
     */
    public static void recordSystemCost(GroupConversation gc, String key, double cost) {
        if (key == null || cost <= 0.0) {
            return;
        }
        recordAndReSum(gc, key, cost);
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

    /**
     * Read-only "is the ceiling already blown?" — no transcript entry, no
     * {@code costCeilingOutcome}, no policy applied.
     * <p>
     * For callers that want to skip optional work rather than end the phase, where
     * {@link #enforceCeiling} would be wrong twice over: it would append a second
     * SKIPPED entry for an overspend already reported, and it would set the outcome
     * flag, converting "skip this optional extra" into "stop the phase". I2's
     * convergence judge is the case — declining to spend on an optional judge call
     * is not the same event as a phase running out of budget.
     */
    public static boolean wouldExceedCeiling(GroupConversation gc, ProtocolConfig protocol) {
        Double ceiling = protocol != null ? protocol.maxCostPerDiscussion() : null;
        if (ceiling == null) {
            return false;
        }
        // Exactly the inverse of enforceCeiling's "not exceeded" test, zero case and
        // all. Keeping only the `>` half here left a nested child that inherited a
        // fully-spent budget (ceiling 0.0, totalCost 0.0) reading as "budget
        // available", so the optional work this gate exists to skip — I2's
        // convergence judge, I4's whole dissent round, one call per dissenter — ran
        // anyway against a budget already gone. The two must agree: a caller that
        // asks "already blown?" and one that asks "stop now?" cannot disagree about
        // the same discussion.
        return ceiling <= 0.0 || gc.getTotalCost() > ceiling;
    }

    private static void recordAndReSum(GroupConversation gc, String agentId, double cost) {
        synchronized (gc.getMemberCosts()) {
            gc.getMemberCosts().put(agentId, cost);
            gc.setTotalCost(gc.getMemberCosts().values().stream().mapToDouble(Double::doubleValue).sum());
        }
    }
}
