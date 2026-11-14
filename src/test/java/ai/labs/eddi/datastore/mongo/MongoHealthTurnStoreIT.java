/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.mongo;

import ai.labs.eddi.engine.health.HealthTurnStoreContract;
import ai.labs.eddi.engine.health.IHealthTurnStore;
import ai.labs.eddi.engine.health.mongo.MongoHealthTurnStore;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MongoHealthTurnStore} against a real MongoDB (Testcontainers): the
 * shared {@link HealthTurnStoreContract} plus the indexes, created again
 * without complaint.
 */
@DisplayName("MongoHealthTurnStore (MongoDB)")
class MongoHealthTurnStoreIT extends MongoTestBase implements HealthTurnStoreContract {

    private static final String COLLECTION = "healthturns";

    private IHealthTurnStore store;

    @BeforeEach
    void setUp() {
        dropCollections(COLLECTION);
        store = new MongoHealthTurnStore(getDatabase());
    }

    @Override
    public IHealthTurnStore store() {
        return store;
    }

    @Test
    void createsTheQueryIndexesIdempotently() {
        new MongoHealthTurnStore(getDatabase());
        List<String> names = getDatabase().getCollection(COLLECTION).listIndexes().into(new ArrayList<>()).stream()
                .map(index -> index.getString("name")).toList();
        assertTrue(names.containsAll(List.of("idx_health_turns_agent_day", "idx_health_turns_conversation", "idx_health_turns_user",
                "idx_health_turns_day", "idx_health_turns_discarded_at", "idx_health_turns_updated")), names.toString());
    }
}
