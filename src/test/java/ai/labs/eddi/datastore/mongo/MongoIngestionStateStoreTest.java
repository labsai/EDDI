/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.mongo;

import ai.labs.eddi.modules.ingestion.IIngestionStateStore;
import ai.labs.eddi.modules.ingestion.IngestionStateStoreContract;
import ai.labs.eddi.modules.ingestion.IngestionWorkQueue.EnqueueOutcome;
import ai.labs.eddi.modules.ingestion.IngestionWorkQueue.WorkCounts;
import ai.labs.eddi.modules.ingestion.IngestionWorkQueue.WorkRequest;
import ai.labs.eddi.modules.ingestion.IngestionWorkQueueContract;
import ai.labs.eddi.modules.ingestion.mongo.MongoIngestionStateStore;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The shared {@link IngestionStateStoreContract} against MongoDB.
 */
@DisplayName("MongoIngestionStateStore")
class MongoIngestionStateStoreTest extends MongoTestBase implements IngestionStateStoreContract, IngestionWorkQueueContract {

    private MongoIngestionStateStore store;

    @BeforeEach
    void setUp() {
        dropCollections("rag_ingestion_documents", "rag_ingestion_runs", "rag_ingestion_sources");
        // Rebuilt per test: the constructor creates the indexes, including the
        // partial unique index that enforces one in-flight run per source, and
        // dropping the collections drops those with them.
        store = new MongoIngestionStateStore(getDatabase());
    }

    @Override
    public IIngestionStateStore store() {
        return store;
    }

    @Test
    @DisplayName("a document written before the work table reads as having no work, and takes work")
    void aRowWrittenBeforeTheWorkTableHasNoWork() {
        getDatabase().getCollection("rag_ingestion_documents").insertOne(new Document("sourceId", "legacy")
                .append("documentId", "doc").append("contentHash", "hash").append("missedRuns", 0)
                .append("tombstoned", false));

        assertEquals("hash", store.lookup("legacy", "doc").orElseThrow().contentHash());
        assertEquals(WorkCounts.NONE, store.workCounts("legacy", Instant.now()));
        String run = store.startRun("legacy").orElseThrow();
        assertEquals(EnqueueOutcome.QUEUED,
                store.enqueueWork("legacy", run, new WorkRequest("doc", "v2", "{}"), Instant.now()));
        assertEquals(1, store.claimWork("legacy", run, 10, Instant.now(), Instant.now().plusSeconds(60)).size());
    }

    @Override
    public void forceClaimedBy(String sourceId, String documentId, String claimer) {
        getDatabase().getCollection("rag_ingestion_documents").updateOne(
                Filters.and(Filters.eq("sourceId", sourceId), Filters.eq("documentId", documentId)),
                Updates.set("claimedBy", claimer));
    }

    @Override
    public void forceDocumentOwner(String sourceId, String documentId, String runId) {
        getDatabase().getCollection("rag_ingestion_documents").updateOne(
                Filters.and(Filters.eq("sourceId", sourceId), Filters.eq("documentId", documentId)),
                Updates.set("fencingRunId", runId));
    }
}
