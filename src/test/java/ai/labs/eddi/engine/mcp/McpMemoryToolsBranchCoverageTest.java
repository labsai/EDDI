/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.configs.properties.model.Property.Visibility;
import ai.labs.eddi.configs.properties.model.UserMemoryEntry;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.security.OwnershipValidator;
import io.quarkus.security.identity.SecurityIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;

@DisplayName("McpMemoryTools — Branch Coverage")
class McpMemoryToolsBranchCoverageTest {

    @Mock
    private IUserMemoryStore userMemoryStore;
    @Mock
    private IJsonSerialization jsonSerialization;
    @Mock
    private SecurityIdentity identity;
    @Mock
    private OwnershipValidator ownershipValidator;

    private McpMemoryTools tools;

    @BeforeEach
    void setUp() {
        openMocks(this);
        tools = new McpMemoryTools(userMemoryStore, jsonSerialization, identity, ownershipValidator, false);
    }

    // ─── listUserMemories ────────────────────────────────────────────────

    @Nested
    @DisplayName("listUserMemories")
    class ListUserMemories {

        @Test
        @DisplayName("null userId returns error")
        void nullUserId() {
            String result = tools.listUserMemories(null, null);
            assertTrue(result.contains("error"));
        }

        @Test
        @DisplayName("blank userId returns error")
        void blankUserId() {
            String result = tools.listUserMemories("  ", null);
            assertTrue(result.contains("error"));
        }

        @Test
        @DisplayName("success with default limit")
        void successDefaultLimit() throws Exception {
            var entries = List.of(mock(UserMemoryEntry.class));
            when(userMemoryStore.getAllEntries("user1")).thenReturn(entries);
            when(jsonSerialization.serialize(any())).thenReturn("{\"count\":1}");

            String result = tools.listUserMemories("user1", null);
            assertTrue(result.contains("count"));
        }

        @Test
        @DisplayName("success with custom limit — entries truncated")
        void successWithLimit() throws Exception {
            var entries = List.of(
                    mock(UserMemoryEntry.class),
                    mock(UserMemoryEntry.class),
                    mock(UserMemoryEntry.class));
            when(userMemoryStore.getAllEntries("user1")).thenReturn(new java.util.ArrayList<>(entries));
            when(jsonSerialization.serialize(any())).thenReturn("{\"count\":2}");

            String result = tools.listUserMemories("user1", 2);
            verify(jsonSerialization).serialize(any());
        }

        @Test
        @DisplayName("exception returns error json")
        void exceptionReturnsError() throws Exception {
            when(userMemoryStore.getAllEntries("user1")).thenThrow(new RuntimeException("db error"));

            String result = tools.listUserMemories("user1", null);
            assertTrue(result.contains("error"));
        }
    }

    // ─── getVisibleMemories ──────────────────────────────────────────────

    @Nested
    @DisplayName("getVisibleMemories")
    class GetVisibleMemories {

        @Test
        @DisplayName("null userId returns error")
        void nullUserId() {
            String result = tools.getVisibleMemories(null, "agent1", null, null, null);
            assertTrue(result.contains("error"));
        }

        @Test
        @DisplayName("null agentId returns error")
        void nullAgentId() {
            String result = tools.getVisibleMemories("user1", null, null, null, null);
            assertTrue(result.contains("error"));
        }

        @Test
        @DisplayName("blank agentId returns error")
        void blankAgentId() {
            String result = tools.getVisibleMemories("user1", "  ", null, null, null);
            assertTrue(result.contains("error"));
        }

        @Test
        @DisplayName("success with groupIds and custom order")
        void successWithGroupIds() throws Exception {
            when(userMemoryStore.getVisibleEntries(anyString(), anyString(), anyList(), anyString(), anyInt()))
                    .thenReturn(List.of());
            when(jsonSerialization.serialize(any())).thenReturn("{}");

            String result = tools.getVisibleMemories("user1", "agent1", "g1,g2", "most_accessed", 10);
            assertNotNull(result);
            verify(userMemoryStore).getVisibleEntries("user1", "agent1", List.of("g1", "g2"), "most_accessed", 10);
        }

        @Test
        @DisplayName("null groupIds defaults to empty list")
        void nullGroupIds() throws Exception {
            when(userMemoryStore.getVisibleEntries(anyString(), anyString(), anyList(), anyString(), anyInt()))
                    .thenReturn(List.of());
            when(jsonSerialization.serialize(any())).thenReturn("{}");

            tools.getVisibleMemories("user1", "agent1", null, null, null);
            verify(userMemoryStore).getVisibleEntries("user1", "agent1", List.of(), "most_recent", 50);
        }

