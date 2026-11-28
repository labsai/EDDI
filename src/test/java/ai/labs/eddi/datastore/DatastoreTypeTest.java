/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatastoreTypeTest {

    @ParameterizedTest
    @ValueSource(strings = {"postgres", "Postgres", "POSTGRES", " postgres ", "postgresql", "PostgreSQL", "\tpostgresql\n"})
    void postgresSpellings(String raw) {
        assertEquals(DatastoreType.POSTGRES, DatastoreType.parse(raw));
        assertTrue(DatastoreType.isPostgres(raw));
    }

    @ParameterizedTest
    @ValueSource(strings = {"mongodb", "MongoDB", " MONGODB "})
    void mongoSpellings(String raw) {
        assertEquals(DatastoreType.MONGODB, DatastoreType.parse(raw));
        assertFalse(DatastoreType.isPostgres(raw));
    }

    @Test
    void unsetAndBlankMeanTheDefault() {
        assertEquals(DatastoreType.MONGODB, DatastoreType.parse(null));
        assertEquals(DatastoreType.MONGODB, DatastoreType.parse(""));
        assertEquals(DatastoreType.MONGODB, DatastoreType.parse("   "));
    }

    @ParameterizedTest
    @ValueSource(strings = {"mongo", "pg", "postgre", "postgres-sql", "mysql", "redis", "postgres,mongodb", "true"})
    void anythingElseIsRefusedWithAMessageThatNamesTheAcceptedValues(String raw) {
        var failure = assertThrows(IllegalArgumentException.class, () -> DatastoreType.parse(raw));
        assertTrue(failure.getMessage().contains("'" + raw + "'"), failure.getMessage());
        assertTrue(failure.getMessage().contains("mongodb") && failure.getMessage().contains("postgres"), failure.getMessage());
        assertThrows(IllegalArgumentException.class, () -> DatastoreType.isPostgres(raw));
    }
}
