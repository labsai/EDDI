/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email.ledger;

import ai.labs.eddi.datastore.mongo.MongoTestBase;
import org.bson.Document;
import org.junit.jupiter.api.BeforeAll;

import java.util.HashSet;
import java.util.Set;

/** {@link EmailSuppressionStoreContract} against MongoDB (Testcontainers). */
class MongoEmailSuppressionStoreIT extends MongoTestBase implements EmailSuppressionStoreContract {

    private static MongoEmailSuppressionStore store;

    @BeforeAll
    static void createStore() {
        store = new MongoEmailSuppressionStore(getDatabase());
    }

    @Override
    public IEmailSuppressionStore store() {
        return store;
    }

    @Override
    public void clear() {
        getDatabase().getCollection(MongoEmailSuppressionStore.COLLECTION).deleteMany(new Document());
    }

    @Override
    public Set<String> storedValues() {
        Set<String> values = new HashSet<>();
        for (Document document : getDatabase().getCollection(MongoEmailSuppressionStore.COLLECTION).find()) {
            for (Object value : document.values()) {
                values.add(String.valueOf(value));
            }
        }
        return values;
    }
}
