/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email.ledger;

import ai.labs.eddi.engine.security.spaces.directory.IUserDirectoryStore;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class EmailSuppressionGdprParticipantTest {

    @Test
    @DisplayName("an email identity's suppression entries are exported and erased, across integrations")
    @SuppressWarnings("unchecked")
    void eraseAndExport() throws Exception {
        var store = new InMemoryEmailSuppressionStore();
        Instant now = Instant.parse("2026-10-25T10:00:00Z");
        store.suppress("int-1", "alice@example.com", "bounce:5.1.1", now);
        store.suppress("int-2", "alice@example.com", "bounce:5.1.1", now);
        store.suppress("int-2", "bob@example.com", "bounce:5.1.1", now);
        Instance<IUserDirectoryStore> directory = mock(Instance.class);
        var participant = new EmailSuppressionGdprParticipant(store, new EmailLedgerGdprParticipant(new InMemoryEmailMessageLedger(), directory));

        assertEquals("emailSuppressions", participant.name());
        var exported = (List<Map<String, Object>>) participant.export("email:int-1:alice@example.com");
        assertEquals(2, exported.size());
        assertEquals("alice@example.com", exported.get(0).get("address"));
        assertEquals(2, participant.erase("alice@example.com"));
        assertFalse(store.isSuppressed("int-1", "alice@example.com"));
        assertTrue(store.isSuppressed("int-2", "bob@example.com"));
        assertNull(participant.export("alice@example.com"));
    }
}
