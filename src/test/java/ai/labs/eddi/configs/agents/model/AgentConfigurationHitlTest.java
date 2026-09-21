/* Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.agents.model;

import ai.labs.eddi.configs.agents.model.AgentConfiguration.HitlConfig;
import ai.labs.eddi.configs.hitl.HitlTimeoutPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("AgentConfiguration — HitlConfig")
class AgentConfigurationHitlTest {

    // ── HitlConfig defaults ──────────────────────────────────────────

    @Nested
    @DisplayName("HitlConfig default values")
    class HitlConfigDefaults {

        private HitlConfig config;

        @BeforeEach
        void setUp() {
            config = new HitlConfig();
        }

        @Test
        @DisplayName("approvalTimeout defaults to null")
        void approvalTimeoutDefault() {
            assertNull(config.getApprovalTimeout());
        }

        @Test
        @DisplayName("timeoutPolicy defaults to 'WAIT_INDEFINITELY'")
        void timeoutPolicyDefault() {
            assertEquals(HitlTimeoutPolicy.WAIT_INDEFINITELY, config.getTimeoutPolicy());
        }
    }

    // ── HitlConfig getter/setter round-trips ──────────────────────────

    @Nested
    @DisplayName("HitlConfig getter/setter round-trips")
    class HitlConfigRoundTrips {

        private HitlConfig config;

        @BeforeEach
        void setUp() {
            config = new HitlConfig();
        }

        @Test
        @DisplayName("approvalTimeout round-trip")
        void approvalTimeout() {
            config.setApprovalTimeout("PT5M");
            assertEquals("PT5M", config.getApprovalTimeout());
        }

        @Test
        @DisplayName("timeoutPolicy round-trip")
        void timeoutPolicy() {
            config.setTimeoutPolicy(HitlTimeoutPolicy.AUTO_APPROVE);
            assertEquals(HitlTimeoutPolicy.AUTO_APPROVE, config.getTimeoutPolicy());
        }

        @Test
        @DisplayName("approvalTimeout can be set back to null")
        void approvalTimeoutSetToNull() {
            config.setApprovalTimeout("PT1H");
            config.setApprovalTimeout(null);
            assertNull(config.getApprovalTimeout());
        }
    }

    // ── hitlConfig field on AgentConfiguration ────────────────────────

    @Nested
    @DisplayName("hitlConfig field on AgentConfiguration")
    class AgentConfigurationHitlField {

        private AgentConfiguration agentConfig;

        @BeforeEach
        void setUp() {
            agentConfig = new AgentConfiguration();
        }

        @Test
        @DisplayName("hitlConfig defaults to null on a new AgentConfiguration")
        void defaultsToNull() {
            assertNull(agentConfig.getHitlConfig());
        }

        @Test
        @DisplayName("set and get HitlConfig round-trip")
        void roundTrip() {
            HitlConfig config = new HitlConfig();
            config.setApprovalTimeout("PT30S");
            config.setTimeoutPolicy(HitlTimeoutPolicy.AUTO_REJECT);

            agentConfig.setHitlConfig(config);

            assertNotNull(agentConfig.getHitlConfig());
            assertEquals("PT30S", agentConfig.getHitlConfig().getApprovalTimeout());
            assertEquals(HitlTimeoutPolicy.AUTO_REJECT, agentConfig.getHitlConfig().getTimeoutPolicy());
        }

        @Test
        @DisplayName("hitlConfig can be set back to null")
        void setToNull() {
            agentConfig.setHitlConfig(new HitlConfig());
            agentConfig.setHitlConfig(null);
            assertNull(agentConfig.getHitlConfig());
        }
    }
}
