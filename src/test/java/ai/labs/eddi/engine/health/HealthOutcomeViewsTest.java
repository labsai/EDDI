/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.health.HealthConfigPolicy.Resolved;
import ai.labs.eddi.engine.health.HealthPeriods.Window;
import ai.labs.eddi.engine.health.model.HealthOutcome;
import ai.labs.eddi.engine.health.model.HealthOutcomeCount;
import ai.labs.eddi.engine.health.model.HealthSessionQuery;
import ai.labs.eddi.engine.health.report.HealthReports.OutcomeSuggestion;
import ai.labs.eddi.engine.health.report.HealthReports.SessionPage;
import ai.labs.eddi.engine.security.ConversationReviewPolicy;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Session drill-down and outcome suggestions")
class HealthOutcomeViewsTest {

    static final Window WINDOW = new Window(LocalDate.of(2026, 10, 12), LocalDate.of(2026, 11, 10));
    static final Instant AT = Instant.parse("2026-11-01T10:00:00Z");

    static HealthOutcome session(String id) {
        return new HealthOutcome(id, 0, "agent", 3, "secret-user", "api", true, AT, AT.plusSeconds(60), 4, 0.02, 1, "ABANDONED", "inferred", false,
                LocalDate.of(2026, 11, 1), null, 0, List.of("x"), AT);
    }

    @Nested
    @DisplayName("HealthOutcomeViews")
    class Views {

        @Test
        @DisplayName("a page carries no user id, asks for one row more, and returns the next offset as the cursor")
        void page() {
            IHealthOutcomeStore store = mock(IHealthOutcomeStore.class);
            when(store.sessions(any())).thenReturn(List.of(session("c1"), session("c2"), session("c3")));
            HealthOutcomeViews views = new HealthOutcomeViews(store, HealthSettings.defaults());
            SessionPage page = views.sessions("agent", WINDOW, Set.of(3), List.of("api"), List.of("ABANDONED"), List.of(), 2, 10);
            ArgumentCaptor<HealthSessionQuery> query = ArgumentCaptor.forClass(HealthSessionQuery.class);
            verify(store).sessions(query.capture());
            assertEquals(3, query.getValue().limit());
            assertEquals(10, query.getValue().offset());
            assertEquals(List.of("ABANDONED"), query.getValue().outcomes());
            assertEquals(2, page.items().size());
            assertEquals("12", page.nextCursor());
            assertEquals("c1", page.items().get(0).conversationId());
            assertEquals("2026-11-01T10:01:00Z", page.items().get(0).endedAt());
            assertTrue(page.toString().contains("c1") && !page.toString().contains("secret-user"), "no user id in a row");

            when(store.sessions(any())).thenReturn(List.of(session("c1")));
            assertNull(views.sessions("agent", WINDOW, Set.of(3), List.of("api"), List.of(), List.of(), 2, 0).nextCursor());
        }

        @Test
        @DisplayName("suggestions: the unmapped, non-reserved actions that end most sessions, with a name heuristic and the share")
        void suggestions() {
            IHealthOutcomeStore store = mock(IHealthOutcomeStore.class);
            Map<String, Long> ending = new LinkedHashMap<>();
            ending.put("order_confirmed", 41L);
            ending.put("handoff_human", 20L);
            ending.put("out_of_scope", 9L);
            ending.put("smalltalk", 30L);
            ending.put("already_mapped", 50L);
            ending.put("CONVERSATION_END", 60L);
            when(store.endingActions(any())).thenReturn(ending);
            when(store.counts(any(), anyBoolean(), anyString()))
                    .thenReturn(List.of(new HealthOutcomeCount(null, null, "ASSUMED_RESOLVED", "silence", true, false, 100, 300)));
            HealthOutcomeViews views = new HealthOutcomeViews(store, HealthSettings.defaults());

            List<OutcomeSuggestion> items = views.suggestions("agent", WINDOW, null, List.of("api"), Set.of("already_mapped")).items();
            assertEquals(List.of("order_confirmed", "smalltalk", "handoff_human", "out_of_scope"), items.stream().map(OutcomeSuggestion::action)
                    .toList());
            assertEquals("RESOLVED", items.get(0).suggested());
            assertEquals(41, items.get(0).sessionsEndingWith());
            assertEquals("Last action of 41 % of engaged sessions", items.get(0).reason());
            assertNull(items.get(1).suggested());
            assertTrue(items.get(1).reason().contains("suggests no outcome"));
            assertEquals("ESCALATED", items.get(2).suggested());
            assertEquals("DEFLECTED", items.get(3).suggested());
        }

