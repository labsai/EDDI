/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.configs.properties.model.Property.Visibility;
import ai.labs.eddi.configs.properties.model.UserMemoryEntry;
import ai.labs.eddi.modules.llm.impl.SummarizationService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link DreamService}.
 */
class DreamServiceTest {

    private IUserMemoryStore store;
    private SummarizationService summarizationService;
    private DreamService dreamService;
    private AgentConfiguration.DreamConfig dreamConfig;

    @BeforeEach
    void setUp() {
        store = mock(IUserMemoryStore.class);
        summarizationService = mock(SummarizationService.class);
        dreamService = new DreamService(store, summarizationService, new SimpleMeterRegistry());
        dreamService.initMetrics();

        dreamConfig = new AgentConfiguration.DreamConfig();
        dreamConfig.setEnabled(true);
        dreamConfig.setPruneStaleAfterDays(30);
        dreamConfig.setDetectContradictions(true);
        dreamConfig.setSummarizeInteractions(false);
    }

    // === Existing tests (updated for new constructor) ===

    @Test
    void process_shouldPruneStaleEntries() throws Exception {
        Instant stale = Instant.now().minus(Duration.ofDays(60));
        Instant fresh = Instant.now().minus(Duration.ofDays(5));

        var entries = List.of(
                new UserMemoryEntry("1", "user-1", "old_fact", "value1", "fact", Visibility.self, "agent-1", List.of(), "conv-1", false, 0, stale,
                        stale),
                new UserMemoryEntry("2", "user-1", "fresh_fact", "value2", "fact", Visibility.self, "agent-1", List.of(), "conv-1", false, 0, fresh,
                        fresh));
        when(store.getAllEntries("user-1")).thenReturn(entries);

        var result = dreamService.process("user-1", dreamConfig);

        assertTrue(result.isSuccess());
        assertEquals(1, result.entriesPruned());
        verify(store).deleteEntry("1");
        verify(store, never()).deleteEntry("2");
    }

    @Test
    void process_shouldSkipPruningWhenDisabled() throws Exception {
        dreamConfig.setPruneStaleAfterDays(0);
        when(store.getAllEntries("user-1")).thenReturn(List.of());

        var result = dreamService.process("user-1", dreamConfig);

        assertTrue(result.isSuccess());
        assertEquals(0, result.entriesPruned());
        verify(store, never()).deleteEntry(any());
    }

    @Test
    void process_shouldDetectContradictions() throws Exception {
        Instant now = Instant.now();
        var entries = List.of(
                new UserMemoryEntry("1", "user-1", "language", "English", "preference", Visibility.self, "agent-1", List.of(), "conv-1", false, 0,
                        now, now),
                new UserMemoryEntry("2", "user-1", "language", "German", "preference", Visibility.self, "agent-2", List.of(), "conv-2", false, 0, now,
                        now));
        when(store.getAllEntries("user-1")).thenReturn(entries);

        dreamConfig.setPruneStaleAfterDays(0);

        var result = dreamService.process("user-1", dreamConfig);

        assertTrue(result.isSuccess());
        assertEquals(1, result.contradictionsFound());
    }

    @Test
    void process_shouldSkipContradictionDetectionWhenDisabled() throws Exception {
        dreamConfig.setPruneStaleAfterDays(0);
        dreamConfig.setDetectContradictions(false);
        when(store.getAllEntries("user-1")).thenReturn(List.of());

        var result = dreamService.process("user-1", dreamConfig);

        assertTrue(result.isSuccess());
        assertEquals(0, result.contradictionsFound());
    }

    @Test
    void process_shouldRecordMetrics() throws Exception {
        when(store.getAllEntries("user-1")).thenReturn(List.of());
        dreamConfig.setPruneStaleAfterDays(0);
        dreamConfig.setDetectContradictions(false);

        var result = dreamService.process("user-1", dreamConfig);

        assertTrue(result.isSuccess());
        assertTrue(result.durationMs() >= 0);
    }

