/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime;

import ai.labs.eddi.secrets.sanitize.SecretRedactionFilter;

import java.util.IdentityHashMap;

/**
 * A stand-in for a throwable whose message carries credential material.
 * <p>
 * {@link Throwable#getMessage()} is final and set at construction, so redacting
 * it means substituting the object. Substituting it naively would print
 * {@code ai.labs.eddi…RedactedThrowable: …} and cost an operator the exception
 * type — the single most useful thing in the line. So this class reports the
 * ORIGINAL type name from {@link #toString()} and carries the original stack
 * trace, and only the message text differs.
 * <p>
 * Not serializable-compatible with the original by design: it exists to be
 * printed, not to be caught.
 */
final class RedactedThrowable extends Throwable {

    private static final long serialVersionUID = 1L;

    private final String originalTypeName;

    private RedactedThrowable(String originalTypeName, String redactedMessage, Throwable cause) {
        // No suppression, no writable stack trace: the trace is copied from the
        // original below, and filling in this constructor's own would be noise.
        super(redactedMessage, cause, false, true);
        this.originalTypeName = originalTypeName;
    }

    /**
     * Builds a redacted copy of {@code original}, including its cause chain.
     * <p>
     * The chain is walked with an identity set so a self-referential or cyclic
     * chain — which is legal, and which real code produces — cannot turn one log
     * line into a stack overflow.
     */
    static Throwable of(Throwable original) {
        return copy(original, new IdentityHashMap<>());
    }

    private static Throwable copy(Throwable original, IdentityHashMap<Throwable, Boolean> seen) {
        if (original == null || seen.put(original, Boolean.TRUE) != null) {
            return null;
        }
        String message = original.getMessage();
        String redacted = message == null ? null : SecretRedactionFilter.redact(message);
        var copy = new RedactedThrowable(original.getClass().getName(), redacted, copy(original.getCause(), seen));
        copy.setStackTrace(original.getStackTrace());
        return copy;
    }

    /**
     * Renders as the original type would, so a printed stack trace is unchanged
     * apart from the redacted text.
     */
    @Override
    public String toString() {
        String message = getLocalizedMessage();
        return message == null ? originalTypeName : originalTypeName + ": " + message;
    }
}
