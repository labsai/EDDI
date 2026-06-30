/* Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.groups.model;

import ai.labs.eddi.configs.groups.model.GroupConversation.GroupConversationState;
import ai.labs.eddi.configs.groups.model.GroupConversation.HitlPauseType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("GroupConversation — HITL fields")
class GroupConversationHitlTest {

    private GroupConversation conversation;

    @BeforeEach
    void setUp() {
        conversation = new GroupConversation();
    }

    // ── GroupConversationState enum ────────────────────────────────────

    @Nested
    @DisplayName("GroupConversationState enum")
    class GroupConversationStateTests {

        @Test
        @DisplayName("CANCELLED exists and round-trips through valueOf")
        void cancelledExists() {
            assertEquals(GroupConversationState.CANCELLED,
                    GroupConversationState.valueOf("CANCELLED"));
        }

        @Test
        @DisplayName("AWAITING_APPROVAL exists and round-trips through valueOf")
        void awaitingApprovalExists() {
            assertEquals(GroupConversationState.AWAITING_APPROVAL,
                    GroupConversationState.valueOf("AWAITING_APPROVAL"));
        }

        @Test
        @DisplayName("values() contains both HITL states alongside originals")
        void valuesContainAllExpected() {
            var values = GroupConversationState.values();
            // CREATED, IN_PROGRESS, SYNTHESIZING, COMPLETED, FAILED, CANCELLED,
            // AWAITING_APPROVAL
            assertEquals(7, values.length);
        }
    }

    // ── HitlPauseType enum ────────────────────────────────────────────

    @Nested
    @DisplayName("HitlPauseType enum")
    class HitlPauseTypeTests {

        @Test
        @DisplayName("PHASE exists")
        void phaseExists() {
            assertEquals(HitlPauseType.PHASE, HitlPauseType.valueOf("PHASE"));
        }

        @Test
        @DisplayName("TASK exists")
        void taskExists() {
            assertEquals(HitlPauseType.TASK, HitlPauseType.valueOf("TASK"));
        }

        @Test
        @DisplayName("values() has exactly 2 members")
        void valuesCount() {
            assertEquals(2, HitlPauseType.values().length);
        }
    }

    // ── Pause field defaults ──────────────────────────────────────────

    @Nested
    @DisplayName("Pause field defaults on a new instance")
    class PauseFieldDefaults {

        @Test
        @DisplayName("pausedAtPhaseIndex defaults to -1")
        void pausedAtPhaseIndexDefault() {
            assertEquals(-1, conversation.getPausedAtPhaseIndex());
        }

        @Test
        @DisplayName("pausedTurnCount defaults to 0")
        void pausedTurnCountDefault() {
            assertEquals(0, conversation.getPausedTurnCount());
        }

        @Test
        @DisplayName("pausedPhaseName defaults to null")
        void pausedPhaseNameDefault() {
            assertNull(conversation.getPausedPhaseName());
        }

        @Test
        @DisplayName("pausedAt defaults to null")
        void pausedAtDefault() {
            assertNull(conversation.getPausedAt());
        }

        @Test
        @DisplayName("hitlPauseType defaults to null")
        void hitlPauseTypeDefault() {
            assertNull(conversation.getHitlPauseType());
        }
    }

    // ── Getter/setter round-trips ─────────────────────────────────────

    @Nested
    @DisplayName("Getter/setter round-trips")
    class GetterSetterRoundTrips {

        @Test
        @DisplayName("pausedAtPhaseIndex round-trip")
        void pausedAtPhaseIndex() {
            conversation.setPausedAtPhaseIndex(3);
            assertEquals(3, conversation.getPausedAtPhaseIndex());
        }

        @Test
        @DisplayName("pausedTurnCount round-trip")
        void pausedTurnCount() {
            conversation.setPausedTurnCount(7);
            assertEquals(7, conversation.getPausedTurnCount());
        }

        @Test
        @DisplayName("pausedPhaseName round-trip")
        void pausedPhaseName() {
            conversation.setPausedPhaseName("Peer Critique");
            assertEquals("Peer Critique", conversation.getPausedPhaseName());
        }

        @Test
        @DisplayName("pausedAt round-trip")
        void pausedAt() {
            Instant now = Instant.now();
            conversation.setPausedAt(now);
            assertEquals(now, conversation.getPausedAt());
        }

        @Test
        @DisplayName("hitlPauseType round-trip with PHASE")
        void hitlPauseTypePhase() {
            conversation.setHitlPauseType(HitlPauseType.PHASE);
            assertEquals(HitlPauseType.PHASE, conversation.getHitlPauseType());
        }

        @Test
        @DisplayName("hitlPauseType round-trip with TASK")
        void hitlPauseTypeTask() {
            conversation.setHitlPauseType(HitlPauseType.TASK);
            assertEquals(HitlPauseType.TASK, conversation.getHitlPauseType());
        }

        @Test
        @DisplayName("state round-trip with AWAITING_APPROVAL")
        void stateAwaitingApproval() {
            conversation.setState(GroupConversationState.AWAITING_APPROVAL);
            assertEquals(GroupConversationState.AWAITING_APPROVAL, conversation.getState());
        }

        @Test
        @DisplayName("state round-trip with CANCELLED")
        void stateCancelled() {
            conversation.setState(GroupConversationState.CANCELLED);
            assertEquals(GroupConversationState.CANCELLED, conversation.getState());
        }
    }

    // ── isPaused() behaviour ──────────────────────────────────────────

    @Nested
    @DisplayName("isPaused() behaviour")
    class IsPausedBehaviour {

        @Test
        @DisplayName("isPaused() returns false when pausedAt is null")
        void notPausedByDefault() {
            assertFalse(conversation.isPaused());
        }

        @Test
        @DisplayName("isPaused() returns true when pausedAt is set")
        void pausedWhenTimestampSet() {
            conversation.setPausedAt(Instant.now());
            assertTrue(conversation.isPaused());
        }

        @Test
        @DisplayName("isPaused() returns false after clearing pausedAt")
        void notPausedAfterClearing() {
            conversation.setPausedAt(Instant.now());
            assertTrue(conversation.isPaused());

            conversation.setPausedAt(null);
            assertFalse(conversation.isPaused());
        }
    }
}