        @Test
        @DisplayName("exception returns error")
        void exceptionReturnsError() throws Exception {
            when(userMemoryStore.getVisibleEntries(anyString(), anyString(), anyList(), anyString(), anyInt()))
                    .thenThrow(new RuntimeException("fail"));

            String result = tools.getVisibleMemories("user1", "agent1", null, null, null);
            assertTrue(result.contains("error"));
        }
    }

    // ─── searchUserMemories ──────────────────────────────────────────────

    @Nested
    @DisplayName("searchUserMemories")
    class SearchUserMemories {

        @Test
        @DisplayName("null userId returns error")
        void nullUserId() {
            assertTrue(tools.searchUserMemories(null, "q").contains("error"));
        }

        @Test
        @DisplayName("null query returns error")
        void nullQuery() {
            assertTrue(tools.searchUserMemories("user1", null).contains("error"));
        }

        @Test
        @DisplayName("blank query returns error")
        void blankQuery() {
            assertTrue(tools.searchUserMemories("user1", "  ").contains("error"));
        }

        @Test
        @DisplayName("success")
        void success() throws Exception {
            when(userMemoryStore.filterEntries("user1", "test")).thenReturn(List.of());
            when(jsonSerialization.serialize(any())).thenReturn("{}");

            String result = tools.searchUserMemories("user1", "test");
            assertNotNull(result);
        }

        @Test
        @DisplayName("exception returns error")
        void exception() throws Exception {
            when(userMemoryStore.filterEntries("user1", "q")).thenThrow(new RuntimeException("fail"));
            assertTrue(tools.searchUserMemories("user1", "q").contains("error"));
        }
    }

    // ─── getMemoryByKey ──────────────────────────────────────────────────

    @Nested
    @DisplayName("getMemoryByKey")
    class GetMemoryByKey {

        @Test
        @DisplayName("null userId returns error")
        void nullUserId() {
            assertTrue(tools.getMemoryByKey(null, "key").contains("error"));
        }

        @Test
        @DisplayName("null key returns error")
        void nullKey() {
            assertTrue(tools.getMemoryByKey("user1", null).contains("error"));
        }

        @Test
        @DisplayName("blank key returns error")
        void blankKey() {
            assertTrue(tools.getMemoryByKey("user1", "  ").contains("error"));
        }

        @Test
        @DisplayName("found entry returns serialized")
        void foundEntry() throws Exception {
            var entry = mock(UserMemoryEntry.class);
            when(userMemoryStore.getByKey("user1", "fav_color")).thenReturn(Optional.of(entry));
            when(jsonSerialization.serialize(entry)).thenReturn("{\"key\":\"fav_color\"}");

            String result = tools.getMemoryByKey("user1", "fav_color");
            assertTrue(result.contains("fav_color"));
        }

        @Test
        @DisplayName("not found returns error")
        void notFound() throws Exception {
            when(userMemoryStore.getByKey("user1", "missing")).thenReturn(Optional.empty());

            String result = tools.getMemoryByKey("user1", "missing");
            assertTrue(result.contains("error"));
        }

        @Test
        @DisplayName("exception returns error")
        void exception() throws Exception {
            when(userMemoryStore.getByKey(anyString(), anyString())).thenThrow(new RuntimeException("fail"));
            assertTrue(tools.getMemoryByKey("user1", "key").contains("error"));
        }
    }

    // ─── upsertUserMemory ────────────────────────────────────────────────

    @Nested
    @DisplayName("upsertUserMemory")
    class UpsertUserMemory {

        @Test
        @DisplayName("null userId returns error")
        void nullUserId() {
            assertTrue(tools.upsertUserMemory(null, "k", "v", "a", null, null).contains("error"));
        }

        @Test
        @DisplayName("null key returns error")
        void nullKey() {
            assertTrue(tools.upsertUserMemory("u", null, "v", "a", null, null).contains("error"));
        }

        @Test
        @DisplayName("null value returns error")
        void nullValue() {
            assertTrue(tools.upsertUserMemory("u", "k", null, "a", null, null).contains("error"));
        }

        @Test
        @DisplayName("null agentId returns error")
        void nullAgentId() {
            assertTrue(tools.upsertUserMemory("u", "k", "v", null, null, null).contains("error"));
        }

        @Test
        @DisplayName("blank agentId returns error")
        void blankAgentId() {
            assertTrue(tools.upsertUserMemory("u", "k", "v", "  ", null, null).contains("error"));
        }

