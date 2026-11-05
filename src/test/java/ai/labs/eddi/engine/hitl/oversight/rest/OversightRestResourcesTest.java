/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.oversight.rest;

import ai.labs.eddi.engine.hitl.oversight.analytics.ApprovalAnalyticsService;
import ai.labs.eddi.engine.hitl.oversight.analytics.ApprovalBackfillService;
import ai.labs.eddi.engine.hitl.oversight.analytics.ApprovalDecision;
import ai.labs.eddi.engine.hitl.oversight.analytics.ApprovalSummary;
import ai.labs.eddi.engine.hitl.oversight.analytics.DecisionFilter;
import ai.labs.eddi.engine.hitl.oversight.autonomy.AutonomyService;
import ai.labs.eddi.engine.hitl.oversight.autonomy.AutonomyState;
import ai.labs.eddi.engine.hitl.oversight.samples.AuditSample;
import ai.labs.eddi.engine.hitl.oversight.samples.AuditSampleService;
import ai.labs.eddi.engine.hitl.oversight.samples.SampleFilter;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static ai.labs.eddi.engine.hitl.oversight.OversightFixtures.AGENT;
import static ai.labs.eddi.engine.hitl.oversight.OversightFixtures.CATEGORY;
import static ai.labs.eddi.engine.hitl.oversight.OversightFixtures.NOW;
import static ai.labs.eddi.engine.hitl.oversight.OversightFixtures.decision;
import static ai.labs.eddi.engine.hitl.oversight.OversightFixtures.sample;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("oversight REST resources")
class OversightRestResourcesTest {

    private OversightAccess access;

    @BeforeEach
    void setUp() {
        access = mock(OversightAccess.class);
        when(access.requireReadScope(any())).thenAnswer(inv -> inv.getArgument(0));
        when(access.actor()).thenReturn("carol");
    }

    private static String code(WebApplicationException e) {
        return String.valueOf(((Map<?, ?>) e.getResponse().getEntity()).get("error"));
    }

    @Nested
    @DisplayName("analytics")
    class Analytics {

        private ApprovalAnalyticsService analytics;
        private ApprovalBackfillService backfill;
        private RestApprovalAnalytics rest;

        @BeforeEach
        void setUp() {
            analytics = mock(ApprovalAnalyticsService.class);
            backfill = mock(ApprovalBackfillService.class);
            rest = new RestApprovalAnalytics();
            rest.analytics = analytics;
            rest.backfill = backfill;
            rest.access = access;
            rest.clock = Clock.fixed(NOW, ZoneOffset.UTC);
        }

        @Test
        @DisplayName("summarize normalises the filters, defaults the window to 30 days and delegates with the parsed grouping")
        void summarize() {
            var result = new ApprovalSummary.Result("category", false, List.of());
            when(analytics.summarize(any(), any())).thenReturn(result);
            var query = new IRestApprovalAnalytics.Query();
            query.agentId = AGENT;
            query.category = " " + CATEGORY + " ";
            query.surface = "REGULAR";
            query.pauseType = "tool_call";
            query.channel = "manager";
            query.agentVersion = 3;

            assertSame(result, rest.summarize(query, "category"));

            var filter = ArgumentCaptor.forClass(DecisionFilter.class);
            verify(analytics).summarize(filter.capture(), eq(ApprovalAnalyticsService.GroupBy.CATEGORY));
            DecisionFilter f = filter.getValue();
            assertEquals(AGENT, f.agentId());
            assertEquals(CATEGORY, f.category());
            assertEquals("regular", f.surface());
            assertEquals("TOOL_CALL", f.pauseType());
            assertEquals("MANAGER", f.channel());
            assertEquals(3, f.agentVersion());
            assertEquals(NOW, f.to());
            assertEquals(NOW.minusSeconds(30L * 86_400), f.from());
        }

        @Test
        @DisplayName("a missing query and a missing grouping mean everything, ungrouped")
        void summarizeDefaults() {
            rest.summarize(null, null);
            verify(analytics).summarize(any(), eq(ApprovalAnalyticsService.GroupBy.NONE));
        }

        @Test
        @DisplayName("an unknown grouping is a 400")
        void badGrouping() {
            var e = assertThrows(WebApplicationException.class, () -> rest.summarize(new IRestApprovalAnalytics.Query(), "hour"));
            assertEquals(400, e.getResponse().getStatus());
            assertEquals("INVALID_PARAMETER", code(e));
        }

