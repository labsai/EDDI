/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.mongo;

import ai.labs.eddi.modules.ingestion.IngestionStateStoreException;
import com.mongodb.MongoSocketReadException;
import com.mongodb.ServerAddress;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What a caller has to catch when the database is unwell.
 *
 * <p>
 * The interface promises {@link IngestionStateStoreException} and the
 * PostgreSQL store translates every {@code SQLException} into it. This store
 * routes every operation through its {@code translating} helper for the same
 * reason — except {@code startRun}, which handled {@code MongoWriteException}
 * by hand and let everything else out raw, so during an outage what a caller
 * had to catch depended on which backend the operator had chosen. That is the
 * drift the shared contract exists to prevent, and the contract tests cannot
 * see it because a Testcontainers MongoDB does not fail that way on demand.
 * </p>
 */
@DisplayName("MongoIngestionStateStore — the exception contract under a failing server")
class MongoIngestionStateStoreExceptionTest {

    private MongoCollection<Document> runs;
    private MongoIngestionStateStore store;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        MongoDatabase database = mock(MongoDatabase.class);
        runs = mock(MongoCollection.class);
        MongoCollection<Document> documents = mock(MongoCollection.class);
        when(database.getCollection("rag_ingestion_runs")).thenReturn(runs);
        when(database.getCollection("rag_ingestion_documents")).thenReturn(documents);

        // nextGeneration runs before the insert and must not be what fails.
        FindIterable<Document> iterable = mock(FindIterable.class);
        when(runs.find(any(Bson.class))).thenReturn(iterable);
        when(iterable.sort(any(Bson.class))).thenReturn(iterable);
        when(iterable.limit(anyInt())).thenReturn(iterable);
        when(iterable.first()).thenReturn(null);

        store = new MongoIngestionStateStore(database);
    }

    /**
     * A socket read failure, a timeout or a step-down during the insert — anything
     * that is not the duplicate key the partial unique index raises when another
     * run is already in flight.
     */
    @Test
    @DisplayName("a non-write driver failure during startRun arrives as IngestionStateStoreException")
    void startRunTranslatesADriverFailure() {
        var driverFailure = new MongoSocketReadException("connection reset", new ServerAddress(), new IOException("reset"));
        doThrow(driverFailure).when(runs).insertOne(any(Document.class));

        var thrown = assertThrows(IngestionStateStoreException.class, () -> store.startRun("src-1"));

        assertInstanceOf(MongoSocketReadException.class, thrown.getCause(),
                "the driver's own failure has to stay reachable as the cause");
    }
}
