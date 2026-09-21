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
import org.jboss.logging.Logger;

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

        validateRules(cfg, fieldPath);
    }

    /**
     * Validates {@code toolApprovals.rules} — the per-tool friction overrides.
     * <p>
     * {@code match} goes through the same {@link ToolApprovalPatterns#validate} as
     * a gating pattern, so a rule that could never match is refused rather than
     * saved. That matters less here than for {@code requireApproval} (an
     * unmatchable rule costs the designer their intended friction, not the gate
     * itself), but the failure looks identical from the outside — a config that
     * reads as if it were protecting something — so it is refused the same way.
     */
    private static void validateRules(ToolApprovalsConfig cfg, String fieldPath) {
        List<ToolApprovalsConfig.ApprovalRule> rules = cfg.getRules();
        if (rules == null || rules.isEmpty()) {
            return;
        }
        if (cfg.getRequireApproval() == null || cfg.getRequireApproval().isEmpty()) {
            throw new IllegalArgumentException(fieldPath
                    + ".rules has no effect without requireApproval patterns — rules tune the friction on a gated"
                    + " call, they never gate one");
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < rules.size(); i++) {
            String rulePath = fieldPath + ".rules[" + i + "]";
            ToolApprovalsConfig.ApprovalRule rule = rules.get(i);
            if (rule == null) {
                throw new IllegalArgumentException(rulePath + " must not be null");
            }
            Optional<String> error = ToolApprovalPatterns.validate(rule.getMatch());
            if (error.isPresent()) {
                throw new IllegalArgumentException(rulePath + ".match: " + error.get());
            }
            if (!seen.add(rule.getMatch())) {
                throw new IllegalArgumentException("duplicate rule match '" + rule.getMatch() + "' in " + fieldPath
                        + ".rules — only one would ever apply; merge them");
            }
            // A rule whose pattern is string-identical to an exempt pattern is provably
            // dead: any call it could match is exempt, so it is never gated, so no rule
            // is ever resolved for it. Only the exact-equality case is checked — a rule
            // may legitimately OVERLAP an exempt pattern while still covering gated
            // calls (e.g. 'http.*:*' alongside an exempt 'http.get:*'), and deciding
            // that in general would mean reasoning about globs over an unknown tool
            // set. Exact equality needs no such reasoning.
            if (cfg.getExempt() != null && cfg.getExempt().contains(rule.getMatch())) {
                throw new IllegalArgumentException(rulePath + ".match '" + rule.getMatch()
                        + "' is also an exempt pattern, so it can never apply — an exempt call is never gated, and a"
                        + " rule only tunes a gated call. Remove the rule or the exemption");
            }
            // Format only — the "a finite policy needs a duration" check is rule-scoped
            // below, because a rule may legitimately inherit the enclosing timeout.
            validateApprovalTimeout(rule.getApprovalTimeout(), false);
            boolean finitePolicy = rule.getTimeoutPolicy() != null
                    && rule.getTimeoutPolicy() != HitlTimeoutPolicy.WAIT_INDEFINITELY;
            if (finitePolicy && isNullOrBlank(rule.getApprovalTimeout()) && isNullOrBlank(cfg.getApprovalTimeout())) {
                throw new IllegalArgumentException(rulePath + ".timeoutPolicy is " + rule.getTimeoutPolicy()
                        + ", which never fires without a duration — set " + rulePath + ".approvalTimeout (ISO-8601,"
                        + " e.g. \"PT5M\") or " + fieldPath + ".approvalTimeout for the rule to inherit");
            }
            checkReasonLength(rule.getPauseReason(), rulePath + ".pauseReason");
            checkReasonLength(rule.getPendingMessage(), rulePath + ".pendingMessage");
        }
    }

    private static boolean isNullOrBlank(String value) {
        return value == null || value.isBlank();
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
            Logger.getLogger(HitlConfigValidation.class).warn(
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
