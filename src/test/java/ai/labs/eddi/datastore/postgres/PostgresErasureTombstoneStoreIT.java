/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.engine.gdpr.erasure.ErasureTombstoneStoreContract;
import ai.labs.eddi.engine.gdpr.erasure.IErasureTombstoneStore;
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

@DisplayName("Erasure tombstone store (PostgreSQL)")
class PostgresErasureTombstoneStoreIT extends PostgresTestBase implements ErasureTombstoneStoreContract {

    private static final DataSource DATA_SOURCE = createDataSource();

    private PostgresErasureTombstoneStore store;

    @BeforeEach
    void setUp() throws Exception {
        store = new PostgresErasureTombstoneStore(createDataSourceInstance());
        // The first call creates the schema; then start from empty tables.
        store.countPendingRemovals(null, null, null);
        truncateTables(DATA_SOURCE, "erasure_tombstones", "erasure_pending_removals", "erasure_meta");
    }

    @Override
    public IErasureTombstoneStore tombstoneStore() {
        return store;
    }

    @Test
    void theSchemaIsIdempotentAndIndexed() throws Exception {
        // A second store on the same database runs the DDL again (every node, every
        // start).
        new PostgresErasureTombstoneStore(createDataSourceInstance()).countPendingRemovals(null, null, null);
        List<String> indexes = new ArrayList<>();
        try (Connection conn = DATA_SOURCE.getConnection();
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery(
                        "SELECT indexname FROM pg_indexes WHERE tablename IN ('erasure_tombstones', 'erasure_pending_removals', 'erasure_meta')")) {
            while (rs.next()) {
                indexes.add(rs.getString(1));
            }
        }
        assertTrue(indexes.containsAll(List.of("erasure_tombstones_pkey", "idx_erasure_tombstones_expiry", "erasure_pending_removals_pkey",
                "idx_erasure_pending_user", "idx_erasure_pending_conversation", "idx_erasure_pending_created", "erasure_meta_pkey")),
                indexes.toString());
    }
}
