/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.mongo;

import ai.labs.eddi.engine.health.HealthEventStoreContract;
import ai.labs.eddi.engine.health.IHealthEventStore;
import ai.labs.eddi.engine.health.mongo.MongoHealthEventStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MongoHealthEventStore} against a real MongoDB (Testcontainers): the
 * shared {@link HealthEventStoreContract} plus the indexes, created again
 * without complaint.
 */
@DisplayName("MongoHealthEventStore (MongoDB)")
class MongoHealthEventStoreIT extends MongoTestBase implements HealthEventStoreContract {

    private static final String COLLECTION = "healthevents";

    private IHealthEventStore store;

    @BeforeEach
    void setUp() {
        dropCollections(COLLECTION);
        store = new MongoHealthEventStore(getDatabase());
    }

    @Override
    public IHealthEventStore store() {
        return store;
    }

    @Test
    void createsTheQueryIndexesIdempotently() {
        new MongoHealthEventStore(getDatabase());
        List<String> names = getDatabase().getCollection(COLLECTION).listIndexes().into(new ArrayList<>()).stream()
                .map(index -> index.getString("name")).toList();
        assertTrue(names.containsAll(List.of("idx_health_events_state", "idx_health_events_agent_day", "idx_health_events_conversation",
                "idx_health_events_user", "idx_health_events_day")), names.toString());
    }
}
