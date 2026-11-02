/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email.ledger;

import ai.labs.eddi.datastore.mongo.MongoTestBase;
import org.bson.Document;
import org.junit.jupiter.api.BeforeAll;

/** {@link EmailMailboxStateStoreContract} against MongoDB (Testcontainers). */
class MongoEmailMailboxStateStoreIT extends MongoTestBase implements EmailMailboxStateStoreContract {

    private static MongoEmailMailboxStateStore store;

    @BeforeAll
    static void createStore() {
        store = new MongoEmailMailboxStateStore(getDatabase());
    }

    @Override
    public IEmailMailboxStateStore store() {
        return store;
    }

    @Override
    public void clear() {
        getDatabase().getCollection(MongoEmailMailboxStateStore.COLLECTION).deleteMany(new Document());
        getDatabase().getCollection(MongoEmailMailboxStateStore.NOTICES).deleteMany(new Document());
    }
}
