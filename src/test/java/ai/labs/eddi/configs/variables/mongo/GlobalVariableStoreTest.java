/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.variables.mongo;

import ai.labs.eddi.configs.variables.model.GlobalVariable;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.result.DeleteResult;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link GlobalVariableStore} — MongoDB adapter. Uses mocked
 * {@link MongoCollection} to verify CRUD operations without requiring a running
 * MongoDB instance.
 */
@SuppressWarnings("unchecked")
class GlobalVariableStoreTest {

    private MongoCollection<Document> collection;
    private GlobalVariableStore store;

    @BeforeEach
    void setUp() {
        MongoDatabase database = mock(MongoDatabase.class);
        collection = mock(MongoCollection.class);
        when(database.getCollection("globalvariables")).thenReturn(collection);
        store = new GlobalVariableStore(database);
    }

    // ==================== getAll ====================

    @Test
    @DisplayName("getAll — returns empty map when collection is empty")
    void getAllEmpty() {
        FindIterable<Document> iterable = mock(FindIterable.class);
        when(collection.find()).thenReturn(iterable);
        // forEach does nothing — no documents

        Map<String, String> result = store.getAll();

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("getAll — returns key-value pairs from documents")
    void getAllWithDocuments() {
        FindIterable<Document> iterable = mock(FindIterable.class);
        when(collection.find()).thenReturn(iterable);

        doAnswer(invocation -> {
            Consumer<Document> consumer = invocation.getArgument(0);
            consumer.accept(new Document("_id", "model").append("value", "gpt-4.1"));
            consumer.accept(new Document("_id", "temp").append("value", "0.7"));
            return null;
        }).when(iterable).forEach(any(Consumer.class));

        Map<String, String> result = store.getAll();

        assertEquals(2, result.size());
        assertEquals("gpt-4.1", result.get("model"));
        assertEquals("0.7", result.get("temp"));
    }

    // ==================== get ====================

    @Test
    @DisplayName("get — returns GlobalVariable when document exists")
    void getFound() {
        FindIterable<Document> iterable = mock(FindIterable.class);
        when(collection.find(any(Document.class))).thenReturn(iterable);
        when(iterable.first()).thenReturn(
                new Document("_id", "model")
                        .append("value", "gpt-4.1")
                        .append("description", "Default model")
                        .append("exportable", true));

        GlobalVariable result = store.get("model");

        assertNotNull(result);
        assertEquals("model", result.key());
        assertEquals("gpt-4.1", result.value());
        assertEquals("Default model", result.description());
        assertTrue(result.exportable());
    }

    @Test
    @DisplayName("get — returns null when document does not exist")
    void getNotFound() {
        FindIterable<Document> iterable = mock(FindIterable.class);
        when(collection.find(any(Document.class))).thenReturn(iterable);
        when(iterable.first()).thenReturn(null);

        assertNull(store.get("missing"));
    }

    @Test
    @DisplayName("get — defaults exportable to true when field is absent")
    void getDefaultsExportable() {
        FindIterable<Document> iterable = mock(FindIterable.class);
        when(collection.find(any(Document.class))).thenReturn(iterable);
        when(iterable.first()).thenReturn(
                new Document("_id", "key").append("value", "val"));

        GlobalVariable result = store.get("key");

        assertNotNull(result);
        assertTrue(result.exportable());
    }

    // ==================== upsert ====================

    @Test
    @DisplayName("upsert — calls replaceOne with upsert=true")
    void upsert() {
        var variable = new GlobalVariable("model", "gpt-4.1", "Default", true);

        store.upsert(variable);

        ArgumentCaptor<Document> filterCaptor = ArgumentCaptor.forClass(Document.class);
        ArgumentCaptor<Document> docCaptor = ArgumentCaptor.forClass(Document.class);
        ArgumentCaptor<ReplaceOptions> optionsCaptor = ArgumentCaptor.forClass(ReplaceOptions.class);

        verify(collection).replaceOne(filterCaptor.capture(), docCaptor.capture(), optionsCaptor.capture());

        assertEquals("model", filterCaptor.getValue().getString("_id"));
        assertEquals("model", docCaptor.getValue().getString("_id"));
        assertEquals("gpt-4.1", docCaptor.getValue().getString("value"));
        assertEquals("Default", docCaptor.getValue().getString("description"));
        assertTrue(docCaptor.getValue().getBoolean("exportable"));
        assertTrue(optionsCaptor.getValue().isUpsert());
    }

    @Test
    @DisplayName("upsert — stores null description when not provided")
    void upsertNullDescription() {
        var variable = new GlobalVariable("key", "val", null, false);

        store.upsert(variable);

        ArgumentCaptor<Document> docCaptor = ArgumentCaptor.forClass(Document.class);
        verify(collection).replaceOne(any(Document.class), docCaptor.capture(), any(ReplaceOptions.class));

        assertNull(docCaptor.getValue().getString("description"));
        assertFalse(docCaptor.getValue().getBoolean("exportable"));
    }

    // ==================== delete ====================

    @Test
    @DisplayName("delete — calls deleteOne with correct filter")
    void delete() {
        when(collection.deleteOne(any(Document.class))).thenReturn(mock(DeleteResult.class));

        store.delete("model");

        ArgumentCaptor<Document> captor = ArgumentCaptor.forClass(Document.class);
        verify(collection).deleteOne(captor.capture());
        assertEquals("model", captor.getValue().getString("_id"));
    }

    // ==================== listAll ====================

    @Test
    @DisplayName("listAll — returns empty list when collection is empty")
    void listAllEmpty() {
        FindIterable<Document> iterable = mock(FindIterable.class);
        when(collection.find()).thenReturn(iterable);

        List<GlobalVariable> result = store.listAll();

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("listAll — returns GlobalVariable objects")
    void listAllWithDocuments() {
        FindIterable<Document> iterable = mock(FindIterable.class);
        when(collection.find()).thenReturn(iterable);

        doAnswer(invocation -> {
            Consumer<Document> consumer = invocation.getArgument(0);
            consumer.accept(new Document("_id", "a").append("value", "1")
                    .append("description", "desc-a").append("exportable", true));
            consumer.accept(new Document("_id", "b").append("value", "2")
                    .append("description", null).append("exportable", false));
            return null;
        }).when(iterable).forEach(any(Consumer.class));

        List<GlobalVariable> result = store.listAll();

        assertEquals(2, result.size());
        assertEquals("a", result.get(0).key());
        assertEquals("1", result.get(0).value());
        assertEquals("desc-a", result.get(0).description());
        assertTrue(result.get(0).exportable());
        assertEquals("b", result.get(1).key());
        assertFalse(result.get(1).exportable());
    }
}
