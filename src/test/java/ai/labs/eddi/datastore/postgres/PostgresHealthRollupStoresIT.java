/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.engine.health.HealthRollupStoresContract;
import ai.labs.eddi.engine.health.IHealthDailyStore;
import ai.labs.eddi.engine.health.IHealthDirtyDayStore;
import ai.labs.eddi.engine.health.IHealthMarkerStore;
import ai.labs.eddi.engine.health.IHealthTurnStore;
import ai.labs.eddi.engine.health.postgres.PostgresHealthDailyStore;
import ai.labs.eddi.engine.health.postgres.PostgresHealthDirtyDayStore;
import ai.labs.eddi.engine.health.postgres.PostgresHealthMarkerStore;
import ai.labs.eddi.engine.health.postgres.PostgresHealthTurnStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.LocalDate;

/**
 * {@link HealthRollupStoresContract} against PostgreSQL (Testcontainers).
 */
@DisplayName("Agent Health rollup stores (PostgreSQL)")
class PostgresHealthRollupStoresIT extends PostgresTestBase implements HealthRollupStoresContract {

    private static final DataSource DATA_SOURCE = createDataSource();

    private IHealthTurnStore turns;
    private IHealthDailyStore daily;
    private IHealthDirtyDayStore dirty;
    private IHealthMarkerStore markers;

    @BeforeEach
    void setUp() throws Exception {
        turns = new PostgresHealthTurnStore(createDataSourceInstance());
        daily = new PostgresHealthDailyStore(createDataSourceInstance());
        dirty = new PostgresHealthDirtyDayStore(createDataSourceInstance());
        markers = new PostgresHealthMarkerStore(createDataSourceInstance());
        // The first call of each store creates its schema; then start from empty
        // tables.
        turns.findByUserId("nobody", 1);
        daily.find("nobody", LocalDate.EPOCH, LocalDate.EPOCH);
        dirty.count();
        markers.find("nobody", Instant.EPOCH, Instant.EPOCH, 1);
        truncateTables(DATA_SOURCE, "health_turns", "health_daily", "health_dirty", "health_markers");
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
