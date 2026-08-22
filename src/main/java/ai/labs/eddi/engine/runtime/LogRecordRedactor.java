/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime;

import ai.labs.eddi.secrets.sanitize.SecretRedactionFilter;
import org.jboss.logmanager.ExtLogRecord;

import java.text.MessageFormat;
import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.logging.LogRecord;

/**
 * Redacts a {@link LogRecord} in place, so what reaches container stdout is
 * what reaches the ring buffer.
 * <p>
 * Redaction used to happen on a <em>copy</em> inside
 * {@link BoundedLogStore#capture(LogRecord)}: the ring buffer, the database and
 * the SSE stream were clean, and the console — the one destination an operator
 * cannot revoke access to after the fact, and the one a log shipper forwards
 * verbatim — was not. A failed MCP handshake or outbound HTTP call logs its
 * throwable, and that throwable's message routinely carries the resolved URL,
 * which with a templated credential in it <em>is</em> the credential.
 * <p>
 * Mutating the record rather than adding a console formatter keeps one
 * definition of "redacted" for every destination: a second, differently
 * effective scheme for the same data is how the two drifted apart in the first
 * place.
 * <p>
 * The record is left untouched when nothing matched, which is the overwhelming
 * majority of records — the cost on that path is one format and one pass of the
 * rules, and {@link Redaction} carries the result on to
 * {@link BoundedLogStore#capture} so that pass happens once per record rather
 * than twice.
 */
final class LogRecordRedactor {

    /** Stands in for a message that could not even be scanned. */
    private static final String REDACTION_FAILED = "<REDACTED: log redaction failed>";

    private LogRecordRedactor() {
    }

    /**
     * What one pass produced: the record's formatted message after redaction — the
     * text a console handler will print — and whether the record was changed.
     * <p>
     * The message is handed back so {@link BoundedLogStore#capture} need not format
     * and redact the same record all over again. That second pass could never
     * change anything the first had already done, and it ran the whole rule set a
     * second time on the thread emitting the log line.
     *
     * @param message
     *            the redacted message, or {@code null} when this class cannot
     *            resolve the record's parameters as faithfully as {@code capture}
     *            would — see {@link #reusableMessage}
     * @param modified
     *            whether the record itself was rewritten
     */
    record Redaction(String message, boolean modified) {
    }

    /**
     * Replaces the record's message and throwable with redacted equivalents where
     * redaction changed anything.
     *
     * @return whether the record was modified
     */
    static boolean redactInPlace(LogRecord record) {
        return redact(record).modified();
    }

    /**
     * Redacts the record in place and reports what a downstream consumer needs in
     * order not to repeat the work.
     * <p>
     * Parameters are resolved first, then redacted. A secret is far more often a
     * {@code %s} argument than part of the format string —
     * {@code LOGGER.warnf("connecting to %s", url)} — so redacting
     * {@link LogRecord#getMessage()} alone would miss the case that matters.
     * Formatting is therefore collapsed into the message and the parameters are
     * dropped, which also stops a downstream formatter from re-applying them.
     */
    static Redaction redact(LogRecord record) {
        if (record == null) {
            return new Redaction(null, false);
        }
        String formatted = formatMessage(record);
        String redacted = formatted == null || formatted.isEmpty() ? formatted : SecretRedactionFilter.redact(formatted);
        boolean messageModified = redacted != null && !redacted.equals(formatted);
        if (messageModified) {
            setMessage(record, redacted);
            record.setParameters(null);
        }
        boolean modified = redactThrown(record) || messageModified;
        return new Redaction(reusableMessage(record, redacted, messageModified), modified);
    }

    /**
     * Strips a record whose redaction did not complete, so the console handler
     * formatting it next cannot print what the failed pass never removed.
     * <p>
     * A pass that threw leaves the record exactly as it arrived. Only the STORED
     * copy was protected — {@link BoundedLogStore#capture} redacts its own text
     * when it is handed none — while the console, the one destination an operator
     * cannot revoke after the fact, printed the record itself. So "redaction threw"
     * and "the credential printed" were the same event.
     * <p>
     * The line is worth keeping and the credential is not. The message is scanned
     * in its RAW form (its parameters unresolved — resolving them is the step most
     * likely to have thrown), the parameters are dropped so no formatter can
     * substitute them back in, and the throwable is replaced by a redacted copy, or
     * removed outright when even that cannot be produced.
     */
    static void failClosed(LogRecord record) {
        if (record == null) {
            return;
        }
        String message = safeMessage(record);
        if (message != null) {
            setMessage(record, message);
        }
        record.setParameters(null);
        record.setThrown(safeThrowable(record.getThrown()));
    }

