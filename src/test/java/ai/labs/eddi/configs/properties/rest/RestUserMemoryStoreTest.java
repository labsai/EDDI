/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.properties.rest;

import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.configs.properties.model.Property.Visibility;
import ai.labs.eddi.configs.properties.model.UserMemoryEntry;
import ai.labs.eddi.datastore.IResourceStore;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RestUserMemoryStore}.
 */
class RestUserMemoryStoreTest {

    private IUserMemoryStore store;
    private RestUserMemoryStore rest;

    @BeforeEach
    void setUp() {
        store = mock(IUserMemoryStore.class);
        rest = new RestUserMemoryStore(store);
    }

    // === getAllMemories ===

    @Test
    void getAllMemories_shouldReturnEntries() throws Exception {
        var entries = List.of(entry("1", "fav_color", "blue"));
        when(store.getAllEntries("user-1")).thenReturn(entries);

        var result = rest.getAllMemories("user-1");

        assertEquals(1, result.size());
        assertEquals("fav_color", result.get(0).key());
    }

    @Test
    void getAllMemories_shouldThrowOnStoreError() throws Exception {
        when(store.getAllEntries("user-1")).thenThrow(new IResourceStore.ResourceStoreException("fail"));

        assertThrows(InternalServerErrorException.class, () -> rest.getAllMemories("user-1"));
    }

    // === getVisibleMemories ===

    @Test
    void getVisibleMemories_shouldDelegateWithDefaults() throws Exception {
        when(store.getVisibleEntries("user-1", "agent-1", List.of(), "most_recent", 50)).thenReturn(List.of(entry("1", "k", "v")));

        var result = rest.getVisibleMemories("user-1", "agent-1", null, "most_recent", 50);

        assertEquals(1, result.size());
    }

    // === searchMemories ===

    @Test
    void searchMemories_shouldDelegateToFilter() throws Exception {
        when(store.filterEntries("user-1", "color")).thenReturn(List.of(entry("1", "fav_color", "blue")));

        var result = rest.searchMemories("user-1", "color");

        assertEquals(1, result.size());
    }

    // === getMemoriesByCategory ===

    @Test
    void getMemoriesByCategory_shouldFilterByCategory() throws Exception {
        when(store.getEntriesByCategory("user-1", "preference")).thenReturn(List.of(entry("1", "lang", "en")));

        var result = rest.getMemoriesByCategory("user-1", "preference");

        assertEquals(1, result.size());
    }

    // === getMemoryByKey ===

    @Test
    void getMemoryByKey_shouldReturnEntry() throws Exception {
        when(store.getByKey("user-1", "name")).thenReturn(Optional.of(entry("1", "name", "Alice")));

        Response response = rest.getMemoryByKey("user-1", "name");

        assertEquals(200, response.getStatus());
    }

    @Test
    void getMemoryByKey_shouldThrow404WhenNotFound() throws Exception {
        when(store.getByKey("user-1", "missing")).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class, () -> rest.getMemoryByKey("user-1", "missing"));
    }

    // === upsertMemory ===

    @Test
    void upsertMemory_shouldReturnId() throws Exception {
        var entry = entry("1", "key", "val");
        when(store.upsert(entry)).thenReturn("entry-id");

        Response response = rest.upsertMemory(entry);

        assertEquals(200, response.getStatus());
        @SuppressWarnings("unchecked")
        var body = (Map<String, Object>) response.getEntity();
        assertEquals("entry-id", body.get("id"));
    }

    @Test
    void upsertMemory_shouldRejectNullEntry() {
        Response response = rest.upsertMemory(null);
        assertEquals(400, response.getStatus());
    }

    @Test
    void upsertMemory_shouldRejectBlankUserId() {
        var entry = new UserMemoryEntry(null, "", "key", "val", "fact", Visibility.self, "agent-1", List.of(), "conv-1", false, 0, Instant.now(),
                Instant.now());
        Response response = rest.upsertMemory(entry);
        assertEquals(400, response.getStatus());
    }

    @Test
    void upsertMemory_shouldRejectBlankKey() {
        var entry = new UserMemoryEntry(null, "user-1", "", "val", "fact", Visibility.self, "agent-1", List.of(), "conv-1", false, 0, Instant.now(),
                Instant.now());
        Response response = rest.upsertMemory(entry);
        assertEquals(400, response.getStatus());
    }

    @Test
    void upsertMemory_shouldRejectKeyTooLong() {
        String longKey = "a".repeat(256);
        var entry = new UserMemoryEntry(null, "user-1", longKey, "val", "fact", Visibility.self, "agent-1", List.of(), "conv-1", false, 0,
                Instant.now(), Instant.now());
        Response response = rest.upsertMemory(entry);
        assertEquals(400, response.getStatus());
    }

    // === deleteMemory ===

    @Test
    void deleteMemory_shouldReturn204() throws Exception {
        doNothing().when(store).deleteEntry("entry-1");

        Response response = rest.deleteMemory("entry-1");

        assertEquals(204, response.getStatus());
    }

    // === deleteAllForUser ===

    @Test
    void deleteAllForUser_shouldReturn204() throws Exception {
        doNothing().when(store).deleteAllForUser("user-1");

        Response response = rest.deleteAllForUser("user-1");

        assertEquals(204, response.getStatus());
    }

    // === countMemories ===

    @Test
    void countMemories_shouldReturnCount() throws Exception {
        when(store.countEntries("user-1")).thenReturn(42L);

        Response response = rest.countMemories("user-1");

        assertEquals(200, response.getStatus());
        @SuppressWarnings("unchecked")
        var body = (Map<String, Object>) response.getEntity();
        assertEquals(42L, body.get("count"));
        assertEquals("user-1", body.get("userId"));
    }

    // === Helper ===

    private UserMemoryEntry entry(String id, String key, Object value) {
        return new UserMemoryEntry(id, "user-1", key, value, "fact", Visibility.self, "agent-1", List.of(), "conv-1", false, 0, Instant.now(),
                Instant.now());
    }
}
