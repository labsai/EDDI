/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email.ledger;

import ai.labs.eddi.integrations.email.transport.ImapCursor;
import ai.labs.eddi.integrations.email.transport.MailboxCursor;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The stored form of a mailbox cursor, shared by the MongoDB and PostgreSQL
 * stores: state written before opaque cursors must load as the same IMAP
 * cursor.
 */
class StoredCursorTest {

    @Test
    @DisplayName("legacy state (UIDVALIDITY and last UID only) loads as the same IMAP cursor")
    void legacyState() {
        assertEquals(new ImapCursor(1792893887L, 42L).toCursor(), StoredCursor.read(null, 1792893887L, 42L));
    }

    @Test
    @DisplayName("UIDVALIDITY 0 and no serialised cursor: no cursor yet, the next poll is a first poll")
    void none() {
        assertNull(StoredCursor.read(null, 0, 0));
        assertNull(StoredCursor.read(" ", 0, 17));
    }

    @Test
    @DisplayName("an IMAP cursor is written to both forms and reads back unchanged")
    void imapRoundTrip() {
        MailboxCursor cursor = new ImapCursor(7, 9).toCursor();
        assertEquals("imap:7:9", StoredCursor.serialized(cursor));
        assertEquals(7, StoredCursor.legacyUidValidity(cursor));
        assertEquals(9, StoredCursor.legacyLastUid(cursor));
        assertEquals(cursor, StoredCursor.read(StoredCursor.serialized(cursor), StoredCursor.legacyUidValidity(cursor),
                StoredCursor.legacyLastUid(cursor)));
    }

    @Test
    @DisplayName("for IMAP the legacy fields win: an older node that wrote only them is not overridden by a stale serialised cursor")
    void legacyFieldsWinForImap() {
        assertEquals(new ImapCursor(7, 30).toCursor(), StoredCursor.read("imap:7:9", 7, 30));
    }

    @Test
    @DisplayName("another transport's cursor is stored as-is, with the legacy fields at 0")
    void otherTransport() {
        MailboxCursor cursor = new MailboxCursor("gmail", "123456");
        assertEquals(0, StoredCursor.legacyUidValidity(cursor));
        assertEquals(0, StoredCursor.legacyLastUid(cursor));
        assertEquals(cursor, StoredCursor.read(StoredCursor.serialized(cursor), 0, 0));
        assertNull(StoredCursor.serialized(null));
    }

    @Test
    @DisplayName("a MongoDB document written before opaque cursors loads with its cursor, spend and since intact")
    void legacyMongoDocument() {
        Date since = new Date(1_700_000_000_000L);
        Document legacy = new Document("_id", "int-1").append("folder", "INBOX").append("uidValidity", 1792893887L).append("lastUid", 42)
                .append("since", since).append("failures", 0).append("budgetDay", "2026-10-25").append("spentUsd", 1.5);

        var state = MongoEmailMailboxStateStore.toState("int-1", legacy);

        assertEquals(new ImapCursor(1792893887L, 42L).toCursor(), state.cursor());
        assertEquals(1792893887L, state.uidValidity());
        assertEquals(42L, state.lastUid());
        assertEquals("INBOX", state.folder());
        assertEquals(since.toInstant(), state.since());
        assertEquals(1.5, state.spentUsd(), 1e-9);
    }

    @Test
    @DisplayName("a MongoDB document with a non-IMAP cursor loads it from the serialised field")
    void mongoOtherTransport() {
        Document document = new Document("_id", "int-1").append("folder", "INBOX").append("mailboxCursor", "gmail:98765")
                .append("uidValidity", 0L).append("lastUid", 0L);
        var state = MongoEmailMailboxStateStore.toState("int-1", document);
        assertEquals(new MailboxCursor("gmail", "98765"), state.cursor());
        assertEquals(0, state.uidValidity());
    }
}
