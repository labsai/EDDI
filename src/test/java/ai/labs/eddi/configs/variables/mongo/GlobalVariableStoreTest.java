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
import org.bson.conversions.Bson;
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
 * Unit tests for {@link GlobalVariableStore} — MongoDB adapter with tenant
 * scoping. Uses mocked {@link MongoCollection} to verify CRUD operations.
 */
@SuppressWarnings("unchecked")
class GlobalVariableStoreTest {

    private static final String DEFAULT = GlobalVariable.DEFAULT_TENANT;

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
    @DisplayName("getAll — returns empty map when no docs for tenant")
    void getAllEmpty() {
        FindIterable<Document> iterable = mock(FindIterable.class);
        when(collection.find(any(Bson.class))).thenReturn(iterable);

        Map<String, String> result = store.getAll(DEFAULT);
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("getAll — returns key-value pairs for specific tenant")
    void getAllWithDocuments() {
        FindIterable<Document> iterable = mock(FindIterable.class);
        when(collection.find(any(Bson.class))).thenReturn(iterable);

        doAnswer(invocation -> {
            Consumer<Document> consumer = invocation.getArgument(0);
            consumer.accept(new Document("tenantId", DEFAULT).append("key", "model").append("value", "gpt-4.1"));
            consumer.accept(new Document("tenantId", DEFAULT).append("key", "temp").append("value", "0.7"));
            return null;
        }).when(iterable).forEach(any(Consumer.class));

        Map<String, String> result = store.getAll(DEFAULT);
        assertEquals(2, result.size());
        assertEquals("gpt-4.1", result.get("model"));
        assertEquals("0.7", result.get("temp"));
    }

    // ==================== get ====================

    @Test
    @DisplayName("get — returns GlobalVariable when document exists")
    void getFound() {
        FindIterable<Document> iterable = mock(FindIterable.class);
        when(collection.find(any(Bson.class))).thenReturn(iterable);
        when(iterable.first()).thenReturn(
                new Document("tenantId", DEFAULT)
                        .append("key", "model")
                        .append("value", "gpt-4.1")
                        .append("description", "Default model")
                        .append("exportable", true));

        GlobalVariable result = store.get(DEFAULT, "model");
        assertNotNull(result);
        assertEquals(DEFAULT, result.tenantId());
        assertEquals("model", result.key());
        assertEquals("gpt-4.1", result.value());
        assertEquals("Default model", result.description());
        assertTrue(result.exportable());
    }

    @Test
    @DisplayName("get — returns null when document does not exist")
    void getNotFound() {
        FindIterable<Document> iterable = mock(FindIterable.class);
        when(collection.find(any(Bson.class))).thenReturn(iterable);
        when(iterable.first()).thenReturn(null);

        assertNull(store.get(DEFAULT, "missing"));
    }

    // ==================== upsert ====================

    @Test
    @DisplayName("upsert — stores document with composite _id and tenant fields")
    void upsert() {
        var variable = new GlobalVariable(DEFAULT, "model", "gpt-4.1", "Default", true);
        store.upsert(variable);

        ArgumentCaptor<Document> docCaptor = ArgumentCaptor.forClass(Document.class);
        ArgumentCaptor<ReplaceOptions> optionsCaptor = ArgumentCaptor.forClass(ReplaceOptions.class);
        verify(collection).replaceOne(any(Document.class), docCaptor.capture(), optionsCaptor.capture());

        Document stored = docCaptor.getValue();
        assertEquals("default/model", stored.getString("_id"));
        assertEquals(DEFAULT, stored.getString("tenantId"));
        assertEquals("model", stored.getString("key"));
        assertEquals("gpt-4.1", stored.getString("value"));
        assertTrue(optionsCaptor.getValue().isUpsert());
    }

    @Test
    @DisplayName("upsert — different tenant produces different _id")
    void upsertDifferentTenant() {
        var variable = new GlobalVariable("tenant-a", "model", "claude");
        store.upsert(variable);

        ArgumentCaptor<Document> docCaptor = ArgumentCaptor.forClass(Document.class);
        verify(collection).replaceOne(any(Document.class), docCaptor.capture(), any(ReplaceOptions.class));
        assertEquals("tenant-a/model", docCaptor.getValue().getString("_id"));
        assertEquals("tenant-a", docCaptor.getValue().getString("tenantId"));
    }

    // ==================== delete ====================

    @Test
    @DisplayName("delete — calls deleteOne with tenant+key filter")
    void delete() {
        when(collection.deleteOne(any(Bson.class))).thenReturn(mock(DeleteResult.class));
        store.delete(DEFAULT, "model");
        verify(collection).deleteOne(any(Bson.class));
    }

    // ==================== listAll ====================

    @Test
    @DisplayName("listAll — returns empty list when no docs for tenant")
    void listAllEmpty() {
        FindIterable<Document> iterable = mock(FindIterable.class);
        when(collection.find(any(Bson.class))).thenReturn(iterable);

        List<GlobalVariable> result = store.listAll(DEFAULT);
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("listAll — returns GlobalVariable objects for specific tenant")
    void listAllWithDocuments() {
        FindIterable<Document> iterable = mock(FindIterable.class);
        when(collection.find(any(Bson.class))).thenReturn(iterable);

        doAnswer(invocation -> {
            Consumer<Document> consumer = invocation.getArgument(0);
            consumer.accept(new Document("tenantId", DEFAULT).append("key", "a").append("value", "1")
                    .append("description", "desc-a").append("exportable", true));
            consumer.accept(new Document("tenantId", DEFAULT).append("key", "b").append("value", "2")
                    .append("description", null).append("exportable", false));
            return null;
        }).when(iterable).forEach(any(Consumer.class));

        List<GlobalVariable> result = store.listAll(DEFAULT);
        assertEquals(2, result.size());
        assertEquals("a", result.get(0).key());
        assertEquals(DEFAULT, result.get(0).tenantId());
        assertEquals("b", result.get(1).key());
    }
}
