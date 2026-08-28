/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.configs.properties.model.Property.Visibility;
import ai.labs.eddi.configs.properties.model.UserMemoryEntry;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.modules.llm.impl.SummarizationService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import java.net.UnknownHostException;
import java.net.SocketTimeoutException;
import java.net.ConnectException;
import com.fasterxml.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link DreamService}.
 */
class DreamServiceTest {

    private IUserMemoryStore store;
    private IAgentStore agentStore;
    private SummarizationService summarizationService;
    private DreamService dreamService;
    private AgentConfiguration.DreamConfig dreamConfig;

    @BeforeEach
    void setUp() {
        store = mock(IUserMemoryStore.class);
        agentStore = mock(IAgentStore.class);
        summarizationService = mock(SummarizationService.class);
        dreamService = new DreamService(store, agentStore, summarizationService, new SimpleMeterRegistry(),
                new ObjectMapper());
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

        var result = dreamService.process("user-1", "agent-1", dreamConfig);

        assertTrue(result.isSuccess());
        assertEquals(1, result.entriesPruned());
        verify(store).deleteEntry("1");
        verify(store, never()).deleteEntry("2");
    }

    @Test
    void process_shouldSkipPruningWhenDisabled() throws Exception {
        dreamConfig.setPruneStaleAfterDays(0);
        when(store.getAllEntries("user-1")).thenReturn(List.of());

        var result = dreamService.process("user-1", "agent-1", dreamConfig);

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
        dreamConfig.setCrossAgentMaintenance(true); // the conflicting pair spans two agents

        var result = dreamService.process("user-1", "agent-1", dreamConfig);

        assertTrue(result.isSuccess());
        assertEquals(1, result.contradictionsFound());
    }

    @Test
    void process_shouldSkipContradictionDetectionWhenDisabled() throws Exception {
        dreamConfig.setPruneStaleAfterDays(0);
        dreamConfig.setDetectContradictions(false);
        when(store.getAllEntries("user-1")).thenReturn(List.of());

        var result = dreamService.process("user-1", "agent-1", dreamConfig);

        assertTrue(result.isSuccess());
        assertEquals(0, result.contradictionsFound());
    }

    @Test
    void process_shouldRecordMetrics() throws Exception {
        when(store.getAllEntries("user-1")).thenReturn(List.of());
        dreamConfig.setPruneStaleAfterDays(0);
        dreamConfig.setDetectContradictions(false);

        var result = dreamService.process("user-1", "agent-1", dreamConfig);

        assertTrue(result.isSuccess());
        assertTrue(result.durationMs() >= 0);
    }

    @Test
    void process_shouldHandleStoreException() throws Exception {
        when(store.getAllEntries("user-1")).thenThrow(new IResourceStore.ResourceStoreException("DB down"));

        var result = dreamService.process("user-1", "agent-1", dreamConfig);

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

        dreamService.process("user-1", "agent-1", dreamConfig);

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

        var result = dreamService.process("user-1", "agent-1", dreamConfig);

        assertTrue(result.isSuccess());
        assertEquals(1, result.entriesPruned());
        // After pruning, entries are reloaded for contradiction detection
        verify(store, times(2)).getAllEntries("user-1");
    }

    // === Summarization tests ===

    private List<UserMemoryEntry> makeEntries(int count, String category, String agentId) {
        Instant now = Instant.now();
        var entries = new ArrayList<UserMemoryEntry>();
        for (int i = 0; i < count; i++) {
            entries.add(new UserMemoryEntry("id-" + i, "user-1", "key-" + i, "value-" + i,
                    category, Visibility.self, agentId, List.of(), "conv-1", false, 0, now, now));
        }
        return entries;
    }

    /**
     * Note: deliberately does NOT touch {@code maxSummarizationCalls}. That legacy
     * ceiling only applies to configurations that declare it, so leaving it unset
     * here mirrors a stored config that never mentions the field.
     */
    private void enableSummarization() {
        dreamConfig.setPruneStaleAfterDays(0);
        dreamConfig.setDetectContradictions(false);
        dreamConfig.setSummarizeInteractions(true);
        dreamConfig.setSummarizeMinEntries(5);
        dreamConfig.setSummarizeTargetEntries(2);
        dreamConfig.setMaxCostPerRun(0.50);
    }

    /**
     * Helper: wraps an LLM response string into a SummarizationResult with zero
     * token usage.
     */
    private SummarizationService.SummarizationResult llmResult(String response) {
        return new SummarizationService.SummarizationResult(response, 0, 0);
    }

    /** Helper: wraps an LLM response string with specific token counts. */
    private SummarizationService.SummarizationResult llmResult(String response, int inputTokens, int outputTokens) {
        return new SummarizationService.SummarizationResult(response, inputTokens, outputTokens);
    }

