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
import java.util.Map;

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
        assertNull(gc.getDecision());
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
        gc.setDecision(new DecisionRecord(DecisionType.VERDICT, "PRO wins", "PRO", null, List.of(), "debate-judgment", "Judgment", null));
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
        assertEquals(DecisionType.VERDICT, gc.getDecision().type());
        assertEquals("PRO", gc.getDecision().winner());
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

    // ==================== DecisionRecord (Wave 0, F3) ====================

    @Nested
    @DisplayName("DecisionRecord")
    class DecisionRecordTests {

        @Test
        @DisplayName("record fields accessible via accessors")
        void recordFields() {
            var dissent = new Dissent("agent-2", "Agent Two", "I still disagree");
            var decision = new DecisionRecord(DecisionType.VOTE, "Option A wins 3-1", "Option A",
                    Map.of("Option A", 3, "Option B", 1), List.of(dissent), "majority", "Voting", "raw ballot text");

            assertEquals(DecisionType.VOTE, decision.type());
            assertEquals("Option A wins 3-1", decision.outcome());
            assertEquals("Option A", decision.winner());
            assertEquals(3, decision.tally().get("Option A"));
            assertEquals(1, decision.dissents().size());
            assertEquals("agent-2", decision.dissents().get(0).agentId());
            assertEquals("majority", decision.method());
            assertEquals("Voting", decision.decidedAtPhase());
            assertEquals("raw ballot text", decision.raw());
        }

        @Test
        @DisplayName("nullable fields (winner, tally) may be null")
        void nullableFields() {
            var decision = new DecisionRecord(DecisionType.NONE, null, null, null, List.of(), "debate-judgment", "Judgment", "unparseable");

            assertNull(decision.winner());
            assertNull(decision.tally());
            assertTrue(decision.dissents().isEmpty());
        }

        @Test
        @DisplayName("Dissent record fields accessible via accessors")
        void dissentFields() {
            var dissent = new Dissent("agent-3", "Agent Three", "The evidence was insufficient");

            assertEquals("agent-3", dissent.agentId());
            assertEquals("Agent Three", dissent.displayName());
            assertEquals("The evidence was insufficient", dissent.position());
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

        @Test
        @DisplayName("DecisionType — all values")
        void decisionTypes() {
            var values = DecisionType.values();
            assertEquals(5, values.length);
            assertNotNull(DecisionType.valueOf("VERDICT"));
            assertNotNull(DecisionType.valueOf("VOTE"));
            assertNotNull(DecisionType.valueOf("AGREEMENT"));
            assertNotNull(DecisionType.valueOf("AWARD"));
            assertNotNull(DecisionType.valueOf("NONE"));
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
        @DisplayName("CANCELLED offers close only")
        void cancelled() {
            var gc = new GroupConversation();
            gc.setState(GroupConversationState.CANCELLED);
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
