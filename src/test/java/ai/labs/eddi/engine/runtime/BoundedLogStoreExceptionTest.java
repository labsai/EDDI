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
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * The throwable on a log record reaches the ring buffer, the live-tail
 * listeners and the database batch — redacted like the message.
 */
class BoundedLogStoreExceptionTest {

    private static final String KEY = "sk-ant-abcdefghijklmnopqrstuvwxyz01";

    private IDatabaseLogs databaseLogs;
    private BoundedLogStore store;

    @BeforeEach
    void setUp() {
        LogCaptureFilter.setStore(null);
        var ids = mock(InstanceIdProducer.class);
        when(ids.getInstanceId()).thenReturn("test-host");
        databaseLogs = mock(IDatabaseLogs.class);
        store = BoundedLogStore.createForTestingWithFrameCap(ids, databaseLogs, 50, true, 5, "WARN", 2);
    }

    @AfterEach
    void tearDown() {
        LogCaptureFilter.setStore(null);
    }

    private static ExtLogRecord record(Level level, String msg, Throwable thrown) {
        ExtLogRecord r = new ExtLogRecord(level, msg, FormatStyle.NO_FORMAT, BoundedLogStoreExceptionTest.class.getName());
        r.setLoggerName("test.logger");
        r.setThrown(thrown);
        return r;
    }

    private static Throwable failure() {
        Throwable cause = new IOException("socket closed");
        cause.setStackTrace(new StackTraceElement[]{new StackTraceElement("a.A", "a", "A.java", 1), new StackTraceElement("a.B", "b", "B.java", 2),
                new StackTraceElement("a.C", "c", "C.java", 3)});
        Throwable top = new IllegalStateException("API agent sync failed", cause);
        top.setStackTrace(new StackTraceElement[]{new StackTraceElement("x.X", "x", "X.java", 9)});
        return top;
    }

    @Test
    @DisplayName("capture stores the exception with its cause chain, capped at the configured frames")
    void capturesExceptionWithCauseChainAndFrameCap() {
        store.capture(record(Level.SEVERE, "sync failed", failure()));

        LogEntry entry = store.getEntries(null, null, null, 10).get(0);
        assertEquals("sync failed", entry.message());
        assertNotNull(entry.exception());
        assertTrue(entry.exception().startsWith("java.lang.IllegalStateException: API agent sync failed\n\tat x.X.x(X.java:9)"), entry.exception());
        assertTrue(
                entry.exception()
                        .contains("\nCaused by: java.io.IOException: socket closed\n\tat a.A.a(A.java:1)\n\tat a.B.b(B.java:2)\n\t... 1 more"),
                entry.exception());
        assertFalse(entry.exception().contains("a.C.c"), "the third frame is past the cap of 2");
    }

    @Test
    @DisplayName("a record without a throwable has no exception")
    void noThrowableNoException() {
        store.capture(record(Level.WARNING, "plain", null));

        assertNull(store.getEntries(null, null, null, 10).get(0).exception());
    }

    @Test
    @DisplayName("a secret in the exception message is redacted when capture formats the record itself")
    void secretRedactedOnTheRawPath() {
        store.capture(record(Level.SEVERE, "failed", new RuntimeException("calling provider with " + KEY)));

        String exception = store.getEntries(null, null, null, 10).get(0).exception();
        assertFalse(exception.contains(KEY), exception);
        assertTrue(exception.contains("sk-ant-<REDACTED>"), exception);
    }

    @Test
    @DisplayName("through the console filter the stored exception is redacted and escaped")
    void secretRedactedThroughTheFilter() {
        LogCaptureFilter.setStore(store);

        new LogCaptureFilter().isLoggable(record(Level.SEVERE, "failed", new RuntimeException("with " + KEY + "\r\nINFO forged")));

        String exception = store.getEntries(null, null, null, 10).get(0).exception();
        assertFalse(exception.contains(KEY), exception);
        assertFalse(exception.contains("\r"), exception);
        assertTrue(exception.contains("\\r\\nINFO forged"), exception);
    }

    @Test
    @DisplayName("the live-tail listener receives the exception")
    void listenerReceivesException() {
        List<LogEntry> received = new ArrayList<>();
        store.addListener(received::add);

        store.capture(record(Level.SEVERE, "boom", failure()));

        assertEquals(1, received.size());
        assertNotNull(received.get(0).exception());
    }

    @Test
    @DisplayName("the database batch carries the exception")
    @SuppressWarnings("unchecked")
    void databaseBatchCarriesException() {
        store.capture(record(Level.SEVERE, "boom", failure()));

        store.flushToDb();

        ArgumentCaptor<List<LogEntry>> batch = ArgumentCaptor.forClass(List.class);
        verify(databaseLogs, times(1)).addLogsBatch(batch.capture());
        assertTrue(batch.getValue().get(0).exception().contains("Caused by: java.io.IOException: socket closed"));
    }
}
