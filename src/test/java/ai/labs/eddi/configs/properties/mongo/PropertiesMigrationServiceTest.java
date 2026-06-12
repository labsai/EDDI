/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.properties.mongo;

import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.configs.properties.model.UserMemoryEntry;
import com.mongodb.MongoNamespace;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import io.quarkus.runtime.StartupEvent;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

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
        when(database.listCollectionNames()).thenThrow(new RuntimeException("Simulated check"));

        // When
        service.onStartup(startupEvent);

        // Then
        verify(database).listCollectionNames();
    }

    @Nested
    @DisplayName("migrateIfNeeded detailed scenarios")
    class MigrateIfNeededTests {

        @Test
        @DisplayName("should skip when legacy collection does not exist")
        void skipNoLegacyCollection() {
            // Given — listCollectionNames returns names without 'properties'
            var service = new PropertiesMigrationService(database, userMemoryStore, "mongodb");
            var iterable = mockIterableOf("users", "conversations");
            when(database.listCollectionNames()).thenReturn(iterable);

            // When
            service.onStartup(startupEvent);

            // Then — no collection access beyond listing
            verify(database, never()).getCollection("properties");
            verifyNoInteractions(userMemoryStore);
        }

        @Test
        @DisplayName("should skip when legacy collection is empty")
        @SuppressWarnings("unchecked")
        void skipWhenCollectionEmpty() {
            // Given
            var service = new PropertiesMigrationService(database, userMemoryStore, "mongodb");
            var iterable = mockIterableOf("properties", "other");
            when(database.listCollectionNames()).thenReturn(iterable);

            MongoCollection<Document> legacyCollection = mock(MongoCollection.class);
            when(database.getCollection("properties")).thenReturn(legacyCollection);
            when(legacyCollection.countDocuments()).thenReturn(0L);

            // When
            service.onStartup(startupEvent);

            // Then
            verifyNoInteractions(userMemoryStore);
        }

        @Test
        @DisplayName("should migrate properties and rename collection")
        @SuppressWarnings("unchecked")
        void successfulMigration() throws Exception {
            // Given
            var service = new PropertiesMigrationService(database, userMemoryStore, "mongodb");

            // First call for migrateIfNeeded, second call for rename backup check
            var iterable1 = mockIterableOf("properties", "other");
            var iterable2 = mockIterableOf("other");
            when(database.listCollectionNames())
                    .thenReturn(iterable1)
                    .thenReturn(iterable2);

            MongoCollection<Document> legacyCollection = mock(MongoCollection.class);
            when(database.getCollection("properties")).thenReturn(legacyCollection);
            when(legacyCollection.countDocuments()).thenReturn(1L);
            when(database.getName()).thenReturn("testdb");

            // Document with userId and properties
            var doc = new Document("_id", new ObjectId())
                    .append("userId", "user-123")
                    .append("favorite_color", "blue")
                    .append("age", 30);

            FindIterable<Document> findIterable = mock(FindIterable.class);
            MongoCursor<Document> cursor = mock(MongoCursor.class);
            when(legacyCollection.find()).thenReturn(findIterable);
            when(findIterable.iterator()).thenReturn(cursor);
            when(cursor.hasNext()).thenReturn(true, false);
            when(cursor.next()).thenReturn(doc);

            // When
            service.onStartup(startupEvent);

            // Then — upsert called for each non-system key
            verify(userMemoryStore, times(2)).upsert(any(UserMemoryEntry.class));
            verify(legacyCollection).renameCollection(any(MongoNamespace.class));
        }

        @Test
        @DisplayName("should skip document without userId")
        @SuppressWarnings("unchecked")
        void skipDocumentWithoutUserId() throws Exception {
            // Given
            var service = new PropertiesMigrationService(database, userMemoryStore, "mongodb");
            var iterable1 = mockIterableOf("properties");
            var iterable2 = mockIterableOf();
            when(database.listCollectionNames())
                    .thenReturn(iterable1)
                    .thenReturn(iterable2);

            MongoCollection<Document> legacyCollection = mock(MongoCollection.class);
            when(database.getCollection("properties")).thenReturn(legacyCollection);
            when(legacyCollection.countDocuments()).thenReturn(1L);
            when(database.getName()).thenReturn("testdb");

            // Document WITHOUT userId
            var doc = new Document("_id", new ObjectId())
                    .append("key1", "value1");

            FindIterable<Document> findIterable = mock(FindIterable.class);
            MongoCursor<Document> cursor = mock(MongoCursor.class);
            when(legacyCollection.find()).thenReturn(findIterable);
            when(findIterable.iterator()).thenReturn(cursor);
            when(cursor.hasNext()).thenReturn(true, false);
            when(cursor.next()).thenReturn(doc);

            // When
            service.onStartup(startupEvent);

            // Then — upsert NOT called since there's no userId
            verify(userMemoryStore, never()).upsert(any());
        }

        @Test
        @DisplayName("should handle upsert failure gracefully")
        @SuppressWarnings("unchecked")
        void handleUpsertFailure() throws Exception {
            // Given
            var service = new PropertiesMigrationService(database, userMemoryStore, "mongodb");
            var iterable1 = mockIterableOf("properties");
            var iterable2 = mockIterableOf();
            when(database.listCollectionNames())
                    .thenReturn(iterable1)
                    .thenReturn(iterable2);

            MongoCollection<Document> legacyCollection = mock(MongoCollection.class);
            when(database.getCollection("properties")).thenReturn(legacyCollection);
            when(legacyCollection.countDocuments()).thenReturn(1L);
            when(database.getName()).thenReturn("testdb");

            var doc = new Document("_id", new ObjectId())
                    .append("userId", "user-1")
                    .append("key1", "value1");

            FindIterable<Document> findIterable = mock(FindIterable.class);
            MongoCursor<Document> cursor = mock(MongoCursor.class);
            when(legacyCollection.find()).thenReturn(findIterable);
            when(findIterable.iterator()).thenReturn(cursor);
            when(cursor.hasNext()).thenReturn(true, false);
            when(cursor.next()).thenReturn(doc);

            // upsert throws
            doThrow(new RuntimeException("DB error")).when(userMemoryStore).upsert(any());

            // When — should not throw
            service.onStartup(startupEvent);

            // Then — migration continues despite failure; rename still attempted
            verify(legacyCollection).renameCollection(any(MongoNamespace.class));
        }

        @Test
        @DisplayName("should drop existing backup collection before rename")
        @SuppressWarnings("unchecked")
        void dropsExistingBackup() throws Exception {
            // Given
            var service = new PropertiesMigrationService(database, userMemoryStore, "mongodb");
            var iterable1 = mockIterableOf("properties");
            var iterable2 = mockIterableOf("properties_migrated_v6");
            when(database.listCollectionNames())
                    .thenReturn(iterable1)
                    .thenReturn(iterable2);

            MongoCollection<Document> legacyCollection = mock(MongoCollection.class);
            when(database.getCollection("properties")).thenReturn(legacyCollection);
            when(legacyCollection.countDocuments()).thenReturn(1L);
            when(database.getName()).thenReturn("testdb");

            MongoCollection<Document> backupCollection = mock(MongoCollection.class);
            when(database.getCollection("properties_migrated_v6")).thenReturn(backupCollection);

            // Empty document set
            FindIterable<Document> findIterable = mock(FindIterable.class);
            MongoCursor<Document> cursor = mock(MongoCursor.class);
            when(legacyCollection.find()).thenReturn(findIterable);
            when(findIterable.iterator()).thenReturn(cursor);
            when(cursor.hasNext()).thenReturn(false);

            // When
            service.onStartup(startupEvent);

            // Then — backup collection dropped before rename
            verify(backupCollection).drop();
            verify(legacyCollection).renameCollection(any(MongoNamespace.class));
        }

        @Test
        @DisplayName("should handle rename failure gracefully")
        @SuppressWarnings("unchecked")
        void handleRenameFailure() throws Exception {
            // Given
            var service = new PropertiesMigrationService(database, userMemoryStore, "mongodb");
            var iterable1 = mockIterableOf("properties");
            var iterable2 = mockIterableOf();
            when(database.listCollectionNames())
                    .thenReturn(iterable1)
                    .thenReturn(iterable2);

            MongoCollection<Document> legacyCollection = mock(MongoCollection.class);
            when(database.getCollection("properties")).thenReturn(legacyCollection);
            when(legacyCollection.countDocuments()).thenReturn(1L);
            when(database.getName()).thenReturn("testdb");

            FindIterable<Document> findIterable = mock(FindIterable.class);
            MongoCursor<Document> cursor = mock(MongoCursor.class);
            when(legacyCollection.find()).thenReturn(findIterable);
            when(findIterable.iterator()).thenReturn(cursor);
            when(cursor.hasNext()).thenReturn(false);

            doThrow(new RuntimeException("Rename failed")).when(legacyCollection).renameCollection(any(MongoNamespace.class));

            // When — should not throw
            service.onStartup(startupEvent);

            // Then — rename was attempted
            verify(legacyCollection).renameCollection(any(MongoNamespace.class));
        }
    }

    /**
     * Helper to mock MongoDatabase.listCollectionNames() which returns an iterable
     * of strings.
     */
    @SuppressWarnings("unchecked")
    private static com.mongodb.client.ListCollectionNamesIterable mockIterableOf(String... names) {
        var iterable = mock(com.mongodb.client.ListCollectionNamesIterable.class);
        doReturn(mockCursorOf(names)).when(iterable).iterator();
        return iterable;
    }

    @SuppressWarnings("unchecked")
    private static MongoCursor<String> mockCursorOf(String... names) {
        MongoCursor<String> cursor = mock(MongoCursor.class);
        List<String> list = Arrays.asList(names);
        Iterator<String> iter = list.iterator();
        when(cursor.hasNext()).thenAnswer(inv -> iter.hasNext());
        when(cursor.next()).thenAnswer(inv -> iter.next());
        return cursor;
    }
}
