/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools;

import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.configs.properties.model.Property.Visibility;
import ai.labs.eddi.configs.properties.model.UserMemoryEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Regression tests for the agent-scoping of the memory tool (G1) and for the
 * {@code onCapReached} capacity policy (G4).
 */
class UserMemoryToolScopingTest {

    private static final String USER = "user-1";
    private static final String AGENT_A = "agent-a";
    private static final String AGENT_B = "agent-b";

    private IUserMemoryStore store;
    private AgentConfiguration.UserMemoryConfig config;

    @BeforeEach
    void setUp() {
        store = mock(IUserMemoryStore.class);
        config = new AgentConfiguration.UserMemoryConfig();
    }

    private UserMemoryTool toolFor(String agentId, List<String> groupIds) {
        return new UserMemoryTool(store, USER, agentId, "conv-1", groupIds, config);
    }

    private UserMemoryEntry entry(String id, String key, Visibility visibility, String sourceAgentId, List<String> groupIds, Instant updatedAt) {
        return new UserMemoryEntry(id, USER, key, "value-of-" + key, "fact", visibility, sourceAgentId, groupIds, "conv-x", false, 0, updatedAt,
                updatedAt);
    }

    // ==================== G1 — cross-agent read ====================

    @Test
    @DisplayName("G1 — searchMemory must not surface another agent's visibility:self entries")
    void searchMemoryHidesForeignSelfEntries() throws Exception {
        var foreignPrivate = entry("1", "salary", Visibility.self, AGENT_A, List.of(), Instant.now());
        var sharedGlobal = entry("2", "language", Visibility.global, AGENT_A, List.of(), Instant.now());
        when(store.filterEntries(USER, "a")).thenReturn(List.of(foreignPrivate, sharedGlobal));

        String result = toolFor(AGENT_B, List.of()).searchMemory("a");

        assertFalse(result.contains("salary"), "agent B must not see agent A's visibility:self memory; got: " + result);
        assertTrue(result.contains("language"), "global memories stay visible; got: " + result);
    }

    @Test
    @DisplayName("G1 — searchMemory surfaces a group entry only when the group actually overlaps")
    void searchMemoryRespectsGroupOverlap() throws Exception {
        var groupEntry = entry("1", "project", Visibility.group, AGENT_A, List.of("group-1"), Instant.now());
        when(store.filterEntries(USER, "p")).thenReturn(List.of(groupEntry));

        assertTrue(toolFor(AGENT_B, List.of("group-1")).searchMemory("p").contains("project"));
        assertFalse(toolFor(AGENT_B, List.of("group-2")).searchMemory("p").contains("project"));
    }

    // ==================== G1 — cross-agent delete ====================

    @Test
    @DisplayName("G1 — forgetFact must not delete another agent's visibility:self entry")
    void forgetFactRefusesForeignSelfEntry() throws Exception {
        var foreignPrivate = entry("foreign-1", "salary", Visibility.self, AGENT_A, List.of(), Instant.now());
        when(store.getByKey(USER, "salary")).thenReturn(Optional.of(foreignPrivate));
        when(store.filterEntries(USER, "salary")).thenReturn(List.of(foreignPrivate));

        String result = toolFor(AGENT_B, List.of()).forgetFact("salary");

        assertTrue(result.contains("No memory with key 'salary' found"), "expected a not-found answer, got: " + result);
        verify(store, never()).deleteEntry(any());
    }

    @Test
    @DisplayName("G1 — forgetFact still deletes this agent's own entry when getByKey returned a foreign one")
    void forgetFactFallsBackToOwnEntry() throws Exception {
        var foreignPrivate = entry("foreign-1", "salary", Visibility.self, AGENT_A, List.of(), Instant.now());
        var ownEntry = entry("own-1", "salary", Visibility.self, AGENT_B, List.of(), Instant.now());
        when(store.getByKey(USER, "salary")).thenReturn(Optional.of(foreignPrivate));
        when(store.filterEntries(USER, "salary")).thenReturn(List.of(foreignPrivate, ownEntry));

        String result = toolFor(AGENT_B, List.of()).forgetFact("salary");

        assertTrue(result.contains("✅ Forgotten"), result);
        verify(store).deleteEntry("own-1");
        verify(store, never()).deleteEntry("foreign-1");
    }

    // ==================== G4 — onCapReached ====================

    @Test
    @DisplayName("G4 — evict_oldest actually deletes the oldest own entry when the cap is reached")
    void evictOldestDeletesOldestOwnEntry() throws Exception {
        config.setMaxEntriesPerUser(2);
        config.setOnCapReached("evict_oldest");

        var oldest = entry("old-1", "ancient", Visibility.self, AGENT_B, List.of(), Instant.parse("2020-01-01T00:00:00Z"));
        var newer = entry("new-1", "recent", Visibility.self, AGENT_B, List.of(), Instant.parse("2026-01-01T00:00:00Z"));
        when(store.countEntries(USER)).thenReturn(2L);
        when(store.getAllEntries(USER)).thenReturn(List.of(newer, oldest));
        when(store.upsert(any())).thenReturn("new-id");

        String result = toolFor(AGENT_B, List.of()).rememberFact("fresh_fact", "value", "fact", "self");

        assertTrue(result.contains("✅ Remembered"), result);
        verify(store).deleteEntry("old-1");
        verify(store, never()).deleteEntry("new-1");
        verify(store).upsert(any(UserMemoryEntry.class));
    }