        @Test
        @DisplayName("group analytics without an agent need a reviewer or administrator; with an agent the agent scope applies")
        void groupScope() {
            var query = new IRestApprovalAnalytics.Query();
            query.groupId = "g1";

            when(access.seesAll()).thenReturn(false);
            var e = assertThrows(WebApplicationException.class, () -> rest.summarize(query, null));
            assertEquals(403, e.getResponse().getStatus());
            verify(analytics, never()).summarize(any(), any());

            when(access.seesAll()).thenReturn(true);
            rest.summarize(query, null);
            var filter = ArgumentCaptor.forClass(DecisionFilter.class);
            verify(analytics).summarize(filter.capture(), any());
            assertNull(filter.getValue().agentId());
            assertEquals("g1", filter.getValue().groupId());

            query.agentId = AGENT;
            when(access.seesAll()).thenReturn(false);
            rest.summarize(query, null);
            verify(access).requireReadScope(AGENT);
        }

        @Test
        @DisplayName("a window that is backwards or longer than a year is refused")
        void badWindow() {
            var backwards = new IRestApprovalAnalytics.Query();
            backwards.from = "2026-11-02";
            backwards.to = "2026-10-01";
            assertEquals("INVALID_PARAMETER", code(assertThrows(WebApplicationException.class, () -> rest.summarize(backwards, null))));

            var tooLong = new IRestApprovalAnalytics.Query();
            tooLong.from = "2024-01-01";
            tooLong.to = "2026-01-01";
            assertEquals("WINDOW_TOO_LONG", code(assertThrows(WebApplicationException.class, () -> rest.summarize(tooLong, null))));
        }

        @Test
        @DisplayName("listing defaults to the first page of 20, clamps the page size, and shows user ids only to an administrator")
        void listDecisions() {
            ApprovalDecision row = decision("APPROVED", NOW);
            when(analytics.count(any())).thenReturn(7L);
            when(analytics.decisions(any(), anyInt(), anyInt(), anyBoolean())).thenReturn(List.of(row));
            when(access.isAdmin()).thenReturn(true);

            var page = rest.listDecisions(new IRestApprovalAnalytics.Query(), null, null);
            assertEquals(7, page.total());
            assertEquals(0, page.index());
            assertEquals(20, page.limit());
            assertEquals(List.of(row), page.rows());
            verify(analytics).decisions(any(), eq(0), eq(20), eq(true));

            assertEquals(1, rest.listDecisions(null, 4, 0).limit());
            assertEquals(100, rest.listDecisions(null, 4, 5000).limit());
            assertEquals(4, rest.listDecisions(null, 4, 5).index());
        }

        @Test
        @DisplayName("a negative index is a 400")
        void negativeIndex() {
            var e = assertThrows(WebApplicationException.class, () -> rest.listDecisions(null, -1, 10));
            assertEquals("INVALID_PARAMETER", code(e));
        }

        @Test
        @DisplayName("a rebuild already running here is reported, not started")
        void rebuildAlreadyRunning() {
            when(backfill.isRunning()).thenReturn(true);

            var status = rest.rebuild("2026-10-01", "2026-10-31");

            assertFalse(status.started());
            assertTrue(status.alreadyRunning());
            assertEquals(Instant.parse("2026-10-01T00:00:00Z").toString(), status.from());
            assertEquals(Instant.parse("2026-11-01T00:00:00Z").toString(), status.to(), "a date as the end covers that whole day");
            verify(backfill, never()).rebuild(any(), any());
        }

        @Test
        @DisplayName("a rebuild starts in the background over the requested window, or the last 90 days by default")
        void rebuildStarts() {
            var status = rest.rebuild("2026-10-01T00:00:00Z", "2026-10-02T00:00:00Z");
            assertTrue(status.started());
            assertFalse(status.alreadyRunning());
            verify(backfill, timeout(5000)).rebuild(Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-10-02T00:00:00Z"));

            var defaults = rest.rebuild(null, null);
            assertEquals(NOW.toString(), defaults.to());
            assertEquals(NOW.minusSeconds(90L * 86_400).toString(), defaults.from());
            verify(backfill, timeout(5000)).rebuild(NOW.minusSeconds(90L * 86_400), NOW);
        }

        @Test
        @DisplayName("a rebuild window with a malformed date is a 400 and starts nothing")
        void rebuildBadDate() {
            var e = assertThrows(WebApplicationException.class, () -> rest.rebuild("yesterday", null));
            assertEquals("INVALID_PARAMETER", code(e));
            verify(backfill, never()).rebuild(any(), any());
        }
    }

