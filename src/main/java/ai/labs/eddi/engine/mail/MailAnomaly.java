/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mail;

/**
 * Something about a message that the parser worked around rather than refused.
 * <p>
 * Recorded so that a later stage can be stricter than the parser: the DKIM
 * verifier, for one, must treat {@link #MULTIPLE_FROM} and
 * {@link #DUPLICATE_SINGLETON_HEADER} as unauthenticated whatever the signature
 * says, because those are how a signed message gets a second, unsigned
 * identity.
 */
public enum MailAnomaly {
    /** No Message-ID header; a synthetic id was assigned. */
    MISSING_MESSAGE_ID,
    /** A Message-ID header that holds no usable id; a synthetic id was assigned. */
    INVALID_MESSAGE_ID,
    /** No From header, or one with no valid mailbox. */
    MISSING_FROM,
    /**
     * More than one From header, or one From with several mailboxes. The sender is
     * ambiguous, so {@link InboundMail#from()} is null.
     */
    MULTIPLE_FROM,
    /**
     * A Subject, Date, Message-ID, In-Reply-To or References header appears twice.
     */
    DUPLICATE_SINGLETON_HEADER,
    /** An address in To, Cc or Reply-To was not a valid mailbox and was skipped. */
    INVALID_ADDRESS_SKIPPED,
    /**
     * A part named a charset this JVM does not have; it was decoded heuristically.
     */
    UNKNOWN_CHARSET,
    /** A Date header that could not be parsed. */
    UNPARSEABLE_DATE,
    /** A Content-Type that could not be parsed; the part was read as text/plain. */
    MALFORMED_CONTENT_TYPE,
    /** A part that could not be decoded and was skipped. */
    MALFORMED_PART,
    /**
     * The {@code text/plain} and {@code text/html} renderings of a
     * {@code multipart/alternative} say substantially different things. The agent
     * reads the plain one while a person reviewing the mail sees the HTML, so a
     * later phase should surface or refuse such a mail rather than act on it.
     */
    ALTERNATIVES_DIVERGE
}
