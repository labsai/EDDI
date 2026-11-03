/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime;

import ai.labs.eddi.engine.model.LogEntry;
import io.quarkus.runtime.Startup;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Records logged before the {@link BoundedLogStore} exists are held (redacted)
 * by {@link LogCaptureFilter} and replayed once it registers; the store itself
 * is created at application start.
 */
class LogCaptureFilterPreStartTest {

    private static final String KEY = "sk-ant-aaaaaaaaaaaaaaaaaaaaaaaaaaa";

    private final LogCaptureFilter filter = new LogCaptureFilter();
    private BoundedLogStore store;

    @BeforeEach
    void setUp() {
        LogCaptureFilter.setStore(null); // also clears the pre-start buffer
        var ids = mock(InstanceIdProducer.class);
        when(ids.getInstanceId()).thenReturn("test-host");
        store = BoundedLogStore.createForTesting(ids, mock(IDatabaseLogs.class), 4000, false, 5, "WARN");
    }

    @AfterEach
    void tearDown() {
        LogCaptureFilter.setStore(null);
    }

    private ExtLogRecord record(Level level, String msg) {
        ExtLogRecord r = new ExtLogRecord(level, msg, FormatStyle.NO_FORMAT, getClass().getName());
        r.setLoggerName("test.logger");
        return r;
    }

    @Test
    @DisplayName("the store is created at startup, not on first injection")
    void storeIsEager() {
        assertNotNull(BoundedLogStore.class.getAnnotation(Startup.class), "without @Startup the store only exists after the log admin is first used");
    }

    @Test
    @DisplayName("records logged before the store exists are replayed in order, with their MDC")
    void replayedInOrder() {
        ExtLogRecord first = record(Level.WARNING, "first");
        first.putMdc("agentId", "agent-1");
        assertTrue(filter.isLoggable(first), "never suppresses");
        filter.isLoggable(record(Level.INFO, "second"));
        filter.isLoggable(record(Level.SEVERE, "third"));
        assertEquals(3, LogCaptureFilter.preStartSize());

        LogCaptureFilter.setStore(store);

        List<LogEntry> entries = store.getEntries(null, null, null, 10);
        assertEquals(3, entries.size());
        // getEntries is newest-first
        assertEquals("third", entries.get(0).message());
        assertEquals("second", entries.get(1).message());
        assertEquals("first", entries.get(2).message());
        assertEquals("agent-1", entries.get(2).agentId());
        assertEquals("WARNING", entries.get(2).level());
        assertEquals("test.logger", entries.get(2).loggerName());
        assertEquals(0, LogCaptureFilter.preStartSize(), "buffer is emptied by the replay");
    }

    @Test
    @DisplayName("the pre-start buffer is bounded: newest kept, drops counted and reported")
    void boundedDropsOldest() {
        int total = LogCaptureFilter.PRE_START_CAPACITY + 5;
        for (int i = 0; i < total; i++) {
            filter.isLoggable(record(Level.INFO, "m" + i));
        }
        assertEquals(LogCaptureFilter.PRE_START_CAPACITY, LogCaptureFilter.preStartSize());

        LogCaptureFilter.setStore(store);

        List<LogEntry> entries = store.getEntries(null, null, null, 4000);
        assertEquals(LogCaptureFilter.PRE_START_CAPACITY + 1, entries.size(), "kept records plus one drop notice");
        assertTrue(entries.stream().anyMatch(e -> e.message().equals("m" + (total - 1))), "newest kept");
        assertFalse(entries.stream().anyMatch(e -> e.message().equals("m0")), "oldest dropped");
        assertTrue(entries.stream().anyMatch(e -> e.level().equals("WARNING") && e.message().startsWith("5 log record(s)")),
                "the drop is reported");
    }

    @Test
    @DisplayName("buffered text is the redacted text, never the raw credential")
    void bufferedTextIsRedacted() {
        ExtLogRecord r = new ExtLogRecord(Level.WARNING, "token=%s", FormatStyle.PRINTF, getClass().getName());
        r.setParameters(new Object[]{KEY});
        filter.isLoggable(r);

        LogCaptureFilter.setStore(store);

        String stored = store.getEntries(null, null, null, 10).get(0).message();
        assertFalse(stored.contains(KEY), stored);
        assertTrue(stored.contains("sk-ant-<REDACTED>"), stored);
    }

    @Test
    @DisplayName("a record that fails redaction is not buffered raw and is still stripped for the console")
    void failedRedactionIsNotBuffered() {
        LogRecord broken = new LogRecord(Level.WARNING, "boom " + KEY) {
            @Override
            public String getMessage() {
                throw new IllegalStateException("redaction input unavailable");
            }
        };
        assertTrue(filter.isLoggable(broken), "never suppresses");
        assertEquals(0, LogCaptureFilter.preStartSize(), "nothing unredacted may be held");

        LogCaptureFilter.setStore(store);
        assertEquals(0, store.getEntries(null, null, null, 10).size());
    }

    @Test
    @DisplayName("once a store is registered records go straight to it, nothing is buffered")
    void liveAfterStore() {
        LogCaptureFilter.setStore(store);
        filter.isLoggable(record(Level.WARNING, "live"));
        assertEquals(0, LogCaptureFilter.preStartSize());
        assertEquals(1, store.getEntries(null, null, null, 10).size());
    }
}
