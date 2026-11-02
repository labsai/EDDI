/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email.transport;

import ai.labs.eddi.configs.channels.model.EmailChannelSettings;
import ai.labs.eddi.engine.mail.MailSendException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * A mailbox transport (email plan §6): fetch raw bytes, mark done, and — for a
 * conversation-mode integration only — send raw bytes. The one input format is
 * the raw RFC 5322 message; parsing, sender authentication and every guard are
 * transport-independent. IMAP in + SMTP out is the only implementation so far
 * ({@link ImapMailTransport}).
 */
public interface MailTransport {

    /** The {@code transport} value this implementation serves. */
    String id();

    /**
     * Connects, authenticates and opens the configured folder for writing.
     *
     * @throws MailboxException
     *             on every failure, categorised
     */
    MailboxSession open(String integrationId, EmailChannelSettings settings) throws MailboxException;

    /** One open mailbox. Not thread-safe; closed by the poll that opened it. */
    interface MailboxSession extends AutoCloseable {

        /** The folder's {@code UIDVALIDITY}. */
        long uidValidity() throws MailboxException;

        /** The folder's {@code UIDNEXT}: the UID the next delivered mail gets. */
        long uidNext() throws MailboxException;

        /**
         * Envelope data of up to {@code max} messages with a UID greater than
         * {@code lastUid}, ascending by UID. No body is fetched.
         */
        List<MailboxMessage> messagesAfter(long lastUid, int max) throws MailboxException;

        /**
         * Marks a message processed: the {@value #PROCESSED_KEYWORD} keyword where the
         * server allows keywords, and a move to {@code settings.moveToFolder()} when
         * configured. Never deletes. Called only after the ledger row is written.
         *
         * @return what was done, for logs and tests
         */
        MarkResult markProcessed(MailboxMessage message, EmailChannelSettings settings) throws MailboxException;

        /**
         * Finds a message by its Message-ID in the polled folder or, when processed
         * mail is moved, in that folder, and fetches it (at most {@code maxBytes}). How
         * a mail whose turn never ran is submitted again: it was marked or moved
         * already, so the cursor does not list it any more. Empty when it is in neither
         * folder.
         */
        default Optional<FoundMessage> findByMessageId(String messageId, EmailChannelSettings settings, long maxBytes) throws MailboxException {
            return Optional.empty();
        }

        @Override
        void close();
    }

    /**
     * Submits one rendered reply to exactly one recipient, with the mailbox address
     * as envelope sender (bounces come back to the polled mailbox). Implementations
     * refuse an integration that is not in conversation mode: trigger mode never
     * sends.
     *
     * @return what the relay answered on acceptance
     * @throws MailSendException
     *             when it was not accepted — permanent only for a refusal that a
     *             retry cannot change
     */
    default String send(String integrationId, EmailChannelSettings settings, byte[] mime, String recipient) throws MailSendException {
        throw new MailSendException(MailSendException.Category.NOT_CONFIGURED, true, "Transport " + id() + " cannot send mail.", null);
    }

    /** The keyword a processed message gets. */
    String PROCESSED_KEYWORD = "$EddiProcessed";

    /**
     * A message found again by its Message-ID, already fetched.
     *
     * @param raw
     *            the raw message
     * @param internalDate
     *            when the server received it, or null
     * @param sourceRef
     *            where it was found
     */
    record FoundMessage(byte[] raw, Instant internalDate, String sourceRef) {
        @Override
        public String toString() {
            return "FoundMessage[" + raw.length + " bytes, " + sourceRef + "]";
        }
    }

    /** What {@link MailboxSession#markProcessed} did. */
    enum MarkResult {
        FLAGGED, MOVED, FLAGGED_NOT_MOVED, NOTHING
    }

    /** One message as the envelope describes it; the body is fetched on demand. */
    interface MailboxMessage {
        long uid();

        /** The raw Message-ID header value from the envelope, or null. */
        String envelopeMessageId();

        /** {@code RFC822.SIZE}, or -1 when the server did not say. */
        long size();

        /** {@code INTERNALDATE}: when the server received it. */
        Instant internalDate();

        /** Whether it already carries {@link MailTransport#PROCESSED_KEYWORD}. */
        boolean alreadyProcessed();

        /**
         * {@code <transport>:<folder>:<uidvalidity>:<uid>}, for the ledger's sourceRef.
         */
        String sourceRef();

        /**
         * The raw message ({@code BODY.PEEK[]}, so the {@code \Seen} flag is not set).
         *
         * @throws MailboxException
         *             also when the message turns out larger than {@code maxBytes}
         */
        byte[] fetchRaw(long maxBytes) throws MailboxException;
    }
}
