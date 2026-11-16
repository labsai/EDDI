/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.oversight.autonomy;

import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.hitl.model.OversightConfig;
import ai.labs.eddi.configs.hitl.model.SampledAuditConfig;
import ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig;
import ai.labs.eddi.engine.hitl.oversight.OversightJson;
import ai.labs.eddi.engine.hitl.tools.ToolApprovalPatterns;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the configuration change a recommendation proposes. Pure: it takes an
 * agent's {@code hitlConfig} and returns a changed copy, never touching the
 * original.
 * <p>
 * The change always yields a configuration {@code HitlConfigValidation}
 * accepts:
 * <ul>
 * <li>a category that <em>is</em> a {@code requireApproval} pattern is removed
 * from that list (the validator refuses the same pattern in both lists);</li>
 * <li>a narrower category is added to {@code exempt}, and a friction rule whose
 * {@code match} is identical to it is removed (the validator refuses that
 * too);</li>
 * <li>removing the last {@code requireApproval} pattern drops {@code exempt}
 * and {@code rules} with it — the agent then has no gated tool, which the
 * preview says in so many words;</li>
 * <li>the pattern is added to (or updated in) {@code oversight.sampledAudit} at
 * the policy's {@code promotedSampleRate}: a promoted category is always
 * sampled.</li>
 * </ul>
 *
 * @since 6.7.0
 */
public final class PromotionPatchBuilder {

    private PromotionPatchBuilder() {
    }

    /**
     * The tool pattern a category stands for, or null when it is not a tool
     * category (a RULE or group category — advisory only).
     */
    public static String patternOf(AgentConfiguration.HitlConfig hitlConfig, String category) {
        if (category == null || category.startsWith("legacy:")) {
            return null;
        }
        if (category.startsWith("tool:") && category.length() > "tool:".length()) {
            return category.substring("tool:".length());
        }
        if (hitlConfig == null) {
            return null;
        }
        ToolApprovalsConfig toolApprovals = hitlConfig.getToolApprovals();
        if (toolApprovals != null && toolApprovals.getRules() != null) {
            for (ToolApprovalsConfig.ApprovalRule rule : toolApprovals.getRules()) {
                if (rule != null && category.equals(rule.getApprovalCategory()) && rule.getMatch() != null) {
                    return rule.getMatch();
                }
            }
        }
        OversightConfig oversight = hitlConfig.getOversight();
        if (oversight != null && oversight.getSampledAudit() != null && oversight.getSampledAudit().getRules() != null) {
            for (SampledAuditConfig.Rule rule : oversight.getSampledAudit().getRules()) {
                if (rule != null && rule.getMatch() != null && category.equals(rule.effectiveCategory())) {
                    return rule.getMatch();
                }
            }
        }
        return null;
    }

    /**
     * A built change.
     *
     * @param after
     *            the changed copy of {@code hitlConfig}
     * @param changes
     *            the change in words
     * @param leavesNoGate
     *            the agent has no {@code requireApproval} pattern afterwards
     */
    public record Patch(AgentConfiguration.HitlConfig after, List<String> changes, boolean leavesNoGate) {

        /** {@code {toolApprovals, oversight}} of the result, for a preview. */
        public Map<String, Object> preview() {
            var map = new LinkedHashMap<String, Object>();
            map.put("toolApprovals", after.getToolApprovals() != null ? OversightJson.copy(after.getToolApprovals(), Map.class) : null);
            map.put("oversight", after.getOversight() != null ? OversightJson.copy(after.getOversight(), Map.class) : null);
            return map;
        }
    }

    /** The change that stops gating {@code pattern} and samples it instead. */
    public static Patch promote(AgentConfiguration.HitlConfig hitlConfig, String category, String pattern, double promotedSampleRate) {
        AgentConfiguration.HitlConfig after = copy(hitlConfig);
        List<String> changes = new ArrayList<>();
        ToolApprovalsConfig toolApprovals = after.getToolApprovals() != null ? after.getToolApprovals() : new ToolApprovalsConfig();
        after.setToolApprovals(toolApprovals);
        List<String> require = mutable(toolApprovals.getRequireApproval());
        boolean leavesNoGate = false;
        if (require.remove(pattern)) {
            changes.add("Remove '" + pattern + "' from hitlConfig.toolApprovals.requireApproval");
            toolApprovals.setRequireApproval(require);
            if (require.isEmpty()) {
                leavesNoGate = true;
                if (toolApprovals.getExempt() != null && !toolApprovals.getExempt().isEmpty()) {
                    changes.add("Drop hitlConfig.toolApprovals.exempt (it has no effect without requireApproval)");
                }
                if (toolApprovals.getRules() != null && !toolApprovals.getRules().isEmpty()) {
                    changes.add("Drop hitlConfig.toolApprovals.rules (they have no effect without requireApproval)");
                }
                toolApprovals.setExempt(null);
                toolApprovals.setRules(null);
                changes.add("After this the agent has no gated tools");
            }
        } else {
            List<String> exempt = mutable(toolApprovals.getExempt());
            if (!exempt.contains(pattern)) {
                exempt.add(pattern);
                changes.add("Add '" + pattern + "' to hitlConfig.toolApprovals.exempt");
            }
            toolApprovals.setExempt(exempt);
            if (toolApprovals.getRules() != null) {
                List<ToolApprovalsConfig.ApprovalRule> kept = new ArrayList<>();
                for (ToolApprovalsConfig.ApprovalRule rule : toolApprovals.getRules()) {
                    if (rule != null && pattern.equals(rule.getMatch())) {
                        changes.add("Remove the friction rule for '" + pattern + "' (an exempt call is never gated)");
                    } else {
                        kept.add(rule);
                    }
                }
                toolApprovals.setRules(kept.isEmpty() ? null : kept);
            }
        }
        upsertSampleRule(after, category, pattern, promotedSampleRate, changes);
        return new Patch(after, List.copyOf(changes), leavesNoGate);
    }

