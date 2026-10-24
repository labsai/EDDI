/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.alerts;

import ai.labs.eddi.engine.alerts.model.AlertCondition;
import ai.labs.eddi.engine.alerts.model.AlertEvent;
import ai.labs.eddi.engine.alerts.model.AlertEvent.Delivery;
import ai.labs.eddi.engine.alerts.model.AlertEvent.DeliveryStatus;
import ai.labs.eddi.engine.alerts.model.AlertEventFilter;
import ai.labs.eddi.engine.alerts.model.AlertMatch;
import ai.labs.eddi.engine.alerts.model.AlertRule;
import ai.labs.eddi.engine.alerts.model.AlertScope;
import ai.labs.eddi.engine.alerts.model.AlertSource;
import ai.labs.eddi.engine.alerts.model.DeliveryTarget;
import ai.labs.eddi.engine.alerts.model.ErrorKind;
import ai.labs.eddi.engine.alerts.model.MatchOp;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The behaviour both alert store pairs (MongoDB, PostgreSQL) must share — run
 * by an IT per database against a real one. The cluster guarantees live here:
 * the dedupe, the window counter, the version-conditional updates and the
 * delivery claim are each one conditional write in the database.
 */
public interface AlertStoreContract {

    IAlertRuleStore ruleStore();

    IAlertEventStore eventStore();

    String AGENT = "6aba70011a25f8be7920e055";
    String OTHER = "6aba70011a25f8be7920e099";
    Instant NOW = Instant.now().truncatedTo(ChronoUnit.MILLIS);

    private static AlertRule rule(String name, AlertScope scope, boolean enabled) {
        return new AlertRule(null, name, enabled, scope, AlertSource.RATING, new AlertCondition(0.25, true, List.of("Too slow"), null),
                List.of(DeliveryTarget.slack("C0123ABC"), DeliveryTarget.webhook("https://hooks.example.com/x", "${vault:hook}")),
                new AlertRule.Throttle(30, 5), AlertRule.DigestMode.DAILY, "alice", NOW, "bob", NOW.plusSeconds(1));
    }

    private static AlertEvent event(String agentId, String dedupeKey, AlertEvent.State state, Instant firedAt, Instant nextAttemptAt) {
        DeliveryTarget slack = DeliveryTarget.slack("C0123ABC");
        return new AlertEvent(null, "6aba70011a25f8be7920e0aa", "Unhappy users", AlertSource.RATING, AlertEvent.Kind.ALERT, agentId, 3,
                "6aba70011a25f8be7920e0cc", firedAt, "Unhappy users: conversation rating 1",
                new AlertMatch(List.of("comment"), null, "rating-1", -1, "FIVE_STAR", 1, 0.0, List.of("Too slow"), "It took forever"),
                List.of(Delivery.pending(slack)), state, nextAttemptAt, dedupeKey, 0, null, null, null, null, 0);
    }

    // ------------------------------------------------------------------ rules

    @Test
    default void ruleRoundTripsWithEveryField() {
        AlertRule created = ruleStore().create(rule("Unhappy users", AlertScope.agent(AGENT), true));
        assertNotNull(created.id());
        AlertRule read = ruleStore().read(created.id()).orElseThrow();
        assertEquals(created, read);
        assertEquals(AlertRule.DigestMode.DAILY, read.digest());
        assertEquals("${vault:hook}", read.delivery().get(1).signingSecret());

        AlertRule renamed = new AlertRule(read.id(), "Renamed", false, read.scope(), read.source(), read.condition(), List.of(), null, null,
                read.createdBy(), read.createdAt(), "carol", NOW.plusSeconds(5));
        assertTrue(ruleStore().update(renamed));
        assertEquals(renamed, ruleStore().read(read.id()).orElseThrow());
        assertTrue(ruleStore().delete(read.id()));
        assertTrue(ruleStore().read(read.id()).isEmpty());
        assertFalse(ruleStore().update(renamed));
    }

