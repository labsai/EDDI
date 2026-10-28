/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.alerts;

import ai.labs.eddi.engine.alerts.model.DeliveryTarget.DeliveryType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@DisplayName("AlertMetrics — the bounded series exist at zero before any alert work")
class AlertMetricsTest {

    @Test
    @DisplayName("every delivery type and outcome and the queue_full drop are registered at 0")
    void preRegistered() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new AlertMetrics(registry);

        for (DeliveryType type : DeliveryType.values()) {
            for (String outcome : new String[]{"success", "failure", "gave_up"}) {
                var counter = registry.find(AlertMetrics.DELIVERIES).tag("type", type.name().toLowerCase()).tag("outcome", outcome).counter();
                assertNotNull(counter, type + "/" + outcome);
                assertEquals(0.0, counter.count());
            }
        }
        assertEquals(0.0, registry.get(AlertMetrics.DROPPED).tag("reason", AlertDispatcher.REASON_QUEUE_FULL).counter().count());
    }

    @Test
    @DisplayName("an increment lands on the pre-registered series")
    void incrementsSameSeries() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AlertMetrics metrics = new AlertMetrics(registry);
        metrics.delivery(DeliveryType.WEBHOOK, "failure");
        metrics.dropped(AlertDispatcher.REASON_QUEUE_FULL);

        assertEquals(1.0, registry.get(AlertMetrics.DELIVERIES).tag("type", "webhook").tag("outcome", "failure").counter().count());
        assertEquals(1.0, registry.get(AlertMetrics.DROPPED).counter().count());
    }
}
