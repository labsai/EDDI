/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.mongo;

import ai.labs.eddi.modules.ingestion.IIngestionStateStore;
import ai.labs.eddi.modules.ingestion.IngestionStateStoreContract;
import ai.labs.eddi.modules.ingestion.mongo.MongoIngestionStateStore;
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
}
