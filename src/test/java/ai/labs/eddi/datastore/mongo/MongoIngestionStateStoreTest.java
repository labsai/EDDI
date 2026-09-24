/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.mongo;

import ai.labs.eddi.modules.ingestion.IIngestionStateStore;
import ai.labs.eddi.modules.ingestion.IngestionStateStoreContract;
import ai.labs.eddi.modules.ingestion.mongo.MongoIngestionStateStore;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;

/**
 * The shared {@link IngestionStateStoreContract} against MongoDB.
 */
@DisplayName("MongoIngestionStateStore")
class MongoIngestionStateStoreTest extends MongoTestBase implements IngestionStateStoreContract {

    private MongoIngestionStateStore store;

    @BeforeEach
    void setUp() {
        dropCollections("rag_ingestion_documents", "rag_ingestion_runs");
        // Rebuilt per test: the constructor creates the indexes, including the
        // partial unique index that enforces one in-flight run per source, and
        // dropping the collections drops those with them.
        store = new MongoIngestionStateStore(getDatabase());
    }

    @Override
    public IIngestionStateStore store() {
        return store;
    }

    @Override
    public void forceDocumentOwner(String sourceId, String documentId, String runId) {
        getDatabase().getCollection("rag_ingestion_documents").updateOne(
                Filters.and(Filters.eq("sourceId", sourceId), Filters.eq("documentId", documentId)),
                Updates.set("fencingRunId", runId));
    }
}
