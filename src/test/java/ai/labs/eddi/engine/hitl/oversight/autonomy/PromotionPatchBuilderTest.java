/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.oversight.autonomy;

import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.hitl.HitlConfigValidation;
import ai.labs.eddi.configs.hitl.model.OversightConfig;
import ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig;
import ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig.ApprovalRule;
import ai.labs.eddi.engine.hitl.oversight.OversightFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("PromotionPatchBuilder")
class PromotionPatchBuilderTest {

    private static AgentConfiguration.HitlConfig hitl(List<String> require, List<String> exempt, List<ApprovalRule> rules) {
        var toolApprovals = new ToolApprovalsConfig();
        toolApprovals.setRequireApproval(require != null ? new ArrayList<>(require) : null);
        toolApprovals.setExempt(exempt != null ? new ArrayList<>(exempt) : null);
        toolApprovals.setRules(rules);
        var hitl = new AgentConfiguration.HitlConfig();
        hitl.setToolApprovals(toolApprovals);
        return hitl;
    }

    private static ApprovalRule rule(String match, String category) {
        var rule = new ApprovalRule();
        rule.setMatch(match);
        rule.setApprovalCategory(category);
        return rule;
    }

    @Nested
    @DisplayName("promote")
    class Promote {

        @Test
        @DisplayName("a category that IS a requireApproval pattern is removed from requireApproval, never added to exempt")
        void requirePatternRemoved() {
            var before = hitl(List.of("http.post:/crm/notes", "http.delete:*"), null, null);

            var patch = PromotionPatchBuilder.promote(before, "tool:http.post:/crm/notes", "http.post:/crm/notes", 0.1);

            assertEquals(List.of("http.delete:*"), patch.after().getToolApprovals().getRequireApproval());
            assertNull(patch.after().getToolApprovals().getExempt());
            assertFalse(patch.leavesNoGate());
            assertEquals(List.of("http.post:/crm/notes", "http.delete:*"), before.getToolApprovals().getRequireApproval(),
                    "the input is never changed");
        }

        @Test
        @DisplayName("a narrower category is added to exempt")
        void narrowerAddedToExempt() {
            var patch = PromotionPatchBuilder.promote(hitl(List.of("http.post:*"), null, null), "crm.notes", "http.post:/crm/notes", 0.1);

            assertEquals(List.of("http.post:*"), patch.after().getToolApprovals().getRequireApproval());
            assertEquals(List.of("http.post:/crm/notes"), patch.after().getToolApprovals().getExempt());
        }

        @Test
        @DisplayName("a friction rule identical to the new exemption is removed (the validator refuses it)")
        void identicalRuleRemoved() {
            var before = hitl(List.of("http.post:*"), null, List.of(rule("http.post:/crm/notes", "crm.notes"), rule("http.post:/x", null)));

            var patch = PromotionPatchBuilder.promote(before, "crm.notes", "http.post:/crm/notes", 0.1);

            assertEquals(List.of("http.post:/x"), patch.after().getToolApprovals().getRules().stream().map(ApprovalRule::getMatch).toList());
        }

        @Test
        @DisplayName("removing the last requireApproval pattern drops exempt and rules and says the agent has no gated tools")
        void lastPatternLeavesNoGate() {
            var before = hitl(List.of("http.post:/crm/notes"), List.of("http.get:*"), List.of(rule("http.post:/crm/*", null)));

            var patch = PromotionPatchBuilder.promote(before, "tool:http.post:/crm/notes", "http.post:/crm/notes", 0.1);

            assertTrue(patch.leavesNoGate());
            assertTrue(patch.after().getToolApprovals().getRequireApproval().isEmpty());
            assertNull(patch.after().getToolApprovals().getExempt());
            assertNull(patch.after().getToolApprovals().getRules());
            assertTrue(patch.changes().stream().anyMatch(c -> c.contains("no gated tools")), patch.changes().toString());
        }

