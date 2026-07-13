/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory;

import ai.labs.eddi.configs.hitl.HitlTimeoutPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the HITL pause bookmark fields on {@link ConversationMemory}.
 */
class ConversationMemoryHitlTest {

    private ConversationMemory memory;

    @BeforeEach
    void setUp() {
        memory = new ConversationMemory("agentId", 1);
    }

    // =========================================================================
    // Defaults
    // =========================================================================

    @Nested
    @DisplayName("HITL field defaults")
    class Defaults {

        @Test
        @DisplayName("hitlPausedWorkflowId defaults to null")
        void workflowIdDefault() {
            assertNull(memory.getHitlPausedWorkflowId());
        }

        @Test
        @DisplayName("hitlPausedAbsoluteTaskIndex defaults to -1")
        void taskIndexDefault() {
            assertEquals(-1, memory.getHitlPausedAbsoluteTaskIndex());
        }

        @Test
        @DisplayName("hitlPausedAt defaults to null")
        void pausedAtDefault() {
            assertNull(memory.getHitlPausedAt());
        }

        @Test
        @DisplayName("hitlPauseReason defaults to null")
        void pauseReasonDefault() {
            assertNull(memory.getHitlPauseReason());
        }

        @Test
        @DisplayName("hitlTimeoutPolicy defaults to null")
        void timeoutPolicyDefault() {
            assertNull(memory.getHitlTimeoutPolicy());
        }

        @Test
        @DisplayName("hitlApprovalTimeout defaults to null")
        void approvalTimeoutDefault() {
            assertNull(memory.getHitlApprovalTimeout());
        }
    }

    // =========================================================================
    // Setter / Getter round-trips
    // =========================================================================

    @Nested
    @DisplayName("HITL field round-trips")
    class RoundTrips {

        @Test
        @DisplayName("hitlPausedWorkflowId round-trip")
        void workflowId() {
            memory.setHitlPausedWorkflowId("workflow-42");
            assertEquals("workflow-42", memory.getHitlPausedWorkflowId());
        }

        @Test
        @DisplayName("hitlPausedAbsoluteTaskIndex round-trip")
        void taskIndex() {
            memory.setHitlPausedAbsoluteTaskIndex(7);
            assertEquals(7, memory.getHitlPausedAbsoluteTaskIndex());
        }

        @Test
        @DisplayName("hitlPausedAt round-trip")
        void pausedAt() {
            var now = Instant.now();
            memory.setHitlPausedAt(now);
            assertEquals(now, memory.getHitlPausedAt());
        }

        @Test
        @DisplayName("hitlPauseReason round-trip")
        void pauseReason() {
            memory.setHitlPauseReason("Requires human review");
            assertEquals("Requires human review", memory.getHitlPauseReason());
        }

        @Test
        @DisplayName("hitlTimeoutPolicy round-trip")
        void timeoutPolicy() {
            memory.setHitlTimeoutPolicy(HitlTimeoutPolicy.AUTO_REJECT);
            assertEquals(HitlTimeoutPolicy.AUTO_REJECT, memory.getHitlTimeoutPolicy());
        }

        @Test
        @DisplayName("hitlApprovalTimeout round-trip")
        void approvalTimeout() {
            memory.setHitlApprovalTimeout("PT30M");
            assertEquals("PT30M", memory.getHitlApprovalTimeout());
        }
    }

    // =========================================================================
    // Cancelled flag
    // =========================================================================

    @Nested
    @DisplayName("cancelled flag")
    class CancelledFlag {

        @Test
        @DisplayName("cancelled defaults to false")
        void defaultFalse() {
            assertFalse(memory.isCancelled());
        }

        @Test
        @DisplayName("setCancelled / isCancelled round-trip")
        void roundTrip() {
            memory.setCancelled(true);
            assertTrue(memory.isCancelled());

            memory.setCancelled(false);
            assertFalse(memory.isCancelled());
        }
    }
}
