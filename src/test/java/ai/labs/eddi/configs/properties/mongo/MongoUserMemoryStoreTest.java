/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.properties.mongo;

import ai.labs.eddi.configs.properties.model.Properties;
import ai.labs.eddi.configs.properties.model.Property.Visibility;
import ai.labs.eddi.configs.properties.model.UserMemoryEntry;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.UpdateResult;
import org.bson.BsonObjectId;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SuppressWarnings("unchecked")
class MongoUserMemoryStoreTest {

    private static final String TEST_USER = "user-1";
    private static final String TEST_AGENT = "agent-1";
    private static final ObjectId TEST_OID = new ObjectId("aabbccddeeff112233445566");

    private MongoCollection<Document> collection;
    private MongoUserMemoryStore store;

    @BeforeEach
    void setUp() {
        MongoDatabase database = mock(MongoDatabase.class);
        collection = mock(MongoCollection.class);
        when(database.getCollection("usermemories")).thenReturn(collection);
        store = new MongoUserMemoryStore(database);
    }

    // ==================== readProperties ====================

    @Test
    @DisplayName("readProperties — returns null when no global entries")
    void readPropertiesEmpty() throws Exception {
        FindIterable<Document> iterable = mock(FindIterable.class);
        when(collection.find(any(Bson.class))).thenReturn(iterable);
        MongoCursor<Document> cursor = mock(MongoCursor.class);
        doReturn(cursor).when(iterable).iterator();
        when(cursor.hasNext()).thenReturn(false);

        Properties result = store.readProperties(TEST_USER);
        assertNull(result);
    }

    @Test
    @DisplayName("readProperties — returns properties map with entries")
    void readPropertiesWithData() throws Exception {
        Document doc = new Document("key", "lang").append("value", "en");
        FindIterable<Document> iterable = mock(FindIterable.class);
        when(collection.find(any(Bson.class))).thenReturn(iterable);
        MongoCursor<Document> cursor = mock(MongoCursor.class);
        doReturn(cursor).when(iterable).iterator();
        when(cursor.hasNext()).thenReturn(true, false);
        when(cursor.next()).thenReturn(doc);

        Properties result = store.readProperties(TEST_USER);
        assertNotNull(result);
        assertEquals("en", result.get("lang"));
    }

    // ==================== mergeProperties ====================

    @Test
    @DisplayName("mergeProperties — upserts each property")
    void mergeProperties() throws Exception {
        Properties props = new Properties();
        props.put("key1", "val1");
        props.put("key2", "val2");

        when(collection.updateOne(any(Bson.class), any(Bson.class), any())).thenReturn(mock(UpdateResult.class));
        store.mergeProperties(TEST_USER, props);
        verify(collection, times(2)).updateOne(any(Bson.class), any(Bson.class), any());
    }

    @Test
    @DisplayName("mergeProperties — skips empty properties")
    void mergePropertiesEmpty() throws Exception {
        store.mergeProperties(TEST_USER, new Properties());
        verify(collection, never()).updateOne(any(org.bson.conversions.Bson.class), any(org.bson.conversions.Bson.class),
                any(com.mongodb.client.model.UpdateOptions.class));
    }

    @Test
    @DisplayName("mergeProperties — skips _id and userId keys")
    void mergePropertiesSkipsReserved() throws Exception {
        Properties props = new Properties();
        props.put("_id", "should-skip");
        props.put("userId", "should-skip");
        props.put("valid", "keep");

        when(collection.updateOne(any(Bson.class), any(Bson.class), any())).thenReturn(mock(UpdateResult.class));
        store.mergeProperties(TEST_USER, props);
        verify(collection, times(1)).updateOne(any(Bson.class), any(Bson.class), any());
    }

    // ==================== deleteProperties ====================

    @Test
    @DisplayName("deleteProperties — deletes global entries for user")
    void deleteProperties() throws Exception {
        when(collection.deleteMany(any(Bson.class))).thenReturn(mock(DeleteResult.class));
        store.deleteProperties(TEST_USER);
        verify(collection).deleteMany(any(Bson.class));
    }

    // ==================== upsert ====================

    @Test
    @DisplayName("upsert — returns upserted ID for new entry")
    void upsertNew() throws Exception {
        UpdateResult updateResult = mock(UpdateResult.class);
        when(updateResult.getUpsertedId()).thenReturn(new BsonObjectId(TEST_OID));
        when(collection.updateOne(any(Bson.class), any(Bson.class), any())).thenReturn(updateResult);

        UserMemoryEntry entry = new UserMemoryEntry(null, TEST_USER, "myKey", "myVal", "cat",
                Visibility.self, TEST_AGENT, List.of(), null, false, 0, null, null);

        String id = store.upsert(entry);
        assertEquals(TEST_OID.toHexString(), id);
    }

