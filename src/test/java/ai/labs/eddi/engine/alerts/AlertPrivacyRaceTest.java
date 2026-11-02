/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.alerts;

import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.engine.alerts.model.AlertCondition;
import ai.labs.eddi.engine.alerts.model.AlertEvent;
import ai.labs.eddi.engine.alerts.model.AlertMatch;
import ai.labs.eddi.engine.alerts.model.AlertRule;
import ai.labs.eddi.engine.alerts.model.AlertScope;
import ai.labs.eddi.engine.alerts.model.AlertSource;
import ai.labs.eddi.engine.alerts.model.DeliveryTarget;
import ai.labs.eddi.engine.alerts.model.MatchOp;
import ai.labs.eddi.engine.cluster.KvKeys;
import ai.labs.eddi.engine.ratings.IConversationRatingStore;
import ai.labs.eddi.engine.ratings.model.ConversationRating;
import ai.labs.eddi.engine.security.ConversationReviewPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static ai.labs.eddi.engine.alerts.AlertTestData.AGENT;
import static ai.labs.eddi.engine.alerts.AlertTestData.CONVERSATION;
import static ai.labs.eddi.engine.alerts.AlertTestData.NOW;
import static ai.labs.eddi.engine.alerts.AlertTestData.RULE_ID;
import static ai.labs.eddi.engine.alerts.AlertTestData.rating;
import static ai.labs.eddi.engine.alerts.AlertTestData.ratingRule;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Alerts — erasure races and the internal subject id")
class AlertPrivacyRaceTest {

    private static final String OWNER = "owner-user-91c2";
    private static final String EVENT_ID = "6aba70011a25f8be7920e001";

    private AlertRuleCache cache;
    private IAlertEventStore store;
    private AlertDeliveryService delivery;
    private IConversationRatingStore ratings;
    private ConversationReviewPolicy review;
    private AlertEngine engine;

