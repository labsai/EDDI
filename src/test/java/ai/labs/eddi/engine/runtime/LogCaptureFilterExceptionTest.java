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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Records logged before the store exists keep their (redacted) exception text.
 */
class LogCaptureFilterExceptionTest {

    private static final String KEY = "sk-ant-abcdefghijklmnopqrstuvwxyz01";

    private final LogCaptureFilter filter = new LogCaptureFilter();
    private BoundedLogStore store;

    @BeforeEach
    void setUp() {
        LogCaptureFilter.setStore(null);
        var ids = mock(InstanceIdProducer.class);
        when(ids.getInstanceId()).thenReturn("test-host");
        store = BoundedLogStore.createForTesting(ids, mock(IDatabaseLogs.class), 100, false, 5, "WARN");
    }

    @AfterEach
    void tearDown() {
        LogCaptureFilter.setStore(null);
    }

    private static ExtLogRecord record(String msg, Throwable thrown) {
        ExtLogRecord r = new ExtLogRecord(Level.SEVERE, msg, FormatStyle.NO_FORMAT, LogCaptureFilterExceptionTest.class.getName());
        r.setLoggerName("test.logger");
        r.setThrown(thrown);
        return r;
    }

    @Test
    @DisplayName("a pre-start record keeps its exception through the replay, redacted")
    void preStartExceptionReplayed() {
        filter.isLoggable(record("early failure", new RuntimeException("calling with " + KEY, new IllegalStateException("root"))));
        filter.isLoggable(record("early plain", null));

        LogCaptureFilter.setStore(store);

        List<LogEntry> entries = store.getEntries(null, null, null, 10); // newest first
        assertEquals("early plain", entries.get(0).message());
        assertNull(entries.get(0).exception());
        String exception = entries.get(1).exception();
        assertNotNull(exception, "the cause was dropped on replay");
        assertTrue(exception.contains("sk-ant-<REDACTED>"), exception);
        assertFalse(exception.contains(KEY), exception);
        assertTrue(exception.contains("Caused by: java.lang.IllegalStateException: root"), exception);
    }

    @Test
    @DisplayName("a pre-start record with no message but a throwable is replayed; one with neither is not")
    void preStartEmptyMessageWithThrowableReplayed() {
        filter.isLoggable(record("", new RuntimeException("boom")));
        filter.isLoggable(record("", null));

        LogCaptureFilter.setStore(store);

        List<LogEntry> entries = store.getEntries(null, null, null, 10);
        assertEquals(1, entries.size(), entries.toString());
        assertEquals("", entries.get(0).message());
        assertTrue(entries.get(0).exception().contains("boom"), entries.get(0).exception());
    }
}
