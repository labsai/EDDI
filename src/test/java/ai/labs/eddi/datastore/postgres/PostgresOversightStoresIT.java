/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.engine.hitl.oversight.OversightStoreContracts;
import ai.labs.eddi.engine.hitl.oversight.analytics.DecisionFilter;
import ai.labs.eddi.engine.hitl.oversight.analytics.IApprovalDecisionStore;
import ai.labs.eddi.engine.hitl.oversight.autonomy.IAutonomyStateStore;
import ai.labs.eddi.engine.hitl.oversight.samples.IAuditSampleStore;
import ai.labs.eddi.engine.hitl.oversight.samples.SampleFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The three HITL oversight stores against a real PostgreSQL (Testcontainers):
 * the shared {@link OversightStoreContracts} plus the schema each creates.
 */
@DisplayName("HITL oversight stores (PostgreSQL)")
class PostgresOversightStoresIT extends PostgresTestBase {

    private static final DataSource DATA_SOURCE = createDataSource();

    private static List<String> indexNames(String table) throws Exception {
        List<String> indexes = new ArrayList<>();
        try (Connection conn = DATA_SOURCE.getConnection();
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery("SELECT indexname FROM pg_indexes WHERE tablename = '" + table + "'")) {
            while (rs.next()) {
                indexes.add(rs.getString(1));
            }
        }
        return indexes;
    }

    @Nested
    @DisplayName("approval_decisions")
    class Decisions implements OversightStoreContracts.Decisions {

        private PostgresApprovalDecisionStore store;

        @BeforeEach
        void setUp() throws Exception {
            store = new PostgresApprovalDecisionStore(createDataSourceInstance());
            // The first call creates the schema; then start from an empty table.
            store.count(new DecisionFilter(null, null, null, null, null, null, null, null, null));
            truncateTables(DATA_SOURCE, "approval_decisions");
        }

        @Override
        public IApprovalDecisionStore decisions() {
            return store;
        }

        @Test
        void createsTheQueryIndexes() throws Exception {
            List<String> indexes = indexNames("approval_decisions");
            assertTrue(indexes.containsAll(List.of("idx_approval_decisions_agent", "idx_approval_decisions_group",
                    "idx_approval_decisions_decided", "idx_approval_decisions_user", "idx_approval_decisions_decider")), indexes.toString());
        }
    }

    @Nested
    @DisplayName("audit_samples")
    class Samples implements OversightStoreContracts.Samples {

        private PostgresAuditSampleStore store;

        @BeforeEach
        void setUp() throws Exception {
            store = new PostgresAuditSampleStore(createDataSourceInstance());
            store.count(SampleFilter.pending(null));
            truncateTables(DATA_SOURCE, "audit_samples");
        }

        @Override
        public IAuditSampleStore samples() {
            return store;
        }

        @Test
        void createsTheQueryIndexes() throws Exception {
            List<String> indexes = indexNames("audit_samples");
            assertTrue(indexes.containsAll(List.of("idx_audit_samples_status_due", "idx_audit_samples_agent_category",
                    "idx_audit_samples_agent_status", "idx_audit_samples_user", "idx_audit_samples_reviewer")), indexes.toString());
        }
    }

    @Nested
    @DisplayName("autonomy_state")
    class States implements OversightStoreContracts.States {

        private PostgresAutonomyStateStore store;

        @BeforeEach
        void setUp() throws Exception {
            store = new PostgresAutonomyStateStore(createDataSourceInstance());
            store.countSuspended();
            truncateTables(DATA_SOURCE, "autonomy_state");
        }

        @Override
        public IAutonomyStateStore states() {
            return store;
        }

        @Test
        void createsThePrimaryKeyAndSuspensionIndex() throws Exception {
            List<String> indexes = indexNames("autonomy_state");
            assertTrue(indexes.containsAll(List.of("autonomy_state_pkey", "idx_autonomy_state_suspended")), indexes.toString());
        }
    }
}
