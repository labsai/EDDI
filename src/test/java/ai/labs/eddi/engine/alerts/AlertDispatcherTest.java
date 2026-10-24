/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.alerts;

import ai.labs.eddi.engine.alerts.model.ErrorKind;
import ai.labs.eddi.engine.ratings.ConversationRatingSaved;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static ai.labs.eddi.engine.alerts.AlertTestData.AGENT;
import static ai.labs.eddi.engine.alerts.AlertTestData.CONVERSATION;
import static ai.labs.eddi.engine.alerts.AlertTestData.rating;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * The hook side of alerting: whatever the alert worker is doing — stuck on a
 * dead webhook, a slow database — the turn and the rating request that hand it
 * work are not slowed down.
 */
@DisplayName("AlertDispatcher — never blocks the caller")
class AlertDispatcherTest {

    private AlertDispatcher dispatcher;
    private final CountDownLatch release = new CountDownLatch(1);

    @AfterEach
    void tearDown() {
        release.countDown();
        if (dispatcher != null) {
            dispatcher.shutdown();
        }
    }

    private static AlertSettings settings(boolean enabled, int capacity) {
        return new AlertSettings(enabled, capacity, Duration.ofSeconds(60), 5, Duration.ofSeconds(30), Duration.ofMinutes(2),
                Duration.ofDays(90), Duration.ofSeconds(10), Optional.empty());
    }

    @Test
    @DisplayName("with the worker stuck and the queue full, 10,000 turn outcomes are handed off in well under a second and the overflow is counted")
    void neverBlocks() throws Exception {
        AlertEngine engine = mock(AlertEngine.class);
        CountDownLatch workerBusy = new CountDownLatch(1);
        doAnswer(inv -> {
            workerBusy.countDown();
            release.await(30, TimeUnit.SECONDS); // a webhook that never answers
            return null;
        }).when(engine).evaluateTurn(any());
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        dispatcher = new AlertDispatcher(engine, new AlertMetrics(registry), settings(true, 10));

        TurnOutcome outcome = new TurnOutcome(CONVERSATION, AGENT, 1, ErrorKind.TURN_ERROR);
        dispatcher.onTurnOutcome(outcome);
        assertTrue(workerBusy.await(5, TimeUnit.SECONDS));

        assertTimeoutPreemptively(Duration.ofMillis(1000), () -> {
            for (int i = 0; i < 10_000; i++) {
                dispatcher.onTurnOutcome(outcome);
            }
        });
        assertEquals(10_000 - 10, registry.counter(AlertMetrics.DROPPED, "reason", AlertDispatcher.REASON_QUEUE_FULL).count());
        assertEquals(10.0, registry.get(AlertMetrics.QUEUE_DEPTH).gauge().value());
    }

    @Test
    @DisplayName("a submit with a full queue returns false at once instead of waiting for room")
    void fullQueueRefusesAtOnce() throws Exception {
        dispatcher = new AlertDispatcher(mock(AlertEngine.class), new AlertMetrics(new SimpleMeterRegistry()), settings(true, 1));
        CountDownLatch running = new CountDownLatch(1);
        assertTrue(dispatcher.submit(() -> {
            running.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        assertTrue(running.await(5, TimeUnit.SECONDS));
        assertTrue(dispatcher.submit(() -> {
        }));
        long started = System.nanoTime();
        assertFalse(dispatcher.submit(() -> {
        }));
        assertTrue(System.nanoTime() - started < TimeUnit.MILLISECONDS.toNanos(200));
    }

    @Test
    @DisplayName("a rating is evaluated on the worker; a failure there never reaches the caller")
    void ratingsAndFailures() {
        AlertEngine engine = mock(AlertEngine.class);
        doAnswer(inv -> {
            throw new IllegalStateException("database down");
        }).when(engine).evaluateRating(any());
        dispatcher = new AlertDispatcher(engine, new AlertMetrics(new SimpleMeterRegistry()), settings(true, 10));

        dispatcher.onRatingSaved(new ConversationRatingSaved(rating(1, "bad", List.of())));

        verify(engine, timeout(5000)).evaluateRating(any());
    }

    @Test
    @DisplayName("with alerting switched off nothing is queued")
    void disabled() {
        AlertEngine engine = mock(AlertEngine.class);
        dispatcher = new AlertDispatcher(engine, new AlertMetrics(new SimpleMeterRegistry()), settings(false, 10));
        dispatcher.onTurnOutcome(new TurnOutcome(CONVERSATION, AGENT, 1, ErrorKind.TURN_ERROR));
        assertFalse(dispatcher.submit(() -> {
        }));
        verify(engine, never()).evaluateTurn(any());
    }
}
