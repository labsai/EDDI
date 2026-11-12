/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.gdpr;

import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.GroupMember;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.MemberType;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.PendingHumanInput;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntry;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntryType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static ai.labs.eddi.engine.gdpr.ThirdPartyIdentifiers.OTHER_PERSON;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Masking of other people's identifiers in a data subject's export. */
class ThirdPartyIdentifiersTest {

    /** A discussion created by this version: it carries a human-member record. */
    private static GroupConversation recorded() {
        GroupConversation discussion = new GroupConversation();
        discussion.startHumanMemberRecord();
        return discussion;
    }

    @Test
    @DisplayName("legacy: without a human-member record, agent evidence unmasks nothing")
    void legacyDiscussionTrustsNoAgentEvidence() {
        GroupConversation legacy = new GroupConversation();
        List<TranscriptEntry> transcript = new ArrayList<>();
        // a human turn written as an OPINION, which leaves no evidence of its own
        transcript.add(entry("x", "Xena", TranscriptEntryType.OPINION, "agent-1"));
        transcript.add(entry("agent-1", "Analyst", TranscriptEntryType.CRITIQUE, "x"));
        transcript.add(entry("owner", "Owner", TranscriptEntryType.HUMAN_INPUT, "x"));
        transcript.add(entry(null, "System", TranscriptEntryType.ERROR, null));
        legacy.setTranscript(transcript);
        // a later continuation started a member conversation for x as an agent
        legacy.getMemberConversationIds().put("x", "conv-x");
        legacy.getMemberConversationIds().put("agent-1", "conv-a");
        // recording into a legacy discussion does not turn it into a recorded one
        legacy.recordHumanMember("someone");
        assertNull(legacy.getHumanMemberIds());

        List<TranscriptEntry> masked = ThirdPartyIdentifiers.transcriptFor(legacy, "owner");

        assertEquals(OTHER_PERSON, masked.get(0).speakerAgentId());
        assertEquals(OTHER_PERSON, masked.get(0).speakerDisplayName());
        assertEquals(OTHER_PERSON, masked.get(1).targetAgentId());
        assertEquals(OTHER_PERSON, masked.get(1).speakerAgentId(), "agents read [other person] in a legacy discussion");
        assertEquals("owner", masked.get(2).speakerAgentId(), "the subject's own entries are kept");
        assertEquals(OTHER_PERSON, masked.get(2).targetAgentId());
        assertSame(legacy.getTranscript().get(3), masked.get(3));
    }

    private static TranscriptEntry entry(String speaker, String name, TranscriptEntryType type, String target) {
        return new TranscriptEntry(speaker, name, "text", 0, "phase", type, Instant.EPOCH, null, target);
    }

    @Test
    @DisplayName("keepOwn keeps the subject, empty values and system principals, and masks everyone else")
    void keepOwn() {
        assertEquals("alice", ThirdPartyIdentifiers.keepOwn("alice", "alice"));
        assertNull(ThirdPartyIdentifiers.keepOwn(null, "alice"));
        assertEquals("system:timeout", ThirdPartyIdentifiers.keepOwn("system:timeout", "alice"));
        assertEquals("unknown", ThirdPartyIdentifiers.keepOwn("unknown", "alice"));
        assertEquals(OTHER_PERSON, ThirdPartyIdentifiers.keepOwn("bob", "alice"));
        assertEquals(OTHER_PERSON, ThirdPartyIdentifiers.keepOwn("unverified:bob", "alice"));
    }