    @Test
    @DisplayName("G4 — evict_oldest returns an actionable error when only other agents' entries exist")
    void evictOldestReportsWhenNothingCanBeEvicted() throws Exception {
        config.setMaxEntriesPerUser(1);
        config.setOnCapReached("evict_oldest");

        var foreign = entry("foreign-1", "theirs", Visibility.self, AGENT_A, List.of(), Instant.parse("2020-01-01T00:00:00Z"));
        when(store.countEntries(USER)).thenReturn(1L);
        when(store.getAllEntries(USER)).thenReturn(List.of(foreign));

        String result = toolFor(AGENT_B, List.of()).rememberFact("fresh_fact", "value", "fact", "self");

        assertTrue(result.contains("Memory capacity reached"), result);
        assertTrue(result.contains("maxEntriesPerUser"), "the error must tell the caller what to do; got: " + result);
        verify(store, never()).deleteEntry(any());
        verify(store, never()).upsert(any());
    }

    // ==================== I8 — team-owned lessons ====================

    @Test
    @DisplayName("I8 — eviction can never touch a team-owned retro lesson, even when it is the oldest entry the store returns")
    void evictOldestNeverTouchesTeamOwnedLessons() throws Exception {
        config.setMaxEntriesPerUser(2);
        config.setOnCapReached("evict_oldest");

        // The lesson lives under the synthetic team owner with the fixed "retro"
        // source. Structurally it can never surface from getAllEntries(USER) —
        // but even if a store bug returned it, the sourceAgentId filter is a
        // second independent wall. This test breaches the first wall on purpose.
        var teamLesson = new UserMemoryEntry("lesson-1", IUserMemoryStore.TEAM_OWNER_PREFIX + "g1", "retro:abc",
                "Cap debate rounds", "context", Visibility.group, "retro", List.of("g1"), "gc-1", false, 0,
                Instant.parse("2019-01-01T00:00:00Z"), Instant.parse("2019-01-01T00:00:00Z"));
        var own = entry("own-1", "mine", Visibility.self, AGENT_B, List.of(), Instant.parse("2024-01-01T00:00:00Z"));
        when(store.countEntries(USER)).thenReturn(2L);
        when(store.getAllEntries(USER)).thenReturn(List.of(teamLesson, own));
        when(store.upsert(any())).thenReturn("new-id");

        String result = toolFor(AGENT_B, List.of("g1")).rememberFact("fresh_fact", "value", "fact", "self");

        assertTrue(result.contains("✅ Remembered"), result);
        verify(store, never()).deleteEntry("lesson-1");
        verify(store).deleteEntry("own-1");
    }

    @Test
    @DisplayName("I8 — a personal visibility:self entry never surfaces to another user's tool, groups shared or not")
    void personalEntriesNeverCrossUsers() throws Exception {
        // The tool always queries by ITS user's id — a shared group cannot widen
        // the personal scope. The store is queried for USER only; nothing about
        // user-2 is ever requested, and a self entry of another AGENT under the
        // same user stays hidden too (the existing G1 wall).
        var otherAgentsPrivate = entry("p-1", "salary", Visibility.self, AGENT_A, List.of("g1"), Instant.now());
        when(store.filterEntries(USER, "salary")).thenReturn(List.of(otherAgentsPrivate));

        String result = toolFor(AGENT_B, List.of("g1")).searchMemory("salary");

        assertFalse(result.contains("value-of-salary"), result);
        verify(store, never()).filterEntries(eq("user-2"), any());
        verify(store, never()).getAllEntries("user-2");
    }

    @Test
    @DisplayName("G4 — updating an existing own key at the cap neither evicts nor is refused")
    void updatingExistingKeyAtCapIsAllowed() throws Exception {
        config.setMaxEntriesPerUser(1);
        config.setOnCapReached("evict_oldest");

        var existing = entry("own-1", "known_key", Visibility.self, AGENT_B, List.of(), Instant.parse("2024-01-01T00:00:00Z"));
        when(store.countEntries(USER)).thenReturn(1L);
        when(store.getAllEntries(USER)).thenReturn(List.of(existing));
        when(store.upsert(any())).thenReturn("own-1");

        String result = toolFor(AGENT_B, List.of()).rememberFact("known_key", "new value", "fact", "self");

        assertTrue(result.contains("✅ Remembered"), result);
        verify(store, never()).deleteEntry(any());
    }
}