    @Nested
    @DisplayName("autonomy")
    class Autonomy {

        private AutonomyService autonomy;
        private RestAutonomy rest;
        private final AutonomyService.CategoryView view = new AutonomyService.CategoryView(AGENT, CATEGORY, "GATED", null,
                AutonomyState.initial(AGENT, CATEGORY), null, null, null);

        @BeforeEach
        void setUp() {
            autonomy = mock(AutonomyService.class);
            when(autonomy.get(anyString(), anyString())).thenReturn(view);
            rest = new RestAutonomy();
            rest.autonomy = autonomy;
            rest.access = access;
        }

        @Test
        @DisplayName("listing is scoped by the caller's access")
        void list() {
            when(autonomy.list(AGENT, RestAutonomy.MAX_STATES)).thenReturn(List.of(view));
            assertEquals(List.of(view), rest.listStates(AGENT).categories());
            verify(access).requireReadScope(AGENT);
        }

        @Test
        @DisplayName("reading needs a reviewer and a category")
        void read() {
            assertSame(view, rest.readCategory(AGENT, CATEGORY));
            verify(access).requireReviewer(AGENT);
            assertEquals("INVALID_PARAMETER", code(assertThrows(WebApplicationException.class, () -> rest.readCategory(AGENT, "  "))));
            assertEquals("INVALID_PARAMETER", code(assertThrows(WebApplicationException.class, () -> rest.readCategory(AGENT, null))));
        }

        @Test
        @DisplayName("apply needs an editor, a recommendation id and the reviewed agent version, and answers with the category as it now is")
        void apply() {
            assertSame(view, rest.apply(AGENT, CATEGORY, new IRestAutonomy.ApplyRequest("rec-1", 3)));
            verify(access).requireEditor(AGENT);
            verify(autonomy).apply(AGENT, CATEGORY, "rec-1", 3, "carol");

            for (IRestAutonomy.ApplyRequest bad : new IRestAutonomy.ApplyRequest[]{null, new IRestAutonomy.ApplyRequest(null, 3),
                    new IRestAutonomy.ApplyRequest("rec-1", null)}) {
                var e = assertThrows(WebApplicationException.class, () -> rest.apply(AGENT, CATEGORY, bad));
                assertEquals("INVALID_PARAMETER", code(e));
            }
        }

        @Test
        @DisplayName("dismiss, suspend and lift pass the note and the actor on, and tolerate a missing body")
        void notes() {
            rest.dismiss(AGENT, CATEGORY, new IRestAutonomy.DismissRequest("rec-1", "not yet"));
            verify(autonomy).dismiss(AGENT, CATEGORY, "rec-1", "not yet", "carol");
            rest.dismiss(AGENT, CATEGORY, null);
            verify(autonomy).dismiss(AGENT, CATEGORY, null, null, "carol");

            rest.suspend(AGENT, CATEGORY, new IRestAutonomy.NoteRequest("looks wrong"));
            verify(autonomy).suspendManually(AGENT, CATEGORY, "looks wrong", "carol");
            rest.suspend(AGENT, CATEGORY, null);
            verify(autonomy).suspendManually(AGENT, CATEGORY, null, "carol");

            rest.lift(AGENT, CATEGORY, new IRestAutonomy.NoteRequest("fine"));
            verify(autonomy).lift(AGENT, CATEGORY, "fine", "carol");
            rest.lift(AGENT, CATEGORY, null);
            verify(autonomy).lift(AGENT, CATEGORY, null, "carol");

            verify(access, times(2)).requireReviewer(AGENT);
        }

        @Test
        @DisplayName("every change needs a category")
        void categoryRequired() {
            assertEquals("INVALID_PARAMETER",
                    code(assertThrows(WebApplicationException.class, () -> rest.dismiss(AGENT, " ", new IRestAutonomy.DismissRequest("r", "n")))));
            assertEquals("INVALID_PARAMETER", code(assertThrows(WebApplicationException.class, () -> rest.suspend(AGENT, null, null))));
            assertEquals("INVALID_PARAMETER", code(assertThrows(WebApplicationException.class, () -> rest.lift(AGENT, "", null))));
        }
    }

    @Nested
    @DisplayName("audit samples")
    class Samples {

        private AuditSampleService service;
        private RestAuditSamples rest;

