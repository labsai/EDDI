/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.pricing.mongo;

import ai.labs.eddi.datastore.mongo.MongoTestBase;
import ai.labs.eddi.engine.pricing.ModelPriceCatalogStoreContract;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link MongoModelPriceCatalogStore} against a real MongoDB (Testcontainers).
 */
class MongoModelPriceCatalogStoreIT extends MongoTestBase {

    private MongoModelPriceCatalogStore store;

    @BeforeEach
    void setUp() {
        dropCollections(MongoModelPriceCatalogStore.COLLECTION);
        store = new MongoModelPriceCatalogStore(getDatabase());
    }

    @Test
    void emptyStoreHasNoCatalog() {
        ModelPriceCatalogStoreContract.emptyStoreHasNoCatalog(store);
    }

    @Test
    void documentRoundTrips() {
        ModelPriceCatalogStoreContract.documentRoundTrips(store);
    }

    @Test
    void compareAndSetRefusesAStaleRevision() {
        ModelPriceCatalogStoreContract.compareAndSetRefusesAStaleRevision(store);
    }
}
