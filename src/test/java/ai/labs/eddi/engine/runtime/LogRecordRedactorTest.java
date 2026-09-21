/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime;

import org.jboss.logmanager.ExtLogRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import java.net.ConnectException;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavioural tests for {@link LogRecordRedactor}.
 * <p>
 * Each asserts on the text a console handler would print, because the defect
 * being fixed was precisely that the console saw something the ring buffer did
 * not.
 */
class LogRecordRedactorTest {

    private static final String KEY = "sk-ant-abcdefghijklmnopqrstuvwxyz01";

    private static ExtLogRecord extRecord(String message) {
        return new ExtLogRecord(Level.WARNING, message, LogRecordRedactorTest.class.getName());
    }

    private static ExtLogRecord printfRecord(String message) {
        return new ExtLogRecord(Level.WARNING, message, ExtLogRecord.FormatStyle.PRINTF, LogRecordRedactorTest.class.getName());
    }

    @Test
    @DisplayName("a secret in the message text is redacted in place")
    void redactsMessageText() {
        ExtLogRecord record = extRecord("calling provider with " + KEY);

        assertTrue(LogRecordRedactor.redactInPlace(record));

        assertFalse(record.getFormattedMessage().contains(KEY), "the console must not see the key");
        assertTrue(record.getFormattedMessage().contains("sk-ant-<REDACTED>"), "the kind of credential stays visible");
    }

    @Test
    @DisplayName("a secret passed as a format PARAMETER is redacted — the common shape")
    void redactsFormatParameter() {
        ExtLogRecord record = printfRecord("connecting to %s");
        record.setParameters(new Object[]{"https://api.example.com?token=" + KEY});

        assertTrue(LogRecordRedactor.redactInPlace(record));

        assertFalse(record.getFormattedMessage().contains(KEY));
        assertNull(record.getParameters(), "parameters are collapsed so nothing can re-apply them");
    }

    @Test
    @DisplayName("a percent sign surviving redaction is not re-read as a conversion")
    void doesNotReformatRedactedText() {
        ExtLogRecord record = printfRecord("progress %s");
        record.setParameters(new Object[]{"100% done with " + KEY});

        LogRecordRedactor.redactInPlace(record);

        assertTrue(record.getFormattedMessage().contains("100% done"), "the literal percent survives verbatim");
    }

    @Test
    @DisplayName("a clean record is left untouched")
    void leavesCleanRecordsAlone() {
        ExtLogRecord record = extRecord("started listening on port 7070");

        assertFalse(LogRecordRedactor.redactInPlace(record));
        assertEquals("started listening on port 7070", record.getFormattedMessage());
    }

    @Test
    @DisplayName("a throwable message carrying a credentialed URL is replaced, keeping its type and trace")
    void redactsThrowableMessage() {
        ExtLogRecord record = extRecord("MCP handshake failed");
        var original = new ConnectException("failed to connect to https://mcp.example.com?apiKey=" + KEY);
        record.setThrown(original);

        assertTrue(LogRecordRedactor.redactInPlace(record));

        Throwable redacted = record.getThrown();
        assertFalse(redacted.toString().contains(KEY), "the console stack trace must not carry the key");
        assertTrue(redacted.toString().startsWith("java.net.ConnectException"), "the exception type is what an operator needs and must survive");
        assertEquals(original.getStackTrace().length, redacted.getStackTrace().length, "the stack trace is preserved");
    }

    @Test
    @DisplayName("a cause chain is redacted too")
    void redactsCauseChain() {
        ExtLogRecord record = extRecord("outbound call failed");
        var cause = new IllegalStateException("token=" + KEY);
        record.setThrown(new RuntimeException("wrapper", cause));

        assertTrue(LogRecordRedactor.redactInPlace(record));

        assertFalse(record.getThrown().getCause().toString().contains(KEY));
        assertTrue(record.getThrown().getCause().toString().startsWith("java.lang.IllegalStateException"));
    }

    @Test
    @DisplayName("a secret in a SUPPRESSED exception is redacted, and the suppressed graph survives")
    void redactsSuppressedExceptions() {
        // try-with-resources around a failed outbound call attaches the close
        // failure as a suppressed exception, and printStackTrace prints it exactly
        // like a cause. Scanning the cause chain alone left that one on the console.
        ExtLogRecord record = extRecord("outbound call failed");
        var thrown = new IllegalStateException("request aborted");
        thrown.addSuppressed(new IllegalStateException("closing https://api.example.com?apiKey=" + KEY));
        record.setThrown(thrown);

        assertTrue(LogRecordRedactor.redactInPlace(record));

        var printed = new StringWriter();
        record.getThrown().printStackTrace(new PrintWriter(printed));
        assertFalse(printed.toString().contains(KEY), "the printed trace must not carry the key: " + printed);
        assertEquals(1, record.getThrown().getSuppressed().length,
                "and the suppressed exception must still be there — dropping it would trade one loss for another");
        assertTrue(record.getThrown().getSuppressed()[0].toString().startsWith("java.lang.IllegalStateException"),
                "with the type an operator needs: " + record.getThrown().getSuppressed()[0]);
    }

    @Test
    @DisplayName("a suppressed exception that points back at its own thrower terminates")
    void toleratesCyclicSuppression() {
        var outer = new RuntimeException("outer " + KEY);
        var inner = new RuntimeException("inner");
        outer.addSuppressed(inner);
        inner.addSuppressed(outer);

        ExtLogRecord record = extRecord("boom");
        record.setThrown(outer);

        assertTrue(LogRecordRedactor.redactInPlace(record));
        assertFalse(record.getThrown().toString().contains(KEY));
    }

    @Test
    @DisplayName("a clean throwable is not substituted at all")
    void leavesCleanThrowableAlone() {
        ExtLogRecord record = extRecord("task failed");
        var original = new IllegalArgumentException("version must be positive");
        record.setThrown(original);

        assertFalse(LogRecordRedactor.redactInPlace(record));
        assertSame(original, record.getThrown(), "no substitution means no loss of type, suppressed exceptions or identity");
    }

    @Test
    @DisplayName("a cyclic cause chain terminates")
    void toleratesCyclicCauseChain() {
        var first = new RuntimeException("outer " + KEY);
        var second = new RuntimeException("inner", first);
        first.initCause(second);

        ExtLogRecord record = extRecord("boom");
        record.setThrown(first);

        assertTrue(LogRecordRedactor.redactInPlace(record));
        assertFalse(record.getThrown().toString().contains(KEY));
    }

    @Test
    @DisplayName("a plain JUL record is handled without ExtLogRecord")
    void handlesPlainLogRecord() {
        var record = new LogRecord(Level.INFO, "using key {0}");
        record.setParameters(new Object[]{KEY});

        assertTrue(LogRecordRedactor.redactInPlace(record));

        assertFalse(record.getMessage().contains(KEY));
        assertNull(record.getParameters());
    }

    @Test
    @DisplayName("a null record is ignored rather than thrown on")
    void toleratesNull() {
        assertFalse(LogRecordRedactor.redactInPlace(null));
    }
}
