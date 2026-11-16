/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.oversight.analytics;

import ai.labs.eddi.engine.hitl.oversight.analytics.ApprovalAnalyticsService.GroupBy;
import ai.labs.eddi.engine.hitl.oversight.analytics.ApprovalDecision.DeciderKind;
import ai.labs.eddi.engine.hitl.oversight.samples.AuditSample;
import ai.labs.eddi.engine.hitl.oversight.samples.IAuditSampleStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static ai.labs.eddi.engine.hitl.oversight.OversightFixtures.AGENT;
import static ai.labs.eddi.engine.hitl.oversight.OversightFixtures.CATEGORY;
import static ai.labs.eddi.engine.hitl.oversight.OversightFixtures.NOW;
import static ai.labs.eddi.engine.hitl.oversight.OversightFixtures.decision;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("approval analytics branches")
class ApprovalAnalyticsBranchesTest {

    private IApprovalDecisionStore decisionStore;
    private IAuditSampleStore sampleStore;
    private ApprovalAnalyticsService service;
    private final List<ApprovalDecision> rows = new ArrayList<>();
    private final List<AuditSample> samples = new ArrayList<>();

    @BeforeEach
    void setUp() {
        decisionStore = mock(IApprovalDecisionStore.class);
        sampleStore = mock(IAuditSampleStore.class);
        service = new ApprovalAnalyticsService(decisionStore, sampleStore);
        when(decisionStore.query(any(), anyInt(), anyInt())).thenAnswer(inv -> List.copyOf(rows));
        when(sampleStore.query(any(), anyInt(), anyInt())).thenAnswer(inv -> List.copyOf(samples));
    }

    private static ApprovalDecision row(String outcome, String channel, String reasonCode, boolean timedOut, boolean legacy, Long latency,
                                        String category, String agentId, String groupId, Integer version, Instant decidedAt) {
        return new ApprovalDecision("id-" + System.nanoTime(), "d", 0, "regular", "TOOL_CALL", "TOOL_CALL", "u", agentId, version, groupId, "prod",
                "conv", "user-1", category, "DERIVED", outcome, reasonCode, null, null, "alice", DeciderKind.HUMAN_VERIFIED, channel,
                decidedAt != null ? decidedAt.minusSeconds(10) : null, decidedAt, latency, timedOut, "tool", legacy);
    }

    private static AuditSample sample(String agentId, String category, AuditSample.Status status, String reason, Instant sampledAt) {
        return new AuditSample("s-" + System.nanoTime(), agentId, 3, "conv", "user", "call", "tool", "http", "post", category, "p", "p", "{}", "d",
                null, sampledAt, sampledAt != null ? sampledAt.plusSeconds(60) : null, status, reason, null, null, null, 1L);
    }

    private static DecisionFilter filter(String groupId) {
        return new DecisionFilter(null, groupId, null, NOW.minusSeconds(86_400), NOW, null, null, null, null);
    }

    @Nested
    @DisplayName("GroupBy")
    class Grouping {

        @ParameterizedTest
        @CsvSource({"none,NONE,none", "category,CATEGORY,category", "agent,AGENT,agent", "group,GROUP,group", "day,DAY,day", "week,WEEK,week",
                "channel,CHANNEL,channel", "agentVersion,AGENT_VERSION,agentVersion", "' AgentVersion ',AGENT_VERSION,agentVersion",
                "CATEGORY,CATEGORY,category"})
        void parses(String text, GroupBy expected, String label) {
            assertEquals(expected, GroupBy.parse(text));
            assertEquals(label, expected.label());
        }

        @Test
        @DisplayName("null and blank mean NONE, anything else is refused with the allowed values")
        void nullBlankInvalid() {
            assertEquals(GroupBy.NONE, GroupBy.parse(null));
            assertEquals(GroupBy.NONE, GroupBy.parse("   "));
            var e = assertThrows(IllegalArgumentException.class, () -> GroupBy.parse("hour"));
            assertTrue(e.getMessage().contains("agentVersion"));
        }
    }

    @Nested
    @DisplayName("summarize")
    class Summarize {

        @Test
        @DisplayName("requests within the same minute share one cached answer; a different grouping does not")
        void cached() {
            rows.add(decision("APPROVED", NOW));
            var first = new DecisionFilter(null, null, null, NOW.minusSeconds(3600).plusSeconds(5), NOW.plusSeconds(7), null, null, null, null);
            var second = new DecisionFilter(null, null, null, NOW.minusSeconds(3600).plusSeconds(20), NOW.plusSeconds(11), null, null, null, null);

            var a = service.summarize(first, GroupBy.NONE);
            var b = service.summarize(second, GroupBy.NONE);
            service.summarize(first, GroupBy.CATEGORY);

            assertTrue(a == b, "the minute-truncated key is the same");
            verify(decisionStore, times(2)).query(any(), anyInt(), anyInt());
        }

        @Test
        @DisplayName("an open-ended filter (no from, no to) is summarised too")
        void openEnded() {
            rows.add(decision("APPROVED", NOW));
            var result = service.summarize(new DecisionFilter(null, null, null, null, null, null, null, null, null), GroupBy.NONE);
            assertEquals(1, result.buckets().get(0).total());
        }

