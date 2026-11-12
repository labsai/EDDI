/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email.ledger;

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
        participant = new EmailLedgerGdprParticipant(ledger, instance);
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
    @DisplayName("an unverified directory email is not taken as the principal's: another person's rows are neither exported nor erased")
    void unverifiedDirectoryEmailIsSkipped() throws Exception {
        // mallory typed carol's address into an IdP profile that does not verify it
        when(directory.find("mallory")).thenReturn(Optional.of(new DirectoryUser("mallory", "sub-2", "mallory", "carol@example.com", false,
                "Mallory", List.of(), Instant.now(), Instant.now())));

        assertNull(participant.export("mallory"));
        assertEquals(0, participant.erase("mallory"));
        assertEquals(2, ledger.findByRecipient("carol@example.com").size(), "carol's rows stay");

        Map<String, Object> basis = participant.auditBasis("mallory");
        assertEquals(0, basis.get("emailAddresses"));
        assertEquals(true, basis.get("unverifiedDirectoryEmailSkipped"));
    }

    @Test
    @DisplayName("the skip is told to the caller with the remedy, never with the address")
    void unverifiedDirectoryEmailIsAnnounced() {
        when(directory.find("mallory")).thenReturn(Optional.of(new DirectoryUser("mallory", "sub-2", "mallory", "carol@example.com", false,
                "Mallory", List.of(), Instant.now(), Instant.now())));
        when(directory.find("carol")).thenReturn(Optional.of(new DirectoryUser("carol", "sub-1", "carol", "carol@example.com", true, "Carol",
                List.of(), Instant.now(), Instant.now())));

        List<String> erasure = participant.notices("mallory", IGdprParticipant.Operation.ERASURE);
        assertEquals(1, erasure.size());
        assertTrue(erasure.getFirst().startsWith(EmailLedgerGdprParticipant.UNVERIFIED_DIRECTORY_EMAIL));
        assertTrue(erasure.getFirst().contains("not erased"), erasure.getFirst());
        // the remedy warns that a re-run under the address reaches whichever account
        // uses it as its id
        assertTrue(erasure.getFirst().contains("no user account uses the address itself as its user id"), erasure.getFirst());
        assertTrue(erasure.getFirst().contains("deletes everything"), erasure.getFirst());
        assertTrue(erasure.getFirst().contains("email-suppressions/clear"), "points at the address-scoped clear that does exist");
        assertFalse(erasure.getFirst().contains("carol@example.com"), "the response must not carry the address");
        String export = participant.notices("mallory", IGdprParticipant.Operation.EXPORT).getFirst();
        assertTrue(export.contains("not exported"), export);
        assertTrue(export.contains("no user account uses the address itself as its user id"), export);
        assertFalse(export.contains("carol@example.com"));
        assertTrue(participant.notices("carol", IGdprParticipant.Operation.ERASURE).isEmpty());
    }

    @Test
    @DisplayName("the audit basis names where each address came from, never the address")
    void auditBasisNamesSources() throws Exception {
        when(directory.find("carol")).thenReturn(Optional.of(new DirectoryUser("carol", "sub-1", "carol", "carol@example.com", true, "Carol",
                List.of(), Instant.now(), Instant.now())));

        Map<String, Object> basis = participant.auditBasis("carol");
        assertEquals(1, basis.get("emailAddresses"));
        assertEquals(List.of("directoryVerified"), basis.get("emailAddressSources"));
        assertEquals(false, basis.get("unverifiedDirectoryEmailSkipped"));
        assertFalse(basis.toString().contains("carol@example.com"));

        when(directory.find("email:int-1:carol@example.com")).thenReturn(Optional.empty());
        assertEquals(List.of("emailIdentity"), participant.auditBasis("email:int-1:carol@example.com").get("emailAddressSources"));
        when(directory.find("nobody")).thenReturn(Optional.empty());
        assertTrue(participant.auditBasis("nobody").isEmpty());
    }

    @Test
    @DisplayName("a user id that is itself an address reaches its rows without a directory entry")
    void addressUserId() {
        when(directory.find("carol@example.com")).thenReturn(Optional.empty());

        assertEquals(2, participant.erase("carol@example.com"));
        assertNull(participant.export("carol@example.com"));
    }

    @Test
    @DisplayName("it runs before the user directory, which erases the address it reads")
    void orderedBeforeDirectory() {
        IGdprParticipant directoryParticipant = new UserDirectoryGdprParticipant(mock(UserDirectory.class));
        assertTrue(participant.order() < directoryParticipant.order());
    }
}
