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
 * ({@link ImapMailTransport}); where a poll stands is an opaque
 * {@link MailboxCursor}, so an API transport (Gmail history, Graph delta) fits
 * without a poller change.
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

    /**
     * One open mailbox. Not thread-safe; closed by the poll that opened it.
     * <p>
     * Everything the poller needs to know about where it stands is a
     * {@link MailboxCursor} it cannot read: IMAP keeps {@code UIDVALIDITY} and a
     * UID in it ({@link UidFolderSession}), an API transport a history id or a
     * delta link. A new transport is one implementation of this interface; the
     * poller does not change.
     */
    interface MailboxSession extends AutoCloseable {

        /**
         * The cursor of a first poll: after everything already in the folder, so
         * connecting an integration to a full mailbox does not process old mail.
         */
        MailboxCursor startCursor() throws MailboxException;

        /**
         * Envelope data of up to {@code max} messages after {@code cursor}, in the
         * order their cursors advance. No body is fetched.
         * <p>
         * A cursor this session cannot continue from — the folder was rebuilt (IMAP
         * {@code UIDVALIDITY} changed), the history expired, or another transport wrote
         * it — is not an error: the batch comes back with {@link Batch#cursorReset()}
         * set, starting over from a cursor the transport chooses, and the message
         * ledger deduplicates what was seen before.
         */
        Batch messagesAfter(MailboxCursor cursor, int max) throws MailboxException;

        /**
         * Marks a message processed as {@code settings.processedAction()} says
         * ({@code flag}, {@code move:<folder>} or {@code label:<name>}, each as far as
         * the transport supports it — the save-time validation refuses what it cannot
         * do). IMAP: the {@value #PROCESSED_KEYWORD} keyword where the server allows
         * keywords, and a move for {@code move:}. Never deletes. Called only after the
         * ledger row is written.
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

    /**
     * One listing of {@link MailboxSession#messagesAfter(MailboxCursor, int)}.
     *
     * @param entries
     *            the messages, each with the cursor that settles it and everything
     *            before it
     * @param start
     *            the cursor to keep when nothing is settled: the one asked for, or
     *            after a reset the cursor the rescan starts from
     * @param end
     *            the cursor once every entry is settled; for a transport whose
     *            position moves without messages (an API history), past what was
     *            listed
     * @param cursorReset
     *            the cursor asked for could not be continued from, and the entries
     *            are a rescan
     */
    record Batch(List<Entry> entries, MailboxCursor start, MailboxCursor end, boolean cursorReset) {
        public Batch {
            entries = List.copyOf(entries);
            if (start == null || end == null) {
                throw new IllegalArgumentException("A batch needs a start and an end cursor.");
            }
        }
    }

    /**
     * A listed message and the cursor that is settled once it and every entry
     * before it are.
     */
    record Entry(MailboxMessage message, MailboxCursor settles) {
    }

    /** What {@link MailboxSession#markProcessed} did. */
    enum MarkResult {
        FLAGGED, MOVED, FLAGGED_NOT_MOVED, NOTHING
    }

    /** One message as the envelope describes it; the body is fetched on demand. */
    interface MailboxMessage {
        /**
         * The message's UID where the transport has numeric ids (IMAP), else -1. For
         * logs and the UID-based sessions only; the poller goes by
         * {@link Entry#settles()}.
         */
        default long uid() {
            return -1;
        }

        /** The raw Message-ID header value from the envelope, or null. */
        String envelopeMessageId();

        /** {@code RFC822.SIZE}, or -1 when the server did not say. */
        long size();

        /** {@code INTERNALDATE}: when the server received it. */
        Instant internalDate();

        /** Whether it already carries {@link MailTransport#PROCESSED_KEYWORD}. */
        boolean alreadyProcessed();

        /**
         * Where the message was found, for the ledger's sourceRef:
         * {@code <transport>:...}, e.g. IMAP's
         * {@code imap:<folder>:<uidvalidity>:<uid>}.
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
