/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mail;

/**
 * What {@link MimeMessageParser} may spend on one message.
 * <p>
 * The message is written by whoever can send to the mailbox, and MIME is a
 * container format that can describe far more structure than it occupies:
 * thousands of empty parts in a few kilobytes, or multiparts nested until a
 * recursive walker overflows the stack. Every limit is enforced before the work
 * it bounds, and a breach refuses the whole message with a
 * {@link MailParseException} — except the two that degrade instead, because the
 * rest of the message is still worth reading: an attachment over
 * {@link #maxAttachmentBytes} is kept as metadata without content, and text
 * over {@link #maxOutputCharacters} is truncated and flagged.
 * <p>
 * A non-positive value falls back to the default.
 *
 * @param maxRawBytes
 *            size of the raw RFC 5322 message, checked before parsing
 * @param maxHeaderBytes
 *            size of the top-level header block
 * @param maxMimeDepth
 *            how deep multiparts may nest
 * @param maxParts
 *            MIME parts in the whole message, counted before the parser
 *            materialises them
 * @param maxAttachments
 *            attachment parts (inline images included)
 * @param maxAttachmentBytes
 *            decoded size of one attachment
 * @param maxOutputCharacters
 *            characters of body text produced
 */
public record MailLimits(long maxRawBytes, int maxHeaderBytes, int maxMimeDepth, int maxParts, int maxAttachments,
        long maxAttachmentBytes, int maxOutputCharacters) {

    public static final long DEFAULT_MAX_RAW_BYTES = 25L * 1024 * 1024;
    public static final int DEFAULT_MAX_HEADER_BYTES = 256 * 1024;
    public static final int DEFAULT_MAX_MIME_DEPTH = 12;
    public static final int DEFAULT_MAX_PARTS = 256;
    public static final int DEFAULT_MAX_ATTACHMENTS = 50;
    public static final long DEFAULT_MAX_ATTACHMENT_BYTES = 20L * 1024 * 1024;
    public static final int DEFAULT_MAX_OUTPUT_CHARACTERS = 100_000;

    public MailLimits {
        maxRawBytes = maxRawBytes > 0 ? maxRawBytes : DEFAULT_MAX_RAW_BYTES;
        maxHeaderBytes = maxHeaderBytes > 0 ? maxHeaderBytes : DEFAULT_MAX_HEADER_BYTES;
        maxMimeDepth = maxMimeDepth > 0 ? maxMimeDepth : DEFAULT_MAX_MIME_DEPTH;
        maxParts = maxParts > 0 ? maxParts : DEFAULT_MAX_PARTS;
        maxAttachments = maxAttachments > 0 ? maxAttachments : DEFAULT_MAX_ATTACHMENTS;
        maxAttachmentBytes = maxAttachmentBytes > 0 ? maxAttachmentBytes : DEFAULT_MAX_ATTACHMENT_BYTES;
        maxOutputCharacters = maxOutputCharacters > 0 ? maxOutputCharacters : DEFAULT_MAX_OUTPUT_CHARACTERS;
    }

    /** The defaults: a 25 MB message, matching the channel's default cap. */
    public static MailLimits defaults() {
        return new MailLimits(0, 0, 0, 0, 0, 0, 0);
    }

    /**
     * The same limits with a different raw-size cap, as a channel's settings ask.
     */
    public MailLimits withMaxRawBytes(long bytes) {
        return new MailLimits(bytes, maxHeaderBytes, maxMimeDepth, maxParts, maxAttachments, maxAttachmentBytes, maxOutputCharacters);
    }

    /** The same limits with a different output cap. */
    public MailLimits withMaxOutputCharacters(int characters) {
        return new MailLimits(maxRawBytes, maxHeaderBytes, maxMimeDepth, maxParts, maxAttachments, maxAttachmentBytes, characters);
    }
}
