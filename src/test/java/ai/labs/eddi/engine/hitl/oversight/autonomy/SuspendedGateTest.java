/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.oversight.autonomy;

import ai.labs.eddi.configs.hitl.HitlTimeoutPolicy;
import ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig;
import ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig.ApprovalRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("SuspendedGate")
class SuspendedGateTest {

    private static ApprovalRule rule(String match, HitlTimeoutPolicy policy) {
        var rule = new ApprovalRule();
        rule.setMatch(match);
        rule.setTimeoutPolicy(policy);
        return rule;
    }

    private static ToolApprovalsConfig withRules(ApprovalRule... rules) {
        var cfg = new ToolApprovalsConfig();
        cfg.setRequireApproval(List.of("http.post:*"));
        cfg.setRules(List.of(rules));
        return cfg;
    }

    @Test
    @DisplayName("none is true for a missing config, missing list and empty list, false otherwise")
    void none() {
        assertTrue(SuspendedGate.none(null));
        assertTrue(SuspendedGate.none(new ToolApprovalsConfig()));
        var empty = new ToolApprovalsConfig();
        empty.setSuspendedPatterns(List.of());
        assertTrue(SuspendedGate.none(empty));
        empty.setSuspendedPatterns(List.of("a:*"));
        assertFalse(SuspendedGate.none(empty));
    }

    @Test
    @DisplayName("match returns the first suspended pattern matching any address, skipping null patterns")
    void match() {
        var cfg = new ToolApprovalsConfig();
        cfg.setSuspendedPatterns(Arrays.asList(null, "http.post:/crm/*", "mcp:*"));

        assertEquals("http.post:/crm/*", SuspendedGate.match(cfg, List.of("http.get:/x", "http.post:/crm/notes")));
        assertEquals("mcp:*", SuspendedGate.match(cfg, List.of("mcp:tool")));
        assertNull(SuspendedGate.match(cfg, List.of("http.get:/x")));
        assertNull(SuspendedGate.match(cfg, List.of()));
        assertNull(SuspendedGate.match(cfg, null), "no addresses");
        assertNull(SuspendedGate.match(null, List.of("mcp:tool")), "no config");
        assertNull(SuspendedGate.match(new ToolApprovalsConfig(), List.of("mcp:tool")), "nothing suspended");
    }

    @Test
    @DisplayName("overlay returns the config itself when nothing is suspended")
    void overlayNothing() {
        var cfg = withRules();
        assertSame(cfg, SuspendedGate.overlay(cfg, null));
        assertSame(cfg, SuspendedGate.overlay(cfg, Map.of()));
        assertNull(SuspendedGate.overlay(null, Map.of()));
    }

    @Test
    @DisplayName("overlay never touches the undetermined sentinel, and never stacks on an overlay")
    void overlayRefusals() {
        assertSame(ToolApprovalsConfig.UNDETERMINED, SuspendedGate.overlay(ToolApprovalsConfig.UNDETERMINED, Map.of("a:*", "c")));

        var overlaid = SuspendedGate.overlay(withRules(), Map.of("a:*", "c"));
        assertSame(overlaid, SuspendedGate.overlay(overlaid, Map.of("b:*", "d")));
    }

    @Test
    @DisplayName("a null config yields a fresh one carrying only the synthetic waiting rules")
    void overlayOfNull() {
        var suspended = new LinkedHashMap<String, String>();
        suspended.put("http.post:/crm/notes", "tool:http.post:/crm/notes");
        suspended.put("mcp:*", "tool:mcp:*");

        ToolApprovalsConfig copy = SuspendedGate.overlay(null, suspended);

        assertEquals(List.of("http.post:/crm/notes", "mcp:*"), copy.getSuspendedPatterns());
        assertEquals(2, copy.getRules().size());
        ApprovalRule first = copy.getRules().get(0);
        assertEquals("http.post:/crm/notes", first.getMatch());
        assertEquals(HitlTimeoutPolicy.WAIT_INDEFINITELY, first.getTimeoutPolicy());
        assertEquals("tool:http.post:/crm/notes", first.getApprovalCategory());
        assertEquals(SuspendedGate.PAUSE_REASON, first.getPauseReason());
        assertNull(copy.getRequireApproval());
    }

