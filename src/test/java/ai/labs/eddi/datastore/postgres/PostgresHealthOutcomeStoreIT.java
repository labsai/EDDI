/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.engine.health.HealthOutcomeStoreContract;
import ai.labs.eddi.engine.health.IHealthOutcomeStore;
import ai.labs.eddi.engine.health.postgres.PostgresHealthOutcomeStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PostgresHealthOutcomeStore} against a real PostgreSQL
 * (Testcontainers): the shared {@link HealthOutcomeStoreContract} plus the
 * schema, whose DDL runs again on a fresh store without complaint.
 */
@DisplayName("PostgresHealthOutcomeStore")
class PostgresHealthOutcomeStoreIT extends PostgresTestBase implements HealthOutcomeStoreContract {

    private static final DataSource DATA_SOURCE = createDataSource();
    private static final String TABLE = "health_outcomes";

    private PostgresHealthOutcomeStore store;

    @BeforeEach
    void setUp() throws Exception {
        store = new PostgresHealthOutcomeStore(createDataSourceInstance());
        // The first call creates the schema; then start from empty tables.
        store.findByUserId("nobody", 1);
        truncateTables(DATA_SOURCE, TABLE, "health_rollup_state");
    }

    @Override
    public IHealthOutcomeStore store() {
        return store;
    }

    @Test
    void createsTheKeyAndQueryIndexesIdempotently() throws Exception {
        new PostgresHealthOutcomeStore(createDataSourceInstance()).findByUserId("nobody", 1);
        List<String> indexes = new ArrayList<>();
        try (Connection conn = DATA_SOURCE.getConnection();
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery("SELECT indexname FROM pg_indexes WHERE tablename = '" + TABLE + "'")) {
            while (rs.next()) {
                indexes.add(rs.getString(1));
            }
        }
        assertTrue(indexes.containsAll(List.of("health_outcomes_pkey", "idx_health_outcomes_agent_day", "idx_health_outcomes_user",
                "idx_health_outcomes_day", "idx_health_outcomes_due", "idx_health_outcomes_evaluated")), indexes.toString());
    }
}
