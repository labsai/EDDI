/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime;

import ai.labs.eddi.utils.LogSanitizer;

import java.util.IdentityHashMap;

/**
 * Renders a log record's throwable as the text kept in
 * {@link ai.labs.eddi.engine.model.LogEntry#exception()}.
 *
 * <p>
 * The text is the throwable's type and message, then the first
 * {@code maxFrames} stack frames, then the same for each {@code Caused by:}
 * down the cause chain. It is bounded twice — frames per throwable, and
 * {@value #MAX_CHARS} characters in all, with a marker when the cut is made —
 * so one log line cannot bloat the ring buffer or a database row.
 * </p>
 *
 * <p>
 * It gets the same treatment as the message: every throwable message goes
 * through {@link LogRecordRedactor#rewrite(String)} (secret redaction, then
 * record-boundary escaping), and the type and frame lines are boundary-escaped.
 * The lines are joined with real newlines, because that is the structure of a
 * stack trace; nothing taken from the throwable can add one. A raw throwable
 * message is never returned, and neither is anything when rendering fails.
 * </p>
 */
final class LogExceptionText {

    /**
     * Frames kept per throwable unless {@code eddi.logs.exception-max-frames} says
     * otherwise.
     */
    static final int DEFAULT_MAX_FRAMES = 10;

    /** Longest text returned, marker included. */
    static final int MAX_CHARS = 8 * 1024;

    static final String TRUNCATION_MARKER = "\n\t... [exception text truncated]";

    /** What stands in when rendering itself fails: never the raw throwable. */
    static final String RENDER_FAILED = "[exception text unavailable]";

    /** Cause-chain depth bound, for a pathologically deep chain. */
    private static final int MAX_CAUSES = 16;

    private LogExceptionText() {
    }

    /**
     * @return the redacted text, or {@code null} when {@code thrown} is null
     */
    static String render(Throwable thrown, int maxFrames) {
        if (thrown == null) {
            return null;
        }
        try {
            return renderUnchecked(thrown, Math.max(0, maxFrames));
        } catch (Exception _) {
            return RENDER_FAILED;
        }
    }

    private static String renderUnchecked(Throwable thrown, int maxFrames) {
        StringBuilder out = new StringBuilder();
        var seen = new IdentityHashMap<Throwable, Boolean>();
        Throwable current = thrown;
        for (int depth = 0; current != null && depth < MAX_CAUSES && seen.put(current, Boolean.TRUE) == null; depth++) {
            if (depth > 0) {
                out.append('\n').append("Caused by: ");
            }
            appendHeader(out, current);
            StackTraceElement[] frames = current.getStackTrace();
            int shown = Math.min(frames.length, maxFrames);
            for (int i = 0; i < shown; i++) {
                out.append('\n').append(LogSanitizer.escapeRecordBoundaries("\tat " + frames[i]));
            }
            if (frames.length > shown) {
                out.append('\n').append("\t... ").append(frames.length - shown).append(" more");
            }
            if (out.length() > MAX_CHARS) {
                break;
            }
            current = current.getCause();
        }
        return cap(out);
    }

    private static void appendHeader(StringBuilder out, Throwable t) {
        String type = t instanceof RedactedThrowable redacted ? redacted.originalTypeName() : t.getClass().getName();
        out.append(LogSanitizer.escapeRecordBoundaries(type));
        String message = t.getMessage();
        if (message != null) {
            out.append(": ").append(LogRecordRedactor.rewrite(message));
        }
    }

    private static String cap(StringBuilder text) {
        if (text.length() <= MAX_CHARS) {
            return text.toString();
        }
        int end = MAX_CHARS - TRUNCATION_MARKER.length();
        if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end) + TRUNCATION_MARKER;
    }
}
