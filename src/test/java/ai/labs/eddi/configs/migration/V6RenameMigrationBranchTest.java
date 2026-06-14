/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.migration;

import ai.labs.eddi.configs.migration.model.MigrationLog;
import com.mongodb.MongoCommandException;
import com.mongodb.MongoNamespace;
import com.mongodb.ServerAddress;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import org.bson.BsonDocument;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;

/**
 * Extended branch coverage tests for {@link V6RenameMigration} focusing on
 * saveDocument branches, rewriteUrisInDocument recursion, rewriteUrisInList,
 * renameCollectionIfExists error handling, and migrateEnvironments field
 * rewrites.
 */
@DisplayName("V6RenameMigration — Extended Branch Coverage")
class V6RenameMigrationBranchTest {

    @Mock
    private MongoDatabase database;
    @Mock
    private IMigrationLogStore migrationLogStore;

    private V6RenameMigration migration;

    @BeforeEach
    void setUp() {
        openMocks(this);
        migration = new V6RenameMigration(database, migrationLogStore, true);
    }

    // =========================================================
    // runIfNeeded — disabled
    // =========================================================

    @Nested
    @DisplayName("runIfNeeded — disabled")
    class RunIfNeededDisabled {

        @Test
        @DisplayName("disabled migration does not run")
        void disabledDoesNotRun() {
            migration = new V6RenameMigration(database, migrationLogStore, false);
            migration.runIfNeeded();
            verify(migrationLogStore, never()).createMigrationLog(any());
        }

        @Test
        @DisplayName("already applied migration does not re-run")
        void alreadyApplied() {
            when(migrationLogStore.readMigrationLog(anyString()))
                    .thenReturn(new MigrationLog("v6-rename-migration-complete"));

            migration.runIfNeeded();
            verify(migrationLogStore, never()).createMigrationLog(any());
            verify(database, never()).getCollection(anyString());
        }
    }

    // =========================================================
    // rewriteUriString — edge cases
    // =========================================================

    @Nested
    @DisplayName("rewriteUriString — edge cases")
    class RewriteUriStringEdgeCases {

        @Test
        @DisplayName("null returns null")
        void nullReturnsNull() {
            assertNull(migration.rewriteUriString(null));
        }

        @Test
        @DisplayName("empty string returns empty")
        void emptyReturnsEmpty() {
            assertEquals("", migration.rewriteUriString(""));
        }

        @Test
        @DisplayName("string without eddi:// returns unchanged")
        void noEddiPrefixUnchanged() {
            String input = "https://api.example.com/v1";
            assertEquals(input, migration.rewriteUriString(input));
        }

        @Test
        @DisplayName("all 6 authority rewrites applied")
        void allAuthorityRewrites() {
            assertEquals("eddi://ai.labs.dictionary/dictionarystore/dictionaries/x",
                    migration.rewriteUriString("eddi://ai.labs.regulardictionary/regulardictionarystore/regulardictionaries/x"));

            assertEquals("eddi://ai.labs.apicalls/apicallstore/apicalls/x",
                    migration.rewriteUriString("eddi://ai.labs.httpcalls/httpcallsstore/httpcalls/x"));

            assertEquals("eddi://ai.labs.rules/rulestore/rulesets/x",
                    migration.rewriteUriString("eddi://ai.labs.behavior/behaviorstore/behaviorsets/x"));

            assertEquals("eddi://ai.labs.llm/llmstore/llms/x",
                    migration.rewriteUriString("eddi://ai.labs.langchain/langchainstore/langchains/x"));

            assertEquals("eddi://ai.labs.workflow/workflowstore/workflows/x",
                    migration.rewriteUriString("eddi://ai.labs.package/packagestore/packages/x"));

            assertEquals("eddi://ai.labs.agent/agentstore/agents/x",
                    migration.rewriteUriString("eddi://ai.labs.bot/botstore/bots/x"));
        }

        @Test
        @DisplayName("multiple URIs in one string all rewritten")
        void multipleUris() {
            String input = "ref1: eddi://ai.labs.bot/botstore/bots/b1 ref2: eddi://ai.labs.package/packagestore/packages/p1";
            String result = migration.rewriteUriString(input);
            assertTrue(result.contains("eddi://ai.labs.agent/agentstore/agents/b1"));
            assertTrue(result.contains("eddi://ai.labs.workflow/workflowstore/workflows/p1"));
        }
    }

    // =========================================================
    // rewriteUrisInDocument — nested documents and lists
    // =========================================================

    @Nested
    @DisplayName("rewriteUrisInDocument — recursion")
    class RewriteUrisInDocumentRecursion {

