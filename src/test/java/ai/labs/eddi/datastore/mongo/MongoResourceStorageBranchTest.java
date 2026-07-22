/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.mongo;

import ai.labs.eddi.datastore.IResourceFilter;
import ai.labs.eddi.datastore.IResourceStorage;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Additional branch coverage for {@link MongoResourceStorage}:
 * <ul>
 * <li>findHistoryResourceIdsContaining — Document vs non-Document _id</li>
 * <li>findResources — AND/OR filters, sort field null, skip/limit edge
 * cases</li>
 * <li>readHistoryLatest — non-null doc after sort</li>
 * <li>readHistoryLatest — null doc after sort (count > 0 but find returns
 * null)</li>
 * <li>checkInternalHistoryResource — rejects external implementations</li>
 * <li>constructor with custom indexes</li>
 * </ul>
 */
@SuppressWarnings("unchecked")
@DisplayName("MongoResourceStorage — Extended Branch Coverage")
class MongoResourceStorageBranchTest {

    private static final String COLLECTION_NAME = "testcol";
    private static final String VALID_ID = "aabbccddeeff112233445566";

    private MongoCollection<Document> currentCollection;
    private MongoCollection<Document> historyCollection;
    private IDocumentBuilder documentBuilder;
    private MongoResourceStorage<String> storage;

    @BeforeEach
    void setUp() {
        MongoDatabase database = mock(MongoDatabase.class);
        currentCollection = mock(MongoCollection.class);
        historyCollection = mock(MongoCollection.class);
        documentBuilder = mock(IDocumentBuilder.class);

        when(database.getCollection(COLLECTION_NAME)).thenReturn(currentCollection);
        when(database.getCollection(COLLECTION_NAME + ".history")).thenReturn(historyCollection);

        storage = new MongoResourceStorage<>(database, COLLECTION_NAME, documentBuilder, String.class);
    }

    // ==================== Constructor with indexes ====================

    @Test
    @DisplayName("Constructor with indexes — creates indexes on both collections")
    void constructorWithIndexes() throws Exception {
        MongoDatabase database = mock(MongoDatabase.class);
        MongoCollection<Document> curCol = mock(MongoCollection.class);
        MongoCollection<Document> histCol = mock(MongoCollection.class);
        when(database.getCollection("indexed")).thenReturn(curCol);
        when(database.getCollection("indexed.history")).thenReturn(histCol);

        new MongoResourceStorage<>(database, "indexed", documentBuilder, String.class, "field1", "field2");

        // The ID_FIELD+VERSION_FIELD unique index on currentCollection +
        // two indexes on each collection for field1, field2
        // = 1 (unique on current) + 2 (on current) + 2 (on history) = 5 createIndex
        // calls
        verify(curCol, times(3)).createIndex(any(Bson.class), any());
        verify(histCol, times(2)).createIndex(any(Bson.class), any());
    }

    // ==================== findHistoryResourceIdsContaining ====================

    @Nested
    @DisplayName("findHistoryResourceIdsContaining")
    class FindHistoryResourceIdsTests {

        @Test
        @DisplayName("Document _id — extracts id and version from nested Document")
        void documentIdExtraction() throws Exception {
            Document innerIdDoc = new Document("_id", new ObjectId(VALID_ID)).append("_version", 3);
            Document doc = new Document("_id", innerIdDoc).append("data", "test");

            FindIterable<Document> iterable = mock(FindIterable.class);
            when(historyCollection.find(any(Document.class))).thenReturn(iterable);

            doAnswer(inv -> {
                java.util.function.Consumer<Document> consumer = inv.getArgument(0);
                consumer.accept(doc);
                return null;
            }).when(iterable).forEach(any(java.util.function.Consumer.class));

            List<IResourceStore.IResourceId> result = storage.findHistoryResourceIdsContaining("path", "value");
            assertEquals(1, result.size());
            assertEquals(VALID_ID, result.getFirst().getId());
            assertEquals(3, result.getFirst().getVersion());
        }

        @Test
        @DisplayName("non-Document _id — skips entry")
        void nonDocumentIdSkipped() throws Exception {
            // _id is a plain ObjectId, not a nested Document
            Document doc = new Document("_id", new ObjectId(VALID_ID)).append("data", "test");

            FindIterable<Document> iterable = mock(FindIterable.class);
            when(historyCollection.find(any(Document.class))).thenReturn(iterable);

            doAnswer(inv -> {
                java.util.function.Consumer<Document> consumer = inv.getArgument(0);
                consumer.accept(doc);
                return null;
            }).when(iterable).forEach(any(java.util.function.Consumer.class));

            List<IResourceStore.IResourceId> result = storage.findHistoryResourceIdsContaining("path", "value");
            assertEquals(0, result.size());
        }
    }

    // ==================== findResources ====================

    @Nested
    @DisplayName("findResources")
    class FindResourcesTests {

