/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.internal.readiness;

import org.eclipse.microprofile.health.HealthCheckResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link AgentsReadiness} and {@link AgentsReadinessHealthCheck}.
 */
@DisplayName("Agent Readiness Tests")
class AgentsReadinessTest {

    @Nested
    @DisplayName("AgentsReadiness")
    class Readiness {

        @Test
        @DisplayName("should start as not ready")
        void startsNotReady() {
            var readiness = new AgentsReadiness();
            assertFalse(readiness.isAgentsReady());
        }

        @Test
        @DisplayName("should become ready when set")
        void becomesReady() {
            var readiness = new AgentsReadiness();
            readiness.setAgentsReadiness(true);
            assertTrue(readiness.isAgentsReady());
        }

        @Test
        @DisplayName("should become not ready when unset")
        void becomesNotReady() {
            var readiness = new AgentsReadiness();
            readiness.setAgentsReadiness(true);
            readiness.setAgentsReadiness(false);
            assertFalse(readiness.isAgentsReady());
        }
    }

    @Nested
    @DisplayName("AgentsReadinessHealthCheck")
    class HealthCheck {

        @Test
        @DisplayName("should report DOWN when agents not ready")
        void reportsDown() {
            var readiness = new AgentsReadiness();
            var healthCheck = new AgentsReadinessHealthCheck(readiness);

            HealthCheckResponse response = healthCheck.call();

            assertEquals(HealthCheckResponse.Status.DOWN, response.getStatus());
            assertTrue(response.getName().contains("Agents"));
        }

        @Test
        @DisplayName("should report UP when agents ready")
        void reportsUp() {
            var readiness = new AgentsReadiness();
            readiness.setAgentsReadiness(true);
            var healthCheck = new AgentsReadinessHealthCheck(readiness);

            HealthCheckResponse response = healthCheck.call();

            assertEquals(HealthCheckResponse.Status.UP, response.getStatus());
        }
    }
}
