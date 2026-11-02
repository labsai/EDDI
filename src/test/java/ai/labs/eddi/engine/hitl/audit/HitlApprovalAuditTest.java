/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.audit;

import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.hitl.model.HitlReviewConfig;
import ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig;
import ai.labs.eddi.engine.audit.AuditLedgerService;
import ai.labs.eddi.engine.audit.model.AuditEntry;
import ai.labs.eddi.engine.hitl.audit.HitlDecisionUnit.UnitKind;
import ai.labs.eddi.engine.hitl.review.ApprovalCategories;
import ai.labs.eddi.engine.hitl.review.HitlDecisionRecords;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision.HitlVerdict;
import ai.labs.eddi.engine.lifecycle.model.ToolCallDecision;
import ai.labs.eddi.engine.memory.model.PendingToolCallBatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("HitlApprovalAudit — the hitl.approval contract")
class HitlApprovalAuditTest {

    private AuditLedgerService ledger;
    private List<HitlDecisionRecorded> events;
    private HitlApprovalAudit audit;

    @BeforeEach
    void setUp() {
        ledger = mock(AuditLedgerService.class);
        when(ledger.isEnabled()).thenReturn(true);
        events = new ArrayList<>();
        audit = new HitlApprovalAudit(ledger, events::add);
    }

    private AuditEntry submitted() {
        var captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(ledger).submit(captor.capture());
        return captor.getValue();
    }

    private static HitlDecision decision(HitlVerdict verdict, String decidedBy) {
        var decision = new HitlDecision();
        decision.setVerdict(verdict);
        decision.setDecidedBy(decidedBy);
        return decision;
    }

    @Nested
    @DisplayName("regular RULE decision")
    class RuleDecision {

        @Test
        @DisplayName("an edited approval is APPROVED_EDITED with edit metrics, latency, channel and category")
        void editedApproval() {
            var hitl = new AgentConfiguration.HitlConfig();
            var review = new HitlReviewConfig();
            review.setApprovalCategory("github.reply");
            hitl.setReview(review);
            var decision = decision(HitlVerdict.APPROVED, "alice");
            decision.setEditedAnswer("The release is Monday.");
            decision.setReasonCode("FACTUAL_ERROR");
            decision.setChannel(HitlChannel.MANAGER);
            Instant pausedAt = Instant.parse("2026-11-02T10:00:00Z");
            var record = HitlDecisionRecords.regularDecision("c1", "agent-1", 3, "owner", "production", decision, false, null, hitl,
                    new HitlDecisionRecords.RegularPause(pausedAt, "WAIT_INDEFINITELY", "The release is Friday."), null, null)
                    .decidedAt(pausedAt.plusSeconds(90));

            String id = audit.write(record, "decision-1");

            assertEquals("decision-1", id);
            AuditEntry entry = submitted();
            assertEquals("decision-1", entry.id());
            assertEquals(HitlApprovalAudit.TASK_ID, entry.taskId());
            assertEquals("agent-1", entry.agentId());
            assertEquals(3, entry.agentVersion());
            Map<String, Object> out = entry.output();
            assertEquals("APPROVED", out.get("verdict"), "legacy field kept");
            assertEquals("alice", out.get("decidedBy"));
            assertEquals(false, out.get("automated"));
            assertEquals(2, out.get("schema"));
            assertEquals("regular", out.get("surface"));
            assertEquals("RULE", out.get("pauseType"));
            assertEquals(HitlDecision.pauseIdOf(pausedAt), out.get("pauseId"));
            assertEquals(90_000L, out.get("latencyMs"));
            assertEquals("MANAGER", out.get("channel"));
            assertEquals("APPROVED_EDITED", out.get("decisionOutcome"));
            assertEquals("github.reply", out.get("approvalCategory"));
            assertEquals("CONFIG", out.get("approvalCategorySource"));
            assertEquals("FACTUAL_ERROR", out.get("reasonCode"));
            assertEquals("WAIT_INDEFINITELY", out.get("timeoutPolicy"));

            assertEquals(1, events.size());
            HitlDecisionRecorded event = events.get(0);
            assertEquals("decision-1", event.auditEntryId());
            HitlDecisionUnit unit = event.units().get(0);
            assertEquals(UnitKind.ANSWER, unit.unitKind());
            assertEquals(DecisionOutcome.APPROVED_EDITED, unit.decisionOutcome());
            assertEquals("github.reply", unit.approvalCategory());
            assertEquals(ApprovalCategories.Source.CONFIG, unit.approvalCategorySource());
            assertEquals("FACTUAL_ERROR", unit.reasonCode());
            assertEquals(HitlChannel.MANAGER, unit.channel());
            assertEquals(90_000L, unit.latencyMs());
            assertEquals("agent-1", unit.agentId());
            assertNull(unit.groupId());
            assertEquals(EditSummary.Kind.ANSWER, unit.edit().kind());
            assertEquals("The release is Monday.", unit.edit().editedText());
            assertEquals(unit, HitlApprovalAudit.parse(entry).get(0), "the event carries what parse reads back");
        }

