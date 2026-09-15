/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime;

import ai.labs.eddi.engine.model.LogEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import org.jboss.logmanager.ExtLogRecord;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link BoundedLogStore}.
 */
class BoundedLogStoreTest {

    private InstanceIdProducer instanceIdProducer;
    private IDatabaseLogs databaseLogs;
    private BoundedLogStore store;

    @BeforeEach
    void setUp() {
        instanceIdProducer = mock(InstanceIdProducer.class);
        when(instanceIdProducer.getInstanceId()).thenReturn("test-host-abcd");
        databaseLogs = mock(IDatabaseLogs.class);
        // Create a small buffer for testing, DB disabled by default
        store = BoundedLogStore.createForTesting(instanceIdProducer, databaseLogs, 5, false, 5, "WARN");
    }

    private LogEntry createEntry(String level, String agentId, String conversationId, String message) {
        return new LogEntry(System.currentTimeMillis(), level, "test.Logger", message, "production", agentId, 1, conversationId, "user-1",
                "test-host-abcd");
    }

    // ==================== Ring Buffer ====================

    @Nested
    class RingBuffer {

        @Test
        void shouldStoreAndRetrieveEntries() {
            store.publish(createEntry("INFO", "agent-1", "conv-1", "Hello"));
            store.publish(createEntry("WARN", "agent-1", "conv-1", "Warning"));

            List<LogEntry> entries = store.getEntries(null, null, null, 10);

            assertEquals(2, entries.size());
            assertEquals(2, store.getBufferSize());
        }

        @Test
        void shouldReturnEntriesNewestFirst() {
            store.publish(createEntry("INFO", null, null, "First"));
            store.publish(createEntry("INFO", null, null, "Second"));
            store.publish(createEntry("INFO", null, null, "Third"));

            List<LogEntry> entries = store.getEntries(null, null, null, 10);

            assertEquals("Third", entries.get(0).message());
            assertEquals("Second", entries.get(1).message());
            assertEquals("First", entries.get(2).message());
        }

        @Test
        void shouldEvictOldestWhenBufferFull() {
            // Buffer size is 5
            for (int i = 1; i <= 7; i++) {
                store.publish(createEntry("INFO", null, null, "msg-" + i));
            }

            assertEquals(5, store.getBufferSize());
            List<LogEntry> entries = store.getEntries(null, null, null, 10);
            // Newest first: msg-7, msg-6, msg-5, msg-4, msg-3 (msg-1, msg-2 evicted)
            assertEquals("msg-7", entries.get(0).message());
            assertEquals("msg-3", entries.get(4).message());
        }

        @Test
        void shouldReturnEmptyListForEmptyBuffer() {
            List<LogEntry> entries = store.getEntries(null, null, null, 10);
            assertTrue(entries.isEmpty());
        }

        @Test
        void shouldRespectLimit() {
            for (int i = 0; i < 5; i++) {
                store.publish(createEntry("INFO", null, null, "msg-" + i));
            }

            List<LogEntry> entries = store.getEntries(null, null, null, 2);

            assertEquals(2, entries.size());
        }
    }

    // ==================== Filtering ====================

    @Nested
    class Filtering {

        @Test
        void shouldFilterByAgentId() {
            store.publish(createEntry("INFO", "agent-a", null, "from A"));
            store.publish(createEntry("INFO", "agent-b", null, "from B"));
            store.publish(createEntry("INFO", "agent-a", null, "from A again"));

            List<LogEntry> entries = store.getEntries("agent-a", null, null, 10);

            assertEquals(2, entries.size());
            assertTrue(entries.stream().allMatch(e -> "agent-a".equals(e.agentId())));
        }

        @Test
        void shouldFilterByConversationId() {
            store.publish(createEntry("INFO", null, "conv-1", "msg 1"));
            store.publish(createEntry("INFO", null, "conv-2", "msg 2"));
            store.publish(createEntry("INFO", null, "conv-1", "msg 3"));

            List<LogEntry> entries = store.getEntries(null, "conv-1", null, 10);

            assertEquals(2, entries.size());
            assertTrue(entries.stream().allMatch(e -> "conv-1".equals(e.conversationId())));
        }

