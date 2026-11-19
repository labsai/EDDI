/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.mongo;

import ai.labs.eddi.engine.spendcontrol.ISpendControlChangeStore;
import ai.labs.eddi.engine.spendcontrol.SpendControlChangeStoreContract;
import ai.labs.eddi.engine.spendcontrol.mongo.MongoSpendControlChangeStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;

/**
 * {@link MongoSpendControlChangeStore} against a real MongoDB (Testcontainers):
 * the shared {@link SpendControlChangeStoreContract} — round trip, status
 * filter and order, compare-and-set claims under contention, retention.
 */
@DisplayName("MongoSpendControlChangeStore")
class MongoSpendControlChangeStoreIT extends MongoTestBase implements SpendControlChangeStoreContract {

    private ISpendControlChangeStore store;

    @BeforeEach
    void setUp() {
        dropCollections(MongoSpendControlChangeStore.COLLECTION);
        store = new MongoSpendControlChangeStore(getDatabase());
    }

    @Override
    public ISpendControlChangeStore store() {
        return store;
    }
}
