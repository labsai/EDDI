/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.documents;

import java.util.Locale;
import java.util.Objects;

/**
 * A selector that cannot be resolved or a document whose structure cannot be
 * read. The message is <strong>model-safe</strong>: it names no storage
 * reference and quotes nothing from the document, so a tool result or an
 * {@code attachments:errors} note may carry it as is.
 *
 * @since 6.7.0
 */
public class SelectorException extends RuntimeException {

    /** Why a selector failed; {@link #tag()} is safe for a log line or a tag. */
    public enum Reason {
        /**
         * No store, or the conversation cannot read the file (the same answer for
         * both).
         */
        NOT_FOUND,
        /** A part was asked of a file that is not a stored PDF. */
        NOT_PDF,
        /** The page range is not of the form {@code <from>-<to>}. */
        BAD_PAGES,
        /** The page range lies outside the document. */
        PAGES_OUT_OF_RANGE,
        /** The unit label is malformed or the document has no such unit. */
        UNKNOWN_UNIT,
        /**
         * The unit label belongs to another split of the file and no pages came with
         * it.
         */
        FOREIGN_SPLIT,
        /** The PDF is encrypted, corrupt or too costly to read. */
        UNREADABLE;

        /** Lower-case name, for logs. */
        public String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private final Reason reason;

    public SelectorException(Reason reason, String message) {
        super(message);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public SelectorException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public Reason reason() {
        return reason;
    }
}