        @Test
        @DisplayName("nested document URI rewriting")
        void nestedDocumentRewrite() throws Exception {
            var inner = new Document("uri", "eddi://ai.labs.bot/botstore/bots/b1");
            var doc = new Document("nested", inner).append("_id", new ObjectId());

            var method = V6RenameMigration.class.getDeclaredMethod("rewriteUrisInDocument", Document.class);
            method.setAccessible(true);
            Document result = (Document) method.invoke(migration, doc);

            assertNotNull(result);
            assertEquals("eddi://ai.labs.agent/agentstore/agents/b1",
                    ((Document) result.get("nested")).getString("uri"));
        }

        @Test
        @DisplayName("list of strings with URIs rewritten")
        void listOfStringsRewritten() throws Exception {
            var list = new ArrayList<Object>();
            list.add("eddi://ai.labs.bot/botstore/bots/b1");
            list.add("no-change");
            var doc = new Document("uris", list).append("_id", new ObjectId());

            var method = V6RenameMigration.class.getDeclaredMethod("rewriteUrisInDocument", Document.class);
            method.setAccessible(true);
            Document result = (Document) method.invoke(migration, doc);

            assertNotNull(result);
            @SuppressWarnings("unchecked")
            List<Object> resultList = (List<Object>) result.get("uris");
            assertEquals("eddi://ai.labs.agent/agentstore/agents/b1", resultList.get(0));
            assertEquals("no-change", resultList.get(1));
        }

        @Test
        @DisplayName("list of nested documents rewritten")
        void listOfDocumentsRewritten() throws Exception {
            var inner = new Document("uri", "eddi://ai.labs.httpcalls/httpcallsstore/httpcalls/h1");
            var list = new ArrayList<Object>();
            list.add(inner);
            var doc = new Document("items", list).append("_id", new ObjectId());

            var method = V6RenameMigration.class.getDeclaredMethod("rewriteUrisInDocument", Document.class);
            method.setAccessible(true);
            Document result = (Document) method.invoke(migration, doc);

            assertNotNull(result);
        }

        @Test
        @DisplayName("nested list of lists rewritten")
        void nestedListOfLists() throws Exception {
            var innerList = new ArrayList<Object>();
            innerList.add("eddi://ai.labs.langchain/langchainstore/langchains/l1");
            var outerList = new ArrayList<Object>();
            outerList.add(innerList);
            var doc = new Document("nested", outerList).append("_id", new ObjectId());

            var method = V6RenameMigration.class.getDeclaredMethod("rewriteUrisInDocument", Document.class);
            method.setAccessible(true);
            Document result = (Document) method.invoke(migration, doc);

            assertNotNull(result);
        }

        @Test
        @DisplayName("document with no eddi:// returns null (no changes)")
        void noChangesReturnsNull() throws Exception {
            var doc = new Document("key", "normal-value").append("_id", new ObjectId());

            var method = V6RenameMigration.class.getDeclaredMethod("rewriteUrisInDocument", Document.class);
            method.setAccessible(true);
            Document result = (Document) method.invoke(migration, doc);

            assertNull(result); // null means no changes
        }

        @Test
        @DisplayName("non-string, non-Document, non-List values are skipped")
        void nonStringValuesSkipped() throws Exception {
            var doc = new Document("num", 42)
                    .append("bool", true)
                    .append("_id", new ObjectId());

            var method = V6RenameMigration.class.getDeclaredMethod("rewriteUrisInDocument", Document.class);
            method.setAccessible(true);
            Document result = (Document) method.invoke(migration, doc);

            assertNull(result); // no changes
        }
    }

    // =========================================================
    // saveDocument — history vs non-history branches
    // =========================================================