        @BeforeEach
        void setUp() {
            service = mock(AuditSampleService.class);
            rest = new RestAuditSamples();
            rest.samples = service;
            rest.access = access;
        }

        @Test
        @DisplayName("status defaults to PENDING, ALL means every status, anything else must be a status")
        void status() {
            assertEquals(AuditSample.Status.PENDING, RestAuditSamples.status(null));
            assertEquals(AuditSample.Status.PENDING, RestAuditSamples.status("  "));
            assertNull(RestAuditSamples.status("all"));
            assertEquals(AuditSample.Status.FLAGGED, RestAuditSamples.status(" flagged "));
            assertEquals("INVALID_PARAMETER", code(assertThrows(WebApplicationException.class, () -> RestAuditSamples.status("nonsense"))));
        }

        @Test
        @DisplayName("listing scopes, filters and pages, and hides user ids from everyone but an administrator")
        void list() {
            AuditSample row = sample("s1");
            when(service.count(any())).thenReturn(9L);
            when(service.list(any(), anyInt(), anyInt())).thenReturn(List.of(row));

            var asReviewer = rest.listSamples(AGENT, " crm ", "ok", "2026-10-01", "2026-10-02", 2, 500);
            assertEquals(9, asReviewer.total());
            assertEquals(2, asReviewer.index());
            assertEquals(100, asReviewer.limit());
            assertNull(asReviewer.rows().get(0).userId());
            var filter = ArgumentCaptor.forClass(SampleFilter.class);
            verify(service).count(filter.capture());
            assertEquals("crm", filter.getValue().category());
            assertEquals(AuditSample.Status.OK, filter.getValue().status());
            assertEquals(Instant.parse("2026-10-01T00:00:00Z"), filter.getValue().from());
            assertEquals(Instant.parse("2026-10-03T00:00:00Z"), filter.getValue().to());

            when(access.isAdmin()).thenReturn(true);
            var asAdmin = rest.listSamples(null, null, null, null, null, null, null);
            assertEquals("user-1", asAdmin.rows().get(0).userId());
            assertEquals(20, asAdmin.limit());
            assertEquals(1, rest.listSamples(null, null, null, null, null, 0, 0).limit());
        }

        @Test
        @DisplayName("a negative index is a 400")
        void negativeIndex() {
            assertEquals("INVALID_PARAMETER",
                    code(assertThrows(WebApplicationException.class, () -> rest.listSamples(AGENT, null, null, null, null, -1, null))));
        }

        @Test
        @DisplayName("the pending count is scoped by the caller's access")
        void pending() {
            when(service.count(any())).thenReturn(4L);
            assertEquals(4, rest.pendingCount(AGENT).pending());
            verify(access).requireReadScope(AGENT);
        }

        @Test
        @DisplayName("reading a sample needs a reviewer of its agent; an unknown sample is a 404")
        void read() {
            AuditSample row = sample("s1");
            when(service.read("s1")).thenReturn(Optional.of(row));
            when(service.reasonCodes(row)).thenReturn(List.of(Map.of("code", "TONE")));

            var detail = rest.readSample("s1");
            assertNull(detail.sample().userId());
            assertEquals(List.of(Map.of("code", "TONE")), detail.reasonCodes());
            verify(access).requireReviewer(AGENT);

            when(access.isAdmin()).thenReturn(true);
            assertEquals("user-1", rest.readSample("s1").sample().userId());

            when(service.read("ghost")).thenReturn(Optional.empty());
            var e = assertThrows(WebApplicationException.class, () -> rest.readSample("ghost"));
            assertEquals(404, e.getResponse().getStatus());
            assertEquals("SAMPLE_NOT_FOUND", code(e));
        }

        @Test
        @DisplayName("a verdict is recorded under the caller's name and the answer hides user ids unless administrator")
        void review() {
            AuditSample row = sample("s1");
            AuditSample reviewed = row.reviewed(AuditSample.Status.OK, null, null, "carol", NOW);
            var verdict = new AuditSampleService.Verdict("OK", null, null, 1L);
            when(service.read("s1")).thenReturn(Optional.of(row));
            when(service.review(row, verdict, "carol")).thenReturn(reviewed);

            assertNull(rest.reviewSample("s1", verdict).userId());
            when(access.isAdmin()).thenReturn(true);
            assertEquals("user-1", rest.reviewSample("s1", verdict).userId());
            verify(service, never()).review(any(), isNull(), anyString());
        }
    }
}