    @Test
    void process_shouldHandleStoreException() throws Exception {
        when(store.getAllEntries("user-1")).thenThrow(new ai.labs.eddi.datastore.IResourceStore.ResourceStoreException("DB down"));

        var result = dreamService.process("user-1", dreamConfig);

        assertFalse(result.isSuccess());
        assertNotNull(result.error());
    }

    @Test
    void process_shouldLoadEntriesOnlyOnce() throws Exception {
        Instant now = Instant.now();
        var entries = List.of(
                new UserMemoryEntry("1", "user-1", "fact1", "value1", "fact", Visibility.self, "agent-1", List.of(), "conv-1", false, 0, now, now));
        when(store.getAllEntries("user-1")).thenReturn(entries);

        dreamConfig.setPruneStaleAfterDays(30);
        dreamConfig.setDetectContradictions(true);

        dreamService.process("user-1", dreamConfig);

        // getAllEntries should be called exactly once (shared across both operations)
        verify(store, times(1)).getAllEntries("user-1");
    }

    @Test
    void process_shouldReloadAfterPruning() throws Exception {
        Instant stale = Instant.now().minus(Duration.ofDays(60));
        var entries = List.of(new UserMemoryEntry("1", "user-1", "old_fact", "value1", "fact", Visibility.self, "agent-1", List.of(), "conv-1", false,
                0, stale, stale));
        when(store.getAllEntries("user-1")).thenReturn(entries).thenReturn(List.of());

        dreamConfig.setPruneStaleAfterDays(30);
        dreamConfig.setDetectContradictions(true);

        var result = dreamService.process("user-1", dreamConfig);

        assertTrue(result.isSuccess());
        assertEquals(1, result.entriesPruned());
        // After pruning, entries are reloaded for contradiction detection
        verify(store, times(2)).getAllEntries("user-1");
    }

    // === Summarization tests ===

    private List<UserMemoryEntry> makeEntries(int count, String category, String agentId) {
        Instant now = Instant.now();
        var entries = new java.util.ArrayList<UserMemoryEntry>();
        for (int i = 0; i < count; i++) {
            entries.add(new UserMemoryEntry("id-" + i, "user-1", "key-" + i, "value-" + i,
                    category, Visibility.self, agentId, List.of(), "conv-1", false, 0, now, now));
        }
        return entries;
    }

    private void enableSummarization() {
        dreamConfig.setPruneStaleAfterDays(0);
        dreamConfig.setDetectContradictions(false);
        dreamConfig.setSummarizeInteractions(true);
        dreamConfig.setSummarizeMinEntries(5);
        dreamConfig.setSummarizeTargetEntries(2);
        dreamConfig.setMaxSummarizationCalls(10);
    }

    @Test
    void summarize_belowThreshold_noOp() throws Exception {
        enableSummarization();
        var entries = makeEntries(3, "fact", "agent-1"); // below threshold of 5
        when(store.getAllEntries("user-1")).thenReturn(entries);

        var result = dreamService.process("user-1", dreamConfig);

        assertTrue(result.isSuccess());
        assertEquals(0, result.entriesSummarized());
        verifyNoInteractions(summarizationService);
    }

