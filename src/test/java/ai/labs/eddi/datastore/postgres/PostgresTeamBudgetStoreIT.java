/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.engine.groups.budget.ITeamBudgetStore;
import ai.labs.eddi.engine.groups.budget.PostgresTeamBudgetStore;
import ai.labs.eddi.engine.groups.budget.TeamBudgetStoreContract;
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
 * {@link PostgresTeamBudgetStore} against a real PostgreSQL (Testcontainers):
 * the shared {@link TeamBudgetStoreContract} plus the schema.
 */
@DisplayName("PostgresTeamBudgetStore")
class PostgresTeamBudgetStoreIT extends PostgresTestBase implements TeamBudgetStoreContract {

    private static final DataSource DATA_SOURCE = createDataSource();

    private ITeamBudgetStore store;

    @BeforeEach
    void setUp() {
        store = new PostgresTeamBudgetStore(createDataSourceInstance());
    }

    @Override
    public ITeamBudgetStore store() {
        return store;
    }

    @Test
    void createsTheKeyAndTheRetentionIndex() throws Exception {
        store.read("schema-probe", "2026-11"); // creates the schema
        List<String> indexes = new ArrayList<>();
        try (Connection conn = DATA_SOURCE.getConnection();
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery("SELECT indexname FROM pg_indexes WHERE tablename = 'team_budget_months'")) {
            while (rs.next()) {
                indexes.add(rs.getString(1));
            }
        }
        assertTrue(indexes.containsAll(List.of("team_budget_months_pkey", "idx_team_budget_expires")), indexes.toString());
    }
}
