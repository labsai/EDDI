/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email.ledger;

import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.gdpr.IGdprParticipant;
import ai.labs.eddi.engine.security.spaces.directory.DirectoryUser;
import ai.labs.eddi.engine.security.spaces.directory.IUserDirectoryStore;
import ai.labs.eddi.engine.security.spaces.directory.UserDirectoryGdprParticipant;
import ai.labs.eddi.engine.security.spaces.directory.UserDirectory;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.Direction;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.Kind;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.LedgerEntry;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.State;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** GDPR erasure and export of email ledger rows. */
class EmailLedgerGdprParticipantTest {

    private InMemoryEmailMessageLedger ledger;
    private IUserDirectoryStore directory;
    private EmailLedgerGdprParticipant participant;

    private static LedgerEntry row(String recipient) {
        Instant now = Instant.now();
        return new LedgerEntry(UUID.randomUUID().toString(), IEmailMessageLedger.SYSTEM_INTEGRATION, Kind.NOTIFY, Direction.OUT,
                "<eddi." + UUID.randomUUID() + "@example.com>", "conv", "pause", UUID.randomUUID().toString(), recipient, State.SENT, 1, null, null,
                "250", null, now, now, now.plus(Duration.ofDays(30)));
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ledger = new InMemoryEmailMessageLedger();
        directory = mock(IUserDirectoryStore.class);
        Instance<IUserDirectoryStore> instance = mock(Instance.class);
        when(instance.isResolvable()).thenReturn(true);
        when(instance.get()).thenReturn(directory);
        // No conversation the rows name exists any more: nothing holds the erasure.
        IConversationMemoryStore store = mock(IConversationMemoryStore.class);
        try {
            when(store.loadConversationMemorySnapshot(anyString())).thenThrow(new IResourceStore.ResourceNotFoundException("gone"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        Instance<IConversationMemoryStore> stores = mock(Instance.class);
        when(stores.isResolvable()).thenReturn(true);
        when(stores.get()).thenReturn(store);
        participant = new EmailLedgerGdprParticipant(ledger, instance, stores);
        ledger.insertIfAbsent(row("carol@example.com"));
        ledger.insertIfAbsent(row("carol@example.com"));
        ledger.insertIfAbsent(row("ops@example.com"));
    }

    @Test
    @DisplayName("a principal's rows are found through the email the user directory recorded")
    void throughDirectory() {
        when(directory.find("carol")).thenReturn(Optional.of(new DirectoryUser("carol", "sub-1", "carol", "Carol@Example.com", true, "Carol",
                List.of(), Instant.now(), Instant.now())));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> exported = (List<Map<String, Object>>) participant.export("carol");
        assertEquals(2, exported.size());
        assertFalse(exported.get(0).containsKey("claimToken"));
        assertFalse(exported.get(0).containsKey("idempotencyKey"));

        assertEquals(2, participant.erase("carol"));
        assertTrue(ledger.findByRecipient("carol@example.com").isEmpty());
        assertEquals(1, ledger.findByRecipient("ops@example.com").size(), "somebody else's rows stay");
    }

    @Test
    @DisplayName("a user id that is itself an address reaches its rows without a directory entry")
    void addressUserId() {
        when(directory.find("carol@example.com")).thenReturn(Optional.empty());

        assertEquals(2, participant.erase("carol@example.com"));
        assertNull(participant.export("carol@example.com"));
    }

    @Test
    @DisplayName("a reply the address was copied on is exported with role CC, and its entry is erased")
    void copies() {
        LedgerEntry reply = row("ops@example.com");
        ledger.insertIfAbsent(reply);
        ledger.addReplyRecipients(List.of(
                new IEmailMessageLedger.ReplyRecipient(reply.id(), reply.integrationId(), "conv", "ops@example.com",
                        IEmailMessageLedger.RecipientRole.TO,
                        reply.createdAt(), reply.expiresAt()),
                new IEmailMessageLedger.ReplyRecipient(reply.id(), reply.integrationId(), "conv", "dave@example.com",
                        IEmailMessageLedger.RecipientRole.CC, reply.createdAt(), reply.expiresAt())));
        when(directory.find("dave@example.com")).thenReturn(Optional.empty());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> exported = (List<Map<String, Object>>) participant.export("dave@example.com");
        assertEquals(1, exported.size());
        assertEquals("CC", exported.get(0).get("role"));
        assertEquals(reply.messageId(), exported.get(0).get("messageId"));
        assertEquals("SENT", exported.get(0).get("state"));

        participant.erase("dave@example.com");
        assertTrue(ledger.findReplyRecipientsByAddress("dave@example.com").isEmpty());
        assertFalse(ledger.hasSentReply("conv", "dave@example.com"));
        assertEquals(1, ledger.findReplyRecipients(reply.id()).size(), "the To's own entry stays");
    }

    @Test
    @DisplayName("it runs before the user directory, which erases the address it reads")
    void orderedBeforeDirectory() {
        IGdprParticipant directoryParticipant = new UserDirectoryGdprParticipant(mock(UserDirectory.class));
        assertTrue(participant.order() < directoryParticipant.order());
    }
}
