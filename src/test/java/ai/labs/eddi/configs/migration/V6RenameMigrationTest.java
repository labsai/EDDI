package ai.labs.eddi.configs.migration;

import ai.labs.eddi.configs.migration.model.MigrationLog;
import com.mongodb.MongoNamespace;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;

class V6RenameMigrationTest {

    @Mock
    private MongoDatabase database;
    @Mock
    private MigrationLogStore migrationLogStore;

    private V6RenameMigration migration;

    @BeforeEach
    void setUp() {
        openMocks(this);
        // enabled = true for most tests
        migration = new V6RenameMigration(database, migrationLogStore, true);
    }

    // ───────────────────────────────────────────────────────────
    // URI Rewrite Tests
    // ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("rewriteUriString")
    class UriRewriteTests {

        @Test
        @DisplayName("should rewrite regulardictionary authority + store path")
        void rewriteDictionaryUri() {
            String input = "eddi://ai.labs.regulardictionary/regulardictionarystore/regulardictionaries/abc123?version=1";
            String expected = "eddi://ai.labs.dictionary/dictionarystore/dictionaries/abc123?version=1";
            assertEquals(expected, migration.rewriteUriString(input));
        }

        @Test
        @DisplayName("should rewrite httpcalls authority + store path")
        void rewriteHttpCallsUri() {
            String input = "eddi://ai.labs.httpcalls/httpcallsstore/httpcalls/abc123?version=1";
            String expected = "eddi://ai.labs.apicalls/apicallstore/apicalls/abc123?version=1";
            assertEquals(expected, migration.rewriteUriString(input));
        }

        @Test
        @DisplayName("should rewrite behavior authority + store path")
        void rewriteBehaviorUri() {
            String input = "eddi://ai.labs.behavior/behaviorstore/behaviorsets/abc123?version=1";
            String expected = "eddi://ai.labs.rules/rulestore/rulesets/abc123?version=1";
            assertEquals(expected, migration.rewriteUriString(input));
        }

        @Test
        @DisplayName("should rewrite langchain authority + store path")
        void rewriteLangchainUri() {
            String input = "eddi://ai.labs.langchain/langchainstore/langchains/abc123?version=1";
            String expected = "eddi://ai.labs.llm/llmstore/llms/abc123?version=1";
            assertEquals(expected, migration.rewriteUriString(input));
        }

        @Test
        @DisplayName("should rewrite package authority + store path")
        void rewritePackageUri() {
            String input = "eddi://ai.labs.package/packagestore/packages/abc123?version=1";
            String expected = "eddi://ai.labs.workflow/workflowstore/workflows/abc123?version=1";
            assertEquals(expected, migration.rewriteUriString(input));
        }

        @Test
        @DisplayName("should rewrite bot authority + store path")
        void rewriteBotUri() {
            String input = "eddi://ai.labs.bot/botstore/bots/abc123?version=1";
            String expected = "eddi://ai.labs.agent/agentstore/agents/abc123?version=1";
            assertEquals(expected, migration.rewriteUriString(input));
        }

        @Test
        @DisplayName("should not modify already-migrated v6 URIs (idempotent)")
        void idempotentV6Uris() {
            String v6Uri = "eddi://ai.labs.dictionary/dictionarystore/dictionaries/abc123?version=1";
            assertEquals(v6Uri, migration.rewriteUriString(v6Uri));
        }

        @Test
        @DisplayName("should not modify non-eddi strings")
        void nonEddiString() {
            String input = "https://example.com/api/v1/resource";
            assertEquals(input, migration.rewriteUriString(input));
        }

        @Test
        @DisplayName("should handle null input")
        void nullInput() {
            assertNull(migration.rewriteUriString(null));
        }

        @Test
        @DisplayName("should handle empty string")
        void emptyString() {
            assertEquals("", migration.rewriteUriString(""));
        }
    }

    // ───────────────────────────────────────────────────────────
    // runIfNeeded lifecycle tests
    // ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("runIfNeeded")
    class RunIfNeededTests {

        @Test
        @DisplayName("should skip when disabled")
        void skipsWhenDisabled() {
            V6RenameMigration disabled = new V6RenameMigration(database, migrationLogStore, false);
            disabled.runIfNeeded();
            verifyNoInteractions(database);
        }

        @Test
        @DisplayName("should skip when already applied")
        void skipsWhenAlreadyApplied() {
            when(migrationLogStore.readMigrationLog("v6-rename-migration-complete"))
                    .thenReturn(new MigrationLog("v6-rename-migration-complete"));
            migration.runIfNeeded();
            verify(database, never()).getCollection(anyString());
        }

