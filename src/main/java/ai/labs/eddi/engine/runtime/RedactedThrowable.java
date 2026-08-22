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
 * trace, its causes and its suppressed exceptions, and only the message text
 * differs.
 * <p>
 * Not serializable-compatible with the original by design: it exists to be
 * printed, not to be caught.
 */
final class RedactedThrowable extends Throwable {

    private static final long serialVersionUID = 1L;

    private final String originalTypeName;

    private RedactedThrowable(String originalTypeName, String redactedMessage, Throwable cause) {
        // Suppression enabled, because the original's suppressed exceptions are
        // copied over below and a printed trace shows them exactly as it shows a
        // cause. No writable stack trace: the trace is copied from the original,
        // and filling in this constructor's own would be noise.
        super(redactedMessage, cause, true, true);
        this.originalTypeName = originalTypeName;
    }

    /**
     * Builds a redacted copy of {@code original}, including its cause chain and its
     * suppressed exceptions.
     * <p>
     * Suppressed exceptions are part of what gets printed — try-with-resources on a
     * failed outbound call routinely attaches one carrying the resolved URL — so a
     * copy that dropped them would either lose the diagnostic or, worse, leave the
     * original in place because nothing had looked at it.
     * <p>
     * The graph is walked with one identity set so a self-referential or cyclic
     * chain — which is legal, and which real code produces — cannot turn one log
     * line into a stack overflow. A throwable reachable twice is copied once and
     * attached where it is first reached.
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
        for (Throwable suppressed : original.getSuppressed()) {
            Throwable suppressedCopy = copy(suppressed, seen);
            if (suppressedCopy != null) {
                copy.addSuppressed(suppressedCopy);
            }
        }
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
