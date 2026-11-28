/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email.transport;

import ai.labs.eddi.integrations.email.transport.MailTransport.Batch;
import ai.labs.eddi.integrations.email.transport.MailTransport.Entry;
import ai.labs.eddi.integrations.email.transport.MailTransport.MailboxMessage;
import ai.labs.eddi.integrations.email.transport.MailTransport.MailboxSession;

import java.util.ArrayList;
import java.util.List;

/**
 * A {@link MailboxSession} over a folder with IMAP UIDs: the {@link ImapCursor}
 * logic in one place, on three UID primitives.
 * <ul>
 * <li>The start cursor is the folder's {@code UIDVALIDITY} and
 * {@code UIDNEXT - 1}: everything already there is behind it.</li>
 * <li>A cursor with the folder's {@code UIDVALIDITY} continues after its UID. A
 * cursor with another {@code UIDVALIDITY} (the folder was rebuilt), or that is
 * not an IMAP cursor at all, is reset: the folder is listed from UID 0 under
 * the current {@code UIDVALIDITY}.</li>
 * <li>Each listed message settles {@code (UIDVALIDITY, its UID)}; when nothing
 * is listed the cursor stays where it is.</li>
 * </ul>
 */
public abstract class UidFolderSession implements MailboxSession {

    /** The folder's {@code UIDVALIDITY}. */
    public abstract long uidValidity() throws MailboxException;

    /** The folder's {@code UIDNEXT}: the UID the next delivered mail gets. */
    public abstract long uidNext() throws MailboxException;

    /**
     * Envelope data of up to {@code max} messages with a UID greater than
     * {@code lastUid}, ascending by UID. No body is fetched. Each message's
     * {@link MailboxMessage#uid()} is its UID.
     */
    public abstract List<MailboxMessage> messagesAfter(long lastUid, int max) throws MailboxException;

    @Override
    public MailboxCursor startCursor() throws MailboxException {
        return new ImapCursor(uidValidity(), Math.max(0, uidNext() - 1)).toCursor();
    }

    @Override
    public Batch messagesAfter(MailboxCursor cursor, int max) throws MailboxException {
        long uidValidity = uidValidity();
        ImapCursor from = ImapCursor.from(cursor).orElse(null);
        boolean reset = from == null || from.uidValidity() != uidValidity;
        long lastUid = reset ? 0 : from.lastUid();
        MailboxCursor start = new ImapCursor(uidValidity, lastUid).toCursor();
        List<MailboxMessage> messages = messagesAfter(lastUid, max);
        List<Entry> entries = new ArrayList<>(messages.size());
        MailboxCursor end = start;
        for (MailboxMessage message : messages) {
            end = new ImapCursor(uidValidity, message.uid()).toCursor();
            entries.add(new Entry(message, end));
        }
        return new Batch(entries, start, end, reset);
    }
}
