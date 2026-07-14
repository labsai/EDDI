/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.groups.model;

import ai.labs.eddi.configs.groups.model.GroupConversation.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for GroupConversation model — fields, enums, and TranscriptEntry
 * record.
 */
class GroupConversationTest {

    @Test
    @DisplayName("defaults — empty collections, null scalar fields")
    void defaults() {
        var gc = new GroupConversation();

        assertNull(gc.getId());
        assertNull(gc.getGroupId());
        assertNull(gc.getUserId());
        assertNull(gc.getState());
        assertNull(gc.getOriginalQuestion());
        assertNotNull(gc.getTranscript());
        assertTrue(gc.getTranscript().isEmpty());
        assertNotNull(gc.getMemberConversationIds());
        assertTrue(gc.getMemberConversationIds().isEmpty());
        assertEquals(0, gc.getCurrentPhaseIndex());
        assertNull(gc.getCurrentPhaseName());
        assertNull(gc.getSynthesizedAnswer());
        assertEquals(0, gc.getDepth());
        assertEquals(1, gc.getRound());
        assertNull(gc.getCreated());
        assertNull(gc.getLastModified());
    }

    @Test
    @DisplayName("round-trip all fields")
    void roundTrip() {
        var gc = new GroupConversation();
        var now = Instant.now();

        gc.setId("gc-1");
        gc.setGroupId("group-A");
        gc.setUserId("user-X");
        gc.setState(GroupConversationState.IN_PROGRESS);
        gc.setOriginalQuestion("What is EDDI?");
        gc.setCurrentPhaseIndex(2);
        gc.setCurrentPhaseName("Peer Critique");
        gc.setSynthesizedAnswer("EDDI is a middleware.");
        gc.setDepth(1);
        gc.setCreated(now);
        gc.setLastModified(now);

        var memberIds = new LinkedHashMap<String, String>();
        memberIds.put("agent-1", "conv-1");
        memberIds.put("agent-2", "conv-2");
        gc.setMemberConversationIds(memberIds);

        assertEquals("gc-1", gc.getId());
        assertEquals("group-A", gc.getGroupId());
        assertEquals("user-X", gc.getUserId());
        assertEquals(GroupConversationState.IN_PROGRESS, gc.getState());
        assertEquals("What is EDDI?", gc.getOriginalQuestion());
        assertEquals(2, gc.getCurrentPhaseIndex());
        assertEquals("Peer Critique", gc.getCurrentPhaseName());
        assertEquals("EDDI is a middleware.", gc.getSynthesizedAnswer());
        assertEquals(1, gc.getDepth());
        assertEquals(now, gc.getCreated());
        assertEquals(now, gc.getLastModified());
        assertEquals(2, gc.getMemberConversationIds().size());
    }

    // ==================== TranscriptEntry ====================

    @Nested
    @DisplayName("TranscriptEntry")
    class TranscriptEntryTests {

        @Test
        @DisplayName("record fields accessible via accessors")
        void recordFields() {
            var now = Instant.now();
            var entry = new TranscriptEntry(
                    "agent-1", "Agent One", "I think EDDI is great",
                    0, "Initial Opinions", TranscriptEntryType.OPINION,
                    now, null, null);

            assertEquals("agent-1", entry.speakerAgentId());
            assertEquals("Agent One", entry.speakerDisplayName());
            assertEquals("I think EDDI is great", entry.content());
            assertEquals(0, entry.phaseIndex());
            assertEquals("Initial Opinions", entry.phaseName());
            assertEquals(TranscriptEntryType.OPINION, entry.type());
            assertEquals(now, entry.timestamp());
            assertNull(entry.errorReason());
            assertNull(entry.targetAgentId());
        }

        @Test
        @DisplayName("error entry with errorReason")
        void errorEntry() {
            var entry = new TranscriptEntry(
                    "agent-2", "Agent Two", null,
                    1, "Critique", TranscriptEntryType.ERROR,
                    Instant.now(), "Connection timed out", "agent-1");

            assertEquals(TranscriptEntryType.ERROR, entry.type());
            assertEquals("Connection timed out", entry.errorReason());
            assertEquals("agent-1", entry.targetAgentId());
        }

