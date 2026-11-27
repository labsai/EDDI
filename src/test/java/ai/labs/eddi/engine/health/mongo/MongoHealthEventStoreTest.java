/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health.mongo;

import com.mongodb.ReadConcern;
import com.mongodb.ReadPreference;
import com.mongodb.client.MongoCollection;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("MongoHealthEventStore — the erasure verification counts are committed reads")
class MongoHealthEventStoreTest {

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("countByUserId and countByConversationId read the primary at majority, never the client default")
    void erasureCountsAreCommitted() {
        MongoCollection<Document> collection = mock(MongoCollection.class);
        MongoCollection<Document> primary = mock(MongoCollection.class);
        MongoCollection<Document> committed = mock(MongoCollection.class);
        when(collection.withReadPreference(ReadPreference.primary())).thenReturn(primary);
        when(primary.withReadConcern(ReadConcern.MAJORITY)).thenReturn(committed);
        when(committed.countDocuments(any(Bson.class))).thenReturn(2L, 3L);
        MongoHealthEventStore store = new MongoHealthEventStore(collection);

        assertEquals(2L, store.countByUserId("user"));
        assertEquals(3L, store.countByConversationId("conv"));
        verify(collection, never()).countDocuments(any(Bson.class));
    }
}