        @Test
        void shouldFilterByMinimumLevel() {
            store.publish(createEntry("DEBUG", null, null, "debug"));
            store.publish(createEntry("INFO", null, null, "info"));
            store.publish(createEntry("WARN", null, null, "warn"));
            store.publish(createEntry("ERROR", null, null, "error"));

            // Minimum-level semantics: WARN returns WARN + ERROR
            List<LogEntry> entries = store.getEntries(null, null, "WARN", 10);

            assertEquals(2, entries.size());
            assertTrue(entries.stream().allMatch(e -> "WARN".equals(e.level()) || "ERROR".equals(e.level())));
        }

        @Test
        void shouldFilterByErrorLevelOnly() {
            store.publish(createEntry("INFO", null, null, "info"));
            store.publish(createEntry("WARN", null, null, "warn"));
            store.publish(createEntry("ERROR", null, null, "error"));

            List<LogEntry> entries = store.getEntries(null, null, "ERROR", 10);

            assertEquals(1, entries.size());
            assertEquals("ERROR", entries.get(0).level());
        }

        @Test
        void shouldCombineFilters() {
            store.publish(createEntry("DEBUG", "agent-a", "conv-1", "below-threshold"));
            store.publish(createEntry("WARN", "agent-a", "conv-1", "match-warn"));
            store.publish(createEntry("ERROR", "agent-a", "conv-1", "match-error"));
            store.publish(createEntry("WARN", "agent-b", "conv-1", "wrong-agent"));

            // level=WARN + agentId=agent-a + conversationId=conv-1 → match-warn +
            // match-error
            List<LogEntry> entries = store.getEntries("agent-a", "conv-1", "WARN", 10);

            assertEquals(2, entries.size());
            assertTrue(entries.stream().allMatch(e -> "agent-a".equals(e.agentId())));
        }
    }

    // ==================== Minimum Level Check ====================

    @Nested
    class MinimumLevelCheck {

        @Test
        void shouldReturnTrueWhenEntryLevelAboveThreshold() {
            assertTrue(store.meetsMinimumLevel("ERROR", "WARN"));
            assertTrue(store.meetsMinimumLevel("ERROR", "INFO"));
            assertTrue(store.meetsMinimumLevel("WARN", "DEBUG"));
        }

        @Test
        void shouldReturnTrueWhenEntryLevelEqualsThreshold() {
            assertTrue(store.meetsMinimumLevel("WARN", "WARN"));
            assertTrue(store.meetsMinimumLevel("ERROR", "ERROR"));
            assertTrue(store.meetsMinimumLevel("INFO", "INFO"));
        }

        @Test
        void shouldReturnFalseWhenEntryLevelBelowThreshold() {
            assertFalse(store.meetsMinimumLevel("DEBUG", "INFO"));
            assertFalse(store.meetsMinimumLevel("INFO", "WARN"));
            assertFalse(store.meetsMinimumLevel("WARN", "ERROR"));
        }

        @Test
        void shouldTreatUnknownLevelAsLowest() {
            assertTrue(store.meetsMinimumLevel("ERROR", "UNKNOWN"));
            assertFalse(store.meetsMinimumLevel("UNKNOWN", "ERROR"));
        }
    }

    // ==================== SSE Listeners ====================

    @Nested
    class Listeners {

        @Test
        void shouldNotifyListenersOnPublish() throws InterruptedException {
            List<LogEntry> received = Collections.synchronizedList(new ArrayList<>());
            CountDownLatch latch = new CountDownLatch(1);

            store.addListener(entry -> {
                received.add(entry);
                latch.countDown();
            });

            store.publish(createEntry("INFO", null, null, "test"));

            assertTrue(latch.await(1, TimeUnit.SECONDS));
            assertEquals(1, received.size());
            assertEquals("test", received.get(0).message());
        }

        @Test
        void shouldRemoveListener() {
            List<LogEntry> received = new ArrayList<>();
            String id = store.addListener(received::add);
            assertEquals(1, store.getListenerCount());

            store.removeListener(id);
            assertEquals(0, store.getListenerCount());

            store.publish(createEntry("INFO", null, null, "after removal"));
            assertTrue(received.isEmpty());
        }

        @Test
        void shouldNotBreakOnListenerException() {
            store.addListener(entry -> {
                throw new RuntimeException("bad listener");
            });

            // Should not throw
            assertDoesNotThrow(() -> store.publish(createEntry("INFO", null, null, "test")));
            assertEquals(1, store.getBufferSize());
        }
    }

    // ==================== Async DB Writer ====================

    @Nested
    class DbWriter {

