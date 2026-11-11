/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.engine.health.report.HealthReports.Interval;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("HealthStatistics — Wilson intervals, significance tests, bucket percentiles")
class HealthStatisticsTest {

    @Test
    @DisplayName("Wilson interval of 5/10 is the textbook [0.2366, 0.7634]")
    void wilsonHalf() {
        Interval i = HealthStatistics.wilson(5, 10);
        assertEquals(0.2366, i.low(), 1e-4);
        assertEquals(0.7634, i.high(), 1e-4);
    }

    @Test
    @DisplayName("Wilson stays inside 0..1 at 0 % and 100 % where the normal interval collapses")
    void wilsonEdges() {
        Interval none = HealthStatistics.wilson(0, 10);
        assertEquals(0.0, none.low(), 1e-12);
        assertEquals(0.2775, none.high(), 1e-4);
        Interval all = HealthStatistics.wilson(10, 10);
        assertEquals(0.7225, all.low(), 1e-4);
        assertEquals(1.0, all.high(), 1e-12);
        assertNull(HealthStatistics.wilson(0, 0));
    }

    @Test
    @DisplayName("the two-proportion z-test calls 1 % vs 5 % on 1000 turns significant, and 1 % vs 2 % on 100 not")
    void proportions() {
        assertTrue(HealthStatistics.significantProportions(50, 1000, 10, 1000));
        assertFalse(HealthStatistics.significantProportions(2, 100, 1, 100));
        assertFalse(HealthStatistics.significantProportions(0, 100, 0, 100), "no variance, nothing to call");
        assertFalse(HealthStatistics.significantProportions(1, 0, 1, 10));
    }

    @Test
    @DisplayName("Welch z for means and the Poisson test for counts")
    void meansAndCounts() {
        assertTrue(HealthStatistics.significantMeans(0.9, 0.05, 500, 0.7, 0.1, 500));
        assertFalse(HealthStatistics.significantMeans(0.9, 0.05, 5, 0.85, 0.05, 5));
        assertTrue(HealthStatistics.significantCounts(200, 100), "(200-100)/sqrt(300) = 5.8");
        assertFalse(HealthStatistics.significantCounts(105, 100));
        assertFalse(HealthStatistics.significantCounts(0, 0));
    }

    @Test
    @DisplayName("percentile interpolates inside the bucket and answers the last bound for the overflow bucket")
    void percentile() {
        long[] bounds = {100, 200, 400};
        // 10 turns: 5 in (0,100], 5 in (100,200] — p95 is 95 % into the second bucket.
        assertEquals(190.0, HealthStatistics.percentile(List.of(5L, 5L, 0L, 0L), bounds, 0.95), 1e-9);
        assertEquals(400.0, HealthStatistics.percentile(List.of(0L, 0L, 0L, 3L), bounds, 0.95));
        assertNull(HealthStatistics.percentile(List.of(0L, 0L, 0L, 0L), bounds, 0.95));
    }
}
