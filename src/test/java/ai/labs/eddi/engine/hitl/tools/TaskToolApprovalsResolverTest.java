/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.tools;

import ai.labs.eddi.configs.hitl.HitlTimeoutPolicy;
import ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig;
import ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig.ApprovalRule;
import ai.labs.eddi.engine.hitl.tools.TaskToolApprovalsResolver.Mode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The strict-vs-replace combination contract for task-level
 * {@code toolApprovals} — above all, that STRICT closes the empty-list and
 * exemption bypasses a wholesale replace allowed.
 */
class TaskToolApprovalsResolverTest {

    private static ToolApprovalsConfig agentGate() {
        var cfg = new ToolApprovalsConfig();
        cfg.setRequireApproval(List.of("http.post:*", "http.put:*", "http.patch:*", "http.delete:*"));
        cfg.setExempt(List.of("http.get:*"));
        cfg.setTimeoutPolicy(HitlTimeoutPolicy.WAIT_INDEFINITELY);
        cfg.setMaxAutoApprovalsPerTurn(2);
        return cfg;
    }

    // ── inheritance and replace mode ─────────────────────────────────────

    @Test
    void nullTaskInheritsAgentConfigUnchangedInBothModes() {
        var agent = agentGate();
        assertSame(agent, TaskToolApprovalsResolver.resolve(agent, null, Mode.STRICT));
        assertSame(agent, TaskToolApprovalsResolver.resolve(agent, null, Mode.REPLACE));
    }

    @Test
    void replaceModeHonoursTheTaskConfigWholesale() {
        // The legacy escape hatch must stay byte-identical to the historical
        // behavior: the task config wins, weaker or not.
        var task = new ToolApprovalsConfig();
        task.setRequireApproval(List.of());
        assertSame(task, TaskToolApprovalsResolver.resolve(agentGate(), task, Mode.REPLACE));
    }

    @Test
    void bothNullResolvesToNull_gateInert() {
        assertNull(TaskToolApprovalsResolver.resolve(null, null, Mode.STRICT));
    }

    // ── strict: the two bypass vectors ───────────────────────────────────

    @Test
    void strictNeutralisesTheEmptyRequireApprovalBypass() {
        // requireApproval: [] used to switch the gate off for the task entirely
        // (empty list = gate inert). Union with the agent's patterns keeps every
        // agent-gated write gated.
        var task = new ToolApprovalsConfig();
        task.setRequireApproval(List.of());

        var merged = TaskToolApprovalsResolver.resolve(agentGate(), task, Mode.STRICT);
        assertEquals(List.of("http.post:*", "http.put:*", "http.patch:*", "http.delete:*"),
                merged.getRequireApproval());
    }

    @Test
    void strictIgnoresTaskLevelExemptions() {
        // Exempt beats requireApproval at gate time (ToolApprovalGate P1), so a
        // task-added exemption is precisely the ungating vector. Only the
        // agent's own exemptions survive.
        var task = new ToolApprovalsConfig();
        task.setExempt(List.of("http.post:*"));

        var merged = TaskToolApprovalsResolver.resolve(agentGate(), task, Mode.STRICT);
        assertEquals(List.of("http.get:*"), merged.getExempt());
    }

    // ── strict: strengthening is honoured ────────────────────────────────

    @Test
    void strictUnionLetsATaskGateMoreThanTheAgent() {
        var task = new ToolApprovalsConfig();
        task.setRequireApproval(List.of("mcp:*", "http.post:*"));

        var merged = TaskToolApprovalsResolver.resolve(agentGate(), task, Mode.STRICT);
        assertEquals(List.of("http.post:*", "http.put:*", "http.patch:*", "http.delete:*", "mcp:*"),
                merged.getRequireApproval());
    }

    @Test
    void strictWithNoAgentGateStillHonoursTaskGating() {
        // An agent with no gate at all: the task may add one (that is a pure
        // strengthening), but gets neither exemptions nor AUTO_APPROVE.
        var task = new ToolApprovalsConfig();
        task.setRequireApproval(List.of("http.post:*"));
        task.setExempt(List.of("http.get:*"));
        task.setTimeoutPolicy(HitlTimeoutPolicy.AUTO_APPROVE);

        var merged = TaskToolApprovalsResolver.resolve(null, task, Mode.STRICT);
        assertEquals(List.of("http.post:*"), merged.getRequireApproval());
        assertNull(merged.getExempt());
        assertEquals(HitlTimeoutPolicy.WAIT_INDEFINITELY, merged.getTimeoutPolicy());
    }

