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
import ai.labs.eddi.modules.llm.impl.SummarizationService.SummarizationResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Extended tests for {@link DreamService} — additional branch coverage for:
 * buildGroups, estimateCost, escapeJson, mostRestrictiveVisibility,
 * parseConsolidatedEntries, multi-agent merging, visibility upgrades,
 * summarization target capping, cost ceiling.
 */
@DisplayName("DreamService — Extended Branch Coverage")
class DreamServiceExtendedTest {

    @Mock
    private IUserMemoryStore store;
    @Mock
    private SummarizationService summarizationService;

    private DreamService dreamService;
    private AgentConfiguration.DreamConfig dreamConfig;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        meterRegistry = new SimpleMeterRegistry();
        dreamService = new DreamService(store, summarizationService, meterRegistry, new ObjectMapper());
        dreamService.initMetrics();

        dreamConfig = new AgentConfiguration.DreamConfig();
        dreamConfig.setPruneStaleAfterDays(30);
        dreamConfig.setDetectContradictions(true);
        dreamConfig.setSummarizeInteractions(true);
        dreamConfig.setMaxSummarizationCalls(5);
        dreamConfig.setMaxCostPerRun(1.0);
        dreamConfig.setSummarizeMinEntries(2);
        dreamConfig.setSummarizeTargetEntries(1);
    }

    // ==================== estimateCost ====================

    @Nested
    @DisplayName("estimateCost")
    class EstimateCostTests {

        @Test
        @DisplayName("uses totalTokens when > 0")
        void usesTotalTokens() {
            var result = new SummarizationResult("summary", 100, 50);
            double cost = DreamService.estimateCost(result, 400);
            assertEquals(150 * 0.01 / 1000.0, cost, 1e-10);
        }

        @Test
        @DisplayName("uses character fallback when totalTokens = 0")
        void usesCharacterFallback() {
            var result = new SummarizationResult("short", 0, 0);
            double cost = DreamService.estimateCost(result, 200);
            // (200 + 5) / 4 = 51 tokens → 51 * 0.01 / 1000
            double expected = 51 * 0.01 / 1000.0;
            assertEquals(expected, cost, 1e-10);
        }

        @Test
        @DisplayName("handles null summary in fallback")
        void handleNullSummary() {
            var result = new SummarizationResult(null, 0, 0);
            double cost = DreamService.estimateCost(result, 200);
            // outputLength = 0 → (200 + 0) / 4 = 50 tokens
            double expected = 50 * 0.01 / 1000.0;
            assertEquals(expected, cost, 1e-10);
        }
    }

    // ==================== escapeJson ====================

    @Nested
    @DisplayName("escapeJson")
    class EscapeJsonTests {

        @Test
        @DisplayName("null returns empty string")
        void nullInput() {
            assertEquals("", DreamService.escapeJson(null));
        }

        @Test
        @DisplayName("plain text passes through")
        void plainText() {
            assertEquals("hello world", DreamService.escapeJson("hello world"));
        }

        @Test
        @DisplayName("quotes are escaped")
        void quotesEscaped() {
            String result = DreamService.escapeJson("say \"hello\"");
            assertTrue(result.contains("\\\""));
        }

        @Test
        @DisplayName("newlines are escaped")
        void newlinesEscaped() {
            String result = DreamService.escapeJson("line1\nline2");
            assertFalse(result.contains("\n"));
        }
    }

    // ==================== mostRestrictiveVisibility ====================

    @Nested
    @DisplayName("mostRestrictiveVisibility")
    class VisibilityTests {

        @Test
        @DisplayName("all global → returns global")
        void allGlobal() {
            var entries = List.of(
                    makeEntry("k1", Visibility.global, "a1"),
                    makeEntry("k2", Visibility.global, "a1"));
            assertEquals(Visibility.global, DreamService.mostRestrictiveVisibility(entries));
        }

        @Test
        @DisplayName("mixed global and group → returns group")
        void mixedGlobalGroup() {
            var entries = List.of(
                    makeEntry("k1", Visibility.global, "a1"),
                    makeEntry("k2", Visibility.group, "a1"));
            assertEquals(Visibility.group, DreamService.mostRestrictiveVisibility(entries));
        }

        @Test
        @DisplayName("any self → returns self")
        void anySelf() {
            var entries = List.of(
                    makeEntry("k1", Visibility.global, "a1"),
                    makeEntry("k2", Visibility.self, "a1"));
            assertEquals(Visibility.self, DreamService.mostRestrictiveVisibility(entries));
        }
    }

    // ==================== parseConsolidatedEntries — edge cases
    // ====================

    @Nested
    @DisplayName("parseConsolidatedEntries — edge cases")
    class ParseEdgeCases {

        @Test
        @DisplayName("null response returns empty list")
        void nullResponse() {
            assertTrue(dreamService.parseConsolidatedEntries(null).isEmpty());
        }

        @Test
        @DisplayName("blank response returns empty list")
        void blankResponse() {
            assertTrue(dreamService.parseConsolidatedEntries("   ").isEmpty());
        }

        @Test
        @DisplayName("no JSON array brackets returns empty list")
        void noArrayBrackets() {
            assertTrue(dreamService.parseConsolidatedEntries("not json at all").isEmpty());
        }

        @Test
        @DisplayName("markdown fence is stripped")
        void markdownFenceStripped() {
            String fenced = "```json\n[{\"key\": \"k1\", \"value\": \"v1\"}]\n```";
            var result = dreamService.parseConsolidatedEntries(fenced);
            assertEquals(1, result.size());
            assertEquals("k1", result.getFirst().key());
        }

        @Test
        @DisplayName("entries missing 'key' field are filtered")
        void missingKeyField() {
            var result = dreamService.parseConsolidatedEntries(
                    "[{\"value\": \"v1\"}, {\"key\": \"k2\", \"value\": \"v2\"}]");
            assertEquals(1, result.size());
        }

        @Test
        @DisplayName("entries with blank value are filtered")
        void blankValue() {
            var result = dreamService.parseConsolidatedEntries(
                    "[{\"key\": \"k1\", \"value\": \"\"}, {\"key\": \"k2\", \"value\": \"v2\"}]");
            assertEquals(1, result.size());
        }

        @Test
        @DisplayName("entries with null value are filtered")
        void nullValue() {
            var result = dreamService.parseConsolidatedEntries(
                    "[{\"key\": \"k1\", \"value\": null}, {\"key\": \"k2\", \"value\": \"v2\"}]");
            assertEquals(1, result.size());
        }

        @Test
        @DisplayName("malformed JSON returns empty list")
        void malformedJson() {
            assertTrue(dreamService.parseConsolidatedEntries("[{broken json}]").isEmpty());
        }

        @Test
        @DisplayName("truncation applies to long keys and values")
        void truncation() {
            String longKey = "k".repeat(200);
            String longVal = "v".repeat(2000);
            var result = dreamService.parseConsolidatedEntries(
                    "[{\"key\": \"" + longKey + "\", \"value\": \"" + longVal + "\"}]");
            assertEquals(1, result.size());
            assertTrue(result.getFirst().key().length() <= DreamService.MAX_KEY_LENGTH);
            assertTrue(result.getFirst().value().length() <= DreamService.MAX_VALUE_LENGTH);
        }
    }

    // ==================== process — cost ceiling ====================

    @Nested
    @DisplayName("process — cost ceiling")
    class CostCeilingTests {

        @Test
        @DisplayName("cost ceiling stops processing before max calls reached")
        void costCeilingStopsProcessing() throws Exception {
            dreamConfig.setMaxCostPerRun(0.0001); // Very low cost ceiling
            dreamConfig.setSummarizeGroupBy("all");
            dreamConfig.setPreserveAgentProvenance(false);

            var entries = makeEntries(10, "fact", "agent-1");
            when(store.getAllEntries("user-1")).thenReturn(entries);
            when(store.upsert(any(UserMemoryEntry.class))).thenReturn("new-id");

            // Return a result with high token count to exceed cost ceiling
            String llmResponse = "[{\"key\": \"s\", \"value\": \"v\"}]";
            when(summarizationService.summarizeWithUsage(anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(new SummarizationResult(llmResponse, 5000, 5000));

            var result = dreamService.process("user-1", dreamConfig);
            assertTrue(result.isSuccess());
        }
    }

    // ==================== process — summarization target capping
    // ====================

    @Nested
    @DisplayName("process — summarization target capping")
    class TargetCappingTests {

        @Test
        @DisplayName("consolidated entries capped to target count")
        void consolidatedCapped() throws Exception {
            dreamConfig.setSummarizeTargetEntries(1);
            dreamConfig.setSummarizeGroupBy("all");
            dreamConfig.setPreserveAgentProvenance(false);

            var entries = makeEntries(5, "fact", "agent-1");
            when(store.getAllEntries("user-1")).thenReturn(entries);
            when(store.upsert(any(UserMemoryEntry.class))).thenReturn("new-id");

            // LLM returns 3 entries but target is 1 → should cap to 1
            String llmResponse = "[{\"key\": \"s1\", \"value\": \"v1\"}, {\"key\": \"s2\", \"value\": \"v2\"}, {\"key\": \"s3\", \"value\": \"v3\"}]";
            when(summarizationService.summarizeWithUsage(anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(new SummarizationResult(llmResponse, 100, 50));

            var result = dreamService.process("user-1", dreamConfig);
            assertTrue(result.isSuccess());
            // Only 1 upsert should happen (capped to target)
            verify(store, times(1)).upsert(any(UserMemoryEntry.class));
        }
    }

    // ==================== process — LLM returns more entries than input
    // ====================

    @Nested
    @DisplayName("process — LLM returns too many entries")
    class TooManyEntriesTests {

        @Test
        @DisplayName("LLM returns >= original count → group skipped")
        void tooManyEntriesSkipped() throws Exception {
            dreamConfig.setSummarizeGroupBy("all");
            dreamConfig.setPreserveAgentProvenance(false);
            dreamConfig.setSummarizeMinEntries(2);

            var entries = makeEntries(3, "fact", "agent-1");
            when(store.getAllEntries("user-1")).thenReturn(entries);

            // LLM returns 3 entries = same as original → skip
            String llmResponse = "[{\"key\": \"s1\", \"value\": \"v1\"}, {\"key\": \"s2\", \"value\": \"v2\"}, {\"key\": \"s3\", \"value\": \"v3\"}]";
            when(summarizationService.summarizeWithUsage(anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(new SummarizationResult(llmResponse, 100, 50));

            var result = dreamService.process("user-1", dreamConfig);
            assertTrue(result.isSuccess());
            assertEquals(0, result.entriesSummarized());
            verify(store, never()).upsert(any(UserMemoryEntry.class));
        }
    }

    // ==================== process — multi-agent visibility upgrade
    // ====================

    @Nested
    @DisplayName("process — multi-agent visibility upgrade")
    class MultiAgentVisibilityTests {

        @Test
        @DisplayName("self-scoped entries from multiple agents upgrade to global")
        void selfUpgradedToGlobal() throws Exception {
            dreamConfig.setSummarizeGroupBy("all");
            dreamConfig.setPreserveAgentProvenance(false);
            dreamConfig.setSummarizeMinEntries(2);

            // Entries from 2 different agents, both self-scoped
            var entries = new ArrayList<>(List.of(
                    new UserMemoryEntry("id1", "user-1", "k1", "v1", "fact",
                            Visibility.self, "agent-1", null, "source", false, 0,
                            Instant.now().minusSeconds(100), Instant.now()),
                    new UserMemoryEntry("id2", "user-1", "k2", "v2", "fact",
                            Visibility.self, "agent-2", null, "source", false, 0,
                            Instant.now().minusSeconds(50), Instant.now())));
            when(store.getAllEntries("user-1")).thenReturn(entries);
            when(store.upsert(any(UserMemoryEntry.class))).thenReturn("new-id");

            String llmResponse = "[{\"key\": \"consolidated\", \"value\": \"merged\"}]";
            when(summarizationService.summarizeWithUsage(anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(new SummarizationResult(llmResponse, 0, 0));

            var result = dreamService.process("user-1", dreamConfig);
            assertTrue(result.isSuccess());

            // Verify upserted entry has global visibility
            verify(store).upsert(argThat(entry -> entry.visibility() == Visibility.global));
        }
    }

    // ==================== buildGroups — groupBy strategies ====================

    @Nested
    @DisplayName("buildGroups — groupBy strategies")
    class BuildGroupsTests {

        @Test
        @DisplayName("groupBy 'all' puts everything in one group")
        void groupByAll() throws Exception {
            dreamConfig.setSummarizeGroupBy("all");
            dreamConfig.setPreserveAgentProvenance(false);

            var entries = makeEntries(4, "fact", "agent-1");
            entries.addAll(makeEntries(2, "preference", "agent-2"));
            when(store.getAllEntries("user-1")).thenReturn(entries);
            when(store.upsert(any(UserMemoryEntry.class))).thenReturn("new-id");

            String llmResponse = "[{\"key\": \"s\", \"value\": \"v\"}]";
            when(summarizationService.summarizeWithUsage(anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(new SummarizationResult(llmResponse, 0, 0));

            var result = dreamService.process("user-1", dreamConfig);
            assertTrue(result.isSuccess());
            // One LLM call for the "all" group
            verify(summarizationService, times(1))
                    .summarizeWithUsage(anyString(), anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("groupBy category with preserveAgentProvenance")
        void groupByCategoryWithProvenance() throws Exception {
            dreamConfig.setSummarizeGroupBy("category");
            dreamConfig.setPreserveAgentProvenance(true);
            dreamConfig.setSummarizeMinEntries(2);

            var entries = new ArrayList<>(List.of(
                    new UserMemoryEntry("id1", "user-1", "k1", "v1", "fact",
                            Visibility.global, "agent-1", null, "source", false, 0,
                            Instant.now().minusSeconds(100), Instant.now()),
                    new UserMemoryEntry("id2", "user-1", "k2", "v2", "fact",
                            Visibility.global, "agent-1", null, "source", false, 0,
                            Instant.now().minusSeconds(50), Instant.now()),
                    new UserMemoryEntry("id3", "user-1", "k3", "v3", "fact",
                            Visibility.global, "agent-2", null, "source", false, 0,
                            Instant.now().minusSeconds(25), Instant.now()),
                    new UserMemoryEntry("id4", "user-1", "k4", "v4", "fact",
                            Visibility.global, "agent-2", null, "source", false, 0,
                            Instant.now().minusSeconds(10), Instant.now())));
            when(store.getAllEntries("user-1")).thenReturn(entries);
            when(store.upsert(any(UserMemoryEntry.class))).thenReturn("new-id");

            String llmResponse = "[{\"key\": \"s\", \"value\": \"v\"}]";
            when(summarizationService.summarizeWithUsage(anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(new SummarizationResult(llmResponse, 0, 0));

            var result = dreamService.process("user-1", dreamConfig);
            assertTrue(result.isSuccess());
            // Two sub-groups: fact:agent-1 and fact:agent-2
            verify(summarizationService, times(2))
                    .summarizeWithUsage(anyString(), anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("entries with null category default to 'fact'")
        void nullCategoryDefaultsFact() throws Exception {
            dreamConfig.setSummarizeGroupBy("category");
            dreamConfig.setPreserveAgentProvenance(false);
            dreamConfig.setSummarizeMinEntries(2);

            var entries = new ArrayList<>(List.of(
                    new UserMemoryEntry("id1", "user-1", "k1", "v1", null, // null category
                            Visibility.global, "agent-1", null, "source", false, 0,
                            Instant.now().minusSeconds(100), Instant.now()),
                    new UserMemoryEntry("id2", "user-1", "k2", "v2", null, // null category
                            Visibility.global, "agent-1", null, "source", false, 0,
                            Instant.now().minusSeconds(50), Instant.now())));
            when(store.getAllEntries("user-1")).thenReturn(entries);
            when(store.upsert(any(UserMemoryEntry.class))).thenReturn("new-id");

            String llmResponse = "[{\"key\": \"s\", \"value\": \"v\"}]";
            when(summarizationService.summarizeWithUsage(anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(new SummarizationResult(llmResponse, 0, 0));

            var result = dreamService.process("user-1", dreamConfig);
            assertTrue(result.isSuccess());
            verify(summarizationService, times(1))
                    .summarizeWithUsage(anyString(), anyString(), anyString(), anyString());
        }
    }

    // ==================== process — error during process ====================

    @Nested
    @DisplayName("process — exception handling")
    class ProcessExceptionTests {

        @Test
        @DisplayName("exception returns DreamResult with error")
        void exceptionReturnsError() throws Exception {
            when(store.getAllEntries("user-1")).thenThrow(new RuntimeException("DB error"));

            var result = dreamService.process("user-1", dreamConfig);
            assertFalse(result.isSuccess());
            assertNotNull(result.error());
            assertTrue(result.error().contains("DB error"));
        }
    }

    // ==================== DreamResult ====================

    @Nested
    @DisplayName("DreamResult")
    class DreamResultTests {

        @Test
        @DisplayName("isSuccess returns true when error is null")
        void isSuccessTrue() {
            var result = new DreamService.DreamResult("user1", 5, 2, 3, 100L, null);
            assertTrue(result.isSuccess());
        }

        @Test
        @DisplayName("isSuccess returns false when error is present")
        void isSuccessFalse() {
            var result = new DreamService.DreamResult("user1", 0, 0, 0, 50L, "failed");
            assertFalse(result.isSuccess());
        }
    }

    // ==================== Helpers ====================

    private UserMemoryEntry makeEntry(String key, Visibility visibility, String agentId) {
        return new UserMemoryEntry("id-" + key, "user-1", key, "value-" + key, "fact",
                visibility, agentId, null, "source", false, 0,
                Instant.now().minusSeconds(100), Instant.now());
    }

    private ArrayList<UserMemoryEntry> makeEntries(int count, String category, String agentId) {
        var entries = new ArrayList<UserMemoryEntry>();
        for (int i = 0; i < count; i++) {
            entries.add(new UserMemoryEntry("id-" + i, "user-1", "key-" + i, "value-" + i, category,
                    Visibility.global, agentId, null, "source", false, 0,
                    Instant.now().minusSeconds(100 - i), Instant.now()));
        }
        return entries;
    }
}
