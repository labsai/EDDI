/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.tools;

import ai.labs.eddi.configs.hitl.HitlTimeoutPolicy;
import ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig;
import ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig.ApprovalRule;
import ai.labs.eddi.engine.hitl.oversight.autonomy.SuspendedGate;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The earned-autonomy overlay on the approval gate: a suspended pattern gates a
 * call the configuration allows, and never un-gates anything.
 */
@DisplayName("ToolApprovalGate — suspended earned-autonomy patterns")
class ToolApprovalGateSuspensionTest {

    private static final Map<String, String> SOURCES = Map.of("createNote", "http", "lookup", "http", "deleteAll", "http");
    private static final Map<String, String> ENDPOINTS = Map.of("createNote", "post:/crm/notes", "lookup", "get:/crm/contacts",
            "deleteAll", "delete:/crm/contacts");

    private final ToolApprovalGate gate = new ToolApprovalGate();

    private static ToolExecutionRequest req(String id, String name) {
        return ToolExecutionRequest.builder().id(id).name(name).arguments("{}").build();
    }

    private static ToolApprovalsConfig cfg(List<String> require, List<String> exempt) {
        var cfg = new ToolApprovalsConfig();
        cfg.setRequireApproval(require);
        cfg.setExempt(exempt);
        return cfg;
    }

    private static ToolApprovalsConfig suspended(ToolApprovalsConfig base, String... patterns) {
        Map<String, String> map = new LinkedHashMap<>();
        for (String p : patterns) {
            map.put(p, "tool:" + p);
        }
        return SuspendedGate.overlay(base, map);
    }

    @Nested
    @DisplayName("re-gating")
    class Regating {

        @Test
        @DisplayName("an exempt call matching a suspended pattern is gated with suspended:<pattern>")
        void exemptCallGated() {
            var effective = suspended(cfg(List.of("http.post:*"), List.of("http.post:/crm/notes")), "http.post:/crm/notes");

            var result = gate.classify(List.of(req("c1", "createNote")), SOURCES, ENDPOINTS, effective, ClearedToolCalls.none());

            assertEquals(1, result.gated().size());
            assertTrue(result.allowed().isEmpty());
            assertEquals("suspended:http.post:/crm/notes", result.gateReasonByCallId().get("c1"));
        }

        @Test
        @DisplayName("an unmatched call matching a suspended pattern is gated")
        void unmatchedCallGated() {
            var effective = suspended(cfg(List.of("http.delete:*"), null), "http.get:/crm/*");

            var result = gate.classify(List.of(req("c1", "lookup")), SOURCES, ENDPOINTS, effective, ClearedToolCalls.none());

            assertEquals("suspended:http.get:/crm/*", result.gateReasonByCallId().get("c1"));
        }

        @Test
        @DisplayName("a suspension gates even when requireApproval is empty — the early return must not skip it")
        void earlyReturnDoesNotSkipSuspension() {
            var effective = suspended(cfg(List.of(), null), "http.post:/crm/notes");

            var result = gate.classify(List.of(req("c1", "createNote"), req("c2", "lookup")), SOURCES, ENDPOINTS, effective,
                    ClearedToolCalls.none());

            assertEquals(List.of("c1"), result.gated().stream().map(ToolExecutionRequest::id).toList());
            assertEquals(List.of("c2"), result.allowed().stream().map(ToolExecutionRequest::id).toList());
        }

        @Test
        @DisplayName("a suspension on a null config (the promotion emptied the gate) still gates")
        void suspensionOnNullConfig() {
            var effective = suspended(null, "http.post:/crm/notes");

            var result = gate.classify(List.of(req("c1", "createNote")), SOURCES, ENDPOINTS, effective, ClearedToolCalls.none());

            assertEquals(1, result.gated().size());
        }

        @Test
        @DisplayName("a call a requireApproval pattern gates keeps its own reason — a suspension never un-gates or relabels")
        void requireGatedKeepsReason() {
            var effective = suspended(cfg(List.of("http.delete:*"), null), "http.*:*");

            var result = gate.classify(List.of(req("c1", "deleteAll")), SOURCES, ENDPOINTS, effective, ClearedToolCalls.none());

            assertEquals("http.delete:*", result.gateReasonByCallId().get("c1"));
        }

        @Test
        @DisplayName("an already-executed approved call is gated as a repeat, not as a suspension (clearance first)")
        void clearanceBeforeSuspension() {
            var cleared = ClearedToolCalls.none();
            cleared.clear("c1", "createNote", "{}");
            var effective = suspended(cfg(List.of("http.post:*"), List.of("http.post:/crm/notes")), "http.post:/crm/notes");

            var result = gate.classify(List.of(req("c1", "createNote")), SOURCES, ENDPOINTS, effective, cleared);

            assertEquals(ToolApprovalGate.REPEAT_REASON, result.gateReasonByCallId().get("c1"));
        }