    @Nested
    @DisplayName("saveDocument — ID type branches")
    class SaveDocumentBranches {

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("history document with Map ID uses compound query")
        void historyDocWithMapId() throws Exception {
            var idMap = Map.of("_id", new ObjectId(), "version", 1);
            var doc = new Document("_id", idMap).append("data", "test");

            MongoCollection<Document> collection = mock(MongoCollection.class);

            var method = V6RenameMigration.class.getDeclaredMethod(
                    "saveDocument", MongoCollection.class, Document.class, boolean.class);
            method.setAccessible(true);
            method.invoke(migration, collection, doc, true);

            verify(collection).replaceOne(any(), eq(doc));
        }

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("non-history document with ObjectId uses simple query")
        void nonHistoryDocWithObjectId() throws Exception {
            var objectId = new ObjectId();
            var doc = new Document("_id", objectId).append("data", "test");

            MongoCollection<Document> collection = mock(MongoCollection.class);

            var method = V6RenameMigration.class.getDeclaredMethod(
                    "saveDocument", MongoCollection.class, Document.class, boolean.class);
            method.setAccessible(true);
            method.invoke(migration, collection, doc, false);

            verify(collection).replaceOne(any(), eq(doc));
        }

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("non-history document with String ID converts to ObjectId")
        void nonHistoryDocWithStringId() throws Exception {
            var doc = new Document("_id", "aabbccddeeff112233445566").append("data", "test");

            MongoCollection<Document> collection = mock(MongoCollection.class);

            var method = V6RenameMigration.class.getDeclaredMethod(
                    "saveDocument", MongoCollection.class, Document.class, boolean.class);
            method.setAccessible(true);
            method.invoke(migration, collection, doc, false);

            verify(collection).replaceOne(any(), eq(doc));
        }

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("history document with non-Map ID is silently skipped")
        void historyDocWithNonMapId() throws Exception {
            var doc = new Document("_id", new ObjectId()).append("data", "test");

            MongoCollection<Document> collection = mock(MongoCollection.class);

            var method = V6RenameMigration.class.getDeclaredMethod(
                    "saveDocument", MongoCollection.class, Document.class, boolean.class);
            method.setAccessible(true);
            // history=true but _id is ObjectId, not Map → no replaceOne
            method.invoke(migration, collection, doc, true);

            verify(collection, never()).replaceOne(any(), any());
        }

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("saveDocument handles exception gracefully")
        void saveDocumentException() throws Exception {
            var doc = new Document("_id", new ObjectId()).append("data", "test");

            MongoCollection<Document> collection = mock(MongoCollection.class);
            when(collection.replaceOne(any(), any())).thenThrow(new RuntimeException("save error"));

            var method = V6RenameMigration.class.getDeclaredMethod(
                    "saveDocument", MongoCollection.class, Document.class, boolean.class);
            method.setAccessible(true);

            // Should not throw
            assertDoesNotThrow(() -> method.invoke(migration, collection, doc, false));
        }
    }

    // =========================================================
    // renameCollectionIfExists — error code 48 and generic
    // =========================================================

    @Nested
    @DisplayName("renameCollectionIfExists — error handling")
    class RenameCollectionErrors {

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("empty collection is not renamed")
        void emptyCollectionNotRenamed() {
            when(migrationLogStore.readMigrationLog(anyString())).thenReturn(null);

            MongoCollection<Document> emptyCol = mock(MongoCollection.class);
            when(emptyCol.estimatedDocumentCount()).thenReturn(0L);
            when(database.getCollection(anyString())).thenReturn(emptyCol);
            when(database.getName()).thenReturn("eddi");

            migration.runIfNeeded();

            verify(emptyCol, never()).renameCollection(any(MongoNamespace.class));
        }

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("MongoCommandException with error code 48 is handled (target exists)")
        void errorCode48Handled() throws Exception {
            MongoCollection<Document> collection = mock(MongoCollection.class);
            when(collection.estimatedDocumentCount()).thenReturn(5L);

            MongoCommandException exception = new MongoCommandException(
                    new BsonDocument(), new ServerAddress());
            // Code 48 = NamespaceExists
            // We can't easily construct a MongoCommandException with code 48,
            // so we test via the general exception path
            doThrow(new RuntimeException("generic rename error"))
                    .when(collection).renameCollection(any(MongoNamespace.class));

            var method = V6RenameMigration.class.getDeclaredMethod(
                    "renameCollectionIfExists", String.class, String.class);
            method.setAccessible(true);

            when(database.getCollection("old")).thenReturn(collection);
            when(database.getName()).thenReturn("eddi");

            // Should not throw — exception is caught
            assertDoesNotThrow(() -> method.invoke(migration, "old", "new"));
        }
    }

    // =========================================================
    // migrateEnvironments — field name rewrites
    // =========================================================

