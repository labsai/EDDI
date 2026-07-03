/* Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.lifecycle.exceptions;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ConversationPauseException")
class ConversationPauseExceptionTest {

    @Nested
    @DisplayName("constructor and getters")
    class ConstructorAndGetters {

        @Test
        @DisplayName("constructor sets all fields correctly")
        void constructorSetsAllFields() {
            var ex = new ConversationPauseException("wf-1", 3, "needs human review");

            assertEquals("wf-1", ex.getPausedWorkflowId());
            assertEquals(3, ex.getPausedAbsoluteTaskIndex());
            assertEquals("needs human review", ex.getPauseReason());
        }

        @Test
        @DisplayName("getMessage contains the reason")
        void messageContainsReason() {
            var ex = new ConversationPauseException("wf-2", 0, "approval required");

            assertTrue(ex.getMessage().contains("approval required"));
        }

        @Test
        @DisplayName("getMessage follows the 'Conversation paused: <reason>' format")
        void messageFormat() {
            var ex = new ConversationPauseException("wf-1", 1, "sensitive content");

            assertEquals("Conversation paused: sensitive content", ex.getMessage());
        }

        @Test
        @DisplayName("getPausedWorkflowId returns correct value")
        void getPausedWorkflowId() {
            var ex = new ConversationPauseException("workflow-abc", 5, "reason");

            assertEquals("workflow-abc", ex.getPausedWorkflowId());
        }

        @Test
        @DisplayName("getPausedAbsoluteTaskIndex returns correct value")
        void getPausedAbsoluteTaskIndex() {
            var ex = new ConversationPauseException("wf-1", 42, "reason");

            assertEquals(42, ex.getPausedAbsoluteTaskIndex());
        }

        @Test
        @DisplayName("getPauseReason returns correct value")
        void getPauseReason() {
            var ex = new ConversationPauseException("wf-1", 0, "custom reason");

            assertEquals("custom reason", ex.getPauseReason());
        }
    }

    @Nested
    @DisplayName("edge cases")
    class EdgeCases {

        @Test
        @DisplayName("null reason is handled without NPE in getter")
        void nullReasonInGetter() {
            var ex = new ConversationPauseException("wf-1", 0, null);

            assertNull(ex.getPauseReason());
        }

        @Test
        @DisplayName("null reason in getMessage falls back to a safe default, not 'Conversation paused: null'")
        void nullReasonInMessage() {
            var ex = new ConversationPauseException("wf-1", 0, null);

            assertEquals("Conversation paused: human approval required", ex.getMessage());
            assertNull(ex.getPauseReason(), "the raw (null) reason is still exposed via the getter");
        }

        @Test
        @DisplayName("exception is a checked Exception subclass")
        void isCheckedException() {
            var ex = new ConversationPauseException("wf-1", 0, "reason");

            assertInstanceOf(Exception.class, ex);
        }

        @Test
        @DisplayName("task index of zero is valid")
        void zeroTaskIndex() {
            var ex = new ConversationPauseException("wf-1", 0, "reason");

            assertEquals(0, ex.getPausedAbsoluteTaskIndex());
        }
    }
}
