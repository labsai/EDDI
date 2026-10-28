/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.branding.mongo;

import ai.labs.eddi.configs.branding.BrandingStoreContract;
import ai.labs.eddi.datastore.mongo.MongoTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** {@link BrandingStore} against a real MongoDB (Testcontainers). */
class BrandingStoreMongoIT extends MongoTestBase {

    private BrandingStore store;

    @BeforeEach
    void setUp() {
        dropCollections(BrandingStore.COLLECTION, BrandingStore.ASSET_COLLECTION);
        store = new BrandingStore(getDatabase());
    }

    @Test
    void emptyStoreHasNoBrand() {
        BrandingStoreContract.emptyStoreHasNoBrand(store);
    }

    @Test
    void documentRoundTrips() {
        BrandingStoreContract.documentRoundTrips(store);
    }

    @Test
    void compareAndSetRefusesAStaleRevision() {
        BrandingStoreContract.compareAndSetRefusesAStaleRevision(store);
    }

    @Test
    void assetsAreStoredPerSlot() {
        BrandingStoreContract.assetsAreStoredPerSlot(store);
    }
}
