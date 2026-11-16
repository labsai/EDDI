/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.review;

import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig;

import java.util.Map;

/**
 * Resolves the approval category of a paused unit — the key approval analytics
 * groups decisions by. A configured category (source {@code CONFIG}) beats a
 * derived one (source {@code DERIVED}):
 *
 * <table>
 * <caption>Categories</caption>
 * <tr>
 * <th>Unit</th>
 * <th>Configured</th>
 * <th>Derived</th>
 * </tr>
 * <tr>
 * <td>TOOL_CALL</td>
 * <td>{@code toolApprovals.rules[].approvalCategory} of the rule that matched
 * the call</td>
 * <td>{@code tool:<pattern>} — the gating {@code requireApproval} pattern</td>
 * </tr>
 * <tr>
 * <td>RULE</td>
 * <td>{@code hitlConfig.review.approvalCategory}</td>
 * <td>{@code rule:<agentId>}</td>
 * </tr>
 * <tr>
 * <td>group PHASE</td>
 * <td>{@code review.phaseCategories[phaseName]}</td>
 * <td>{@code group:<groupId>:phase:<phaseName>}</td>
 * </tr>
 * <tr>
 * <td>group TASK</td>
 * <td>{@code review.phaseCategories[phaseName]}</td>
 * <td>{@code group:<groupId>:task:<phaseName>}</td>
 * </tr>
 * </table>
 * Public so analytics can categorise calls that were never paused (an exempt
 * call in a sampled audit) the same way.
 *
 * @since 6.7.0
 */
public final class ApprovalCategories {

    /** A resolved category and where it came from. */
    public record ApprovalCategory(String category, Source source) {
    }

    /** Where a category came from. */
    public enum Source {
        CONFIG, DERIVED
    }

    private ApprovalCategories() {
    }

    /**
     * Category of one tool call.
     *
     * @param effectiveToolApprovals
     *            the config that gated the call (the batch's effective config)
     * @param gateOrExemptPattern
     *            the {@code requireApproval} pattern that gated it, or for an
     *            exempt call the exempt pattern
     * @param matchedRule
     *            the {@code match} of the rule that governed the call, or null
     */
    public static ApprovalCategory forToolCall(ToolApprovalsConfig effectiveToolApprovals, String gateOrExemptPattern, String matchedRule) {
        if (matchedRule != null && effectiveToolApprovals != null && effectiveToolApprovals.getRules() != null) {
            for (ToolApprovalsConfig.ApprovalRule rule : effectiveToolApprovals.getRules()) {
                if (rule != null && matchedRule.equals(rule.getMatch()) && notBlank(rule.getApprovalCategory())) {
                    return new ApprovalCategory(rule.getApprovalCategory(), Source.CONFIG);
                }
            }
        }
        String pattern = notBlank(gateOrExemptPattern) ? gateOrExemptPattern : notBlank(matchedRule) ? matchedRule : "*";
        return new ApprovalCategory("tool:" + pattern, Source.DERIVED);
    }

    /** Category of a behavior-rule pause of the agent. */
    public static ApprovalCategory forRule(AgentConfiguration.HitlConfig hitlConfig, String agentId) {
        if (hitlConfig != null && hitlConfig.getReview() != null && notBlank(hitlConfig.getReview().getApprovalCategory())) {
            return new ApprovalCategory(hitlConfig.getReview().getApprovalCategory(), Source.CONFIG);
        }
        return new ApprovalCategory("rule:" + agentId, Source.DERIVED);
    }

    /** Category of a group PHASE pause. */
    public static ApprovalCategory forGroupPhase(AgentGroupConfiguration.HitlConfig hitlConfig, String groupId, String phaseName) {
        return forGroup(hitlConfig, groupId, phaseName, "phase");
    }

    /** Category of a group TASK pause. */
    public static ApprovalCategory forGroupTask(AgentGroupConfiguration.HitlConfig hitlConfig, String groupId, String phaseName) {
        return forGroup(hitlConfig, groupId, phaseName, "task");
    }

    private static ApprovalCategory forGroup(AgentGroupConfiguration.HitlConfig hitlConfig, String groupId, String phaseName, String kind) {
        if (phaseName != null && hitlConfig != null && hitlConfig.getReview() != null) {
            Map<String, String> categories = hitlConfig.getReview().getPhaseCategories();
            if (categories != null && notBlank(categories.get(phaseName))) {
                return new ApprovalCategory(categories.get(phaseName), Source.CONFIG);
            }
        }
        return new ApprovalCategory("group:" + groupId + ":" + kind + ":" + (phaseName != null ? phaseName : "unknown"), Source.DERIVED);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
