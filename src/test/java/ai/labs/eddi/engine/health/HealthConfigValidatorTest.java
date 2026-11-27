/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.agents.model.HealthConfig;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.health.HealthConfigPolicy.Resolved;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("healthConfig — save-time validation and the lenient per-version reader")
class HealthConfigValidatorTest {

    static AgentConfiguration agent(HealthConfig health) {
        AgentConfiguration configuration = new AgentConfiguration();
        configuration.setHealthConfig(health);
        return configuration;
    }

    static HealthConfig outcomes(String action, String outcome) {
        HealthConfig health = new HealthConfig();
        Map<String, String> map = new LinkedHashMap<>();
        map.put(action, outcome);
        health.setOutcomes(map);
        return health;
    }

    @Nested
    @DisplayName("HealthConfigValidator")
    class Validator {

        @Test
        @DisplayName("absent, empty and valid configurations pass")
        void valid() {
            assertDoesNotThrow(() -> HealthConfigValidator.validate(null));
            assertDoesNotThrow(() -> HealthConfigValidator.validate(agent(null)));
            assertDoesNotThrow(() -> HealthConfigValidator.validate(agent(new HealthConfig())));
            HealthConfig health = outcomes("order_confirmed", "RESOLVED");
            health.getOutcomes().put("CONVERSATION_END", "DEFLECTED");
            health.getOutcomes().put("STOP_CONVERSATION", "FAILED");
            health.setInactivityWindowHours(Map.of("email", 96, "api", 1));
            assertDoesNotThrow(() -> HealthConfigValidator.validate(agent(health)));
        }

        @Test
        @DisplayName("an unknown or assumed outcome is refused, naming the action")
        void unknownOutcome() {
            BadRequestException e = assertThrows(BadRequestException.class,
                    () -> HealthConfigValidator.validate(agent(outcomes("order_confirmed", "SOLVED"))));
            assertTrue(e.getMessage().contains("healthConfig.outcomes.order_confirmed"), e.getMessage());
            assertThrows(BadRequestException.class, () -> HealthConfigValidator.validate(agent(outcomes("x", "ASSUMED_RESOLVED"))),
                    "an assumption is the engine's, not the designer's");
            assertThrows(BadRequestException.class, () -> HealthConfigValidator.validate(agent(outcomes("x", "OPEN"))));
            assertThrows(BadRequestException.class, () -> HealthConfigValidator.validate(agent(outcomes("x", null))));
        }

        @Test
        @DisplayName("reserved actions that start, pause or continue a conversation cannot declare an outcome")
        void reservedActions() {
            for (String action : new String[]{"CONVERSATION_START", "PAUSE_CONVERSATION", "CONTINUE_CONVERSATION"}) {
                BadRequestException e = assertThrows(BadRequestException.class,
                        () -> HealthConfigValidator.validate(agent(outcomes(action, "RESOLVED"))), action);
                assertTrue(e.getMessage().contains("reserved"), e.getMessage());
            }
        }

        @Test
        @DisplayName("bounds: empty or overlong action names, too many mappings, windows outside 1..720 h or of unknown origins")
        void bounds() {
            assertThrows(BadRequestException.class, () -> HealthConfigValidator.validate(agent(outcomes(" ", "RESOLVED"))));
            assertThrows(BadRequestException.class, () -> HealthConfigValidator.validate(agent(outcomes("a".repeat(129), "RESOLVED"))));
            HealthConfig many = new HealthConfig();
            for (int i = 0; i <= HealthConfigValidator.MAX_OUTCOME_MAPPINGS; i++) {
                many.getOutcomes().put("a" + i, "RESOLVED");
            }
            assertThrows(BadRequestException.class, () -> HealthConfigValidator.validate(agent(many)));
            for (Map<String, Integer> windows : List.of(Map.of("api", 0), Map.of("api", 721), Map.of("chat", 24))) {
                HealthConfig health = new HealthConfig();
                health.setInactivityWindowHours(new LinkedHashMap<>(windows));
                assertThrows(BadRequestException.class, () -> HealthConfigValidator.validate(agent(health)), windows.toString());
            }
            HealthConfig nullHours = new HealthConfig();
            Map<String, Integer> withNull = new LinkedHashMap<>();
            withNull.put("api", null);
            nullHours.setInactivityWindowHours(withNull);
            assertThrows(BadRequestException.class, () -> HealthConfigValidator.validate(agent(nullHours)));
        }

        @Test
        @DisplayName("defaults: silence assumed, the self-report tool off, nothing mapped; null maps become empty")
        void defaults() {
            HealthConfig health = new HealthConfig();
            assertTrue(health.isAssumeResolvedOnSilence());
            assertFalse(health.isSelfReportTool());
            health.setOutcomes(null);
            health.setInactivityWindowHours(null);
            assertTrue(health.getOutcomes().isEmpty());
            assertTrue(health.getInactivityWindowHours().isEmpty());
        }
    }