    @BeforeEach
    void setUp() {
        cache = mock(AlertRuleCache.class);
        store = mock(IAlertEventStore.class);
        delivery = mock(AlertDeliveryService.class);
        ratings = mock(IConversationRatingStore.class);
        review = mock(ConversationReviewPolicy.class);
        engine = new AlertEngine(cache, store, delivery, AlertSettings.defaults(), new AlertMetrics(new SimpleMeterRegistry()), review, ratings,
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(store.insert(any())).thenAnswer(inv -> Optional.of(((AlertEvent) inv.getArgument(0)).withId(EVENT_ID)));
        when(store.updateDelivery(any(), anyLong())).thenAnswer(inv -> Optional.of(inv.getArgument(0)));
        when(store.incrementWindow(any(), any(), any())).thenReturn(1L);
        when(delivery.deliver(any(), any())).thenAnswer(inv -> Optional.of(inv.getArgument(0)));
    }

    @Nested
    @DisplayName("a rating alert still queued when the rating is erased")
    class QueuedRating {

        private final ConversationRating low = rating(1, "It kept looping on my order number.", List.of());
        private final AlertRule rule = ratingRule(0.5, null, null, DeliveryTarget.slack("C0123ABC"));

        @BeforeEach
        void rules() {
            when(cache.rulesFor(AGENT)).thenReturn(List.of(rule));
        }

        @Test
        @DisplayName("erased before the work runs: nothing is evaluated or written")
        void erasedBefore() {
            when(ratings.readById(low.id())).thenReturn(Optional.empty());
            engine.evaluateRating(low);
            verify(store, never()).insert(any());
            verify(delivery, never()).deliver(any(), any());
        }

        @Test
        @DisplayName("changed since it was queued: the newer save fires its own alert, this one is skipped")
        void changedBefore() {
            when(ratings.readById(low.id())).thenReturn(Optional.of(new ConversationRating(low.id(), low.conversationId(), low.stepIndex(),
                    low.agentId(), low.agentVersion(), low.environment(), low.channel(), low.userId(), low.scale(), 5, 1.0, low.tags(), null,
                    low.createdAt(), NOW.plusSeconds(5), null, null, null, null)));
            engine.evaluateRating(low);
            verify(store, never()).insert(any());
        }

        @Test
        @DisplayName("erased between the check and the insert: the excerpt is blanked before anything is delivered")
        void erasedBetweenCheckAndInsert() {
            // Present for the check before evaluation, gone when re-read after the insert.
            when(ratings.readById(low.id())).thenReturn(Optional.of(low), Optional.empty());
            engine.evaluateRating(low);

            var order = inOrder(store, delivery);
            order.verify(store).insert(any());
            order.verify(store).redactRatingComment(eq(EVENT_ID), any());
            ArgumentCaptor<AlertEvent> delivered = ArgumentCaptor.forClass(AlertEvent.class);
            order.verify(delivery).deliver(delivered.capture(), eq(rule));
            assertNull(delivered.getValue().match().commentExcerpt(), "the erased comment never reaches Slack or a webhook");
            assertEquals(0.0, delivered.getValue().match().normalizedScore());
        }

        @Test
        @DisplayName("still there after the insert: the excerpt stays and nothing is redacted")
        void stillThere() {
            when(ratings.readById(low.id())).thenReturn(Optional.of(low));
            engine.evaluateRating(low);
            verify(store, never()).redactRatingComment(any(), any());
            ArgumentCaptor<AlertEvent> delivered = ArgumentCaptor.forClass(AlertEvent.class);
            verify(delivery).deliver(delivered.capture(), eq(rule));
            assertEquals("It kept looping on my order number.", delivered.getValue().match().commentExcerpt());
        }
    }

    @Nested
    @DisplayName("the subject of a stored OUTPUT_MATCH value")
    class Subject {

        private AlertRule outputRule(Boolean includeValue) {
            return new AlertRule(RULE_ID, "Escalations", true, AlertScope.agent(AGENT), AlertSource.OUTPUT_MATCH,
                    AlertCondition.outputMatch("properties.note", MatchOp.EXISTS, null, includeValue),
                    List.of(DeliveryTarget.webhook("https://hooks.example.com/x", "${vault:k}")), null, null, null, NOW, null, NOW);
        }

        private AlertEvent insertedFor(Boolean includeValue, boolean reviewOn) {
            when(cache.rulesFor(AGENT)).thenReturn(List.of(outputRule(includeValue)));
            when(review.reviewOf(AGENT, 2)).thenReturn(reviewOn ? Optional.of(new AgentConfiguration.ConversationReview()) : Optional.empty());
            engine.evaluateOutput(new TurnSnapshot(CONVERSATION, AGENT, 2, Map.of("note", "call me at once"), null, null, OWNER));
            ArgumentCaptor<AlertEvent> inserted = ArgumentCaptor.forClass(AlertEvent.class);
            verify(store).insert(inserted.capture());
            return inserted.getValue();
        }

        @Test
        @DisplayName("is recorded only with a stored value")
        void onlyWithValue() {
            AlertEvent stored = insertedFor(true, true);
            assertEquals("call me at once", stored.match().value());
            assertEquals(OWNER, stored.subjectUserId());
        }

        @Test
        @DisplayName("is never recorded when no value is stored (no includeValue, or no conversation review)")
        void neverWithoutValue() {
            assertNull(insertedFor(false, true).subjectUserId());
        }

        @Test
        @DisplayName("is never recorded without conversation review")
        void neverWithoutReview() {
            AlertEvent stored = insertedFor(true, false);
            assertNull(stored.match().value());
            assertNull(stored.subjectUserId());
        }

        @Test
        @DisplayName("never appears in the serialized REST/MCP row, a webhook payload or a Slack message")
        void neverLeaves() throws Exception {
            AlertEvent stored = insertedFor(true, true).withId(EVENT_ID);
            ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
            String row = json.writeValueAsString(stored);
            assertFalse(row.contains(OWNER), row);
            assertFalse(row.contains("subjectUserId"), row);
            assertFalse(row.contains("digestEventIds"), row);

            String webhook = AlertJson.write(AlertPayloads.webhook(stored, Optional.of("https://eddi.example.com/manage")));
            assertFalse(webhook.contains(OWNER), webhook);
            String slack = AlertPayloads.slackText(stored, Optional.of("https://eddi.example.com/manage"));
            assertFalse(slack.contains(OWNER), slack);
        }
    }

    @Nested
    @DisplayName("the erasure fence for queued work")
    class Fence {

        private AlertErasureFence fence;
        private Instant clockNow;

        private AlertRule outputRule() {
            return new AlertRule(RULE_ID, "Escalations", true, AlertScope.agent(AGENT), AlertSource.OUTPUT_MATCH,
                    AlertCondition.outputMatch("properties.note", MatchOp.EXISTS, null, true),
                    List.of(DeliveryTarget.webhook("https://hooks.example.com/x", "${vault:k}")), null, null, null, NOW, null, NOW);
        }

        @BeforeEach
        void fenced() {
            clockNow = NOW;
            fence = new AlertErasureFence(new Clock() {
                @Override
                public ZoneOffset getZone() {
                    return ZoneOffset.UTC;
                }

                @Override
                public Clock withZone(ZoneId zone) {
                    return this;
                }

                @Override
                public Instant instant() {
                    return clockNow;
                }
            });
            engine = new AlertEngine(cache, store, delivery, AlertSettings.defaults(), new AlertMetrics(new SimpleMeterRegistry()), review,
                    ratings, fence, Clock.fixed(NOW, ZoneOffset.UTC));
            when(cache.rulesFor(AGENT)).thenReturn(List.of(outputRule()));
            when(review.reviewOf(AGENT, 2)).thenReturn(Optional.of(new AgentConfiguration.ConversationReview()));
        }

        private TurnSnapshot capturedAt(Instant at) {
            return new TurnSnapshot(CONVERSATION, AGENT, 2, Map.of("note", "call me at once"), null, null, OWNER, at);
        }

        private AlertEvent inserted() {
            ArgumentCaptor<AlertEvent> inserted = ArgumentCaptor.forClass(AlertEvent.class);
            verify(store).insert(inserted.capture());
            return inserted.getValue();
        }

        @Test
        @DisplayName("captured before an erasure started: the alert fires, with no value and no subject")
        void capturedBeforeFence() {
            fence.stopInFlightWork(OWNER);
            engine.evaluateOutput(capturedAt(NOW.minusSeconds(5)));
            AlertEvent event = inserted();
            assertNull(event.match().value());
            assertNull(event.subjectUserId());
        }

        @Test
        @DisplayName("captured after the erasure started: the value is kept (a new conversation)")
        void capturedAfterFence() {
            fence.stopInFlightWork(OWNER);
            engine.evaluateOutput(capturedAt(NOW.plusSeconds(5)));
            assertEquals("call me at once", inserted().match().value());
        }

        @Test
        @DisplayName("the fence goes up between the check and the insert: value and subject are blanked before delivery")
        void fenceBetweenCheckAndInsert() {
            doAnswer(inv -> {
                fence.stopInFlightWork(OWNER); // the erasure starts while the event is written
                return Optional.of(((AlertEvent) inv.getArgument(0)).withId(EVENT_ID));
            }).when(store).insert(any());
            engine.evaluateOutput(capturedAt(NOW.minusSeconds(5)));

            var order = inOrder(store, delivery);
            order.verify(store).insert(any());
            order.verify(store).redactOutputValues(eq(OWNER), any());
            ArgumentCaptor<AlertEvent> delivered = ArgumentCaptor.forClass(AlertEvent.class);
            order.verify(delivery).deliver(delivered.capture(), any());
            assertNull(delivered.getValue().match().value());
            assertNull(delivered.getValue().subjectUserId());
        }

        @Test
        @DisplayName("a fence expires: after its lifetime, content captured before it is stored again")
        void expiry() {
            fence.stopInFlightWork(OWNER);
            assertTrue(fence.covers(OWNER, NOW.minusSeconds(1)));
            clockNow = NOW.plus(AlertErasureFence.FENCE_TTL);
            assertFalse(fence.covers(OWNER, NOW.minusSeconds(1)));
            assertFalse(fence.covers("someone-else", NOW.minusSeconds(1)));
        }

        @Test
        @DisplayName("a fence set through the cluster broadcast (by hash) covers the same user")
        void byHash() {
            fence.stopInFlightWorkByHash(KvKeys.sha256(OWNER));
            assertTrue(fence.covers(OWNER, NOW));
            assertFalse(fence.covers(OWNER, NOW.plusSeconds(1)));
        }

        @Test
        @DisplayName("a queued rating of a fenced user is not evaluated")
        void ratingFenced() {
            ConversationRating low = rating(1, "It kept looping.", List.of());
            when(cache.rulesFor(AGENT)).thenReturn(List.of(ratingRule(0.5, null, null, DeliveryTarget.slack("C0123ABC"))));
            when(ratings.readById(low.id())).thenReturn(Optional.of(low));
            fence.stopInFlightWork(low.userId());
            engine.evaluateRating(low);
            verify(store, never()).insert(any());
        }
    }

    @Test
    @DisplayName("a digest's covered ids are internal too")
    void digestIdsNotSerialized() throws Exception {
        AlertEvent digest = new AlertEvent("d1", RULE_ID, "x", AlertSource.RATING, AlertEvent.Kind.DIGEST, AGENT, null, null, NOW, "s",
                new AlertMatch(List.of(), null, null, null, null, null, null, null, null), List.of(), AlertEvent.State.INBOX_ONLY, null, "k", 0,
                2, List.of("a", "b"), null, null, 0, null, List.of("h0", "h1"));
        String row = new ObjectMapper().registerModule(new JavaTimeModule()).writeValueAsString(digest);
        assertFalse(row.contains("h0"), row);
    }
}
