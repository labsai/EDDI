/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.oversight.autonomy;

import ai.labs.eddi.configs.hitl.HitlTimeoutPolicy;
import ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig;
import ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig.ApprovalRule;
import ai.labs.eddi.engine.hitl.tools.ToolApprovalPatterns;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The runtime re-gating of suspended earned-autonomy categories: what the
 * approval gate asks, and how the overlay copy of a tool-approval config is
 * built.
 * <p>
 * "Exempt beats requireApproval" cannot be overridden through configuration, so
 * the gate itself applies a suspension: a call it would have <em>allowed</em>
 * (exempt or unmatched) that matches a suspended pattern is gated with the
 * reason {@code suspended:<pattern>}. A suspension never un-gates anything.
 *
 * @since 6.7.0
 */
public final class SuspendedGate {

    /** Prefix of the gate reason of a call gated by a suspension. */
    public static final String REASON_PREFIX = "suspended:";

    /** Pause reason of the synthetic rule of a suspended pattern. */
    public static final String PAUSE_REASON = "This kind of call ran without approval until a reviewer flagged one; it is suspended "
            + "and needs approval again until someone lifts the suspension.";

    private SuspendedGate() {
    }

    /** Whether the config carries no suspended pattern. */
    public static boolean none(ToolApprovalsConfig cfg) {
        return cfg == null || cfg.getSuspendedPatterns() == null || cfg.getSuspendedPatterns().isEmpty();
    }

    /**
     * The first suspended pattern matching any of the call's addresses, or null.
     *
     * @param addresses
     *            the call's addresses, as {@code ToolApprovalGate.addressesOf}
     *            derives them
     */
    public static String match(ToolApprovalsConfig cfg, List<String> addresses) {
        if (none(cfg) || addresses == null) {
            return null;
        }
        for (String pattern : cfg.getSuspendedPatterns()) {
            for (String address : addresses) {
                if (pattern != null && ToolApprovalPatterns.matches(pattern, address)) {
                    return pattern;
                }
            }
        }
        return null;
    }

    /**
     * A copy of {@code cfg} that also gates the suspended patterns — never the
     * cached instance itself.
     * <ul>
     * <li>{@code suspendedPatterns} is set (the gate reads it);</li>
     * <li>a synthetic rule {@code {match: P, timeoutPolicy: WAIT_INDEFINITELY,
     * approvalCategory: C}} is appended for each suspended pattern, so the existing
     * "strictest rule wins" timeout resolution makes a suspended call wait for a
     * human, and its decision is filed under the suspended category;</li>
     * <li>an existing rule whose pattern overlaps a suspended one and would
     * auto-approve on timeout waits instead — a suspension must never
     * auto-execute.</li>
     * </ul>
     * Runtime only: the copy is never validated or persisted as configuration.
     *
     * @param cfg
     *            the effective config, or null for none
     * @param suspended
     *            suspended pattern to its category, in a stable order
     * @return {@code cfg} itself when nothing is suspended, or when it already
     *         carries an overlay
     */
    public static ToolApprovalsConfig overlay(ToolApprovalsConfig cfg, Map<String, String> suspended) {
        if (suspended == null || suspended.isEmpty() || ToolApprovalsConfig.isUndetermined(cfg) || !none(cfg)) {
            return cfg;
        }
        ToolApprovalsConfig copy = copyOf(cfg);
        copy.setSuspendedPatterns(List.copyOf(new LinkedHashMap<>(suspended).keySet()));
        List<ApprovalRule> rules = new ArrayList<>();
        if (cfg != null && cfg.getRules() != null) {
            for (ApprovalRule rule : cfg.getRules()) {
                rules.add(overlapsAutoApprove(rule, suspended.keySet()) ? waiting(rule) : rule);
            }
        }
        for (var entry : suspended.entrySet()) {
            var synthetic = new ApprovalRule();
            synthetic.setMatch(entry.getKey());
            synthetic.setTimeoutPolicy(HitlTimeoutPolicy.WAIT_INDEFINITELY);
            synthetic.setApprovalCategory(entry.getValue());
            synthetic.setPauseReason(PAUSE_REASON);
            rules.add(synthetic);
        }
        copy.setRules(List.copyOf(rules));
        return copy;
    }

    static boolean overlapsAutoApprove(ApprovalRule rule, Iterable<String> suspended) {
        if (rule == null || rule.getMatch() == null || rule.getTimeoutPolicy() != HitlTimeoutPolicy.AUTO_APPROVE) {
            return false;
        }
        for (String pattern : suspended) {
            // Approximate glob overlap: either pattern, read as an address, matches the
            // other. Errs towards waiting, which only ever adds friction.
            if (ToolApprovalPatterns.matches(pattern, rule.getMatch()) || ToolApprovalPatterns.matches(rule.getMatch(), pattern)) {
                return true;
            }
        }
        return false;
    }

    private static ApprovalRule waiting(ApprovalRule rule) {
        var copy = new ApprovalRule();
        copy.setMatch(rule.getMatch());
        copy.setTimeoutPolicy(HitlTimeoutPolicy.WAIT_INDEFINITELY);
        copy.setApprovalTimeout(rule.getApprovalTimeout());
        copy.setPauseReason(rule.getPauseReason());
        copy.setApprovalCategory(rule.getApprovalCategory());
        copy.setPendingMessage(rule.getPendingMessage());
        return copy;
    }

    private static ToolApprovalsConfig copyOf(ToolApprovalsConfig cfg) {
        var copy = new ToolApprovalsConfig();
        if (cfg == null) {
            return copy;
        }
        copy.setRequireApproval(cfg.getRequireApproval());
        copy.setExempt(cfg.getExempt());
        copy.setMaxPausesPerTurn(cfg.getMaxPausesPerTurn());
        copy.setMaxAutoApprovalsPerTurn(cfg.getMaxAutoApprovalsPerTurn());
        copy.setOnNoProgress(cfg.getOnNoProgress());
        copy.setApprovalTimeout(cfg.getApprovalTimeout());
        copy.setTimeoutPolicy(cfg.getTimeoutPolicy());
        copy.setPauseReason(cfg.getPauseReason());
        copy.setPendingMessage(cfg.getPendingMessage());
        copy.setInGroupTurns(cfg.getInGroupTurns());
        copy.setRules(cfg.getRules());
        copy.setDeciders(cfg.getDeciders());
        copy.setAllowArgumentAmendments(cfg.getAllowArgumentAmendments());
        copy.setRequirePauseId(cfg.getRequirePauseId());
        return copy;
    }
}