        @Test
        @DisplayName("an edit identical to the draft is a plain APPROVED with no edit")
        void unchangedEdit() {
            var hitl = new AgentConfiguration.HitlConfig();
            var decision = decision(HitlVerdict.APPROVED, "alice");
            decision.setEditedAnswer("Same.");
            audit.write(HitlDecisionRecords.regularDecision("c1", "agent-1", 1, "u", null, decision, false, null, hitl,
                    new HitlDecisionRecords.RegularPause(null, null, "Same."), null, null));
            HitlDecisionUnit unit = events.get(0).units().get(0);
            assertEquals(DecisionOutcome.APPROVED, unit.decisionOutcome());
            assertNull(unit.edit());
            assertEquals("rule:agent-1", unit.approvalCategory());
            assertEquals(ApprovalCategories.Source.DERIVED, unit.approvalCategorySource());
            assertNull(unit.latencyMs(), "pausedAt unknown");
            assertEquals(HitlChannel.REST, unit.channel());
        }

        @Test
        @DisplayName("a timeout rejection is AUTO_REJECTED via TIMEOUT with an automation reason")
        void timeout() {
            audit.write(HitlDecisionRecords.regularDecision("c1", "a", 1, "u", null, decision(HitlVerdict.REJECTED, "system:timeout"), false,
                    null, null, null, null, null));
            AuditEntry entry = submitted();
            assertEquals("TIMEOUT", entry.output().get("channel"));
            assertEquals("TIMEOUT_POLICY", entry.output().get("automationReason"));
            assertEquals(true, entry.output().get("automated"));
            assertEquals(DecisionOutcome.AUTO_REJECTED, events.get(0).units().get(0).decisionOutcome());
        }
    }

    @Nested
    @DisplayName("regular TOOL_CALL decision")
    class ToolCallDecisionAudit {

        private PendingToolCallBatch batch() {
            var cfg = new ToolApprovalsConfig();
            cfg.setRequireApproval(List.of("http.post:*"));
            var rule = new ToolApprovalsConfig.ApprovalRule();
            rule.setMatch("http.post:*/comments");
            rule.setApprovalCategory("github.reply");
            cfg.setRules(List.of(rule));
            var batch = new PendingToolCallBatch();
            batch.setEffectiveToolApprovals(cfg);
            batch.setCalls(List.of(call("c1", "post_comment", "http.post:*", "http.post:*/comments"), call("c2", "delete", "http.post:*", null)));
            return batch;
        }

        private PendingToolCallBatch.PendingToolCall call(String id, String tool, String gate, String rule) {
            var call = new PendingToolCallBatch.PendingToolCall();
            call.setCallId(id);
            call.setToolName(tool);
            call.setArgumentsRaw("{\"body\":\"hello\"}");
            call.setGateReason(gate);
            call.setMatchedRule(rule);
            return call;
        }

        @Test
        @DisplayName("per-call outcomes, categories and reason codes; MIXED on top; arguments never in the entry")
        void mixed() {
            var decision = decision(HitlVerdict.APPROVED, "mcp:alice");
            var amend = new ToolCallDecision();
            amend.setVerdict(HitlVerdict.APPROVED);
            amend.setAmendedArguments("{\"body\":\"hello there\"}");
            amend.setReasonCode("TONE");
            var reject = new ToolCallDecision();
            reject.setVerdict(HitlVerdict.REJECTED);
            reject.setReasonCode("OFF_POLICY");
            var perCall = new LinkedHashMap<String, ToolCallDecision>();
            perCall.put("c1", amend);
            perCall.put("c2", reject);
            decision.setToolDecisions(perCall);

            audit.write(HitlDecisionRecords.regularDecision("conv", "a", 1, "u", null, decision, true, batch(), null, null, List.of(), null));

            AuditEntry entry = submitted();
            assertEquals("MIXED", entry.output().get("decisionOutcome"));
            assertEquals("MCP", entry.output().get("channel"));
            assertEquals("TOOL_CALL", entry.output().get("pauseType"));
            assertFalse(entry.output().toString().contains("hello there"), "raw arguments never reach the ledger");
            List<HitlDecisionUnit> units = HitlApprovalAudit.parse(entry);
            assertEquals(2, units.size());
            HitlDecisionUnit first = units.get(0);
            assertEquals(DecisionOutcome.APPROVED_EDITED, first.decisionOutcome());
            assertEquals("github.reply", first.approvalCategory());
            assertEquals("TONE", first.reasonCode());
            assertEquals(EditSummary.Kind.TOOL_ARGUMENTS, first.edit().kind());
            assertNull(first.edit().editedText());
            assertEquals(1, first.edit().fieldsChanged());
            assertEquals(EditMetrics.sha256Hex("{\"body\":\"hello there\"}"), first.argsDigest());
            HitlDecisionUnit second = units.get(1);
            assertEquals(DecisionOutcome.REJECTED, second.decisionOutcome());
            assertEquals("tool:http.post:*", second.approvalCategory());
            assertEquals(ApprovalCategories.Source.DERIVED, second.approvalCategorySource());
            assertEquals("OFF_POLICY", second.reasonCode());
            assertNull(second.edit());
        }