        @Test
        @DisplayName("success with default visibility")
        void successDefault() throws Exception {
            when(userMemoryStore.upsert(any())).thenReturn("entry-id");
            when(jsonSerialization.serialize(any())).thenReturn("{\"status\":\"upserted\"}");

            String result = tools.upsertUserMemory("user1", "key1", "val1", "agent1", "fact", null);
            assertTrue(result.contains("upserted"));
        }

        @Test
        @DisplayName("success with explicit visibility")
        void successExplicitVisibility() throws Exception {
            when(userMemoryStore.upsert(any())).thenReturn("entry-id");
            when(jsonSerialization.serialize(any())).thenReturn("{\"status\":\"upserted\"}");

            String result = tools.upsertUserMemory("user1", "key1", "val1", "agent1", null, "global");
            assertTrue(result.contains("upserted"));
        }

        @Test
        @DisplayName("invalid visibility returns error")
        void invalidVisibility() {
            String result = tools.upsertUserMemory("u", "k", "v", "a", null, "INVALID");
            assertTrue(result.contains("error"));
        }

        @Test
        @DisplayName("store exception returns error")
        void storeException() throws Exception {
            when(userMemoryStore.upsert(any())).thenThrow(new RuntimeException("fail"));
            String result = tools.upsertUserMemory("u", "k", "v", "a", null, null);
            assertTrue(result.contains("error"));
        }
    }

    // ─── deleteUserMemory ────────────────────────────────────────────────

    @Nested
    @DisplayName("deleteUserMemory")
    class DeleteUserMemory {

        @Test
        @DisplayName("null entryId returns error")
        void nullEntryId() {
            assertTrue(tools.deleteUserMemory(null).contains("error"));
        }

        @Test
        @DisplayName("blank entryId returns error")
        void blankEntryId() {
            assertTrue(tools.deleteUserMemory("  ").contains("error"));
        }

        @Test
        @DisplayName("success")
        void success() throws Exception {
            when(jsonSerialization.serialize(any())).thenReturn("{\"status\":\"deleted\"}");
            String result = tools.deleteUserMemory("entry123");
            assertTrue(result.contains("deleted"));
            verify(userMemoryStore).deleteEntry("entry123");
        }

        @Test
        @DisplayName("exception returns error")
        void exception() throws Exception {
            doThrow(new RuntimeException("fail")).when(userMemoryStore).deleteEntry(anyString());
            assertTrue(tools.deleteUserMemory("entry123").contains("error"));
        }
    }

    // ─── deleteAllUserMemories ───────────────────────────────────────────

    @Nested
    @DisplayName("deleteAllUserMemories")
    class DeleteAllUserMemories {

        @Test
        @DisplayName("null userId returns error")
        void nullUserId() {
            assertTrue(tools.deleteAllUserMemories(null, "CONFIRM").contains("error"));
        }

        @Test
        @DisplayName("missing CONFIRM returns error")
        void missingConfirm() {
            assertTrue(tools.deleteAllUserMemories("user1", null).contains("error"));
        }

        @Test
        @DisplayName("wrong confirmation returns error")
        void wrongConfirm() {
            assertTrue(tools.deleteAllUserMemories("user1", "yes").contains("error"));
        }

        @Test
        @DisplayName("success with CONFIRM")
        void success() throws Exception {
            when(userMemoryStore.countEntries("user1")).thenReturn(5L);
            when(jsonSerialization.serialize(any())).thenReturn("{\"status\":\"deleted\"}");

            String result = tools.deleteAllUserMemories("user1", "CONFIRM");
            assertTrue(result.contains("deleted"));
            verify(userMemoryStore).deleteAllForUser("user1");
        }

        @Test
        @DisplayName("exception returns error")
        void exception() throws Exception {
            when(userMemoryStore.countEntries("user1")).thenThrow(new RuntimeException("fail"));
            assertTrue(tools.deleteAllUserMemories("user1", "CONFIRM").contains("error"));
        }
    }

    // ─── countUserMemories ───────────────────────────────────────────────

    @Nested
    @DisplayName("countUserMemories")
    class CountUserMemories {

        @Test
        @DisplayName("null userId returns error")
        void nullUserId() {
            assertTrue(tools.countUserMemories(null).contains("error"));
        }

        @Test
        @DisplayName("success")
        void success() throws Exception {
            when(userMemoryStore.countEntries("user1")).thenReturn(42L);
            when(jsonSerialization.serialize(any())).thenReturn("{\"count\":42}");

            String result = tools.countUserMemories("user1");
            assertTrue(result.contains("42"));
        }

        @Test
        @DisplayName("exception returns error")
        void exception() throws Exception {
            when(userMemoryStore.countEntries("user1")).thenThrow(new RuntimeException("fail"));
            assertTrue(tools.countUserMemories("user1").contains("error"));
        }
    }
}
