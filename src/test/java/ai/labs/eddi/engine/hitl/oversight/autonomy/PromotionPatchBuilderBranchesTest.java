/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.oversight.autonomy;

import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.hitl.model.OversightConfig;
import ai.labs.eddi.configs.hitl.model.SampledAuditConfig;
import ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig;
import ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig.ApprovalRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("PromotionPatchBuilder branches")
class PromotionPatchBuilderBranchesTest {

    private static final String PATTERN = "http.post:/crm/notes";
    private static final String CATEGORY = "tool:" + PATTERN;

    private static AgentConfiguration.HitlConfig hitl(List<String> require, List<String> exempt, List<ApprovalRule> rules) {
        var tools = new ToolApprovalsConfig();
        tools.setRequireApproval(require);
        tools.setExempt(exempt);
        tools.setRules(rules);
        var hitl = new AgentConfiguration.HitlConfig();
        hitl.setToolApprovals(tools);
        return hitl;
    }

    private static ApprovalRule rule(String match, String category) {
        var rule = new ApprovalRule();
        rule.setMatch(match);
        rule.setApprovalCategory(category);
        return rule;
    }

    private static SampledAuditConfig.Rule sampling(String match, Double rate, String category) {
        var rule = new SampledAuditConfig.Rule();
        rule.setMatch(match);
        rule.setRate(rate);
        rule.setCategory(category);
        return rule;
    }

    private static AgentConfiguration.HitlConfig withAudit(AgentConfiguration.HitlConfig hitl, SampledAuditConfig.Rule... rules) {
        var audit = new SampledAuditConfig();
        audit.setRules(rules.length == 0 ? null : new ArrayList<>(Arrays.asList(rules)));
        var oversight = new OversightConfig();
        oversight.setSampledAudit(audit);
        hitl.setOversight(oversight);
        return hitl;
    }

    @Nested
    @DisplayName("patternOf")
    class PatternOf {

        @Test
        @DisplayName("a null category, a legacy category and a bare tool: prefix name no tool")
        void noTool() {
            assertNull(PromotionPatchBuilder.patternOf(new AgentConfiguration.HitlConfig(), null));
            assertNull(PromotionPatchBuilder.patternOf(new AgentConfiguration.HitlConfig(), "legacy:tool:x"));
            assertNull(PromotionPatchBuilder.patternOf(null, "tool:"), "a bare prefix with no hitlConfig names nothing");
            assertNull(PromotionPatchBuilder.patternOf(new AgentConfiguration.HitlConfig(), "tool:"));
        }

        @Test
        @DisplayName("a tool: category carries its pattern, with or without a hitlConfig")
        void toolPrefix() {
            assertEquals("http.*", PromotionPatchBuilder.patternOf(null, "tool:http.*"));
            assertEquals("http.*", PromotionPatchBuilder.patternOf(new AgentConfiguration.HitlConfig(), "tool:http.*"));
        }

        @Test
        @DisplayName("a custom category is found on a friction rule, skipping null and matchless rules")
        void fromFrictionRule() {
            var hitl = hitl(List.of("a:*"), null, Arrays.asList(null, rule(null, "custom"), rule("other:*", "different"), rule("a:send", "custom")));
            assertEquals("a:send", PromotionPatchBuilder.patternOf(hitl, "custom"));
        }

        @Test
        @DisplayName("a custom category is found on a sampling rule when no friction rule names it")
        void fromSamplingRule() {
            var hitl = withAudit(hitl(List.of("a:*"), null, null), null, sampling(null, 0.1, "custom"), sampling("b:*", 0.1, "other"),
                    sampling("b:send", 0.1, "custom"));
            assertEquals("b:send", PromotionPatchBuilder.patternOf(hitl, "custom"));
            assertNull(PromotionPatchBuilder.patternOf(hitl, "unknown"));
        }

        @Test
        @DisplayName("a category on a rule list, an audit without rules, or no oversight at all is not found")
        void notFound() {
            assertNull(PromotionPatchBuilder.patternOf(new AgentConfiguration.HitlConfig(), "custom"));
            var emptyRules = hitl(null, null, null);
            assertNull(PromotionPatchBuilder.patternOf(emptyRules, "custom"));
            assertNull(PromotionPatchBuilder.patternOf(withAudit(hitl(null, null, null)), "custom"));
            var noAudit = hitl(null, null, null);
            noAudit.setOversight(new OversightConfig());
            assertNull(PromotionPatchBuilder.patternOf(noAudit, "custom"));
        }
    }

    @Nested
    @DisplayName("promote")
    class Promote {

        @Test
        @DisplayName("removing one of several requireApproval patterns keeps the rest gated")
        void removesOneOfMany() {
            var patch = PromotionPatchBuilder.promote(hitl(List.of(PATTERN, "mcp:*"), List.of("x"), null), CATEGORY, PATTERN, 0.1);

            assertEquals(List.of("mcp:*"), patch.after().getToolApprovals().getRequireApproval());
            assertEquals(List.of("x"), patch.after().getToolApprovals().getExempt(), "exempt stays while something is still gated");
            assertFalse(patch.leavesNoGate());
            assertEquals("Remove '" + PATTERN + "' from hitlConfig.toolApprovals.requireApproval", patch.changes().get(0));
        }