        @Test
        void shouldEnqueueForDbWhenEnabled() {
            // Create store with DB enabled, min level WARN
            var dbStore = BoundedLogStore.createForTesting(instanceIdProducer, databaseLogs, 10, true, 5, "WARN");

            dbStore.publish(createEntry("ERROR", null, null, "should persist"));
            dbStore.publish(createEntry("WARN", null, null, "should persist too"));
            dbStore.publish(createEntry("INFO", null, null, "below min level"));

            assertEquals(2, dbStore.getDbQueueSize());
        }

        @Test
        void shouldNotEnqueueWhenDbDisabled() {
            store.publish(createEntry("ERROR", null, null, "severe but db disabled"));

            assertEquals(0, store.getDbQueueSize());
        }

        @Test
        void shouldFlushToDbInBatch() {
            var dbStore = BoundedLogStore.createForTesting(instanceIdProducer, databaseLogs, 10, true, 5, "WARN");

            dbStore.publish(createEntry("ERROR", null, null, "error 1"));
            dbStore.publish(createEntry("WARN", null, null, "warn 1"));

            dbStore.flushToDb();

            verify(databaseLogs).addLogsBatch(argThat(list -> list.size() == 2));
            assertEquals(0, dbStore.getDbQueueSize());
        }

        @Test
        void shouldNotFlushWhenQueueEmpty() {
            var dbStore = BoundedLogStore.createForTesting(instanceIdProducer, databaseLogs, 10, true, 5, "WARN");

            dbStore.flushToDb();

            verify(databaseLogs, never()).addLogsBatch(any());
        }

        @Test
        void shouldRespectMinLevelForDbPersistence() {
            var dbStore = BoundedLogStore.createForTesting(instanceIdProducer, databaseLogs, 10, true, 5, "ERROR");

            dbStore.publish(createEntry("WARN", null, null, "below error threshold"));
            dbStore.publish(createEntry("ERROR", null, null, "meets threshold"));

            assertEquals(1, dbStore.getDbQueueSize());
        }
    }

    // ==================== capture() Edge Cases ====================

    @Nested
    class CaptureEdgeCases {

        @Test
        void captureWithNullRecord_shouldBeNoOp() {
            store.capture(null);
            assertEquals(0, store.getBufferSize(), "null record should not add anything to the buffer");
        }

        @Test
        void captureWithEmptyMessage_shouldBeNoOp() {
            var record = new LogRecord(Level.INFO, "");
            record.setLoggerName("test.Logger");

            store.capture(record);
            assertEquals(0, store.getBufferSize(), "empty message record should not be captured");
        }

        @Test
        void captureWithNullMessage_shouldBeNoOp() {
            var record = new LogRecord(Level.INFO, null);
            record.setLoggerName("test.Logger");

            store.capture(record);
            assertEquals(0, store.getBufferSize(), "null message record should not be captured");
        }

        @Test
        void captureFromBoundedLogStoreLogger_shouldSkipToPreventRecursion() {
            var record = new LogRecord(Level.INFO, "This is a real message");
            record.setLoggerName("ai.labs.eddi.engine.runtime.BoundedLogStore");

            store.capture(record);
            assertEquals(0, store.getBufferSize(),
                    "Records from BoundedLogStore's own logger should be skipped");
        }

        @Test
        void captureFromBoundedLogStoreSubLogger_shouldSkipToPreventRecursion() {
            var record = new LogRecord(Level.WARNING, "Sub-logger message");
            record.setLoggerName("ai.labs.eddi.engine.runtime.BoundedLogStore.internal");

            store.capture(record);
            assertEquals(0, store.getBufferSize(),
                    "Records from BoundedLogStore sub-loggers should also be skipped");
        }

