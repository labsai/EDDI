/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.rest;

import ai.labs.eddi.engine.api.OperatorMetricsService;
import ai.labs.eddi.engine.api.model.OperatorCanaryReport;
import ai.labs.eddi.engine.api.model.OperatorGateStatusReport;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Validates at the REST boundary, then delegates — the service tests own the
 * metric assertions.
 */
@DisplayName("RestOperatorMetrics")
class RestOperatorMetricsTest {

    private SimpleMeterRegistry registry;
    private RestOperatorMetrics rest;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        var service = new OperatorMetricsService(registry);
        service.registerGateGauge();
        rest = new RestOperatorMetrics(service);
    }

    @Test
    @DisplayName("a valid canary report is 204 and reaches the meter")
    void validCanaryReportIs204() {
        var response = rest.reportCanaryResult(new OperatorCanaryReport("pass", 100L));
        assertEquals(204, response.getStatus());
        assertEquals(1.0, registry.counter("eddi.operator.canary", "outcome", "pass").count());
    }

    @Test
    @DisplayName("a null report body is rejected")
    void nullCanaryReportIsRejected() {
        assertThrows(BadRequestException.class, () -> rest.reportCanaryResult(null));
    }

    @Test
    @DisplayName("an outcome outside the fixed vocabulary is rejected before it reaches the meter")
    void invalidOutcomeIsRejected() {
        // The vocabulary is enforced HERE, not trusted from the client — a free-text
        // outcome would let cardinality grow unbounded on a metric label.
        assertThrows(BadRequestException.class, () -> rest.reportCanaryResult(new OperatorCanaryReport("PASS", 100L)));
        assertThrows(BadRequestException.class, () -> rest.reportCanaryResult(new OperatorCanaryReport("", 100L)));
        assertThrows(BadRequestException.class, () -> rest.reportCanaryResult(new OperatorCanaryReport(null, 100L)));
        assertEquals(0.0, registry.find("eddi.operator.canary").counters().stream().mapToDouble(io.micrometer.core.instrument.Counter::count)
                .sum());
    }

    @Test
    @DisplayName("a valid gate-status report is 204 and moves the gauge")
    void validGateStatusReportIs204() {
        var response = rest.reportGateStatus(new OperatorGateStatusReport(true));
        assertEquals(204, response.getStatus());
        assertEquals(1.0, registry.find("eddi.operator.gate.verified").gauge().value());
    }

    @Test
    @DisplayName("a null gate-status body is rejected")
    void nullGateStatusReportIsRejected() {
        assertThrows(BadRequestException.class, () -> rest.reportGateStatus(null));
    }
}
