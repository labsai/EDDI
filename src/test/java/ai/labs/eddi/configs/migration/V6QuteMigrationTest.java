/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.migration;

import ai.labs.eddi.configs.migration.model.MigrationLog;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class V6QuteMigrationTest {

    private MongoDatabase database;
    private MigrationLogStore migrationLogStore;
    private TemplateSyntaxMigrator migrator;

    @BeforeEach
    void setUp() {
        database = mock(MongoDatabase.class);
        migrationLogStore = mock(MigrationLogStore.class);
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
        when(iterable.iterator()).thenReturn(cursor);
        when(col.find()).thenReturn(iterable);
        when(database.getCollection(anyString())).thenReturn(col);

        var migration = new V6QuteMigration(database, migrationLogStore, migrator, true);
        migration.runIfNeeded();

        verify(col, atLeastOnce()).replaceOne(any(), eq(doc));
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
        when(iterable.iterator()).thenReturn(cursor);
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
        var list = new java.util.ArrayList<Object>();
        list.add("[[${item}]]");
        list.add("safe");
        list.add(innerDoc);
        // Add nested list
        var nestedList = new java.util.ArrayList<Object>();
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
        when(iterable.iterator()).thenReturn(cursor);
        when(col.find()).thenReturn(iterable);
        when(database.getCollection(anyString())).thenReturn(col);

        var migration = new V6QuteMigration(database, migrationLogStore, migrator, true);
        migration.runIfNeeded();

        assertEquals("{item}", list.get(0));
        assertEquals("safe", list.get(1));
        assertEquals("{deep}", innerDoc.get("deepKey"));
        assertEquals("{item}", nestedList.get(0));
    }

    @SuppressWarnings("unchecked")
    @Test
    void runIfNeeded_collectionsNotExist() {
        when(migrationLogStore.readMigrationLog("v6-qute-migration-complete")).thenReturn(null);

        // Simulate getCollection throwing
        when(database.getCollection(anyString())).thenThrow(new RuntimeException("No collection"));

        var migration = new V6QuteMigration(database, migrationLogStore, migrator, true);
        migration.runIfNeeded();

        // Should still record completion
        verify(migrationLogStore).createMigrationLog(any(MigrationLog.class));
    }
}