    @Nested
    @DisplayName("HealthConfigPolicy")
    class Policy {

        @Test
        @DisplayName("reads the version's configuration once, lenient about what the validator would refuse")
        void lenientAndCached() throws Exception {
            IAgentStore store = mock(IAgentStore.class);
            HealthConfig health = outcomes("ok", "RESOLVED");
            health.getOutcomes().put("bad", "SOLVED");
            health.getOutcomes().put("CONVERSATION_START", "RESOLVED");
            health.setInactivityWindowHours(new LinkedHashMap<>(Map.of("email", 96, "api", 9_999, "chat", 3)));
            health.setSelfReportTool(true);
            health.setAssumeResolvedOnSilence(false);
            when(store.read("agent", 3)).thenReturn(agent(health));
            HealthConfigPolicy policy = new HealthConfigPolicy(store, HealthSettings.defaults());

            Resolved resolved = policy.of("agent", 3);
            assertEquals(Map.of("ok", "RESOLVED"), resolved.outcomes());
            assertEquals(Map.of("email", 96), resolved.windowHours());
            assertTrue(resolved.selfReportTool());
            assertFalse(resolved.assumeResolvedOnSilence());
            assertTrue(policy.selfReportToolEnabled("agent", 3));
            verify(store, times(1)).read("agent", 3);
            assertTrue(policy.cached("agent", 3).isPresent());
        }

        @Test
        @DisplayName("an unknown version, no version, or a read failure gives the defaults; a failure is not cached")
        void defaults() throws Exception {
            IAgentStore store = mock(IAgentStore.class);
            when(store.read(anyString(), anyInt())).thenThrow(new RuntimeException("down"));
            HealthConfigPolicy policy = new HealthConfigPolicy(store, HealthSettings.defaults());
            assertEquals(Resolved.DEFAULTS, policy.of("agent", 1));
            assertFalse(policy.cached("agent", 1).isPresent());
            assertEquals(Resolved.DEFAULTS, policy.of("agent", null));
            assertEquals(Resolved.DEFAULTS, policy.of(null, 1));
            assertFalse(policy.selfReportToolEnabled("agent", 1));
        }

        @Test
        @DisplayName("require: an absent version gives the defaults; a store that cannot be read throws, uncached, for the evaluation to retry")
        void requireThrowsOnReadFailure() throws Exception {
            IAgentStore store = mock(IAgentStore.class);
            when(store.read("agent", 1)).thenThrow(new IResourceStore.ResourceStoreException("down"));
            when(store.read("agent", 2)).thenThrow(new IResourceStore.ResourceNotFoundException("gone"));
            HealthConfigPolicy policy = new HealthConfigPolicy(store, HealthSettings.defaults());
            assertThrows(HealthStoreException.class, () -> policy.require("agent", 1));
            assertFalse(policy.cached("agent", 1).isPresent());
            assertEquals(Resolved.DEFAULTS, policy.of("agent", 1), "the lenient reader still answers");
            assertEquals(Resolved.DEFAULTS, policy.require("agent", 2));
            assertEquals(Resolved.DEFAULTS, policy.require("agent", null));
        }

        @Test
        @DisplayName("windows: the agent's override, else 72 h for e-mail and 24 h for every other origin")
        void windows() {
            HealthConfigPolicy policy = new HealthConfigPolicy(mock(IAgentStore.class), HealthSettings.defaults());
            Resolved override = new Resolved(Map.of(), true, Map.of("api", 2), false);
            assertEquals(Duration.ofHours(2), policy.windowOf("api", override));
            assertEquals(Duration.ofHours(24), policy.windowOf("slack", override));
            assertEquals(Duration.ofHours(72), policy.windowOf("email", Resolved.DEFAULTS));
            assertEquals(Duration.ofHours(24), policy.windowOf("schedule", null));
        }

        @Test
        @DisplayName("the deployment windows and coverage come from eddi.health.*, with bad values falling back")
        void deploymentSettings() {
            HealthSettings settings = HealthSettings.defaults();
            settings.outcomeSettings(Duration.ofHours(12), Duration.ofHours(48), 0.6);
            assertEquals(Duration.ofHours(12), settings.sessionWindow());
            assertEquals(Duration.ofHours(48), settings.emailSessionWindow());
            assertEquals(0.6, settings.minOutcomeCoverage());
            settings.outcomeSettings(Duration.ZERO, null, 1.5);
            assertEquals(Duration.ofHours(24), settings.sessionWindow());
            assertEquals(Duration.ofHours(72), settings.emailSessionWindow());
            assertEquals(0.5, settings.minOutcomeCoverage());
        }
    }
}
