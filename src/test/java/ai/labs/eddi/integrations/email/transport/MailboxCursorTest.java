/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email.transport;

import ai.labs.eddi.configs.channels.model.EmailChannelSettings;
import ai.labs.eddi.integrations.email.transport.MailTransport.Batch;
import ai.labs.eddi.integrations.email.transport.MailTransport.MailboxMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MailboxCursorTest {

    @Nested
    @DisplayName("MailboxCursor")
    class Opaque {

        @Test
        @DisplayName("serialises to <transport>:<position> and parses back")
        void roundTrip() {
            var cursor = new MailboxCursor("gmail", "https://x/delta?$deltatoken=a:b:c");
            assertEquals("gmail:https://x/delta?$deltatoken=a:b:c", cursor.serialize());
            assertEquals(Optional.of(cursor), MailboxCursor.parse(cursor.serialize()));
            assertEquals(Optional.of(new MailboxCursor("x", "")), MailboxCursor.parse("x:"));
        }

        @Test
        @DisplayName("null, blank and garbled stored values read as no cursor")
        void garbled() {
            assertTrue(MailboxCursor.parse(null).isEmpty());
            assertTrue(MailboxCursor.parse("  ").isEmpty());
            assertTrue(MailboxCursor.parse("no-colon").isEmpty());
            assertTrue(MailboxCursor.parse(":position").isEmpty());
            assertTrue(MailboxCursor.parse("imap:" + "9".repeat(MailboxCursor.MAX_POSITION_LENGTH + 1)).isEmpty());
        }

        @Test
        @DisplayName("refuses a transport id with ':' or no id, and never logs the position")
        void invariants() {
            assertThrows(IllegalArgumentException.class, () -> new MailboxCursor("a:b", "1"));
            assertThrows(IllegalArgumentException.class, () -> new MailboxCursor(" ", "1"));
            assertThrows(IllegalArgumentException.class, () -> new MailboxCursor("imap", null));
            assertEquals("MailboxCursor[graph]", new MailboxCursor("graph", "secret-ish-token").toString());
        }
    }

    @Nested
    @DisplayName("ImapCursor")
    class Imap {

        @Test
        @DisplayName("round-trips UIDVALIDITY and the last UID through the opaque form")
        void roundTrip() {
            var cursor = new ImapCursor(1792893887L, 42L).toCursor();
            assertEquals("imap:1792893887:42", cursor.serialize());
            assertEquals(Optional.of(new ImapCursor(1792893887L, 42L)), ImapCursor.from(MailboxCursor.parse(cursor.serialize()).orElseThrow()));
        }

        @Test
        @DisplayName("another transport's cursor or a malformed position is not an IMAP cursor")
        void foreign() {
            assertTrue(ImapCursor.from(null).isEmpty());
            assertTrue(ImapCursor.from(new MailboxCursor("gmail", "1:2")).isEmpty());
            assertTrue(ImapCursor.from(new MailboxCursor("imap", "12")).isEmpty());
            assertTrue(ImapCursor.from(new MailboxCursor("imap", "1:")).isEmpty());
            assertTrue(ImapCursor.from(new MailboxCursor("imap", "a:b")).isEmpty());
            assertTrue(ImapCursor.from(new MailboxCursor("imap", "-1:5")).isEmpty());
        }
    }

    @Nested
    @DisplayName("UidFolderSession")
    class UidSession {

        long uidValidity = 7;
        long uidNext = 100;
        final List<Long> uids = new ArrayList<>();
        long askedAfter = -1;

        final UidFolderSession session = new UidFolderSession() {
            @Override
            public long uidValidity() {
                return uidValidity;
            }

            @Override
            public long uidNext() {
                return uidNext;
            }

            @Override
            public List<MailboxMessage> messagesAfter(long lastUid, int max) {
                askedAfter = lastUid;
                return uids.stream().filter(uid -> uid > lastUid).limit(max).map(UidSession::message).toList();
            }

            @Override
            public MailTransport.MarkResult markProcessed(MailboxMessage message, EmailChannelSettings s) {
                return MailTransport.MarkResult.NOTHING;
            }

            @Override
            public void close() {
                // nothing to close
            }
        };

        static MailboxMessage message(long uid) {
            return new MailboxMessage() {
                @Override
                public long uid() {
                    return uid;
                }

                @Override
                public String envelopeMessageId() {
                    return null;
                }

                @Override
                public long size() {
                    return 0;
                }

                @Override
                public Instant internalDate() {
                    return null;
                }

                @Override
                public boolean alreadyProcessed() {
                    return false;
                }

                @Override
                public String sourceRef() {
                    return "imap:INBOX:7:" + uid;
                }

                @Override
                public byte[] fetchRaw(long maxBytes) {
                    return new byte[0];
                }
            };
        }

        @Test
        @DisplayName("the start cursor is UIDVALIDITY and UIDNEXT - 1, never below 0")
        void start() throws MailboxException {
            assertEquals(new ImapCursor(7, 99).toCursor(), session.startCursor());
            uidNext = 0;
            assertEquals(new ImapCursor(7, 0).toCursor(), session.startCursor());
        }

        @Test
        @DisplayName("a valid cursor continues after its UID; each entry settles its own UID")
        void continues() throws MailboxException {
            uids.addAll(List.of(5L, 10L, 11L));
            Batch batch = session.messagesAfter(new ImapCursor(7, 5).toCursor(), 50);
            assertFalse(batch.cursorReset());
            assertEquals(5, askedAfter);
            assertEquals(new ImapCursor(7, 5).toCursor(), batch.start());
            assertEquals(List.of(new ImapCursor(7, 10).toCursor(), new ImapCursor(7, 11).toCursor()),
                    batch.entries().stream().map(MailTransport.Entry::settles).toList());
            assertEquals(new ImapCursor(7, 11).toCursor(), batch.end());
        }

        @Test
        @DisplayName("nothing new: the cursor stays where it is")
        void empty() throws MailboxException {
            Batch batch = session.messagesAfter(new ImapCursor(7, 99).toCursor(), 50);
            assertTrue(batch.entries().isEmpty());
            assertEquals(new ImapCursor(7, 99).toCursor(), batch.start());
            assertEquals(batch.start(), batch.end());
        }

        @Test
        @DisplayName("a changed UIDVALIDITY is a reset: the folder is listed from UID 0 under the new UIDVALIDITY")
        void uidValidityChanged() throws MailboxException {
            uids.addAll(List.of(1L, 2L));
            Batch batch = session.messagesAfter(new ImapCursor(6, 1_000_000).toCursor(), 50);
            assertTrue(batch.cursorReset());
            assertEquals(0, askedAfter);
            assertEquals(new ImapCursor(7, 0).toCursor(), batch.start());
            assertEquals(new ImapCursor(7, 2).toCursor(), batch.end());
        }

        @Test
        @DisplayName("another transport's cursor is a reset too")
        void foreignCursor() throws MailboxException {
            Batch batch = session.messagesAfter(new MailboxCursor("gmail", "12345"), 50);
            assertTrue(batch.cursorReset());
            assertEquals(new ImapCursor(7, 0).toCursor(), batch.start());
        }
    }
}
