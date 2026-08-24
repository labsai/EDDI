/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime;

import ai.labs.eddi.engine.model.LogEntry;
import org.jboss.logmanager.ExtLogRecord;
import org.jboss.logmanager.ExtLogRecord.FormatStyle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The handoff between {@link LogCaptureFilter} and
 * {@link BoundedLogStore#capture(LogRecord, String)}.
 * <p>
 * Redaction moved into the filter so that container stdout — the one
 * destination an operator cannot revoke after the fact — sees what the ring
 * buffer sees. Once it did, the filter could carry its result on to
 * {@code capture} rather than have the whole rule set run a second time on the
 * thread emitting the line. These tests pin what that handoff has to preserve:
 * the message the buffer keeps must be redacted AND faithfully formatted, and
 * {@code capture}'s own redaction has to remain for the record the filter could
 * not process.
 */
class LogCaptureFilterRedactionTest {

    /** Zero-entropy but shape-correct, so no live key literal enters the tree. */
    private static final String KEY = "sk-ant-aaaaaaaaaaaaaaaaaaaaaaaaaaa";

    private static final String CREDENTIALLED_URL = "https://api.example.com/v1?token=" + KEY;

    private final LogCaptureFilter filter = new LogCaptureFilter();

    private BoundedLogStore store;

    @BeforeEach
    void setUp() {
        var instanceIdProducer = mock(InstanceIdProducer.class);
        when(instanceIdProducer.getInstanceId()).thenReturn("test-host-abcd");
        store = BoundedLogStore.createForTesting(instanceIdProducer, mock(IDatabaseLogs.class), 16, false, 5, "WARN");
        LogCaptureFilter.setStore(store);
    }

    @AfterEach
    void tearDown() {
        // The filter reaches its store through a static, because Quarkus builds it
        // before the CDI container is ready. Leaving this test's store behind would
        // feed unrelated records into a buffer nobody reads.
        LogCaptureFilter.setStore(null);
    }

    /** The single record the filter should have handed to the store. */
    private String storedMessage() {
        List<LogEntry> entries = store.getEntries(null, null, null, 10);
        assertEquals(1, entries.size(), "exactly one record should have reached the ring buffer, got " + entries);
        return entries.get(0).message();
    }

    @Test
    @DisplayName("the message the filter redacts in place is the one the ring buffer stores")
    void theRingBufferKeepsTheFiltersRedactedMessage() {
        ExtLogRecord record = new ExtLogRecord(Level.WARNING, "connecting to %s", FormatStyle.PRINTF, getClass().getName());
        record.setParameters(new Object[]{CREDENTIALLED_URL});

        assertTrue(filter.isLoggable(record), "the filter must never suppress a record");

        String stored = storedMessage();
        assertFalse(stored.contains(KEY), "the ring buffer must not carry the key: " + stored);
        assertTrue(stored.contains("sk-ant-<REDACTED>"), "the kind of credential stays visible: " + stored);
        assertTrue(stored.contains("connecting to https://api.example.com/v1?token="),
                "and the line keeps its parameter, rather than a raw '%s' the redactor never resolved: " + stored);
        assertFalse(record.getFormattedMessage().contains(KEY),
                "the console handler formats the same record afterwards and must see the same redacted text");
    }

    @Test
    @DisplayName("a clean printf record still reaches the buffer with its parameter applied")
    void doesNotHandOverAMessageItCouldNotFormatFaithfully() {
        // A plain JUL record is resolved with MessageFormat by the redactor and
        // printf-first by the store. Handing the redactor's version over for a
        // record it did NOT modify would drop the %s argument — a log line quietly
        // losing the value it was emitted to report. Nothing here is secret-shaped,
        // so redaction changes nothing and this shape is reached.
        var record = new LogRecord(Level.INFO, "listening on %s");
        record.setParameters(new Object[]{"port 7070"});

        assertTrue(filter.isLoggable(record));

        assertEquals("listening on port 7070", storedMessage());
    }

    @Test
    @DisplayName("when in-place redaction throws, the store still redacts before it keeps the line")
    void fallsBackToTheStoresOwnRedaction() {
        // This is why capture()'s own redaction could not simply be deleted when
        // the filter began handing its result over. A record the filter cannot
        // process arrives with a null message, and the line must still be scrubbed
        // — published unredacted would be a leak, dropped would be a lost log.
        var record = new UnformattableRecord("calling %s");
        record.setParameters(new Object[]{CREDENTIALLED_URL});

        assertTrue(filter.isLoggable(record), "a redaction failure must never suppress a log line");

        String stored = storedMessage();
        assertFalse(stored.contains(KEY), "the ring buffer must not carry the key: " + stored);
        assertTrue(stored.contains("sk-ant-<REDACTED>"), "the fallback must redact, not merely drop the record: " + stored);
        assertTrue(stored.contains("calling https://api.example.com/v1?token="),
                "and the store must have formatted the record itself: " + stored);
    }

    @Test
    @DisplayName("when in-place redaction throws, the record the CONSOLE formats carries nothing")
    void failsClosedForTheConsoleWhenRedactionThrows() {
        // The store's own fallback protected only the copy it keeps. The console
        // handler formats this same record moments later, and a record left
        // exactly as it arrived is a record that prints the credential — so a
        // redaction failure and a leak were the same event.
        var record = new UnformattableRecord("token=" + KEY + " while calling %s");
        record.setParameters(new Object[]{CREDENTIALLED_URL});
        record.setThrown(new IllegalStateException("connect failed for " + CREDENTIALLED_URL));

        assertTrue(filter.isLoggable(record), "a redaction failure must never suppress a log line");

        assertFalse(record.getMessage().contains(KEY), "the console must not see the key: " + record.getMessage());
        assertNull(record.getParameters(), "and must not be able to substitute it back in");
        assertFalse(String.valueOf(record.getThrown()).contains(KEY),
                "the throwable is printed with the line and must be redacted too: " + record.getThrown());
    }

    /**
     * A record whose formatting blows up, so {@code LogRecordRedactor} cannot
     * complete and the filter falls through to its catch. The store recovers by
     * formatting from the raw message and parameters instead.
     */
    private static final class UnformattableRecord extends ExtLogRecord {

        UnformattableRecord(String message) {
            super(Level.WARNING, message, FormatStyle.PRINTF, LogCaptureFilterRedactionTest.class.getName());
        }

        @Override
        public String getFormattedMessage() {
            throw new IllegalStateException("formatting failed");
        }
    }
}
