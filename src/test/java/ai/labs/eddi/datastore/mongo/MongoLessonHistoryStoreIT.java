/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.mongo;

import ai.labs.eddi.engine.groups.lessons.ILessonHistoryStore;
import ai.labs.eddi.engine.groups.lessons.LessonHistoryStoreContract;
import ai.labs.eddi.engine.groups.lessons.mongo.MongoLessonHistoryStore;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** The lesson history store against a real MongoDB (Testcontainers). */
@DisplayName("group_lesson_history (MongoDB)")
class MongoLessonHistoryStoreIT extends MongoTestBase implements LessonHistoryStoreContract {

    private ILessonHistoryStore store;

    @BeforeEach
    void setUp() {
        dropCollections("group_lesson_history");
        store = new MongoLessonHistoryStore(getDatabase());
    }

    @Override
    public ILessonHistoryStore history() {
        return store;
    }

    @Test
    void createsTheQueryIndex() {
        List<String> names = getDatabase().getCollection("group_lesson_history").listIndexes().into(new ArrayList<Document>()).stream()
                .map(index -> index.getString("name")).toList();
        assertTrue(names.contains("idx_lesson_history_group"), names.toString());
    }
}
