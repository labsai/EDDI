/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.engine.health.HealthMeasures.NoTest;
import ai.labs.eddi.engine.health.HealthMeasures.PeriodData;
import ai.labs.eddi.engine.health.report.HealthReports.Measure;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@DisplayName("Measure.tested — whether a measure's kind has a significance test")
class HealthMeasuresTestedTest {

    @Test
    @DisplayName("tested matches how each measure is built, for every measure")
    void matchesTheBuild() {
        PeriodData probe = HealthMeasures.probe();
        for (String id : HealthDefaults.MEASURES.keySet()) {
            boolean built = !(HealthMeasures.stat(id, probe).test() instanceof NoTest);
            Measure m = HealthMeasures.measure(id, probe, probe);
            assertNotNull(m.value(), id + " has a value in the probe period");
            assertEquals(built, m.tested(), id);
        }
    }

    @Test
    @DisplayName("tested is a property of the measure, not of the data: the same in an empty period")
    void independentOfData() {
        PeriodData empty = new PeriodData(null, null, null, 1.0, null);
        for (String id : HealthDefaults.MEASURES.keySet()) {
            assertEquals(HealthMeasures.TESTED.contains(id), HealthMeasures.measure(id, empty, null).tested(), id);
        }
    }

    @Test
    @DisplayName("the derived set: rates, Apdex and counts are tested; percentiles, averages and the untested shares are not")
    void theSet() {
        assertEquals(Set.of("agentFailureRate", "platformFailureRate", "apdex", "fallbackRate", "resolutionRate", "escalationRate",
                "abandonmentRate", "deflectionRate", "ratingScore", "ratingResponseRate", "replyThumbsUp", "sessions", "engagedSessions",
                "distinctUsers"), HealthMeasures.TESTED);
    }
}