        @Test
        @DisplayName("full constructor with all envelope fields")
        void fullConstructorWithEnvelope() {
            var now = Instant.now();
            var entry = new TranscriptEntry(
                    "agent-1", "Agent One", "Signed content",
                    0, "Opinions", TranscriptEntryType.OPINION, now,
                    null, null, "sig-base64",
                    "nonce-uuid", 1715800000000L, 2);

            assertEquals("sig-base64", entry.signature());
            assertEquals("nonce-uuid", entry.signatureNonce());
            assertEquals(1715800000000L, entry.signatureTimestampMs());
            assertEquals(2, entry.signatureKeyVersion());
        }

        @Test
        @DisplayName("hasEnvelopeData — true when all three fields present")
        void hasEnvelopeData_allPresent() {
            var entry = new TranscriptEntry(
                    "a", "A", "msg", 0, "p", TranscriptEntryType.OPINION,
                    Instant.now(), null, null, "sig",
                    "nonce", 1000L, 1);

            assertTrue(entry.hasEnvelopeData());
        }

        @Test
        @DisplayName("hasEnvelopeData — false when signature is null")
        void hasEnvelopeData_nullSignature() {
            var entry = new TranscriptEntry(
                    "a", "A", "msg", 0, "p", TranscriptEntryType.OPINION,
                    Instant.now(), null, null, null,
                    "nonce", 1000L, 1);

            assertFalse(entry.hasEnvelopeData());
        }

        @Test
        @DisplayName("hasEnvelopeData — false when nonce is null")
        void hasEnvelopeData_nullNonce() {
            var entry = new TranscriptEntry(
                    "a", "A", "msg", 0, "p", TranscriptEntryType.OPINION,
                    Instant.now(), null, null, "sig",
                    null, 1000L, 1);

            assertFalse(entry.hasEnvelopeData());
        }

        @Test
        @DisplayName("hasEnvelopeData — false when timestamp is null")
        void hasEnvelopeData_nullTimestamp() {
            var entry = new TranscriptEntry(
                    "a", "A", "msg", 0, "p", TranscriptEntryType.OPINION,
                    Instant.now(), null, null, "sig",
                    "nonce", null, 1);

            assertFalse(entry.hasEnvelopeData());
        }

        @Test
        @DisplayName("hasEnvelopeData — false for unsigned backward-compatible entry")
        void hasEnvelopeData_unsignedEntry() {
            var entry = new TranscriptEntry(
                    "a", "A", "msg", 0, "p", TranscriptEntryType.OPINION,
                    Instant.now(), null, null);

            assertFalse(entry.hasEnvelopeData());
            assertNull(entry.signature());
            assertNull(entry.signatureNonce());
            assertNull(entry.signatureTimestampMs());
            assertNull(entry.signatureKeyVersion());
        }

        @Test
        @DisplayName("signature-only constructor — envelope fields are null")
        void signatureOnlyConstructor() {
            var entry = new TranscriptEntry(
                    "a", "A", "msg", 0, "p", TranscriptEntryType.OPINION,
                    Instant.now(), null, null, "sig-only");

            assertEquals("sig-only", entry.signature());
            assertNull(entry.signatureNonce());
            assertNull(entry.signatureTimestampMs());
            assertNull(entry.signatureKeyVersion());
            assertFalse(entry.hasEnvelopeData());
        }
    }

    // ==================== Enums ====================

    @Nested
    @DisplayName("Enums")
    class EnumTests {

        @Test
        @DisplayName("TranscriptEntryType — all values")
        void transcriptEntryTypes() {
            var values = TranscriptEntryType.values();
            assertEquals(15, values.length);
            assertNotNull(TranscriptEntryType.valueOf("QUESTION"));
            assertNotNull(TranscriptEntryType.valueOf("SYNTHESIS"));
            assertNotNull(TranscriptEntryType.valueOf("SKIPPED"));
            assertNotNull(TranscriptEntryType.valueOf("PLAN"));
            assertNotNull(TranscriptEntryType.valueOf("TASK_RESULT"));
            assertNotNull(TranscriptEntryType.valueOf("VERIFICATION"));
            assertNotNull(TranscriptEntryType.valueOf("FOLLOW_UP"));
        }

        @Test
        @DisplayName("GroupConversationState — all values")
        void groupConversationStates() {
            var values = GroupConversationState.values();
            assertEquals(8, values.length);
            assertNotNull(GroupConversationState.valueOf("CREATED"));
            assertNotNull(GroupConversationState.valueOf("COMPLETED"));
            assertNotNull(GroupConversationState.valueOf("FAILED"));
            assertNotNull(GroupConversationState.valueOf("AWAITING_APPROVAL"));
            assertNotNull(GroupConversationState.valueOf("CLOSED"));
            assertNotNull(GroupConversationState.valueOf("CANCELLED"));
        }
    }

