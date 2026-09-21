/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime;

import java.util.IdentityHashMap;
import java.util.function.UnaryOperator;

/**
 * A stand-in for a throwable whose message must be rewritten before it is
 * printed — because it carries credential material, or because it carries a CR
 * or LF that would forge a log record (CWE-117), or both.
 * <p>
 * {@link Throwable#getMessage()} is final and set at construction, so rewriting
 * it means substituting the object. Substituting it naively would print
 * {@code ai.labs.eddi…RedactedThrowable: …} and cost an operator the exception
 * type — the single most useful thing in the line. So this class reports the
 * ORIGINAL type name from {@link #toString()} and carries the original stack
 * trace, its causes and its suppressed exceptions, and only the message text
 * differs.
 * <p>
 * The rewrite is supplied by the caller rather than fixed here, so one walk of
 * the throwable graph applies every rule. Two passes — one substituting for
 * secrets, another substituting the substitute for record boundaries — would
 * walk the graph twice and nest one stand-in inside another for no gain.
 * <p>
 * Not serializable-compatible with the original by design: it exists to be
 * printed, not to be caught.
 */
final class RedactedThrowable extends Throwable {

    private static final long serialVersionUID = 1L;

    private final String originalTypeName;

    private RedactedThrowable(String originalTypeName, String rewrittenMessage, Throwable cause) {
        // Suppression enabled, because the original's suppressed exceptions are
        // copied over below and a printed trace shows them exactly as it shows a
        // cause. No writable stack trace: the trace is copied from the original,
        // and filling in this constructor's own would be noise.
        super(rewrittenMessage, cause, true, true);
        this.originalTypeName = originalTypeName;
    }

    /**
     * Builds a copy of {@code original} with {@code rewriteMessage} applied to
     * every message in its graph, including its cause chain and its suppressed
     * exceptions.
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
     *
     * @param rewriteMessage
     *            applied to each non-null message; must tolerate any string and
     *            must not return null for a non-null input
     */
    static Throwable of(Throwable original, UnaryOperator<String> rewriteMessage) {
        return copy(original, rewriteMessage, new IdentityHashMap<>());
    }

    private static Throwable copy(Throwable original, UnaryOperator<String> rewriteMessage,
                                  IdentityHashMap<Throwable, Boolean> seen) {
        if (original == null || seen.put(original, Boolean.TRUE) != null) {
            return null;
        }
        String message = original.getMessage();
        String rewritten = message == null ? null : rewriteMessage.apply(message);
        var copy = new RedactedThrowable(original.getClass().getName(), rewritten,
                copy(original.getCause(), rewriteMessage, seen));
        copy.setStackTrace(original.getStackTrace());
        for (Throwable suppressed : original.getSuppressed()) {
            Throwable suppressedCopy = copy(suppressed, rewriteMessage, seen);
            if (suppressedCopy != null) {
                copy.addSuppressed(suppressedCopy);
            }
        }
        return copy;
    }

    /**
     * Renders as the original type would, so a printed stack trace is unchanged
     * apart from the rewritten text.
     */
    @Override
    public String toString() {
        String message = getLocalizedMessage();
        return message == null ? originalTypeName : originalTypeName + ": " + message;
    }
}
