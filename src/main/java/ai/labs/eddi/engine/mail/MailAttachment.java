/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mail;

/**
 * One attachment: what the message says about it, what the parser found, and —
 * unless it was refused — its bytes.
 * <p>
 * Whether an attachment is <em>wanted</em> is the caller's decision (a
 * channel's {@code attachmentMimeTypes}, a RAG source's allowlist). Two things
 * are decided here because no caller may decide otherwise: an executable never
 * carries content ({@link SkipReason#EXECUTABLE}), and an archive is never
 * expanded — {@link #archive()} only says what it is.
 * <p>
 * The declared {@link #contentType()} and {@link #filename()} are the sender's
 * claims. {@link #executable()} also looks at the bytes, so an executable
 * renamed to {@code invoice.pdf} is still caught.
 *
 * @param filename
 *            decoded, path-free file name, or null
 * @param contentType
 *            declared base type, lower-case ({@code application/pdf})
 * @param size
 *            decoded size in bytes, or -1 when reading stopped at the limit
 * @param contentId
 *            the Content-ID without angle brackets, or null
 * @param inline
 *            whether the part was marked {@code Content-Disposition: inline}
 * @param executable
 *            whether name, type or bytes say it is executable
 * @param archive
 *            whether name or type say it is an archive or disk image
 * @param skipReason
 *            why there is no content, or null when there is
 * @param content
 *            the bytes, or null when skipped
 */
public record MailAttachment(String filename, String contentType, long size, String contentId, boolean inline, boolean executable,
        boolean archive, SkipReason skipReason, AttachmentContent content) {

    /** Why an attachment's content was withheld. */
    public enum SkipReason {
        /** Executables never carry content, whatever a caller's allowlist says. */
        EXECUTABLE,
        /** Over {@link MailLimits#maxAttachmentBytes()}. */
        TOO_LARGE
    }

    /** Whether the bytes are available. */
    public boolean hasContent() {
        return content != null;
    }

    @Override
    public String toString() {
        return "MailAttachment[contentType=" + contentType + ", size=" + size + ", skipReason=" + skipReason + "]";
    }
}