    @Test
    @DisplayName("a discussion's other human members are masked in the transcript; agents and the subject are kept")
    void transcriptMasksOtherHumans() {
        GroupConversation discussion = recorded();
        discussion.setUserId("owner@example.com");
        List<TranscriptEntry> transcript = new ArrayList<>();
        transcript.add(entry("agent-1", "Analyst", TranscriptEntryType.OPINION, null));
        // a human member whose id is not an address, known from their HUMAN_INPUT
        transcript.add(entry("gregor", "Gregor", TranscriptEntryType.HUMAN_INPUT, null));
        transcript.add(entry("agent-1", "Analyst", TranscriptEntryType.CRITIQUE, "gregor"));
        // a human member who never spoke, recognisable by the address id
        transcript.add(entry("dana@example.com", "Dana", TranscriptEntryType.SKIPPED, null));
        // the subject, spelled with different case
        transcript.add(entry("Owner@Example.com", "Owner", TranscriptEntryType.HUMAN_INPUT, null));
        transcript.add(entry(null, "System", TranscriptEntryType.ERROR, null));
        // the member a pending turn waits on, skipped once before
        transcript.add(entry("erin", "Erin", TranscriptEntryType.SKIPPED, null));
        discussion.setTranscript(transcript);
        discussion.setPendingHumanInput(new PendingHumanInput("erin", "Erin", 0, 0, 0, "HUMAN_INPUT", null, null, Instant.EPOCH));
        // agent-1 got a member conversation, which only agents do
        discussion.getMemberConversationIds().put("agent-1", "conv-a");
        transcript = discussion.getTranscript();

        List<TranscriptEntry> masked = ThirdPartyIdentifiers.transcriptFor(discussion, "owner@example.com");

        assertSame(transcript.get(0), masked.get(0), "an agent's entry is passed through untouched");
        assertEquals(OTHER_PERSON, masked.get(1).speakerAgentId());
        assertEquals(OTHER_PERSON, masked.get(1).speakerDisplayName());
        assertEquals("text", masked.get(1).content());
        assertEquals("agent-1", masked.get(2).speakerAgentId());
        assertEquals(OTHER_PERSON, masked.get(2).targetAgentId());
        assertEquals(OTHER_PERSON, masked.get(3).speakerAgentId());
        assertEquals("Owner@Example.com", masked.get(4).speakerAgentId(), "the subject's own entries are kept");
        assertSame(transcript.get(5), masked.get(5));
        assertEquals(OTHER_PERSON, masked.get(6).speakerAgentId(), "the member a pending turn waits on is a human");
        assertEquals("gregor", transcript.get(1).speakerAgentId(), "the stored transcript is not changed");
    }

    @Test
    @DisplayName("fail closed: with no configuration a skipped-only, non-address human is masked as speaker, name and target")
    void unknownIdsAreMaskedWithoutAConfiguration() {
        GroupConversation discussion = recorded();
        discussion.setUserId("owner");
        List<TranscriptEntry> transcript = new ArrayList<>();
        // never spoke, not pending, no address for an id, no configuration
        transcript.add(entry("gregor", "Gregor", TranscriptEntryType.SKIPPED, null));
        transcript.add(entry("agent-1", "Analyst", TranscriptEntryType.CRITIQUE, "gregor"));
        transcript.add(entry("owner", "Owner", TranscriptEntryType.HUMAN_INPUT, "agent-1"));
        discussion.setTranscript(transcript);
        discussion.getMemberConversationIds().put("agent-1", "conv-a");

        List<TranscriptEntry> masked = ThirdPartyIdentifiers.transcriptFor(discussion, "owner");

        assertEquals(OTHER_PERSON, masked.get(0).speakerAgentId());
        assertEquals(OTHER_PERSON, masked.get(0).speakerDisplayName());
        assertEquals("agent-1", masked.get(1).speakerAgentId(), "an agent the discussion recorded still reads correctly");
        assertEquals("Analyst", masked.get(1).speakerDisplayName());
        assertEquals("text", masked.get(1).content());
        assertEquals(OTHER_PERSON, masked.get(1).targetAgentId());
        assertSame(discussion.getTranscript().get(2), masked.get(2), "the subject's own entry is kept, its agent target too");
    }

