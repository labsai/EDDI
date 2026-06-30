/* Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory.model;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ConversationMemorySnapshot — HITL bookmark fields")
class ConversationMemorySnapshotHitlTest {

    private ConversationMemorySnapshot snapshot;

    @BeforeEach
    void setUp() {
        snapshot = new ConversationMemorySnapshot();
    }

    // ── Default values ────────────────────────────────────────────────

    @Nested
    @DisplayName("Default values on a new instance")
    class DefaultValues {

        @Test
        @DisplayName("hitlPausedWorkflowId defaults to null")
        void hitlPausedWorkflowIdDefault() {
            assertNull(snapshot.getHitlPausedWorkflowId());
        }

        @Test
        @DisplayName("hitlPausedAbsoluteTaskIndex defaults to -1")
        void hitlPausedAbsoluteTaskIndexDefault() {
            assertEquals(-1, snapshot.getHitlPausedAbsoluteTaskIndex());
        }

        @Test
        @DisplayName("hitlPausedAt defaults to null")
        void hitlPausedAtDefault() {
            assertNull(snapshot.getHitlPausedAt());
        }

        @Test
        @DisplayName("hitlPauseReason defaults to null")
        void hitlPauseReasonDefault() {
            assertNull(snapshot.getHitlPauseReason());
        }

        @Test
        @DisplayName("hitlTimeoutPolicy defaults to null")
        void hitlTimeoutPolicyDefault() {
            assertNull(snapshot.getHitlTimeoutPolicy());
        }

        @Test
        @DisplayName("hitlApprovalTimeout defaults to null")
        void hitlApprovalTimeoutDefault() {
            assertNull(snapshot.getHitlApprovalTimeout());
        }
    }

    // ── Getter/setter round-trips ─────────────────────────────────────

    @Nested
    @DisplayName("Getter/setter round-trips")
    class GetterSetterRoundTrips {

        @Test
        @DisplayName("hitlPausedWorkflowId round-trip")
        void hitlPausedWorkflowId() {
            snapshot.setHitlPausedWorkflowId("workflow-42");
            assertEquals("workflow-42", snapshot.getHitlPausedWorkflowId());
        }

        @Test
        @DisplayName("hitlPausedAbsoluteTaskIndex round-trip")
        void hitlPausedAbsoluteTaskIndex() {
            snapshot.setHitlPausedAbsoluteTaskIndex(5);
            assertEquals(5, snapshot.getHitlPausedAbsoluteTaskIndex());
        }

        @Test
        @DisplayName("hitlPausedAt round-trip")
        void hitlPausedAt() {
            Instant now = Instant.now();
            snapshot.setHitlPausedAt(now);
            assertEquals(now, snapshot.getHitlPausedAt());
        }

        @Test
        @DisplayName("hitlPauseReason round-trip")
        void hitlPauseReason() {
            snapshot.setHitlPauseReason("requires human review");
            assertEquals("requires human review", snapshot.getHitlPauseReason());
        }

        @Test
        @DisplayName("hitlTimeoutPolicy round-trip")
        void hitlTimeoutPolicy() {
            snapshot.setHitlTimeoutPolicy("WAIT_INDEFINITELY");
            assertEquals("WAIT_INDEFINITELY", snapshot.getHitlTimeoutPolicy());
        }

        @Test
        @DisplayName("hitlApprovalTimeout round-trip")
        void hitlApprovalTimeout() {
            snapshot.setHitlApprovalTimeout("PT30S");
            assertEquals("PT30S", snapshot.getHitlApprovalTimeout());
        }

        @Test
        @DisplayName("hitlPausedWorkflowId can be set back to null")
        void hitlPausedWorkflowIdSetToNull() {
            snapshot.setHitlPausedWorkflowId("wf-1");
            snapshot.setHitlPausedWorkflowId(null);
            assertNull(snapshot.getHitlPausedWorkflowId());
        }

        @Test
        @DisplayName("hitlPausedAt can be set back to null")
        void hitlPausedAtSetToNull() {
            snapshot.setHitlPausedAt(Instant.now());
            snapshot.setHitlPausedAt(null);
            assertNull(snapshot.getHitlPausedAt());
        }
    }
}
