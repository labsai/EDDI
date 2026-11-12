/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.gdpr;

import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.PendingHumanInput;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntry;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntryType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static ai.labs.eddi.engine.gdpr.ThirdPartyIdentifiers.OTHER_PERSON;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Masking of other people's identifiers in a data subject's export. */
class ThirdPartyIdentifiersTest {

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
        GroupConversation discussion = new GroupConversation();
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
}