        @Test
        void captureWithRegularLogRecord_shouldFallbackToSlf4jMdc() {
            // Use a plain JUL LogRecord (not ExtLogRecord) — capture should
            // fall through to the SLF4J MDC branch
            var record = new LogRecord(Level.WARNING, "SLF4J fallback test");
            record.setLoggerName("com.example.MyService");

            // Set SLF4J MDC values before capture
            org.slf4j.MDC.put("environment", "test-env");
            org.slf4j.MDC.put("agentId", "agent-mdc");
            org.slf4j.MDC.put("conversationId", "conv-mdc");
            org.slf4j.MDC.put("userId", "user-mdc");
            org.slf4j.MDC.put("agentVersion", "5");

            try {
                store.capture(record);

                assertEquals(1, store.getBufferSize(), "Regular LogRecord should be captured");
                List<LogEntry> entries = store.getEntries(null, null, null, 10);
                assertEquals(1, entries.size());

                LogEntry entry = entries.get(0);
                assertEquals("WARNING", entry.level());
                assertEquals("SLF4J fallback test", entry.message());
                assertEquals("test-env", entry.environment());
                assertEquals("agent-mdc", entry.agentId());
                assertEquals("conv-mdc", entry.conversationId());
                assertEquals("user-mdc", entry.userId());
                assertEquals(5, entry.agentVersion());
            } finally {
                org.slf4j.MDC.clear();
            }
        }

        @Test
        void captureWithInvalidAgentVersionMdc_shouldHandleGracefully() {
            var record = new LogRecord(Level.INFO, "Invalid version test");
            record.setLoggerName("com.example.MyService");

            org.slf4j.MDC.put("agentVersion", "not-a-number");
            try {
                store.capture(record);

                assertEquals(1, store.getBufferSize(), "Should still capture even with invalid agentVersion");
                List<LogEntry> entries = store.getEntries(null, null, null, 10);
                assertNull(entries.get(0).agentVersion(), "agentVersion should be null for non-numeric MDC value");
            } finally {
                org.slf4j.MDC.clear();
            }
        }

        @Test
        void captureWithExtLogRecordAndMdc_shouldExtractMdcFields() {
            var extRecord = new ExtLogRecord(
                    Level.SEVERE, "ExtLogRecord test",
                    "com.example.ExtService");
            extRecord.setLoggerName("com.example.ExtService");

            // Set MDC on ExtLogRecord
            extRecord.putMdc("environment", "prod");
            extRecord.putMdc("agentId", "agent-ext");
            extRecord.putMdc("conversationId", "conv-ext");
            extRecord.putMdc("userId", "user-ext");
            extRecord.putMdc("agentVersion", "3");

            store.capture(extRecord);

            assertEquals(1, store.getBufferSize());
            List<LogEntry> entries = store.getEntries(null, null, null, 10);
            LogEntry entry = entries.get(0);

            assertEquals("SEVERE", entry.level());
            assertEquals("ExtLogRecord test", entry.message());
            assertEquals("prod", entry.environment());
            assertEquals("agent-ext", entry.agentId());
            assertEquals("conv-ext", entry.conversationId());
            assertEquals("user-ext", entry.userId());
            assertEquals(3, entry.agentVersion());
        }

        @Test
        void captureWithExtLogRecord_invalidAgentVersion_shouldHandleGracefully() {
            var extRecord = new ExtLogRecord(
                    Level.INFO, "Invalid ext version",
                    "com.example.ExtService");
            extRecord.setLoggerName("com.example.ExtService");
            extRecord.putMdc("agentVersion", "abc");

            store.capture(extRecord);

            assertEquals(1, store.getBufferSize());
            List<LogEntry> entries = store.getEntries(null, null, null, 10);
            assertNull(entries.get(0).agentVersion(), "agentVersion should be null for non-numeric ExtLogRecord MDC");
        }

        @Test
        void captureWithPrintfFormatPattern_shouldFormatCorrectly() {
            var record = new LogRecord(Level.WARNING, "%s, line %d in %s");
            record.setLoggerName("com.example.ScriptEngine");
            record.setParameters(new Object[]{"hitlConfig is configured but nothing in this agent can trigger a pause", 42, "rules.js"});

            store.capture(record);

            List<LogEntry> entries = store.getEntries(null, null, null, 10);
            assertEquals(1, entries.size());
            assertEquals("hitlConfig is configured but nothing in this agent can trigger a pause, line 42 in rules.js", entries.get(0).message());
        }

        @Test
        void captureWithExtLogRecordPrintfPattern_shouldFormatCorrectly() {
            var extRecord = new ExtLogRecord(
                    Level.WARNING, "%s, line %d in %s",
                    "com.example.ScriptEngine");
            extRecord.setLoggerName("com.example.ScriptEngine");
            extRecord.setParameters(new Object[]{"Syntax error", 10, "agent.js"});

            store.capture(extRecord);

            List<LogEntry> entries = store.getEntries(null, null, null, 10);
            assertEquals(1, entries.size());
            assertEquals("Syntax error, line 10 in agent.js", entries.get(0).message());
        }

