/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.mongo;

import ai.labs.eddi.engine.health.HealthRollupStoresContract;
import ai.labs.eddi.engine.health.IHealthDailyStore;
import ai.labs.eddi.engine.health.IHealthDirtyDayStore;
import ai.labs.eddi.engine.health.IHealthMarkerStore;
import ai.labs.eddi.engine.health.IHealthTurnStore;
import ai.labs.eddi.engine.health.mongo.MongoHealthDailyStore;
import ai.labs.eddi.engine.health.mongo.MongoHealthDirtyDayStore;
import ai.labs.eddi.engine.health.mongo.MongoHealthMarkerStore;
import ai.labs.eddi.engine.health.mongo.MongoHealthTurnStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;

/**
 * {@link HealthRollupStoresContract} against MongoDB (Testcontainers).
 */
@DisplayName("Agent Health rollup stores (MongoDB)")
class MongoHealthRollupStoresIT extends MongoTestBase implements HealthRollupStoresContract {

    private IHealthTurnStore turns;
    private IHealthDailyStore daily;
    private IHealthDirtyDayStore dirty;
    private IHealthMarkerStore markers;

    @BeforeEach
    void setUp() {
        dropCollections("healthturns", "healthdaily", "healthdirty", "healthmarkers");
        turns = new MongoHealthTurnStore(getDatabase());
        daily = new MongoHealthDailyStore(getDatabase());
        dirty = new MongoHealthDirtyDayStore(getDatabase());
        markers = new MongoHealthMarkerStore(getDatabase());
    }

    @Override
    public IHealthTurnStore turns() {
        return turns;
    }

    @Override
    public IHealthDailyStore daily() {
        return daily;
    }

    @Override
    public IHealthDirtyDayStore dirty() {
        return dirty;
    }

    @Override
    public IHealthMarkerStore markers() {
        return markers;
    }
}
