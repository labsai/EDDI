/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mail;

/**
 * A message {@link MimeMessageParser} refuses to turn into an
 * {@link InboundMail}.
 * <p>
 * The message text never names content from the mail, so it is safe to log.
 * {@link #getReason()} is a bounded category, suitable as a metric tag (the
 * email plan's {@code too_large} and {@code parse_error} outcomes).
 */
public class MailParseException extends Exception {

    /** Why the message was refused. */
    public enum Reason {
        /** The raw message is over {@link MailLimits#maxRawBytes()}. */
        TOO_LARGE,
        /** The header block is over {@link MailLimits#maxHeaderBytes()}. */
        HEADERS_TOO_LARGE,
        /** Multiparts nest deeper than {@link MailLimits#maxMimeDepth()}. */
        TOO_DEEP,
        /** More MIME parts than {@link MailLimits#maxParts()}. */
        TOO_MANY_PARTS,
        /** More attachments than {@link MailLimits#maxAttachments()}. */
        TOO_MANY_ATTACHMENTS,
        /** Not parseable as a message at all. */
        MALFORMED
    }

    private final Reason reason;

    public MailParseException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public MailParseException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
