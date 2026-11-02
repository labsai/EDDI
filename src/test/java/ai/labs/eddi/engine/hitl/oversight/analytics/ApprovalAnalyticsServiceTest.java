/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.oversight.analytics;

import ai.labs.eddi.engine.hitl.oversight.OversightFixtures;
import ai.labs.eddi.engine.hitl.oversight.analytics.ApprovalAnalyticsService.GroupBy;
import ai.labs.eddi.engine.hitl.oversight.analytics.ApprovalDecision.DeciderKind;
import ai.labs.eddi.engine.hitl.oversight.samples.AuditSample;
import ai.labs.eddi.engine.hitl.oversight.samples.IAuditSampleStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static ai.labs.eddi.engine.hitl.oversight.OversightFixtures.NOW;
import static ai.labs.eddi.engine.hitl.oversight.OversightFixtures.decision;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("approval analytics")
class ApprovalAnalyticsServiceTest {

    @Nested
    @DisplayName("LatencyHistogram")
    class Histogram {

        @Test
        @DisplayName("empty: no percentiles")
        void empty() {
            assertNull(new LatencyHistogram().percentile(0.5));
        }

        @Test
        @DisplayName("everything in one bucket interpolates within it")
        void oneBucket() {
            var h = new LatencyHistogram();
            for (int i = 0; i < 10; i++) {
                h.add(2_000L);
            }
            // bucket [1s, 5s): median at the middle
            assertEquals(3_000L, h.percentile(0.5));
            assertEquals(4_600L, h.percentile(0.9));
        }

        @Test
        @DisplayName("p50 and p90 across buckets; the open bucket reports its lower bound; null is ignored")
        void acrossBuckets() {
            var h = new LatencyHistogram();
            for (int i = 0; i < 5; i++) {
                h.add(500L);
            }
            for (int i = 0; i < 4; i++) {
                h.add(30_000L);
            }
            h.add(400_000_000L);
            h.add(null);
            assertEquals(10, h.total());
            assertEquals(1_000L, h.percentile(0.5));
            assertEquals(60_000L, h.percentile(0.9));
            assertEquals(259_200_000L, h.percentile(1.0));
        }
    }

    @Test
    @DisplayName("a summary counts outcomes and computes rates over human decisions only")
    void summary() {
        List<ApprovalDecision> rows = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            rows.add(decision("APPROVED", NOW.minusSeconds(i)));
        }
        rows.add(decision("APPROVED_EDITED", NOW.minusSeconds(10)));
        rows.add(decision("REJECTED", NOW.minusSeconds(11), "bob", DeciderKind.HUMAN_VERIFIED, "SLACK", "TONE"));
        rows.add(decision("REJECTED", NOW.minusSeconds(12), "bob", DeciderKind.HUMAN_VERIFIED, "SLACK", "TONE"));
        ApprovalDecision timeout = decision("AUTO_APPROVED", NOW.minusSeconds(13), "system:timeout", DeciderKind.SYSTEM, "TIMEOUT", null);
        rows.add(new ApprovalDecision(timeout.id(), timeout.decisionId(), 0, timeout.surface(), timeout.pauseType(), timeout.unitKind(),
                timeout.unitId(), timeout.agentId(), 3, null, null, null, null, timeout.category(), null, timeout.outcome(), null, null, null,
                timeout.decidedBy(), timeout.deciderKind(), timeout.channel(), null, timeout.decidedAt(), null, true, null, false));

        ApprovalSummary s = ApprovalAnalyticsService.summarize("all", rows, null);

        assertEquals(10, s.total());
        assertEquals(9, s.humanDecisions());
        assertEquals(0.6667, s.uneditedApprovalRate());
        assertEquals(0.1111, s.editRate());
        assertEquals(0.2222, s.rejectionRate());
        assertEquals(0.1, s.timeoutShare());
        assertEquals("TONE", s.topReasonCodes().getFirst().code());
        assertEquals(2, s.topReasonCodes().getFirst().count());
        assertEquals(2L, s.channelMix().get("SLACK"));
        assertEquals(180_000L, s.latencyP50Ms(), "every row waited exactly one minute: bucket [1m, 5m), median at its middle");
    }

    @Test
    @DisplayName("grouped by category, a promoted category with only samples still gets a bucket; expired share is a capacity signal")
    void groupedWithSamples() {
        IApprovalDecisionStore decisions = mock(IApprovalDecisionStore.class);
        IAuditSampleStore samples = mock(IAuditSampleStore.class);
        when(decisions.query(any(), anyInt(), anyInt())).thenReturn(List.of(decision("APPROVED", NOW)));
        AuditSample pending = OversightFixtures.sample("s1");
        AuditSample expired = pending.withColumns(null, null, AuditSample.Status.EXPIRED, 2);
        AuditSample other = new AuditSample("s3", "agent-1", 3, "c", null, "c3", "lookup", "http", "get:/x", "tool:http.get:*", "http.get:*",
                null, "{}", "d", null, NOW, NOW, AuditSample.Status.OK, null, null, "erin", NOW, 2);
        when(samples.query(any(), anyInt(), anyInt())).thenReturn(List.of(pending, expired, other));
        var service = new ApprovalAnalyticsService(decisions, samples);

        var result = service.compute(new DecisionFilter("agent-1", null, null, NOW.minusSeconds(86_400), NOW.plusSeconds(1), null, null, null,
                null), GroupBy.CATEGORY);

        assertEquals(List.of("tool:http.get:*", OversightFixtures.CATEGORY), result.buckets().stream().map(ApprovalSummary::key).toList());
        var notes = result.buckets().get(1);
        assertEquals(1, notes.total());
        assertEquals(2, notes.samples().sampled());
        assertEquals(1, notes.samples().expired());
        assertEquals(1.0, notes.samples().expiredShare());
        assertEquals(0, result.buckets().getFirst().total(), "the sample-only category has no decisions");
    }

    @Test
    @DisplayName("groupBy parses the documented values and refuses others")
    void groupByParse() {
        assertEquals(GroupBy.AGENT_VERSION, GroupBy.parse("agentVersion"));
        assertEquals(GroupBy.NONE, GroupBy.parse(null));
        assertThrows(IllegalArgumentException.class, () -> GroupBy.parse("tenant"));
    }

    @Test
    @DisplayName("day and week keys are UTC dates; weeks start on Monday")
    void dayWeek() {
        Instant sunday = Instant.parse("2026-11-01T23:30:00Z");
        assertEquals("2026-11-01", ApprovalAnalyticsService.day(sunday));
        assertEquals("2026-10-26", ApprovalAnalyticsService.week(sunday));
        assertTrue(ApprovalAnalyticsService.week(NOW).startsWith("2026-11-02"));
    }
}
