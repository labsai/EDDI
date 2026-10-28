/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.alerts;

import ai.labs.eddi.engine.alerts.model.AlertEvent;
import ai.labs.eddi.engine.alerts.model.AlertEvent.Delivery;
import ai.labs.eddi.engine.alerts.model.AlertEvent.DeliveryStatus;
import ai.labs.eddi.engine.alerts.model.AlertMatch;
import ai.labs.eddi.engine.alerts.model.AlertRule;
import ai.labs.eddi.engine.alerts.model.AlertSource;
import ai.labs.eddi.engine.alerts.model.DeliveryTarget;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static ai.labs.eddi.engine.alerts.AlertTestData.AGENT;
import static ai.labs.eddi.engine.alerts.AlertTestData.CONVERSATION;
import static ai.labs.eddi.engine.alerts.AlertTestData.NOW;
import static ai.labs.eddi.engine.alerts.AlertTestData.RULE_ID;
import static ai.labs.eddi.engine.alerts.AlertTestData.ratingRule;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The send claim against GDPR erasure: no send starts with erased content once
 * an erasure has completed. Ordering is driven by latches, never by sleeps.
 */
@DisplayName("Alert send claim — delivery against erasure")
class AlertSendClaimTest {

    private static final String EVENT_ID = "6aba70011a25f8be7920e001";
    private static final String RATING_ID = "6aba70011a25f8be7920e0dd";
    private static final String COMMENT = "It kept looping on my order number.";
    private static final DeliveryTarget SLACK = DeliveryTarget.slack("C0123ABC");

    /**
     * One stored event, with the claim and redaction semantics of the real stores.
     */
    private static final class OneEventStore {
        final AtomicReference<AlertEvent> event = new AtomicReference<>();
        String claimToken;
        Instant claimUntil;
        final CountDownLatch redactionAttempts = new CountDownLatch(2);
        final List<String> claimsRefused = new ArrayList<>();
        Runnable onRead = () -> {
        };

        synchronized boolean claim(String token, Instant now, Instant until) {
            if (claimUntil != null && claimUntil.isAfter(now)) {
                claimsRefused.add(token);
                return false;
            }
            claimToken = token;
            claimUntil = until;
            return true;
        }

        synchronized boolean release(String token) {
            if (!token.equals(claimToken)) {
                return false;
            }
            claimToken = null;
            claimUntil = null;
            return true;
        }

        synchronized long redactComments(Instant now) {
            redactionAttempts.countDown();
            AlertEvent e = event.get();
            if (e == null || e.match() == null || e.match().commentExcerpt() == null || (claimUntil != null && claimUntil.isAfter(now))) {
                return 0;
            }
            AlertMatch m = e.match();
            event.set(withMatch(e, new AlertMatch(m.matched(), m.errorKind(), m.ratingId(), m.stepIndex(), m.scale(), m.score(),
                    m.normalizedScore(), m.tags(), null, m.value())));
            return 1;
        }

        synchronized long countComments() {
            AlertEvent e = event.get();
            return e != null && e.match() != null && e.match().commentExcerpt() != null ? 1 : 0;
        }

        IAlertEventStore asStore() {
            IAlertEventStore store = mock(IAlertEventStore.class);
            doAnswer(inv -> claim(inv.getArgument(1), inv.getArgument(2), inv.getArgument(3))).when(store).claimSend(any(), any(), any(), any());
            doAnswer(inv -> release(inv.getArgument(1))).when(store).releaseSend(any(), any());
            doAnswer(inv -> {
                onRead.run();
                return Optional.ofNullable(event.get());
            }).when(store).read(any());
            doAnswer(inv -> redactComments(inv.getArgument(1))).when(store).redactRatingComments(any(), any());
            doAnswer(inv -> countComments()).when(store).countRatingComments(any());
            doAnswer(inv -> {
                AlertEvent updated = inv.getArgument(0);
                AlertEvent current = event.get();
                // Delivery updates touch deliveries and state only, never the content.
                AlertEvent merged = current.withDelivery(updated.deliveries(), updated.state(), updated.nextAttemptAt());
                event.set(merged);
                return Optional.of(merged);
            }).when(store).updateDelivery(any(), anyLong());
            return store;
        }
    }

    /** A clock the test moves. */
    private static final class MovableClock extends Clock {
        volatile Instant now = NOW;

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static AlertEvent withMatch(AlertEvent e, AlertMatch match) {
        return new AlertEvent(e.id(), e.ruleId(), e.ruleName(), e.source(), e.kind(), e.agentId(), e.agentVersion(), e.conversationId(),
                e.firedAt(), e.summary(), match, e.deliveries(), e.state(), e.nextAttemptAt(), e.dedupeKey(), e.duplicates(), e.digestCount(),
                e.digestItems(), e.acknowledgedBy(), e.acknowledgedAt(), e.version());
    }

    private static AlertEvent pendingRatingEvent() {
        AlertMatch match = new AlertMatch(List.of("comment"), null, RATING_ID, -1, "FIVE_STAR", 1, 0.0, null, COMMENT);
        return new AlertEvent(EVENT_ID, RULE_ID, "Unhappy users", AlertSource.RATING, AlertEvent.Kind.ALERT, AGENT, 3, CONVERSATION, NOW, "s",
                match, List.of(Delivery.pending(SLACK)), AlertEvent.State.PENDING, NOW, "k", 0, null, null, null, null, 0);
    }

