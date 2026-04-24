/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.configs.migration.model.MigrationLog;
import org.junit.jupiter.api.*;

import javax.sql.DataSource;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link PostgresMigrationLogStore} using Testcontainers.
 *
 * @since 6.0.0
 */
@DisplayName("PostgresMigrationLogStore IT")
class PostgresMigrationLogStoreTest extends PostgresTestBase {

    private static PostgresMigrationLogStore store;
    private static DataSource ds;

    @BeforeAll
    static void init() {
        var dsInstance = createDataSourceInstance();
        ds = dsInstance.get();
        store = new PostgresMigrationLogStore(dsInstance);
    }

    @BeforeEach
    void clean() {
        try {
            truncateTables(ds, "migration_log");
        } catch (SQLException ignored) {
        }
    }

    @Test
    @DisplayName("create + read round-trip")
    void createAndRead() {
        store.createMigrationLog(new MigrationLog("V6_rename_migration"));

        MigrationLog log = store.readMigrationLog("V6_rename_migration");
        assertNotNull(log);
        assertEquals("V6_rename_migration", log.getName());
    }

    @Test
    @DisplayName("read non-existent — returns null")
    void readNonExistent() {
        assertNull(store.readMigrationLog("nonexistent_migration"));
    }

    @Test
    @DisplayName("create duplicate — idempotent (ON CONFLICT DO NOTHING)")
    void duplicateCreate() {
        store.createMigrationLog(new MigrationLog("V6_qute_migration"));
        store.createMigrationLog(new MigrationLog("V6_qute_migration"));

        // Should not throw, and reading should still work
        assertNotNull(store.readMigrationLog("V6_qute_migration"));
    }

    @Test
    @DisplayName("multiple migrations tracked independently")
    void multipleMigrations() {
        store.createMigrationLog(new MigrationLog("migration_A"));
        store.createMigrationLog(new MigrationLog("migration_B"));

        assertNotNull(store.readMigrationLog("migration_A"));
        assertNotNull(store.readMigrationLog("migration_B"));
        assertNull(store.readMigrationLog("migration_C"));
    }
}
