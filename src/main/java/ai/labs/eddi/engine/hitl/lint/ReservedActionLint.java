/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.lint;

import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.rules.model.RuleConfiguration;
import ai.labs.eddi.configs.rules.model.RuleGroupConfiguration;
import ai.labs.eddi.configs.rules.model.RuleSetConfiguration;
import ai.labs.eddi.engine.hitl.tools.ToolApprovalPatterns;
import ai.labs.eddi.engine.lifecycle.IConversation;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Save-time lints for the HITL surface. Both checks here are strictly non-fatal
 * WARNINGS — a legitimate action name may legally resemble a reserved name, and
 * a designer may configure {@code hitlConfig} before wiring up the rule that
 * pauses the conversation, so neither check may ever reject a save or a
 * deployment.
 * <p>
 * Reuses {@link ToolApprovalPatterns#levenshtein(String, String)} (Task 2) as
 * the single Levenshtein implementation in the codebase — this class does not
 * duplicate it.
 */
public final class ReservedActionLint {

    /**
     * The four reserved action names that carry special pipeline meaning (see
     * {@link IConversation}). An agent-authored action that near-misses one of
     * these is almost always a typo, since designers are told to use dedicated
     * identifiers instead (see AGENTS.md §5.3).
     */
    public static final List<String> RESERVED_ACTIONS = List.of(
            IConversation.PAUSE_CONVERSATION,
            IConversation.STOP_CONVERSATION,
            IConversation.CONVERSATION_START,
            IConversation.CONVERSATION_END);

    /** Inclusive upper bound on Levenshtein distance for a "near-miss" match. */
    private static final int NEAR_MISS_THRESHOLD = 2;

    private ReservedActionLint() {
    }

    /**
     * Scans every action name in every rule of the given ruleset for near-misses of
     * a reserved action name. An action is flagged when it is a case-variant of a
     * reserved action OR within Levenshtein distance {@value #NEAR_MISS_THRESHOLD}
     * of one, and is NOT an exact (case-sensitive) match (exact matches are the
     * legitimate, intentional use of a reserved action and are never flagged).
     * <p>
     * Non-fatal by construction: returns human-readable warning messages for the
     * caller to log/collect. Never throws, never rejects — a legitimate action may
     * legally resemble a reserved name.
     *
     * @return warning messages, one per distinct near-miss action name found (in
     *         encounter order); empty if none.
     */
    public static List<String> checkReservedActionNearMisses(RuleSetConfiguration ruleSet) {
        if (ruleSet == null || ruleSet.getBehaviorGroups() == null) {
            return List.of();
        }

        Set<String> flaggedActions = new LinkedHashSet<>();
        List<String> warnings = new java.util.ArrayList<>();

        for (RuleGroupConfiguration group : ruleSet.getBehaviorGroups()) {
            if (group == null || group.getRules() == null) {
                continue;
            }
            for (RuleConfiguration rule : group.getRules()) {
                if (rule == null || rule.getActions() == null) {
                    continue;
                }
                for (String action : rule.getActions()) {
                    if (action == null || !flaggedActions.add(action)) {
                        continue;
                    }
                    findNearMissReservedAction(action).ifPresent(reserved -> warnings
                            .add("action '" + action + "' closely resembles reserved action '" + reserved
                                    + "' — if this was meant to be '" + reserved
                                    + "', fix the typo; otherwise this is a false positive and can be ignored"));
                }
            }
        }

        return warnings;
    }

    /**
     * @return the reserved action name {@code action} near-misses, or empty if
     *         {@code action} exactly matches a reserved action or resembles none of
     *         them.
     */
    private static Optional<String> findNearMissReservedAction(String action) {
        for (String reserved : RESERVED_ACTIONS) {
            if (action.equals(reserved)) {
                // Exact match is the legitimate, intentional use — never flagged.
                return Optional.empty();
            }
            boolean caseVariant = action.equalsIgnoreCase(reserved);
            boolean withinDistance = ToolApprovalPatterns.levenshtein(action, reserved) <= NEAR_MISS_THRESHOLD;
            if (caseVariant || withinDistance) {
                return Optional.of(reserved);
            }
        }
        return Optional.empty();
    }

    /**
     * Checks whether a deployed agent's {@code hitlConfig} can ever actually
     * trigger a pause. Warns when hitlConfig is configured (present) AND no ruleset
     * reachable from the agent emits {@code PAUSE_CONVERSATION} AND
     * {@code hitlConfig.toolApprovals.requireApproval} is empty/absent — i.e.
     * nothing in the agent can ever pause the conversation, so the whole hitlConfig
     * block is dead configuration.
     * <p>
     * Non-fatal by construction: returns an informational message for the caller to
     * log/collect. Never throws, never blocks deployment.
     *
     * @param agentId
     *            the agent id, for the warning message
     * @param hitlConfig
     *            the agent's HITL config (may be {@code null})
     * @param anyRulesetEmitsPauseConversation
     *            {@code true} if any ruleset reachable from this agent's workflows
     *            contains a rule that emits {@code PAUSE_CONVERSATION}
     * @return a warning message if the config is inert; empty otherwise
     */
    public static Optional<String> checkInertHitlConfig(String agentId, AgentConfiguration.HitlConfig hitlConfig,
                                                        boolean anyRulesetEmitsPauseConversation) {

        if (hitlConfig == null) {
            return Optional.empty();
        }
        if (anyRulesetEmitsPauseConversation) {
            return Optional.empty();
        }
        if (hasRequireApproval(hitlConfig)) {
            return Optional.empty();
        }

        return Optional.of("agent " + agentId + ": hitlConfig is configured but nothing in this agent can trigger a pause");
    }

    private static boolean hasRequireApproval(AgentConfiguration.HitlConfig hitlConfig) {
        var toolApprovals = hitlConfig.getToolApprovals();
        return toolApprovals != null
                && toolApprovals.getRequireApproval() != null
                && !toolApprovals.getRequireApproval().isEmpty();
    }
}
