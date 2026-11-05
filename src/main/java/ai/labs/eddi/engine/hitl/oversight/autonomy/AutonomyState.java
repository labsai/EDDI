/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.oversight.autonomy;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The earned-autonomy state of one (agent, approval category): its current
 * recommendation and its suspension, in one compare-and-set document. History
 * is in the audit ledger ({@code autonomy.*} entries); this row holds only the
 * present.
 *
 * @param evidenceResetAt
 *            evidence before this instant no longer counts (set by a demotion
 *            and by a rejection carrying a demote reason code)
 * @param lastDemotionAt
 *            when the category was last suspended
 * @param suppressUntilCount
 *            after a dismissal: no new recommendation until the evidence count
 *            reaches this
 * @param version
 *            compare-and-set counter; 0 means "not stored yet"
 * @since 6.7.0
 */
public record AutonomyState(String agentId, String category, Instant evidenceResetAt, Instant lastDemotionAt,
        Recommendation recommendation, Suspension suspension, Integer suppressUntilCount, long version) {

    /** {@code agentId} of the engine's own marker rows (never an agent). */
    public static final String SYSTEM_AGENT_ID = "__system__";

    /** Whether this is an engine marker row rather than an agent's category. */
    public boolean systemRow() {
        return SYSTEM_AGENT_ID.equals(agentId);
    }

    /** A fresh, unstored state. */
    public static AutonomyState initial(String agentId, String category) {
        return new AutonomyState(agentId, category, null, null, null, null, null, 0L);
    }

    /** Whether a suspension is active. */
    public boolean suspended() {
        return suspension != null && suspension.active();
    }

    /** Whether an OPEN recommendation exists. */
    public boolean hasOpenRecommendation() {
        return recommendation != null && recommendation.status() == Recommendation.Status.OPEN;
    }

    public AutonomyState withRecommendation(Recommendation r) {
        return new AutonomyState(agentId, category, evidenceResetAt, lastDemotionAt, r, suspension, suppressUntilCount, version);
    }

    public AutonomyState withSuspension(Suspension s) {
        return new AutonomyState(agentId, category, evidenceResetAt, lastDemotionAt, recommendation, s, suppressUntilCount, version);
    }

    public AutonomyState withEvidenceResetAt(Instant at) {
        return new AutonomyState(agentId, category, at, lastDemotionAt, recommendation, suspension, suppressUntilCount, version);
    }

    public AutonomyState withLastDemotionAt(Instant at) {
        return new AutonomyState(agentId, category, evidenceResetAt, at, recommendation, suspension, suppressUntilCount, version);
    }

    public AutonomyState withSuppressUntilCount(Integer count) {
        return new AutonomyState(agentId, category, evidenceResetAt, lastDemotionAt, recommendation, suspension, count, version);
    }

    public AutonomyState withVersion(long v) {
        return new AutonomyState(agentId, category, evidenceResetAt, lastDemotionAt, recommendation, suspension, suppressUntilCount, v);
    }

    /** The evidence a recommendation rests on. */
    public record Evidence(int n, int approved, int edited, int rejected, double uneditedRate, Instant windowFrom, Instant windowTo) {
    }

    /**
     * A recommendation for one category.
     *
     * @param pattern
     *            the tool pattern the change is about; null for an advisory one
     * @param applicable
     *            whether it can be applied automatically (tool categories); RULE
     *            and group categories are advisory — the text says what to change
     * @param proposedHitlConfig
     *            {@code {toolApprovals, oversight}} as they would be after the
     *            change; null for an advisory one
     * @param changes
     *            the change in words, for the preview
     * @param leavesNoGate
     *            applying it leaves the agent without any gated tool
     */
    public record Recommendation(String id, Kind kind, Status status, Instant computedAt, Integer basedOnAgentVersion, Evidence evidence,
            String pattern, boolean applicable, Map<String, Object> proposedHitlConfig, List<String> changes, boolean leavesNoGate,
            String appliedBy, Instant appliedAt, Integer resultingAgentVersion, String dismissedBy, Instant dismissedAt,
            String dismissNote) {

        /** What a recommendation proposes. */
        public enum Kind {
            /** Stop asking: ungate the category (sampling it instead). */
            PROMOTE,
            /** Make a suspension permanent: gate the category again in configuration. */
            REVERT
        }

        /** Where a recommendation is. */
        public enum Status {
            OPEN, APPLIED, DISMISSED, STALE
        }

        public Recommendation withStatus(Status newStatus) {
            return new Recommendation(id, kind, newStatus, computedAt, basedOnAgentVersion, evidence, pattern, applicable,
                    proposedHitlConfig, changes, leavesNoGate, appliedBy, appliedAt, resultingAgentVersion, dismissedBy, dismissedAt,
                    dismissNote);
        }

        public Recommendation applied(String by, Instant at, Integer resultingVersion) {
            return new Recommendation(id, kind, Status.APPLIED, computedAt, basedOnAgentVersion, evidence, pattern, applicable,
                    proposedHitlConfig, changes, leavesNoGate, by, at, resultingVersion, dismissedBy, dismissedAt, dismissNote);
        }

        public Recommendation dismissed(String by, Instant at, String note) {
            return new Recommendation(id, kind, Status.DISMISSED, computedAt, basedOnAgentVersion, evidence, pattern, applicable,
                    proposedHitlConfig, changes, leavesNoGate, appliedBy, appliedAt, resultingAgentVersion, by, at, note);
        }
    }

    /**
     * A suspension: the category's pattern is gated again at runtime.
     *
     * @param triggerRef
     *            the sample or decision that caused it, or null for a manual one
     */
    public record Suspension(boolean active, String pattern, Instant since, Trigger trigger, String triggerRef, String reasonCode,
            String suspendedBy, String liftedBy, Instant liftedAt, String liftNote) {

        /** What caused a suspension. */
        public enum Trigger {
            /** A flagged sample whose reason code is a demote code. */
            REASON_CODE,
            /** Enough flagged samples within the window. */
            SAMPLE_FLAGGED,
            /** A human suspended it. */
            MANUAL
        }

        public Suspension lifted(String by, Instant at, String note) {
            return new Suspension(false, pattern, since, trigger, triggerRef, reasonCode, suspendedBy, by, at, note);
        }
    }
}
