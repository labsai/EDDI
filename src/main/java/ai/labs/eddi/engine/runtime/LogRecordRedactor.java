/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime;

import ai.labs.eddi.secrets.sanitize.SecretRedactionFilter;
import org.jboss.logmanager.ExtLogRecord;

import java.text.MessageFormat;
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
 * majority of records — the cost on that path is one format and one regex
 * sweep, the same sweep {@code capture} already performed.
 */
final class LogRecordRedactor {

    private LogRecordRedactor() {
    }

    /**
     * Replaces the record's message and throwable with redacted equivalents where
     * redaction changed anything.
     *
     * @return whether the record was modified
     */
    static boolean redactInPlace(LogRecord record) {
        if (record == null) {
            return false;
        }
        boolean modified = redactMessage(record);
        return redactThrown(record) || modified;
    }

    /**
     * Resolves parameters first, then redacts.
     * <p>
     * A secret is far more often a {@code %s} argument than part of the format
     * string — {@code LOGGER.warnf("connecting to %s", url)} — so redacting
     * {@link LogRecord#getMessage()} alone would miss the case that matters.
     * Formatting is therefore collapsed into the message and the parameters are
     * dropped, which also stops a downstream formatter from re-applying them.
     */
    private static boolean redactMessage(LogRecord record) {
        String formatted = formatMessage(record);
        if (formatted == null || formatted.isEmpty()) {
            return false;
        }
        String redacted = SecretRedactionFilter.redact(formatted);
        if (redacted.equals(formatted)) {
            return false;
        }
        if (record instanceof ExtLogRecord extRecord) {
            // NO_FORMAT, because the text is already substituted: leaving the style
            // as PRINTF would have the console handler read a stray '%' in the
            // redacted text as a conversion and either mangle or drop the line.
            extRecord.setMessage(redacted, ExtLogRecord.FormatStyle.NO_FORMAT);
        } else {
            record.setMessage(redacted);
        }
        record.setParameters(null);
        return true;
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

    /** Whether any message in the cause chain changes under redaction. */
    private static boolean carriesSecret(Throwable thrown) {
        // Bounded: a self-referential or pathologically deep cause chain must not
        // turn a log line into a hang. Java's own printStackTrace bounds itself the
        // same way, by tracking what it has already seen.
        var seen = new IdentityHashMap<Throwable, Boolean>();
        for (Throwable current = thrown; current != null && seen.put(current, Boolean.TRUE) == null; current = current.getCause()) {
            String message = current.getMessage();
            if (message != null && !SecretRedactionFilter.redact(message).equals(message)) {
                return true;
            }
        }
        return false;
    }
}