        /**
         * Indexed, padded and grouped specifiers matched none of the enumerated
         * "%s/%d/%f/%n/%x" cases, and MessageFormat does not throw on them — with no
         * {0} placeholders it returns the pattern unchanged, so the raw "%1$s" reached
         * the log viewer instead of the formatted message.
         */
        @Test
        void captureWithIndexedPrintfSpecifiers_shouldFormatCorrectly() {
            // Neither "%1$s" nor "%2$d" contains the literal "%s"/"%d" the old check
            // looked for, so this reached the viewer as the raw pattern.
            var record = new LogRecord(
                    Level.WARNING, "agent %1$s failed after %2$d attempts");
            record.setLoggerName("com.example.Retry");
            record.setParameters(new Object[]{"support-bot", 7});

            store.capture(record);

            List<LogEntry> entries = store.getEntries(null, null, null, 10);
            assertEquals(1, entries.size());
            assertEquals("agent support-bot failed after 7 attempts", entries.get(0).message());
        }

        @Test
        void captureWithPaddedPrintfSpecifier_shouldFormatCorrectly() {
            // "%03d" likewise does not contain "%d".
            var record = new LogRecord(
                    Level.INFO, "retrying in %03d ms");
            record.setLoggerName("com.example.Retry");
            record.setParameters(new Object[]{5});

            store.capture(record);

            List<LogEntry> entries = store.getEntries(null, null, null, 10);
            assertEquals("retrying in 005 ms", entries.get(0).message());
        }

        @Test
        void captureWithMessageFormatPatternContainingPercent_shouldStillUseMessageFormat() {
            // A literal percent must not drag a MessageFormat pattern down the printf
            // path: String.format rejects "% f", so it falls through as intended.
            var record = new LogRecord(
                    Level.INFO, "progress 50% for {0}");
            record.setLoggerName("com.example.Progress");
            record.setParameters(new Object[]{"batch-1"});

            store.capture(record);

            List<LogEntry> entries = store.getEntries(null, null, null, 10);
            assertEquals("progress 50% for batch-1", entries.get(0).message());
        }
    }

    // ==================== init() Idempotency ====================

    @Nested
    class InitIdempotency {

        @Test
        void callingInitTwice_shouldOnlyInitializeOnce() {
            // Create a store with DB enabled to check dbWriter creation
            var dbStore = BoundedLogStore.createForTesting(instanceIdProducer, databaseLogs, 5, true, 5, "WARN");

            // First init
            dbStore.init();

            // Second init — should be no-op (idempotent)
            dbStore.init();

            // If it initialized twice, it would set up two dbWriters.
            // We can verify it works by publishing and flushing normally.
            dbStore.publish(createEntry("ERROR", null, null, "after double init"));
            assertEquals(1, dbStore.getDbQueueSize());
            dbStore.shutdown();
        }
    }

    // ==================== shutdown() Tests ====================

    @Nested
    class ShutdownTests {

        @Test
        void shutdownWithDbEnabled_shouldFlushAndShutdownExecutor() {
            var dbStore = BoundedLogStore.createForTesting(instanceIdProducer, databaseLogs, 10, true, 5, "WARN");
            dbStore.init();

            // Publish some entries that need flushing
            dbStore.publish(createEntry("ERROR", null, null, "pending flush 1"));
            dbStore.publish(createEntry("WARN", null, null, "pending flush 2"));

            dbStore.shutdown();

            // Verify flushToDb was called during shutdown
            verify(databaseLogs).addLogsBatch(argThat(list -> list.size() == 2));
        }

        @Test
        void shutdownWithDbDisabled_shouldBeNoOp() {
            // The default 'store' has DB disabled
            // shutdown() should not throw and should not call databaseLogs
            assertDoesNotThrow(() -> store.shutdown());
            verify(databaseLogs, never()).addLogsBatch(any());
        }
    }

    // ==================== flushToDb() Error Handling ====================

    @Nested
    class FlushToDbErrorHandling {

