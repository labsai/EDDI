/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.alerts;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AlertSettingsTest {

    private static AlertSettings withMaxRules(int maxRulesPerAgent) {
        return new AlertSettings(true, 1000, Duration.ofSeconds(60), 5, Duration.ofSeconds(30), Duration.ofMinutes(2), Duration.ofDays(90),
                Duration.ofSeconds(10), Optional.empty(), maxRulesPerAgent);
    }

    @Test
    @DisplayName("max-rules-per-agent defaults to 50 and is kept as configured")
    void maxRulesPerAgent() {
        assertEquals(50, AlertSettings.defaults().maxRulesPerAgent());
        assertEquals(7, withMaxRules(7).maxRulesPerAgent());
    }

    @Test
    @DisplayName("max-rules-per-agent of zero or less is raised to 1, never a cap that refuses every rule")
    void maxRulesPerAgentIsAtLeastOne() {
        assertEquals(1, withMaxRules(0).maxRulesPerAgent());
        assertEquals(1, withMaxRules(-3).maxRulesPerAgent());
    }
}