        @Test
        @DisplayName("an empty ungrouped window still yields one 'all' bucket, with empty sample figures")
        void emptyAll() {
            var result = service.summarize(filter(null), GroupBy.NONE);

            assertEquals("none", result.groupBy());
            assertFalse(result.truncated());
            assertEquals(1, result.buckets().size());
            var bucket = result.buckets().get(0);
            assertEquals("all", bucket.key());
            assertEquals(0, bucket.total());
            assertNull(bucket.uneditedApprovalRate());
            assertNull(bucket.timeoutShare());
            assertEquals(0, bucket.samples().sampled());
            assertNull(bucket.samples().expiredShare());
        }

        @Test
        @DisplayName("with a groupId filter no sample figures are joined")
        void groupFilterHasNoSamples() {
            rows.add(decision("APPROVED", NOW));
            var result = service.summarize(filter("g1"), GroupBy.NONE);
            assertNull(result.buckets().get(0).samples());
            verify(sampleStore, never()).query(any(), anyInt(), anyInt());

            var empty = new ApprovalAnalyticsService(decisionStore, sampleStore);
            rows.clear();
            assertNull(empty.summarize(filter("g1"), GroupBy.NONE).buckets().get(0).samples());
        }

        @Test
        @DisplayName("groupings with no sample equivalent (group, channel, agent version) carry no sample figures")
        void noSampleEquivalent() {
            rows.add(row("APPROVED", "REST", null, false, false, 100L, CATEGORY, AGENT, "g1", 3, NOW));
            rows.add(row("APPROVED", null, null, false, false, 100L, CATEGORY, AGENT, null, null, NOW));

            for (GroupBy g : List.of(GroupBy.GROUP, GroupBy.CHANNEL, GroupBy.AGENT_VERSION)) {
                var result = service.summarize(filter(null), g);
                assertEquals(2, result.buckets().size(), g.name());
                result.buckets().forEach(b -> assertNull(b.samples()));
            }
            var byChannel = service.summarize(filter(null), GroupBy.CHANNEL);
            assertEquals(List.of("REST", "unknown"), byChannel.buckets().stream().map(ApprovalSummary::key).toList());
            var byVersion = service.summarize(filter(null), GroupBy.AGENT_VERSION);
            assertEquals(List.of("3", "unknown"), byVersion.buckets().stream().map(ApprovalSummary::key).toList());
            assertEquals("agentVersion", byVersion.groupBy());
        }

        @Test
        @DisplayName("sample-only buckets appear, joined by category, agent, day and week")
        void sampleOnlyBuckets() {
            Instant monday = Instant.parse("2026-11-02T10:00:00Z");
            Instant wednesday = Instant.parse("2026-11-04T10:00:00Z");
            rows.add(row("APPROVED", "REST", null, false, false, 100L, CATEGORY, AGENT, null, 3, monday));
            samples.add(sample(AGENT, CATEGORY, AuditSample.Status.OK, null, monday));
            samples.add(sample("other-agent", "tool:other", AuditSample.Status.PENDING, null, wednesday));
            samples.add(sample(null, null, AuditSample.Status.EXPIRED, null, null));

            var byCategory = service.summarize(filter(null), GroupBy.CATEGORY);
            assertEquals(List.of(CATEGORY, "tool:other", "unknown"), byCategory.buckets().stream().map(ApprovalSummary::key).toList());
            assertEquals(1, byCategory.buckets().get(0).samples().reviewed());
            assertEquals(0, byCategory.buckets().get(1).total());
            assertEquals(1, byCategory.buckets().get(1).samples().pending());

            var byAgent = service.summarize(filter(null), GroupBy.AGENT);
            assertEquals(List.of("agent-1", "other-agent", "unknown"), byAgent.buckets().stream().map(ApprovalSummary::key).toList());

            var byDay = service.summarize(filter(null), GroupBy.DAY);
            assertEquals(List.of("2026-11-02", "2026-11-04", "unknown"), byDay.buckets().stream().map(ApprovalSummary::key).toList());

            var byWeek = service.summarize(filter(null), GroupBy.WEEK);
            assertEquals(List.of("2026-11-02", "unknown"), byWeek.buckets().stream().map(ApprovalSummary::key).toList(),
                    "Monday and Wednesday share the week of Monday");
        }

        @Test
        @DisplayName("more rows than the cap are cut and the result is marked truncated")
        void truncated() {
            ApprovalDecision one = decision("APPROVED", NOW);
            List<ApprovalDecision> many = new ArrayList<>();
            for (int i = 0; i <= ApprovalAnalyticsService.MAX_ROWS; i++) {
                many.add(one);
            }
            when(decisionStore.query(any(), anyInt(), anyInt())).thenReturn(many);

            var result = service.summarize(filter(null), GroupBy.NONE);

            assertTrue(result.truncated());
            assertEquals(ApprovalAnalyticsService.MAX_ROWS, result.buckets().get(0).total());
        }
    }

