/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.mongo;

import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import com.mongodb.MongoException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.result.UpdateResult;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.inject.Instance;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static ai.labs.eddi.utils.LogCaptureSupport.FORGED_RECORD;
import static ai.labs.eddi.utils.LogCaptureSupport.assertNoForgedRecordBoundary;
import static ai.labs.eddi.utils.LogCaptureSupport.captureLogsOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CWE-117 regression tests for the tombstone-healing WARN lines in
 * {@link MongoResourceStorage} (code scanning alert #557) and the startup index
 * WARN in {@link GridFsIndexInitializer}.
 *
 * <p>
 * The value that reaches these lines is a driver exception's message, and a
 * driver quotes back what it was handed — a filter, a document, a server-side
 * error naming the offending value. None of it is the developer's text, so a
 * CR/LF in it must not start a new log record.
 * </p>
 */
@DisplayName("MongoResourceStorage log injection (CWE-117)")
class MongoResourceStorageLogInjectionTest {

    private static final String COLLECTION_NAME = "testcollection";
    private static final String VALID_ID = "aabbccddeeff112233445566";

    private MongoCollection<Document> currentCollection;
    private MongoCollection<Document> historyCollection;
    private MongoCollection<Document> durableCurrent;
    private MongoCollection<Document> durableHistory;
    private MongoResourceStorage<String> storage;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() throws Exception {
        MongoDatabase database = mock(MongoDatabase.class);
        currentCollection = mock(MongoCollection.class);
        historyCollection = mock(MongoCollection.class);
        durableCurrent = mock(MongoCollection.class);
        durableHistory = mock(MongoCollection.class);
        IDocumentBuilder documentBuilder = mock(IDocumentBuilder.class);
        when(database.getCollection(COLLECTION_NAME)).thenReturn(currentCollection);
        when(database.getCollection(COLLECTION_NAME + ".history")).thenReturn(historyCollection);
        when(currentCollection.withWriteConcern(any())).thenReturn(durableCurrent);
        when(historyCollection.withWriteConcern(any())).thenReturn(durableHistory);
        when(documentBuilder.toString(any())).thenReturn("{\"data\":\"test\"}");
        storage = new MongoResourceStorage<>(database, COLLECTION_NAME, documentBuilder, String.class);
    }

    @Test
    @DisplayName("a failed delete whose tombstone check also fails does not forge a record")
    void untombstoneWarnIsSanitized() throws Exception {
        var history = storage.newHistoryResourceFor(storage.newResource(VALID_ID, 1, "test"), true);
        when(durableCurrent.deleteOne(any(Bson.class))).thenThrow(new MongoException("write concern timeout"));
        when(currentCollection.countDocuments(any(Bson.class))).thenThrow(new MongoException("unreachable" + FORGED_RECORD));

        List<String> captured = captureLogsOf(MongoResourceStorage.class,
                () -> assertThrows(MongoException.class, () -> storage.storeHistoryAndRemove(history, VALID_ID, 1)));

        assertLineReached(captured, "tombstone could not be checked");
        assertNoForgedRecordBoundary(captured, "the untombstone WARN");
    }

    @Test
    @DisplayName("an update whose stale-tombstone clean-up fails does not forge a record")
    void clearStaleTombstoneWarnIsSanitized() throws Exception {
        var resource = storage.newResource(VALID_ID, 2, "test");
        var history = storage.newHistoryResourceFor(storage.newResource(VALID_ID, 1, "test"), false);
        var updateResult = mock(UpdateResult.class);
        when(updateResult.getMatchedCount()).thenReturn(1L);
        when(durableCurrent.replaceOne(any(Bson.class), any(Document.class))).thenReturn(updateResult);
        when(historyCollection.updateOne(any(Bson.class), any(Bson.class))).thenThrow(new MongoException("unreachable" + FORGED_RECORD));

        List<String> captured = captureLogsOf(MongoResourceStorage.class, () -> {
            try {
                storage.storeHistoryAndUpdate(history, resource, 1);
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });

        assertLineReached(captured, "stale deleted flag");
        assertNoForgedRecordBoundary(captured, "the stale-tombstone WARN");
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("a startup index failure does not forge a record")
    void indexInitializerWarnIsSanitized() {
        Instance<GridFsAttachmentStore> instance = mock(Instance.class);
        GridFsAttachmentStore store = mock(GridFsAttachmentStore.class);
        when(instance.get()).thenReturn(store);
        doThrow(new IllegalStateException("unreachable" + FORGED_RECORD)).when(store).ensureIndexes();

        List<String> captured = captureLogsOf(GridFsIndexInitializer.class,
                () -> new GridFsIndexInitializer(instance, "mongodb").onStart(mock(StartupEvent.class)));

        assertLineReached(captured, "attachment indexes");
        assertNoForgedRecordBoundary(captured, "the attachment-index WARN");
    }

    /**
     * Guards against a vacuous pass: the line under test must actually have been
     * logged.
     */
    private static void assertLineReached(List<String> captured, String fragment) {
        assertTrue(captured.stream().anyMatch(value -> value.contains(fragment)),
                "expected a log line containing '" + fragment + "', captured: " + captured);
    }
}