    // ── strict: AUTO_APPROVE demotion ────────────────────────────────────

    @Test
    void strictDemotesTaskAutoApproveTheAgentNeverGranted() {
        var task = new ToolApprovalsConfig();
        task.setTimeoutPolicy(HitlTimeoutPolicy.AUTO_APPROVE);

        var merged = TaskToolApprovalsResolver.resolve(agentGate(), task, Mode.STRICT);
        assertEquals(HitlTimeoutPolicy.WAIT_INDEFINITELY, merged.getTimeoutPolicy());
    }

    @Test
    void strictHonoursTaskAutoApproveWhenTheAgentItselfGrantsIt() {
        var agent = agentGate();
        agent.setTimeoutPolicy(HitlTimeoutPolicy.AUTO_APPROVE);
        var task = new ToolApprovalsConfig();
        task.setTimeoutPolicy(HitlTimeoutPolicy.AUTO_APPROVE);

        var merged = TaskToolApprovalsResolver.resolve(agent, task, Mode.STRICT);
        assertEquals(HitlTimeoutPolicy.AUTO_APPROVE, merged.getTimeoutPolicy());
    }

    @Test
    void strictHonoursSafeTaskPolicies() {
        // AUTO_REJECT and ABORT cannot execute anything unattended — a task may
        // choose them freely.
        for (var policy : List.of(HitlTimeoutPolicy.AUTO_REJECT, HitlTimeoutPolicy.ABORT)) {
            var task = new ToolApprovalsConfig();
            task.setTimeoutPolicy(policy);
            assertEquals(policy, TaskToolApprovalsResolver.resolve(agentGate(), task, Mode.STRICT).getTimeoutPolicy());
        }
    }

    @Test
    void strictInheritsAgentPolicyWhenTaskHasNoOpinion() {
        var task = new ToolApprovalsConfig();
        task.setRequireApproval(List.of("mcp:*"));

        var merged = TaskToolApprovalsResolver.resolve(agentGate(), task, Mode.STRICT);
        assertEquals(HitlTimeoutPolicy.WAIT_INDEFINITELY, merged.getTimeoutPolicy());
    }

    // ── strict: scalars and rules ────────────────────────────────────────

    @Test
    void strictBudgetCannotGrowPastAnUnsetAgentValue() {
        // An unset agent budget is the RUNTIME DEFAULT (2), not "no cap" — a
        // task stating 10 against an unset agent value must clamp to it, or the
        // task quietly raised the auto-approval budget. A task may still
        // tighten below the default.
        var agent = new ToolApprovalsConfig();
        agent.setRequireApproval(List.of("http.post:*"));

        var loosening = new ToolApprovalsConfig();
        loosening.setMaxAutoApprovalsPerTurn(10);
        assertEquals(ToolApprovalsConfig.DEFAULT_MAX_AUTO_APPROVALS_PER_TURN,
                TaskToolApprovalsResolver.resolve(agent, loosening, Mode.STRICT).getMaxAutoApprovalsPerTurn());

        var tightening = new ToolApprovalsConfig();
        tightening.setMaxAutoApprovalsPerTurn(0);
        assertEquals(0, TaskToolApprovalsResolver.resolve(agent, tightening, Mode.STRICT).getMaxAutoApprovalsPerTurn());
    }

    @Test
    void strictTakesTheSmallerAutoApprovalBudget() {
        var task = new ToolApprovalsConfig();
        task.setMaxAutoApprovalsPerTurn(10);
        assertEquals(2, TaskToolApprovalsResolver.resolve(agentGate(), task, Mode.STRICT).getMaxAutoApprovalsPerTurn());

        var tighter = new ToolApprovalsConfig();
        tighter.setMaxAutoApprovalsPerTurn(1);
        assertEquals(1, TaskToolApprovalsResolver.resolve(agentGate(), tighter, Mode.STRICT).getMaxAutoApprovalsPerTurn());
    }

    @Test
    void strictDemotesAutoApproveInsideTaskRules() {
        var rule = new ApprovalRule();
        rule.setMatch("http.post:/administration/*");
        rule.setTimeoutPolicy(HitlTimeoutPolicy.AUTO_APPROVE);
        var task = new ToolApprovalsConfig();
        task.setRules(List.of(rule));

        var merged = TaskToolApprovalsResolver.resolve(agentGate(), task, Mode.STRICT);
        assertEquals(1, merged.getRules().size());
        assertEquals(HitlTimeoutPolicy.WAIT_INDEFINITELY, merged.getRules().get(0).getTimeoutPolicy());
        assertEquals("http.post:/administration/*", merged.getRules().get(0).getMatch());
    }

