/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime;

import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.engine.model.LogEntry;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.result.UpdateResult;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link DatabaseLogs} — MongoDB-backed log persistence. Covers
 * getLogs with various filter parameters, addLogsBatch, and
 * pseudonymizeByUserId.
 */
@SuppressWarnings("unchecked")
class DatabaseLogsTest {

    private DatabaseLogs sut;
    private MongoCollection<Document> logsCollection;

    @BeforeEach
    void setUp() {
        MongoDatabase database = mock(MongoDatabase.class);
        logsCollection = mock(MongoCollection.class);
        when(database.getCollection("logs")).thenReturn(logsCollection);

        sut = new DatabaseLogs(database);
    }

    // =========================================================
    // getLogs
    // =========================================================

    @Nested
    class GetLogs {

        @Test
        void getLogs_allFiltersNull_usesEmptyFilter() {
            var findIterable = mockFindIterable(List.of());

            when(logsCollection.find(any(Document.class))).thenReturn(findIterable);

            var result = sut.getLogs(null, null, null, null, null, null, 0, 0);

            assertTrue(result.isEmpty());

            ArgumentCaptor<Document> filterCaptor = ArgumentCaptor.forClass(Document.class);
            verify(logsCollection).find(filterCaptor.capture());
            assertTrue(filterCaptor.getValue().isEmpty());
        }

        @Test
        void getLogs_allFiltersPopulated_setsAllFilterFields() {
            var findIterable = mockFindIterable(List.of());
            when(logsCollection.find(any(Document.class))).thenReturn(findIterable);

            sut.getLogs(Environment.production, "agent-1", 2, "conv-1", "user-1", "inst-1", 5, 10);

            ArgumentCaptor<Document> filterCaptor = ArgumentCaptor.forClass(Document.class);
            verify(logsCollection).find(filterCaptor.capture());
            Document filter = filterCaptor.getValue();

            assertEquals("production", filter.getString("environment"));
            assertEquals("agent-1", filter.getString("agentId"));
            assertEquals(2, filter.getInteger("agentVersion"));
            assertEquals("conv-1", filter.getString("conversationId"));
            assertEquals("user-1", filter.getString("userId"));
            assertEquals("inst-1", filter.getString("instanceId"));
        }

        @Test
        void getLogs_withSkipAndLimit_appliesBoth() {
            var findIterable = mockFindIterable(List.of());
            when(logsCollection.find(any(Document.class))).thenReturn(findIterable);

            sut.getLogs(null, null, null, null, null, null, 5, 10);

            verify(findIterable).limit(10);
            verify(findIterable).skip(5);
        }

        @Test
        void getLogs_zeroSkipAndLimit_skipsSkipAndLimit() {
            var findIterable = mockFindIterable(List.of());
            when(logsCollection.find(any(Document.class))).thenReturn(findIterable);

            sut.getLogs(null, null, null, null, null, null, 0, 0);

            verify(findIterable, never()).limit(anyInt());
            verify(findIterable, never()).skip(anyInt());
        }

        @Test
        void getLogs_withResults_convertsDocumentsToLogEntries() {
            Date ts = new Date(1234567890L);
            Document doc = new Document()
                    .append("timestamp", ts)
                    .append("level", "INFO")
                    .append("loggerName", "test.Logger")
                    .append("message", "Test message")
                    .append("environment", "production")
                    .append("agentId", "agent-1")
                    .append("agentVersion", 2)
                    .append("conversationId", "conv-1")
                    .append("userId", "user-1")
                    .append("instanceId", "inst-1");

            var findIterable = mockFindIterable(List.of(doc));
            when(logsCollection.find(any(Document.class))).thenReturn(findIterable);

            var result = sut.getLogs(null, null, null, null, null, null, 0, 1);

            assertEquals(1, result.size());
            LogEntry entry = result.get(0);
            assertEquals(1234567890L, entry.timestamp());
            assertEquals("INFO", entry.level());
            assertEquals("test.Logger", entry.loggerName());
            assertEquals("Test message", entry.message());
            assertEquals("production", entry.environment());
            assertEquals("agent-1", entry.agentId());
            assertEquals(2, entry.agentVersion());
            assertEquals("conv-1", entry.conversationId());
            assertEquals("user-1", entry.userId());
            assertEquals("inst-1", entry.instanceId());
        }

        @Test
        void getLogs_nullTimestamp_returnsZeroTimestamp() {
            Document doc = new Document()
                    .append("level", "WARN")
                    .append("loggerName", "test.Logger")
                    .append("message", "No timestamp");
            // timestamp is null

            var findIterable = mockFindIterable(List.of(doc));
            when(logsCollection.find(any(Document.class))).thenReturn(findIterable);

            var result = sut.getLogs(null, null, null, null, null, null, 0, 1);

            assertEquals(1, result.size());
            assertEquals(0L, result.get(0).timestamp());
        }

        @Test
        void getLogs_onlyEnvironmentFilter() {
            var findIterable = mockFindIterable(List.of());
            when(logsCollection.find(any(Document.class))).thenReturn(findIterable);

            sut.getLogs(Environment.production, null, null, null, null, null, 0, 0);

            ArgumentCaptor<Document> filterCaptor = ArgumentCaptor.forClass(Document.class);
            verify(logsCollection).find(filterCaptor.capture());
            Document filter = filterCaptor.getValue();

            assertEquals(1, filter.size());
            assertEquals("production", filter.getString("environment"));
        }
    }

