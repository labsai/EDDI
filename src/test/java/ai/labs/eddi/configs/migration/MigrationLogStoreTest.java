/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.migration;

import ai.labs.eddi.configs.migration.model.MigrationLog;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link MigrationLogStore}.
 */
class MigrationLogStoreTest {

    private MongoCollection<MigrationLog> collection;
    private MigrationLogStore store;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        MongoDatabase database = mock(MongoDatabase.class);
        collection = mock(MongoCollection.class);
        when(database.getCollection(eq("migrationlog"), eq(MigrationLog.class))).thenReturn(collection);
        store = new MigrationLogStore(database);
    }

    @Test
    @DisplayName("readMigrationLog — returns log when found")
    void readMigrationLog_found() {
        var log = new MigrationLog("test-migration");
        @SuppressWarnings("unchecked")
        FindIterable<MigrationLog> iterable = mock(FindIterable.class);
        when(collection.find(any(Document.class))).thenReturn(iterable);
        when(iterable.first()).thenReturn(log);

        MigrationLog result = store.readMigrationLog("test-migration");

        assertNotNull(result);
        assertEquals("test-migration", result.getName());
    }

    @Test
    @DisplayName("readMigrationLog — returns null when not found")
    void readMigrationLog_notFound() {
        @SuppressWarnings("unchecked")
        FindIterable<MigrationLog> iterable = mock(FindIterable.class);
        when(collection.find(any(Document.class))).thenReturn(iterable);
        when(iterable.first()).thenReturn(null);

        assertNull(store.readMigrationLog("nonexistent"));
    }

    @Test
    @DisplayName("createMigrationLog — delegates to collection.insertOne")
    void createMigrationLog_delegates() {
        var log = new MigrationLog("new-migration");

        store.createMigrationLog(log);

        verify(collection).insertOne(log);
    }

    @Test
    @DisplayName("constructor — throws on null database")
    void constructor_nullDatabase() {
        assertThrows(IllegalArgumentException.class, () -> new MigrationLogStore(null));
    }
}
