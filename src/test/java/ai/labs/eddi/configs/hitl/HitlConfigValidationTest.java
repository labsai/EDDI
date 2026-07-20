/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.hitl;

import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link HitlConfigValidation} — store-level validation that
 * rejects unusable HITL configs at save time instead of silently degrading to
 * wait-forever at runtime.
 */
@DisplayName("HitlConfigValidation")
class HitlConfigValidationTest {

    // =====================================================================
    // Agent-level (regular surface)
    // =====================================================================

    @Nested
    @DisplayName("agent-level config")
    class AgentLevel {

        @Test
        @DisplayName("null config is a no-op")
        void nullConfigNoOp() {
            assertDoesNotThrow(() -> HitlConfigValidation.validate((AgentConfiguration.HitlConfig) null));
        }

        @Test
        @DisplayName("WAIT_INDEFINITELY without approvalTimeout is valid")
        void waitIndefinitelyWithoutTimeoutValid() {
            var config = new AgentConfiguration.HitlConfig();
            config.setTimeoutPolicy(HitlTimeoutPolicy.WAIT_INDEFINITELY);
            assertDoesNotThrow(() -> HitlConfigValidation.validate(config));
        }

        @Test
        @DisplayName("finite policy without approvalTimeout is rejected with an actionable message")
        void finitePolicyWithoutTimeoutRejected() {
            var config = new AgentConfiguration.HitlConfig();
            config.setTimeoutPolicy(HitlTimeoutPolicy.AUTO_REJECT);

            var ex = assertThrows(IllegalArgumentException.class,
                    () -> HitlConfigValidation.validate(config));
            assertTrue(ex.getMessage().contains("approvalTimeout"),
                    "error must name the missing field: " + ex.getMessage());
            assertTrue(ex.getMessage().contains("PT30M"),
                    "error must include a usage example: " + ex.getMessage());
        }

        @Test
        @DisplayName("finite policy with valid ISO-8601 duration is accepted")
        void finitePolicyWithValidDurationAccepted() {
            var config = new AgentConfiguration.HitlConfig();
            config.setTimeoutPolicy(HitlTimeoutPolicy.AUTO_APPROVE);
            config.setApprovalTimeout("PT30M");
            assertDoesNotThrow(() -> HitlConfigValidation.validate(config));
        }

        @Test
        @DisplayName("garbage duration string is rejected")
        void garbageDurationRejected() {
            var config = new AgentConfiguration.HitlConfig();
            config.setTimeoutPolicy(HitlTimeoutPolicy.ABORT);
            config.setApprovalTimeout("30 minutes");

            var ex = assertThrows(IllegalArgumentException.class,
                    () -> HitlConfigValidation.validate(config));
            assertTrue(ex.getMessage().contains("ISO-8601"),
                    "error must explain the expected format: " + ex.getMessage());
        }

        @Test
        @DisplayName("zero duration is rejected")
        void zeroDurationRejected() {
            var config = new AgentConfiguration.HitlConfig();
            config.setTimeoutPolicy(HitlTimeoutPolicy.AUTO_REJECT);
            config.setApprovalTimeout("PT0S");

            assertThrows(IllegalArgumentException.class, () -> HitlConfigValidation.validate(config));
        }

        @Test
        @DisplayName("negative duration is rejected")
        void negativeDurationRejected() {
            var config = new AgentConfiguration.HitlConfig();
            config.setTimeoutPolicy(HitlTimeoutPolicy.AUTO_REJECT);
            config.setApprovalTimeout("PT-5M");

            assertThrows(IllegalArgumentException.class, () -> HitlConfigValidation.validate(config));
        }

        @Test
        @DisplayName("a timeout WITHOUT a finite policy is still format-validated")
        void timeoutWithoutFinitePolicyStillFormatChecked() {
            var config = new AgentConfiguration.HitlConfig();
            config.setTimeoutPolicy(HitlTimeoutPolicy.WAIT_INDEFINITELY);
            config.setApprovalTimeout("bogus");

            assertThrows(IllegalArgumentException.class, () -> HitlConfigValidation.validate(config));
        }
    }

    // =====================================================================
    // Group-level
    // =====================================================================

    @Nested
    @DisplayName("group-level config")
    class GroupLevel {

        @Test
        @DisplayName("null config is a no-op")
        void nullConfigNoOp() {
            assertDoesNotThrow(() -> HitlConfigValidation.validate((AgentGroupConfiguration.HitlConfig) null));
        }

        @Test
        @DisplayName("finite policy without approvalTimeout is rejected")
        void finitePolicyWithoutTimeoutRejected() {
            var config = new AgentGroupConfiguration.HitlConfig();
            config.setTimeoutPolicy(HitlTimeoutPolicy.AUTO_APPROVE);

            assertThrows(IllegalArgumentException.class, () -> HitlConfigValidation.validate(config));
        }

        @Test
        @DisplayName("finite policy with valid duration is accepted")
        void finitePolicyWithValidDurationAccepted() {
            var config = new AgentGroupConfiguration.HitlConfig();
            config.setTimeoutPolicy(HitlTimeoutPolicy.AUTO_REJECT);
            config.setApprovalTimeout("PT2H");
            assertDoesNotThrow(() -> HitlConfigValidation.validate(config));
        }

        @Test
        @DisplayName("default config (WAIT_INDEFINITELY, no timeout) is valid")
        void defaultConfigValid() {
            assertDoesNotThrow(() -> HitlConfigValidation.validate(new AgentGroupConfiguration.HitlConfig()));
        }
    }
}
