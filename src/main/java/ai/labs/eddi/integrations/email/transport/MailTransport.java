/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email.transport;

import ai.labs.eddi.configs.channels.model.EmailChannelSettings;

import java.time.Instant;
import java.util.List;

/**
 * A polled mailbox transport (email plan §6): fetch raw bytes, mark done. The
 * one input format is the raw RFC 5322 message; parsing, sender authentication
 * and every guard are transport-independent. IMAP is the only implementation of
 * this phase ({@link ImapMailTransport}).
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

        @Override
        void close();
    }

    /** The keyword a processed message gets. */
    String PROCESSED_KEYWORD = "$EddiProcessed";

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
