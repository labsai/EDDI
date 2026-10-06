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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The migration parses what it converted. A conversion Qute rejects used to be
 * written back and counted as migrated, and failed on every render afterwards.
 * Now the document is left as it was and reported, like any other template the
 * converter cannot convert. Ids and values are synthetic.
 */
@DisplayName("V6QuteMigration — the converted template must parse as Qute")
class V6QuteMigrationParseCheckTest {

    private static final String NESTED = "[[${userInfo.chapterNumber > 0 ? 'session ' + userInfo.chapterNumber + "
            + "(userInfo.actionNumber > 0 ? ', inside action screen ' + userInfo.actionNumber : ' overview screen') "
            + ": 'program overview screen'}]]";

    /** A conditional the converter is not sure about (a method call). */
    private static final String UNSURE = "[[${items.size() > 0 && (a ? b : 'x' ? 'many' : 'none'}]]";

    private MongoDatabase database;
    private IMigrationLogStore migrationLogStore;
    private MongoCollection<Document> collection;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        database = mock(MongoDatabase.class);
        migrationLogStore = mock(IMigrationLogStore.class);
        collection = mock(MongoCollection.class);
        when(migrationLogStore.readMigrationLog("v6-qute-migration-complete")).thenReturn(null);
        MongoCollection<Document> empty = mock(MongoCollection.class);
        when(empty.estimatedDocumentCount()).thenReturn(0L);
        when(database.getCollection(anyString())).thenReturn(empty);
        when(database.getCollection("outputs")).thenReturn(collection);
    }

    @SuppressWarnings("unchecked")
    private void documents(Document... docs) {
        when(collection.estimatedDocumentCount()).thenReturn((long) docs.length);
        FindIterable<Document> iterable = mock(FindIterable.class);
        MongoCursor<Document> cursor = mock(MongoCursor.class);
        int[] served = {0};
        when(cursor.hasNext()).thenAnswer(i -> served[0] < docs.length);
        when(cursor.next()).thenAnswer(i -> docs[served[0]++]);
        doReturn(cursor).when(iterable).iterator();
        when(collection.find()).thenReturn(iterable);
    }

    private void run() {
        new V6QuteMigration(database, migrationLogStore, new TemplateSyntaxMigrator(), true).runIfNeeded();
    }

    @Test
    @DisplayName("the nested conditional is migrated to valid Qute and the migration completes")
    void nestedConditionalIsMigrated() {
        Document doc = new Document("_id", "00000000000000000000a001").append("text", NESTED);
        documents(doc);

        run();

        verify(collection).replaceOne(any(), eq(doc));
        assertEquals("{#if userInfo.chapterNumber && userInfo.chapterNumber > 0}session {userInfo.chapterNumber}"
                + "{#if userInfo.actionNumber && userInfo.actionNumber > 0}, inside action screen {userInfo.actionNumber}"
                + "{#else} overview screen{/if}{#else}program overview screen{/if}", doc.getString("text"));
        verify(migrationLogStore).createMigrationLog(any(MigrationLog.class));
    }

    @Test
    @DisplayName("a conversion Qute rejects leaves the document untouched, reports it, and does not complete the migration")
    void invalidConversionIsReportedAndLeftUntouched() {
        Document doc = new Document("_id", "00000000000000000000a002").append("text", UNSURE);
        documents(doc);

        run();

        verify(collection, never()).replaceOne(any(), any(Document.class));
        assertEquals(UNSURE, doc.getString("text"), "the original is kept");
        verify(migrationLogStore, never()).createMigrationLog(any(MigrationLog.class));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> reported = ArgumentCaptor.forClass(List.class);
        verify(migrationLogStore).writeMigrationEntries(eq(V6QuteMigration.REPORTED_KEY), reported.capture());
        assertEquals(List.of("outputs/00000000000000000000a002"), reported.getValue());
    }

    @Test
    @DisplayName("one bad template keeps its whole document unchanged, even a field that would convert")
    void goodFieldOfARefusedDocumentIsNotWritten() {
        Document doc = new Document("_id", "00000000000000000000a003").append("good", "[[${properties.name}]]").append("bad", UNSURE);
        documents(doc);

        run();

        verify(collection, never()).replaceOne(any(), any(Document.class));
        verify(migrationLogStore, never()).createMigrationLog(any(MigrationLog.class));
    }
}
