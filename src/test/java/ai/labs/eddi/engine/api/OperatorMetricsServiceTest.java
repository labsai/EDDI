/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.api;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("OperatorMetricsService")
class OperatorMetricsServiceTest {

    private SimpleMeterRegistry registry;
    private OperatorMetricsService service;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        service = new OperatorMetricsService(registry);
        service.registerGateGauge();
    }

    @Test
    @DisplayName("isValidOutcome accepts exactly pass/fail/unknown")
    void isValidOutcomeAcceptsTheFixedVocabulary() {
        assertTrue(OperatorMetricsService.isValidOutcome("pass"));
        assertTrue(OperatorMetricsService.isValidOutcome("fail"));
        assertTrue(OperatorMetricsService.isValidOutcome("unknown"));
        assertFalse(OperatorMetricsService.isValidOutcome("PASS"));
        assertFalse(OperatorMetricsService.isValidOutcome("passed"));
        assertFalse(OperatorMetricsService.isValidOutcome(""));
        assertFalse(OperatorMetricsService.isValidOutcome(null));
    }

    @Test
    @DisplayName("recordCanaryResult increments the outcome-tagged counter")
    void recordCanaryResultIncrementsTheOutcomeCounter() {
        service.recordCanaryResult("pass", 120L);
        service.recordCanaryResult("pass", 80L);
        service.recordCanaryResult("fail", 50L);

        assertEquals(2.0, registry.counter("eddi.operator.canary", "outcome", "pass").count());
        assertEquals(1.0, registry.counter("eddi.operator.canary", "outcome", "fail").count());
        assertEquals(0.0, registry.counter("eddi.operator.canary", "outcome", "unknown").count());
    }

    @Test
    @DisplayName("recordCanaryResult records the duration as a timer sample")
    void recordCanaryResultRecordsDuration() {
        service.recordCanaryResult("pass", 250L);

        var timer = registry.find("eddi.operator.canary.duration").timer();
        assertEquals(1, timer.count());
        assertEquals(250.0, timer.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS), 0.001);
    }

    @Test
    @DisplayName("a null duration is a valid report — no timer sample, no exception")
    void nullDurationRecordsNoSample() {
        service.recordCanaryResult("unknown", null);

        assertEquals(1.0, registry.counter("eddi.operator.canary", "outcome", "unknown").count());
        assertNull(registry.find("eddi.operator.canary.duration").timer());
    }

    @Test
    @DisplayName("a negative duration is silently not recorded, not rejected")
    void negativeDurationIsIgnored() {
        // A malformed duration says nothing about whether the gate held, so the
        // outcome must still count even though the timer sample does not.
        service.recordCanaryResult("pass", -5L);

        assertEquals(1.0, registry.counter("eddi.operator.canary", "outcome", "pass").count());
        assertNull(registry.find("eddi.operator.canary.duration").timer());
    }

    @Test
    @DisplayName("the gate gauge defaults to 0 before any report ever arrives")
    void gateGaugeDefaultsToUnverified() {
        assertEquals(0.0, registry.find("eddi.operator.gate.verified").gauge().value());
    }

    @Test
    @DisplayName("recordGateStatus moves the gauge to 1, and back to 0 on a later failure")
    void recordGateStatusMovesTheGauge() {
        service.recordGateStatus(true);
        assertEquals(1.0, registry.find("eddi.operator.gate.verified").gauge().value());

        // The alertable case: a gate that WAS sound stops being sound. The gauge must
        // actually move, not just have moved once and stuck.
        service.recordGateStatus(false);
        assertEquals(0.0, registry.find("eddi.operator.gate.verified").gauge().value());
    }
}
