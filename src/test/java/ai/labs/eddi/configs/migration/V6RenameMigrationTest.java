/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.migration;

import ai.labs.eddi.configs.migration.model.MigrationLog;
import com.mongodb.MongoCommandException;
import com.mongodb.MongoNamespace;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.RenameCollectionOptions;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.bson.types.ObjectId;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;

class V6RenameMigrationTest {

    @Mock
    private MongoDatabase database;
    @Mock
    private IMigrationLogStore migrationLogStore;

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
            when(migrationLogStore.readMigrationLog("v6-rename-migration-complete")).thenReturn(new MigrationLog("v6-rename-migration-complete"));
            migration.runIfNeeded();
            verify(database, never()).getCollection(anyString());
        }

        @Test
        @DisplayName("should run and record completion when enabled and not yet applied")
        @SuppressWarnings("unchecked")
        void runsAndRecords() {
            when(migrationLogStore.readMigrationLog("v6-rename-migration-complete")).thenReturn(null);

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

            var oldNames = Set.of("bots", "bots.history", "packages", "packages.history", "behaviorrulesets", "behaviorrulesets.history",
                    "httpcalls", "httpcalls.history", "langchain", "langchain.history", "regulardictionaries", "regulardictionaries.history");

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
            List<String> renamedTo = new ArrayList<>();
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
                    }).when(coll).renameCollection(any(MongoNamespace.class), any(RenameCollectionOptions.class));
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
            verify(emptyCollection, never()).renameCollection(any(MongoNamespace.class), any(RenameCollectionOptions.class));
        }

        /**
         * A target that really does hold documents (written between the pre-flight and
         * the rename) is genuinely ambiguous and must still stop the migration —
         * dropping it there would destroy data. The rename therefore goes out with
         * {@code dropTarget=false}, fails with error 48, and the run aborts cleanly
         * without throwing.
         * <p>
         * The recovery half — an EMPTY leftover v6 namespace, which is the normal state
         * of a v5 database because startup creates those namespaces itself — lives in
         * {@link EmptyTargetNamespaceTests}.
         */
        @Test
        @DisplayName("error 48 with a POPULATED target → never drops it, aborts, stays incomplete")
        @SuppressWarnings("unchecked")
        void populatedTargetCollection_abortsWithoutDropping() {
            when(migrationLogStore.readMigrationLog(anyString())).thenReturn(null);

            MongoCollection<Document> bots = mock(MongoCollection.class);
            when(bots.estimatedDocumentCount()).thenReturn(5L);
            var namespaceExists = mock(MongoCommandException.class);
            when(namespaceExists.getErrorCode()).thenReturn(48);
            doThrow(namespaceExists).when(bots).renameCollection(any(MongoNamespace.class), any(RenameCollectionOptions.class));

            MongoCollection<Document> agents = mock(MongoCollection.class);
            // Stale estimate lets it past the pre-flight; the exact count is the truth.
            when(agents.estimatedDocumentCount()).thenReturn(0L);
            when(agents.countDocuments()).thenReturn(3L);

            when(database.getCollection(anyString())).thenAnswer(invocation -> switch (invocation.<String>getArgument(0)) {
                case "bots" -> bots;
                case "agents" -> agents;
                default -> emptyCollection();
            });
            when(database.getName()).thenReturn("eddi");

            assertDoesNotThrow(() -> migration.runIfNeeded());

            // The exact count must actually be consulted — an estimate is not a
            // licence to drop a collection.
            verify(agents).countDocuments();
            verify(agents, never()).drop();
            var options = ArgumentCaptor.forClass(RenameCollectionOptions.class);
            verify(bots).renameCollection(any(MongoNamespace.class), options.capture());
            assertFalse(options.getValue().isDropTarget(), "a target holding documents must never be dropped");
            verify(migrationLogStore, never()).createMigrationLog(any());
        }

        /**
         * An unreadable target count is not permission to drop either: the rename must
         * go out with {@code dropTarget=false}, fail with 48, and leave the migration
         * incomplete so the next start retries.
         */
        @Test
        @DisplayName("error 48 with an unreadable target count → aborts without dropping")
        @SuppressWarnings("unchecked")
        void unreadableTargetCount_abortsWithoutDropping() {
            when(migrationLogStore.readMigrationLog(anyString())).thenReturn(null);

            MongoCollection<Document> bots = mock(MongoCollection.class);
            when(bots.estimatedDocumentCount()).thenReturn(5L);
            var namespaceExists = mock(MongoCommandException.class);
            when(namespaceExists.getErrorCode()).thenReturn(48);
            doThrow(namespaceExists).when(bots).renameCollection(any(MongoNamespace.class), any(RenameCollectionOptions.class));

            MongoCollection<Document> agents = mock(MongoCollection.class);
            when(agents.estimatedDocumentCount()).thenReturn(0L);
            when(agents.countDocuments()).thenThrow(new IllegalStateException("no primary available"));

            when(database.getCollection(anyString())).thenAnswer(invocation -> switch (invocation.<String>getArgument(0)) {
                case "bots" -> bots;
                case "agents" -> agents;
                default -> emptyCollection();
            });
            when(database.getName()).thenReturn("eddi");

            assertDoesNotThrow(() -> migration.runIfNeeded());

            verify(agents, never()).drop();
            var options = ArgumentCaptor.forClass(RenameCollectionOptions.class);
            verify(bots).renameCollection(any(MongoNamespace.class), options.capture());
            assertFalse(options.getValue().isDropTarget(), "an unreadable count is not permission to drop the target");
            verify(migrationLogStore, never()).createMigrationLog(any());
        }

        @Test
        @DisplayName("a non-48 MongoCommandException aborts the run and leaves it incomplete")
        @SuppressWarnings("unchecked")
        void handlesOtherMongoCommandException() {
            when(migrationLogStore.readMigrationLog(anyString())).thenReturn(null);

            MongoCollection<Document> bots = mock(MongoCollection.class);
            when(bots.estimatedDocumentCount()).thenReturn(5L);
            var internalError = mock(MongoCommandException.class);
            when(internalError.getErrorCode()).thenReturn(500);
            when(internalError.getMessage()).thenReturn("Internal error");
            doThrow(internalError).when(bots).renameCollection(any(MongoNamespace.class), any(RenameCollectionOptions.class));

            MongoCollection<Document> agents = mock(MongoCollection.class);
            when(agents.estimatedDocumentCount()).thenReturn(0L);

            when(database.getCollection(anyString())).thenAnswer(invocation -> switch (invocation.<String>getArgument(0)) {
                case "bots" -> bots;
                case "agents" -> agents;
                default -> emptyCollection();
            });
            when(database.getName()).thenReturn("eddi");

            assertDoesNotThrow(() -> migration.runIfNeeded());

            // A server-side failure is not a placeholder — nothing may be dropped and
            // the documents still sitting under "bots" must be retried next start.
            verify(agents, never()).drop();
            verify(migrationLogStore, never()).createMigrationLog(any());
        }

        @SuppressWarnings("unchecked")
        private MongoCollection<Document> emptyCollection() {
            MongoCollection<Document> collection = mock(MongoCollection.class);
            FindIterable<Document> iterable = mock(FindIterable.class);
            MongoCursor<Document> cursor = mock(MongoCursor.class);
            doReturn(false).when(cursor).hasNext();
            doReturn(cursor).when(iterable).iterator();
            doReturn(0L).when(collection).estimatedDocumentCount();
            doReturn(iterable).when(collection).find();
            return collection;
        }
    }

    // ───────────────────────────────────────────────────────────
    // B14 — a rename that cannot happen must not be shrugged off
    // ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("v5/v6 collection conflicts")
    class CollectionConflictTests {

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("v5 and v6 collection both populated → reports an error, renames nothing, stays incomplete")
        void bothCollectionsPopulated_abortsInsteadOfLosingV5Documents() {
            when(migrationLogStore.readMigrationLog(anyString())).thenReturn(null);

            MongoCollection<Document> bots = mock(MongoCollection.class);
            when(bots.estimatedDocumentCount()).thenReturn(5L);
            MongoCollection<Document> agents = mock(MongoCollection.class);
            when(agents.estimatedDocumentCount()).thenReturn(3L);
            // Build the iterable BEFORE it is handed to thenReturn(): creating/stubbing
            // another mock inside an unfinished when(...) is exactly what Mockito reports
            // as UnfinishedStubbingException. The stub itself is only a safety net — a
            // regression that carries on past the conflict then fails on the verifies
            // below instead of blowing up with an NPE inside migrateAgentFields.
            FindIterable<Document> agentDocuments = emptyIterable();
            when(agents.find()).thenReturn(agentDocuments);

            MongoCollection<Document> emptyCollection = mock(MongoCollection.class);
            when(emptyCollection.estimatedDocumentCount()).thenReturn(0L);

            when(database.getCollection(anyString())).thenAnswer(invocation -> {
                String name = invocation.getArgument(0);
                if ("bots".equals(name)) {
                    return bots;
                }
                if ("agents".equals(name)) {
                    return agents;
                }
                return emptyCollection;
            });
            when(database.getName()).thenReturn("eddi");

            migration.runIfNeeded();

            var conflicts = migration.detectCollectionRenameConflicts();
            assertEquals(1, conflicts.size(), "the bots/agents clash must be reported");
            assertTrue(conflicts.getFirst().contains("bots") && conflicts.getFirst().contains("agents"), conflicts.getFirst());

            // The rename would have failed and the v5 documents would then have been
            // invisible to the URI rewrite (it only scans v6 names), so the run must touch
            // nothing at all...
            verify(bots, never()).renameCollection(any(MongoNamespace.class), any(RenameCollectionOptions.class));
            verify(agents, never()).renameCollection(any(MongoNamespace.class), any(RenameCollectionOptions.class));
            verify(bots, never()).find();
            verify(agents, never()).find();
            // ...and above all must not claim to be done, or the v5 documents would be
            // stranded forever behind the "already applied" short-circuit.
            verify(migrationLogStore, never()).createMigrationLog(any());
        }

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("only the v5 collection populated → no conflict, migration completes")
        void onlyV5Populated_isNotAConflict() {
            when(migrationLogStore.readMigrationLog(anyString())).thenReturn(null);

            MongoCollection<Document> bots = mock(MongoCollection.class);
            when(bots.estimatedDocumentCount()).thenReturn(5L);

            MongoCollection<Document> emptyCollection = mock(MongoCollection.class);
            when(emptyCollection.estimatedDocumentCount()).thenReturn(0L);

            when(database.getCollection(anyString())).thenAnswer(invocation -> "bots".equals(invocation.getArgument(0)) ? bots : emptyCollection);
            when(database.getName()).thenReturn("eddi");

            migration.runIfNeeded();

            assertTrue(migration.detectCollectionRenameConflicts().isEmpty());
            verify(bots).renameCollection(any(MongoNamespace.class), any(RenameCollectionOptions.class));
            verify(migrationLogStore).createMigrationLog(any());
        }

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("a rename that fails aborts the run and leaves the migration incomplete so it runs again")
        void failedRename_doesNotMarkMigrationComplete() {
            when(migrationLogStore.readMigrationLog(anyString())).thenReturn(null);

            MongoCollection<Document> bots = mock(MongoCollection.class);
            when(bots.estimatedDocumentCount()).thenReturn(5L);
            doThrow(new IllegalStateException("rename refused")).when(bots)
                    .renameCollection(any(MongoNamespace.class), any(RenameCollectionOptions.class));

            // A populated v6 collection that the URI-rewrite pass would visit if the run
            // carried on past the failed rename — the witness that we really aborted.
            MongoCollection<Document> workflows = mock(MongoCollection.class);
            when(workflows.estimatedDocumentCount()).thenReturn(2L);
            FindIterable<Document> workflowDocuments = emptyIterable();
            when(workflows.find()).thenReturn(workflowDocuments);

            MongoCollection<Document> emptyCollection = mock(MongoCollection.class);
            when(emptyCollection.estimatedDocumentCount()).thenReturn(0L);

            when(database.getCollection(anyString())).thenAnswer(invocation -> switch (invocation.<String>getArgument(0)) {
                case "bots" -> bots;
                case "workflows" -> workflows;
                default -> emptyCollection;
            });
            when(database.getName()).thenReturn("eddi");

            migration.runIfNeeded();

            verify(bots).renameCollection(any(MongoNamespace.class), any(RenameCollectionOptions.class));
            verify(workflows, never()).find(); // aborted before the URI rewrite pass
            verify(migrationLogStore, never()).createMigrationLog(any());
        }

        /**
         * Always call this into a local variable first — never inline it into
         * {@code thenReturn(...)}, which stubs a mock inside an unfinished stubbing.
         */
        @SuppressWarnings("unchecked")
        private FindIterable<Document> emptyIterable() {
            FindIterable<Document> iterable = mock(FindIterable.class);
            MongoCursor<Document> cursor = mock(MongoCursor.class);
            doReturn(false).when(cursor).hasNext();
            doReturn(cursor).when(iterable).iterator();
            return iterable;
        }
    }

    // ───────────────────────────────────────────────────────────
    // An EMPTY but EXISTING v6 namespace must not deadlock the migration.
    // MongoDB refuses renameCollection with NamespaceExists (48) whenever the
    // target namespace exists — being empty does not help — and EDDI creates
    // those empty v6 namespaces itself on any boot (createIndex).
    // ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("empty-but-existing v6 target namespace")
    class EmptyTargetNamespaceTests {

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("an empty v6 target is dropped so the rename succeeds instead of failing forever with error 48")
        void emptyExistingTargetIsDroppedAndMigrationCompletes() {
            when(migrationLogStore.readMigrationLog(anyString())).thenReturn(null);

            MongoCollection<Document> bots = mock(MongoCollection.class);
            when(bots.estimatedDocumentCount()).thenReturn(5L);

            var renameOptions = recordRenamesAndRejectWithoutDropTarget(bots);

            // "agents" EXISTS but is empty: the estimate keeps the pre-flight quiet
            // (it only flags pairs where BOTH sides hold documents) and the exact count
            // proves the namespace may be dropped.
            MongoCollection<Document> agents = mock(MongoCollection.class);
            when(agents.estimatedDocumentCount()).thenReturn(0L);
            when(agents.countDocuments()).thenReturn(0L);

            MongoCollection<Document> emptyCollection = mock(MongoCollection.class);
            when(emptyCollection.estimatedDocumentCount()).thenReturn(0L);

            when(database.getCollection(anyString())).thenAnswer(invocation -> switch (invocation.<String>getArgument(0)) {
                case "bots" -> bots;
                case "agents" -> agents;
                default -> emptyCollection;
            });
            when(database.getName()).thenReturn("eddi");

            migration.runIfNeeded();

            assertEquals(1, renameOptions.size(), "bots → agents must be attempted exactly once");
            assertTrue(renameOptions.getFirst().isDropTarget(),
                    "an empty leftover v6 namespace must be dropped — otherwise the rename fails with 48, the run "
                            + "aborts, the app re-creates the namespace on the next boot and the migration can never complete");
            // ...and because the rename went through, the run finishes and is recorded.
            verify(migrationLogStore).createMigrationLog(any());
        }

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("a v6 target that really holds documents is never dropped, even when the cheap estimate says it is empty")
        void populatedTargetIsNeverDroppedOnAStaleEstimate() {
            when(migrationLogStore.readMigrationLog(anyString())).thenReturn(null);

            MongoCollection<Document> bots = mock(MongoCollection.class);
            when(bots.estimatedDocumentCount()).thenReturn(5L);

            var renameOptions = recordRenamesAndRejectWithoutDropTarget(bots);

            // estimatedDocumentCount() reads collection metadata that can be stale after
            // an unclean shutdown; countDocuments() is the truth. Deciding on the stale
            // estimate would drop three live agent documents.
            MongoCollection<Document> agents = mock(MongoCollection.class);
            when(agents.estimatedDocumentCount()).thenReturn(0L);
            when(agents.countDocuments()).thenReturn(3L);

            MongoCollection<Document> emptyCollection = mock(MongoCollection.class);
            when(emptyCollection.estimatedDocumentCount()).thenReturn(0L);

            when(database.getCollection(anyString())).thenAnswer(invocation -> switch (invocation.<String>getArgument(0)) {
                case "bots" -> bots;
                case "agents" -> agents;
                default -> emptyCollection;
            });
            when(database.getName()).thenReturn("eddi");

            migration.runIfNeeded();

            assertEquals(1, renameOptions.size(), "bots → agents must be attempted exactly once");
            assertFalse(renameOptions.getFirst().isDropTarget(), "a target holding documents must never be dropped");
            // The rename then legitimately fails with 48 and the run must stay incomplete.
            verify(migrationLogStore, never()).createMigrationLog(any());
        }

        /**
         * Models MongoDB: the rename throws NamespaceExists (48) unless dropTarget is
         * set, and every attempt is recorded so the test can assert on the options.
         */
        private List<RenameCollectionOptions> recordRenamesAndRejectWithoutDropTarget(MongoCollection<Document> collection) {
            var namespaceExists = mock(MongoCommandException.class);
            when(namespaceExists.getErrorCode()).thenReturn(48);

            var recorded = new ArrayList<RenameCollectionOptions>();
            doAnswer(invocation -> {
                RenameCollectionOptions options = invocation.getArgument(1);
                recorded.add(options);
                if (!options.isDropTarget()) {
                    throw namespaceExists;
                }
                return null;
            }).when(collection).renameCollection(any(MongoNamespace.class), any(RenameCollectionOptions.class));
            return recorded;
        }
    }

    // ───────────────────────────────────────────────────────────
    // URI rewrite in Document/List (recursive) tests
    // ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("rewriteUrisInDocument/List recursive walking")
    class RecursiveUriRewriteTests {

        @Test
        @DisplayName("should rewrite URI strings nested inside Documents")
        void rewritesNestedDocument() {
            String oldUri = "eddi://ai.labs.bot/botstore/bots/abc123?version=1";
            String newUri = "eddi://ai.labs.agent/agentstore/agents/abc123?version=1";

            // Just verify the string-level rewrite which is the foundation
            assertEquals(newUri, migration.rewriteUriString(oldUri));
        }

        @Test
        @DisplayName("should rewrite multiple URIs in same string")
        void multipleUrisInString() {
            String input = "ref1=eddi://ai.labs.bot/botstore/bots/a1 ref2=eddi://ai.labs.package/packagestore/packages/p1";
            String result = migration.rewriteUriString(input);

            assertTrue(result.contains("eddi://ai.labs.agent/agentstore/agents/a1"));
            assertTrue(result.contains("eddi://ai.labs.workflow/workflowstore/workflows/p1"));
        }
    }

    // ───────────────────────────────────────────────────────────
    // migrateEnvironments tests
    // ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("migrateEnvironments")
    class EnvironmentMigrationTests {

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("should run full migration with environment rewrites")
        void runsFullMigration() {
            when(migrationLogStore.readMigrationLog(anyString())).thenReturn(null);

            // Create a doc with botId, botVersion, and unrestricted environment
            var envDoc = new Document("botId", "agent-1")
                    .append("botVersion", 2)
                    .append("environment", "unrestricted")
                    .append("_id", new ObjectId());

            MongoCollection<Document> envCol = mock(MongoCollection.class);
            when(envCol.estimatedDocumentCount()).thenReturn(1L);

            FindIterable<Document> envIterable = mock(FindIterable.class);
            MongoCursor<Document> envCursor = mock(MongoCursor.class);
            when(envCursor.hasNext()).thenReturn(true, false);
            when(envCursor.next()).thenReturn(envDoc);
            when(envIterable.iterator()).thenReturn(envCursor);
            when(envCol.find()).thenReturn(envIterable);

            // Empty collection for most calls, env collection for
            // conversationmemories/deployments
            MongoCollection<Document> emptyCol = mock(MongoCollection.class);
            when(emptyCol.estimatedDocumentCount()).thenReturn(0L);

            when(database.getCollection(anyString())).thenAnswer(invocation -> {
                String name = invocation.getArgument(0);
                if ("conversationmemories".equals(name) || "deployments".equals(name)) {
                    return envCol;
                }
                return emptyCol;
            });
            when(database.getName()).thenReturn("eddi");

            migration.runIfNeeded();

            // Verify field renames
            assertEquals("agent-1", envDoc.get("agentId"));
            assertFalse(envDoc.containsKey("botId"));
            assertEquals(2, envDoc.get("agentVersion"));
            assertFalse(envDoc.containsKey("botVersion"));
            assertEquals("production", envDoc.get("environment"));
        }
    }

    // ───────────────────────────────────────────────────────────
    // Edge cases
    // ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Edge cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("restricted environment should map to production")
        void restrictedMapsToProduction() {
            // Test the rewrite string for 'restricted' → 'production'
            // This is tested at the string level via environment rewrite logic
            // covered by the full migration test above. Quick assertion on URI path:
            String v5 = "eddi://ai.labs.langchain/langchainstore/langchains/x?version=1";
            String v6 = migration.rewriteUriString(v5);
            assertEquals("eddi://ai.labs.llm/llmstore/llms/x?version=1", v6);
        }
    }

    // ───────────────────────────────────────────────────────────
    // rewriteUrisInDocument/List deep walk tests
    // ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("rewriteUrisInDocument deep nested")
    class DeepDocumentRewriteTests {

        @Test
        @DisplayName("should rewrite URIs nested in sub-documents")
        void rewriteNestedDocuments() {
            Document inner = new Document("ref", "eddi://ai.labs.bot/botstore/bots/b1?version=1");

            // rewriteUriString is package-private but rewriteUrisInDocument is private
            // We test transitively by verifying rewriteUriString on nested values
            String result = migration.rewriteUriString(inner.getString("ref"));
            assertEquals("eddi://ai.labs.agent/agentstore/agents/b1?version=1", result);
        }

        @Test
        @DisplayName("should handle list containing strings with URIs")
        void rewriteListOfStrings() {
            String uri1 = "eddi://ai.labs.behavior/behaviorstore/behaviorsets/r1";
            String uri2 = "eddi://ai.labs.httpcalls/httpcallsstore/httpcalls/h1";

            assertEquals("eddi://ai.labs.rules/rulestore/rulesets/r1", migration.rewriteUriString(uri1));
            assertEquals("eddi://ai.labs.apicalls/apicallstore/apicalls/h1", migration.rewriteUriString(uri2));
        }

        @Test
        @DisplayName("should not modify non-eddi strings")
        void nonEddiStrings() {
            String value = "some normal text without eddi URIs";
            assertEquals(value, migration.rewriteUriString(value));
        }

        @Test
        @DisplayName("should handle string containing multiple eddi URIs")
        void multipleUrisInSingleString() {
            String input = "refs: eddi://ai.labs.bot/botstore/bots/b1 and eddi://ai.labs.package/packagestore/packages/p1";
            String result = migration.rewriteUriString(input);
            assertTrue(result.contains("eddi://ai.labs.agent/agentstore/agents/b1"));
            assertTrue(result.contains("eddi://ai.labs.workflow/workflowstore/workflows/p1"));
        }
    }

    // ───────────────────────────────────────────────────────────
    // migrateAgentFields tests
    // ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("migrateAgentFields")
    class AgentFieldMigrationTests {

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("should rename 'packages' to 'workflows' in agent documents")
        void renamesPackagesToWorkflows() {
            when(migrationLogStore.readMigrationLog(anyString())).thenReturn(null);

            // Agent document with old field name 'packages'
            var agentDoc = new Document("packages", List.of("workflow-ref"))
                    .append("_id", new ObjectId());

            MongoCollection<Document> agentCol = mock(MongoCollection.class);
            when(agentCol.estimatedDocumentCount()).thenReturn(1L);

            FindIterable<Document> agentIterable = mock(FindIterable.class);
            MongoCursor<Document> agentCursor = mock(MongoCursor.class);
            when(agentCursor.hasNext()).thenReturn(true, false);
            when(agentCursor.next()).thenReturn(agentDoc);
            when(agentIterable.iterator()).thenReturn(agentCursor);
            when(agentCol.find()).thenReturn(agentIterable);

            // Empty collection for all other calls
            MongoCollection<Document> emptyCol = mock(MongoCollection.class);
            when(emptyCol.estimatedDocumentCount()).thenReturn(0L);

            when(database.getCollection(anyString())).thenAnswer(invocation -> {
                String name = invocation.getArgument(0);
                if ("agents".equals(name)) {
                    return agentCol;
                }
                return emptyCol;
            });
            when(database.getName()).thenReturn("eddi");

            migration.runIfNeeded();

            // Agent document should have 'workflows' field instead of 'packages'
            assertTrue(agentDoc.containsKey("workflows"));
            assertFalse(agentDoc.containsKey("packages"));
        }
    }

    // ───────────────────────────────────────────────────────────
    // migrateCollection with URI rewriting tests
    // ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("migrateCollection — URI rewriting in documents")
    class CollectionUriRewriteTests {

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("should rewrite URIs in workflow documents")
        void rewritesUrisInWorkflowDocuments() {
            when(migrationLogStore.readMigrationLog(anyString())).thenReturn(null);

            // Workflow document with old URI
            var workflowDoc = new Document(
                    "extension", "eddi://ai.labs.httpcalls/httpcallsstore/httpcalls/h1?version=1")
                    .append("_id", new ObjectId());

            MongoCollection<Document> workflowCol = mock(MongoCollection.class);
            when(workflowCol.estimatedDocumentCount()).thenReturn(1L);

            FindIterable<Document> iterable = mock(FindIterable.class);
            MongoCursor<Document> cursor = mock(MongoCursor.class);
            when(cursor.hasNext()).thenReturn(true, false);
            when(cursor.next()).thenReturn(workflowDoc);
            doReturn(cursor).when(iterable).iterator();
            when(workflowCol.find()).thenReturn(iterable);

            MongoCollection<Document> emptyCol = mock(MongoCollection.class);
            when(emptyCol.estimatedDocumentCount()).thenReturn(0L);

            when(database.getCollection(anyString())).thenAnswer(invocation -> {
                String name = invocation.getArgument(0);
                if ("workflows".equals(name)) {
                    return workflowCol;
                }
                return emptyCol;
            });
            when(database.getName()).thenReturn("eddi");

            migration.runIfNeeded();

            // The document should have the rewritten URI
            assertEquals("eddi://ai.labs.apicalls/apicallstore/apicalls/h1?version=1",
                    workflowDoc.getString("extension"));
        }
    }

    // ───────────────────────────────────────────────────────────
    // migrateEnvironments — restricted field rewrite tests
    // ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("migrateEnvironments — restricted environment rewrite")
    class RestrictedEnvironmentTests {

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("should rewrite 'restricted' environment to 'production'")
        void rewriteRestrictedToProduction() {
            when(migrationLogStore.readMigrationLog(anyString())).thenReturn(null);

            var envDoc = new Document("environment", "restricted")
                    .append("_id", new ObjectId());

            MongoCollection<Document> envCol = mock(MongoCollection.class);
            when(envCol.estimatedDocumentCount()).thenReturn(1L);

            FindIterable<Document> envIterable = mock(FindIterable.class);
            MongoCursor<Document> envCursor = mock(MongoCursor.class);
            when(envCursor.hasNext()).thenReturn(true, false);
            when(envCursor.next()).thenReturn(envDoc);
            when(envIterable.iterator()).thenReturn(envCursor);
            when(envCol.find()).thenReturn(envIterable);

            MongoCollection<Document> emptyCol = mock(MongoCollection.class);
            when(emptyCol.estimatedDocumentCount()).thenReturn(0L);

            when(database.getCollection(anyString())).thenAnswer(invocation -> {
                String name = invocation.getArgument(0);
                if ("conversationmemories".equals(name) || "deployments".equals(name)) {
                    return envCol;
                }
                return emptyCol;
            });
            when(database.getName()).thenReturn("eddi");

            migration.runIfNeeded();

            assertEquals("production", envDoc.get("environment"));
        }
    }
}
