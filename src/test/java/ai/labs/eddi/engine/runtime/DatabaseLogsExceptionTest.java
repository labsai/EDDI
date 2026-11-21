/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime;

import ai.labs.eddi.engine.model.LogEntry;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Date;
import java.util.Iterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * MongoDB persistence of {@link LogEntry#exception()}.
 */
@SuppressWarnings("unchecked")
class DatabaseLogsExceptionTest {

    private DatabaseLogs sut;
    private MongoCollection<Document> logsCollection;

    @BeforeEach
    void setUp() {
        MongoDatabase database = mock(MongoDatabase.class);
        logsCollection = mock(MongoCollection.class);
        when(database.getCollection("logs")).thenReturn(logsCollection);
        sut = new DatabaseLogs(database);
    }

    private FindIterable<Document> iterableOf(List<Document> docs) {
        FindIterable<Document> findIterable = mock(FindIterable.class);
        when(findIterable.sort(any(Document.class))).thenReturn(findIterable);
        when(findIterable.limit(anyInt())).thenReturn(findIterable);
        when(findIterable.skip(anyInt())).thenReturn(findIterable);
        MongoCursor<Document> cursor = mock(MongoCursor.class);
        Iterator<Document> iter = docs.iterator();
        when(cursor.hasNext()).thenAnswer(inv -> iter.hasNext());
        when(cursor.next()).thenAnswer(inv -> iter.next());
        doReturn(cursor).when(findIterable).iterator();
        return findIterable;
    }

    @Test
    @DisplayName("an entry's exception is written to the document; no exception writes no field")
    void writesExceptionOnlyWhenPresent() {
        sut.addLogsBatch(List.of(new LogEntry(1L, "ERROR", "l", "m", null, null, null, null, null, null, "java.io.IOException: x"),
                new LogEntry(2L, "ERROR", "l", "m", null, null, null, null, null, null)));

        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(logsCollection).insertMany(captor.capture());
        assertEquals("java.io.IOException: x", captor.getValue().get(0).getString("exception"));
        assertFalse(captor.getValue().get(1).containsKey("exception"), "absent, not null");
    }

    @Test
    @DisplayName("the exception is read back, and a document stored before the field existed reads as null")
    void readsExceptionAndOldDocuments() {
        Document withException = new Document("level", "ERROR").append("message", "m").append("timestamp", new Date(5L)).append("exception",
                "java.io.IOException: x");
        Document old = new Document("level", "ERROR").append("message", "m").append("timestamp", new Date(4L));
        FindIterable<Document> iterable = iterableOf(List.of(withException, old));
        when(logsCollection.find(any(Document.class))).thenReturn(iterable);

        List<LogEntry> logs = sut.getLogs(null, null, null, null, null, null, 0, 0);

        assertEquals("java.io.IOException: x", logs.get(0).exception());
        assertNull(logs.get(1).exception());
    }
}