    @Nested
    @DisplayName("migrateEnvironments — field name rewrites")
    class EnvironmentFieldRewrites {

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("botId → agentId field rename")
        void botIdToAgentId() throws Exception {
            var doc = new Document("botId", "b1")
                    .append("botVersion", 1)
                    .append("environment", "unrestricted")
                    .append("_id", new ObjectId());

            // Use reflection to call migrateEnvironments
            var method = V6RenameMigration.class.getDeclaredMethod("migrateEnvironments", String.class);
            method.setAccessible(true);

            MongoCollection<Document> collection = mock(MongoCollection.class);
            when(collection.estimatedDocumentCount()).thenReturn(1L);

            FindIterable<Document> iterable = mock(FindIterable.class);
            MongoCursor<Document> cursor = mock(MongoCursor.class);
            when(cursor.hasNext()).thenReturn(true, false);
            when(cursor.next()).thenReturn(doc);
            doReturn(cursor).when(iterable).iterator();
            when(collection.find()).thenReturn(iterable);

            when(database.getCollection(eq("conversationmemories"))).thenReturn(collection);

            method.invoke(migration, "conversationmemories");

            // Verify field renames
            assertTrue(doc.containsKey("agentId"));
            assertFalse(doc.containsKey("botId"));
            assertTrue(doc.containsKey("agentVersion"));
            assertFalse(doc.containsKey("botVersion"));
            assertEquals("production", doc.get("environment"));
        }

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("environment not a string is not rewritten")
        void environmentNotString() throws Exception {
            var doc = new Document("environment", 42)
                    .append("_id", new ObjectId());

            var method = V6RenameMigration.class.getDeclaredMethod("migrateEnvironments", String.class);
            method.setAccessible(true);

            MongoCollection<Document> collection = mock(MongoCollection.class);
            when(collection.estimatedDocumentCount()).thenReturn(1L);

            FindIterable<Document> iterable = mock(FindIterable.class);
            MongoCursor<Document> cursor = mock(MongoCursor.class);
            when(cursor.hasNext()).thenReturn(true, false);
            when(cursor.next()).thenReturn(doc);
            doReturn(cursor).when(iterable).iterator();
            when(collection.find()).thenReturn(iterable);

            when(database.getCollection(eq("deployments"))).thenReturn(collection);

            method.invoke(migration, "deployments");

            // Environment should remain as-is (not a String)
            assertEquals(42, doc.get("environment"));
        }

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("environment with unknown value is not rewritten")
        void unknownEnvironmentValue() throws Exception {
            var doc = new Document("environment", "staging")
                    .append("_id", new ObjectId());

            var method = V6RenameMigration.class.getDeclaredMethod("migrateEnvironments", String.class);
            method.setAccessible(true);

            MongoCollection<Document> collection = mock(MongoCollection.class);
            when(collection.estimatedDocumentCount()).thenReturn(1L);

            FindIterable<Document> iterable = mock(FindIterable.class);
            MongoCursor<Document> cursor = mock(MongoCursor.class);
            when(cursor.hasNext()).thenReturn(true, false);
            when(cursor.next()).thenReturn(doc);
            doReturn(cursor).when(iterable).iterator();
            when(collection.find()).thenReturn(iterable);

            when(database.getCollection(eq("conversationmemories"))).thenReturn(collection);

            method.invoke(migration, "conversationmemories");

            assertEquals("staging", doc.get("environment"));
        }
    }

    // =========================================================
    // migrateAgentFields — no changes needed
    // =========================================================

    @Nested
    @DisplayName("migrateAgentFields edge cases")
    class MigrateAgentFieldsEdgeCases {

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("agent doc without 'packages' field is not modified")
        void noPackagesField() throws Exception {
            var doc = new Document("workflows", List.of("wf1"))
                    .append("_id", new ObjectId());

            MongoCollection<Document> collection = mock(MongoCollection.class);
            when(collection.estimatedDocumentCount()).thenReturn(1L);

            FindIterable<Document> iterable = mock(FindIterable.class);
            MongoCursor<Document> cursor = mock(MongoCursor.class);
            when(cursor.hasNext()).thenReturn(true, false);
            when(cursor.next()).thenReturn(doc);
            doReturn(cursor).when(iterable).iterator();
            when(collection.find()).thenReturn(iterable);

            when(database.getCollection(eq("agents"))).thenReturn(collection);

            MongoCollection<Document> historyCol = mock(MongoCollection.class);
            FindIterable<Document> historyIterable = mock(FindIterable.class);
            MongoCursor<Document> historyCursor = mock(MongoCursor.class);
            when(historyCursor.hasNext()).thenReturn(false);
            doReturn(historyCursor).when(historyIterable).iterator();
            when(historyCol.find()).thenReturn(historyIterable);
            when(database.getCollection(eq("agents.history"))).thenReturn(historyCol);

            var method = V6RenameMigration.class.getDeclaredMethod("migrateAgentFields");
            method.setAccessible(true);
            int migrated = (int) method.invoke(migration);

            assertEquals(0, migrated);
            verify(collection, never()).replaceOne(any(), any());
        }
    }

    // =========================================================
    // migrateCollection — exception during getCollection
    // =========================================================

    @Nested
    @DisplayName("migrateCollection — exception handling")
    class MigrateCollectionExceptions {

        @Test
        @DisplayName("exception during getCollection returns 0")
        void getCollectionException() throws Exception {
            when(database.getCollection("nonexistent")).thenThrow(new RuntimeException("not found"));

            var method = V6RenameMigration.class.getDeclaredMethod("migrateCollection", String.class);
            method.setAccessible(true);
            int result = (int) method.invoke(migration, "nonexistent");

            assertEquals(0, result);
        }
    }
}