        @Test
        @DisplayName("should run and record completion when enabled and not yet applied")
        @SuppressWarnings("unchecked")
        void runsAndRecords() {
            when(migrationLogStore.readMigrationLog("v6-rename-migration-complete"))
                    .thenReturn(null);

            // Mock all collection accesses to return empty collections
            MongoCollection<Document> mockCollection = mock(MongoCollection.class);
            when(mockCollection.estimatedDocumentCount()).thenReturn(0L);
            when(database.getCollection(anyString())).thenReturn(mockCollection);
            when(database.getName()).thenReturn("eddi");

            migration.runIfNeeded();

            // Should record the migration as complete
            ArgumentCaptor<MigrationLog> captor = ArgumentCaptor.forClass(MigrationLog.class);
            verify(migrationLogStore).createMigrationLog(captor.capture());
            assertEquals("v6-rename-migration-complete", captor.getValue().getName());
        }
    }

    // ───────────────────────────────────────────────────────────
    // Collection rename tests
    // ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("collection renames")
    class CollectionRenameTests {

        @Test
        @DisplayName("should attempt to rename all 6 collections + their history counterparts")
        @SuppressWarnings("unchecked")
        void renamesAllCollections() {
            when(migrationLogStore.readMigrationLog(anyString())).thenReturn(null);

            var oldNames = java.util.Set.of(
                    "bots", "bots.history", "packages", "packages.history",
                    "behaviorrulesets", "behaviorrulesets.history",
                    "httpcalls", "httpcalls.history",
                    "langchain", "langchain.history",
                    "regulardictionaries", "regulardictionaries.history");

            // Use thenAnswer to return non-empty for old names, empty for everything else
            when(database.getCollection(anyString())).thenAnswer(invocation -> {
                String name = invocation.getArgument(0);
                MongoCollection<Document> coll = mock(MongoCollection.class);
                if (oldNames.contains(name)) {
                    when(coll.estimatedDocumentCount()).thenReturn(5L);
                } else {
                    when(coll.estimatedDocumentCount()).thenReturn(0L);
                }
                return coll;
            });

            when(database.getName()).thenReturn("eddi");

            // Track all rename calls across all mock collections
            java.util.List<String> renamedTo = new java.util.ArrayList<>();
            // Re-wire: capture renameCollection calls
            when(database.getCollection(anyString())).thenAnswer(invocation -> {
                String name = invocation.getArgument(0);
                MongoCollection<Document> coll = mock(MongoCollection.class);
                if (oldNames.contains(name)) {
                    when(coll.estimatedDocumentCount()).thenReturn(5L);
                    doAnswer(renameInvocation -> {
                        MongoNamespace ns = renameInvocation.getArgument(0);
                        renamedTo.add(ns.getCollectionName());
                        return null;
                    }).when(coll).renameCollection(any(MongoNamespace.class));
                } else {
                    when(coll.estimatedDocumentCount()).thenReturn(0L);
                }
                return coll;
            });

            migration.runIfNeeded();

            assertTrue(renamedTo.contains("agents"), "Should rename bots → agents");
            assertTrue(renamedTo.contains("agents.history"), "Should rename bots.history → agents.history");
            assertTrue(renamedTo.contains("workflows"), "Should rename packages → workflows");
            assertTrue(renamedTo.contains("workflows.history"), "Should rename packages.history → workflows.history");
            assertTrue(renamedTo.contains("rulesets"), "Should rename behaviorrulesets → rulesets");
            assertTrue(renamedTo.contains("rulesets.history"), "Should rename behaviorrulesets.history → rulesets.history");
            assertTrue(renamedTo.contains("apicalls"), "Should rename httpcalls → apicalls");
            assertTrue(renamedTo.contains("apicalls.history"), "Should rename httpcalls.history → apicalls.history");
            assertTrue(renamedTo.contains("llms"), "Should rename langchain → llms");
            assertTrue(renamedTo.contains("llms.history"), "Should rename langchain.history → llms.history");
            assertTrue(renamedTo.contains("dictionaries"), "Should rename regulardictionaries → dictionaries");
            assertTrue(renamedTo.contains("dictionaries.history"), "Should rename regulardictionaries.history → dictionaries.history");
            assertEquals(12, renamedTo.size(), "Should rename exactly 12 collections (6 + 6 history)");
        }

        @Test
        @DisplayName("should skip rename for empty collections")
        @SuppressWarnings("unchecked")
        void skipsEmptyCollections() {
            when(migrationLogStore.readMigrationLog(anyString())).thenReturn(null);

            MongoCollection<Document> emptyCollection = mock(MongoCollection.class);
            when(emptyCollection.estimatedDocumentCount()).thenReturn(0L);
            when(database.getCollection(anyString())).thenReturn(emptyCollection);
            when(database.getName()).thenReturn("eddi");

            migration.runIfNeeded();

            // renameCollection should never be called for empty collections
            verify(emptyCollection, never()).renameCollection(any(MongoNamespace.class));
        }
    }
}
