/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.oversight.autonomy;

import ai.labs.eddi.configs.hitl.model.AutonomyPolicyConfig;
import ai.labs.eddi.engine.hitl.oversight.analytics.ApprovalDecision;
import ai.labs.eddi.engine.hitl.oversight.autonomy.AutonomyState.Evidence;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * Decides, from decision facts alone, whether a gated category has earned a
 * recommendation to stop asking. Pure: no store, no clock.
 * <p>
 * Evidence counts only what a <em>verified human</em> decided on an
 * <em>evidence channel</em> — approvals, edited approvals and rejections. A
 * timeout, a system decision, an anonymous one, an MCP decision (unless the
 * policy lists {@code MCP}) and every pre-6.7 legacy row are not evidence: an
 * agent or a model can produce none of them.
 *
 * @since 6.7.0
 */
public final class AutonomyEvaluator {

    private AutonomyEvaluator() {
    }

    /** The verdict of one evaluation. */
    public record Result(Evidence evidence, boolean eligible, String reason) {
    }

    /**
     * Where the evidence window starts: the latest of {@code now - window}, the
     * last evidence reset and the end of the cooldown after the last demotion.
     */
    public static Instant windowStart(AutonomyPolicyConfig.Resolved policy, AutonomyState state, Instant now) {
        Instant start = now.minus(policy.window());
        if (state != null && state.evidenceResetAt() != null && state.evidenceResetAt().isAfter(start)) {
            start = state.evidenceResetAt();
        }
        if (state != null && state.lastDemotionAt() != null) {
            Instant cooledDown = state.lastDemotionAt().plus(policy.cooldownAfterDemotion());
            if (cooledDown.isAfter(start)) {
                start = cooledDown;
            }
        }
        return start;
    }

    /** Whether one decision counts as evidence under the policy. */
    public static boolean counts(ApprovalDecision d, AutonomyPolicyConfig.Resolved policy, Instant from) {
        return d != null && !d.legacy() && d.deciderKind() == ApprovalDecision.DeciderKind.HUMAN_VERIFIED
                && d.channel() != null && policy.evidenceChannels().contains(d.channel()) && d.humanOutcome()
                && d.decidedAt() != null && !d.decidedAt().isBefore(from);
    }

    /**
     * Evaluates a category.
     *
     * @param decisions
     *            the category's decisions (any superset of the window; filtered
     *            here)
     * @param state
     *            the stored state, or null
     */
    public static Result evaluate(AutonomyPolicyConfig.Resolved policy, Collection<ApprovalDecision> decisions, AutonomyState state,
                                  Instant now) {
        Instant from = windowStart(policy, state, now);
        int approved = 0;
        int edited = 0;
        int rejected = 0;
        boolean demoteCodeSeen = false;
        List<String> demoteCodes = policy.demoteOnReasonCodes();
        for (ApprovalDecision d : decisions) {
            if (!counts(d, policy, from)) {
                continue;
            }
            switch (d.outcome()) {
                case "APPROVED" -> approved++;
                case "APPROVED_EDITED" -> edited++;
                default -> {
                    rejected++;
                    if (d.reasonCode() != null && demoteCodes.contains(d.reasonCode())) {
                        demoteCodeSeen = true;
                    }
                }
            }
        }
        int n = approved + edited + rejected;
        double rate = n == 0 ? 0.0 : (double) approved / n;
        Evidence evidence = new Evidence(n, approved, edited, rejected, rate, from, now);
        if (demoteCodeSeen) {
            return new Result(evidence, false, "a rejection in the window carries a demote reason code");
        }
        if (n < policy.promoteAfter()) {
            return new Result(evidence, false, n + " of " + policy.promoteAfter() + " decisions");
        }
        if (rate < policy.minUneditedRate()) {
            return new Result(evidence, false, "unedited approval rate " + round(rate) + " is below " + policy.minUneditedRate());
        }
        if (state != null && state.suppressUntilCount() != null && n < state.suppressUntilCount()) {
            return new Result(evidence, false, "dismissed; waiting for " + state.suppressUntilCount() + " decisions");
        }
        return new Result(evidence, true, "eligible");
    }

    private static double round(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }
}