    private final AlertRule rule = ratingRule(0.5, null, null, SLACK);
    private OneEventStore stored;
    private IAlertEventStore store;
    private MovableClock clock;
    private AlertSenders senders;
    private AlertDeliveryService delivery;
    private AlertErasureRedactor redactor;
    private ExecutorService threads;
    private final List<AlertEvent> sent = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        stored = new OneEventStore();
        stored.event.set(pendingRatingEvent());
        store = stored.asStore();
        clock = new MovableClock();
        senders = mock(AlertSenders.class);
        doAnswer(inv -> {
            synchronized (sent) {
                sent.add(inv.getArgument(0));
            }
            return null;
        }).when(senders).send(any(), any());
        delivery = new AlertDeliveryService(store, senders, AlertSettings.defaults(), new AlertMetrics(new SimpleMeterRegistry()), clock);
        // The erasure's pause between passes only yields; ordering comes from the
        // latches.
        redactor = new AlertErasureRedactor(store, clock, duration -> Thread.sleep(1));
        threads = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void tearDown() {
        threads.shutdownNow();
    }

    @Test
    @DisplayName("a send in progress holds the erasure until it is done; the next delivery sends the redacted event")
    void erasureWaitsForSendInProgress() throws Exception {
        CountDownLatch sending = new CountDownLatch(1);
        CountDownLatch finishSend = new CountDownLatch(1);
        doAnswer(inv -> {
            synchronized (sent) {
                sent.add(inv.getArgument(0));
            }
            sending.countDown();
            assertTrue(finishSend.await(10, TimeUnit.SECONDS));
            return null;
        }).when(senders).send(any(), any());

        Future<?> deliveryRun = threads.submit(() -> delivery.deliver(stored.event.get(), rule));
        assertTrue(sending.await(10, TimeUnit.SECONDS), "the send has started, after the final read");
        assertEquals(COMMENT, sent.get(0).match().commentExcerpt(), "read before the erasure: the send carries what was stored then");

        CompletableFuture<Long> erasure = CompletableFuture.supplyAsync(() -> redactor.redactRatingComments(List.of(RATING_ID)), threads);
        assertTrue(stored.redactionAttempts.await(10, TimeUnit.SECONDS), "the erasure has tried at least twice");
        assertFalse(erasure.isDone(), "the erasure has not completed while the send is in progress");
        assertEquals(COMMENT, stored.event.get().match().commentExcerpt(), "the claimed event is left alone");

        finishSend.countDown();
        deliveryRun.get(10, TimeUnit.SECONDS);
        assertEquals(1L, erasure.get(10, TimeUnit.SECONDS));
        assertNull(stored.event.get().match().commentExcerpt());

        // A retry after the erasure sends the redacted event.
        doAnswer(inv -> {
            synchronized (sent) {
                sent.add(inv.getArgument(0));
            }
            return null;
        }).when(senders).send(any(), any());
        AlertEvent retry = stored.event.get().withDelivery(List.of(Delivery.pending(SLACK)), AlertEvent.State.PENDING, NOW);
        stored.event.set(retry);
        delivery.deliver(retry, rule);
        assertNull(sent.get(sent.size() - 1).match().commentExcerpt());
    }

    @Test
    @DisplayName("erasure first: a delivery afterwards claims, reads and sends the redacted event")
    void erasureFirst() throws Exception {
        assertEquals(1L, redactor.redactRatingComments(List.of(RATING_ID)));
        delivery.deliver(pendingRatingEvent(), rule);
        assertEquals(1, sent.size());
        assertNull(sent.get(0).match().commentExcerpt());
    }

    @Test
    @DisplayName("a live claim of another node: this attempt skips without sending and is due again after one lease")
    void liveClaimSkips() throws Exception {
        assertTrue(stored.claim("other-node", NOW, NOW.plus(AlertDeliveryService.SEND_CLAIM_LEASE)));
        AlertEvent after = delivery.deliver(pendingRatingEvent(), rule).orElseThrow();
        verify(senders, never()).send(any(), any());
        Delivery d = after.deliveries().get(0);
        assertEquals(DeliveryStatus.PENDING, d.status());
        assertEquals(0, d.attempts());
        assertEquals(NOW.plus(AlertDeliveryService.SEND_CLAIM_LEASE), d.nextAttemptAt());
        assertEquals("other-node", stored.claimToken, "someone else's claim is not released");
    }

    @Test
    @DisplayName("an expired claim (a node that died mid-send) is taken over")
    void expiredClaimTakenOver() throws Exception {
        assertTrue(stored.claim("dead-node", NOW.minusSeconds(60), NOW.minusSeconds(1)));
        delivery.deliver(pendingRatingEvent(), rule);
        assertEquals(1, sent.size());
        assertNull(stored.claimToken, "released after the send");
    }

    @Test
    @DisplayName("too little lease left after the read: the claim is released and nothing is sent")
    void leaseTooShort() throws Exception {
        // The read after the claim (the second read; the first is the existence check)
        // takes so long that no more than the send timeout is left of the lease.
        int[] reads = {0};
        stored.onRead = () -> {
            if (++reads[0] == 2) {
                clock.now = NOW.plus(AlertDeliveryService.SEND_CLAIM_LEASE).minus(AlertSettings.defaults().webhookTimeout());
            }
        };
        AlertEvent after = delivery.deliver(pendingRatingEvent(), rule).orElseThrow();
        verify(senders, never()).send(any(), any());
        assertNull(stored.claimToken, "released");
        assertEquals(0, after.deliveries().get(0).attempts());
    }

    @Test
    @DisplayName("the erasure gives up only after its bound, with the step failed")
    void erasureBound() {
        assertTrue(stored.claim("stuck", NOW, NOW.plus(Duration.ofDays(1))));
        AlertErasureRedactor bounded = new AlertErasureRedactor(store, clock, duration -> clock.now = clock.now.plus(Duration.ofMinutes(1)));
        Collection<String> ids = List.of(RATING_ID);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> bounded.redactRatingComments(ids));
        assertTrue(e.getMessage().contains("still held"));
    }
}
