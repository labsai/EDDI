/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.engine.groups.outcomes.IOutcomeEventStore;
import ai.labs.eddi.engine.groups.outcomes.OutcomeStoreContract;
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

/** The outcome store against a real PostgreSQL (Testcontainers). */
@DisplayName("group_outcomes (PostgreSQL)")
class PostgresOutcomeEventStoreIT extends PostgresTestBase implements OutcomeStoreContract {

    private static final DataSource DATA_SOURCE = createDataSource();

    private PostgresOutcomeEventStore store;

    @BeforeEach
    void setUp() throws Exception {
        store = new PostgresOutcomeEventStore(createDataSourceInstance());
        // The first call creates the schema; then start from an empty table.
        store.countByGroup("none");
        truncateTables(DATA_SOURCE, "group_outcomes");
    }

    @Override
    public IOutcomeEventStore outcomes() {
        return store;
    }

    @Test
    void createsTheQueryIndexes() throws Exception {
        List<String> indexes = new ArrayList<>();
        try (Connection conn = DATA_SOURCE.getConnection();
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery("SELECT indexname FROM pg_indexes WHERE tablename = 'group_outcomes'")) {
            while (rs.next()) {
                indexes.add(rs.getString(1));
            }
        }
        assertTrue(indexes.containsAll(List.of("idx_group_outcomes_group", "idx_group_outcomes_subject", "idx_group_outcomes_user")),
                indexes.toString());
    }
}
