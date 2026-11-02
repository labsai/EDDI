/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.modules.llm.tools.quota.IToolQuotaStore;
import ai.labs.eddi.modules.llm.tools.quota.PostgresToolQuotaStore;
import ai.labs.eddi.modules.llm.tools.quota.ToolQuotaStoreContract;
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
 * {@link PostgresToolQuotaStore} against a real PostgreSQL (Testcontainers):
 * the shared {@link ToolQuotaStoreContract} plus the schema.
 */
@DisplayName("PostgresToolQuotaStore")
class PostgresToolQuotaStoreIT extends PostgresTestBase implements ToolQuotaStoreContract {

    private static final DataSource DATA_SOURCE = createDataSource();

    private IToolQuotaStore store;

    @BeforeEach
    void setUp() {
        store = new PostgresToolQuotaStore(createDataSourceInstance());
    }

    @Override
    public IToolQuotaStore store() {
        return store;
    }

    @Test
    void createsTheKeyAndTheLookupIndexes() throws Exception {
        store.used(ToolQuotaStoreContract.key("schema-probe", "tool:a", 1L)); // creates the schema
        List<String> indexes = new ArrayList<>();
        try (Connection conn = DATA_SOURCE.getConnection();
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery("SELECT indexname FROM pg_indexes WHERE tablename = 'tool_quota_counters'")) {
            while (rs.next()) {
                indexes.add(rs.getString(1));
            }
        }
        assertTrue(indexes.containsAll(List.of("tool_quota_counters_pkey", "idx_tool_quota_subject", "idx_tool_quota_expires")), indexes.toString());
    }
}
