/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.mongo;

import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link DescriptorStore}. Mocks the MongoDatabase and verifies
 * the delegation pattern to internal resource storage and filter components.
 */
class DescriptorStoreTest {

    private MongoDatabase database;
    private IDocumentBuilder documentBuilder;
    private DescriptorStore<Object> store;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        database = mock(MongoDatabase.class);
        documentBuilder = mock(IDocumentBuilder.class);

        MongoCollection<Document> mockCollection = mock(MongoCollection.class);
        when(database.getCollection(anyString())).thenReturn(mockCollection);

        // Stub the find() chain so readDescriptors can execute to completion
        // instead of throwing an NPE from an un-stubbed mock.
        FindIterable<Document> mockIterable = mock(FindIterable.class);
        when(mockCollection.find(any(Bson.class))).thenReturn(mockIterable);
        when(mockIterable.sort(any(Document.class))).thenReturn(mockIterable);
        when(mockIterable.limit(anyInt())).thenReturn(mockIterable);
        when(mockIterable.skip(anyInt())).thenReturn(mockIterable);
        MongoCursor<Document> mockCursor = mock(MongoCursor.class);
        when(mockCursor.hasNext()).thenReturn(false);
        when(mockIterable.iterator()).thenReturn(mockCursor);

        store = new DescriptorStore<>(database, documentBuilder, Object.class);
    }

    // ─── Constructor ──────────────────────────────────────────

    @Nested
    @DisplayName("Constructor")
    class ConstructorTests {

        @Test
        @DisplayName("should throw on null database")
        void throwsOnNullDatabase() {
            assertThrows(IllegalArgumentException.class,
                    () -> new DescriptorStore<>(null, documentBuilder, Object.class));
        }

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("should create indexes on descriptors collection")
        void createsIndexes() {
            // Verify that createIndex was called during construction
            MongoCollection<Document> col = mock(MongoCollection.class);
            MongoDatabase db = mock(MongoDatabase.class);
            when(db.getCollection(anyString())).thenReturn(col);

            new DescriptorStore<>(db, documentBuilder, Object.class);

            // 7 field indexes on descriptors + 1 unique composite on the resource storage
            // collection + at least 1 on the history collection
            verify(col, atLeast(7)).createIndex(any(Bson.class), any(IndexOptions.class));
        }
    }

    // ─── readDescriptors ──────────────────────────────────────

    @Nested
    @DisplayName("readDescriptors")
    class ReadDescriptors {

        @Test
        @DisplayName("should return empty list when filter is non-null and no results match")
        void appliesFilter() throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {
            // Calling with a non-null filter exercises the queryFiltersOptional branch
            // (OR filter with name, description, userId, resource fields).
            List<Object> result = store.readDescriptors("ai.labs.agent", "searchTerm", 0, 10, false);

            assertNotNull(result);
            assertTrue(result.isEmpty(), "Expected empty list when no documents match the filter");
        }

        @Test
        @DisplayName("should return empty list when filter is null (required filters only)")
        void noOptionalFiltersWhenNull() throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {
            // Null filter → empty queryFiltersOptional → single required filter only.
            List<Object> result = store.readDescriptors("ai.labs.agent", null, 0, 10, false);

            assertNotNull(result);
            assertTrue(result.isEmpty(), "Expected empty list when no documents exist");
        }
    }

    // ─── findByOriginId ──────────────────────────────────────

    @Test
    @DisplayName("findByOriginId — should return empty list (legacy stub)")
    void findByOriginIdReturnsEmpty() {
        List<Object> result = store.findByOriginId("any-id");
        assertTrue(result.isEmpty());
    }
}