    @Test
    @DisplayName("human evidence wins: a human skip, then a continuation that starts a member conversation for the same id")
    void humanEvidenceWinsOverALaterMemberConversation() {
        GroupConversation discussion = recorded();
        List<TranscriptEntry> transcript = new ArrayList<>();
        // round 1: x was a human member and timed out
        transcript.add(new TranscriptEntry("x", "Xena", null, 0, "p", TranscriptEntryType.SKIPPED, Instant.EPOCH,
                "No response from Xena within PT4H", null));
        transcript.add(entry("agent-1", "Analyst", TranscriptEntryType.CRITIQUE, "x"));
        // continuation under a configuration that now lists x as an agent
        transcript.add(entry("x", "Xena", TranscriptEntryType.OPINION, "agent-1"));
        discussion.setTranscript(transcript);
        discussion.getMemberConversationIds().put("agent-1", "conv-a");
        discussion.getMemberConversationIds().put("x", "conv-x");

        List<TranscriptEntry> masked = ThirdPartyIdentifiers.transcriptFor(discussion, "owner");

        assertEquals(OTHER_PERSON, masked.get(0).speakerAgentId());
        assertEquals(OTHER_PERSON, masked.get(0).speakerDisplayName());
        assertEquals("agent-1", masked.get(1).speakerAgentId());
        assertEquals(OTHER_PERSON, masked.get(1).targetAgentId());
        assertEquals(OTHER_PERSON, masked.get(2).speakerAgentId(), "masked in every entry, the later 'agent' ones too");
        assertEquals(OTHER_PERSON, masked.get(2).speakerDisplayName());
        assertEquals("agent-1", masked.get(2).targetAgentId());
    }

    @Test
    @DisplayName("a human turn written with any entry type is masked once the discussion recorded the human member")
    void recordedHumanMemberIsMaskedWhateverTheEntryType() {
        GroupConversation discussion = recorded();
        discussion.setTranscript(List.of(entry("y", "Yuri", TranscriptEntryType.VOTE, null)));
        // the pause for y's turn records y; the marker is cleared once the turn is
        // answered
        discussion.setPendingHumanInput(new PendingHumanInput("y", "Yuri", 0, 0, 0, "VOTE", null, null, Instant.EPOCH));
        discussion.setPendingHumanInput(null);
        // a later continuation started a member conversation for y
        discussion.getMemberConversationIds().put("y", "conv-y");

        List<TranscriptEntry> masked = ThirdPartyIdentifiers.transcriptFor(discussion, "owner");

        assertEquals(List.of("y"), discussion.getHumanMemberIds());
        assertEquals(OTHER_PERSON, masked.getFirst().speakerAgentId());
        assertEquals(OTHER_PERSON, masked.getFirst().speakerDisplayName());
    }

    @Test
    @DisplayName("only the discussion's own record makes an agent; a configured or persisted HUMAN is masked regardless")
    void onlyTheStoredRecordPlacesAgents() {
        GroupConversation discussion = recorded();
        List<TranscriptEntry> transcript = new ArrayList<>();
        transcript.add(entry("agent-2", "Recruit", TranscriptEntryType.OPINION, null));
        transcript.add(entry("dyn-human", "Dee", TranscriptEntryType.OPINION, null));
        transcript.add(entry("stranger", "Stranger", TranscriptEntryType.OPINION, null));
        transcript.add(entry("hank", "Hank", TranscriptEntryType.OPINION, null));
        discussion.setTranscript(transcript);
        discussion.addDynamicMember(new GroupMember("agent-2", "Recruit", 3, null, MemberType.AGENT));
        discussion.addDynamicMember(new GroupMember("dyn-human", "Dee", 4, null, MemberType.HUMAN));
        discussion.getMemberConversationIds().put("dyn-human", "conv-d");
        discussion.getMemberConversationIds().put("hank", "conv-h");

        List<TranscriptEntry> masked = ThirdPartyIdentifiers.transcriptFor(discussion, "owner", Set.of("hank"));

        assertEquals("agent-2", masked.get(0).speakerAgentId(), "a dynamic member persisted as AGENT is an agent");
        assertEquals(OTHER_PERSON, masked.get(1).speakerAgentId(), "a dynamic member persisted as HUMAN is masked");
        assertEquals(OTHER_PERSON, masked.get(2).speakerAgentId());
        assertEquals(OTHER_PERSON, masked.get(3).speakerAgentId(), "a configured HUMAN is masked even with a member conversation");
    }
}
