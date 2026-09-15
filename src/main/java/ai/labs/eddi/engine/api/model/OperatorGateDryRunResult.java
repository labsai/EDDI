/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.api.model;

/**
 * Outcome of a gate dry-run classification.
 *
 * @param policyPresent
 *            whether the agent document carries a non-empty
 *            {@code toolApprovals.requireApproval} at all — {@code false} means
 *            the gate is configured inert, and {@code gated} is then always
 *            {@code false}
 * @param gated
 *            whether the synthetic call would pause for approval
 * @param matchedPattern
 *            the {@code requireApproval} pattern that gated the call, or null
 *            when it was allowed (exempt, unmatched, or no policy)
 */
public record OperatorGateDryRunResult(boolean policyPresent, boolean gated, String matchedPattern) {
}
