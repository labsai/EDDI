/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.engine.health.HealthEventStoreContract;
import ai.labs.eddi.engine.health.IHealthEventStore;
import ai.labs.eddi.engine.health.postgres.PostgresHealthEventStore;
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
 * {@link PostgresHealthEventStore} against a real PostgreSQL (Testcontainers):
 * the shared {@link HealthEventStoreContract} plus the schema, whose DDL runs
 * again on a fresh store without complaint.
 */
@DisplayName("PostgresHealthEventStore")
class PostgresHealthEventStoreIT extends PostgresTestBase implements HealthEventStoreContract {

    private static final DataSource DATA_SOURCE = createDataSource();
    private static final String TABLE = "health_events";

    private PostgresHealthEventStore store;

    @BeforeEach
    void setUp() throws Exception {
        store = new PostgresHealthEventStore(createDataSourceInstance());
        // The first call creates the schema; then start from an empty table.
        store.findByUserId("nobody", 1);
        truncateTables(DATA_SOURCE, TABLE);
    }

    @Override
    public IHealthEventStore store() {
        return store;
    }

    @Test
    void createsTheKeyAndQueryIndexesIdempotently() throws Exception {
        new PostgresHealthEventStore(createDataSourceInstance()).findByUserId("nobody", 1);
        List<String> indexes = new ArrayList<>();
        try (Connection conn = DATA_SOURCE.getConnection();
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery("SELECT indexname FROM pg_indexes WHERE tablename = '" + TABLE + "'")) {
            while (rs.next()) {
                indexes.add(rs.getString(1));
            }
        }
        assertTrue(indexes.containsAll(List.of("health_events_pkey", "idx_health_events_pending", "idx_health_events_agent_day",
                "idx_health_events_conversation", "idx_health_events_user", "idx_health_events_day")), indexes.toString());
    }
}