    /**
     * What a failed record may still say: its raw message, redacted, or a marker
     * when that scan fails too.
     */
    private static String safeMessage(LogRecord record) {
        try {
            String raw = record.getMessage();
            return raw == null ? null : SecretRedactionFilter.redact(raw);
        } catch (Exception _) {
            return REDACTION_FAILED;
        }
    }

    /** A printable stand-in for a throwable nothing has vouched for. */
    private static Throwable safeThrowable(Throwable thrown) {
        if (thrown == null) {
            return null;
        }
        try {
            return RedactedThrowable.of(thrown);
        } catch (Exception _) {
            // No copy could be produced, so nothing about this throwable is known
            // to be safe to print. A dropped stack trace costs diagnostics; a
            // printed credential cannot be taken back.
            return null;
        }
    }

    /** Replaces a record's message in whichever form the record understands. */
    private static void setMessage(LogRecord record, String message) {
        if (record instanceof ExtLogRecord extRecord) {
            // NO_FORMAT, because the text is already substituted: leaving the
            // style as PRINTF would have the console handler read a stray '%'
            // in the redacted text as a conversion and either mangle or drop
            // the line.
            extRecord.setMessage(message, ExtLogRecord.FormatStyle.NO_FORMAT);
        } else {
            record.setMessage(message);
        }
    }

    /**
     * The redacted message, but only where it is what
     * {@link BoundedLogStore#capture} would itself have formatted.
     * <p>
     * It is, but for one shape: a plain JUL record still carrying parameters.
     * {@link #formatMessage} resolves those with {@link MessageFormat} alone while
     * capture tries printf first, so handing that one over would drop a {@code %s}
     * argument from the ring buffer — a log line quietly losing the value it was
     * emitted to report. A record whose message WAS redacted has had its parameters
     * collapsed already, so it is never that shape.
     */
    private static String reusableMessage(LogRecord record, String redacted, boolean messageModified) {
        if (messageModified || record instanceof ExtLogRecord) {
            return redacted;
        }
        Object[] parameters = record.getParameters();
        return parameters == null || parameters.length == 0 ? redacted : null;
    }

    /** The record's message with its parameters applied, or null. */
    private static String formatMessage(LogRecord record) {
        if (record instanceof ExtLogRecord extRecord) {
            return extRecord.getFormattedMessage();
        }
        String message = record.getMessage();
        Object[] parameters = record.getParameters();
        if (message == null || parameters == null || parameters.length == 0) {
            return message;
        }
        try {
            return MessageFormat.format(message, parameters);
        } catch (RuntimeException _) {
            // An unformattable message is still worth scanning in its raw form.
            return message;
        }
    }

    /**
     * Replaces a throwable whose message chain carries a secret.
     * <p>
     * A {@link Throwable}'s message is final, so the only way to redact it is to
     * substitute an object. {@link RedactedThrowable} keeps the original type name
     * and stack trace so the substitution costs no diagnostic value — the printed
     * line still reads {@code java.net.ConnectException: …}, just without the
     * credential the URL in it carried.
     */
    private static boolean redactThrown(LogRecord record) {
        Throwable thrown = record.getThrown();
        if (thrown == null || !carriesSecret(thrown)) {
            return false;
        }
        record.setThrown(RedactedThrowable.of(thrown));
        return true;
    }

    /**
     * Whether any message in the throwable's graph changes under redaction.
     * <p>
     * The graph, not the cause chain: {@link Throwable#getSuppressed()} is printed
     * by {@code printStackTrace} exactly like a cause, so a secret in a suppressed
     * exception reached the console whenever the chain itself happened to be clean
     * — and try-with-resources on a failed outbound call is precisely where a
     * suppressed exception carrying the resolved URL comes from.
     */
    private static boolean carriesSecret(Throwable thrown) {
        // Bounded: a self-referential or pathologically deep graph must not turn a
        // log line into a hang. Java's own printStackTrace bounds itself the same
        // way, by tracking what it has already seen.
        var seen = new IdentityHashMap<Throwable, Boolean>();
        // ArrayDeque rejects null, so nothing null is ever offered to it.
        var pending = new ArrayDeque<Throwable>();
        pending.push(thrown);
        while (!pending.isEmpty()) {
            Throwable current = pending.pop();
            if (seen.put(current, Boolean.TRUE) != null) {
                continue;
            }
            String message = current.getMessage();
            if (message != null && !SecretRedactionFilter.redact(message).equals(message)) {
                return true;
            }
            if (current.getCause() != null) {
                pending.push(current.getCause());
            }
            for (Throwable suppressed : current.getSuppressed()) {
                pending.push(suppressed);
            }
        }
        return false;
    }
}
