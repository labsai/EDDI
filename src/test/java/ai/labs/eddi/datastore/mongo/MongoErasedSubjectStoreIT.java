/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.mongo;

import ai.labs.eddi.engine.gdpr.ErasedSubjectStoreContract;
import ai.labs.eddi.engine.gdpr.IErasedSubjectStore;
import ai.labs.eddi.engine.gdpr.mongo.MongoErasedSubjectStore;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@link MongoErasedSubjectStore} against a real MongoDB (Testcontainers). */
@DisplayName("Erased-subject store (MongoDB)")
class MongoErasedSubjectStoreIT extends MongoTestBase implements ErasedSubjectStoreContract {

    private IErasedSubjectStore store;

    @BeforeEach
    void setUp() {
        dropCollections("gdpr_erased_subjects");
        store = new MongoErasedSubjectStore(getDatabase());
    }

    @Override
    public IErasedSubjectStore erasedSubjects() {
        return store;
    }

    @Test
    @DisplayName("the document holds the token and the time, nothing else")
    void documentShape() {
        store.record("gdpr-erased:s1:bob", AT);

        Document doc = getDatabase().getCollection("gdpr_erased_subjects").find().first();
        assertEquals(new Document("_id", "gdpr-erased:s1:bob").append("erasedAt", AT.toEpochMilli()), doc);
    }
}