    @Test
    default void rulesWatchingAnAgentAndListing() {
        AlertRule mine = ruleStore().create(rule("mine", AlertScope.agent(AGENT), true));
        AlertRule both = ruleStore().create(rule("both", new AlertScope(false, List.of(AGENT, OTHER)), true));
        AlertRule all = ruleStore().create(rule("all", new AlertScope(true, List.of()), true));
        ruleStore().create(rule("off", AlertScope.agent(AGENT), false));
        ruleStore().create(rule("other", AlertScope.agent(OTHER), true));

        Set<String> watching = new HashSet<>();
        ruleStore().findEnabledForAgent(AGENT).forEach(r -> watching.add(r.name()));
        assertEquals(Set.of("mine", "both", "all"), watching);
        assertEquals(4, ruleStore().findEnabled().size());

        assertEquals(3, ruleStore().count(AGENT, false)); // mine, both, off
        assertEquals(2, ruleStore().count(AGENT, true)); // mine, off
        List<String> listed = ruleStore().list(AGENT, true, 0, 10).stream().map(AlertRule::name).toList();
        assertEquals(Set.of("mine", "off"), new HashSet<>(listed));
        assertEquals(5, ruleStore().count(null, false));
        assertEquals(2, ruleStore().list(null, false, 1, 2).size());
        assertNotNull(mine.id());
        assertNotNull(both.id());
        assertNotNull(all.id());
    }

    @Test
    default void gdprForgetsRuleAuthors() {
        AlertRule created = ruleStore().create(rule("x", AlertScope.agent(AGENT), true));
        assertEquals(2, ruleStore().clearAuthor("alice") + ruleStore().clearAuthor("bob"));
        AlertRule read = ruleStore().read(created.id()).orElseThrow();
        assertNull(read.createdBy());
        assertNull(read.updatedBy());
    }

    // ------------------------------------------------------------------ events

    @Test
    default void eventRoundTripsWithDeliveriesAndMatch() {
        AlertEvent stored = eventStore().insert(event(AGENT, "k1", AlertEvent.State.PENDING, NOW, NOW.plusSeconds(120))).orElseThrow();
        AlertEvent read = eventStore().read(stored.id()).orElseThrow();
        assertEquals(stored, read);
        assertEquals("It took forever", read.match().commentExcerpt());
        assertEquals(DeliveryStatus.PENDING, read.deliveries().get(0).status());
    }

    @Test
    default void dedupeKeyIsUniqueAndCountsDuplicates() {
        assertTrue(eventStore().insert(event(AGENT, "same", AlertEvent.State.PENDING, NOW, null)).isPresent());
        assertTrue(eventStore().insert(event(AGENT, "same", AlertEvent.State.PENDING, NOW, null)).isEmpty());
        assertTrue(eventStore().insert(event(AGENT, "same", AlertEvent.State.PENDING, NOW, null)).isEmpty());
        assertEquals(2, eventStore().list(AlertEventFilter.all(), 0, 10).get(0).duplicates());
        // No key, no dedupe.
        assertTrue(eventStore().insert(event(AGENT, null, AlertEvent.State.INBOX_ONLY, NOW, null)).isPresent());
        assertTrue(eventStore().insert(event(AGENT, null, AlertEvent.State.INBOX_ONLY, NOW, null)).isPresent());
        assertEquals(3, eventStore().count(AlertEventFilter.all()));
    }

