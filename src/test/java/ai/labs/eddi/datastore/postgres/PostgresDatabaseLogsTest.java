/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.engine.model.LogEntry;
import org.junit.jupiter.api.*;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link PostgresDatabaseLogs} using Testcontainers.
 *
 * @since 6.0.0
 */
@DisplayName("PostgresDatabaseLogs IT")
class PostgresDatabaseLogsTest extends PostgresTestBase {

    private static PostgresDatabaseLogs logs;
    private static DataSource ds;

    @BeforeAll
    static void init() {
        var dsInstance = createDataSourceInstance();
        ds = dsInstance.get();
        logs = new PostgresDatabaseLogs(dsInstance);
    }

    @BeforeEach
    void clean() {
        try {
            truncateTables(ds, "database_logs");
        } catch (SQLException ignored) {
        }
    }

    // ─── Batch Insert + Query ───────────────────────────────────

    @Nested
    @DisplayName("Batch Insert and Query")
    class BatchInsertAndQuery {

        @Test
        @DisplayName("addLogsBatch + getLogs round-trip")
        void batchInsertAndQuery() {
            var entries = List.of(
                    new LogEntry(System.currentTimeMillis(), "INFO", "ai.labs.Test", "Hello",
                            "production", "agent1", 1, "conv1", "user1", "node-1"),
                    new LogEntry(System.currentTimeMillis(), "WARN", "ai.labs.Test", "Warning",
                            "production", "agent1", 1, "conv1", "user1", "node-1"));
            logs.addLogsBatch(entries);

            List<LogEntry> result = logs.getLogs(Environment.production, "agent1", 1,
                    "conv1", "user1", "node-1", 0, 10);
            assertEquals(2, result.size());
        }

        @Test
        @DisplayName("addLogsBatch null — no-op")
        void nullBatch() {
            assertDoesNotThrow(() -> logs.addLogsBatch(null));
        }

        @Test
        @DisplayName("addLogsBatch empty — no-op")
        void emptyBatch() {
            assertDoesNotThrow(() -> logs.addLogsBatch(List.of()));
        }

        @Test
        @DisplayName("addLogsBatch with null agentVersion")
        void nullAgentVersion() {
            var entry = new LogEntry(System.currentTimeMillis(), "ERROR", "test",
                    "msg", "production", "agent1", null, "conv", "user", "node");
            logs.addLogsBatch(List.of(entry));

            List<LogEntry> result = logs.getLogs(Environment.production, "agent1", null,
                    null, null, null, null, 10);
            assertEquals(1, result.size());
            assertNull(result.getFirst().agentVersion());
        }
    }

    // ─── Query Filters ──────────────────────────────────────────

    @Nested
    @DisplayName("Query Filters")
    class QueryFilters {

        @Test
        @DisplayName("getLogs filters by environment")
        void filterByEnvironment() {
            logs.addLogsBatch(List.of(
                    new LogEntry(System.currentTimeMillis(), "INFO", "t", "m",
                            "production", "a", 1, "c", "u", "n"),
                    new LogEntry(System.currentTimeMillis(), "INFO", "t", "m",
                            "test", "a", 1, "c", "u", "n")));

            assertEquals(1, logs.getLogs(Environment.production, null, null,
                    null, null, null, null, 10).size());
            assertEquals(1, logs.getLogs(Environment.test, null, null,
                    null, null, null, null, 10).size());
        }

        @Test
        @DisplayName("getLogs filters by userId")
        void filterByUserId() {
            logs.addLogsBatch(List.of(
                    new LogEntry(System.currentTimeMillis(), "INFO", "t", "m",
                            "production", "a", 1, "c", "userA", "n"),
                    new LogEntry(System.currentTimeMillis(), "INFO", "t", "m",
                            "production", "a", 1, "c", "userB", "n")));

            assertEquals(1, logs.getLogs(null, null, null,
                    null, "userA", null, null, 10).size());
        }

        @Test
        @DisplayName("getLogs with skip and limit")
        void skipAndLimit() {
            for (int i = 0; i < 5; i++) {
                logs.addLogsBatch(List.of(
                        new LogEntry(System.currentTimeMillis() + i, "INFO", "t", "msg" + i,
                                "production", "a", 1, "c", "u", "n")));
            }

            assertEquals(2, logs.getLogs(null, null, null,
                    null, null, null, 0, 2).size());
            assertEquals(3, logs.getLogs(null, null, null,
                    null, null, null, 2, 10).size());
        }

        @Test
        @DisplayName("getLogs no filters — returns all")
        void noFilters() {
            logs.addLogsBatch(List.of(
                    new LogEntry(System.currentTimeMillis(), "INFO", "t", "m1",
                            "production", "a", 1, "c", "u", "n"),
                    new LogEntry(System.currentTimeMillis(), "WARN", "t", "m2",
                            "production", "a", 1, "c", "u", "n")));

            assertEquals(2, logs.getLogs(null, null, null,
                    null, null, null, null, null).size());
        }
    }

    // ─── GDPR ───────────────────────────────────────────────────

    @Nested
    @DisplayName("GDPR Pseudonymization")
    class Gdpr {

        @Test
        @DisplayName("pseudonymizeByUserId — replaces userId")
        void pseudonymize() {
            logs.addLogsBatch(List.of(
                    new LogEntry(System.currentTimeMillis(), "INFO", "t", "m",
                            "production", "a", 1, "c1", "real_user", "n"),
                    new LogEntry(System.currentTimeMillis(), "WARN", "t", "m",
                            "production", "a", 1, "c2", "real_user", "n"),
                    new LogEntry(System.currentTimeMillis(), "INFO", "t", "m",
                            "production", "a", 1, "c3", "other_user", "n")));

            long updated = logs.pseudonymizeByUserId("real_user", "anon_123");
            assertEquals(2, updated);

            assertEquals(2, logs.getLogs(null, null, null,
                    null, "anon_123", null, null, 10).size());
            assertEquals(0, logs.getLogs(null, null, null,
                    null, "real_user", null, null, 10).size());
        }

        @Test
        @DisplayName("pseudonymizeByUserId non-existent — returns 0")
        void pseudonymizeNonExistent() {
            assertEquals(0, logs.pseudonymizeByUserId("ghost", "anon"));
        }
    }
}