    @Test
    void summarize_belowThreshold_noOp() throws Exception {
        enableSummarization();
        var entries = makeEntries(3, "fact", "agent-1"); // below threshold of 5
        when(store.getAllEntries("user-1")).thenReturn(entries);

        var result = dreamService.process("user-1", "agent-1", dreamConfig);

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
        when(summarizationService.summarizeWithUsage(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(llmResult(llmResponse));

        var result = dreamService.process("user-1", "agent-1", dreamConfig);

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
        when(summarizationService.summarizeWithUsage(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(llmResult(""));

        var result = dreamService.process("user-1", "agent-1", dreamConfig);

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
        when(summarizationService.summarizeWithUsage(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(llmResult("I can't do that, sorry!"));

        var result = dreamService.process("user-1", "agent-1", dreamConfig);

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
        when(summarizationService.summarizeWithUsage(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(llmResult(llmResponse));

        var result = dreamService.process("user-1", "agent-1", dreamConfig);

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
        when(summarizationService.summarizeWithUsage(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(llmResult(llmResponse));

        var result = dreamService.process("user-1", "agent-1", dreamConfig);

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
        when(summarizationService.summarizeWithUsage(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(llmResult(llmResponse));
        doThrow(new RuntimeException("DB write failed")).when(store).upsert(any(UserMemoryEntry.class));

        var result = dreamService.process("user-1", "agent-1", dreamConfig);

        assertTrue(result.isSuccess());
        assertEquals(0, result.entriesSummarized());
        verify(store, never()).deleteEntry(anyString()); // originals preserved
    }

    /**
     * Config-compat: {@code maxSummarizationCalls} is deprecated in favour of the
     * dollar budget, but a stored agent config that <em>sets</em> it asked for a
     * hard call ceiling. Dropping it silently turns "at most 1 consolidation call"
     * into "as many calls as $1.00 buys" — at $0.00015 a call, hundreds. An
     * explicitly configured count must still stop the run.
     */
    @Test
    void summarize_legacyCallCount_stillCapsWhenExplicitlyConfigured() throws Exception {
        enableSummarization();
        dreamConfig.setMaxSummarizationCalls(1); // deprecated, but explicitly configured
        dreamConfig.setMaxCostPerRun(1.00); // generous dollar budget — the count is what must bite
        dreamConfig.setSummarizeGroupBy("category");

        Instant now = Instant.now();
        // Two categories with 5+ entries each
        var entries = new ArrayList<UserMemoryEntry>();
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
        when(summarizationService.summarizeWithUsage(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(llmResult(llmResponse, 10, 5)); // ~$0.00015 per call

        var result = dreamService.process("user-1", "agent-1", dreamConfig);

        assertTrue(result.isSuccess());
        // Exactly one group is consolidated — the second is stopped by the count
        verify(summarizationService, times(1)).summarizeWithUsage(anyString(), anyString(), anyString(), anyString(), any());
        assertEquals(5, result.entriesSummarized()); // 6 - 1, one group only
    }

    /**
     * The other half of the same contract: the field's <em>default</em> value is
     * not a ceiling. A config that never mentions {@code maxSummarizationCalls} is
     * bounded by {@code maxCostPerRun} alone, so a cycle with more groups than the
     * default 10 runs them all.
     */
    @Test
    void summarize_legacyCallCount_unsetNeverCaps() throws Exception {
        enableSummarization(); // never calls setMaxSummarizationCalls
        dreamConfig.setMaxCostPerRun(1.00);
        dreamConfig.setSummarizeGroupBy("category");

        Instant now = Instant.now();
        var entries = new ArrayList<UserMemoryEntry>();
        for (int category = 0; category < 12; category++) { // 12 groups > default ceiling of 10
            for (int i = 0; i < 6; i++) {
                entries.add(new UserMemoryEntry("c" + category + "-" + i, "user-1", "k" + category + "-" + i, "v",
                        "cat-" + category, Visibility.self, "agent-1", List.of(), "conv-1", false, 0, now, now));
            }
        }
        when(store.getAllEntries("user-1")).thenReturn(entries);
        when(summarizationService.summarizeWithUsage(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(llmResult("[{\"key\": \"s1\", \"value\": \"v1\"}]", 10, 5));

        var result = dreamService.process("user-1", "agent-1", dreamConfig);

        assertTrue(result.isSuccess());
        verify(summarizationService, times(12)).summarizeWithUsage(anyString(), anyString(), anyString(), anyString(), any());
        assertEquals(60, result.entriesSummarized()); // 12 groups x (6 - 1)
    }

    /**
     * Finding I1: Dream has no parent LLM task to inherit credentials from, so the
     * agent's {@code dream.parameters} block must reach the summarizer — otherwise
     * it authenticates with nothing and every cycle fails.
     */
    @Test
    void summarize_passesConfiguredModelParametersToSummarizer() throws Exception {
        enableSummarization();
        var parameters = Map.of("apiKey", "${vault:dream-key}", "baseUrl", "https://llm.example/v1");
        dreamConfig.setParameters(parameters);

        when(store.getAllEntries("user-1")).thenReturn(makeEntries(6, "fact", "agent-1"));
        when(summarizationService.summarizeWithUsage(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(llmResult("[{\"key\": \"s\", \"value\": \"v\"}]"));

        dreamService.process("user-1", "agent-1", dreamConfig);

        verify(summarizationService).summarizeWithUsage(anyString(), anyString(),
                eq(dreamConfig.getLlmProvider()), eq(dreamConfig.getLlmModel()), eq(parameters));
    }

    @Test
    void summarize_groupByAll_singleGroup() throws Exception {
        enableSummarization();
        dreamConfig.setSummarizeGroupBy("all");

        Instant now = Instant.now();
        var entries = new ArrayList<UserMemoryEntry>();
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
        when(summarizationService.summarizeWithUsage(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(llmResult(llmResponse));

        var result = dreamService.process("user-1", "agent-1", dreamConfig);

        assertTrue(result.isSuccess());
        // All 6 entries in one group → 2 consolidated → 4 reduced
        assertEquals(4, result.entriesSummarized());
        verify(summarizationService, times(1)).summarizeWithUsage(anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void summarize_preserveAgentProvenance_subGroups() throws Exception {
        enableSummarization();
        dreamConfig.setPreserveAgentProvenance(true);
        dreamConfig.setSummarizeMinEntries(3);
        dreamConfig.setCrossAgentMaintenance(true); // sub-grouping is only observable across agents

        Instant now = Instant.now();
        var entries = new ArrayList<UserMemoryEntry>();
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
        when(summarizationService.summarizeWithUsage(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(llmResult(llmResponse));

        var result = dreamService.process("user-1", "agent-1", dreamConfig);

        assertTrue(result.isSuccess());
        // Two separate groups, each 3→1 = 2 reduced per group = 4 total
        assertEquals(4, result.entriesSummarized());
        verify(summarizationService, times(2)).summarizeWithUsage(anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void summarize_customPrompt_passedToService() throws Exception {
        enableSummarization();
        String customPrompt = "Custom consolidation instructions.";
        dreamConfig.setSummarizationPrompt(customPrompt);

        var entries = makeEntries(6, "fact", "agent-1");
        when(store.getAllEntries("user-1")).thenReturn(entries);
        when(summarizationService.summarizeWithUsage(anyString(), eq(customPrompt), anyString(), anyString(), any()))
                .thenReturn(llmResult("[{\"key\": \"s\", \"value\": \"v\"}]"));

        dreamService.process("user-1", "agent-1", dreamConfig);

        verify(summarizationService).summarizeWithUsage(anyString(), eq(customPrompt), anyString(), anyString(), any());
    }

    @Test
    void summarize_mostRestrictiveVisibility_applied() throws Exception {
        enableSummarization();

        Instant now = Instant.now();
        var entries = new ArrayList<UserMemoryEntry>();
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
        when(summarizationService.summarizeWithUsage(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(llmResult(llmResponse));

        dreamService.process("user-1", "agent-1", dreamConfig);

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
        var result = dreamService.process("user-1", "agent-1", dreamConfig);

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
        var result = dreamService.process("user-1", "agent-1", dreamConfig);

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
        dreamConfig.setCrossAgentMaintenance(true); // both entries must be in scope for this to mean anything

        var result = dreamService.process("user-1", "agent-1", dreamConfig);

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
        when(summarizationService.summarizeWithUsage(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(llmResult(llmResponse));

        var result = dreamService.process("user-1", "agent-1", dreamConfig);

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
        when(summarizationService.summarizeWithUsage(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(llmResult(llmResponse));
        // First 3 deletes succeed, last 3 fail
        doNothing().doNothing().doNothing()
                .doThrow(new RuntimeException("DB")).doThrow(new RuntimeException("DB")).doThrow(new RuntimeException("DB"))
                .when(store).deleteEntry(anyString());

        var result = dreamService.process("user-1", "agent-1", dreamConfig);

        assertTrue(result.isSuccess());
        // Metric tracks actual reduction: 3 successful deletes - 1 consolidated = 2
        assertEquals(2, result.entriesSummarized());
        verify(store, times(6)).deleteEntry(anyString());
    }

    /**
     * Finding I1: an LLM failure used to be a swallowed WARN, so a Dream cycle that
     * could never consolidate anything still reported success — the same defect F13
     * fixed for the conversation summarizer. The failure must now surface on the
     * result so the schedule fire is marked FAILED (and retries/dead-letters).
     */
    @Test
    void summarize_llmThrows_preservesEntriesAndFailsTheCycle() throws Exception {
        enableSummarization();
        var entries = makeEntries(6, "fact", "agent-1");
        when(store.getAllEntries("user-1")).thenReturn(entries);
        when(summarizationService.summarizeWithUsage(anyString(), anyString(), anyString(), anyString(), any()))
                .thenThrow(new RuntimeException("401 Unauthorized"));

        var result = dreamService.process("user-1", "agent-1", dreamConfig);

        assertFalse(result.isSuccess());
        assertNotNull(result.error());
        assertTrue(result.error().contains("401 Unauthorized"), "cause must be reported, got: " + result.error());
        assertEquals(0, result.entriesSummarized());
        verify(store, never()).upsert(any(UserMemoryEntry.class));
        verify(store, never()).deleteEntry(anyString());
    }

    /**
     * A <em>permanent</em> LLM failure (bad credentials, wrong endpoint, unknown
     * model) would repeat for every remaining group — the phase aborts rather than
     * burning the budget group by group. Transient failures are handled the
     * opposite way, see
     * {@link #summarize_transientLlmFailure_skipsGroupAndKeepsTheCycleSuccessful}.
     */
    @Test
    void summarize_llmThrows_abortsRemainingGroups() throws Exception {
        enableSummarization();
        dreamConfig.setSummarizeGroupBy("category");

        Instant now = Instant.now();
        var entries = new ArrayList<UserMemoryEntry>();
        for (int i = 0; i < 6; i++) {
            entries.add(new UserMemoryEntry("f-" + i, "user-1", "fk-" + i, "fv-" + i,
                    "fact", Visibility.self, "agent-1", List.of(), "conv-1", false, 0, now, now));
        }
        for (int i = 0; i < 6; i++) {
            entries.add(new UserMemoryEntry("p-" + i, "user-1", "pk-" + i, "pv-" + i,
                    "preference", Visibility.self, "agent-1", List.of(), "conv-1", false, 0, now, now));
        }
        when(store.getAllEntries("user-1")).thenReturn(entries);
        when(summarizationService.summarizeWithUsage(anyString(), anyString(), anyString(), anyString(), any()))
                .thenThrow(new RuntimeException("401 Unauthorized: invalid api key"));

        var result = dreamService.process("user-1", "agent-1", dreamConfig);

        assertFalse(result.isSuccess());
        verify(summarizationService, times(1)).summarizeWithUsage(anyString(), anyString(), anyString(), anyString(), any());
    }

    /**
     * A transient provider failure (429/timeout/5xx) is not a configuration fault,
     * and a FAILED fire consumes the schedule's dead-letter budget: three of them
     * in a row disable the user's dream schedule for good. So a rate-limited group
     * is skipped, the remaining groups still run, and the cycle reports success.
     */
    @Test
    void summarize_transientLlmFailure_skipsGroupAndKeepsTheCycleSuccessful() throws Exception {
        enableSummarization();
        dreamConfig.setSummarizeGroupBy("category");

        Instant now = Instant.now();
        var entries = new ArrayList<UserMemoryEntry>();
        for (int i = 0; i < 6; i++) {
            entries.add(new UserMemoryEntry("f-" + i, "user-1", "fk-" + i, "fv-" + i,
                    "fact", Visibility.self, "agent-1", List.of(), "conv-1", false, 0, now, now));
        }
        for (int i = 0; i < 6; i++) {
            entries.add(new UserMemoryEntry("p-" + i, "user-1", "pk-" + i, "pv-" + i,
                    "preference", Visibility.self, "agent-1", List.of(), "conv-1", false, 0, now, now));
        }
        when(store.getAllEntries("user-1")).thenReturn(entries);
        when(store.upsert(any(UserMemoryEntry.class))).thenReturn("new-id");
        when(summarizationService.summarizeWithUsage(anyString(), anyString(), anyString(), anyString(), any()))
                .thenThrow(new RuntimeException("429 Too Many Requests"))
                .thenReturn(llmResult("[{\"key\": \"s1\", \"value\": \"v1\"}]"));

        var result = dreamService.process("user-1", "agent-1", dreamConfig);

        assertTrue(result.isSuccess(), "a rate limit must not fail the cycle, got: " + result.error());
        assertNull(result.error());
        // The second group was still attempted and consolidated (6 originals → 1 entry)
        verify(summarizationService, times(2)).summarizeWithUsage(anyString(), anyString(), anyString(), anyString(), any());
        assertEquals(5, result.entriesSummarized());
    }

    /**
     * Even when every group hits the same transient failure the cycle must not be
     * marked failed — otherwise a minutes-long provider outage across three cron
     * fires dead-letters the schedule permanently.
     */
    @Test
    void summarize_transientLlmFailureOnEveryGroup_stillReportsSuccess() throws Exception {
        enableSummarization();
        var entries = makeEntries(6, "fact", "agent-1");
        when(store.getAllEntries("user-1")).thenReturn(entries);
        when(summarizationService.summarizeWithUsage(anyString(), anyString(), anyString(), anyString(), any()))
                .thenThrow(new RuntimeException("upstream call timed out"));

        var result = dreamService.process("user-1", "agent-1", dreamConfig);

        assertTrue(result.isSuccess(), "a timeout must not fail the cycle, got: " + result.error());
        assertNull(result.error());
        assertEquals(0, result.entriesSummarized());
        verify(store, never()).deleteEntry(anyString());
    }

    @Test
    void isTransientLlmFailure_distinguishesProviderBlipsFromConfigurationFaults() {
        assertTrue(DreamService.isTransientLlmFailure(new RuntimeException("429 Too Many Requests")));
        assertTrue(DreamService.isTransientLlmFailure(new RuntimeException("status 503")));
        assertTrue(DreamService.isTransientLlmFailure(new RuntimeException("Overloaded")));
        assertTrue(DreamService.isTransientLlmFailure(
                new RuntimeException("llm call failed", new SocketTimeoutException("read timed out"))));

        assertFalse(DreamService.isTransientLlmFailure(new RuntimeException("401 Unauthorized")));
        assertFalse(DreamService.isTransientLlmFailure(new RuntimeException("model 'nope' does not exist")));
        assertFalse(DreamService.isTransientLlmFailure(new RuntimeException((String) null)));
        // A self-referential cause chain must terminate rather than spin
        assertFalse(DreamService.isTransientLlmFailure(new SelfCausedException("bad request")));
    }

    /**
     * A hostname that does not resolve is a WRONG ENDPOINT — the very example the
     * classifier's own javadoc gives for "permanent". Classifying it as transient
     * made every cycle skip its groups and report SUCCESS indefinitely: the
     * schedule never retried, never dead-lettered, and the operator never learned
     * the endpoint was misconfigured.
     * <p>
     * ConnectException is the contrast that makes the distinction meaningful: the
     * host resolved and refused, which is what a restarting service looks like.
     */
    @Test
    void isTransientLlmFailure_treatsAnUnresolvableHostAsPermanent() {
        assertFalse(DreamService.isTransientLlmFailure(new UnknownHostException("api.wrong-endpoint.invalid")),
                "an unresolvable host is a misconfiguration; reporting success forever hides it");
        assertFalse(DreamService.isTransientLlmFailure(
                new RuntimeException("llm call failed", new UnknownHostException("api.wrong-endpoint.invalid"))),
                "also when wrapped — the classifier walks the cause chain");

        // Still transient: resolved but refused, i.e. a service that may come back.
        assertTrue(DreamService.isTransientLlmFailure(
                new RuntimeException("llm call failed", new ConnectException("connection refused"))));
    }

    /** Exception whose cause is itself — guards the cause-chain walk. */
    private static final class SelfCausedException extends RuntimeException {
        SelfCausedException(String message) {
            super(message);
        }

        @Override
        public synchronized Throwable getCause() {
            return this;
        }
    }

    @Test
    void summarize_afterPruning_reloadsEntries() throws Exception {
        enableSummarization();
        dreamConfig.setPruneStaleAfterDays(30);

        Instant stale = Instant.now().minus(Duration.ofDays(60));
        Instant fresh = Instant.now();
        // First call returns stale + fresh entries; second call (after prune) returns
        // only fresh
        var initialEntries = new ArrayList<UserMemoryEntry>();
        initialEntries.add(new UserMemoryEntry("stale-1", "user-1", "old", "v", "fact", Visibility.self, "agent-1", List.of(), "conv-1", false, 0,
                stale, stale));
        for (int i = 0; i < 6; i++) {
            initialEntries.add(new UserMemoryEntry("fresh-" + i, "user-1", "k-" + i, "v-" + i, "fact", Visibility.self, "agent-1", List.of(),
                    "conv-1", false, 0, fresh, fresh));
        }

        var afterPruneEntries = initialEntries.subList(1, 7); // only the 6 fresh ones
        when(store.getAllEntries("user-1")).thenReturn(initialEntries).thenReturn(afterPruneEntries);

        String llmResponse = "[{\"key\": \"s\", \"value\": \"v\"}]";
        when(summarizationService.summarizeWithUsage(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(llmResult(llmResponse));

        var result = dreamService.process("user-1", "agent-1", dreamConfig);

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

    // === Cost estimation tests ===

    @Test
    void estimateCost_withTokenUsage() {
        var result = new SummarizationService.SummarizationResult("summary", 500, 100);
        double cost = DreamService.estimateCost(result, 0);
        // 600 tokens * $0.01/1K = $0.006
        assertEquals(0.006, cost, 0.0001);
    }

    @Test
    void estimateCost_withoutTokenUsage_fallsBackToCharEstimate() {
        var result = new SummarizationService.SummarizationResult("a]b".repeat(100), 0, 0);
        int inputLength = 500; // simulate 500-char input
        double cost = DreamService.estimateCost(result, inputLength);
        // (500 input + 300 output) / 4 = 200 estimated tokens * $0.01/1K = $0.002
        assertEquals(0.002, cost, 0.0001);
    }

    @Test
    void summarize_costCeilingReached_stopsEarly() throws Exception {
        enableSummarization();
        dreamConfig.setMaxSummarizationCalls(10); // high call limit
        dreamConfig.setMaxCostPerRun(0.005); // very low cost ceiling
        dreamConfig.setSummarizeGroupBy("category");

        Instant now = Instant.now();
        var entries = new ArrayList<UserMemoryEntry>();
        for (int i = 0; i < 6; i++) {
            entries.add(new UserMemoryEntry("f-" + i, "user-1", "fk-" + i, "fv-" + i,
                    "fact", Visibility.self, "agent-1", List.of(), "conv-1", false, 0, now, now));
        }
        for (int i = 0; i < 6; i++) {
            entries.add(new UserMemoryEntry("p-" + i, "user-1", "pk-" + i, "pv-" + i,
                    "preference", Visibility.self, "agent-1", List.of(), "conv-1", false, 0, now, now));
        }
        when(store.getAllEntries("user-1")).thenReturn(entries);

        // Return result with high token usage (1000 tokens → $0.01 > $0.005 ceiling)
        String llmResponse = "[{\"key\": \"s1\", \"value\": \"v1\"}]";
        when(summarizationService.summarizeWithUsage(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(llmResult(llmResponse, 800, 200));

        var result = dreamService.process("user-1", "agent-1", dreamConfig);

        assertTrue(result.isSuccess());
        // Only 1 LLM call should have been made — cost ceiling stops second group
        verify(summarizationService, times(1)).summarizeWithUsage(anyString(), anyString(), anyString(), anyString(), any());
    }

    // === PR Review Fixes: Copilot + CodeRabbit findings ===

    /**
     * Finding G8 — privacy boundary. Two agents' {@code self}-scoped memories used
     * to be merged into ONE entry whose visibility was upgraded to {@code global},
     * i.e. readable by every agent. Since {@code summarizeGroupBy} defaults to
     * "category" and {@code preserveAgentProvenance} to false, that was the default
     * path. Consolidation must now produce one {@code self} entry per contributing
     * agent, and never a {@code global} one.
     */
    @Test
    void summarize_multiAgentSelfScope_neverWidensVisibility() throws Exception {
        enableSummarization();
        dreamConfig.setPreserveAgentProvenance(false);
        dreamConfig.setSummarizeGroupBy("category");
        dreamConfig.setSummarizeMinEntries(2);
        dreamConfig.setCrossAgentMaintenance(true); // opt-in whole-set maintenance is what puts two agents in one group

        Instant now = Instant.now();
        var entries = new ArrayList<UserMemoryEntry>();
        for (int i = 0; i < 3; i++) {
            entries.add(new UserMemoryEntry("a1-" + i, "user-1", "k1-" + i, "secret of agent-1",
                    "fact", Visibility.self, "agent-1", List.of(), "conv-1", false, 0, now, now));
        }
        for (int i = 0; i < 3; i++) {
            entries.add(new UserMemoryEntry("a2-" + i, "user-1", "k2-" + i, "secret of agent-2",
                    "fact", Visibility.self, "agent-2", List.of(), "conv-1", false, 0, now, now));
        }
        when(store.getAllEntries("user-1")).thenReturn(entries);
        when(store.upsert(any(UserMemoryEntry.class))).thenReturn("new-id-1");

        String llmResponse = "[{\"key\": \"s\", \"value\": \"v\"}]";
        when(summarizationService.summarizeWithUsage(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(llmResult(llmResponse));

        dreamService.process("user-1", "agent-1", dreamConfig);

        // One consolidated entry per contributing agent — never a merged one
        var captor = org.mockito.ArgumentCaptor.forClass(UserMemoryEntry.class);
        verify(store, times(2)).upsert(captor.capture());
        var written = captor.getAllValues();
        assertTrue(written.stream().allMatch(e -> e.visibility() == Visibility.self),
                "self-scoped memories must never be widened, got: "
                        + written.stream().map(UserMemoryEntry::visibility).toList());
        assertEquals(List.of("agent-1", "agent-2"),
                written.stream().map(UserMemoryEntry::sourceAgentId).sorted().toList());
    }

    /**
     * Finding G8, mixed case: a group holding one agent's {@code self} memories and
     * another agent's {@code global} ones must still keep the private half private.
     */
    @Test
    void summarize_selfAndGlobalAcrossAgents_selfHalfStaysSelf() throws Exception {
        enableSummarization();
        dreamConfig.setPreserveAgentProvenance(false);
        dreamConfig.setSummarizeGroupBy("all");
        dreamConfig.setSummarizeMinEntries(2);
        dreamConfig.setCrossAgentMaintenance(true); // opt-in whole-set maintenance is what puts two agents in one group

        Instant now = Instant.now();
        var entries = new ArrayList<UserMemoryEntry>();
        for (int i = 0; i < 2; i++) {
            entries.add(new UserMemoryEntry("priv-" + i, "user-1", "pk-" + i, "private",
                    "fact", Visibility.self, "agent-1", List.of(), "conv-1", false, 0, now, now));
        }
        for (int i = 0; i < 2; i++) {
            entries.add(new UserMemoryEntry("pub-" + i, "user-1", "gk-" + i, "shared",
                    "fact", Visibility.global, "agent-2", List.of(), "conv-1", false, 0, now, now));
        }
        when(store.getAllEntries("user-1")).thenReturn(entries);
        when(store.upsert(any(UserMemoryEntry.class))).thenReturn("new-id");

        when(summarizationService.summarizeWithUsage(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(llmResult("[{\"key\": \"s\", \"value\": \"v\"}]"));

        dreamService.process("user-1", "agent-1", dreamConfig);

        var captor = org.mockito.ArgumentCaptor.forClass(UserMemoryEntry.class);
        verify(store, times(2)).upsert(captor.capture());
        var byAgent = captor.getAllValues().stream()
                .collect(Collectors.toMap(UserMemoryEntry::sourceAgentId, UserMemoryEntry::visibility));
        assertEquals(Visibility.self, byAgent.get("agent-1"));
        assertEquals(Visibility.global, byAgent.get("agent-2"));
    }

    @Test
    void summarize_preservesGroupIds() throws Exception {
        enableSummarization();

        Instant now = Instant.now();
        var entries = new ArrayList<UserMemoryEntry>();
        for (int i = 0; i < 6; i++) {
            entries.add(new UserMemoryEntry("id-" + i, "user-1", "k-" + i, "v-" + i,
                    "fact", Visibility.group, "agent-1",
                    List.of("group-A", "group-B"), "conv-1", false, 0, now, now));
        }
        when(store.getAllEntries("user-1")).thenReturn(entries);
        when(store.upsert(any(UserMemoryEntry.class))).thenReturn("new-id-1");

        String llmResponse = "[{\"key\": \"s\", \"value\": \"v\"}]";
        when(summarizationService.summarizeWithUsage(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(llmResult(llmResponse));

        dreamService.process("user-1", "agent-1", dreamConfig);

        var captor = org.mockito.ArgumentCaptor.forClass(UserMemoryEntry.class);
        verify(store).upsert(captor.capture());
        assertTrue(captor.getValue().groupIds().contains("group-A"));
        assertTrue(captor.getValue().groupIds().contains("group-B"));
    }

    @Test
    void summarize_nullCategory_defaultsToFact() throws Exception {
        enableSummarization();
        dreamConfig.setSummarizeMinEntries(3);

        Instant now = Instant.now();
        var entries = new ArrayList<UserMemoryEntry>();
        for (int i = 0; i < 3; i++) {
            entries.add(new UserMemoryEntry("id-" + i, "user-1", "k-" + i, "v-" + i,
                    null, Visibility.self, "agent-1", List.of(), "conv-1", false, 0, now, now));
        }
        when(store.getAllEntries("user-1")).thenReturn(entries);
        when(store.upsert(any(UserMemoryEntry.class))).thenReturn("new-id");

        String llmResponse = "[{\"key\": \"s\", \"value\": \"v\"}]";
        when(summarizationService.summarizeWithUsage(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(llmResult(llmResponse));

        var result = dreamService.process("user-1", "agent-1", dreamConfig);

        assertTrue(result.isSuccess());
        verify(summarizationService, times(1)).summarizeWithUsage(anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void parseConsolidatedEntries_blankKeyFiltered() {
        var result = dreamService.parseConsolidatedEntries(
                "[{\"key\": \"\", \"value\": \"v1\"}, {\"key\": \"  \", \"value\": \"v2\"}, {\"key\": \"good\", \"value\": \"v3\"}]");
        assertEquals(1, result.size());
        assertEquals("good", result.getFirst().key());
    }

    @Test
    void parseConsolidatedEntries_longKeyTruncated() {
        String longKey = "k".repeat(200);
        var result = dreamService.parseConsolidatedEntries(
                "[{\"key\": \"" + longKey + "\", \"value\": \"v\"}]");
        assertEquals(1, result.size());
        assertTrue(result.getFirst().key().length() <= DreamService.MAX_KEY_LENGTH);
    }

    @Test
    void truncate_shortString_unchanged() {
        assertEquals("hello", DreamService.truncate("hello", 100));
    }

    @Test
    void truncate_longString_truncated() {
        String result = DreamService.truncate("a".repeat(200), 100);
        assertEquals(100, result.length());
        assertTrue(result.endsWith("\u2026"));
    }

    @Test
    void truncate_null_returnsNull() {
        assertNull(DreamService.truncate(null, 100));
    }

    @Test
    void summarize_partialInsertFails_rollsBack() throws Exception {
        enableSummarization();
        var entries = makeEntries(6, "fact", "agent-1");
        when(store.getAllEntries("user-1")).thenReturn(entries);

        String llmResponse = "[{\"key\": \"s1\", \"value\": \"v1\"}, {\"key\": \"s2\", \"value\": \"v2\"}]";
        when(summarizationService.summarizeWithUsage(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(llmResult(llmResponse));

        // First upsert succeeds, second throws
        when(store.upsert(any(UserMemoryEntry.class)))
                .thenReturn("inserted-1")
                .thenThrow(new RuntimeException("DB write failed"));

        var result = dreamService.process("user-1", "agent-1", dreamConfig);

        assertTrue(result.isSuccess());
        assertEquals(0, result.entriesSummarized());
        // Verify rollback: the successfully-inserted entry should be deleted
        verify(store).deleteEntry("inserted-1");
        // Only the rollback delete — no originals deleted
        verify(store, times(1)).deleteEntry(anyString());
    }

    @Test
    void setSummarizeTargetEntries_rejectsZero() {
        var config = new AgentConfiguration.DreamConfig();
        assertThrows(IllegalArgumentException.class,
                () -> config.setSummarizeTargetEntries(0));
    }

    @Test
    void setSummarizeTargetEntries_rejectsNegative() {
        var config = new AgentConfiguration.DreamConfig();
        assertThrows(IllegalArgumentException.class,
                () -> config.setSummarizeTargetEntries(-1));
    }

    // === Agent ownership: a cycle configured by one agent must not act on another
    // agent's memories ===

    /** Three stale entries: one owned by agent-1, one by agent-2, one unowned. */
    private List<UserMemoryEntry> mixedOwnershipStaleEntries() {
        Instant stale = Instant.now().minus(Duration.ofDays(60));
        return List.of(
                new UserMemoryEntry("own", "user-1", "k-own", "v", "fact", Visibility.self, "agent-1", List.of(), "conv-1", false, 0, stale, stale),
                new UserMemoryEntry("foreign", "user-1", "k-foreign", "v", "fact", Visibility.self, "agent-2", List.of(), "conv-2", false, 0, stale,
                        stale),
                new UserMemoryEntry("unowned", "user-1", "k-unowned", "v", "fact", Visibility.global, null, List.of(), "conv-3", false, 0, stale,
                        stale));
    }

    /**
     * Data-loss finding: the cycle read {@code getAllEntries(userId)} — a
     * userId-only, agent-unscoped query — while {@code pruneStaleAfterDays} came
     * from ONE agent's dream config. Agent A's 30-day retention therefore deleted
     * agent B's memories under a value B's owner never configured. Pruning must
     * stay inside the firing agent's own entries, the same ownership rule
     * {@code UserMemoryTool} applies before evicting.
     */
    @Test
    void prune_foreignAndUnownedEntries_areNeverDeleted() throws Exception {
        when(store.getAllEntries("user-1")).thenReturn(mixedOwnershipStaleEntries()).thenReturn(List.of());
        dreamConfig.setDetectContradictions(false);

        var result = dreamService.process("user-1", "agent-1", dreamConfig);

        assertTrue(result.isSuccess());
        assertEquals(1, result.entriesPruned(), "only the firing agent's own entry may be pruned");
        verify(store).deleteEntry("own");
        verify(store, never()).deleteEntry("foreign");
        verify(store, never()).deleteEntry("unowned");
    }

    /**
     * The escape hatch for a dedicated housekeeping agent: with
     * {@code crossAgentMaintenance=true} the whole memory set is in scope again.
     */
    @Test
    void prune_crossAgentMaintenance_optsBackIntoTheWholeMemorySet() throws Exception {
        when(store.getAllEntries("user-1")).thenReturn(mixedOwnershipStaleEntries()).thenReturn(List.of());
        dreamConfig.setDetectContradictions(false);
        dreamConfig.setCrossAgentMaintenance(true);

        var result = dreamService.process("user-1", "agent-1", dreamConfig);

        assertTrue(result.isSuccess());
        assertEquals(3, result.entriesPruned());
        verify(store).deleteEntry("own");
        verify(store).deleteEntry("foreign");
        verify(store).deleteEntry("unowned");
    }

    /**
     * The same boundary on the summarization side: another agent's memory text must
     * not be serialized into the prompt that goes to <em>this</em> agent's
     * configured provider/baseUrl, and its originals must not be deleted and
     * replaced.
     */
    @Test
    void summarize_foreignAgentEntries_neverReachTheModelAndAreNotDeleted() throws Exception {
        enableSummarization();
        dreamConfig.setSummarizeMinEntries(2);

        Instant now = Instant.now();
        var entries = new ArrayList<UserMemoryEntry>();
        for (int i = 0; i < 3; i++) {
            entries.add(new UserMemoryEntry("a1-" + i, "user-1", "k1-" + i, "owned-by-agent-1",
                    "fact", Visibility.self, "agent-1", List.of(), "conv-1", false, 0, now, now));
        }
        for (int i = 0; i < 3; i++) {
            entries.add(new UserMemoryEntry("a2-" + i, "user-1", "k2-" + i, "private-to-agent-2",
                    "fact", Visibility.self, "agent-2", List.of(), "conv-2", false, 0, now, now));
        }
        when(store.getAllEntries("user-1")).thenReturn(entries);
        when(store.upsert(any(UserMemoryEntry.class))).thenReturn("new-id");
        when(summarizationService.summarizeWithUsage(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(llmResult("[{\"key\": \"s\", \"value\": \"v\"}]"));

        var result = dreamService.process("user-1", "agent-1", dreamConfig);

        assertTrue(result.isSuccess());
        var content = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(summarizationService, times(1)).summarizeWithUsage(content.capture(), anyString(), anyString(), anyString(), any());
        assertTrue(content.getValue().contains("owned-by-agent-1"));
        assertFalse(content.getValue().contains("private-to-agent-2"),
                "another agent's private memory must not be sent to this agent's model endpoint");
        for (int i = 0; i < 3; i++) {
            verify(store).deleteEntry("a1-" + i);
            verify(store, never()).deleteEntry("a2-" + i);
        }
    }

    // === Finding I1: schedule wiring (processScheduledFire) ===

    @Test
    void isDreamSchedule_recognisesOnlyTheDreamMarker() {
        assertTrue(DreamService.isDreamSchedule(
                Map.of(DreamService.METADATA_TYPE_KEY, DreamService.METADATA_TYPE_CONSOLIDATION)));
        assertFalse(DreamService.isDreamSchedule(null));
        assertFalse(DreamService.isDreamSchedule(Map.of()));
        assertFalse(DreamService.isDreamSchedule(Map.of("hitlType", "hitl_timeout")));
        assertFalse(DreamService.isDreamSchedule(Map.of(DreamService.METADATA_TYPE_KEY, "something_else")));
    }

    @Test
    void processScheduledFire_resolvesDreamConfigFromAgentAndRunsCycle() throws Exception {
        var dream = new AgentConfiguration.DreamConfig();
        dream.setEnabled(true);
        dream.setPruneStaleAfterDays(30);
        dream.setDetectContradictions(false);
        dream.setSummarizeInteractions(false);

        var memoryConfig = new AgentConfiguration.UserMemoryConfig();
        memoryConfig.setDream(dream);
        var agentConfiguration = new AgentConfiguration();
        agentConfiguration.setUserMemoryConfig(memoryConfig);
        when(agentStore.read("agent-1", 7)).thenReturn(agentConfiguration);

        Instant stale = Instant.now().minus(Duration.ofDays(60));
        when(store.getAllEntries("user-1")).thenReturn(List.of(
                new UserMemoryEntry("1", "user-1", "old", "v", "fact", Visibility.self, "agent-1", List.of(), "conv-1", false, 0, stale, stale)))
                .thenReturn(List.of());

        var result = dreamService.processScheduledFire("agent-1", 7, "user-1");

        assertTrue(result.isSuccess(), "expected success, got: " + result.error());
        assertEquals(1, result.entriesPruned());
        verify(store).deleteEntry("1");
    }

    /**
     * The schedule's {@code agentId} is the ownership boundary, not just the config
     * source: a dream schedule for agent-1 must not prune agent-2's memories for
     * the same user.
     */
    @Test
    void processScheduledFire_prunesOnlyTheFiringAgentsMemories() throws Exception {
        var dream = new AgentConfiguration.DreamConfig();
        dream.setEnabled(true);
        dream.setPruneStaleAfterDays(30);
        dream.setDetectContradictions(false);
        dream.setSummarizeInteractions(false);

        var memoryConfig = new AgentConfiguration.UserMemoryConfig();
        memoryConfig.setDream(dream);
        var agentConfiguration = new AgentConfiguration();
        agentConfiguration.setUserMemoryConfig(memoryConfig);
        when(agentStore.read("agent-1", 7)).thenReturn(agentConfiguration);

        when(store.getAllEntries("user-1")).thenReturn(mixedOwnershipStaleEntries()).thenReturn(List.of());

        var result = dreamService.processScheduledFire("agent-1", 7, "user-1");

        assertTrue(result.isSuccess(), "expected success, got: " + result.error());
        assertEquals(1, result.entriesPruned());
        verify(store).deleteEntry("own");
        verify(store, never()).deleteEntry("foreign");
    }

    @Test
    void processScheduledFire_versionZero_resolvesLatestAgentVersion() throws Exception {
        var dream = new AgentConfiguration.DreamConfig();
        dream.setEnabled(true);
        dream.setPruneStaleAfterDays(0);
        dream.setDetectContradictions(false);
        dream.setSummarizeInteractions(false);

        var memoryConfig = new AgentConfiguration.UserMemoryConfig();
        memoryConfig.setDream(dream);
        var agentConfiguration = new AgentConfiguration();
        agentConfiguration.setUserMemoryConfig(memoryConfig);

        when(agentStore.getCurrentResourceId("agent-1")).thenReturn(resourceId("agent-1", 4));
        when(agentStore.read("agent-1", 4)).thenReturn(agentConfiguration);
        when(store.getAllEntries("user-1")).thenReturn(List.of());

        var result = dreamService.processScheduledFire("agent-1", 0, "user-1");

        assertTrue(result.isSuccess(), "expected success, got: " + result.error());
        verify(agentStore).read("agent-1", 4);
    }

    @Test
    void processScheduledFire_missingUserId_failsLoudlyWithoutTouchingMemory() throws Exception {
        var result = dreamService.processScheduledFire("agent-1", 1, null);

        assertFalse(result.isSuccess());
        assertTrue(result.error().contains("userId"), "error must name the missing field, got: " + result.error());
        verifyNoInteractions(store);
        verifyNoInteractions(agentStore);
    }

    @Test
    void processScheduledFire_schedulerPlaceholderUserId_failsLoudly() throws Exception {
        // The schedule REST surface defaults userId to "system:scheduler"; running
        // Dream under it would consolidate an empty memory set and look successful.
        var result = dreamService.processScheduledFire("agent-1", 1, "system:scheduler");

        assertFalse(result.isSuccess());
        assertTrue(result.error().contains("system:scheduler"), "got: " + result.error());
        // Rejected before any lookup — not merely failing later for another reason
        verifyNoInteractions(store);
        verifyNoInteractions(agentStore);
    }

    @Test
    void processScheduledFire_dreamDisabledOnAgent_failsLoudly() throws Exception {
        var memoryConfig = new AgentConfiguration.UserMemoryConfig();
        memoryConfig.getDream().setEnabled(false);
        var agentConfiguration = new AgentConfiguration();
        agentConfiguration.setUserMemoryConfig(memoryConfig);
        when(agentStore.read("agent-1", 1)).thenReturn(agentConfiguration);

        var result = dreamService.processScheduledFire("agent-1", 1, "user-1");

        assertFalse(result.isSuccess());
        assertTrue(result.error().contains("dream consolidation disabled"), "got: " + result.error());
        verifyNoInteractions(store);
    }

    @Test
    void processScheduledFire_agentNotFound_failsLoudly() throws Exception {
        when(agentStore.read("agent-1", 1)).thenThrow(new IResourceStore.ResourceNotFoundException("no such agent"));

        var result = dreamService.processScheduledFire("agent-1", 1, "user-1");

        assertFalse(result.isSuccess());
        assertTrue(result.error().contains("no such agent"), "got: " + result.error());
        verifyNoInteractions(store);
    }

    @Test
    void processScheduledFire_missingAgentId_failsLoudly() throws Exception {
        var result = dreamService.processScheduledFire("  ", 1, "user-1");

        assertFalse(result.isSuccess());
        assertTrue(result.error().contains("agentId"), "got: " + result.error());
        verifyNoInteractions(store);
    }

    private static IResourceStore.IResourceId resourceId(String id, int version) {
        return new IResourceStore.IResourceId() {
            @Override
            public String getId() {
                return id;
            }

            @Override
            public Integer getVersion() {
                return version;
            }
        };
    }
}
