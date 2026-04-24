/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.configs.properties.model.UserMemoryEntry;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import io.quarkus.security.identity.SecurityIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class McpMemoryToolsTest {

    private McpMemoryTools tools;
    private IUserMemoryStore userMemoryStore;
    private IJsonSerialization jsonSerialization;

    @BeforeEach
    void setUp() {
        userMemoryStore = mock(IUserMemoryStore.class);
        jsonSerialization = mock(IJsonSerialization.class);
        var identity = mock(SecurityIdentity.class);
        // authEnabled=false so no role checks
        tools = new McpMemoryTools(userMemoryStore, jsonSerialization, identity, false);
    }

    // ==================== listUserMemories ====================

    @Test
    void listUserMemories_success() throws Exception {
        when(userMemoryStore.getAllEntries("user1")).thenReturn(List.of());
        when(jsonSerialization.serialize(any())).thenReturn("{\"count\":0}");
        var result = tools.listUserMemories("user1", null);
        assertEquals("{\"count\":0}", result);
    }

    @Test
    void listUserMemories_nullUserId() {
        var result = tools.listUserMemories(null, null);
        assertTrue(result.contains("userId is required"));
    }

    @Test
    void listUserMemories_blankUserId() {
        var result = tools.listUserMemories("  ", null);
        assertTrue(result.contains("userId is required"));
    }

    @Test
    void listUserMemories_withLimit() throws Exception {
        when(userMemoryStore.getAllEntries("user1")).thenReturn(List.of(
                mock(UserMemoryEntry.class), mock(UserMemoryEntry.class), mock(UserMemoryEntry.class)));
        when(jsonSerialization.serialize(any())).thenReturn("ok");
        tools.listUserMemories("user1", 2);
        verify(jsonSerialization).serialize(any());
    }

    @Test
    void listUserMemories_exception() throws Exception {
        when(userMemoryStore.getAllEntries("user1")).thenThrow(new RuntimeException("DB error"));
        var result = tools.listUserMemories("user1", null);
        assertTrue(result.contains("Failed to list memories"));
    }

    // ==================== getVisibleMemories ====================

    @Test
    void getVisibleMemories_success() throws Exception {
        when(userMemoryStore.getVisibleEntries(eq("user1"), eq("agent1"), anyList(), eq("most_recent"), eq(50)))
                .thenReturn(List.of());
        when(jsonSerialization.serialize(any())).thenReturn("ok");
        var result = tools.getVisibleMemories("user1", "agent1", null, null, null);
        assertNotNull(result);
    }

    @Test
    void getVisibleMemories_nullUserId() {
        var result = tools.getVisibleMemories(null, "agent1", null, null, null);
        assertTrue(result.contains("userId is required"));
    }

    @Test
    void getVisibleMemories_nullAgentId() {
        var result = tools.getVisibleMemories("user1", null, null, null, null);
        assertTrue(result.contains("agentId is required"));
    }

    @Test
    void getVisibleMemories_withGroupIds() throws Exception {
        when(userMemoryStore.getVisibleEntries(eq("user1"), eq("agent1"), eq(List.of("g1", "g2")), anyString(), anyInt()))
                .thenReturn(List.of());
        when(jsonSerialization.serialize(any())).thenReturn("ok");
        tools.getVisibleMemories("user1", "agent1", "g1,g2", "most_accessed", 10);
        verify(userMemoryStore).getVisibleEntries("user1", "agent1", List.of("g1", "g2"), "most_accessed", 10);
    }

    // ==================== searchUserMemories ====================

    @Test
    void searchUserMemories_success() throws Exception {
        when(userMemoryStore.filterEntries("user1", "preferences")).thenReturn(List.of());
        when(jsonSerialization.serialize(any())).thenReturn("ok");
        tools.searchUserMemories("user1", "preferences");
        verify(userMemoryStore).filterEntries("user1", "preferences");
    }

    @Test
    void searchUserMemories_nullQuery() {
        var result = tools.searchUserMemories("user1", null);
        assertTrue(result.contains("query is required"));
    }

    // ==================== getMemoryByKey ====================

    @Test
    void getMemoryByKey_found() throws Exception {
        var entry = mock(UserMemoryEntry.class);
        when(userMemoryStore.getByKey("user1", "language")).thenReturn(Optional.of(entry));
        when(jsonSerialization.serialize(entry)).thenReturn("{\"key\":\"language\"}");
        var result = tools.getMemoryByKey("user1", "language");
        assertEquals("{\"key\":\"language\"}", result);
    }

    @Test
    void getMemoryByKey_notFound() throws Exception {
        when(userMemoryStore.getByKey("user1", "missing")).thenReturn(Optional.empty());
        var result = tools.getMemoryByKey("user1", "missing");
        assertTrue(result.contains("No memory found"));
    }

    @Test
    void getMemoryByKey_nullKey() {
        var result = tools.getMemoryByKey("user1", null);
        assertTrue(result.contains("key is required"));
    }

    // ==================== upsertUserMemory ====================

    @Test
    void upsertUserMemory_success() throws Exception {
        when(userMemoryStore.upsert(any())).thenReturn("new-id");
        when(jsonSerialization.serialize(any())).thenReturn("{\"status\":\"upserted\"}");
        var result = tools.upsertUserMemory("user1", "lang", "en", "agent1", "preference", "self");
        assertNotNull(result);
        verify(userMemoryStore).upsert(any());
    }

    @Test
    void upsertUserMemory_nullUserId() {
        var result = tools.upsertUserMemory(null, "key", "val", "agent", null, null);
        assertTrue(result.contains("userId is required"));
    }

    @Test
    void upsertUserMemory_nullKey() {
        var result = tools.upsertUserMemory("user1", null, "val", "agent", null, null);
        assertTrue(result.contains("key is required"));
    }

    @Test
    void upsertUserMemory_nullValue() {
        var result = tools.upsertUserMemory("user1", "key", null, "agent", null, null);
        assertTrue(result.contains("value is required"));
    }

    @Test
    void upsertUserMemory_nullAgentId() {
        var result = tools.upsertUserMemory("user1", "key", "val", null, null, null);
        assertTrue(result.contains("agentId is required"));
    }

    @Test
    void upsertUserMemory_invalidVisibility() {
        var result = tools.upsertUserMemory("user1", "key", "val", "agent", null, "invalid");
        assertTrue(result.contains("Invalid visibility"));
    }

    // ==================== deleteUserMemory ====================

    @Test
    void deleteUserMemory_success() throws Exception {
        when(jsonSerialization.serialize(any())).thenReturn("{\"status\":\"deleted\"}");
        var result = tools.deleteUserMemory("entry-1");
        verify(userMemoryStore).deleteEntry("entry-1");
        assertNotNull(result);
    }

    @Test
    void deleteUserMemory_nullId() {
        var result = tools.deleteUserMemory(null);
        assertTrue(result.contains("entryId is required"));
    }

    // ==================== deleteAllUserMemories ====================

    @Test
    void deleteAllUserMemories_success() throws Exception {
        when(userMemoryStore.countEntries("user1")).thenReturn(5L);
        when(jsonSerialization.serialize(any())).thenReturn("ok");
        tools.deleteAllUserMemories("user1", "CONFIRM");
        verify(userMemoryStore).deleteAllForUser("user1");
    }

    @Test
    void deleteAllUserMemories_noConfirmation() {
        var result = tools.deleteAllUserMemories("user1", "yes");
        assertTrue(result.contains("CONFIRM"));
    }

    @Test
    void deleteAllUserMemories_nullUserId() {
        var result = tools.deleteAllUserMemories(null, "CONFIRM");
        assertTrue(result.contains("userId is required"));
    }

    // ==================== countUserMemories ====================

    @Test
    void countUserMemories_success() throws Exception {
        when(userMemoryStore.countEntries("user1")).thenReturn(42L);
        when(jsonSerialization.serialize(any())).thenReturn("{\"count\":42}");
        var result = tools.countUserMemories("user1");
        assertEquals("{\"count\":42}", result);
    }

    @Test
    void countUserMemories_nullUserId() {
        var result = tools.countUserMemories(null);
        assertTrue(result.contains("userId is required"));
    }

    @Test
    void countUserMemories_exception() throws Exception {
        when(userMemoryStore.countEntries("user1")).thenThrow(new RuntimeException("timeout"));
        var result = tools.countUserMemories("user1");
        assertTrue(result.contains("Failed to count memories"));
    }
}