    @Test
    @DisplayName("transcript list is mutable")
    void transcriptMutable() {
        var gc = new GroupConversation();
        gc.getTranscript().add(new TranscriptEntry(
                "a", "A", "msg", 0, "p", TranscriptEntryType.OPINION,
                Instant.now(), null, null));

        assertEquals(1, gc.getTranscript().size());
    }

    @Test
    @DisplayName("setTranscript replaces list")
    void setTranscript() {
        var gc = new GroupConversation();
        var entry = new TranscriptEntry(
                "a", "A", "msg", 0, "p", TranscriptEntryType.QUESTION,
                Instant.now(), null, null);
        gc.setTranscript(List.of(entry));

        assertEquals(1, gc.getTranscript().size());
    }

    @Test
    @DisplayName("round setter round-trips")
    void round_setterRoundTrips() {
        var gc = new GroupConversation();
        gc.setRound(3);
        assertEquals(3, gc.getRound());
    }

    // ==================== availableActions (computed) ====================

    @Nested
    @DisplayName("availableActions")
    class AvailableActionsTests {

        @Test
        @DisplayName("COMPLETED offers followup, continue, close")
        void completed() {
            var gc = new GroupConversation();
            gc.setState(GroupConversationState.COMPLETED);
            assertEquals(List.of("followup", "continue", "close"), gc.getAvailableActions());
        }

        @Test
        @DisplayName("FAILED offers close only")
        void failed() {
            var gc = new GroupConversation();
            gc.setState(GroupConversationState.FAILED);
            assertEquals(List.of("close"), gc.getAvailableActions());
        }

        @Test
        @DisplayName("CLOSED offers nothing")
        void closed() {
            var gc = new GroupConversation();
            gc.setState(GroupConversationState.CLOSED);
            assertTrue(gc.getAvailableActions().isEmpty());
        }

        @Test
        @DisplayName("non-terminal states offer nothing")
        void nonTerminal() {
            for (var state : List.of(GroupConversationState.CREATED, GroupConversationState.IN_PROGRESS,
                    GroupConversationState.SYNTHESIZING, GroupConversationState.AWAITING_APPROVAL)) {
                var gc = new GroupConversation();
                gc.setState(state);
                assertTrue(gc.getAvailableActions().isEmpty(), "expected no actions for " + state);
            }
        }

        @Test
        @DisplayName("null state yields empty list, not null")
        void nullState() {
            var gc = new GroupConversation();
            assertNotNull(gc.getAvailableActions());
            assertTrue(gc.getAvailableActions().isEmpty());
        }
    }

    // ==================== memberDisplayNames encapsulation ====================

    @Nested
    @DisplayName("memberDisplayNames")
    class MemberDisplayNamesTests {

        @Test
        @DisplayName("getter returns an unmodifiable view")
        void getterUnmodifiable() {
            var gc = new GroupConversation();
            gc.addMemberDisplayName("a", "Alice");
            assertThrows(UnsupportedOperationException.class,
                    () -> gc.getMemberDisplayNames().put("b", "Bob"));
        }

        @Test
        @DisplayName("addMemberDisplayName populates the map")
        void addPopulates() {
            var gc = new GroupConversation();
            gc.addMemberDisplayName("a", "Alice");
            gc.addMemberDisplayName("b", "Bob");
            assertEquals("Alice", gc.getMemberDisplayNames().get("a"));
            assertEquals(2, gc.getMemberDisplayNames().size());
        }

        @Test
        @DisplayName("setter defensively copies the input map")
        void setterDefensiveCopy() {
            var gc = new GroupConversation();
            var src = new LinkedHashMap<String, String>();
            src.put("a", "Alice");
            gc.setMemberDisplayNames(src);
            src.put("b", "Bob"); // mutate caller's map afterward — must not leak in
            assertEquals(1, gc.getMemberDisplayNames().size());
        }

        @Test
        @DisplayName("setter treats null as empty and stays mutable via add")
        void setterNull() {
            var gc = new GroupConversation();
            gc.setMemberDisplayNames(null);
            assertTrue(gc.getMemberDisplayNames().isEmpty());
            assertDoesNotThrow(() -> gc.addMemberDisplayName("a", "Alice"));
            assertEquals("Alice", gc.getMemberDisplayNames().get("a"));
        }
    }
}
