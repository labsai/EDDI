/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.properties.mongo;

import ai.labs.eddi.configs.properties.IUserMemoryStore;
import com.mongodb.client.MongoDatabase;
import io.quarkus.runtime.StartupEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.*;

class PropertiesMigrationServiceTest {

    private MongoDatabase database;
    private IUserMemoryStore userMemoryStore;
    private StartupEvent startupEvent;

    @BeforeEach
    void setUp() {
        database = mock(MongoDatabase.class);
        userMemoryStore = mock(IUserMemoryStore.class);
        startupEvent = mock(StartupEvent.class);
    }

    @Test
    void shouldSkipMigrationInPostgresMode() {
        // Given
        PropertiesMigrationService service = new PropertiesMigrationService(database, userMemoryStore, "postgres");

        // When
        service.onStartup(startupEvent);

        // Then
        // Verify database is never touched
        verifyNoInteractions(database);
        verifyNoInteractions(userMemoryStore);
    }

    @Test
    void shouldAttemptMigrationInMongoMode() {
        // Given
        PropertiesMigrationService service = new PropertiesMigrationService(database, userMemoryStore, "mongodb");
        // For the sake of test, let's just make it throw an exception early or mock the
        // collection
        // To avoid deep mocking of MongoCollection/Iterable etc, we can just verify the
        // database is accessed.
        when(database.listCollectionNames()).thenThrow(new RuntimeException("Simulated check"));

        // When
        service.onStartup(startupEvent);

        // Then
        // Verified database was accessed
        verify(database).listCollectionNames();
    }
}