        @Test
        @DisplayName("a cancelled tool pause aborts every call")
        void cancelled() {
            audit.write(HitlDecisionRecords.regularCancellation("conv", "a", 1, "u", null, "TOOL_CALL", batch(), null, null, null,
                    "CANCEL_GRACEFUL", "system:timeout"));
            AuditEntry entry = submitted();
            assertEquals("CANCELLED", entry.output().get("verdict"));
            assertEquals("ABORTED", entry.output().get("decisionOutcome"));
            assertEquals("CANCEL_GRACEFUL", entry.output().get("mode"));
            assertEquals(2, events.get(0).units().size());
            assertTrue(events.get(0).units().stream().allMatch(u -> u.decisionOutcome() == DecisionOutcome.ABORTED));
        }
    }

    @Nested
    @DisplayName("group decisions")
    class Groups {

        @Test
        @DisplayName("a TASK decision lists every awaiting task; unlisted tasks take the top-level verdict")
        void taskUnits() {
            var pause = new HitlDecisionRecords.GroupPause("TASK", "Execution", Instant.now().minusSeconds(5), null, List.of("t1", "t2"), "r");
            var outcomes = new LinkedHashMap<String, HitlDecisionRecords.TaskOutcome>();
            outcomes.put("t1", new HitlDecisionRecords.TaskOutcome(HitlVerdict.REJECTED, "SCOPE", null));
            audit.write(HitlDecisionRecords.groupDecision("gc1", "g1", "owner", decision(HitlVerdict.APPROVED, "bob"), null, pause, outcomes,
                    null));
            AuditEntry entry = submitted();
            assertEquals("g1", entry.agentId(), "the agentId column of a group entry carries the groupId, as before");
            assertEquals("group", entry.output().get("surface"));
            List<HitlDecisionUnit> units = events.get(0).units();
            assertEquals(2, units.size());
            assertEquals(DecisionOutcome.REJECTED, units.get(0).decisionOutcome());
            assertEquals("SCOPE", units.get(0).reasonCode());
            assertEquals(DecisionOutcome.APPROVED, units.get(1).decisionOutcome());
            assertEquals("group:g1:task:Execution", units.get(1).approvalCategory());
            assertEquals("g1", units.get(1).groupId());
            assertNull(units.get(1).agentId());
        }

        @Test
        @DisplayName("a cancelled HUMAN_TURN has no units and fires no event")
        void humanTurnCancel() {
            var pause = new HitlDecisionRecords.GroupPause("HUMAN_TURN", "Debate", null, null, List.of(), null);
            audit.write(HitlDecisionRecords.groupCancellation("gc1", "g1", "u", null, pause, "CANCEL_GRACEFUL", "carol"));
            AuditEntry entry = submitted();
            assertNull(entry.output().get("decisionOutcome"), "HUMAN_TURN has no outcome");
            assertEquals("carol", entry.output().get("decidedBy"), "a group cancel names who cancelled");
            assertTrue(events.isEmpty());
        }
    }

    @Nested
    @DisplayName("schema 1 entries")
    class SchemaOne {

        private AuditEntry legacy(String agentId, Map<String, Object> output) {
            return new AuditEntry("old-1", "conv", agentId, 2, "u", "production", -1, "hitl.approval", "hitl", -1, 0L, Map.of(), output, null,
                    null, List.of(), 0.0, Instant.parse("2026-01-01T00:00:00Z"), null, null);
        }

