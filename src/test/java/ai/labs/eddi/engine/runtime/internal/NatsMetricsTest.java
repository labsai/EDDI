/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.internal;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("NatsMetrics")
class NatsMetricsTest {

    private NatsMetrics natsMetrics;

    @BeforeEach
    void setUp() {
        MeterRegistry registry = new SimpleMeterRegistry();
        natsMetrics = new NatsMetrics(registry);
        natsMetrics.init();
    }

    @Test
    @DisplayName("constructor and init register all meters")
    void initRegistersMeters() {
        assertNotNull(natsMetrics.getPublishCount());
        assertNotNull(natsMetrics.getPublishDuration());
        assertNotNull(natsMetrics.getConsumeCount());
        assertNotNull(natsMetrics.getConsumeDuration());
        assertNotNull(natsMetrics.getDeadLetterCount());
    }

    @Test
    @DisplayName("counters start at zero")
    void countersStartAtZero() {
        assertEquals(0.0, natsMetrics.getPublishCount().count());
        assertEquals(0.0, natsMetrics.getConsumeCount().count());
        assertEquals(0.0, natsMetrics.getDeadLetterCount().count());
    }

    @Test
    @DisplayName("counters can be incremented")
    void countersIncrement() {
        natsMetrics.getPublishCount().increment();
        natsMetrics.getConsumeCount().increment();
        natsMetrics.getConsumeCount().increment();
        natsMetrics.getDeadLetterCount().increment();

        assertEquals(1.0, natsMetrics.getPublishCount().count());
        assertEquals(2.0, natsMetrics.getConsumeCount().count());
        assertEquals(1.0, natsMetrics.getDeadLetterCount().count());
    }

    @Test
    @DisplayName("timers have zero count initially")
    void timersStartEmpty() {
        assertEquals(0, natsMetrics.getPublishDuration().count());
        assertEquals(0, natsMetrics.getConsumeDuration().count());
    }
}
