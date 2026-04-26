/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.mongo;

import ai.labs.eddi.datastore.IResourceFilter.QueryFilter;
import ai.labs.eddi.datastore.IResourceFilter.QueryFilters;
import ai.labs.eddi.datastore.IResourceStore;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import org.bson.BsonDocument;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.bson.types.ObjectId;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ResourceFilter} covering query construction,
 * pagination, sorting, and resource retrieval delegation.
 */
class ResourceFilterTest {

    private MongoCollection<Document> collection;
    private IResourceStore<Object> resourceStore;
    private ResourceFilter<Object> filter;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        collection = mock(MongoCollection.class);
        resourceStore = mock(IResourceStore.class);
        filter = new ResourceFilter<>(collection, resourceStore);
    }

    // ─── readResources ────────────────────────────────────

    @Nested
    @DisplayName("readResources")
    class ReadResources {

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("should return results from collection with AND-connected string filter")
        void returnsResultsWithStringFilter() throws Exception {
            // Setup: AND filter with a string field (triggers regex path)
            var queryFilter = new QueryFilter("name", "test.*");
            var queryFilters = new QueryFilters(List.of(queryFilter));

            // Mock the collection find chain
            var mockId = new ObjectId();
            Document doc = new Document("_id", mockId).append("_version", 1);
            FindIterable<Document> iterable = mock(FindIterable.class);
            when(collection.find(any(BsonDocument.class))).thenReturn(iterable);
            when(iterable.sort(any(Document.class))).thenReturn(iterable);
            when(iterable.limit(anyInt())).thenReturn(iterable);
            when(iterable.skip(anyInt())).thenReturn(iterable);

            MongoCursor<Document> cursor = mock(MongoCursor.class);
            when(iterable.iterator()).thenReturn(cursor);
            when(cursor.hasNext()).thenReturn(true, false);
            when(cursor.next()).thenReturn(doc);

            Object expected = new Object();
            when(resourceStore.read(mockId.toString(), 1)).thenReturn(expected);

            List<Object> result = filter.readResources(
                    new QueryFilters[]{queryFilters}, 0, 10, "lastModifiedOn");

            assertEquals(1, result.size());
            assertSame(expected, result.get(0));
        }

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("should use eq filter for non-string filter values (boolean)")
        void usesEqFilterForBoolean() throws Exception {
            var queryFilter = new QueryFilter("deleted", false);
            var queryFilters = new QueryFilters(List.of(queryFilter));

            FindIterable<Document> iterable = mock(FindIterable.class);
            when(collection.find(any(BsonDocument.class))).thenReturn(iterable);
            when(iterable.sort(any(Document.class))).thenReturn(iterable);
            when(iterable.limit(anyInt())).thenReturn(iterable);

            MongoCursor<Document> cursor = mock(MongoCursor.class);
            when(iterable.iterator()).thenReturn(cursor);
            when(cursor.hasNext()).thenReturn(false);

            List<Object> result = filter.readResources(
                    new QueryFilters[]{queryFilters}, null, 10);

            assertNotNull(result);
            assertTrue(result.isEmpty());
        }

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("should use OR connecting type for optional filters")
        void usesOrConnectingType() throws Exception {
            var q1 = new QueryFilter("name", "search");
            var q2 = new QueryFilter("description", "search");
            var queryFilters = new QueryFilters(QueryFilters.ConnectingType.OR, List.of(q1, q2));

            FindIterable<Document> iterable = mock(FindIterable.class);
            when(collection.find(any(BsonDocument.class))).thenReturn(iterable);
            when(iterable.sort(any(Document.class))).thenReturn(iterable);
            when(iterable.limit(anyInt())).thenReturn(iterable);

            MongoCursor<Document> cursor = mock(MongoCursor.class);
            when(iterable.iterator()).thenReturn(cursor);
            when(cursor.hasNext()).thenReturn(false);

            List<Object> result = filter.readResources(
                    new QueryFilters[]{queryFilters}, 0, 5, "name");

            assertNotNull(result);
        }

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("should default to limit=20 when limit is null")
        void defaultsLimitWhenNull() throws Exception {
            var queryFilter = new QueryFilter("field", false);
            var queryFilters = new QueryFilters(List.of(queryFilter));

            FindIterable<Document> iterable = mock(FindIterable.class);
            when(collection.find(any(BsonDocument.class))).thenReturn(iterable);
            when(iterable.sort(any(Document.class))).thenReturn(iterable);
            when(iterable.limit(anyInt())).thenReturn(iterable);

            MongoCursor<Document> cursor = mock(MongoCursor.class);
            when(iterable.iterator()).thenReturn(cursor);
            when(cursor.hasNext()).thenReturn(false);

            filter.readResources(new QueryFilters[]{queryFilters}, null, null);

            verify(iterable).limit(20);
        }

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("should default to limit=20 when limit is 0")
        void defaultsLimitWhenZero() throws Exception {
            var queryFilter = new QueryFilter("field", false);
            var queryFilters = new QueryFilters(List.of(queryFilter));

            FindIterable<Document> iterable = mock(FindIterable.class);
            when(collection.find(any(BsonDocument.class))).thenReturn(iterable);
            when(iterable.sort(any(Document.class))).thenReturn(iterable);
            when(iterable.limit(anyInt())).thenReturn(iterable);

            MongoCursor<Document> cursor = mock(MongoCursor.class);
            when(iterable.iterator()).thenReturn(cursor);
            when(cursor.hasNext()).thenReturn(false);

            filter.readResources(new QueryFilters[]{queryFilters}, null, 0);

            verify(iterable).limit(20);
        }

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("should skip pages when index > 0")
        void skipsPages() throws Exception {
            var queryFilter = new QueryFilter("field", false);
            var queryFilters = new QueryFilters(List.of(queryFilter));

            FindIterable<Document> iterable = mock(FindIterable.class);
            when(collection.find(any(BsonDocument.class))).thenReturn(iterable);
            when(iterable.sort(any(Document.class))).thenReturn(iterable);
            when(iterable.limit(anyInt())).thenReturn(iterable);
            when(iterable.skip(anyInt())).thenReturn(iterable);

            MongoCursor<Document> cursor = mock(MongoCursor.class);
            when(iterable.iterator()).thenReturn(cursor);
            when(cursor.hasNext()).thenReturn(false);

            filter.readResources(new QueryFilters[]{queryFilters}, 2, 10);

            // index=2, limit=10 → skip(2*10=20)
            verify(iterable).skip(20);
        }

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("should skip 0 when index is 0")
        void skipsZeroForIndexZero() throws Exception {
            var queryFilter = new QueryFilter("field", false);
            var queryFilters = new QueryFilters(List.of(queryFilter));

            FindIterable<Document> iterable = mock(FindIterable.class);
            when(collection.find(any(BsonDocument.class))).thenReturn(iterable);
            when(iterable.sort(any(Document.class))).thenReturn(iterable);
            when(iterable.limit(anyInt())).thenReturn(iterable);
            when(iterable.skip(anyInt())).thenReturn(iterable);

            MongoCursor<Document> cursor = mock(MongoCursor.class);
            when(iterable.iterator()).thenReturn(cursor);
            when(cursor.hasNext()).thenReturn(false);

            filter.readResources(new QueryFilters[]{queryFilters}, 0, 10);

            verify(iterable).skip(0);
        }

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("should combine AND and OR filters in multi-filter query")
        void combinesAndOrFilters() throws Exception {
            var requiredFilter = new QueryFilter("resource", "eddi://ai.labs.agent.*");
            var requiredFilters = new QueryFilters(List.of(requiredFilter));

            var optQ1 = new QueryFilter("name", "search");
            var optQ2 = new QueryFilter("description", "search");
            var optionalFilters = new QueryFilters(QueryFilters.ConnectingType.OR, List.of(optQ1, optQ2));

            FindIterable<Document> iterable = mock(FindIterable.class);
            when(collection.find(any(BsonDocument.class))).thenReturn(iterable);
            when(iterable.sort(any(Document.class))).thenReturn(iterable);
            when(iterable.limit(anyInt())).thenReturn(iterable);

            MongoCursor<Document> cursor = mock(MongoCursor.class);
            when(iterable.iterator()).thenReturn(cursor);
            when(cursor.hasNext()).thenReturn(false);

            List<Object> result = filter.readResources(
                    new QueryFilters[]{requiredFilters, optionalFilters}, 0, 10, "lastModifiedOn");

            assertNotNull(result);
            verify(collection).find(any(BsonDocument.class));
        }

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("should handle multiple sort types")
        void multipleSortTypes() throws Exception {
            var queryFilter = new QueryFilter("field", false);
            var queryFilters = new QueryFilters(List.of(queryFilter));

            FindIterable<Document> iterable = mock(FindIterable.class);
            when(collection.find(any(BsonDocument.class))).thenReturn(iterable);
            when(iterable.sort(any(Document.class))).thenReturn(iterable);
            when(iterable.limit(anyInt())).thenReturn(iterable);

            MongoCursor<Document> cursor = mock(MongoCursor.class);
            when(iterable.iterator()).thenReturn(cursor);
            when(cursor.hasNext()).thenReturn(false);

            filter.readResources(new QueryFilters[]{queryFilters}, null, 5, "name", "lastModifiedOn");

            // Verify sort was called (sorting with two fields)
            verify(iterable).sort(argThat(doc -> {
                Document sortDoc = (Document) doc;
                return sortDoc.containsKey("name") && sortDoc.containsKey("lastModifiedOn");
            }));
        }
    }
}
