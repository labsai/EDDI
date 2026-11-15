/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.groups.outcomes;

import ai.labs.eddi.engine.audit.AuditLedgerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("OutcomeGdprParticipant")
class OutcomeGdprParticipantTest {

    private IOutcomeEventStore store;
    private OutcomeGdprParticipant participant;

    @BeforeEach
    void setUp() {
        store = mock(IOutcomeEventStore.class);
        AuditLedgerService ledger = mock(AuditLedgerService.class);
        when(ledger.pseudonymOf("alice")).thenReturn("erased-abc");
        participant = new OutcomeGdprParticipant(store, ledger);
    }

    @Test
    void erasureKeepsTheEventsAndSwapsThePrincipalForTheLedgersPseudonym() {
        when(store.eraseUser("alice", "erased-abc")).thenReturn(4L);

        assertEquals(4, participant.erase("alice"));
        verify(store).eraseUser("alice", "erased-abc");
    }

    @Test
    void exportReturnsWhatThePersonPostedOrNothing() {
        var posted = List.of(new OutcomeEvent("e", "g", OutcomeEvent.SubjectType.TASK, "t", "k", null, null, null, null, null, "alice", Instant.now(),
                Instant.now(), null));
        when(store.readByUser("alice", OutcomeGdprParticipant.MAX_EXPORTED_ROWS)).thenReturn(posted);
        when(store.readByUser("bob", OutcomeGdprParticipant.MAX_EXPORTED_ROWS)).thenReturn(List.of());

        assertSame(posted, participant.export("alice"));
        assertNull(participant.export("bob"), "an empty section is omitted from an export");
        assertEquals("groupOutcomes", participant.name());
        org.mockito.Mockito.verify(store, org.mockito.Mockito.atLeastOnce()).readByUser(org.mockito.ArgumentMatchers.anyString(), anyInt());
    }
}
