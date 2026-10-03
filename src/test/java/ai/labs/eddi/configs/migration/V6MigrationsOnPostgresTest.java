/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.migration;

import com.mongodb.MongoTimeoutException;
import com.mongodb.client.MongoDatabase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * The EDDI 5 migrations rename and rewrite MongoDB collections, and EDDI 5
 * never ran on PostgreSQL. On a PostgreSQL deployment they must do nothing —
 * and above all must not touch their {@code MongoDatabase}: it is a lazy client
 * proxy whose first use creates a MongoClient against
 * {@code mongodb.connectionString}.
 *
 * <p>
 * Live, on a PostgreSQL-only deployment, {@code holdsRetention()} did exactly
 * that: {@code estimatedDocumentCount()} on the v5 marker collection waited 30
 * seconds for {@code mongodb:27017}, the catch answered "hold", and the
 * ended-conversation retention sweep was held for ever while the log claimed
 * the database came from EDDI 5.
 * </p>
 */
@DisplayName("V6 migrations on a PostgreSQL deployment")
class V6MigrationsOnPostgresTest {

    /** A MongoDB that is not there: every call fails the way the live one did. */
    private static MongoDatabase unreachableMongo() {
        return mock(MongoDatabase.class, invocation -> {
            throw new MongoTimeoutException("Timed out while waiting for a server ... mongodb:27017");
        });
    }

    @ParameterizedTest(name = "v6-rename.enabled={0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("the rename migration never holds retention, is never pending, and never touches MongoDB")
    void renameMigrationIsInert(boolean enabled) {
        MongoDatabase database = unreachableMongo();
        IMigrationLogStore migrationLog = mock(IMigrationLogStore.class);
        var migration = new V6RenameMigration(database, migrationLog, enabled, "postgres");

        assertFalse(migration.holdsRetention(), "no PostgreSQL database comes from EDDI 5");
        assertFalse(migration.isPending(), "a pending migration parks the deployment sweep and readiness");
        migration.runIfNeeded();

        verifyNoInteractions(database, migrationLog);
    }

    @ParameterizedTest(name = "v6-qute.enabled={0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("the Qute migration does nothing and never touches MongoDB")
    void quteMigrationIsInert(boolean enabled) {
        MongoDatabase database = unreachableMongo();
        IMigrationLogStore migrationLog = mock(IMigrationLogStore.class);

        new V6QuteMigration(database, migrationLog, mock(TemplateSyntaxMigrator.class), enabled, "postgres").runIfNeeded();

        verifyNoInteractions(database, migrationLog);
    }

    @Test
    @DisplayName("on MongoDB an unreadable database still holds retention — the fail-safe there is unchanged")
    void mongoStillFailsSafe() {
        var migration = new V6RenameMigration(unreachableMongo(), mock(IMigrationLogStore.class), false, "mongodb");

        assertTrue(migration.holdsRetention());
    }

    @Test
    @DisplayName("the datastore type is matched exactly, as DataStoreProducers matches it")
    void datastoreTypeIsMatchedExactly() {
        // "Postgres" selects the MongoDB stores in DataStoreProducers, so it must not
        // switch these migrations off either.
        var migration = new V6RenameMigration(unreachableMongo(), mock(IMigrationLogStore.class), false, "Postgres");

        assertTrue(migration.holdsRetention());
    }
}
