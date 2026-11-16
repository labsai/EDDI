/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.mongo;

import ai.labs.eddi.engine.health.HealthOutcomeStoreContract;
import ai.labs.eddi.engine.health.IHealthOutcomeStore;
import ai.labs.eddi.engine.health.mongo.MongoHealthOutcomeStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MongoHealthOutcomeStore} against a real MongoDB (Testcontainers): the
 * shared {@link HealthOutcomeStoreContract} plus the indexes, created again
 * without complaint.
 */
@DisplayName("MongoHealthOutcomeStore (MongoDB)")
class MongoHealthOutcomeStoreIT extends MongoTestBase implements HealthOutcomeStoreContract {

    private static final String COLLECTION = "healthoutcomes";

    private IHealthOutcomeStore store;

    @BeforeEach
    void setUp() {
        dropCollections(COLLECTION, "healthrollupstate");
        store = new MongoHealthOutcomeStore(getDatabase());
    }

    @Override
    public IHealthOutcomeStore store() {
        return store;
    }

    @Test
    void createsTheQueryIndexesIdempotently() {
        new MongoHealthOutcomeStore(getDatabase());
        List<String> names = getDatabase().getCollection(COLLECTION).listIndexes().into(new ArrayList<>()).stream()
                .map(index -> index.getString("name")).toList();
        assertTrue(names.containsAll(List.of("idx_health_outcomes_agent_day", "idx_health_outcomes_conversation", "idx_health_outcomes_user",
                "idx_health_outcomes_day", "idx_health_outcomes_due", "idx_health_outcomes_evaluated")), names.toString());
    }
}
