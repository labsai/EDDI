/* Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.model;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("PendingApprovalSummary")
class PendingApprovalSummaryTest {

    @Nested
    @DisplayName("default constructor")
    class DefaultConstructor {

        @Test
        @DisplayName("all fields are null by default")
        void allFieldsNull() {
            var summary = new PendingApprovalSummary();

            assertNull(summary.getConversationId());
            assertNull(summary.getAgentId());
            assertNull(summary.getPausedAt());
            assertNull(summary.getPauseReason());
            assertNull(summary.getTimeoutPolicy());
        }
    }

    @Nested
    @DisplayName("parameterized constructor")
    class ParameterizedConstructor {

        @Test
        @DisplayName("sets all fields correctly")
        void setsAllFields() {
            var now = Instant.now();
            var summary = new PendingApprovalSummary(
                    "conv-1", "agent-1", "user-1", now, "needs review", "AUTO_REJECT");

            assertEquals("conv-1", summary.getConversationId());
            assertEquals("agent-1", summary.getAgentId());
            assertEquals("user-1", summary.getUserId());
            assertEquals(now, summary.getPausedAt());
            assertEquals("needs review", summary.getPauseReason());
            assertEquals("AUTO_REJECT", summary.getTimeoutPolicy());
        }

        @Test
        @DisplayName("accepts null values for all parameters")
        void acceptsNullValues() {
            var summary = new PendingApprovalSummary(null, null, null, null, null, null);

            assertNull(summary.getConversationId());
            assertNull(summary.getAgentId());
            assertNull(summary.getUserId());
            assertNull(summary.getPausedAt());
            assertNull(summary.getPauseReason());
            assertNull(summary.getTimeoutPolicy());
        }
    }

    @Nested
    @DisplayName("setters and getters")
    class SettersAndGetters {

        private PendingApprovalSummary summary;

        @BeforeEach
        void setUp() {
            summary = new PendingApprovalSummary();
        }

        @Test
        @DisplayName("setConversationId/getConversationId round-trips")
        void conversationIdRoundTrip() {
            summary.setConversationId("conv-42");
            assertEquals("conv-42", summary.getConversationId());
        }

        @Test
        @DisplayName("setAgentId/getAgentId round-trips")
        void agentIdRoundTrip() {
            summary.setAgentId("agent-7");
            assertEquals("agent-7", summary.getAgentId());
        }

        @Test
        @DisplayName("setPausedAt/getPausedAt round-trips with Instant")
        void pausedAtRoundTrip() {
            var instant = Instant.parse("2026-06-30T12:00:00Z");
            summary.setPausedAt(instant);
            assertEquals(instant, summary.getPausedAt());
        }

        @Test
        @DisplayName("Instant field preserves nanosecond precision")
        void instantPreservesNanos() {
            var instant = Instant.ofEpochSecond(1_000_000, 123_456_789);
            summary.setPausedAt(instant);
            assertEquals(instant, summary.getPausedAt());
        }

        @Test
        @DisplayName("setPauseReason/getPauseReason round-trips")
        void pauseReasonRoundTrip() {
            summary.setPauseReason("sensitive content detected");
            assertEquals("sensitive content detected", summary.getPauseReason());
        }

        @Test
        @DisplayName("setTimeoutPolicy/getTimeoutPolicy round-trips")
        void timeoutPolicyRoundTrip() {
            summary.setTimeoutPolicy("WAIT_INDEFINITELY");
            assertEquals("WAIT_INDEFINITELY", summary.getTimeoutPolicy());
        }

        @Test
        @DisplayName("setters overwrite previous values")
        void settersOverwrite() {
            summary.setConversationId("old");
            summary.setConversationId("new");
            assertEquals("new", summary.getConversationId());
        }

        @Test
        @DisplayName("setPauseType/getPauseType round-trips")
        void pauseTypeRoundTrip() {
            summary.setPauseType("TOOL_CALL");
            assertEquals("TOOL_CALL", summary.getPauseType());
        }

        @Test
        @DisplayName("setToolNames/getToolNames round-trips names only")
        void toolNamesRoundTrip() {
            summary.setToolNames(List.of("sendEmail", "chargeCard"));
            assertEquals(List.of("sendEmail", "chargeCard"), summary.getToolNames());
        }

        @Test
        @DisplayName("pauseType and toolNames are null by default")
        void pauseTypeAndToolNamesNullByDefault() {
            assertNull(summary.getPauseType());
            assertNull(summary.getToolNames());
        }
    }
}
