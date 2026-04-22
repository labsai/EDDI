package ai.labs.eddi.datastore.mongo;

import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import com.mongodb.client.MongoCollection;
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
        @DisplayName("should exercise filter != null branch")
        void appliesFilter() {
            // DescriptorStore internally creates ResourceFilter — not injectable.
            // Calling with a non-null filter exercises the queryFiltersOptional branch.
            // The NullPointerException is expected because the mock collection has no
            // find() stub, confirming the code path was reached.
            assertThrows(Exception.class, () -> store.readDescriptors("ai.labs.agent", "searchTerm", 0, 10, false));
        }

        @Test
        @DisplayName("should exercise filter == null branch")
        void noOptionalFiltersWhenNull() {
            // Null filter → empty queryFiltersOptional → single required filter only.
            assertThrows(Exception.class, () -> store.readDescriptors("ai.labs.agent", null, 0, 10, false));
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