        @Test
        @DisplayName("name heuristics")
        void heuristics() {
            assertEquals("RESOLVED", HealthOutcomeViews.suggest("booking_DONE"));
            assertEquals("RESOLVED", HealthOutcomeViews.suggest("payment_completed"));
            assertEquals("RESOLVED", HealthOutcomeViews.suggest("table_booked"));
            assertEquals("ESCALATED", HealthOutcomeViews.suggest("escalate_to_agent"));
            assertEquals("ESCALATED", HealthOutcomeViews.suggest("handoff_done"), "a handoff first");
            assertEquals("ESCALATED", HealthOutcomeViews.suggest("talk_to_human"));
            assertEquals("DEFLECTED", HealthOutcomeViews.suggest("request_not_supported"));
            assertEquals("DEFLECTED", HealthOutcomeViews.suggest("outofscope"));
            assertNull(HealthOutcomeViews.suggest("greet"));
            assertNull(HealthOutcomeViews.suggest("not_done"), "negated");
            assertNull(HealthOutcomeViews.suggest("unconfirmed"), "a different word");
            assertNull(HealthOutcomeViews.suggest("humanize_reply"));
            assertNull(HealthOutcomeViews.suggest("abandonedCart"));
            assertEquals("RESOLVED", HealthOutcomeViews.suggest("orderConfirmed"), "camelCase is split");
            assertEquals("ESCALATED", HealthOutcomeViews.suggest("hand-off-to-agent"));
            assertEquals("DEFLECTED", HealthOutcomeViews.suggest("OUT_OF_SCOPE"));
        }
    }

    @Nested
    @DisplayName("HealthDrillDownAccess — who may list sessions")
    class Access {

        ConversationReviewPolicy review = mock(ConversationReviewPolicy.class);
        IAgentStore agents = mock(IAgentStore.class);
        HealthConfigPolicy configs = mock(HealthConfigPolicy.class);
        HealthDrillDownAccess access = new HealthDrillDownAccess(review, agents, configs);

        void currentVersion(int version) throws Exception {
            IResourceStore.IResourceId id = mock(IResourceStore.IResourceId.class);
            when(id.getVersion()).thenReturn(version);
            when(agents.getCurrentResourceId("agent")).thenReturn(id);
        }

        Optional<AgentConfiguration.ConversationReview> on() {
            AgentConfiguration.ConversationReview r = new AgentConfiguration.ConversationReview();
            r.setEnabled(true);
            return Optional.of(r);
        }

        String reason(ForbiddenException e) {
            return String.valueOf(((Map<?, ?>) e.getResponse().getEntity()).get("reason"));
        }

        @Test
        @DisplayName("without EDIT on the agent: 403 FORBIDDEN, whatever review says")
        void notAMaintainer() {
            when(review.maintains("agent")).thenReturn(false);
            when(review.reviewOf(anyString(), any())).thenReturn(on());
            ForbiddenException e = assertThrows(ForbiddenException.class, () -> access.reviewableVersions("agent", 3));
            assertEquals(HealthDrillDownAccess.REASON_FORBIDDEN, reason(e));
            assertEquals(403, e.getResponse().getStatus());
        }

        @Test
        @DisplayName("a version without review: 403 REVIEW_DISABLED; with review: just that version")
        void namedVersion() {
            when(review.maintains("agent")).thenReturn(true);
            when(review.reviewOf("agent", 3)).thenReturn(Optional.empty());
            when(review.reviewOf("agent", 4)).thenReturn(on());
            assertEquals(HealthDrillDownAccess.REASON_REVIEW_DISABLED,
                    reason(assertThrows(ForbiddenException.class, () -> access.reviewableVersions("agent", 3))));
            assertEquals(Set.of(4), access.reviewableVersions("agent", 4));
        }

        @Test
        @DisplayName("no version: the current one must have review on; the page covers only versions with review")
        void currentVersionRule() throws Exception {
            when(review.maintains("agent")).thenReturn(true);
            currentVersion(4);
            when(review.reviewOf(anyString(), any())).thenReturn(Optional.empty());
            assertEquals(HealthDrillDownAccess.REASON_REVIEW_DISABLED,
                    reason(assertThrows(ForbiddenException.class, () -> access.reviewableVersions("agent", null))));
            when(review.reviewOf("agent", 4)).thenReturn(on());
            when(review.reviewOf("agent", 2)).thenReturn(on());
            assertEquals(Set.of(2, 4), access.reviewableVersions("agent", null));
        }

        @Test
        @DisplayName("mapped actions are the current version's")
        void mappedActions() throws Exception {
            currentVersion(5);
            when(configs.of("agent", 5)).thenReturn(new Resolved(Map.of("done", "RESOLVED"), true, Map.of(), false));
            assertEquals(Set.of("done"), access.mappedActions("agent"));
        }

        @Test
        @DisplayName("an unknown agent is 404")
        void unknownAgent() throws Exception {
            when(review.maintains("agent")).thenReturn(true);
            when(agents.getCurrentResourceId("agent")).thenThrow(new IResourceStore.ResourceNotFoundException("gone"));
            assertThrows(NotFoundException.class, () -> access.reviewableVersions("agent", null));
        }
    }
}
