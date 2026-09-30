/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.properties.mongo;

import com.mongodb.client.ListIndexesIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import org.bson.Document;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * For unit tests that build a {@link MongoUserMemoryStore} over a mocked
 * collection: reports the unique identity indexes as already installed, so the
 * constructor skips the startup merge (which the Testcontainers suite covers
 * against a real server) instead of running aggregations against a mock.
 */
final class IdentityIndexStubs {

    private IdentityIndexStubs() {
    }

    @SuppressWarnings("unchecked")
    static void stubInstalledIdentityIndexes(MongoCollection<Document> collection) {
        ListIndexesIterable<Document> indexes = mock(ListIndexesIterable.class);
        MongoCursor<Document> cursor = mock(MongoCursor.class);
        when(cursor.hasNext()).thenReturn(true, true, false);
        when(cursor.next()).thenReturn(new Document("name", UserMemoryIdentityIndexes.GLOBAL_INDEX),
                new Document("name", UserMemoryIdentityIndexes.AGENT_INDEX));
        when(indexes.iterator()).thenReturn(cursor);
        when(collection.listIndexes()).thenReturn(indexes);
    }
}