    @Test
    void summarize_aboveThreshold_consolidates() throws Exception {
        enableSummarization();
        var entries = makeEntries(6, "fact", "agent-1");
        when(store.getAllEntries("user-1")).thenReturn(entries);

        String llmResponse = "[{\"key\": \"summary-1\", \"value\": \"combined fact 1\"}, " +
                "{\"key\": \"summary-2\", \"value\": \"combined fact 2\"}]";
        when(summarizationService.summarize(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(llmResponse);

        var result = dreamService.process("user-1", dreamConfig);

        assertTrue(result.isSuccess());
        assertEquals(4, result.entriesSummarized()); // 6 originals - 2 consolidated = 4 reduced
        verify(store, times(2)).upsert(any(UserMemoryEntry.class));
        verify(store, times(6)).deleteEntry(anyString());
    }

    @Test
    void summarize_llmReturnsEmpty_preservesEntries() throws Exception {
        enableSummarization();
        var entries = makeEntries(6, "fact", "agent-1");
        when(store.getAllEntries("user-1")).thenReturn(entries);
        when(summarizationService.summarize(anyString(), anyString(), anyString(), anyString()))
                .thenReturn("");

        var result = dreamService.process("user-1", dreamConfig);

        assertTrue(result.isSuccess());
        assertEquals(0, result.entriesSummarized());
        verify(store, never()).upsert(any(UserMemoryEntry.class));
        verify(store, never()).deleteEntry(anyString());
    }

    @Test
    void summarize_llmReturnsGarbage_preservesEntries() throws Exception {
        enableSummarization();
        var entries = makeEntries(6, "fact", "agent-1");
        when(store.getAllEntries("user-1")).thenReturn(entries);
        when(summarizationService.summarize(anyString(), anyString(), anyString(), anyString()))
                .thenReturn("I can't do that, sorry!");

        var result = dreamService.process("user-1", dreamConfig);

        assertTrue(result.isSuccess());
        assertEquals(0, result.entriesSummarized());
        verify(store, never()).deleteEntry(anyString());
    }

    @Test
    void summarize_llmReturnsMarkdownFences_parsesCorrectly() throws Exception {
        enableSummarization();
        var entries = makeEntries(6, "fact", "agent-1");
        when(store.getAllEntries("user-1")).thenReturn(entries);

        String llmResponse = "```json\n[{\"key\": \"s1\", \"value\": \"v1\"}, {\"key\": \"s2\", \"value\": \"v2\"}]\n```";
        when(summarizationService.summarize(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(llmResponse);

        var result = dreamService.process("user-1", dreamConfig);

        assertTrue(result.isSuccess());
        assertEquals(4, result.entriesSummarized());
    }

    @Test
    void summarize_llmReturnsMoreThanOriginals_skips() throws Exception {
        enableSummarization();
        var entries = makeEntries(5, "fact", "agent-1");
        when(store.getAllEntries("user-1")).thenReturn(entries);

        // LLM returns 6 entries for 5 originals — should be skipped
        String llmResponse = "[" +
                "{\"key\":\"a\",\"value\":\"1\"},{\"key\":\"b\",\"value\":\"2\"}," +
                "{\"key\":\"c\",\"value\":\"3\"},{\"key\":\"d\",\"value\":\"4\"}," +
                "{\"key\":\"e\",\"value\":\"5\"},{\"key\":\"f\",\"value\":\"6\"}]";
        when(summarizationService.summarize(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(llmResponse);

        var result = dreamService.process("user-1", dreamConfig);

        assertTrue(result.isSuccess());
        assertEquals(0, result.entriesSummarized());
        verify(store, never()).deleteEntry(anyString());
    }

    @Test
    void summarize_insertFails_preservesEntries() throws Exception {
        enableSummarization();
        var entries = makeEntries(6, "fact", "agent-1");
        when(store.getAllEntries("user-1")).thenReturn(entries);

        String llmResponse = "[{\"key\": \"s1\", \"value\": \"v1\"}]";
        when(summarizationService.summarize(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(llmResponse);
        doThrow(new RuntimeException("DB write failed")).when(store).upsert(any(UserMemoryEntry.class));

        var result = dreamService.process("user-1", dreamConfig);

        assertTrue(result.isSuccess());
        assertEquals(0, result.entriesSummarized());
        verify(store, never()).deleteEntry(anyString()); // originals preserved
    }

    @Test
    void summarize_callLimitReached_stopsEarly() throws Exception {
        enableSummarization();
        dreamConfig.setMaxSummarizationCalls(1);
        dreamConfig.setSummarizeGroupBy("category");

        Instant now = Instant.now();
        // Two categories with 5+ entries each
        var entries = new java.util.ArrayList<UserMemoryEntry>();
        for (int i = 0; i < 6; i++) {
            entries.add(new UserMemoryEntry("f-" + i, "user-1", "fk-" + i, "fv-" + i,
                    "fact", Visibility.self, "agent-1", List.of(), "conv-1", false, 0, now, now));
        }
        for (int i = 0; i < 6; i++) {
            entries.add(new UserMemoryEntry("p-" + i, "user-1", "pk-" + i, "pv-" + i,
                    "preference", Visibility.self, "agent-1", List.of(), "conv-1", false, 0, now, now));
        }
        when(store.getAllEntries("user-1")).thenReturn(entries);

        String llmResponse = "[{\"key\": \"s1\", \"value\": \"v1\"}]";
        when(summarizationService.summarize(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(llmResponse);

        var result = dreamService.process("user-1", dreamConfig);

        assertTrue(result.isSuccess());
        // Only 1 LLM call should have been made (limit=1)
        verify(summarizationService, times(1)).summarize(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void summarize_groupByAll_singleGroup() throws Exception {
        enableSummarization();
        dreamConfig.setSummarizeGroupBy("all");

        Instant now = Instant.now();
        var entries = new java.util.ArrayList<UserMemoryEntry>();
        for (int i = 0; i < 3; i++) {
            entries.add(new UserMemoryEntry("f-" + i, "user-1", "fk-" + i, "fv",
                    "fact", Visibility.self, "agent-1", List.of(), "conv-1", false, 0, now, now));
        }
        for (int i = 0; i < 3; i++) {
            entries.add(new UserMemoryEntry("p-" + i, "user-1", "pk-" + i, "pv",
                    "preference", Visibility.self, "agent-1", List.of(), "conv-1", false, 0, now, now));
        }
        when(store.getAllEntries("user-1")).thenReturn(entries);

        String llmResponse = "[{\"key\": \"s1\", \"value\": \"v1\"}, {\"key\": \"s2\", \"value\": \"v2\"}]";
        when(summarizationService.summarize(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(llmResponse);

        var result = dreamService.process("user-1", dreamConfig);

        assertTrue(result.isSuccess());
        // All 6 entries in one group → 2 consolidated → 4 reduced
        assertEquals(4, result.entriesSummarized());
        verify(summarizationService, times(1)).summarize(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void summarize_preserveAgentProvenance_subGroups() throws Exception {
        enableSummarization();
        dreamConfig.setPreserveAgentProvenance(true);
        dreamConfig.setSummarizeMinEntries(3);

        Instant now = Instant.now();
        var entries = new java.util.ArrayList<UserMemoryEntry>();
        // 3 entries from agent-1 (fact)
        for (int i = 0; i < 3; i++) {
            entries.add(new UserMemoryEntry("a1-" + i, "user-1", "k-a1-" + i, "v",
                    "fact", Visibility.self, "agent-1", List.of(), "conv-1", false, 0, now, now));
        }
        // 3 entries from agent-2 (fact)
        for (int i = 0; i < 3; i++) {
            entries.add(new UserMemoryEntry("a2-" + i, "user-1", "k-a2-" + i, "v",
                    "fact", Visibility.self, "agent-2", List.of(), "conv-1", false, 0, now, now));
        }
        when(store.getAllEntries("user-1")).thenReturn(entries);

        String llmResponse = "[{\"key\": \"s\", \"value\": \"v\"}]";
        when(summarizationService.summarize(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(llmResponse);

        var result = dreamService.process("user-1", dreamConfig);

        assertTrue(result.isSuccess());
        // Two separate groups, each 3→1 = 2 reduced per group = 4 total
        assertEquals(4, result.entriesSummarized());
        verify(summarizationService, times(2)).summarize(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void summarize_customPrompt_passedToService() throws Exception {
        enableSummarization();
        String customPrompt = "Custom consolidation instructions.";
        dreamConfig.setSummarizationPrompt(customPrompt);

        var entries = makeEntries(6, "fact", "agent-1");
        when(store.getAllEntries("user-1")).thenReturn(entries);
        when(summarizationService.summarize(anyString(), eq(customPrompt), anyString(), anyString()))
                .thenReturn("[{\"key\": \"s\", \"value\": \"v\"}]");

        dreamService.process("user-1", dreamConfig);

        verify(summarizationService).summarize(anyString(), eq(customPrompt), anyString(), anyString());
    }

    @Test
    void summarize_mostRestrictiveVisibility_applied() throws Exception {
        enableSummarization();

        Instant now = Instant.now();
        var entries = new java.util.ArrayList<UserMemoryEntry>();
        // Mix of self and global visibility
        for (int i = 0; i < 3; i++) {
            entries.add(new UserMemoryEntry("s-" + i, "user-1", "sk-" + i, "sv",
                    "fact", Visibility.self, "agent-1", List.of(), "conv-1", false, 0, now, now));
        }
        for (int i = 0; i < 3; i++) {
            entries.add(new UserMemoryEntry("g-" + i, "user-1", "gk-" + i, "gv",
                    "fact", Visibility.global, "agent-1", List.of(), "conv-1", false, 0, now, now));
        }
        when(store.getAllEntries("user-1")).thenReturn(entries);

        String llmResponse = "[{\"key\": \"s1\", \"value\": \"v1\"}]";
        when(summarizationService.summarize(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(llmResponse);

        dreamService.process("user-1", dreamConfig);

        // Verify the upserted entry has Visibility.self (most restrictive)
        var captor = org.mockito.ArgumentCaptor.forClass(UserMemoryEntry.class);
        verify(store).upsert(captor.capture());
        assertEquals(Visibility.self, captor.getValue().visibility());
    }

    // === parseConsolidatedEntries unit tests ===

    @Test
    void parseConsolidatedEntries_validJson() {
        var result = dreamService.parseConsolidatedEntries(
                "[{\"key\": \"k1\", \"value\": \"v1\"}]");
        assertEquals(1, result.size());
        assertEquals("k1", result.getFirst().key());
    }

    @Test
    void parseConsolidatedEntries_nullInput() {
        assertEquals(0, dreamService.parseConsolidatedEntries(null).size());
    }

    @Test
    void parseConsolidatedEntries_blankInput() {
        assertEquals(0, dreamService.parseConsolidatedEntries("  ").size());
    }

    @Test
    void parseConsolidatedEntries_markdownFences() {
        var result = dreamService.parseConsolidatedEntries(
                "```json\n[{\"key\":\"k\",\"value\":\"v\"}]\n```");
        assertEquals(1, result.size());
    }

    @Test
    void mostRestrictiveVisibility_selfWins() {
        Instant now = Instant.now();
        var entries = List.of(
                new UserMemoryEntry("1", "u", "k", "v", "fact", Visibility.global, "a", List.of(), "c", false, 0, now, now),
                new UserMemoryEntry("2", "u", "k", "v", "fact", Visibility.self, "a", List.of(), "c", false, 0, now, now));
        assertEquals(Visibility.self, DreamService.mostRestrictiveVisibility(entries));
    }

    @Test
    void mostRestrictiveVisibility_allGlobal() {
        Instant now = Instant.now();
        var entries = List.of(
                new UserMemoryEntry("1", "u", "k", "v", "fact", Visibility.global, "a", List.of(), "c", false, 0, now, now));
        assertEquals(Visibility.global, DreamService.mostRestrictiveVisibility(entries));
    }

    @Test
    void mostRestrictiveVisibility_groupOnly() {
        Instant now = Instant.now();
        var entries = List.of(
                new UserMemoryEntry("1", "u", "k", "v", "fact", Visibility.global, "a", List.of(), "c", false, 0, now, now),
                new UserMemoryEntry("2", "u", "k", "v", "fact", Visibility.group, "a", List.of(), "c", false, 0, now, now));
        assertEquals(Visibility.group, DreamService.mostRestrictiveVisibility(entries));
    }

    @Test
    void prune_nullUpdatedAt_skipped() throws Exception {
        Instant stale = Instant.now().minus(Duration.ofDays(60));
        var entries = List.of(
                new UserMemoryEntry("1", "user-1", "no_date", "v", "fact", Visibility.self, "agent-1", List.of(), "conv-1", false, 0, stale, null),
                new UserMemoryEntry("2", "user-1", "has_date", "v", "fact", Visibility.self, "agent-1", List.of(), "conv-1", false, 0, stale, stale));
        when(store.getAllEntries("user-1")).thenReturn(entries);

        dreamConfig.setDetectContradictions(false);
        var result = dreamService.process("user-1", dreamConfig);

        assertTrue(result.isSuccess());
        assertEquals(1, result.entriesPruned()); // only "2" pruned, "1" skipped (null updatedAt)
        verify(store).deleteEntry("2");
        verify(store, never()).deleteEntry("1");
    }

    @Test
    void prune_deleteFails_continues() throws Exception {
        Instant stale = Instant.now().minus(Duration.ofDays(60));
        var entries = List.of(
                new UserMemoryEntry("1", "user-1", "k1", "v", "fact", Visibility.self, "agent-1", List.of(), "conv-1", false, 0, stale, stale),
                new UserMemoryEntry("2", "user-1", "k2", "v", "fact", Visibility.self, "agent-1", List.of(), "conv-1", false, 0, stale, stale));
        when(store.getAllEntries("user-1")).thenReturn(entries);
        doThrow(new RuntimeException("DB error")).when(store).deleteEntry("1");

        dreamConfig.setDetectContradictions(false);
        var result = dreamService.process("user-1", dreamConfig);

        assertTrue(result.isSuccess());
        assertEquals(1, result.entriesPruned()); // "1" failed, "2" succeeded
        verify(store).deleteEntry("1");
        verify(store).deleteEntry("2");
    }

    @Test
    void contradictions_sameKeyAndValue_noDuplicate() throws Exception {
        Instant now = Instant.now();
        var entries = List.of(
                new UserMemoryEntry("1", "user-1", "language", "English", "preference", Visibility.self, "agent-1", List.of(), "conv-1", false, 0,
                        now, now),
                new UserMemoryEntry("2", "user-1", "language", "English", "preference", Visibility.self, "agent-2", List.of(), "conv-2", false, 0,
                        now, now));
        when(store.getAllEntries("user-1")).thenReturn(entries);
        dreamConfig.setPruneStaleAfterDays(0);

        var result = dreamService.process("user-1", dreamConfig);

        assertTrue(result.isSuccess());
        assertEquals(0, result.contradictionsFound()); // same value → not a contradiction
    }

    @Test
    void summarize_llmReturnsTooMany_cappedToTarget() throws Exception {
        enableSummarization();
        dreamConfig.setSummarizeTargetEntries(2);
        var entries = makeEntries(8, "fact", "agent-1");
        when(store.getAllEntries("user-1")).thenReturn(entries);

        // LLM returns 4 (< 8 originals, but > 2 target) → capped to 2
        String llmResponse = "[{\"key\":\"a\",\"value\":\"1\"},{\"key\":\"b\",\"value\":\"2\"}," +
                "{\"key\":\"c\",\"value\":\"3\"},{\"key\":\"d\",\"value\":\"4\"}]";
        when(summarizationService.summarize(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(llmResponse);

        var result = dreamService.process("user-1", dreamConfig);

        assertTrue(result.isSuccess());
        assertEquals(6, result.entriesSummarized()); // 8 - 2 (capped) = 6
        verify(store, times(2)).upsert(any(UserMemoryEntry.class)); // only 2 inserted
        verify(store, times(8)).deleteEntry(anyString());
    }

    @Test
    void summarize_deletePartiallyFails_logsAndContinues() throws Exception {
        enableSummarization();
        var entries = makeEntries(6, "fact", "agent-1");
        when(store.getAllEntries("user-1")).thenReturn(entries);

        String llmResponse = "[{\"key\": \"s1\", \"value\": \"v1\"}]";
        when(summarizationService.summarize(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(llmResponse);
        // First 3 deletes succeed, last 3 fail
        doNothing().doNothing().doNothing()
                .doThrow(new RuntimeException("DB")).doThrow(new RuntimeException("DB")).doThrow(new RuntimeException("DB"))
                .when(store).deleteEntry(anyString());

        var result = dreamService.process("user-1", dreamConfig);

        assertTrue(result.isSuccess());
        // Consolidated count reflects the intent (6 - 1 = 5), even if some deletes
        // failed
        assertEquals(5, result.entriesSummarized());
        verify(store, times(6)).deleteEntry(anyString());
    }

    @Test
    void summarize_llmThrows_preservesEntriesAndContinues() throws Exception {
        enableSummarization();
        var entries = makeEntries(6, "fact", "agent-1");
        when(store.getAllEntries("user-1")).thenReturn(entries);
        when(summarizationService.summarize(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("LLM provider down"));

        var result = dreamService.process("user-1", dreamConfig);

        assertTrue(result.isSuccess()); // dream cycle succeeds even though LLM failed
        assertEquals(0, result.entriesSummarized());
        verify(store, never()).upsert(any(UserMemoryEntry.class));
        verify(store, never()).deleteEntry(anyString());
    }

    @Test
    void summarize_afterPruning_reloadsEntries() throws Exception {
        enableSummarization();
        dreamConfig.setPruneStaleAfterDays(30);

        Instant stale = Instant.now().minus(Duration.ofDays(60));
        Instant fresh = Instant.now();
        // First call returns stale + fresh entries; second call (after prune) returns
        // only fresh
        var initialEntries = new java.util.ArrayList<UserMemoryEntry>();
        initialEntries.add(new UserMemoryEntry("stale-1", "user-1", "old", "v", "fact", Visibility.self, "agent-1", List.of(), "conv-1", false, 0,
                stale, stale));
        for (int i = 0; i < 6; i++) {
            initialEntries.add(new UserMemoryEntry("fresh-" + i, "user-1", "k-" + i, "v-" + i, "fact", Visibility.self, "agent-1", List.of(),
                    "conv-1", false, 0, fresh, fresh));
        }

        var afterPruneEntries = initialEntries.subList(1, 7); // only the 6 fresh ones
        when(store.getAllEntries("user-1")).thenReturn(initialEntries).thenReturn(afterPruneEntries);

        String llmResponse = "[{\"key\": \"s\", \"value\": \"v\"}]";
        when(summarizationService.summarize(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(llmResponse);

        var result = dreamService.process("user-1", dreamConfig);

        assertTrue(result.isSuccess());
        assertEquals(1, result.entriesPruned());
        assertEquals(5, result.entriesSummarized()); // 6 fresh - 1 consolidated = 5
        // 1st call = initial load, 2nd call = reload for summarization (pruned > 0)
        verify(store, times(2)).getAllEntries("user-1");
    }

    @Test
    void parseConsolidatedEntries_missingKeyField_filtered() {
        // Entry with "name" instead of "key" should be filtered out
        var result = dreamService.parseConsolidatedEntries(
                "[{\"name\": \"k1\", \"value\": \"v1\"}, {\"key\": \"k2\", \"value\": \"v2\"}]");
        assertEquals(1, result.size());
        assertEquals("k2", result.getFirst().key());
    }

    @Test
    void escapeJson_handlesControlCharacters() {
        // Verify Jackson's encoder handles chars beyond basic \n\r\t
        String result = DreamService.escapeJson("tab\there\nnewline\rcarriage\bback\\slash\"quote");
        assertFalse(result.contains("\t"));
        assertFalse(result.contains("\n"));
        assertFalse(result.contains("\r"));
        assertFalse(result.contains("\b"));
        assertTrue(result.contains("\\t"));
        assertTrue(result.contains("\\n"));
    }

    @Test
    void escapeJson_nullReturnsEmpty() {
        assertEquals("", DreamService.escapeJson(null));
    }
}
