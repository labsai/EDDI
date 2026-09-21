/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.migration;

import ai.labs.eddi.configs.migration.model.MigrationLog;
import com.mongodb.MongoCommandException;
import com.mongodb.MongoTimeoutException;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import java.util.ArrayList;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class V6QuteMigrationTest {

    private MongoDatabase database;
    private IMigrationLogStore migrationLogStore;
    private TemplateSyntaxMigrator migrator;

    @BeforeEach
    void setUp() {
        database = mock(MongoDatabase.class);
        migrationLogStore = mock(IMigrationLogStore.class);
        migrator = mock(TemplateSyntaxMigrator.class);
    }

    @Test
    void runIfNeeded_disabled() {
        var migration = new V6QuteMigration(database, migrationLogStore, migrator, false);
        migration.runIfNeeded();
        verifyNoInteractions(database);
    }

    @Test
    void runIfNeeded_alreadyApplied() {
        when(migrationLogStore.readMigrationLog("v6-qute-migration-complete"))
                .thenReturn(new MigrationLog("v6-qute-migration-complete"));
        var migration = new V6QuteMigration(database, migrationLogStore, migrator, true);
        migration.runIfNeeded();
        verify(database, never()).getCollection(anyString());
    }

    @SuppressWarnings("unchecked")
    @Test
    void runIfNeeded_emptyCollections() {
        when(migrationLogStore.readMigrationLog("v6-qute-migration-complete")).thenReturn(null);

        MongoCollection<Document> emptyCol = mock(MongoCollection.class);
        when(emptyCol.estimatedDocumentCount()).thenReturn(0L);
        when(database.getCollection(anyString())).thenReturn(emptyCol);

        var migration = new V6QuteMigration(database, migrationLogStore, migrator, true);
        migration.runIfNeeded();

        verify(migrationLogStore).createMigrationLog(any(MigrationLog.class));
    }

    @SuppressWarnings("unchecked")
    @Test
    void runIfNeeded_migratesThymeleaf() {
        when(migrationLogStore.readMigrationLog("v6-qute-migration-complete")).thenReturn(null);
        when(migrator.containsThymeleafSyntax("[[${var}]]")).thenReturn(true);
        when(migrator.migrate("[[${var}]]")).thenReturn("{var}");
        when(migrator.containsThymeleafSyntax("{var}")).thenReturn(false);

        var doc = new Document("template", "[[${var}]]");
        doc.put("_id", "doc-1");

        MongoCollection<Document> col = mock(MongoCollection.class);
        when(col.estimatedDocumentCount()).thenReturn(1L);
        FindIterable<Document> iterable = mock(FindIterable.class);
        MongoCursor<Document> cursor = mock(MongoCursor.class);
        when(cursor.hasNext()).thenReturn(true, false);
        when(cursor.next()).thenReturn(doc);
        doReturn(cursor).when(iterable).iterator();
        when(col.find()).thenReturn(iterable);
        when(database.getCollection(anyString())).thenReturn(col);

        var migration = new V6QuteMigration(database, migrationLogStore, migrator, true);
        migration.runIfNeeded();

        verify(col, atLeastOnce()).replaceOne(any(), eq(doc));
    }

    // --- a collection that cannot be counted (CodeRabbit review, PR #781) ---

    /**
     * {@code NamespaceNotFound} is the one count failure that means "nothing to
     * migrate here". Only some of these collections exist on any given database,
     * and while the current driver answers a missing namespace with a count of
     * zero, others have raised this instead — treating it as a failure would leave
     * the migration permanently incomplete on a database with nothing to migrate.
     */
    @SuppressWarnings("unchecked")
    @Test
    void runIfNeeded_missingCollectionIsNotAFailure() {
        when(migrationLogStore.readMigrationLog("v6-qute-migration-complete")).thenReturn(null);

        MongoCommandException namespaceNotFound = mock(MongoCommandException.class);
        when(namespaceNotFound.getErrorCode()).thenReturn(26);
        MongoCollection<Document> missing = mock(MongoCollection.class);
        when(missing.estimatedDocumentCount()).thenThrow(namespaceNotFound);
        when(database.getCollection(anyString())).thenReturn(missing);

        var migration = new V6QuteMigration(database, migrationLogStore, migrator, true);
        migration.runIfNeeded();

        verify(migrationLogStore).createMigrationLog(any(MigrationLog.class));
    }

    /**
     * Any other count failure — an authorization error, a timeout, a server error —
     * means the collection may well hold Thymeleaf templates that nobody has looked
     * at. Swallowing it would mark the migration complete over a collection that
     * was never read, which is the same silent half-migration the per-document
     * guard exists to prevent.
     */
    @SuppressWarnings("unchecked")
    @Test
    void runIfNeeded_unreadableCollectionBlocksCompletion() {
        when(migrationLogStore.readMigrationLog("v6-qute-migration-complete")).thenReturn(null);

        MongoCommandException unauthorized = mock(MongoCommandException.class);
        when(unauthorized.getErrorCode()).thenReturn(13);
        MongoCollection<Document> unreadable = mock(MongoCollection.class);
        when(unreadable.estimatedDocumentCount()).thenThrow(unauthorized);
        when(database.getCollection(anyString())).thenReturn(unreadable);

        var migration = new V6QuteMigration(database, migrationLogStore, migrator, true);
        migration.runIfNeeded();

        verify(migrationLogStore, never()).createMigrationLog(any(MigrationLog.class));
    }

    /**
     * And the same for a failure that is not a command exception at all, such as a
     * server-selection timeout.
     */
    @SuppressWarnings("unchecked")
    @Test
    void runIfNeeded_countTimeoutBlocksCompletion() {
        when(migrationLogStore.readMigrationLog("v6-qute-migration-complete")).thenReturn(null);

        MongoCollection<Document> unreachable = mock(MongoCollection.class);
        when(unreachable.estimatedDocumentCount()).thenThrow(new MongoTimeoutException("no server available"));
        when(database.getCollection(anyString())).thenReturn(unreachable);

        var migration = new V6QuteMigration(database, migrationLogStore, migrator, true);
        migration.runIfNeeded();

        verify(migrationLogStore, never()).createMigrationLog(any(MigrationLog.class));
    }

    /**
     * One document that cannot be migrated must not cost the rest of the database
     * its migration. Before this, {@code migrateCollection} had no per-document
     * guard: a single malformed template threw out of {@code runIfNeeded}, every
     * other config stayed on Thymeleaf syntax, and the only trace was a line saying
     * it would retry on the next startup — where it threw again.
     */
    @SuppressWarnings("unchecked")
    @Test
    void runIfNeeded_oneFailingDocumentDoesNotStopTheOthers() {
        when(migrationLogStore.readMigrationLog("v6-qute-migration-complete")).thenReturn(null);
        when(migrator.containsThymeleafSyntax("[[${boom}]]")).thenReturn(true);
        when(migrator.migrate("[[${boom}]]"))
                .thenThrow(new StringIndexOutOfBoundsException("Range [1, 0) out of bounds for length 1"));
        when(migrator.containsThymeleafSyntax("[[${good}]]")).thenReturn(true);
        when(migrator.migrate("[[${good}]]")).thenReturn("{good}");
        when(migrator.containsThymeleafSyntax("{good}")).thenReturn(false);

        var broken = new Document("template", "[[${boom}]]");
        broken.put("_id", "doc-broken");
        var good = new Document("template", "[[${good}]]");
        good.put("_id", "doc-good");

        MongoCollection<Document> col = mock(MongoCollection.class);
        when(col.estimatedDocumentCount()).thenReturn(2L);
        FindIterable<Document> iterable = mock(FindIterable.class);
        MongoCursor<Document> cursor = mock(MongoCursor.class);
        when(cursor.hasNext()).thenReturn(true, true, false);
        when(cursor.next()).thenReturn(broken, good);
        doReturn(cursor).when(iterable).iterator();
        when(col.find()).thenReturn(iterable);
        when(database.getCollection(anyString())).thenReturn(col);

        var migration = new V6QuteMigration(database, migrationLogStore, migrator, true);
        migration.runIfNeeded();

        verify(col, atLeastOnce()).replaceOne(any(), eq(good));
        verify(col, never()).replaceOne(any(), eq(broken));
        // Not marked complete: the broken document is still on Thymeleaf syntax, so
        // the migration has to run again once someone has dealt with it.
        verify(migrationLogStore, never()).createMigrationLog(any(MigrationLog.class));
    }

    @SuppressWarnings("unchecked")
    @Test
    void runIfNeeded_migratesNestedDocument() {
        when(migrationLogStore.readMigrationLog("v6-qute-migration-complete")).thenReturn(null);
        when(migrator.containsThymeleafSyntax("[[${nested}]]")).thenReturn(true);
        when(migrator.migrate("[[${nested}]]")).thenReturn("{nested}");
        when(migrator.containsThymeleafSyntax("{nested}")).thenReturn(false);
        when(migrator.containsThymeleafSyntax("plain")).thenReturn(false);

        var nestedDoc = new Document("key", "[[${nested}]]");
        var doc = new Document("wrapper", nestedDoc);
        doc.put("_id", "doc-nested");
        doc.put("safe", "plain");

        MongoCollection<Document> col = mock(MongoCollection.class);
        when(col.estimatedDocumentCount()).thenReturn(1L);
        FindIterable<Document> iterable = mock(FindIterable.class);
        MongoCursor<Document> cursor = mock(MongoCursor.class);
        when(cursor.hasNext()).thenReturn(true, false);
        when(cursor.next()).thenReturn(doc);
        doReturn(cursor).when(iterable).iterator();
        when(col.find()).thenReturn(iterable);
        when(database.getCollection(anyString())).thenReturn(col);

        var migration = new V6QuteMigration(database, migrationLogStore, migrator, true);
        migration.runIfNeeded();

        assertEquals("{nested}", nestedDoc.get("key"));
        verify(col, atLeastOnce()).replaceOne(any(), eq(doc));
    }

    @SuppressWarnings("unchecked")
    @Test
    void runIfNeeded_migratesListContainingStringsAndDocs() {
        when(migrationLogStore.readMigrationLog("v6-qute-migration-complete")).thenReturn(null);
        when(migrator.containsThymeleafSyntax("[[${item}]]")).thenReturn(true);
        when(migrator.migrate("[[${item}]]")).thenReturn("{item}");
        when(migrator.containsThymeleafSyntax("safe")).thenReturn(false);
        when(migrator.containsThymeleafSyntax("{item}")).thenReturn(false);
        when(migrator.containsThymeleafSyntax("[[${deep}]]")).thenReturn(true);
        when(migrator.migrate("[[${deep}]]")).thenReturn("{deep}");
        when(migrator.containsThymeleafSyntax("{deep}")).thenReturn(false);

        var innerDoc = new Document("deepKey", "[[${deep}]]");
        var list = new ArrayList<Object>();
        list.add("[[${item}]]");
        list.add("safe");
        list.add(innerDoc);
        // Add nested list
        var nestedList = new ArrayList<Object>();
        nestedList.add("[[${item}]]");
        list.add(nestedList);

        var doc = new Document("items", list);
        doc.put("_id", "doc-list");

        MongoCollection<Document> col = mock(MongoCollection.class);
        when(col.estimatedDocumentCount()).thenReturn(1L);
        FindIterable<Document> iterable = mock(FindIterable.class);
        MongoCursor<Document> cursor = mock(MongoCursor.class);
        when(cursor.hasNext()).thenReturn(true, false);
        when(cursor.next()).thenReturn(doc);
        doReturn(cursor).when(iterable).iterator();
        when(col.find()).thenReturn(iterable);
        when(database.getCollection(anyString())).thenReturn(col);

        var migration = new V6QuteMigration(database, migrationLogStore, migrator, true);
        migration.runIfNeeded();

        assertEquals("{item}", list.get(0));
        assertEquals("safe", list.get(1));
        assertEquals("{deep}", innerDoc.get("deepKey"));
        assertEquals("{item}", nestedList.get(0));
    }

    /**
     * This used to assert the opposite — that a collection which could not be
     * reached at all still let the migration record completion — under the name
     * {@code runIfNeeded_collectionsNotExist}. That premise was wrong twice over:
     * {@code getCollection} does not contact the server, so it never fails merely
     * because a collection is absent (a genuinely missing namespace is covered by
     * {@link #runIfNeeded_missingCollectionIsNotAFailure()}), and recording
     * completion over a collection that was never read is the silent half-migration
     * this whole change exists to stop.
     */
    @SuppressWarnings("unchecked")
    @Test
    void runIfNeeded_collectionAccessFailureBlocksCompletion() {
        when(migrationLogStore.readMigrationLog("v6-qute-migration-complete")).thenReturn(null);

        when(database.getCollection(anyString())).thenThrow(new RuntimeException("No collection"));

        var migration = new V6QuteMigration(database, migrationLogStore, migrator, true);
        migration.runIfNeeded();

        verify(migrationLogStore, never()).createMigrationLog(any(MigrationLog.class));
    }
}
