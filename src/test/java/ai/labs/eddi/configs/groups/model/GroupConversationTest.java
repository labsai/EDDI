/*
 * Copyright (c) 2016-2026 EDDI contributors
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
    }

    // ==================== Enums ====================

    @Nested
    @DisplayName("Enums")
    class EnumTests {

        @Test
        @DisplayName("TranscriptEntryType — all values")
        void transcriptEntryTypes() {
            var values = TranscriptEntryType.values();
            assertEquals(11, values.length);
            assertNotNull(TranscriptEntryType.valueOf("QUESTION"));
            assertNotNull(TranscriptEntryType.valueOf("SYNTHESIS"));
            assertNotNull(TranscriptEntryType.valueOf("SKIPPED"));
        }

        @Test
        @DisplayName("GroupConversationState — all values")
        void groupConversationStates() {
            var values = GroupConversationState.values();
            assertEquals(5, values.length);
            assertNotNull(GroupConversationState.valueOf("CREATED"));
            assertNotNull(GroupConversationState.valueOf("COMPLETED"));
            assertNotNull(GroupConversationState.valueOf("FAILED"));
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
}