        @Test
        @DisplayName("the promoted pattern is sampled at promotedSampleRate under its category")
        void sampled() {
            var patch = PromotionPatchBuilder.promote(hitl(List.of("http.post:*"), null, null), "crm.notes", "http.post:/crm/notes", 0.25);

            var rules = patch.after().getOversight().getSampledAudit().getRules();
            assertEquals(1, rules.size());
            assertEquals("http.post:/crm/notes", rules.getFirst().getMatch());
            assertEquals(0.25, rules.getFirst().getRate());
            assertEquals("crm.notes", rules.getFirst().getCategory());
        }

        @Test
        @DisplayName("an existing sample rule is raised to promotedSampleRate, never lowered")
        void existingRuleRaised() {
            var before = hitl(List.of("http.post:*"), null, null);
            var oversight = new OversightConfig();
            oversight.setSampledAudit(OversightFixtures.audit("http.post:/crm/notes", 0.5));
            before.setOversight(oversight);

            var patch = PromotionPatchBuilder.promote(before, "crm.notes", "http.post:/crm/notes", 0.1);

            assertEquals(0.5, patch.after().getOversight().getSampledAudit().getRules().getFirst().getRate());
        }
    }

    static Stream<Arguments> fixtures() {
        return Stream.of(
                Arguments.of("require pattern", hitl(List.of("http.post:/crm/notes", "http.put:*"), List.of("http.put:/a"), null),
                        "http.post:/crm/notes"),
                Arguments.of("last require pattern", hitl(List.of("http.post:/crm/notes"), List.of("http.get:*"),
                        List.of(rule("http.post:/crm/*", "crm.all"))), "http.post:/crm/notes"),
                Arguments.of("narrower, rule identical", hitl(List.of("http.post:*"), null, List.of(rule("http.post:/crm/notes", "crm.notes"))),
                        "http.post:/crm/notes"),
                Arguments.of("narrower, existing exempt", hitl(List.of("http.*:*"), List.of("http.get:*"), null), "http.post:/crm/notes"),
                Arguments.of("mcp tool", hitl(List.of("mcp:*"), null, null), "mcp:send_email"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    @DisplayName("every promotion and revert yields a configuration HitlConfigValidation accepts")
    void alwaysValid(String name, AgentConfiguration.HitlConfig before, String pattern) {
        var promoted = PromotionPatchBuilder.promote(before, "tool:" + pattern, pattern, 0.1);
        assertDoesNotThrow(() -> HitlConfigValidation.validate(promoted.after()), () -> name + " promote: " + promoted.changes());
        var reverted = PromotionPatchBuilder.revert(promoted.after(), pattern);
        assertDoesNotThrow(() -> HitlConfigValidation.validate(reverted.after()), () -> name + " revert: " + reverted.changes());
    }

    @Nested
    @DisplayName("revert and patternOf")
    class Revert {

        @Test
        @DisplayName("revert removes the exemption; a broader require pattern then gates it again")
        void removesExemption() {
            var patch = PromotionPatchBuilder.revert(hitl(List.of("http.post:*"), List.of("http.post:/crm/notes"), null), "http.post:/crm/notes");

            assertNull(patch.after().getToolApprovals().getExempt());
            assertEquals(List.of("http.post:*"), patch.after().getToolApprovals().getRequireApproval());
        }

        @Test
        @DisplayName("revert adds the pattern back to requireApproval when nothing gates it")
        void addsRequire() {
            var patch = PromotionPatchBuilder.revert(hitl(List.of("http.delete:*"), null, null), "http.post:/crm/notes");

            assertEquals(List.of("http.delete:*", "http.post:/crm/notes"), patch.after().getToolApprovals().getRequireApproval());
        }

        @Test
        @DisplayName("patternOf: tool:<p> → p; a rule's category → its match; RULE/group/legacy categories → null")
        void patternOf() {
            var config = hitl(List.of("http.post:*"), null, List.of(rule("http.post:*/comments", "github.reply")));
            assertEquals("http.post:/x", PromotionPatchBuilder.patternOf(config, "tool:http.post:/x"));
            assertEquals("http.post:*/comments", PromotionPatchBuilder.patternOf(config, "github.reply"));
            assertNull(PromotionPatchBuilder.patternOf(config, "rule:agent-1"));
            assertNull(PromotionPatchBuilder.patternOf(config, "legacy:tool:x"));
        }
    }
}