        @Test
        void ruleDecision() {
            List<HitlDecisionUnit> units = HitlApprovalAudit.parse(legacy("a", Map.of("verdict", "REJECTED", "decidedBy", "slack:U1",
                    "automated", false, "note", "Decided via Slack by U1")));
            assertEquals(1, units.size());
            HitlDecisionUnit unit = units.get(0);
            assertEquals(1, unit.schema());
            assertEquals(UnitKind.ANSWER, unit.unitKind());
            assertEquals(DecisionOutcome.REJECTED, unit.decisionOutcome());
            assertEquals(HitlChannel.SLACK, unit.channel());
            assertNull(unit.approvalCategory());
            assertNull(unit.latencyMs());
            assertEquals(Instant.parse("2026-01-01T00:00:00Z"), unit.decidedAt());
        }

        @Test
        void toolDecisions() {
            var call = new LinkedHashMap<String, Object>();
            call.put("callId", "c1");
            call.put("verdict", "APPROVED");
            call.put("amended", true);
            call.put("toolName", "t");
            call.put("argsDigest", "abc");
            List<HitlDecisionUnit> units = HitlApprovalAudit.parse(legacy("a", Map.of("verdict", "APPROVED", "decidedBy", "alice",
                    "pauseType", "TOOL_CALL", "toolDecisions", List.of(call))));
            assertEquals(DecisionOutcome.APPROVED_EDITED, units.get(0).decisionOutcome());
            assertNull(units.get(0).edit(), "schema 1 never recorded the size of an amendment");
            assertEquals("abc", units.get(0).argsDigest());
        }

        @Test
        void cancelAndHumanTurnAndGroup() {
            assertEquals(DecisionOutcome.ABORTED, HitlApprovalAudit.parse(legacy(null, Map.of("verdict", "CANCELLED", "mode", "CANCEL_GRACEFUL",
                    "decidedBy", "system:timeout", "automated", true))).get(0).decisionOutcome());
            assertTrue(HitlApprovalAudit.parse(legacy("g", Map.of("verdict", "HUMAN_INPUT_SUBMITTED", "surface", "group"))).isEmpty());
            HitlDecisionUnit group = HitlApprovalAudit.parse(legacy("g1", Map.of("verdict", "APPROVED", "decidedBy", "system:timeout",
                    "surface", "group"))).get(0);
            assertEquals(UnitKind.PHASE, group.unitKind());
            assertEquals("g1", group.groupId());
            assertEquals(DecisionOutcome.AUTO_APPROVED, group.decisionOutcome());
            assertEquals(HitlChannel.TIMEOUT, group.channel());
        }

        @Test
        void otherEntriesYieldNothing() {
            assertTrue(HitlApprovalAudit.parse(null).isEmpty());
            var other = new AuditEntry("x", "c", "a", 1, "u", null, 0, "llm", "llm", 0, 0L, Map.of(), Map.of("verdict", "APPROVED"), null, null,
                    List.of(), 0.0, Instant.now(), null, null);
            assertTrue(HitlApprovalAudit.parse(other).isEmpty());
        }
    }

    @Test
    @DisplayName("ledger off: nothing written, no event; a failing ledger never throws")
    void ledgerOffAndFailure() {
        when(ledger.isEnabled()).thenReturn(false);
        assertNull(audit.write(HitlDecisionRecords.regularDecision("c", "a", 1, "u", null, decision(HitlVerdict.APPROVED, "x"), false, null,
                null, null, null, null)));
        verify(ledger, never()).submit(any());
        assertTrue(events.isEmpty());

        when(ledger.isEnabled()).thenReturn(true);
        doThrow(new IllegalStateException("boom")).when(ledger).submit(any());
        assertNull(audit.write(HitlDecisionRecords.regularDecision("c", "a", 1, "u", null, decision(HitlVerdict.APPROVED, "x"), false, null,
                null, null, null, null)));
        assertTrue(events.isEmpty());
    }

    @Test
    @DisplayName("hitl.edit.refused joins its decision by decisionId")
    void editRefused() {
        AuditEntry entry = HitlApprovalAudit.editRefusedEntry("decision-1", "c", "a", 1, "u", null, UnitKind.ANSWER,
                HitlApprovalAudit.REFUSED_SCREENED_OUT, "pii");
        assertEquals(HitlApprovalAudit.EDIT_REFUSED_TASK_ID, entry.taskId());
        assertEquals("decision-1", entry.output().get("decisionId"));
        assertEquals("SCREENED_OUT", entry.output().get("reason"));
        assertEquals("pii", entry.output().get("guardrail"));
        assertNotNull(entry.id());
        assertTrue(HitlApprovalAudit.parse(entry).isEmpty(), "not a decision entry");
    }
}
