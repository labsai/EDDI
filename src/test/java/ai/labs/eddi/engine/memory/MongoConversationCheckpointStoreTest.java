/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory;

import ai.labs.eddi.engine.memory.model.MemoryCheckpoint;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.result.DeleteResult;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collections;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SuppressWarnings("unchecked")
class MongoConversationCheckpointStoreTest {

    private MongoCollection<MemoryCheckpoint> collection;
    private MongoConversationCheckpointStore store;

    @BeforeEach
    void setUp() {
        MongoDatabase database = mock(MongoDatabase.class);
        collection = mock(MongoCollection.class);
        when(database.getCollection("conversation_checkpoints", MemoryCheckpoint.class)).thenReturn(collection);
        store = new MongoConversationCheckpointStore(database);
    }

    // ==================== create ====================

    @Test
    @DisplayName("create — inserts checkpoint")
    void create() {
        MemoryCheckpoint checkpoint = new MemoryCheckpoint(
                "ckpt-1", "conv-1", null, 0, Collections.emptyMap(), Instant.now(), "test", "TestClass");
        store.create(checkpoint);
        verify(collection).insertOne(checkpoint);
    }

    // ==================== findByConversationId ====================

    @Test
    @DisplayName("findByConversationId — returns checkpoints sorted and limited")
    void findByConversationId() {
        MemoryCheckpoint checkpoint = new MemoryCheckpoint(
                "ckpt-1", "conv-1", null, 0, Collections.emptyMap(), Instant.now(), "test", "TestClass");

        FindIterable<MemoryCheckpoint> iterable = mock(FindIterable.class);
        when(collection.find(any(Bson.class))).thenReturn(iterable);
        when(iterable.sort(any())).thenReturn(iterable);
        when(iterable.limit(anyInt())).thenReturn(iterable);

        doAnswer(inv -> {
            Consumer<MemoryCheckpoint> consumer = inv.getArgument(0);
            consumer.accept(checkpoint);
            return null;
        }).when(iterable).forEach(any(Consumer.class));

        var result = store.findByConversationId("conv-1", 10);
        assertEquals(1, result.size());
    }

    // ==================== findById ====================

    @Test
    @DisplayName("findById — returns checkpoint when found")
    void findByIdFound() {
        MemoryCheckpoint checkpoint = new MemoryCheckpoint(
                "ckpt-1", "conv-1", null, 0, Collections.emptyMap(), Instant.now(), "test", "TestClass");

        FindIterable<MemoryCheckpoint> iterable = mock(FindIterable.class);
        when(collection.find(any(Bson.class))).thenReturn(iterable);
        when(iterable.first()).thenReturn(checkpoint);

        MemoryCheckpoint result = store.findById("ckpt-1");
        assertNotNull(result);
        assertEquals("ckpt-1", result.checkpointId());
    }

    @Test
    @DisplayName("findById — returns null when not found")
    void findByIdNotFound() {
        FindIterable<MemoryCheckpoint> iterable = mock(FindIterable.class);
        when(collection.find(any(Bson.class))).thenReturn(iterable);
        when(iterable.first()).thenReturn(null);

        assertNull(store.findById("missing"));
    }

    // ==================== deleteById ====================

    @Test
    @DisplayName("deleteById — deletes checkpoint")
    void deleteById() {
        when(collection.deleteOne(any(Bson.class))).thenReturn(mock(DeleteResult.class));
        store.deleteById("ckpt-1");
        verify(collection).deleteOne(any(Bson.class));
    }

    // ==================== pruneOldest ====================

    @Test
    @DisplayName("pruneOldest — returns 0 when no checkpoints to keep")
    void pruneOldestEmpty() {
        FindIterable<MemoryCheckpoint> iterable = mock(FindIterable.class);
        when(collection.find(any(Bson.class))).thenReturn(iterable);
        when(iterable.sort(any())).thenReturn(iterable);
        when(iterable.limit(anyInt())).thenReturn(iterable);
        // No items to keep
        doAnswer(inv -> null).when(iterable).forEach(any(Consumer.class));

        int deleted = store.pruneOldest("conv-1", 5);
        assertEquals(0, deleted);
    }

    @Test
    @DisplayName("pruneOldest — deletes old checkpoints beyond keepCount")
    void pruneOldestWithDeletion() {
        MemoryCheckpoint kept = new MemoryCheckpoint(
                "ckpt-1", "conv-1", null, 0, Collections.emptyMap(), Instant.now(), "test", "TestClass");

        FindIterable<MemoryCheckpoint> iterable = mock(FindIterable.class);
        when(collection.find(any(Bson.class))).thenReturn(iterable);
        when(iterable.sort(any())).thenReturn(iterable);
        when(iterable.limit(anyInt())).thenReturn(iterable);

        doAnswer(inv -> {
            Consumer<MemoryCheckpoint> consumer = inv.getArgument(0);
            consumer.accept(kept);
            return null;
        }).when(iterable).forEach(any(Consumer.class));

        DeleteResult deleteResult = mock(DeleteResult.class);
        when(deleteResult.getDeletedCount()).thenReturn(3L);
        when(collection.deleteMany(any(Bson.class))).thenReturn(deleteResult);

        int deleted = store.pruneOldest("conv-1", 1);
        assertEquals(3, deleted);
    }

    // ==================== deleteByConversationId ====================

    @Test
    @DisplayName("deleteByConversationId — returns deleted count")
    void deleteByConversationId() {
        DeleteResult deleteResult = mock(DeleteResult.class);
        when(deleteResult.getDeletedCount()).thenReturn(5L);
        when(collection.deleteMany(any(Bson.class))).thenReturn(deleteResult);

        assertEquals(5L, store.deleteByConversationId("conv-1"));
    }
}
