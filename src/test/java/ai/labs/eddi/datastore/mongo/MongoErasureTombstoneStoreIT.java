/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.mongo;

import ai.labs.eddi.engine.gdpr.erasure.ErasureTombstoneStoreContract;
import ai.labs.eddi.engine.gdpr.erasure.IErasureTombstoneStore;
import ai.labs.eddi.engine.gdpr.erasure.mongo.ErasureTombstoneStore;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
