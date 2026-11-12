/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email.transport;

import java.util.Optional;

/**
 * Where a mailbox poll stands, in a form only the transport that wrote it
 * understands (email plan §6). The poller never looks inside: it asks the
 * transport for a start cursor on the first poll, hands the stored cursor back
 * to {@link MailTransport.MailboxSession#messagesAfter(MailboxCursor, int)},
 * and stores whichever cursor the batch says is settled.
 * <p>
 * {@code transport} is the {@link MailTransport#id()} that wrote it, so a
 * cursor read back by another transport (the integration switched transports)
 * is recognised as foreign and treated as invalid by the transport.
 * {@code position} is transport-defined text: IMAP writes
 * {@code <UIDVALIDITY>:<last UID>} ({@link ImapCursor}); an API transport can
 * write a history id or a delta link. The persisted form is
 * {@link #serialize()}: {@code <transport>:<position>}.
 *
 * @param transport
 *            the transport id, without {@code ':'}
 * @param position
 *            the transport-defined position, at most
 *            {@link #MAX_POSITION_LENGTH} characters
 * @since 6.6.0
 */
public record MailboxCursor(String transport, String position) {

    /**
     * A bound on what is stored per mailbox (a Graph delta link is a few hundred
     * characters).
     */
    public static final int MAX_POSITION_LENGTH = 8192;

    public MailboxCursor {
        if (transport == null || transport.isBlank() || transport.indexOf(':') >= 0) {
            throw new IllegalArgumentException("A mailbox cursor needs a transport id without ':'.");
        }
        if (position == null || position.length() > MAX_POSITION_LENGTH) {
            throw new IllegalArgumentException("A mailbox cursor needs a position of at most " + MAX_POSITION_LENGTH + " characters.");
        }
    }

    /** The persisted form, {@code <transport>:<position>}. */
    public String serialize() {
        return transport + ":" + position;
    }

    /**
     * Reads {@link #serialize()}'s form back; empty for null, blank or anything
     * that is not one.
     */
    public static Optional<MailboxCursor> parse(String stored) {
        if (stored == null || stored.isBlank()) {
            return Optional.empty();
        }
        int colon = stored.indexOf(':');
        if (colon <= 0) {
            return Optional.empty();
        }
        try {
            return Optional.of(new MailboxCursor(stored.substring(0, colon), stored.substring(colon + 1)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** Whether {@code transportId} wrote this cursor. */
    public boolean belongsTo(String transportId) {
        return transport.equals(transportId);
    }

    /**
     * The transport only: a position (an API delta link) is not something to put in
     * a log line.
     */
    @Override
    public String toString() {
        return "MailboxCursor[" + transport + "]";
    }
}