    // =========================================================
    // addLogsBatch
    // =========================================================

    @Nested
    class AddLogsBatch {

        @Test
        void addLogsBatch_nullEntries_doesNothing() {
            sut.addLogsBatch(null);
            verifyNoInteractions(logsCollection);
        }

        @Test
        void addLogsBatch_emptyList_doesNothing() {
            sut.addLogsBatch(List.of());
            verifyNoInteractions(logsCollection);
        }

        @Test
        void addLogsBatch_withEntries_insertsDocuments() {
            var entries = List.of(
                    new LogEntry(1000L, "INFO", "logger1", "message1",
                            "production", "agent-1", 1, "conv-1", "user-1", "inst-1"),
                    new LogEntry(2000L, "ERROR", "logger2", "message2",
                            "production", "agent-2", 2, null, null, null));

            sut.addLogsBatch(entries);

            ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
            verify(logsCollection).insertMany(captor.capture());
            List<Document> docs = captor.getValue();

            assertEquals(2, docs.size());

            // First entry — all fields populated
            Document doc1 = docs.get(0);
            assertEquals("message1", doc1.getString("message"));
            assertEquals("INFO", doc1.getString("level"));
            assertEquals("logger1", doc1.getString("loggerName"));
            assertEquals("production", doc1.getString("environment"));
            assertEquals("agent-1", doc1.getString("agentId"));
            assertEquals(1, doc1.getInteger("agentVersion"));
            assertEquals("conv-1", doc1.getString("conversationId"));
            assertEquals("user-1", doc1.getString("userId"));
            assertEquals("inst-1", doc1.getString("instanceId"));

            // Second entry — null optional fields not included
            Document doc2 = docs.get(1);
            assertEquals("message2", doc2.getString("message"));
            assertFalse(doc2.containsKey("conversationId"));
            assertFalse(doc2.containsKey("userId"));
            assertFalse(doc2.containsKey("instanceId"));
        }

        @Test
        void addLogsBatch_insertManyThrows_doesNotPropagate() {
            var entries = List.of(
                    new LogEntry(1000L, "INFO", "logger1", "message1",
                            "production", "agent-1", 1, null, null, null));

            doThrow(new RuntimeException("MongoDB error"))
                    .when(logsCollection).insertMany(anyList());

            // Should not throw — error is logged internally
            assertDoesNotThrow(() -> sut.addLogsBatch(entries));
        }
    }

    // =========================================================
    // pseudonymizeByUserId
    // =========================================================

    @Nested
    class Pseudonymize {

        @Test
        void pseudonymizeByUserId_returnsModifiedCount() {
            UpdateResult updateResult = mock(UpdateResult.class);
            when(updateResult.getModifiedCount()).thenReturn(5L);
            when(logsCollection.updateMany(any(Document.class), any(Document.class)))
                    .thenReturn(updateResult);

            long result = sut.pseudonymizeByUserId("user-1", "pseudo-1");

            assertEquals(5L, result);

            ArgumentCaptor<Document> filterCaptor = ArgumentCaptor.forClass(Document.class);
            ArgumentCaptor<Document> updateCaptor = ArgumentCaptor.forClass(Document.class);
            verify(logsCollection).updateMany(filterCaptor.capture(), updateCaptor.capture());

            assertEquals("user-1", filterCaptor.getValue().getString("userId"));

            Document setDoc = updateCaptor.getValue().get("$set", Document.class);
            assertEquals("pseudo-1", setDoc.getString("userId"));
        }

        @Test
        void pseudonymizeByUserId_noMatches_returnsZero() {
            UpdateResult updateResult = mock(UpdateResult.class);
            when(updateResult.getModifiedCount()).thenReturn(0L);
            when(logsCollection.updateMany(any(Document.class), any(Document.class)))
                    .thenReturn(updateResult);

            long result = sut.pseudonymizeByUserId("non-existent", "pseudo-1");

            assertEquals(0L, result);
        }
    }

    // =========================================================
    // Constructor validation
    // =========================================================

    @Test
    void constructor_nullDatabase_throwsException() {
        assertThrows(IllegalArgumentException.class,
                () -> new DatabaseLogs(null));
    }

    // =========================================================
    // Helper
    // =========================================================

    @SuppressWarnings("unchecked")
    private FindIterable<Document> mockFindIterable(List<Document> docs) {
        FindIterable<Document> findIterable = mock(FindIterable.class);
        when(findIterable.sort(any(Document.class))).thenReturn(findIterable);
        when(findIterable.limit(anyInt())).thenReturn(findIterable);
        when(findIterable.skip(anyInt())).thenReturn(findIterable);

        com.mongodb.client.MongoCursor<Document> cursor = mock(com.mongodb.client.MongoCursor.class);
        Iterator<Document> iter = docs.iterator();
        when(cursor.hasNext()).thenAnswer(inv -> iter.hasNext());
        when(cursor.next()).thenAnswer(inv -> iter.next());
        doReturn(cursor).when(findIterable).iterator();

        return findIterable;
    }
}