        @Test
        @DisplayName("without suspensions the gate behaves exactly as configured")
        void noSuspensionUnchanged() {
            var config = cfg(List.of("http.post:*"), List.of("http.post:/crm/notes"));

            var result = gate.classify(List.of(req("c1", "createNote")), SOURCES, ENDPOINTS, config, ClearedToolCalls.none());

            assertTrue(result.gated().isEmpty());
            assertSame(config, SuspendedGate.overlay(config, Map.of()), "no suspension must hand back the very same config");
        }
    }

    @Nested
    @DisplayName("the overlay copy")
    class Overlay {

        @Test
        @DisplayName("is a copy: the cached config is never mutated")
        void copyNotMutation() {
            var config = cfg(List.of("http.post:*"), List.of("http.post:/crm/notes"));

            var effective = suspended(config, "http.post:/crm/notes");

            assertNotSame(config, effective);
            assertNull(config.getSuspendedPatterns());
            assertNull(config.getRules());
            assertEquals(List.of("http.post:/crm/notes"), effective.getSuspendedPatterns());
        }

        @Test
        @DisplayName("adds a WAIT_INDEFINITELY rule for the pattern, so the governing timeout policy waits for a human")
        void syntheticRuleWaits() {
            var config = cfg(List.of("http.post:*"), List.of("http.post:/crm/notes"));
            config.setTimeoutPolicy(HitlTimeoutPolicy.AUTO_APPROVE);
            config.setApprovalTimeout("PT5M");
            var effective = suspended(config, "http.post:/crm/notes");

            var gated = gate.classify(List.of(req("c1", "createNote")), SOURCES, ENDPOINTS, effective, ClearedToolCalls.none()).gated();
            ApprovalRule governing = ToolApprovalRules.governing(gated, SOURCES, ENDPOINTS, effective);

            assertEquals(HitlTimeoutPolicy.WAIT_INDEFINITELY, governing.getTimeoutPolicy());
            assertEquals("tool:http.post:/crm/notes", governing.getApprovalCategory(), "the decision is filed under the suspended category");
        }

        @Test
        @DisplayName("an overlapping AUTO_APPROVE rule waits instead — a suspension never auto-executes")
        void overlappingAutoApproveDemoted() {
            var config = cfg(List.of("http.post:*"), List.of("http.post:/crm/*"));
            var rule = new ApprovalRule();
            rule.setMatch("http.post:/crm/notes");
            rule.setTimeoutPolicy(HitlTimeoutPolicy.AUTO_APPROVE);
            rule.setApprovalTimeout("PT1M");
            config.setRules(List.of(rule));

            var effective = suspended(config, "http.post:/crm/*");

            var copied = effective.getRules().stream().filter(r -> "http.post:/crm/notes".equals(r.getMatch())).findFirst().orElseThrow();
            assertEquals(HitlTimeoutPolicy.WAIT_INDEFINITELY, copied.getTimeoutPolicy());
            assertEquals(HitlTimeoutPolicy.AUTO_APPROVE, rule.getTimeoutPolicy(), "the original rule is untouched");
        }

        @Test
        @DisplayName("the fail-closed UNDETERMINED config and an existing overlay pass through unchanged")
        void passThrough() {
            assertSame(ToolApprovalsConfig.UNDETERMINED, suspended(ToolApprovalsConfig.UNDETERMINED, "x"));
            var once = suspended(cfg(List.of("a"), null), "b");
            assertSame(once, SuspendedGate.overlay(once, Map.of("c", "tool:c")));
        }

        @Test
        @DisplayName("a task-level merge in strict mode keeps the agent-level suspensions")
        void strictMergeKeepsSuspensions() {
            var agent = suspended(cfg(List.of("http.post:*"), List.of("http.post:/crm/notes")), "http.post:/crm/notes");
            var task = cfg(List.of("http.put:*"), null);

            var merged = TaskToolApprovalsResolver.resolve(agent, task, TaskToolApprovalsResolver.Mode.STRICT);

            assertEquals(List.of("http.post:/crm/notes"), merged.getSuspendedPatterns());
        }
    }

    @Nested
    @DisplayName("firstExemptMatch")
    class FirstExemptMatch {

        @Test
        @DisplayName("names the exempt pattern that lets a call through")
        void exemptPattern() {
            assertEquals("http.post:/crm/notes", ToolApprovalGate.firstExemptMatch("createNote", SOURCES, ENDPOINTS,
                    cfg(List.of("http.post:*"), List.of("http.post:/crm/notes"))));
        }

        @Test
        @DisplayName("is null for a call no exempt pattern matches")
        void unmatched() {
            assertNull(ToolApprovalGate.firstExemptMatch("lookup", SOURCES, ENDPOINTS, cfg(List.of("http.post:*"), List.of("http.post:/x"))));
            assertNull(ToolApprovalGate.firstExemptMatch("lookup", SOURCES, ENDPOINTS, null));
        }
    }
}
