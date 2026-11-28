/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.alerts;

import ai.labs.eddi.engine.alerts.model.AlertEvent;
import ai.labs.eddi.engine.alerts.model.AlertMatch;
import ai.labs.eddi.engine.alerts.model.AlertRule;
import ai.labs.eddi.engine.alerts.model.DeliveryTarget;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static ai.labs.eddi.engine.alerts.AlertTestData.AGENT;
import static ai.labs.eddi.engine.alerts.AlertTestData.CONVERSATION;
import static ai.labs.eddi.engine.alerts.AlertTestData.NOW;
import static ai.labs.eddi.engine.alerts.AlertTestData.ratingRule;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("AlertDeliveryStage — delivery runs outside the GDPR drain barrier")
class AlertDeliveryStageTest {

    private final CountDownLatch release = new CountDownLatch(1);
    private AlertDispatcher dispatcher;
    private AlertDeliveryStage stage;

    @AfterEach
    void tearDown() {
        release.countDown();
        if (dispatcher != null) {
            dispatcher.shutdown();
        }
        if (stage != null) {
            stage.shutdown();
        }
    }

    @Test
    @DisplayName("a delivery stuck on a slow webhook does not hold the alert worker's barrier, so an erasure is not failed by it")
    void blockedDeliveryDoesNotBlockTheBarrier() throws Exception {
        AlertRuleCache cache = mock(AlertRuleCache.class);
        IAlertEventStore store = mock(IAlertEventStore.class);
        AlertDeliveryService delivery = mock(AlertDeliveryService.class);
        AlertMetrics metrics = new AlertMetrics(new SimpleMeterRegistry());
        when(store.insert(any())).thenAnswer(inv -> Optional.of(((AlertEvent) inv.getArgument(0)).withId("e1")));
        when(store.updateDelivery(any(), anyLong())).thenAnswer(inv -> Optional.of(inv.getArgument(0)));
        when(store.incrementWindow(any(), any(), any())).thenReturn(1L);
        CountDownLatch sending = new CountDownLatch(1);
        when(delivery.deliver(any(), any())).thenAnswer(inv -> {
            sending.countDown();
            release.await(10, TimeUnit.SECONDS); // the webhook hangs
            return Optional.of(inv.getArgument(0));
        });

        AlertEngine engine = new AlertEngine(cache, store, delivery, AlertSettings.defaults(), metrics, null, null, null,
                Clock.fixed(NOW, ZoneOffset.UTC));
        stage = new AlertDeliveryStage(metrics, AlertSettings.defaults());
        engine.deliveryStage = stage;
        dispatcher = new AlertDispatcher(engine, metrics, AlertSettings.defaults());

        AlertRule rule = ratingRule(0.5, null, null, DeliveryTarget.slack("C0123ABC"));
        assertTrue(dispatcher.submit(() -> engine.fire(rule, AlertMatch.output("x", null), AGENT, 2, CONVERSATION)));

        assertTrue(sending.await(5, TimeUnit.SECONDS), "the delivery has started and hangs");
        assertTrue(dispatcher.awaitDrained(Duration.ofSeconds(2)), "the guarded write is done; the hanging send is not waited for");
    }
}
