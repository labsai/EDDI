/* Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.lifecycle;

import ai.labs.eddi.engine.lifecycle.GroupConversationEventSink.CancelledEvent;
import ai.labs.eddi.engine.lifecycle.GroupConversationEventSink.HitlPauseEvent;
import ai.labs.eddi.engine.lifecycle.GroupConversationEventSink.HitlResumeEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("GroupConversationEventSink — HITL event constants and records")
class GroupConversationEventSinkHitlTest {

    // ── Event type constants ──────────────────────────────────────────

    @Nested
    @DisplayName("HITL event type constants")
    class EventTypeConstants {

        @Test
        @DisplayName("EVENT_CANCELLED equals 'cancelled'")
        void eventCancelled() {
            assertEquals("cancelled", GroupConversationEventSink.EVENT_CANCELLED);
        }

        @Test
        @DisplayName("EVENT_AWAITING_APPROVAL equals 'awaiting_approval'")
        void eventAwaitingApproval() {
            assertEquals("awaiting_approval", GroupConversationEventSink.EVENT_AWAITING_APPROVAL);
        }

        @Test
        @DisplayName("EVENT_HITL_RESUME equals 'hitl_resume'")
        void eventHitlResume() {
            assertEquals("hitl_resume", GroupConversationEventSink.EVENT_HITL_RESUME);
        }
    }

    // ── CancelledEvent record ─────────────────────────────────────────

    @Nested
    @DisplayName("CancelledEvent record")
    class CancelledEventTests {

        @Test
        @DisplayName("accessors return constructor arguments")
        void accessorsReturnValues() {
            var event = new CancelledEvent("timeout", "admin-user");
            assertEquals("timeout", event.reason());
            assertEquals("admin-user", event.cancelledBy());
        }

        @Test
        @DisplayName("null fields are allowed")
        void nullFieldsAllowed() {
            var event = new CancelledEvent(null, null);
            assertNull(event.reason());
            assertNull(event.cancelledBy());
        }

        @Test
        @DisplayName("equals and hashCode for identical records")
        void equalsAndHashCode() {
            var a = new CancelledEvent("reason", "user1");
            var b = new CancelledEvent("reason", "user1");
            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());
        }

        @Test
        @DisplayName("not equal when fields differ")
        void notEqual() {
            var a = new CancelledEvent("reason1", "user1");
            var b = new CancelledEvent("reason2", "user1");
            assertNotEquals(a, b);
        }
    }

    // ── HitlPauseEvent record ─────────────────────────────────────────

    @Nested
    @DisplayName("HitlPauseEvent record")
    class HitlPauseEventTests {

        @Test
        @DisplayName("accessors return constructor arguments")
        void accessorsReturnValues() {
            var event = new HitlPauseEvent(2, "Peer Review", "requires approval", "PHASE");
            assertEquals(2, event.phaseIndex());
            assertEquals("Peer Review", event.phaseName());
            assertEquals("requires approval", event.reason());
            assertEquals("PHASE", event.granularity());
        }

        @Test
        @DisplayName("null string fields are allowed")
        void nullFieldsAllowed() {
            var event = new HitlPauseEvent(0, null, null, null);
            assertEquals(0, event.phaseIndex());
            assertNull(event.phaseName());
            assertNull(event.reason());
            assertNull(event.granularity());
        }

        @Test
        @DisplayName("equals and hashCode for identical records")
        void equalsAndHashCode() {
            var a = new HitlPauseEvent(1, "Phase1", "reason", "TASK");
            var b = new HitlPauseEvent(1, "Phase1", "reason", "TASK");
            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());
        }
    }

    // ── HitlResumeEvent record ────────────────────────────────────────

    @Nested
    @DisplayName("HitlResumeEvent record")
    class HitlResumeEventTests {

        @Test
        @DisplayName("accessors return constructor arguments")
        void accessorsReturnValues() {
            var event = new HitlResumeEvent("APPROVED", "looks good", "reviewer1");
            assertEquals("APPROVED", event.verdict());
            assertEquals("looks good", event.note());
            assertEquals("reviewer1", event.decidedBy());
        }

        @Test
        @DisplayName("null fields are allowed")
        void nullFieldsAllowed() {
            var event = new HitlResumeEvent(null, null, null);
            assertNull(event.verdict());
            assertNull(event.note());
            assertNull(event.decidedBy());
        }

        @Test
        @DisplayName("equals and hashCode for identical records")
        void equalsAndHashCode() {
            var a = new HitlResumeEvent("APPROVED", "note", "user");
            var b = new HitlResumeEvent("APPROVED", "note", "user");
            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());
        }

        @Test
        @DisplayName("not equal when verdict differs")
        void notEqual() {
            var a = new HitlResumeEvent("APPROVED", "note", "user");
            var b = new HitlResumeEvent("REJECTED", "note", "user");
            assertNotEquals(a, b);
        }
    }
}
