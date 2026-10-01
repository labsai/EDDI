/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.mongo;

import ai.labs.eddi.modules.ingestion.files.IIngestedFileStore;
import ai.labs.eddi.modules.ingestion.files.IngestedFileStoreContract;
import ai.labs.eddi.modules.ingestion.mongo.MongoIngestedFileStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;

/** The shared {@link IngestedFileStoreContract} against MongoDB GridFS. */
@DisplayName("MongoIngestedFileStore")
class MongoIngestedFileStoreTest extends MongoTestBase implements IngestedFileStoreContract {

    private MongoIngestedFileStore store;

    @BeforeEach
    void setUp() {
        dropCollections("rag_ingested_files.files", "rag_ingested_files.chunks");
        // Rebuilt per test: the constructor creates the index the queries rely on,
        // and dropping the collections drops it with them.
        store = new MongoIngestedFileStore(getDatabase());
    }

    @Override
    public IIngestedFileStore store() {
        return store;
    }
}
