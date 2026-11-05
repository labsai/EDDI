/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.oversight.analytics;

import ai.labs.eddi.engine.groups.outcomes.IOutcomeEventStore;
import ai.labs.eddi.engine.groups.outcomes.OutcomeEvent;
import ai.labs.eddi.engine.groups.outcomes.OutcomeEvent.SubjectType;
import ai.labs.eddi.engine.hitl.oversight.analytics.ApprovalDecision.DeciderKind;
import ai.labs.eddi.engine.hitl.oversight.analytics.OutcomeAnalyticsService.CategoryOutcomes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("OutcomeAnalyticsService — approvals joined with what came of them")
class OutcomeAnalyticsServiceTest {

    private static final Instant T = Instant.parse("2026-11-01T10:00:00Z");
    private static final String GROUP = "g1";

    private IApprovalDecisionStore decisions;
    private IOutcomeEventStore outcomes;
    private OutcomeAnalyticsService service;
    private final List<ApprovalDecision> rows = new ArrayList<>();
    private final List<OutcomeEvent> events = new ArrayList<>();

    private static ApprovalDecision decision(String decisionId, String category, String unitKind, String unitId) {
        return new ApprovalDecision(decisionId + "#0", decisionId, 0, "group", "TASK", unitKind, unitId, null, null, GROUP, null, "gc", null,
                category,
                "RULE", "APPROVED", null, null, null, "alice", DeciderKind.HUMAN_VERIFIED, "MANAGER", T, T, 1000L, false, null, false);
    }

    private static OutcomeEvent outcome(String id, String groupId, SubjectType type, String subjectId, String kind) {
        return new OutcomeEvent(id, groupId, type, subjectId, kind, null, null, null, null, null, "svc", T, T, null);
    }

    @BeforeEach
    void setUp() {
        decisions = mock(IApprovalDecisionStore.class);
        outcomes = mock(IOutcomeEventStore.class);
        when(decisions.query(any(), anyInt(), anyInt())).thenAnswer(i -> List.copyOf(rows));
        // The real store scopes by team and subject; the stub does the same.
        when(outcomes.listBySubjects(anyString(), anyCollection(), anyInt())).thenAnswer(i -> {
            String group = i.getArgument(0);
            Collection<String> ids = i.getArgument(1);
            return events.stream().filter(e -> e.groupId().equals(group) && ids.contains(e.subjectId())).toList();
        });
        service = new OutcomeAnalyticsService(decisions, outcomes);
    }

    private OutcomeAnalyticsService.Result run() {
        return service.summarize(new DecisionFilter(null, GROUP, null, null, null, null, null, null, null));
    }

    private CategoryOutcomes line(OutcomeAnalyticsService.Result r, String category) {
        return r.categories().stream().filter(c -> c.category().equals(category)).findFirst().orElseThrow();
    }

    @Test
    void anOutcomeOnTheDecisionIdCountsForItsCategory() {
        rows.add(decision("d1", "crm.notes", "ANSWER", null));
        rows.add(decision("d2", "crm.notes", "ANSWER", null));
        events.add(outcome("o1", GROUP, SubjectType.DECISION, "d1", "reply_received"));
        events.add(outcome("o2", GROUP, SubjectType.DECISION, "d1", "meeting_booked"));

        var r = run();

        CategoryOutcomes c = line(r, "crm.notes");
        assertEquals(2, c.decisions());
        assertEquals(1, c.decisionsWithOutcomes(), "d2 has none");
        assertEquals(2, c.outcomes());
        assertEquals(Map.of("reply_received", 1L, "meeting_booked", 1L), c.byKind());
        assertEquals(2, r.outcomes());
    }

    @Test
    void anOutcomeOnTheApprovedTaskCountsForTheTaskDecision() {
        rows.add(decision("d1", "group.task", "TASK", "task-9"));
        events.add(outcome("o1", GROUP, SubjectType.TASK, "task-9", "pr_merged"));

        var c = line(run(), "group.task");

        assertEquals(1, c.decisionsWithOutcomes());
        assertEquals(Map.of("pr_merged", 1L), c.byKind());
    }

    @Test
    @DisplayName("a task id only joins a TASK unit — not an ANSWER row that happens to carry the same unitId")
    void taskIdsJoinOnlyTaskUnits() {
        rows.add(decision("d1", "rule.answers", "ANSWER", "task-9"));
        events.add(outcome("o1", GROUP, SubjectType.TASK, "task-9", "pr_merged"));

        var c = line(run(), "rule.answers");

        assertEquals(0, c.outcomes());
        assertEquals(0, run().outcomes());
    }

    @Test
    void anotherTeamsOutcomesNeverCount() {
        rows.add(decision("d1", "crm.notes", "ANSWER", null));
        events.add(outcome("o1", "g-other", SubjectType.DECISION, "d1", "reply_received"));

        assertEquals(0, line(run(), "crm.notes").outcomes());
    }

    @Test
    void oneOutcomeOnADecisionWithSeveralUnitsOfOneCategoryCountsOnce() {
        rows.add(decision("d1", "tool:http.post:*", "TOOL_CALL", "c1"));
        rows.add(decision("d1", "tool:http.post:*", "TOOL_CALL", "c2"));
        events.add(outcome("o1", GROUP, SubjectType.DECISION, "d1", "reply_received"));

        var c = line(run(), "tool:http.post:*");

        assertEquals(2, c.decisions());
        assertEquals(2, c.decisionsWithOutcomes());
        assertEquals(1, c.outcomes(), "one event, one count");
        assertEquals(1, run().outcomes());
    }

    @Test
    void anOutcomeOnAnUnknownSubjectIsLeftOut() {
        rows.add(decision("d1", "crm.notes", "ANSWER", null));
        events.add(outcome("o1", GROUP, SubjectType.DECISION, "d-unknown", "reply_received"));

        assertEquals(0, run().outcomes());
    }

    @Test
    void categoriesAreSortedByDecisionsThenName() {
        rows.add(decision("a", "b.cat", "ANSWER", null));
        rows.add(decision("b", "a.cat", "ANSWER", null));
        rows.add(decision("c", "z.cat", "ANSWER", null));
        rows.add(decision("d", "z.cat", "ANSWER", null));

        assertEquals(List.of("z.cat", "a.cat", "b.cat"), run().categories().stream().map(CategoryOutcomes::category).toList());
    }

    @Test
    void aReportNeedsAGroupAndReadsNothingWithout() {
        assertThrows(IllegalArgumentException.class,
                () -> service.summarize(new DecisionFilter(null, null, null, null, null, null, null, null, null)));
        verify(decisions, never()).query(any(), anyInt(), anyInt());
    }

    @Test
    void noDecisionsMeansNoOutcomeRead() {
        var r = run();
        assertTrue(r.categories().isEmpty());
        verify(outcomes, never()).listBySubjects(eq(GROUP), anyCollection(), anyInt());
    }

    @Test
    void subjectKeysAreReadInBatches() {
        for (int i = 0; i < OutcomeAnalyticsService.KEY_BATCH + 10; i++) {
            rows.add(decision("d" + i, "cat", "ANSWER", null));
        }

        run();

        verify(outcomes, org.mockito.Mockito.times(2)).listBySubjects(eq(GROUP), anyCollection(), anyInt());
    }
}
