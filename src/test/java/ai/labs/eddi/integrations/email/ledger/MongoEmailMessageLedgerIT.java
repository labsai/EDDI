/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email.ledger;

import ai.labs.eddi.datastore.mongo.MongoTestBase;
import org.bson.Document;
import org.junit.jupiter.api.BeforeAll;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** {@link EmailMessageLedgerContract} against MongoDB (Testcontainers). */
class MongoEmailMessageLedgerIT extends MongoTestBase implements EmailMessageLedgerContract {

    private static MongoEmailMessageLedger store;

    @BeforeAll
    static void createStore() {
        store = new MongoEmailMessageLedger(getDatabase());
    }

    @Override
    public IEmailMessageLedger ledger() {
        return store;
    }

    @Override
    public void clear() {
        getDatabase().getCollection(MongoEmailMessageLedger.COLLECTION).deleteMany(new Document());
    }

    @Override
    public Set<String> storedFieldNames() {
        Set<String> names = new HashSet<>();
        for (Document document : getDatabase().getCollection(MongoEmailMessageLedger.COLLECTION).find()) {
            for (String key : document.keySet()) {
                names.add(key.replace("_", "").toLowerCase(Locale.ROOT));
            }
        }
        return names;
    }
}
