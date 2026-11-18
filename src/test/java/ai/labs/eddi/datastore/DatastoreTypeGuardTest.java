/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatastoreTypeGuardTest {

    @Test
    void consistentConfigurationsStart() {
        assertDoesNotThrow(() -> DatastoreTypeGuard.check("mongodb", false));
        assertDoesNotThrow(() -> DatastoreTypeGuard.check(null, false));
        assertDoesNotThrow(() -> DatastoreTypeGuard.check("postgres", true));
        assertDoesNotThrow(() -> DatastoreTypeGuard.check("Postgres", true));
        assertDoesNotThrow(() -> DatastoreTypeGuard.check("postgresql", true));
    }

    @Test
    void anUnknownTypeStopsTheStartup() {
        assertThrows(IllegalArgumentException.class, () -> DatastoreTypeGuard.check("postgre", false));
        assertThrows(IllegalArgumentException.class, () -> DatastoreTypeGuard.check("postgre", true));
    }

    /** What .env.example shipped to docker-compose.postgres-only.yml: the profile says postgres, the variable says mongodb. */
    @Test
    void thePostgresProfileWithAMongoTypeStopsTheStartup() {
        var failure = assertThrows(IllegalStateException.class, () -> DatastoreTypeGuard.check("mongodb", true));
        assertTrue(failure.getMessage().contains("EDDI_DATASTORE_TYPE"), failure.getMessage());
        assertThrows(IllegalStateException.class, () -> DatastoreTypeGuard.check(null, true));
    }
}
