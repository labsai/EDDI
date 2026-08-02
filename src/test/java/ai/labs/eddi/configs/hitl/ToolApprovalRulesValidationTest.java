/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.hitl;

import ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig;
import ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig.ApprovalRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Save-time validation of {@code toolApprovals.rules}. A rule that cannot match
 * costs the designer the friction they thought they configured, and — like an
 * unmatchable {@code requireApproval} pattern — the failure is invisible from
 * the config, so it is refused rather than saved.
 */
@DisplayName("toolApprovals.rules validation")
class ToolApprovalRulesValidationTest {

    private static ToolApprovalsConfig withRules(ApprovalRule... rules) {
        var cfg = new ToolApprovalsConfig();
        cfg.setRequireApproval(List.of("http.post:*"));
        cfg.setRules(Arrays.asList(rules));
        return cfg;
    }

    private static ApprovalRule rule(String match) {
        var r = new ApprovalRule();
        r.setMatch(match);
        return r;
    }

    private static String messageOf(Executable body) {
        return assertThrows(IllegalArgumentException.class, body).getMessage();
    }

    @Test
    @DisplayName("a valid endpoint-addressed rule saves")
    void validRuleSaves() {
        var r = rule("http.post:/agentstore/agents");
        r.setTimeoutPolicy(HitlTimeoutPolicy.WAIT_INDEFINITELY);
        r.setPauseReason("Creating a new agent — review the whole config");
        assertDoesNotThrow(() -> HitlConfigValidation.validateToolApprovals(withRules(r), "hitlConfig.toolApprovals"));
    }

    @Test
    @DisplayName("a rule whose prefix can never match an endpoint is refused")
    void unmatchableRuleIsRefused() {
        // 'mcp' tools carry no endpoint, so 'mcp:/agentstore/agents' matches nothing —
        // the same defect ToolApprovalPatterns already refuses in requireApproval.
        String message = messageOf(
                () -> HitlConfigValidation.validateToolApprovals(withRules(rule("mcp:/agentstore/agents")), "cfg.toolApprovals"));
        assertTrue(message.startsWith("cfg.toolApprovals.rules[0].match:"), message);
        assertTrue(message.contains("matches nothing"), message);
    }

    @Test
    @DisplayName("a blank match is refused")
    void blankMatchIsRefused() {
        assertTrue(messageOf(() -> HitlConfigValidation.validateToolApprovals(withRules(rule("  ")), "cfg.toolApprovals"))
                .contains("must not be blank"));
    }

    @Test
    @DisplayName("two rules with the same match are refused — only one could ever apply")
    void duplicateMatchIsRefused() {
        assertTrue(messageOf(() -> HitlConfigValidation.validateToolApprovals(
                withRules(rule("http.post:*"), rule("http.post:*")), "cfg.toolApprovals")).contains("duplicate rule match"));
    }

    @Test
    @DisplayName("a rule whose match is also an exempt pattern is refused — it could never apply")
    void ruleMatchingAnExemptPatternIsRefused() {
        // An exempt call is never gated, and a rule only tunes a gated call — so this
        // rule is dead config that reads as if it were doing something.
        var cfg = new ToolApprovalsConfig();
        cfg.setRequireApproval(List.of("http.post:*"));
        cfg.setExempt(List.of("http.get:*"));
        cfg.setRules(List.of(rule("http.get:*")));

        assertTrue(messageOf(() -> HitlConfigValidation.validateToolApprovals(cfg, "cfg.toolApprovals"))
                .contains("can never apply"));
    }

    @Test
    @DisplayName("a rule merely OVERLAPPING an exempt pattern still saves")
    void ruleOverlappingAnExemptPatternSaves() {
        // Only exact equality is provably dead. A broader rule can still cover gated
        // calls, and refusing it would be a false positive.
        var cfg = new ToolApprovalsConfig();
        cfg.setRequireApproval(List.of("http.post:*"));
        cfg.setExempt(List.of("http.get:*"));
        cfg.setRules(List.of(rule("http.*:*")));

        assertDoesNotThrow(() -> HitlConfigValidation.validateToolApprovals(cfg, "cfg.toolApprovals"));
    }

