/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.mongo;

import ai.labs.eddi.engine.gdpr.erasure.ErasureTombstoneStoreContract;
import ai.labs.eddi.engine.gdpr.erasure.IErasureTombstoneStore;
import ai.labs.eddi.engine.gdpr.erasure.mongo.ErasureTombstoneStore;
import com.mongodb.ReadConcern;
import com.mongodb.ReadPreference;
import com.mongodb.WriteConcern;
import com.mongodb.client.MongoCollection;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Erasure tombstone store (MongoDB)")
class MongoErasureTombstoneStoreIT extends MongoTestBase implements ErasureTombstoneStoreContract {

    private ErasureTombstoneStore store;

    @BeforeEach
    void setUp() {
        dropCollections("erasuretombstones", "erasurependingremovals", "erasuremeta");
        store = new ErasureTombstoneStore(getDatabase());
    }

    @Override
    public IErasureTombstoneStore tombstoneStore() {
        return store;
    }

    /**
     * The client reads {@code nearest()}; a tombstone or pending-removal read from
     * a lagging secondary would break the guarantee, so every collection of the
     * store reads the primary with majority concern and writes with majority.
     */
    @Test
    @SuppressWarnings("unchecked")
    void everyCollectionReadsThePrimaryWithMajorityConcern() throws Exception {
        for (String name : List.of("tombstones", "pending", "meta")) {
            Field field = ErasureTombstoneStore.class.getDeclaredField(name);
            field.setAccessible(true);
            MongoCollection<Document> collection = (MongoCollection<Document>) field.get(store);
            assertEquals(ReadPreference.primary(), collection.getReadPreference(), name);
            assertEquals(ReadConcern.MAJORITY, collection.getReadConcern(), name);
            assertEquals(WriteConcern.MAJORITY, collection.getWriteConcern(), name);
        }
    }

    @Test
    void createsTheTtlAndLookupIndexesIdempotently() {
        new ErasureTombstoneStore(getDatabase()); // the DDL runs on every start
        List<Document> tombstoneIndexes = getDatabase().getCollection("erasuretombstones").listIndexes().into(new ArrayList<>());
        Document ttl = tombstoneIndexes.stream().filter(i -> "idx_erasuretombstone_ttl".equals(i.getString("name"))).findFirst().orElseThrow();
        assertEquals(0L, ((Number) ttl.get("expireAfterSeconds")).longValue());
        List<String> pending = getDatabase().getCollection("erasurependingremovals").listIndexes().into(new ArrayList<>()).stream()
                .map(i -> i.getString("name")).toList();
        assertTrue(pending.containsAll(List.of("idx_erasurepending_user", "idx_erasurepending_conversation", "idx_erasurepending_created")),
                pending.toString());
    }
}