    @Test
    @DisplayName("upsert — returns existing ID for update")
    void upsertExisting() throws Exception {
        UpdateResult updateResult = mock(UpdateResult.class);
        when(updateResult.getUpsertedId()).thenReturn(null);
        when(collection.updateOne(any(Bson.class), any(Bson.class), any())).thenReturn(updateResult);

        Document existingDoc = new Document("_id", TEST_OID);
        FindIterable<Document> iterable = mock(FindIterable.class);
        when(collection.find(any(Bson.class))).thenReturn(iterable);
        when(iterable.first()).thenReturn(existingDoc);

        UserMemoryEntry entry = new UserMemoryEntry(null, TEST_USER, "myKey", "myVal", "cat",
                Visibility.self, TEST_AGENT, List.of(), null, false, 0, null, null);

        String id = store.upsert(entry);
        assertEquals(TEST_OID.toHexString(), id);
    }

    @Test
    @DisplayName("upsert — global visibility logs cross-agent overwrite")
    void upsertGlobalCrossAgent() throws Exception {
        // Existing entry from a different agent
        Document existingDoc = new Document("_id", TEST_OID).append("sourceAgentId", "other-agent");
        FindIterable<Document> iterable = mock(FindIterable.class);
        when(collection.find(any(Bson.class))).thenReturn(iterable);
        when(iterable.first()).thenReturn(existingDoc);

        UpdateResult updateResult = mock(UpdateResult.class);
        when(updateResult.getUpsertedId()).thenReturn(null);
        when(collection.updateOne(any(Bson.class), any(Bson.class), any())).thenReturn(updateResult);

        UserMemoryEntry entry = new UserMemoryEntry(null, TEST_USER, "myKey", "myVal", "cat",
                Visibility.global, TEST_AGENT, List.of(), null, false, 0, null, null);

        // Should not throw, just log
        assertDoesNotThrow(() -> store.upsert(entry));
    }

    // ==================== deleteEntry ====================

    @Test
    @DisplayName("deleteEntry — deletes by ObjectId")
    void deleteEntry() throws Exception {
        when(collection.deleteOne(any(Bson.class))).thenReturn(mock(DeleteResult.class));
        store.deleteEntry(TEST_OID.toHexString());
        verify(collection).deleteOne(any(Bson.class));
    }

    // ==================== findEntryById ====================

    @Test
    @DisplayName("findEntryById — returns entry when found")
    void findEntryByIdFound() throws Exception {
        Instant now = Instant.parse("2024-01-01T00:00:00Z");
        Document doc = createMemoryDoc(TEST_OID, "key1", "val1", Visibility.self, now);
        FindIterable<Document> iterable = mock(FindIterable.class);
        when(collection.find(any(Bson.class))).thenReturn(iterable);
        when(iterable.first()).thenReturn(doc);

        Optional<UserMemoryEntry> result = store.findEntryById(TEST_OID.toHexString());
        assertTrue(result.isPresent());
        assertEquals("key1", result.get().key());
    }

    @Test
    @DisplayName("findEntryById — returns empty when not found")
    void findEntryByIdNotFound() throws Exception {
        FindIterable<Document> iterable = mock(FindIterable.class);
        when(collection.find(any(Bson.class))).thenReturn(iterable);
        when(iterable.first()).thenReturn(null);

        Optional<UserMemoryEntry> result = store.findEntryById(TEST_OID.toHexString());
        assertFalse(result.isPresent());
    }

    // ==================== getVisibleEntries ====================

    @Test
    @DisplayName("getVisibleEntries — returns entries with most_recent ordering")
    void getVisibleEntriesRecentOrder() throws Exception {
        Instant now = Instant.parse("2024-01-01T00:00:00Z");
        Document doc = createMemoryDoc(TEST_OID, "key1", "val1", Visibility.self, now);

        FindIterable<Document> iterable = mock(FindIterable.class);
        when(collection.find(any(Bson.class))).thenReturn(iterable);
        when(iterable.sort(any())).thenReturn(iterable);
        when(iterable.limit(anyInt())).thenReturn(iterable);
        MongoCursor<Document> cursor = mock(MongoCursor.class);
        doReturn(cursor).when(iterable).iterator();
        when(cursor.hasNext()).thenReturn(true, false);
        when(cursor.next()).thenReturn(doc);

        List<UserMemoryEntry> result = store.getVisibleEntries(TEST_USER, TEST_AGENT, null, "most_recent", 10);
        assertEquals(1, result.size());
    }

    @Test
    @DisplayName("getVisibleEntries — most_accessed ordering increments access count")
    void getVisibleEntriesMostAccessed() throws Exception {
        Instant now = Instant.parse("2024-01-01T00:00:00Z");
        Document doc = createMemoryDoc(TEST_OID, "key1", "val1", Visibility.self, now);

        FindIterable<Document> iterable = mock(FindIterable.class);
        when(collection.find(any(Bson.class))).thenReturn(iterable);
        when(iterable.sort(any())).thenReturn(iterable);
        when(iterable.limit(anyInt())).thenReturn(iterable);
        MongoCursor<Document> cursor = mock(MongoCursor.class);
        doReturn(cursor).when(iterable).iterator();
        when(cursor.hasNext()).thenReturn(true, false);
        when(cursor.next()).thenReturn(doc);
        when(collection.updateOne(any(Bson.class), any(Bson.class))).thenReturn(mock(UpdateResult.class));

        List<UserMemoryEntry> result = store.getVisibleEntries(TEST_USER, TEST_AGENT, null, "most_accessed", 10);
        assertEquals(1, result.size());
        verify(collection).updateOne(any(Bson.class), any(Bson.class));
    }

