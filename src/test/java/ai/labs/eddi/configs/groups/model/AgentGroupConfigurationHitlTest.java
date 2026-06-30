/* Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.groups.model;

import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.HitlConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("AgentGroupConfiguration — HitlConfig")
class AgentGroupConfigurationHitlTest {

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
            assertEquals("WAIT_INDEFINITELY", config.getTimeoutPolicy());
        }

        @Test
        @DisplayName("granularity defaults to 'PHASE'")
        void granularityDefault() {
            assertEquals("PHASE", config.getGranularity());
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
            config.setApprovalTimeout("PT10M");
            assertEquals("PT10M", config.getApprovalTimeout());
        }

        @Test
        @DisplayName("timeoutPolicy round-trip")
        void timeoutPolicy() {
            config.setTimeoutPolicy("AUTO_APPROVE");
            assertEquals("AUTO_APPROVE", config.getTimeoutPolicy());
        }

        @Test
        @DisplayName("granularity round-trip")
        void granularity() {
            config.setGranularity("TASK");
            assertEquals("TASK", config.getGranularity());
        }

        @Test
        @DisplayName("approvalTimeout can be set back to null")
        void approvalTimeoutSetToNull() {
            config.setApprovalTimeout("PT1H");
            config.setApprovalTimeout(null);
            assertNull(config.getApprovalTimeout());
        }

        @Test
        @DisplayName("granularity can be changed from PHASE to DISCUSSION")
        void granularityChange() {
            assertEquals("PHASE", config.getGranularity());
            config.setGranularity("DISCUSSION");
            assertEquals("DISCUSSION", config.getGranularity());
        }
    }

    // ── hitlConfig field on AgentGroupConfiguration ───────────────────

    @Nested
    @DisplayName("hitlConfig field on AgentGroupConfiguration")
    class AgentGroupConfigurationHitlField {

        private AgentGroupConfiguration groupConfig;

        @BeforeEach
        void setUp() {
            groupConfig = new AgentGroupConfiguration();
        }

        @Test
        @DisplayName("hitlConfig defaults to null on a new AgentGroupConfiguration")
        void defaultsToNull() {
            assertNull(groupConfig.getHitlConfig());
        }

        @Test
        @DisplayName("set and get HitlConfig round-trip")
        void roundTrip() {
            HitlConfig config = new HitlConfig();
            config.setApprovalTimeout("PT5M");
            config.setTimeoutPolicy("AUTO_REJECT");
            config.setGranularity("TASK");

            groupConfig.setHitlConfig(config);

            assertNotNull(groupConfig.getHitlConfig());
            assertEquals("PT5M", groupConfig.getHitlConfig().getApprovalTimeout());
            assertEquals("AUTO_REJECT", groupConfig.getHitlConfig().getTimeoutPolicy());
            assertEquals("TASK", groupConfig.getHitlConfig().getGranularity());
        }

        @Test
        @DisplayName("hitlConfig can be set back to null")
        void setToNull() {
            groupConfig.setHitlConfig(new HitlConfig());
            groupConfig.setHitlConfig(null);
            assertNull(groupConfig.getHitlConfig());
        }
    }
}
