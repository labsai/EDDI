/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.hitl;

import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;

import java.time.Duration;

/**
 * Store-level validation for HITL configuration. Rejects unusable values at
 * save time with an actionable message instead of silently degrading to
 * wait-forever at runtime (project rule: actionable errors, not silent
 * failures). The policy/granularity fields are enum-typed and validated by
 * Jackson at deserialization; only the ISO-8601 duration needs checking here.
 */
public final class HitlConfigValidation {

    private HitlConfigValidation() {
    }

    /** Validates the agent-level HITL config; no-op when absent. */
    public static void validate(AgentConfiguration.HitlConfig hitlConfig) {
        if (hitlConfig == null) {
            return;
        }
        validateApprovalTimeout(hitlConfig.getApprovalTimeout(),
                hitlConfig.getTimeoutPolicy() != null
                        && hitlConfig.getTimeoutPolicy() != HitlTimeoutPolicy.WAIT_INDEFINITELY);
    }

    /** Validates the group-level HITL config; no-op when absent. */
    public static void validate(AgentGroupConfiguration.HitlConfig hitlConfig) {
        if (hitlConfig == null) {
            return;
        }
        validateApprovalTimeout(hitlConfig.getApprovalTimeout(),
                hitlConfig.getTimeoutPolicy() != null
                        && hitlConfig.getTimeoutPolicy() != HitlTimeoutPolicy.WAIT_INDEFINITELY);
    }

    private static void validateApprovalTimeout(String approvalTimeout, boolean finitePolicy) {
        if (approvalTimeout == null || approvalTimeout.isBlank()) {
            if (finitePolicy) {
                throw new IllegalArgumentException(
                        "hitlConfig: a finite timeoutPolicy (AUTO_APPROVE/AUTO_REJECT/ABORT) requires an "
                                + "approvalTimeout (ISO-8601 duration, e.g. \"PT30M\") — without it the policy never fires");
            }
            return;
        }
        Duration duration;
        try {
            duration = Duration.parse(approvalTimeout);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "hitlConfig.approvalTimeout '" + approvalTimeout
                            + "' is not a valid ISO-8601 duration (expected e.g. \"PT30S\", \"PT15M\", \"PT2H\")");
        }
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(
                    "hitlConfig.approvalTimeout must be a positive duration, got '" + approvalTimeout + "'");
        }
    }
}
