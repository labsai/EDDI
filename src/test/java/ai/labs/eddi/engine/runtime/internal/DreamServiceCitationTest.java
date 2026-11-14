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
import ai.labs.eddi.modules.llm.impl.SummarizationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code userMemoryConfig.dream.citeSources} through
 * {@link DreamService#process}: the request numbers the originals, each
 * consolidated entry is checked against the ones it cites, a failing group
 * follows {@code onValidationFailure}, and a written entry records the ids of
 * its sources.
 */
@DisplayName("DreamService — cited consolidation")
class DreamServiceCitationTest {

    private static final String USER = "user-1";
    private static final String AGENT = "agent-1";
    private static final Instant WHEN = Instant.parse("2026-10-01T00:00:00Z");

    private IUserMemoryStore store;
    private SummarizationService summarizationService;
    private SimpleMeterRegistry meters;
    private DreamService dreamService;
    private AgentConfiguration.DreamConfig config;

    @BeforeEach
    void setUp() throws Exception {
        store = mock(IUserMemoryStore.class);
        summarizationService = mock(SummarizationService.class);
        meters = new SimpleMeterRegistry();
        dreamService = new DreamService(store, mock(IAgentStore.class), summarizationService, meters, new ObjectMapper());
        dreamService.initMetrics();

        config = new AgentConfiguration.DreamConfig();
        config.setEnabled(true);
        config.setPruneStaleAfterDays(0);
        config.setDetectContradictions(false);
        config.setSummarizeInteractions(true);
        config.setSummarizeMinEntries(3);
        config.setSummarizeTargetEntries(1);
        config.setMaxCostPerRun(0.50);

        List<UserMemoryEntry> entries = List.of(entry("e0", "budget", "1,000 EUR"), entry("e1", "seats", "2 seats"),
                entry("e2", "hotel", "near the station"));
        when(store.getAllEntries(USER)).thenReturn(entries);
        when(store.insertIfAbsent(any(UserMemoryEntry.class))).thenReturn("new-id");
        when(store.deleteEntryIfUnchanged(anyString(), any())).thenReturn(true);
    }

    private static UserMemoryEntry entry(String id, String key, String value) {
        return new UserMemoryEntry(id, USER, key, value, "fact", Visibility.self, AGENT, List.of(), null, false, 0, WHEN, WHEN);
    }

    private void answer(String... responses) {
        var stub = when(summarizationService.summarizeWithUsage(anyString(), anyString(), anyString(), anyString(), any()));
        for (String response : responses) {
            stub = stub.thenReturn(new SummarizationService.SummarizationResult(response, 100, 20));
        }
    }

    private double count(String outcome) {
        var counter = meters.find("eddi.summary.citation.checks").tags("source", "dream", "outcome", outcome).counter();
        return counter != null ? counter.count() : 0;
    }

    private static final String GOOD = "[{\"key\": \"trip\", \"value\": \"2 seats, budget 1,000 EUR, near the station\", \"sources\": [0, 1, 2]}]";
    private static final String BAD = "[{\"key\": \"trip\", \"value\": \"2 seats, budget 1.000 EUR\", \"sources\": [0, 1]}]";

    @Test
    @DisplayName("off by default: the request and the written entry are as before")
    void offByDefault() throws Exception {
        assertFalse(new AgentConfiguration.DreamConfig().isCiteSources());
        assertEquals("fallback_to_previous", new AgentConfiguration.DreamConfig().getOnValidationFailure());
        answer("[{\"key\": \"trip\", \"value\": \"2 seats, budget 1.000 EUR\"}]");

        dreamService.process(USER, AGENT, config);

        var content = ArgumentCaptor.forClass(String.class);
        var instructions = ArgumentCaptor.forClass(String.class);
        verify(summarizationService).summarizeWithUsage(content.capture(), instructions.capture(), anyString(), anyString(), any());
        assertFalse(content.getValue().contains("\"ref\""), content.getValue());
        assertFalse(instructions.getValue().contains("\"sources\""));
        var written = ArgumentCaptor.forClass(UserMemoryEntry.class);
        verify(store).insertIfAbsent(written.capture());
        assertEquals(List.of(), written.getValue().sourceEntryIds());
        assertTrue(meters.find("eddi.summary.citation.checks").counters().isEmpty());
    }

    @Test
    @DisplayName("a passing consolidation is written with the ids of the entries it cites")
    void passingRecordsSourceIds() throws Exception {
        config.setCiteSources(true);
        answer(GOOD);

        var result = dreamService.process(USER, AGENT, config);

        assertTrue(result.isSuccess());
        var content = ArgumentCaptor.forClass(String.class);
        var instructions = ArgumentCaptor.forClass(String.class);
        verify(summarizationService).summarizeWithUsage(content.capture(), instructions.capture(), anyString(), anyString(), any());
        assertTrue(content.getValue().contains("\"ref\" : 2"), content.getValue());
        assertTrue(instructions.getValue().contains("\"sources\""));
        var written = ArgumentCaptor.forClass(UserMemoryEntry.class);
        verify(store).insertIfAbsent(written.capture());
        assertEquals(List.of("e0", "e1", "e2"), written.getValue().sourceEntryIds());
        assertEquals(1, count("passed"));
        verify(store, times(3)).deleteEntryIfUnchanged(anyString(), any());
    }

    @Test
    @DisplayName("default fallback_to_previous: a failing group writes and deletes nothing")
    void fallbackKeepsOriginals() throws Exception {
        config.setCiteSources(true);
        answer(BAD);

        var result = dreamService.process(USER, AGENT, config);

        assertTrue(result.isSuccess());
        assertEquals(0, result.entriesSummarized());
        verify(store, never()).insertIfAbsent(any());
        verify(store, never()).deleteEntryIfUnchanged(anyString(), any());
        assertEquals(1, count("fallback"));
    }

    @Test
    @DisplayName("keep writes a failing consolidation, recording what it cited")
    void keepWritesAnyway() throws Exception {
        config.setCiteSources(true);
        config.setOnValidationFailure("keep");
        answer("[{\"key\": \"trip\", \"value\": \"2 seats, budget 1.000 EUR\", \"sources\": [0, 1, 7]}]");

        dreamService.process(USER, AGENT, config);

        var written = ArgumentCaptor.forClass(UserMemoryEntry.class);
        verify(store).insertIfAbsent(written.capture());
        assertEquals(List.of("e0", "e1"), written.getValue().sourceEntryIds(), "the ref that does not exist is dropped");
        assertEquals(1, count("kept"));
    }

    @Test
    @DisplayName("keep: an entry citing nothing records every original of its group")
    void keepWithoutCitationsRecordsTheGroup() throws Exception {
        config.setCiteSources(true);
        config.setOnValidationFailure("keep");
        answer("[{\"key\": \"trip\", \"value\": \"a trip\"}]");

        dreamService.process(USER, AGENT, config);

        var written = ArgumentCaptor.forClass(UserMemoryEntry.class);
        verify(store).insertIfAbsent(written.capture());
        assertEquals(List.of("e0", "e1", "e2"), written.getValue().sourceEntryIds());
    }

    @Test
    @DisplayName("retry_once: the second answer is checked, written, and both calls are paid for")
    void retryOncePasses() throws Exception {
        config.setCiteSources(true);
        config.setOnValidationFailure("retry_once");
        answer(BAD, GOOD);

        var result = dreamService.process(USER, AGENT, config);

        var instructions = ArgumentCaptor.forClass(String.class);
        verify(summarizationService, times(2)).summarizeWithUsage(anyString(), instructions.capture(), anyString(), anyString(), any());
        String retryInstructions = instructions.getAllValues().get(1);
        assertTrue(retryInstructions.contains("number 1.000"), "the retry is told what failed");
        String feedback = retryInstructions.substring(instructions.getAllValues().get(0).length());
        assertTrue(feedback.contains("only the JSON array") && feedback.contains("\"sources\"") && feedback.contains("refs"),
                "the retry feedback restates the Dream answer contract: " + feedback);
        assertFalse(feedback.contains("Write the summary again") || feedback.contains("cite its turns"),
                "the retry feedback must not carry the rolling summary's wording: " + feedback);
        var written = ArgumentCaptor.forClass(UserMemoryEntry.class);
        verify(store).insertIfAbsent(written.capture());
        assertEquals("2 seats, budget 1,000 EUR, near the station", written.getValue().value());
        assertEquals(1, count("retried"));
        // two calls at 100 + 20 tokens each, priced at the documented upper bound
        double oneCall = DreamService.estimateCost(new SummarizationService.SummarizationResult("x", 100, 20), 0);
        assertEquals(2 * oneCall, result.estimatedCostUsd(), 1e-9);
    }

    @Test
    @DisplayName("retry_once: a second failure falls back")
    void retryOnceFailsAgain() throws Exception {
        config.setCiteSources(true);
        config.setOnValidationFailure("retry_once");
        answer(BAD, BAD);

        dreamService.process(USER, AGENT, config);

        verify(summarizationService, times(2)).summarizeWithUsage(anyString(), anyString(), anyString(), anyString(), any());
        verify(store, never()).insertIfAbsent(any());
        assertEquals(1, count("fallback"));
    }

    @Test
    @DisplayName("retry_once: no retry once maxCostPerRun is spent")
    void retryOnceRespectsTheCostCeiling() throws Exception {
        config.setCiteSources(true);
        config.setOnValidationFailure("retry_once");
        config.setMaxCostPerRun(0.000001);
        answer(BAD, GOOD);

        dreamService.process(USER, AGENT, config);

        verify(summarizationService, times(1)).summarizeWithUsage(anyString(), anyString(), anyString(), anyString(), any());
        verify(store, never()).insertIfAbsent(any());
        assertEquals(1, count("fallback"));
    }

    @Test
    @DisplayName("retry_once: a failing second call skips the group without failing the cycle")
    void retryOnceSecondCallThrows() throws Exception {
        config.setCiteSources(true);
        config.setOnValidationFailure("retry_once");
        when(summarizationService.summarizeWithUsage(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(new SummarizationService.SummarizationResult(BAD, 100, 20)).thenThrow(new RuntimeException("401 unauthorized"));

        var result = dreamService.process(USER, AGENT, config);

        assertTrue(result.isSuccess());
        verify(store, never()).insertIfAbsent(any());
        assertEquals(1, count("fallback"));
        assertEquals(1, meters.counter("eddi.dream.summarization.failed").count());
    }

    @Test
    @DisplayName("duplicate keys merge their sources")
    void duplicateKeysMergeSources() {
        var merged = DreamService.mergeDuplicateKeys(List.of(new DreamService.ConsolidatedEntry("k", "a", List.of(0, 1)),
                new DreamService.ConsolidatedEntry("k", "b", List.of(1, 2))));
        assertEquals(1, merged.size());
        assertEquals("a; b", merged.getFirst().value());
        assertEquals(List.of(0, 1, 2), merged.getFirst().sources());
    }

    @Test
    @DisplayName("the cited parser reads sources and rejects a non-scalar value like the plain parser")
    void citedParser() {
        var parsed = dreamService.parseCitedConsolidatedEntries("```json\n[{\"key\": \"k\", \"value\": 5, \"sources\": [0, \"1\", \"x\"]}]\n```");
        assertEquals(1, parsed.size());
        assertEquals("5", parsed.getFirst().value());
        var sources = new ArrayList<Integer>();
        sources.add(0);
        sources.add(1);
        sources.add(null);
        assertEquals(sources, parsed.getFirst().sources());
        assertTrue(dreamService.parseCitedConsolidatedEntries("[{\"key\": \"k\", \"value\": {\"a\": 1}, \"sources\": [0]}]").isEmpty());
        assertTrue(dreamService.parseCitedConsolidatedEntries("not json").isEmpty());
    }
}
