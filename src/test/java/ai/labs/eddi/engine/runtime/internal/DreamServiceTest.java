/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.configs.properties.model.Property.Visibility;
import ai.labs.eddi.configs.properties.model.UserMemoryEntry;
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
    private DreamService dreamService;
    private AgentConfiguration.DreamConfig dreamConfig;

    @BeforeEach
    void setUp() {
        store = mock(IUserMemoryStore.class);
        dreamService = new DreamService(store, new SimpleMeterRegistry());
        dreamService.initMetrics();

        dreamConfig = new AgentConfiguration.DreamConfig();
        dreamConfig.setEnabled(true);
        dreamConfig.setPruneStaleAfterDays(30);
        dreamConfig.setDetectContradictions(true);
        dreamConfig.setSummarizeInteractions(false);
    }

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
    void process_shouldReturnZeroSummarized() throws Exception {
        when(store.getAllEntries("user-1")).thenReturn(List.of());
        dreamConfig.setPruneStaleAfterDays(0);
        dreamConfig.setDetectContradictions(false);
        dreamConfig.setSummarizeInteractions(true);

        var result = dreamService.process("user-1", dreamConfig);

        assertTrue(result.isSuccess());
        assertEquals(0, result.entriesSummarized(), "V1 summarization should always return 0");
    }

    @Test
    void process_shouldLoadEntriesOnlyOnce() throws Exception {
        Instant now = Instant.now();
        var entries = List.of(
                new UserMemoryEntry("1", "user-1", "fact1", "value1", "fact", Visibility.self, "agent-1", List.of(), "conv-1", false, 0, now, now));
        when(store.getAllEntries("user-1")).thenReturn(entries);

        // Both pruning and contradiction detection enabled — entries should be loaded
        // only once
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
}
