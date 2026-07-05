/* Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ConversationState — HITL value")
class ConversationStateHitlTest {

    @Nested
    @DisplayName("AWAITING_HUMAN enum constant")
    class AwaitingHumanTests {

        @Test
        @DisplayName("AWAITING_HUMAN exists in the enum")
        void awaitingHumanExists() {
            assertNotNull(ConversationState.AWAITING_HUMAN);
        }

        @Test
        @DisplayName("valueOf('AWAITING_HUMAN') returns the correct constant")
        void valueOfWorks() {
            assertEquals(ConversationState.AWAITING_HUMAN,
                    ConversationState.valueOf("AWAITING_HUMAN"));
        }

        @Test
        @DisplayName("name() returns 'AWAITING_HUMAN'")
        void nameReturnsCorrectString() {
            assertEquals("AWAITING_HUMAN", ConversationState.AWAITING_HUMAN.name());
        }
    }

    @Nested
    @DisplayName("Enum completeness")
    class EnumCompleteness {

        @Test
        @DisplayName("values() contains at least the expected members")
        void valuesCount() {
            // READY, IN_PROGRESS, ENDED, EXECUTION_INTERRUPTED, ERROR, AWAITING_HUMAN —
            // presence of each is asserted below; this only guards the lower bound so a
            // future legitimate addition does not spuriously fail.
            assertTrue(ConversationState.values().length >= 6);
        }

        @Test
        @DisplayName("all expected constants are present")
        void allConstantsPresent() {
            assertDoesNotThrow(() -> ConversationState.valueOf("READY"));
            assertDoesNotThrow(() -> ConversationState.valueOf("IN_PROGRESS"));
            assertDoesNotThrow(() -> ConversationState.valueOf("ENDED"));
            assertDoesNotThrow(() -> ConversationState.valueOf("EXECUTION_INTERRUPTED"));
            assertDoesNotThrow(() -> ConversationState.valueOf("ERROR"));
            assertDoesNotThrow(() -> ConversationState.valueOf("AWAITING_HUMAN"));
        }

        @Test
        @DisplayName("valueOf with unknown name throws IllegalArgumentException")
        void unknownValueThrows() {
            assertThrows(IllegalArgumentException.class,
                    () -> ConversationState.valueOf("PAUSED"));
        }
    }
}
