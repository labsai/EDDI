/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.oversight.analytics;

import ai.labs.eddi.engine.audit.model.AuditEntry;
import ai.labs.eddi.engine.hitl.audit.DecisionOutcome;
import ai.labs.eddi.engine.hitl.audit.HitlApprovalAudit;
import ai.labs.eddi.engine.hitl.audit.HitlChannel;
import ai.labs.eddi.engine.hitl.audit.HitlDecisionRecorded;
import ai.labs.eddi.engine.hitl.audit.HitlDecisionUnit;
import ai.labs.eddi.engine.hitl.audit.HitlDecisionUnit.UnitKind;
import ai.labs.eddi.engine.hitl.oversight.OversightMetrics;
import ai.labs.eddi.engine.hitl.oversight.analytics.ApprovalDecision.DeciderKind;
import ai.labs.eddi.engine.hitl.oversight.autonomy.AutonomyService;
import ai.labs.eddi.engine.hitl.review.ApprovalCategories;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("ApprovalDecisionProjector")
class ApprovalDecisionProjectorTest {

    private static final Instant DECIDED = Instant.parse("2026-11-02T10:00:00Z");

    private final Set<String> stored = new HashSet<>();
    private IApprovalDecisionStore store;
    private AutonomyService autonomy;
    private ApprovalDecisionProjector projector;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        store = mock(IApprovalDecisionStore.class);
        when(store.insertIfAbsent(any())).thenAnswer(inv -> stored.add(((ApprovalDecision) inv.getArgument(0)).id()));
        autonomy = mock(AutonomyService.class);
        Instance<AutonomyService> instance = mock(Instance.class);
        when(instance.isResolvable()).thenReturn(true);
        when(instance.get()).thenReturn(autonomy);
        projector = new ApprovalDecisionProjector(store, mock(OversightMetrics.class), instance);
    }

    private static HitlDecisionUnit unit(String callId, DecisionOutcome outcome, String reasonCode) {
        return new HitlDecisionUnit("dec-1", 2, "regular", "TOOL_CALL", "conv-1", "agent-1", 3, null, "user-1", "production", "p1",
                DECIDED.minusSeconds(30), DECIDED, 30_000L, "alice", false, HitlChannel.MANAGER, null, "WAIT_INDEFINITELY", UnitKind.TOOL_CALL,
                callId, outcome, "tool:http.post:*", ApprovalCategories.Source.DERIVED, reasonCode, "createNote", "abc", null);
    }

    @Test
    @DisplayName("stores one row per unit, keyed decisionId#index, and an event replayed (or a rebuild) stores nothing twice")
    void idempotent() {
        var event = new HitlDecisionRecorded("dec-1", List.of(unit("c1", DecisionOutcome.APPROVED, null), unit("c2", DecisionOutcome.REJECTED,
                "TONE")));

        projector.onDecisionRecorded(event);
        int second = projector.project(event.units(), true);

        assertEquals(Set.of("dec-1#0", "dec-1#1"), stored);
        assertEquals(0, second);
        var rows = ArgumentCaptor.forClass(List.class);
        verify(autonomy).onDecisions(rows.capture());
        assertEquals(2, rows.getValue().size(), "only newly stored rows reach earned autonomy, once");
    }

    @Test
    @DisplayName("a rebuild does not re-trigger earned autonomy")
    void rebuildDoesNotEvaluate() {
        projector.project(List.of(unit("c1", DecisionOutcome.REJECTED, "FALSE_CLAIM")), false);
        verify(autonomy, never()).onDecisions(any());
    }

    @Test
    @DisplayName("a failing store write is counted and does not stop the other units")
    void failureIsolated() {
        doThrow(new IllegalStateException("db down")).doReturn(true).when(store).insertIfAbsent(any());

        assertEquals(1, projector.project(List.of(unit("c1", DecisionOutcome.APPROVED, null), unit("c2", DecisionOutcome.APPROVED, null)), true));
    }

    @Test
    @DisplayName("row derivation: decider kind, channel, timeout and edit metrics")
    void rowFields() {
        ApprovalDecision row = ApprovalDecision.of(unit("c1", DecisionOutcome.APPROVED, null), 0);
        assertEquals(DeciderKind.HUMAN_VERIFIED, row.deciderKind());
        assertEquals("MANAGER", row.channel());
        assertFalse(row.legacy());
        assertFalse(row.timedOut());
        assertEquals(DeciderKind.SYSTEM, DeciderKind.of("system:timeout", true));
        assertEquals(DeciderKind.UNVERIFIED, DeciderKind.of("unknown", false));
        assertEquals(DeciderKind.UNVERIFIED, DeciderKind.of(null, false));
    }

    /** A pre-6.7 ({@code schema 1}) hitl.approval entry. */
    private static AuditEntry legacyEntry(String verdict, String decidedBy, Map<String, Object> extra) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("verdict", verdict);
        output.put("decidedBy", decidedBy);
        output.put("automated", decidedBy.startsWith("system:"));
        output.putAll(extra);
        return new AuditEntry("legacy-1", "conv-1", "agent-1", 2, "user-1", "production", -1, HitlApprovalAudit.TASK_ID, "hitl", -1, 0L,
                Map.of(), output, null, null, List.of(), 0.0, DECIDED, null, null);
    }

    @ParameterizedTest(name = "{0} by {1} → {2}")
    @CsvSource({"APPROVED,alice,APPROVED,REST", "APPROVED,system:timeout,AUTO_APPROVED,TIMEOUT", "REJECTED,system:no-progress,AUTO_REJECTED,SYSTEM",
            "REJECTED,slack:U1,REJECTED,SLACK", "CANCELLED,alice,ABORTED,REST"})
    @DisplayName("legacy entries are mapped through the F3 parser and marked legacy with a legacy: category")
    void legacyMapping(String verdict, String decidedBy, String outcome, String channel) {
        List<HitlDecisionUnit> units = HitlApprovalAudit.parse(legacyEntry(verdict, decidedBy, Map.of()));

        ApprovalDecision row = ApprovalDecision.of(units.getFirst(), 0);

        assertEquals(outcome, row.outcome());
        assertEquals(channel, row.channel());
        assertTrue(row.legacy());
        assertEquals("legacy:rule:agent-1", row.category());
    }

    @Test
    @DisplayName("a legacy amended tool call is APPROVED_EDITED under legacy:tool:<name>")
    void legacyAmendedToolCall() {
        Map<String, Object> call = new HashMap<>();
        call.put("callId", "c1");
        call.put("verdict", "APPROVED");
        call.put("amended", true);
        call.put("toolName", "createNote");
        var units = HitlApprovalAudit.parse(legacyEntry("APPROVED", "alice", Map.of("toolDecisions", List.of(call))));

        ApprovalDecision row = ApprovalDecision.of(units.getFirst(), 0);

        assertEquals("APPROVED_EDITED", row.outcome());
        assertEquals("legacy:tool:createNote", row.category());
    }
}
