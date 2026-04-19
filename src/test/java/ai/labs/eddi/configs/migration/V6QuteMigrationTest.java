package ai.labs.eddi.configs.migration;

import ai.labs.eddi.configs.migration.model.MigrationLog;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

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
}