    @Test
    default void windowCounterIsAtomicUnderConcurrency() throws Exception {
        Instant window = NOW.truncatedTo(ChronoUnit.HOURS);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Long>> counts = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                counts.add(pool.submit(() -> eventStore().incrementWindow("rule-a", window, window.plus(Duration.ofHours(2)))));
            }
            List<Long> values = new ArrayList<>();
            for (Future<Long> count : counts) {
                values.add(count.get());
            }
            Collections.sort(values);
            List<Long> expected = new ArrayList<>();
            for (long i = 1; i <= 40; i++) {
                expected.add(i);
            }
            assertEquals(expected, values, "every increment must see a distinct count");
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, eventStore().incrementWindow("rule-a", window.plus(Duration.ofHours(1)), window.plus(Duration.ofHours(3))));
        assertEquals(1, eventStore().incrementWindow("rule-b", window, window.plus(Duration.ofHours(2))));
    }

    @Test
    default void deliveryUpdatesAreConditionalOnTheVersion() {
        AlertEvent stored = eventStore().insert(event(AGENT, "v", AlertEvent.State.PENDING, NOW, NOW)).orElseThrow();
        AlertEvent delivered = stored.withDelivery(List.of(new Delivery(stored.deliveries().get(0).targetKey(), stored.deliveries().get(0).type(),
                "Slack C0123ABC", DeliveryStatus.DELIVERED, 1, NOW, null, null)), AlertEvent.State.DELIVERED, null);
        AlertEvent updated = eventStore().updateDelivery(delivered, stored.version()).orElseThrow();
        assertEquals(stored.version() + 1, updated.version());
        assertEquals(AlertEvent.State.DELIVERED, updated.state());
        assertNull(updated.nextAttemptAt());
        assertTrue(eventStore().updateDelivery(delivered, stored.version()).isEmpty(), "a stale version must not overwrite");
    }

    @Test
    default void dueEventsAreClaimedOnce() {
        AlertEvent due = eventStore().insert(event(AGENT, "due", AlertEvent.State.PENDING, NOW, NOW.minusSeconds(5))).orElseThrow();
        eventStore().insert(event(AGENT, "later", AlertEvent.State.PENDING, NOW, NOW.plusSeconds(600)));
        eventStore().insert(event(AGENT, "done", AlertEvent.State.DELIVERED, NOW, null));

        List<AlertEvent> claimed = eventStore().claimDue(NOW, NOW.plusSeconds(120), 10);
        assertEquals(List.of(due.id()), claimed.stream().map(AlertEvent::id).toList());
        assertEquals(NOW.plusSeconds(120), claimed.get(0).nextAttemptAt());
        assertEquals(due.version() + 1, claimed.get(0).version());
        assertTrue(eventStore().claimDue(NOW, NOW.plusSeconds(120), 10).isEmpty(), "a claimed event is not claimed again within its lease");
    }

    @Test
    default void listCountAndFilters() {
        eventStore().insert(event(AGENT, "a", AlertEvent.State.DELIVERED, NOW.minusSeconds(60), null));
        AlertEvent b = eventStore().insert(event(AGENT, "b", AlertEvent.State.FAILED, NOW, null)).orElseThrow();
        eventStore().insert(event(OTHER, "c", AlertEvent.State.DELIVERED, NOW, null));

        assertEquals(2, eventStore().count(AlertEventFilter.unacknowledged(AGENT)));
        assertEquals(b.id(), eventStore().list(AlertEventFilter.unacknowledged(AGENT), 0, 10).get(0).id(), "newest first");
        assertEquals(1, eventStore().count(new AlertEventFilter(AGENT, null, AlertSource.RATING, null, AlertEvent.State.FAILED, null, null,
                null)));
        assertEquals(2, eventStore().count(new AlertEventFilter(null, null, null, null, null, null, NOW.minusSeconds(30), NOW.plusSeconds(1))));
        assertEquals(0, eventStore().count(new AlertEventFilter(null, null, AlertSource.CONVERSATION_ERROR, null, null, null, null, null)));
    }

    @Test
    default void acknowledgement() {
        AlertEvent a = eventStore().insert(event(AGENT, "a", AlertEvent.State.DELIVERED, NOW, null)).orElseThrow();
        AlertEvent b = eventStore().insert(event(AGENT, "b", AlertEvent.State.DELIVERED, NOW, null)).orElseThrow();
        AlertEvent c = eventStore().insert(event(OTHER, "c", AlertEvent.State.DELIVERED, NOW, null)).orElseThrow();

        assertTrue(eventStore().acknowledge(a.id(), "erin", NOW));
        assertFalse(eventStore().acknowledge(a.id(), "frank", NOW), "acknowledging twice changes nothing");
        assertEquals("erin", eventStore().read(a.id()).orElseThrow().acknowledgedBy());
        assertEquals(1, eventStore().acknowledgeIds(List.of(a.id(), b.id()), "frank", NOW));
        assertEquals(1, eventStore().acknowledgeMatching(AlertEventFilter.unacknowledged(OTHER), "frank", NOW));
        assertEquals(0, eventStore().count(new AlertEventFilter(null, null, null, false, null, null, null, null)));
        assertEquals(2, eventStore().clearAcknowledger("frank"));
        assertNull(eventStore().read(c.id()).orElseThrow().acknowledgedBy());
        assertTrue(eventStore().read(c.id()).orElseThrow().acknowledged(), "forgetting who acknowledged does not reopen it");
    }

    @Test
    default void digestPendingAndMarkDigested() {
        AlertEvent older = eventStore().insert(event(AGENT, "o", AlertEvent.State.DIGEST_PENDING, NOW.minusSeconds(60), null)).orElseThrow();
        AlertEvent newer = eventStore().insert(event(AGENT, "n", AlertEvent.State.DIGEST_PENDING, NOW, null)).orElseThrow();
        AlertEvent delivered = eventStore().insert(event(AGENT, "d", AlertEvent.State.DELIVERED, NOW, null)).orElseThrow();

        List<AlertEvent> pending = eventStore().findDigestPending("6aba70011a25f8be7920e0aa", 10);
        assertEquals(List.of(older.id(), newer.id()), pending.stream().map(AlertEvent::id).toList());
        assertEquals(2, eventStore().markDigested(List.of(older.id(), newer.id(), delivered.id())));
        assertEquals(0, eventStore().markDigested(List.of(older.id())), "a second runner digests nothing");
        assertEquals(AlertEvent.State.DELIVERED, eventStore().read(delivered.id()).orElseThrow().state());
    }

    @Test
    default void retentionAndGdprRedaction() {
        AlertEvent old = eventStore().insert(event(AGENT, "old", AlertEvent.State.DELIVERED, NOW.minus(Duration.ofDays(100)), null))
                .orElseThrow();
        AlertEvent recent = eventStore().insert(event(AGENT, "new", AlertEvent.State.DELIVERED, NOW, null)).orElseThrow();
        assertEquals(1, eventStore().deleteFiredBefore(NOW.minus(Duration.ofDays(90))));
        assertTrue(eventStore().read(old.id()).isEmpty());

        assertEquals(1, eventStore().redactRatingComments(List.of("rating-1")));
        AlertEvent redacted = eventStore().read(recent.id()).orElseThrow();
        assertNull(redacted.match().commentExcerpt());
        assertEquals(List.of("Too slow"), redacted.match().tags());
        assertEquals(0, eventStore().redactRatingComments(List.of("rating-1")));
        assertEquals(Optional.empty(), eventStore().read("6aba70011a25f8be7920effff"));
        assertEquals(ErrorKind.TURN_ERROR, AlertMatch.error(ErrorKind.TURN_ERROR).errorKind());
    }

    @Test
    default void outputMatchRuleRoundTrips() {
        AlertRule rule = new AlertRule(null, "Angry", true, AlertScope.agent(AGENT), AlertSource.OUTPUT_MATCH,
                AlertCondition.outputMatch("output.json.sentiment", MatchOp.IN, List.of("angry", "frustrated"), true), List.of(), null, null,
                "alice", NOW, "alice", NOW);
        AlertRule read = ruleStore().read(ruleStore().create(rule).id()).orElseThrow();
        assertEquals(MatchOp.IN, read.condition().op());
        assertEquals(List.of("angry", "frustrated"), read.condition().value());
        assertEquals(Boolean.TRUE, read.condition().includeValue());
        AlertRule numeric = new AlertRule(null, "Score", true, AlertScope.agent(AGENT), AlertSource.OUTPUT_MATCH,
                AlertCondition.outputMatch("properties.score", MatchOp.GT, 3, null), List.of(), null, null, "alice", NOW, "alice", NOW);
        AlertRule readNumeric = ruleStore().read(ruleStore().create(numeric).id()).orElseThrow();
        assertEquals(3, ((Number) readNumeric.condition().value()).intValue());
        AlertEvent stored = eventStore().insert(new AlertEvent(null, "r", "Angry", AlertSource.OUTPUT_MATCH, AlertEvent.Kind.ALERT, AGENT, 1,
                "c", NOW, "s", AlertMatch.output("output.json.sentiment in [angry, frustrated]", "angry"), List.of(), AlertEvent.State.INBOX_ONLY,
                null, "om-1", 0, null, null, null, null, 0)).orElseThrow();
        assertEquals("angry", eventStore().read(stored.id()).orElseThrow().match().value());
    }
}
