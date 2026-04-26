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
}
