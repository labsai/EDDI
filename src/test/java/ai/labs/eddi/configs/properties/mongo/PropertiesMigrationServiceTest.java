/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.properties.mongo;

import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.configs.properties.model.Property.Visibility;
import org.mockito.ArgumentCaptor;
import ai.labs.eddi.configs.properties.model.UserMemoryEntry;
import ai.labs.eddi.secrets.sanitize.SecretScrubber;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Set;
import java.util.stream.Collectors;

import com.mongodb.client.ListCollectionNamesIterable;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
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
        PropertiesMigrationService service = service("postgres");

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
        PropertiesMigrationService service = service("mongodb");
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
            var service = service("mongodb");
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
            var service = service("mongodb");
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
            var service = service("mongodb");

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
            verify(userMemoryStore, times(2)).insertIfAbsent(any(UserMemoryEntry.class));
            verify(legacyCollection).renameCollection(any(MongoNamespace.class));
        }

        @Test
        @DisplayName("should skip document without userId")
        @SuppressWarnings("unchecked")
        void skipDocumentWithoutUserId() throws Exception {
            // Given
            var service = service("mongodb");
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
            verify(userMemoryStore, never()).insertIfAbsent(any());
        }

        /**
         * The rename retires the source collection, and {@code collectionExists} is
         * false afterwards — so a rename on a partial run makes the migration a
         * permanent no-op. A transient Mongo error on three of four hundred users then
         * strands those users' long-term properties in the backup collection, where
         * their next conversation loads nothing and recovery means renaming it back by
         * hand. The loop is idempotent (upsert is keyed on userId and key), so leaving
         * the collection in place costs a retry and buys the entries back.
         */
        @Test
        @DisplayName("a failed entry leaves the legacy collection in place so the next boot retries")
        @SuppressWarnings("unchecked")
        void partialFailureDoesNotRetireTheSource() throws Exception {
            // Given
            var service = service("mongodb");
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
            doThrow(new RuntimeException("DB error")).when(userMemoryStore).insertIfAbsent(any());

            // When — should not throw
            service.onStartup(startupEvent);

            // Then — the loop still runs to the end, but the source is NOT retired
            verify(userMemoryStore).insertIfAbsent(any(UserMemoryEntry.class));
            verify(legacyCollection, never()).renameCollection(any(MongoNamespace.class));
        }

        /**
         * A document with no userId can never be migrated — not on this boot, not on
         * any later one. Holding the rename back for it re-ran the whole migration on
         * every startup, forever. It is skipped, and its contents survive in the backup
         * collection the source is renamed to.
         */
        @Test
        @DisplayName("a document without a userId does not hold the rename back — retrying it can never succeed")
        @SuppressWarnings("unchecked")
        void unownedDocumentDoesNotHoldTheRenameBack() throws Exception {
            var service = service("mongodb");
            // Build both iterables before stubbing: mockIterableOf() mocks internally, and
            // calling it inside a when(...) chain is nested stubbing, which Mockito
            // rejects.
            var iterable1 = mockIterableOf("properties");
            var iterable2 = mockIterableOf();
            when(database.listCollectionNames()).thenReturn(iterable1).thenReturn(iterable2);

            MongoCollection<Document> legacyCollection = mock(MongoCollection.class);
            when(database.getCollection("properties")).thenReturn(legacyCollection);
            when(legacyCollection.countDocuments()).thenReturn(1L);
            when(database.getName()).thenReturn("testdb");

            var doc = new Document("_id", new ObjectId()).append("key1", "value1");

            FindIterable<Document> findIterable = mock(FindIterable.class);
            MongoCursor<Document> cursor = mock(MongoCursor.class);
            when(legacyCollection.find()).thenReturn(findIterable);
            when(findIterable.iterator()).thenReturn(cursor);
            when(cursor.hasNext()).thenReturn(true, false);
            when(cursor.next()).thenReturn(doc);

            service.onStartup(startupEvent);

            verify(userMemoryStore, never()).insertIfAbsent(any());
            verify(legacyCollection).renameCollection(any(MongoNamespace.class));
        }

        /**
         * Live-reproduced: a user's v6 value was replaced by the stale v5 one, and —
         * because the source was never retired — again on every restart.
         */
        @Test
        @DisplayName("a key the user already has in usermemories keeps its newer value")
        @SuppressWarnings("unchecked")
        void existingV6EntryIsNotOverwritten() throws Exception {
            var service = service("mongodb");
            var iterable1 = mockIterableOf("properties");
            var iterable2 = mockIterableOf();
            when(database.listCollectionNames()).thenReturn(iterable1).thenReturn(iterable2);

            MongoCollection<Document> legacyCollection = mock(MongoCollection.class);
            when(database.getCollection("properties")).thenReturn(legacyCollection);
            when(legacyCollection.countDocuments()).thenReturn(1L);
            when(database.getName()).thenReturn("testdb");

            var doc = new Document("_id", new ObjectId()).append("userId", "user-1").append("lang", "OLD-v5").append("color", "red");
            FindIterable<Document> findIterable = mock(FindIterable.class);
            MongoCursor<Document> cursor = mock(MongoCursor.class);
            when(legacyCollection.find()).thenReturn(findIterable);
            when(findIterable.iterator()).thenReturn(cursor);
            when(cursor.hasNext()).thenReturn(true, false);
            when(cursor.next()).thenReturn(doc);

            // "lang" already has a global entry: the store declines the insert
            when(userMemoryStore.insertIfAbsent(argThat(e -> e != null && "lang".equals(e.key())))).thenReturn(null);
            when(userMemoryStore.insertIfAbsent(argThat(e -> e != null && "color".equals(e.key())))).thenReturn("new-id");

            service.onStartup(startupEvent);

            // Never an updating upsert: an existing value must not be replaced, and the
            // insert-if-absent is what makes that atomic.
            verify(userMemoryStore, never()).upsert(any());
            var written = ArgumentCaptor.forClass(UserMemoryEntry.class);
            verify(userMemoryStore, times(2)).insertIfAbsent(written.capture());
            assertTrue(written.getAllValues().stream().allMatch(e -> e.visibility() == Visibility.global));
            verify(legacyCollection).renameCollection(any(MongoNamespace.class));
        }

        @Test
        @DisplayName("should drop existing backup collection before rename")
        @SuppressWarnings("unchecked")
        void dropsExistingBackup() throws Exception {
            // Given
            var service = service("mongodb");
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
            var service = service("mongodb");
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
     * Long-term memories are loaded into every future conversation as properties,
     * so whatever this migration copies reaches template data — and from there
     * prompts and outbound API calls. EDDI 5 kept the caller's per-request identity
     * under {@code userInfo}, a platform bearer token included, and the migration
     * used to copy it into {@code global} memory for every such user.
     * <p>
     * The fake credentials here are zero-entropy on purpose: the scrubber also
     * redacts random-looking strings by entropy, so a random fake would be caught
     * even if the name- and structure-based rules this migration relies on were
     * broken, and the tests would pass for the wrong reason.
     */
    @Nested
    @DisplayName("credentials are not migrated into long-term memory")
    class CredentialsAreNotMigrated {

        private static final String FAKE_TOKEN = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

        @SuppressWarnings("unchecked")
        private List<UserMemoryEntry> migrate(PropertiesMigrationService service, Document legacy) throws Exception {
            var withLegacy = mockIterableOf("properties");
            var afterRename = mockIterableOf();
            when(database.listCollectionNames()).thenReturn(withLegacy, afterRename);
            MongoCollection<Document> legacyCollection = mock(MongoCollection.class);
            when(database.getCollection("properties")).thenReturn(legacyCollection);
            when(legacyCollection.countDocuments()).thenReturn(1L);
            when(database.getName()).thenReturn("testdb");
            FindIterable<Document> findIterable = mock(FindIterable.class);
            MongoCursor<Document> cursor = mock(MongoCursor.class);
            when(legacyCollection.find()).thenReturn(findIterable);
            when(findIterable.iterator()).thenReturn(cursor);
            when(cursor.hasNext()).thenReturn(true, false);
            when(cursor.next()).thenReturn(legacy);
            when(userMemoryStore.insertIfAbsent(any(UserMemoryEntry.class))).thenReturn("new-id");

            service.onStartup(startupEvent);

            var captor = ArgumentCaptor.forClass(UserMemoryEntry.class);
            verify(userMemoryStore, atLeast(0)).insertIfAbsent(captor.capture());
            // A skipped key is not a failure: the source is still retired.
            verify(legacyCollection).renameCollection(any(MongoNamespace.class));
            return captor.getAllValues();
        }

        private static String everythingMigrated(List<UserMemoryEntry> entries) {
            var all = new StringBuilder();
            for (UserMemoryEntry entry : entries) {
                all.append(entry.key()).append('=').append(new Document("v", entry.value()).toJson()).append('\n');
            }
            return all.toString();
        }

        @Test
        @DisplayName("userInfo is not migrated, and no entry holds its token anywhere; ordinary preferences are")
        void userInfoIsNotMigrated() throws Exception {
            var legacy = new Document("_id", new ObjectId()).append("userId", "synthetic-user").append("lang", "de")
                    .append("favoriteTopic", "algebra")
                    .append("userInfo", new Document("token", FAKE_TOKEN).append("courseId", "course-1").append("firstName", "Ada"));

            var entries = migrate(service(), legacy);

            assertEquals(Set.of("lang", "favoriteTopic"), entries.stream().map(UserMemoryEntry::key).collect(Collectors.toSet()));
            assertFalse(everythingMigrated(entries).contains(FAKE_TOKEN), "the token reached long-term memory");
        }

        @Test
        @DisplayName("a key named like a credential is skipped, whatever its value looks like")
        void credentialNamedKeyIsSkipped() throws Exception {
            var legacy = new Document("_id", new ObjectId()).append("userId", "synthetic-user").append("apiKey", FAKE_TOKEN)
                    .append("lang", "de");

            var entries = migrate(service(), legacy);

            assertEquals(List.of("lang"), entries.stream().map(UserMemoryEntry::key).toList());
            assertFalse(everythingMigrated(entries).contains(FAKE_TOKEN));
        }

        @Test
        @DisplayName("a credential nested inside an ordinary-looking key is found, and the key is skipped")
        void nestedCredentialIsFound() throws Exception {
            var legacy = new Document("_id", new ObjectId()).append("userId", "synthetic-user")
                    .append("session", new Document("profile", new Document("accessToken", FAKE_TOKEN)))
                    .append("lang", "de");

            var entries = migrate(service(), legacy);

            assertEquals(List.of("lang"), entries.stream().map(UserMemoryEntry::key).toList());
            assertFalse(everythingMigrated(entries).contains(FAKE_TOKEN));
        }

        @Test
        @DisplayName("an ordinary long-term property migrates unchanged")
        void ordinaryPropertyMigratesUnchanged() throws Exception {
            var preferences = new Document("theme", "dark").append("fontSize", 14);
            var legacy = new Document("_id", new ObjectId()).append("userId", "synthetic-user").append("preferences", preferences)
                    .append("overallConversationCount", 7);

            var entries = migrate(service(), legacy);

            assertEquals(2, entries.size());
            var byKey = entries.stream().collect(Collectors.toMap(UserMemoryEntry::key, UserMemoryEntry::value));
            assertEquals(preferences, byKey.get("preferences"));
            assertEquals(7, byKey.get("overallConversationCount"));
            assertEquals(Visibility.global, entries.getFirst().visibility());
        }

        /**
         * The scrubber judges a string by the name it sits under. For {@code apiKey:
         * {value: "hunter2"}} that is {@code value}, so the credential name one level
         * up has to be checked by the migration itself.
         */
        @Test
        @DisplayName("a credential-named key is skipped whatever its value's shape, at any depth")
        void credentialNameWithObjectValueIsSkipped() throws Exception {
            var legacy = new Document("_id", new ObjectId()).append("userId", "synthetic-user")
                    .append("apiKey", new Document("value", "hunter2"))
                    .append("settings", new Document("integration", new Document("password", new Document("plain", "hunter2"))))
                    .append("accounts", List.of(new Document("token", new Document("v", "hunter2"))))
                    .append("nested", List.of(List.of(new Document("password", new Document("v", "hunter2")))))
                    .append("lang", "de");

            var entries = migrate(service(), legacy);

            assertEquals(List.of("lang"), entries.stream().map(UserMemoryEntry::key).toList());
            assertFalse(everythingMigrated(entries).contains("hunter2"));
        }

        /**
         * A BSON ObjectId is an identifier by type. As extended JSON it is a
         * random-looking 24-character hex string, which the scrubber's entropy rule
         * would take for a key.
         */
        @Test
        @DisplayName("an ObjectId value is not taken for a credential")
        void objectIdIsNotACredential() throws Exception {
            var lastOrder = new ObjectId("65a1b2c3d4e5f6a7b8c9d0e1");
            var legacy = new Document("_id", new ObjectId()).append("userId", "synthetic-user").append("lastOrder", lastOrder)
                    .append("history", List.of(new Document("order", lastOrder)));

            var entries = migrate(service(), legacy);

            assertEquals(Set.of("lastOrder", "history"), entries.stream().map(UserMemoryEntry::key).collect(Collectors.toSet()));
        }

        @Test
        @DisplayName("the skip list is configurable; a credential is still caught when userInfo is taken off it")
        void skipListIsConfigurable() throws Exception {
            var service = new PropertiesMigrationService(database, userMemoryStore, "mongodb", scrubber(), List.of("lang"));
            var legacy = new Document("_id", new ObjectId()).append("userId", "synthetic-user").append("lang", "de")
                    .append("favoriteTopic", "algebra").append("userInfo", new Document("token", FAKE_TOKEN));

            var entries = migrate(service, legacy);

            assertEquals(List.of("favoriteTopic"), entries.stream().map(UserMemoryEntry::key).toList());
        }
    }

    private static SecretScrubber scrubber() {
        return new SecretScrubber(new ObjectMapper());
    }

    private PropertiesMigrationService service(String datastoreType) {
        return new PropertiesMigrationService(database, userMemoryStore, datastoreType, scrubber(),
                List.of(PropertiesMigrationService.DEFAULT_SKIP_KEYS));
    }

    private PropertiesMigrationService service() {
        return service("mongodb");
    }

    /**
     * Helper to mock MongoDatabase.listCollectionNames() which returns an iterable
     * of strings.
     */
    @SuppressWarnings("unchecked")
    private static ListCollectionNamesIterable mockIterableOf(String... names) {
        var iterable = mock(ListCollectionNamesIterable.class);
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
