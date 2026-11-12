/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email.transport;

import ai.labs.eddi.configs.channels.model.EmailChannelSettings;

import java.util.Optional;

/**
 * The IMAP reading of a {@link MailboxCursor}: the folder's {@code UIDVALIDITY}
 * and the last UID up to which every message is settled — exactly the two
 * values the poller stored before cursors were opaque. Position form
 * {@code <uidValidity>:<lastUid>}.
 *
 * @param uidValidity
 *            the folder's {@code UIDVALIDITY}; RFC 3501 makes it non-zero, and
 *            0 has always meant "no cursor recorded yet"
 * @param lastUid
 *            every message with a UID up to this one is settled
 * @since 6.6.0
 */
public record ImapCursor(long uidValidity, long lastUid) {

    /** The transport id IMAP cursors carry. */
    public static final String TRANSPORT = EmailChannelSettings.TRANSPORT_IMAP;

    public ImapCursor {
        if (uidValidity < 0 || lastUid < 0) {
            throw new IllegalArgumentException("UIDVALIDITY and UID are unsigned.");
        }
    }

    /** The opaque form the poller and the state store handle. */
    public MailboxCursor toCursor() {
        return new MailboxCursor(TRANSPORT, uidValidity + ":" + lastUid);
    }

    /**
     * The IMAP reading of {@code cursor}; empty for null, for another transport's
     * cursor and for a position that is not {@code <uidValidity>:<lastUid>}.
     */
    public static Optional<ImapCursor> from(MailboxCursor cursor) {
        if (cursor == null || !cursor.belongsTo(TRANSPORT)) {
            return Optional.empty();
        }
        String position = cursor.position();
        int colon = position.indexOf(':');
        if (colon <= 0 || colon == position.length() - 1) {
            return Optional.empty();
        }
        try {
            return Optional.of(new ImapCursor(Long.parseLong(position.substring(0, colon)), Long.parseLong(position.substring(colon + 1))));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