        @Test
        void flushToDb_whenAddLogsBatchThrows_shouldNotCrash() {
            var dbStore = BoundedLogStore.createForTesting(instanceIdProducer, databaseLogs, 10, true, 5, "WARN");

            dbStore.publish(createEntry("ERROR", null, null, "will fail to persist"));

            doThrow(new RuntimeException("DB write failed"))
                    .when(databaseLogs).addLogsBatch(any());

            // Should not throw — error is caught internally
            assertDoesNotThrow(() -> dbStore.flushToDb());

            // Queue should be drained even on error (entries are polled before batch write)
            assertEquals(0, dbStore.getDbQueueSize());
        }
    }

    // ==================== Level Ordinal Mapping ====================

    @Nested
    class LevelOrdinalMapping {

        @Test
        void shouldMapSevereLevelCorrectly() {
            assertTrue(store.meetsMinimumLevel("SEVERE", "ERROR"), "SEVERE and ERROR should be equal");
            assertTrue(store.meetsMinimumLevel("ERROR", "SEVERE"), "ERROR and SEVERE should be equal");
        }

        @Test
        void shouldMapFatalLevelCorrectly() {
            assertTrue(store.meetsMinimumLevel("FATAL", "ERROR"), "FATAL should equal ERROR tier");
            assertTrue(store.meetsMinimumLevel("FATAL", "WARN"), "FATAL should be >= WARN");
        }

        @Test
        void shouldMapWarningAndWarnAsEqual() {
            assertTrue(store.meetsMinimumLevel("WARNING", "WARN"), "WARNING and WARN should be equal");
            assertTrue(store.meetsMinimumLevel("WARN", "WARNING"), "WARN and WARNING should be equal");
        }

        @Test
        void shouldMapConfigLevel() {
            assertTrue(store.meetsMinimumLevel("CONFIG", "FINE"), "CONFIG should be >= FINE");
            assertTrue(store.meetsMinimumLevel("CONFIG", "DEBUG"), "CONFIG should be >= DEBUG");
            assertFalse(store.meetsMinimumLevel("CONFIG", "INFO"), "CONFIG should be < INFO");
        }

        @Test
        void shouldMapFineAndDebugAsEqual() {
            assertTrue(store.meetsMinimumLevel("FINE", "DEBUG"), "FINE and DEBUG should be equal");
            assertTrue(store.meetsMinimumLevel("DEBUG", "FINE"), "DEBUG and FINE should be equal");
        }

        @Test
        void shouldMapFinerFinestAndTraceAsLowest() {
            assertTrue(store.meetsMinimumLevel("FINER", "FINEST"), "FINER and FINEST should be equal tier");
            assertTrue(store.meetsMinimumLevel("FINEST", "TRACE"), "FINEST and TRACE should be equal tier");
            assertTrue(store.meetsMinimumLevel("TRACE", "FINER"), "TRACE and FINER should be equal tier");
            assertFalse(store.meetsMinimumLevel("FINER", "DEBUG"), "FINER should be < DEBUG");
            assertFalse(store.meetsMinimumLevel("TRACE", "INFO"), "TRACE should be < INFO");
        }

        @Test
        void shouldMapUnknownLevelToInfoDefault() {
            // The levelOrdinal method maps unknown levels to 3 (INFO)
            assertTrue(store.meetsMinimumLevel("UNKNOWN_LEVEL", "INFO"), "Unknown level should map to INFO");
            assertTrue(store.meetsMinimumLevel("UNKNOWN_LEVEL", "DEBUG"), "Unknown level (INFO=3) should be >= DEBUG (1)");
            assertFalse(store.meetsMinimumLevel("UNKNOWN_LEVEL", "WARN"), "Unknown level (INFO=3) should be < WARN (4)");
        }

        @Test
        void shouldCoverFullOrderingChain() {
            // Full ordering: TRACE/FINER/FINEST(0) < FINE/DEBUG(1) < CONFIG(2) < INFO(3) <
            // WARN/WARNING(4) < ERROR/SEVERE/FATAL(5)
            assertTrue(store.meetsMinimumLevel("ERROR", "TRACE"), "ERROR(5) >= TRACE(0)");
            assertTrue(store.meetsMinimumLevel("WARN", "CONFIG"), "WARN(4) >= CONFIG(2)");
            assertTrue(store.meetsMinimumLevel("INFO", "FINE"), "INFO(3) >= FINE(1)");
            assertFalse(store.meetsMinimumLevel("DEBUG", "CONFIG"), "DEBUG(1) < CONFIG(2)");
            assertFalse(store.meetsMinimumLevel("FINEST", "FINE"), "FINEST(0) < FINE(1)");
        }
    }
}