    @Test
    @DisplayName("rules without requireApproval are refused — a rule never gates")
    void rulesWithoutRequireApprovalAreRefused() {
        var cfg = new ToolApprovalsConfig();
        cfg.setRules(List.of(rule("http.post:*")));
        assertTrue(messageOf(() -> HitlConfigValidation.validateToolApprovals(cfg, "cfg.toolApprovals"))
                .contains("they never gate one"));
    }

    @Test
    @DisplayName("a finite rule policy with no duration anywhere is refused — it would never fire")
    void finitePolicyWithoutAnyDurationIsRefused() {
        var r = rule("http.post:*");
        r.setTimeoutPolicy(HitlTimeoutPolicy.AUTO_REJECT);
        String message = messageOf(() -> HitlConfigValidation.validateToolApprovals(withRules(r), "cfg.toolApprovals"));
        assertTrue(message.contains("cfg.toolApprovals.rules[0].approvalTimeout"), message);
    }

    @Test
    @DisplayName("a finite rule policy may inherit the enclosing approvalTimeout")
    void finitePolicyInheritsEnclosingDuration() {
        var r = rule("http.post:*");
        r.setTimeoutPolicy(HitlTimeoutPolicy.AUTO_REJECT);
        var cfg = withRules(r);
        cfg.setApprovalTimeout("PT30M");
        assertDoesNotThrow(() -> HitlConfigValidation.validateToolApprovals(cfg, "cfg.toolApprovals"));
    }

    @Test
    @DisplayName("a malformed rule duration is refused")
    void malformedRuleDurationIsRefused() {
        var r = rule("http.post:*");
        r.setApprovalTimeout("5 minutes");
        assertTrue(messageOf(() -> HitlConfigValidation.validateToolApprovals(withRules(r), "cfg.toolApprovals"))
                .contains("not a valid ISO-8601 duration"));
    }

    @Test
    @DisplayName("over-long rule messages are refused, with the rule index named")
    void overLongRuleMessagesAreRefused() {
        var r = rule("http.post:*");
        r.setPauseReason("x".repeat(HitlConfigValidation.MAX_PAUSE_REASON_LENGTH + 1));
        assertTrue(messageOf(() -> HitlConfigValidation.validateToolApprovals(withRules(r), "cfg.toolApprovals"))
                .contains("cfg.toolApprovals.rules[0].pauseReason"));

        var r2 = rule("http.post:*");
        r2.setPendingMessage("x".repeat(HitlConfigValidation.MAX_PAUSE_REASON_LENGTH + 1));
        assertTrue(messageOf(() -> HitlConfigValidation.validateToolApprovals(withRules(r2), "cfg.toolApprovals"))
                .contains("cfg.toolApprovals.rules[0].pendingMessage"));
    }

    @Test
    @DisplayName("a null rule entry is refused rather than NPEing at runtime")
    void nullRuleEntryIsRefused() {
        var cfg = new ToolApprovalsConfig();
        cfg.setRequireApproval(List.of("http.post:*"));
        var rules = new ArrayList<ApprovalRule>();
        rules.add(null);
        cfg.setRules(rules);
        assertTrue(messageOf(() -> HitlConfigValidation.validateToolApprovals(cfg, "cfg.toolApprovals"))
                .contains("must not be null"));
    }

    @Test
    @DisplayName("absent rules leave every existing config validating exactly as before")
    void absentRulesAreANoOp() {
        var cfg = new ToolApprovalsConfig();
        cfg.setRequireApproval(List.of("http.post:*"));
        assertDoesNotThrow(() -> HitlConfigValidation.validateToolApprovals(cfg, "cfg.toolApprovals"));
        cfg.setRules(List.of());
        assertDoesNotThrow(() -> HitlConfigValidation.validateToolApprovals(cfg, "cfg.toolApprovals"));
    }
}