        @Test
        @DisplayName("AND filter with string field — uses regex")
        void andFilterStringField() throws Exception {
            IResourceFilter.QueryFilter qf = mock(IResourceFilter.QueryFilter.class);
            when(qf.getField()).thenReturn("name");
            when(qf.getFilter()).thenReturn("test.*");

            IResourceFilter.QueryFilters qfs = mock(IResourceFilter.QueryFilters.class);
            when(qfs.getQueryFilters()).thenReturn(List.of(qf));
            when(qfs.getConnectingType()).thenReturn(IResourceFilter.QueryFilters.ConnectingType.AND);

            Document doc = new Document("_id", new ObjectId(VALID_ID)).append("_version", 1);
            FindIterable<Document> iterable = mock(FindIterable.class);
            when(currentCollection.find(any(org.bson.BsonDocument.class))).thenReturn(iterable);
            when(iterable.sort(any(Document.class))).thenReturn(iterable);
            when(iterable.limit(anyInt())).thenReturn(iterable);
            when(iterable.skip(anyInt())).thenReturn(iterable);

            doAnswer(inv -> {
                java.util.function.Consumer<Document> consumer = inv.getArgument(0);
                consumer.accept(doc);
                return null;
            }).when(iterable).forEach(any(java.util.function.Consumer.class));

            List<IResourceStore.IResourceId> result = storage.findResources(
                    new IResourceFilter.QueryFilters[]{qfs}, "name", 0, 10);

            assertEquals(1, result.size());
            assertEquals(VALID_ID, result.getFirst().getId());
        }

        @Test
        @DisplayName("OR filter with non-string field — uses eq")
        void orFilterNonStringField() throws Exception {
            IResourceFilter.QueryFilter qf = mock(IResourceFilter.QueryFilter.class);
            when(qf.getField()).thenReturn("age");
            when(qf.getFilter()).thenReturn(25); // Integer, not String

            IResourceFilter.QueryFilters qfs = mock(IResourceFilter.QueryFilters.class);
            when(qfs.getQueryFilters()).thenReturn(List.of(qf));
            when(qfs.getConnectingType()).thenReturn(IResourceFilter.QueryFilters.ConnectingType.OR);

            FindIterable<Document> iterable = mock(FindIterable.class);
            when(currentCollection.find(any(org.bson.BsonDocument.class))).thenReturn(iterable);
            when(iterable.sort(any(Document.class))).thenReturn(iterable);
            when(iterable.limit(anyInt())).thenReturn(iterable);
            when(iterable.skip(anyInt())).thenReturn(iterable);
            doNothing().when(iterable).forEach(any(java.util.function.Consumer.class));

            List<IResourceStore.IResourceId> result = storage.findResources(
                    new IResourceFilter.QueryFilters[]{qfs}, null, -1, 0);

            assertNotNull(result);
        }

        @Test
        @DisplayName("null sortField → empty sort document; limit < 1 → unlimited up to the ceiling")
        void nullSortAndDefaultLimit() throws Exception {
            IResourceFilter.QueryFilter qf = mock(IResourceFilter.QueryFilter.class);
            when(qf.getField()).thenReturn("name");
            when(qf.getFilter()).thenReturn("test");

            IResourceFilter.QueryFilters qfs = mock(IResourceFilter.QueryFilters.class);
            when(qfs.getQueryFilters()).thenReturn(List.of(qf));
            when(qfs.getConnectingType()).thenReturn(IResourceFilter.QueryFilters.ConnectingType.AND);

            FindIterable<Document> iterable = mock(FindIterable.class);
            when(currentCollection.find(any(org.bson.BsonDocument.class))).thenReturn(iterable);
            when(iterable.sort(any(Document.class))).thenReturn(iterable);
            when(iterable.limit(anyInt())).thenReturn(iterable);
            when(iterable.skip(anyInt())).thenReturn(iterable);
            doNothing().when(iterable).forEach(any(java.util.function.Consumer.class));

            // sortField=null, skip=0, limit=0 → "no caller limit" → the ceiling
            List<IResourceStore.IResourceId> result = storage.findResources(
                    new IResourceFilter.QueryFilters[]{qfs}, null, 0, 0);

            verify(iterable).limit(IResourceStorage.MAX_RESULT_LIMIT);
            verify(iterable).skip(0);
        }
    }

    // ==================== readHistoryLatest edge cases ====================

    @Nested
    @DisplayName("readHistoryLatest edge cases")
    class ReadHistoryLatestEdgeCases {

        @Test
        @DisplayName("count > 0 but find returns null → returns null")
        void countPositiveButFindReturnsNull() throws Exception {
            when(historyCollection.countDocuments(any(Document.class))).thenReturn(1L);

            FindIterable<Document> iterable = mock(FindIterable.class);
            when(historyCollection.find(any(Document.class))).thenReturn(iterable);
            when(iterable.sort(any(Document.class))).thenReturn(iterable);
            when(iterable.limit(1)).thenReturn(iterable);
            when(iterable.first()).thenReturn(null);

            assertNull(storage.readHistoryLatest(VALID_ID));
        }
    }

    // ==================== checkInternalHistoryResource ====================

    @Test
    @DisplayName("store(IHistoryResource) rejects external implementations")
    void storeExternalHistoryResource() throws Exception {
        IResourceStorage.IHistoryResource<String> fakeHistory = mock(IResourceStorage.IHistoryResource.class);
        assertThrows(IllegalArgumentException.class, () -> storage.store(fakeHistory));
    }

    // ==================== Resource.getId with null ====================

    @Test
    @DisplayName("Resource.getId returns null when _id is absent")
    void resourceGetIdNull() throws Exception {
        when(documentBuilder.toString(any())).thenReturn("{}");
        IResourceStorage.IResource<String> resource = storage.newResource("test");
        // newResource creates a doc without _id → getId returns null
        assertNull(resource.getId());
    }
}