    // ==================== filterEntries ====================

    @Test
    @DisplayName("filterEntries — returns all entries when query is blank")
    void filterEntriesBlankQuery() throws Exception {
        FindIterable<Document> iterable = mock(FindIterable.class);
        when(collection.find(any(Bson.class))).thenReturn(iterable);
        when(iterable.sort(any())).thenReturn(iterable);
        MongoCursor<Document> cursor = mock(MongoCursor.class);
        doReturn(cursor).when(iterable).iterator();
        when(cursor.hasNext()).thenReturn(false);

        List<UserMemoryEntry> result = store.filterEntries(TEST_USER, "");
        assertTrue(result.isEmpty());
    }

    // ==================== getEntriesByCategory ====================

    @Test
    @DisplayName("getEntriesByCategory — returns entries matching category")
    void getEntriesByCategory() throws Exception {
        Instant now = Instant.parse("2024-01-01T00:00:00Z");
        Document doc = createMemoryDoc(TEST_OID, "key1", "val1", Visibility.self, now);

        FindIterable<Document> iterable = mock(FindIterable.class);
        when(collection.find(any(Bson.class))).thenReturn(iterable);
        when(iterable.sort(any())).thenReturn(iterable);
        MongoCursor<Document> cursor = mock(MongoCursor.class);
        doReturn(cursor).when(iterable).iterator();
        when(cursor.hasNext()).thenReturn(true, false);
        when(cursor.next()).thenReturn(doc);

        List<UserMemoryEntry> result = store.getEntriesByCategory(TEST_USER, "preferences");
        assertEquals(1, result.size());
    }

    // ==================== getByKey ====================

    @Test
    @DisplayName("getByKey — returns entry when found")
    void getByKeyFound() throws Exception {
        Instant now = Instant.parse("2024-01-01T00:00:00Z");
        Document doc = createMemoryDoc(TEST_OID, "lang", "en", Visibility.global, now);
        FindIterable<Document> iterable = mock(FindIterable.class);
        when(collection.find(any(Bson.class))).thenReturn(iterable);
        when(iterable.first()).thenReturn(doc);

        Optional<UserMemoryEntry> result = store.getByKey(TEST_USER, "lang");
        assertTrue(result.isPresent());
        assertEquals("lang", result.get().key());
    }

    // ==================== getAllEntries ====================

    @Test
    @DisplayName("getAllEntries — returns all entries for user")
    void getAllEntries() throws Exception {
        FindIterable<Document> iterable = mock(FindIterable.class);
        when(collection.find(any(Bson.class))).thenReturn(iterable);
        when(iterable.sort(any())).thenReturn(iterable);
        MongoCursor<Document> cursor = mock(MongoCursor.class);
        doReturn(cursor).when(iterable).iterator();
        when(cursor.hasNext()).thenReturn(false);

        List<UserMemoryEntry> result = store.getAllEntries(TEST_USER);
        assertTrue(result.isEmpty());
    }

    // ==================== deleteAllForUser ====================

    @Test
    @DisplayName("deleteAllForUser — GDPR delete")
    void deleteAllForUser() throws Exception {
        DeleteResult deleteResult = mock(DeleteResult.class);
        when(deleteResult.getDeletedCount()).thenReturn(5L);
        when(collection.deleteMany(any(Bson.class))).thenReturn(deleteResult);

        store.deleteAllForUser(TEST_USER);
        verify(collection).deleteMany(any(Bson.class));
    }

    // ==================== countEntries ====================

    @Test
    @DisplayName("countEntries — returns count")
    void countEntries() throws Exception {
        when(collection.countDocuments(any(Bson.class))).thenReturn(42L);
        assertEquals(42L, store.countEntries(TEST_USER));
    }

    // ==================== deleteOlderThan ====================

    @Test
    @DisplayName("deleteOlderThan — retention cleanup excluding GDPR keys")
    void deleteOlderThan() throws Exception {
        DeleteResult deleteResult = mock(DeleteResult.class);
        when(deleteResult.getDeletedCount()).thenReturn(10L);
        when(collection.deleteMany(any(Bson.class))).thenReturn(deleteResult);

        long deleted = store.deleteOlderThan(30);
        assertEquals(10L, deleted);
    }

    // ==================== Helper ====================

    private Document createMemoryDoc(ObjectId oid, String key, Object value, Visibility vis, Instant timestamp) {
        return new Document("_id", oid)
                .append("userId", TEST_USER)
                .append("key", key)
                .append("value", value)
                .append("category", "preferences")
                .append("visibility", vis.name())
                .append("sourceAgentId", TEST_AGENT)
                .append("groupIds", List.of())
                .append("sourceConversationId", null)
                .append("conflicted", false)
                .append("accessCount", 0)
                .append("createdAt", timestamp.toString())
                .append("updatedAt", timestamp.toString());
    }
}