        @Test
        @DisplayName("removing the last pattern drops exempt and rules and says the agent has no gated tool")
        void removesLastPattern() {
            var patch = PromotionPatchBuilder.promote(hitl(List.of(PATTERN), List.of("x"), List.of(rule("y", null))), CATEGORY, PATTERN, 0.1);

            assertTrue(patch.leavesNoGate());
            assertNull(patch.after().getToolApprovals().getExempt());
            assertNull(patch.after().getToolApprovals().getRules());
            assertTrue(patch.changes().stream().anyMatch(c -> c.contains("Drop hitlConfig.toolApprovals.exempt")));
            assertTrue(patch.changes().stream().anyMatch(c -> c.contains("Drop hitlConfig.toolApprovals.rules")));
            assertTrue(patch.changes().contains("After this the agent has no gated tools"));
        }

        @Test
        @DisplayName("removing the last pattern with an empty exempt and no rules mentions neither")
        void removesLastPatternBare() {
            var patch = PromotionPatchBuilder.promote(hitl(List.of(PATTERN), List.of(), List.of()), CATEGORY, PATTERN, 0.1);

            assertTrue(patch.leavesNoGate());
            assertFalse(patch.changes().stream().anyMatch(c -> c.contains("Drop")));

            var nulls = PromotionPatchBuilder.promote(hitl(List.of(PATTERN), null, null), CATEGORY, PATTERN, 0.1);
            assertFalse(nulls.changes().stream().anyMatch(c -> c.contains("Drop")));
        }

        @Test
        @DisplayName("a narrower pattern is added to exempt once, and a friction rule for exactly that pattern is removed")
        void addsToExempt() {
            var keep = rule("other", null);
            var patch = PromotionPatchBuilder.promote(hitl(List.of("http.post:*"), null, Arrays.asList(rule(PATTERN, null), keep, null)), CATEGORY,
                    PATTERN, 0.1);

            assertEquals(List.of(PATTERN), patch.after().getToolApprovals().getExempt());
            assertEquals(2, patch.after().getToolApprovals().getRules().size());
            assertTrue(patch.changes().stream().anyMatch(c -> c.startsWith("Remove the friction rule")));
            assertFalse(patch.leavesNoGate());
        }

        @Test
        @DisplayName("an exempt entry that is already there is not added twice, and rules left empty are dropped")
        void alreadyExempt() {
            var patch = PromotionPatchBuilder.promote(hitl(List.of("http.post:*"), List.of(PATTERN), List.of(rule(PATTERN, null))), CATEGORY,
                    PATTERN, 0.1);

            assertEquals(List.of(PATTERN), patch.after().getToolApprovals().getExempt());
            assertNull(patch.after().getToolApprovals().getRules());
            assertFalse(patch.changes().stream().anyMatch(c -> c.startsWith("Add '" + PATTERN + "' to hitlConfig.toolApprovals.exempt")));
        }

        @Test
        @DisplayName("a missing hitlConfig or toolApprovals yields a fresh one holding the exempt entry and the sample rule")
        void startsFromNothing() {
            var patch = PromotionPatchBuilder.promote(null, CATEGORY, PATTERN, 0.25);

            assertEquals(List.of(PATTERN), patch.after().getToolApprovals().getExempt());
            var rule = patch.after().getOversight().getSampledAudit().getRules().get(0);
            assertEquals(PATTERN, rule.getMatch());
            assertEquals(0.25, rule.getRate());
            assertEquals(CATEGORY, rule.getCategory());
            assertTrue(patch.changes().stream().anyMatch(c -> c.startsWith("Sample 25%")));
            assertNotNull(patch.preview().get("toolApprovals"));
            assertNotNull(patch.preview().get("oversight"));
        }

        @Test
        @DisplayName("an existing sampling rule is raised to the promoted rate, never lowered, and takes the category")
        void existingSamplingRule() {
            var low = withAudit(hitl(List.of("a:*"), null, null), sampling(PATTERN, 0.01, null));
            var raised = PromotionPatchBuilder.promote(low, CATEGORY, PATTERN, 0.1);
            var rule = raised.after().getOversight().getSampledAudit().getRules().get(0);
            assertEquals(0.1, rule.getRate());
            assertEquals(CATEGORY, rule.getCategory());
            assertTrue(raised.changes().stream().anyMatch(c -> c.startsWith("Raise the sample rate")));

            var high = withAudit(hitl(List.of("a:*"), null, null), sampling(PATTERN, 0.9, null));
            var kept = PromotionPatchBuilder.promote(high, CATEGORY, PATTERN, 0.1);
            assertEquals(0.9, kept.after().getOversight().getSampledAudit().getRules().get(0).getRate());
            assertFalse(kept.changes().stream().anyMatch(c -> c.startsWith("Raise the sample rate")));

            var unrated = withAudit(hitl(List.of("a:*"), null, null), sampling(PATTERN, null, null));
            var fromZero = PromotionPatchBuilder.promote(unrated, CATEGORY, PATTERN, 0.1);
            assertEquals(0.1, fromZero.after().getOversight().getSampledAudit().getRules().get(0).getRate());
        }