    @Test
    void strictKeepsAgentRulesBehindTaskRules() {
        var agentRule = new ApprovalRule();
        agentRule.setMatch("http.delete:*");
        agentRule.setTimeoutPolicy(HitlTimeoutPolicy.ABORT);
        var agent = agentGate();
        agent.setRules(List.of(agentRule));

        var taskRule = new ApprovalRule();
        taskRule.setMatch("mcp:*");
        taskRule.setTimeoutPolicy(HitlTimeoutPolicy.AUTO_REJECT);
        var task = new ToolApprovalsConfig();
        task.setRules(List.of(taskRule));

        var merged = TaskToolApprovalsResolver.resolve(agent, task, Mode.STRICT);
        assertEquals(List.of("mcp:*", "http.delete:*"),
                merged.getRules().stream().map(ApprovalRule::getMatch).toList());
    }

    @Test
    void strictPrefersTaskCosmetics() {
        var agent = agentGate();
        agent.setPauseReason("agent reason");
        var task = new ToolApprovalsConfig();
        task.setPauseReason("task reason");
        task.setPendingMessage("task pending");

        var merged = TaskToolApprovalsResolver.resolve(agent, task, Mode.STRICT);
        assertEquals("task reason", merged.getPauseReason());
        assertEquals("task pending", merged.getPendingMessage());
    }

    // ── mode parsing ─────────────────────────────────────────────────────

    @Test
    void modeParsesLenientlyAndFailsToStrict() {
        assertEquals(Mode.REPLACE, Mode.parse("replace"));
        assertEquals(Mode.REPLACE, Mode.parse(" Replace "));
        assertEquals(Mode.STRICT, Mode.parse("strict"));
        assertEquals(Mode.STRICT, Mode.parse(null));
        assertEquals(Mode.STRICT, Mode.parse("bogus"));
    }

    // ── undetermined policy (a FAILED read, not an absent one) ───────────

    /**
     * REPLACE is the one path that hands the task config back wholesale, so it is
     * the one that could undo the fail-closed fallback. A task config lives inside
     * the very agent whose policy could not be loaded — it is no evidence that
     * gating is unnecessary.
     */
    @Test
    void undeterminedAgentPolicySurvivesReplaceMode() {
        var task = new ToolApprovalsConfig();
        task.setRequireApproval(List.of());

        assertSame(ToolApprovalsConfig.UNDETERMINED,
                TaskToolApprovalsResolver.resolve(ToolApprovalsConfig.UNDETERMINED, task, Mode.REPLACE),
                "an ungating task config replaced a policy that could not be read");
    }

    @Test
    void undeterminedAgentPolicySurvivesStrictMerge() {
        var task = new ToolApprovalsConfig();
        task.setExempt(List.of("*"));

        assertSame(ToolApprovalsConfig.UNDETERMINED,
                TaskToolApprovalsResolver.resolve(ToolApprovalsConfig.UNDETERMINED, task, Mode.STRICT),
                "a task-level exemption weakened a policy that could not be read");
    }

    @Test
    @DisplayName("the sentinel gates everything on its values alone, not only by identity")
    void undeterminedFailsClosedOnValuesToo() {
        assertEquals(List.of("*"), ToolApprovalsConfig.UNDETERMINED.getRequireApproval());
        assertNull(ToolApprovalsConfig.UNDETERMINED.getExempt(),
                "an exemption on the fail-closed sentinel would let calls through");
        assertTrue(ToolApprovalsConfig.isUndetermined(ToolApprovalsConfig.UNDETERMINED));
    }

    @Test
    @DisplayName("an ordinary config carrying [*] is a strict policy, not a failed read")
    void lookalikeConfigIsNotTreatedAsUndetermined() {
        var lookalike = new ToolApprovalsConfig();
        lookalike.setRequireApproval(List.of("*"));

        assertFalse(ToolApprovalsConfig.isUndetermined(lookalike));
        // ...so it still merges normally instead of short-circuiting.
        var task = new ToolApprovalsConfig();
        task.setRequireApproval(List.of("delete_*"));
        assertNotSame(lookalike, TaskToolApprovalsResolver.resolve(lookalike, task, Mode.STRICT));
    }
}
