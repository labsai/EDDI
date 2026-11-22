/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.documents.spend;

import ai.labs.eddi.engine.documents.spend.IDocumentSpendStore.DaySpend;
import ai.labs.eddi.engine.documents.spend.IDocumentSpendStore.SubjectKind;
import com.mongodb.MongoException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.result.DeleteResult;
import jakarta.enterprise.inject.Instance;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The store paths a real database rarely takes: an unreachable database, and
 * MongoDB's duplicate-key race on the first bucket of a day.
 */
@DisplayName("document spend stores — failure paths")
class DocumentSpendStoreFailureTest {

    @Test
    @DisplayName("PostgreSQL down: every call throws DocumentSpendStoreException (the gate fails closed) and the next one retries the DDL")
    void postgresDown() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenThrow(new SQLException("down"));
        @SuppressWarnings("unchecked")
        Instance<DataSource> instance = mock(Instance.class);
        when(instance.get()).thenReturn(dataSource);
        var store = new PostgresDocumentSpendStore(instance);

        assertThrows(DocumentSpendStoreException.class, () -> store.spent(SubjectKind.USER, "u", 1L));
        assertThrows(DocumentSpendStoreException.class, () -> store.add(SubjectKind.USER, "u", 1L, 1.0, 0L));
        assertThrows(DocumentSpendStoreException.class, () -> store.deleteBySubject(SubjectKind.USER, "u"));

        verify(dataSource, times(3)).getConnection();
    }

    @Test
    @DisplayName("MongoDB: the duplicate-key race on the first bucket of a day is retried and the increment lands")
    @SuppressWarnings("unchecked")
    void mongoDuplicateKeyIsRetried() {
        MongoDatabase database = mock(MongoDatabase.class);
        MongoCollection<Document> collection = mock(MongoCollection.class);
        when(database.getCollection(MongoDocumentSpendStore.COLLECTION)).thenReturn(collection);
        when(collection.withTimeout(anyLong(), any(TimeUnit.class))).thenReturn(collection);
        when(collection.deleteMany(any(Bson.class))).thenReturn(DeleteResult.acknowledged(0));
        Document after = new Document("kind", "USER").append("subject", "u").append("day", 5L).append("spentUsd", 1.5).append("calls", 2L);
        when(collection.findOneAndUpdate(any(Bson.class), any(Bson.class), any(FindOneAndUpdateOptions.class)))
                .thenThrow(new MongoException(11000, "E11000 duplicate key")).thenReturn(after);
        var store = new MongoDocumentSpendStore(database);

        DaySpend result = store.add(SubjectKind.USER, "u", 5L, 0.5, 0L);

        assertEquals(1.5, result.spentUsd(), 1e-12);
        assertEquals(2L, result.calls());
        verify(collection, times(2)).findOneAndUpdate(any(Bson.class), any(Bson.class), any(FindOneAndUpdateOptions.class));
    }

    @Test
    @DisplayName("MongoDB: any other error is a DocumentSpendStoreException, and an index failure does not stop the store")
    @SuppressWarnings("unchecked")
    void mongoOtherErrors() {
        MongoDatabase database = mock(MongoDatabase.class);
        MongoCollection<Document> collection = mock(MongoCollection.class);
        when(database.getCollection(MongoDocumentSpendStore.COLLECTION)).thenReturn(collection);
        when(collection.withTimeout(anyLong(), any(TimeUnit.class))).thenThrow(new MongoException("no server"));
        when(collection.find(any(Bson.class))).thenThrow(new MongoException("no server"));
        when(collection.findOneAndUpdate(any(Bson.class), any(Bson.class), any(FindOneAndUpdateOptions.class)))
                .thenThrow(new MongoException(1, "boom"));
        var store = new MongoDocumentSpendStore(database);

        assertThrows(DocumentSpendStoreException.class, () -> store.spent(SubjectKind.USER, "u", 1L));
        assertThrows(DocumentSpendStoreException.class, () -> store.add(SubjectKind.USER, "u", 1L, 1.0, 0L));
        verify(collection, times(1)).withTimeout(anyLong(), any(TimeUnit.class));
    }
}