    @Test
    @DisplayName("the copy carries every setting of the original and leaves the original untouched")
    void overlayCopiesEverything() {
        var cfg = new ToolApprovalsConfig();
        cfg.setRequireApproval(List.of("http.post:*"));
        cfg.setExempt(List.of("http.post:/crm/notes"));
        cfg.setMaxPausesPerTurn(4);
        cfg.setMaxAutoApprovalsPerTurn(7);
        cfg.setOnNoProgress("STOP");
        cfg.setApprovalTimeout("PT5M");
        cfg.setTimeoutPolicy(HitlTimeoutPolicy.AUTO_REJECT);
        cfg.setPauseReason("why");
        cfg.setPendingMessage("pending");
        cfg.setInGroupTurns("PAUSE");

        ToolApprovalsConfig copy = SuspendedGate.overlay(cfg, Map.of("http.post:/crm/notes", "c"));

        assertNotSame(cfg, copy);
        assertEquals(cfg.getRequireApproval(), copy.getRequireApproval());
        assertEquals(cfg.getExempt(), copy.getExempt());
        assertEquals(4, copy.getMaxPausesPerTurn());
        assertEquals(7, copy.getMaxAutoApprovalsPerTurn());
        assertEquals("STOP", copy.getOnNoProgress());
        assertEquals("PT5M", copy.getApprovalTimeout());
        assertEquals(HitlTimeoutPolicy.AUTO_REJECT, copy.getTimeoutPolicy());
        assertEquals("why", copy.getPauseReason());
        assertEquals("pending", copy.getPendingMessage());
        assertEquals("PAUSE", copy.getInGroupTurns());
        assertNull(cfg.getSuspendedPatterns(), "the cached original is never modified");
        assertNull(cfg.getRules());
    }

    @Test
    @DisplayName("an auto-approve rule overlapping a suspended pattern waits instead, in either direction; other rules are kept as they are")
    void overlappingRulesWait() {
        ApprovalRule narrower = rule("http.post:/crm/notes", HitlTimeoutPolicy.AUTO_APPROVE);
        narrower.setApprovalTimeout("PT1M");
        narrower.setPauseReason("reason");
        narrower.setApprovalCategory("cat");
        narrower.setPendingMessage("msg");
        ApprovalRule broader = rule("http.*", HitlTimeoutPolicy.AUTO_APPROVE);
        ApprovalRule unrelated = rule("mcp:*", HitlTimeoutPolicy.AUTO_APPROVE);
        ApprovalRule waiting = rule("http.post:/crm/*", HitlTimeoutPolicy.WAIT_INDEFINITELY);
        ApprovalRule noMatch = rule(null, HitlTimeoutPolicy.AUTO_APPROVE);
        var cfg = withRules(narrower, broader, unrelated, waiting, noMatch);

        ToolApprovalsConfig copy = SuspendedGate.overlay(cfg, Map.of("http.post:/crm/*", "c"));

        List<ApprovalRule> rules = new ArrayList<>(copy.getRules());
        assertEquals(6, rules.size(), "five originals and one synthetic");
        assertEquals(HitlTimeoutPolicy.WAIT_INDEFINITELY, rules.get(0).getTimeoutPolicy());
        assertEquals("PT1M", rules.get(0).getApprovalTimeout());
        assertEquals("reason", rules.get(0).getPauseReason());
        assertEquals("cat", rules.get(0).getApprovalCategory());
        assertEquals("msg", rules.get(0).getPendingMessage());
        assertEquals(HitlTimeoutPolicy.WAIT_INDEFINITELY, rules.get(1).getTimeoutPolicy(), "the broader rule also overlaps");
        assertSame(unrelated, rules.get(2));
        assertSame(waiting, rules.get(3));
        assertSame(noMatch, rules.get(4));
        assertEquals("c", rules.get(5).getApprovalCategory());
    }

    @Test
    @DisplayName("overlapsAutoApprove is false for a null rule and for a rule that is not auto-approve")
    void overlapGuards() {
        assertFalse(SuspendedGate.overlapsAutoApprove(null, List.of("a:*")));
        assertFalse(SuspendedGate.overlapsAutoApprove(rule("a:*", HitlTimeoutPolicy.AUTO_REJECT), List.of("a:*")));
        assertFalse(SuspendedGate.overlapsAutoApprove(rule("a:*", null), List.of("a:*")));
        assertTrue(SuspendedGate.overlapsAutoApprove(rule("a:*", HitlTimeoutPolicy.AUTO_APPROVE), List.of("b:*", "a:x")));
    }
}
