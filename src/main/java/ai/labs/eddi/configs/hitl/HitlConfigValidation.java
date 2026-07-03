/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.hitl;

import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig;
import ai.labs.eddi.engine.hitl.tools.ToolApprovalPatterns;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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

    /**
     * Upper bound for the designer-supplied pause reason (approver-facing text).
     */
    public static final int MAX_PAUSE_REASON_LENGTH = 500;

    /** Validates the agent-level HITL config; no-op when absent. */
    public static void validate(AgentConfiguration.HitlConfig hitlConfig) {
        if (hitlConfig == null) {
            return;
        }
        validateApprovalTimeout(hitlConfig.getApprovalTimeout(),
                hitlConfig.getTimeoutPolicy() != null
                        && hitlConfig.getTimeoutPolicy() != HitlTimeoutPolicy.WAIT_INDEFINITELY);
        if (hitlConfig.getPauseReason() != null
                && hitlConfig.getPauseReason().length() > MAX_PAUSE_REASON_LENGTH) {
            throw new IllegalArgumentException(
                    "hitlConfig.pauseReason exceeds the maximum length of " + MAX_PAUSE_REASON_LENGTH
                            + " characters — it is approver-facing summary text, not documentation");
        }
        validateToolApprovals(hitlConfig.getToolApprovals(), "hitlConfig.toolApprovals");
        warnIfToolApprovalsInheritsAutoApprove(hitlConfig);
    }

    /**
     * Validates a {@link ToolApprovalsConfig} (used both agent-level and as a
     * per-LLM-task override). Rejects unusable values at save time with an
     * actionable message. {@code fieldPath} prefixes messages so the source is
     * clear (e.g. {@code "hitlConfig.toolApprovals"} or
     * {@code "langchain.task[2].toolApprovals"}). No-op when absent.
     */
    public static void validateToolApprovals(ToolApprovalsConfig cfg, String fieldPath) {
        if (cfg == null) {
            return;
        }
        Set<String> require = validatePatternList(cfg.getRequireApproval(), fieldPath + ".requireApproval");
        Set<String> exempt = validatePatternList(cfg.getExempt(), fieldPath + ".exempt");

        if (!exempt.isEmpty() && require.isEmpty()) {
            throw new IllegalArgumentException(fieldPath + ".exempt has no effect without requireApproval patterns");
        }
        for (String pattern : exempt) {
            if (require.contains(pattern)) {
                throw new IllegalArgumentException("pattern '" + pattern + "' appears in both " + fieldPath
                        + ".requireApproval and .exempt; exempt would win — remove one");
            }
        }

        validateRange(cfg.getMaxPausesPerTurn(), 1, 10, fieldPath + ".maxPausesPerTurn");
        validateRange(cfg.getMaxAutoApprovalsPerTurn(), 0, 10, fieldPath + ".maxAutoApprovalsPerTurn");

        if (cfg.getOnNoProgress() != null
                && !List.of("WAIT_FOR_HUMAN", "AUTO_REJECT", "ABORT").contains(cfg.getOnNoProgress())) {
            throw new IllegalArgumentException(fieldPath + ".onNoProgress '" + cfg.getOnNoProgress()
                    + "' must be one of WAIT_FOR_HUMAN, AUTO_REJECT, ABORT");
        }

        if (cfg.getInGroupTurns() != null) {
            if ("INBOX".equals(cfg.getInGroupTurns())) {
                throw new IllegalArgumentException(
                        fieldPath + ".inGroupTurns=INBOX is reserved for a future version; use REJECT");
            }
            if (!"REJECT".equals(cfg.getInGroupTurns())) {
                throw new IllegalArgumentException(
                        fieldPath + ".inGroupTurns '" + cfg.getInGroupTurns() + "' must be REJECT (INBOX is reserved)");
            }
        }

        // Tool-pause timeout override (finite policy needs a positive duration).
        validateApprovalTimeout(cfg.getApprovalTimeout(),
                cfg.getTimeoutPolicy() != null && cfg.getTimeoutPolicy() != HitlTimeoutPolicy.WAIT_INDEFINITELY);

        checkReasonLength(cfg.getPauseReason(), fieldPath + ".pauseReason");
        checkReasonLength(cfg.getPendingMessage(), fieldPath + ".pendingMessage");
    }

    private static Set<String> validatePatternList(List<String> patterns, String fieldPath) {
        Set<String> seen = new HashSet<>();
        if (patterns == null) {
            return seen;
        }
        for (int i = 0; i < patterns.size(); i++) {
            String pattern = patterns.get(i);
            Optional<String> error = ToolApprovalPatterns.validate(pattern);
            if (error.isPresent()) {
                throw new IllegalArgumentException(fieldPath + "[" + i + "]: " + error.get());
            }
            if (!seen.add(pattern)) {
                throw new IllegalArgumentException("duplicate pattern '" + pattern + "' in " + fieldPath);
            }
        }
        return seen;
    }

    private static void validateRange(Integer value, int min, int max, String fieldPath) {
        if (value != null && (value < min || value > max)) {
            throw new IllegalArgumentException(fieldPath + " must be between " + min + " and " + max + ", got " + value);
        }
    }

    private static void checkReasonLength(String value, String fieldPath) {
        if (value != null && value.length() > MAX_PAUSE_REASON_LENGTH) {
            throw new IllegalArgumentException(
                    fieldPath + " exceeds the maximum length of " + MAX_PAUSE_REASON_LENGTH + " characters");
        }
    }

    /**
     * WARN (never a 400): agent-level AUTO_APPROVE does not apply to tool approvals
     * unless the toolApprovals block sets its own timeoutPolicy — tool pauses
     * otherwise WAIT_INDEFINITELY (see Task 10 effective-policy rule). v1 has no
     * warnings channel on the store response, so this is a log only.
     */
    private static void warnIfToolApprovalsInheritsAutoApprove(AgentConfiguration.HitlConfig hitlConfig) {
        ToolApprovalsConfig cfg = hitlConfig.getToolApprovals();
        if (cfg != null
                && cfg.getTimeoutPolicy() == null
                && hitlConfig.getTimeoutPolicy() == HitlTimeoutPolicy.AUTO_APPROVE) {
            org.jboss.logging.Logger.getLogger(HitlConfigValidation.class).warn(
                    "hitlConfig: agent-level AUTO_APPROVE does not apply to tool approvals; tool pauses will "
                            + "WAIT_INDEFINITELY unless hitlConfig.toolApprovals.timeoutPolicy is set explicitly");
        }
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
