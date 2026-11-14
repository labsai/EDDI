/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.engine.health.HealthTurnStoreContract;
import ai.labs.eddi.engine.health.IHealthTurnStore;
import ai.labs.eddi.engine.health.postgres.PostgresHealthTurnStore;
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
 * {@link PostgresHealthTurnStore} against a real PostgreSQL (Testcontainers):
 * the shared {@link HealthTurnStoreContract} plus the schema, whose DDL runs
 * again on a fresh store without complaint.
 */
@DisplayName("PostgresHealthTurnStore")
class PostgresHealthTurnStoreIT extends PostgresTestBase implements HealthTurnStoreContract {

    private static final DataSource DATA_SOURCE = createDataSource();
    private static final String TABLE = "health_turns";

    private PostgresHealthTurnStore store;

    @BeforeEach
    void setUp() throws Exception {
        store = new PostgresHealthTurnStore(createDataSourceInstance());
        // The first call creates the schema; then start from an empty table.
        store.findByUserId("nobody", 1);
        truncateTables(DATA_SOURCE, TABLE);
    }

    @Override
    public IHealthTurnStore store() {
        return store;
    }

    @Test
    void createsTheKeyAndQueryIndexesIdempotently() throws Exception {
        new PostgresHealthTurnStore(createDataSourceInstance()).findByUserId("nobody", 1);
        List<String> indexes = new ArrayList<>();
        try (Connection conn = DATA_SOURCE.getConnection();
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery("SELECT indexname FROM pg_indexes WHERE tablename = '" + TABLE + "'")) {
            while (rs.next()) {
                indexes.add(rs.getString(1));
            }
        }
        assertTrue(indexes.containsAll(List.of("health_turns_pkey", "idx_health_turns_agent_day", "idx_health_turns_user", "idx_health_turns_day",
                "idx_health_turns_discarded_at", "idx_health_turns_updated")), indexes.toString());
    }
}