    @Nested
    @DisplayName("bucket figures")
    class Figures {

        @Test
        @DisplayName("outcomes, rates, channel mix, timeouts, legacy rows and the top five reason codes")
        void bucket() {
            List<ApprovalDecision> in = new ArrayList<>();
            in.add(row("APPROVED", "MANAGER", null, false, false, 1_000L, CATEGORY, AGENT, null, 3, NOW));
            in.add(row("APPROVED", "MANAGER", null, false, false, 2_000L, CATEGORY, AGENT, null, 3, NOW));
            in.add(row("APPROVED_EDITED", null, null, false, true, null, CATEGORY, AGENT, null, 3, NOW));
            in.add(row("REJECTED", "REST", "TONE", false, false, 500L, CATEGORY, AGENT, null, 3, NOW));
            in.add(row("AUTO_APPROVED", "TIMEOUT", null, true, false, 100L, CATEGORY, AGENT, null, 3, NOW));
            in.add(row(null, "REST", "ZZ", false, false, 100L, CATEGORY, AGENT, null, 3, NOW));
            for (String code : List.of("A", "B", "C", "D", "E", "F")) {
                in.add(row("REJECTED", "REST", code, false, false, 100L, CATEGORY, AGENT, null, 3, NOW));
            }
            in.add(row("REJECTED", "REST", "A", false, false, 100L, CATEGORY, AGENT, null, 3, NOW));

            ApprovalSummary s = ApprovalAnalyticsService.summarize("k", in, null);

            assertEquals(13, s.total());
            assertEquals(11, s.humanDecisions());
            assertEquals(Math.round(2.0 / 11 * 10_000.0) / 10_000.0, s.uneditedApprovalRate());
            assertEquals(Math.round(1.0 / 11 * 10_000.0) / 10_000.0, s.editRate());
            assertEquals(1L, s.legacy());
            assertEquals(Math.round(1.0 / 13 * 10_000.0) / 10_000.0, s.timeoutShare());
            assertEquals(5, s.topReasonCodes().size());
            assertEquals("A", s.topReasonCodes().get(0).code());
            assertEquals(2, s.topReasonCodes().get(0).count());
            assertEquals(1, s.byOutcome().get("AUTO_APPROVED"));
            assertEquals(3, s.channelMix().get("MANAGER") + s.channelMix().get("UNKNOWN"));
            assertNull(s.samples());
        }

        @Test
        @DisplayName("sample statistics count each status, flagged reason codes and the expired share")
        void sampleStats() {
            List<AuditSample> in = List.of(sample(AGENT, CATEGORY, AuditSample.Status.PENDING, null, NOW),
                    sample(AGENT, CATEGORY, AuditSample.Status.OK, null, NOW), sample(AGENT, CATEGORY, AuditSample.Status.FLAGGED, "TONE", NOW),
                    sample(AGENT, CATEGORY, AuditSample.Status.FLAGGED, null, NOW), sample(AGENT, CATEGORY, AuditSample.Status.EXPIRED, null, NOW));

            var stats = ApprovalAnalyticsService.sampleStats(in);

            assertEquals(5, stats.sampled());
            assertEquals(1, stats.pending());
            assertEquals(3, stats.reviewed());
            assertEquals(2, stats.flagged());
            assertEquals(1, stats.expired());
            assertEquals(Math.round(1.0 / 4 * 10_000.0) / 10_000.0, stats.expiredShare());
            assertEquals(1, stats.flaggedReasonCodes().size());
            assertEquals("TONE", stats.flaggedReasonCodes().get(0).code());
        }

        @Test
        @DisplayName("days and weeks of an unknown time are 'unknown'; a Monday is its own week, a Sunday belongs to the week before")
        void dayWeek() {
            assertEquals("unknown", ApprovalAnalyticsService.day(null));
            assertEquals("unknown", ApprovalAnalyticsService.week(null));
            assertEquals("2026-11-02", ApprovalAnalyticsService.week(Instant.parse("2026-11-02T00:00:00Z")));
            assertEquals("2026-11-02", ApprovalAnalyticsService.week(Instant.parse("2026-11-08T23:59:59Z")));
            assertEquals("2026-11-08", ApprovalAnalyticsService.day(Instant.parse("2026-11-08T23:59:59Z")));
        }
    }

    @Nested
    @DisplayName("decisions and count")
    class Pages {

        @Test
        @DisplayName("personal fields are kept for an administrator and removed otherwise, though the decider is still named as itself")
        void personalFields() {
            rows.add(decision("APPROVED", NOW));

            ApprovalDecision full = service.decisions(filter(null), 0, 10, true).get(0);
            ApprovalDecision stripped = service.decisions(filter(null), 0, 10, false).get(0);

            assertEquals("user-1", full.userId());
            assertNull(stripped.userId());
            assertEquals("alice", stripped.decidedBy());
        }

        @Test
        @DisplayName("count delegates to the store")
        void count() {
            when(decisionStore.count(any())).thenReturn(42L);
            assertEquals(42L, service.count(filter(null)));
        }
    }
}