    /**
     * The change that gates {@code pattern} again in configuration — making a
     * suspension permanent. It only ever adds a gate.
     */
    public static Patch revert(AgentConfiguration.HitlConfig hitlConfig, String pattern) {
        AgentConfiguration.HitlConfig after = copy(hitlConfig);
        List<String> changes = new ArrayList<>();
        ToolApprovalsConfig toolApprovals = after.getToolApprovals() != null ? after.getToolApprovals() : new ToolApprovalsConfig();
        after.setToolApprovals(toolApprovals);
        List<String> exempt = mutable(toolApprovals.getExempt());
        if (exempt.remove(pattern)) {
            changes.add("Remove '" + pattern + "' from hitlConfig.toolApprovals.exempt");
            toolApprovals.setExempt(exempt.isEmpty() ? null : exempt);
        }
        List<String> require = mutable(toolApprovals.getRequireApproval());
        boolean gated = require.stream().anyMatch(p -> p.equals(pattern) || ToolApprovalPatterns.matches(p, pattern));
        if (!gated) {
            require.add(pattern);
            changes.add("Add '" + pattern + "' to hitlConfig.toolApprovals.requireApproval");
        }
        toolApprovals.setRequireApproval(require);
        for (String covering : coveringExemptions(hitlConfig, pattern)) {
            changes.add("Not changed: the broader exempt entry '" + covering + "' still outranks the gate for '" + pattern + "'");
        }
        if (changes.isEmpty()) {
            changes.add("The configuration already gates '" + pattern + "'; applying only clears the suspension");
        }
        return new Patch(after, List.copyOf(changes), false);
    }

    /**
     * The {@code exempt} entries, other than {@code pattern} itself, that still
     * match {@code pattern}. {@code exempt} outranks {@code requireApproval} at the
     * gate, so while one of these remains a reverted pattern is not actually gated
     * and a revert must not be reported as making the gate permanent. They are
     * never removed automatically: such an entry may exempt other tools too.
     */
    public static List<String> coveringExemptions(AgentConfiguration.HitlConfig hitlConfig, String pattern) {
        if (hitlConfig == null || hitlConfig.getToolApprovals() == null || hitlConfig.getToolApprovals().getExempt() == null) {
            return List.of();
        }
        return hitlConfig.getToolApprovals().getExempt().stream()
                .filter(e -> e != null && !e.equals(pattern) && ToolApprovalPatterns.matches(e, pattern)).toList();
    }

    private static void upsertSampleRule(AgentConfiguration.HitlConfig after, String category, String pattern, double rate,
                                         List<String> changes) {
        OversightConfig oversight = after.getOversight() != null ? after.getOversight() : new OversightConfig();
        after.setOversight(oversight);
        SampledAuditConfig audit = oversight.getSampledAudit() != null ? oversight.getSampledAudit() : new SampledAuditConfig();
        oversight.setSampledAudit(audit);
        List<SampledAuditConfig.Rule> rules = audit.getRules() != null ? new ArrayList<>(audit.getRules()) : new ArrayList<>();
        SampledAuditConfig.Rule existing = rules.stream().filter(r -> r != null && pattern.equals(r.getMatch())).findFirst().orElse(null);
        if (existing != null) {
            double current = existing.getRate() != null ? existing.getRate() : 0.0;
            if (current < rate) {
                existing.setRate(rate);
                changes.add("Raise the sample rate of '" + pattern + "' to " + rate);
            }
            existing.setCategory(category);
        } else {
            var rule = new SampledAuditConfig.Rule();
            rule.setMatch(pattern);
            rule.setRate(rate);
            rule.setCategory(category);
            rules.add(rule);
            changes.add("Sample " + Math.round(rate * 100) + "% of '" + pattern + "' calls for review (hitlConfig.oversight.sampledAudit)");
        }
        audit.setRules(rules);
    }

    private static AgentConfiguration.HitlConfig copy(AgentConfiguration.HitlConfig hitlConfig) {
        AgentConfiguration.HitlConfig copy = OversightJson.copy(hitlConfig, AgentConfiguration.HitlConfig.class);
        return copy != null ? copy : new AgentConfiguration.HitlConfig();
    }

    private static List<String> mutable(List<String> list) {
        return list != null ? new ArrayList<>(list) : new ArrayList<>();
    }
}