        @Test
        @DisplayName("the original hitlConfig is never modified")
        void originalUntouched() {
            var original = hitl(List.of(PATTERN, "mcp:*"), null, null);
            PromotionPatchBuilder.promote(original, CATEGORY, PATTERN, 0.1);
            assertEquals(List.of(PATTERN, "mcp:*"), original.getToolApprovals().getRequireApproval());
            assertNull(original.getOversight());
        }
    }

    @Nested
    @DisplayName("revert")
    class Revert {

        @Test
        @DisplayName("an exempt entry for the pattern is removed (exempt dropped when empty) and the pattern gated again")
        void removesExemptAndGates() {
            var patch = PromotionPatchBuilder.revert(hitl(null, List.of(PATTERN), null), PATTERN);

            assertNull(patch.after().getToolApprovals().getExempt());
            assertEquals(List.of(PATTERN), patch.after().getToolApprovals().getRequireApproval());
            assertEquals(2, patch.changes().size());
            assertFalse(patch.leavesNoGate());
        }

        @Test
        @DisplayName("other exempt entries stay")
        void keepsOtherExempt() {
            var patch = PromotionPatchBuilder.revert(hitl(List.of("mcp:*"), List.of(PATTERN, "x"), null), PATTERN);
            assertEquals(List.of("x"), patch.after().getToolApprovals().getExempt());
            assertEquals(List.of("mcp:*", PATTERN), patch.after().getToolApprovals().getRequireApproval());
        }

        @Test
        @DisplayName("a pattern already gated by an equal or broader requireApproval entry is not added again")
        void alreadyGated() {
            var broad = PromotionPatchBuilder.revert(hitl(List.of("http.post:*"), null, null), PATTERN);
            assertEquals(List.of("http.post:*"), broad.after().getToolApprovals().getRequireApproval());
            assertEquals(List.of("The configuration already gates '" + PATTERN + "'; applying only clears the suspension"), broad.changes());

            var equal = PromotionPatchBuilder.revert(hitl(List.of(PATTERN), null, null), PATTERN);
            assertEquals(List.of(PATTERN), equal.after().getToolApprovals().getRequireApproval());
        }

        @Test
        @DisplayName("a broader exempt entry that still covers the pattern is reported, not removed")
        void coveringExemptReported() {
            var patch = PromotionPatchBuilder.revert(hitl(List.of("http.post:*"), List.of("http.post:/crm/*"), null), PATTERN);

            assertEquals(List.of("http.post:/crm/*"), patch.after().getToolApprovals().getExempt());
            assertTrue(patch.changes().stream().anyMatch(c -> c.startsWith("Not changed: the broader exempt entry 'http.post:/crm/*'")));
        }

        @Test
        @DisplayName("a missing hitlConfig yields a fresh config that gates the pattern")
        void fromNothing() {
            var patch = PromotionPatchBuilder.revert(null, PATTERN);
            assertEquals(List.of(PATTERN), patch.after().getToolApprovals().getRequireApproval());
            assertNull(patch.preview().get("oversight"), "revert never touches oversight");
            assertNotNull(patch.preview().get("toolApprovals"));
        }
    }

    @Nested
    @DisplayName("coveringExemptions")
    class Covering {

        @Test
        @DisplayName("nothing covers when there is no hitlConfig, toolApprovals or exempt list")
        void nothing() {
            assertEquals(List.of(), PromotionPatchBuilder.coveringExemptions(null, PATTERN));
            assertEquals(List.of(), PromotionPatchBuilder.coveringExemptions(new AgentConfiguration.HitlConfig(), PATTERN));
            assertEquals(List.of(), PromotionPatchBuilder.coveringExemptions(hitl(null, null, null), PATTERN));
        }

        @Test
        @DisplayName("only other entries that match the pattern cover it; the pattern itself, null and unrelated entries do not")
        void filters() {
            var cfg = hitl(null, Arrays.asList(PATTERN, null, "mcp:*", "http.*", "http.post:/crm/*"), null);
            assertEquals(List.of("http.*", "http.post:/crm/*"), PromotionPatchBuilder.coveringExemptions(cfg, PATTERN));
        }
    }

    @Test
    @DisplayName("a patch preview of a config with neither part has two null entries")
    void previewMap() {
        var patch = new PromotionPatchBuilder.Patch(new AgentConfiguration.HitlConfig(), List.of(), false);
        Map<String, Object> preview = patch.preview();
        assertNull(preview.get("toolApprovals"));
        assertNull(preview.get("oversight"));
        assertEquals(2, preview.size());
    }
}
