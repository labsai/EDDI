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
import ai.labs.eddi.configs.properties.model.UserMemoryEntry.Assessment;
import ai.labs.eddi.modules.llm.impl.SummarizationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@code userMemoryConfig.dream.contradictionResolution} end to end through
 * {@link DreamService#process}: which compare-and-set writes a cycle makes,
 * what it counts, and what it holds back from consolidation. The decision table
 * itself is pinned by {@link UserMemoryContradictionsTest}.
 */
@DisplayName("DreamService — contradiction resolution")
class DreamServiceContradictionResolutionTest {

    private static final String USER = "user-1";
    private static final String AGENT = "agent-1";
    private static final Instant OLDER = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant NEWER = OLDER.plusSeconds(3600);

    private IUserMemoryStore store;
    private SummarizationService summarizationService;
    private SimpleMeterRegistry meters;
    private DreamService dreamService;
    private AgentConfiguration.DreamConfig dreamConfig;

    @BeforeEach
    void setUp() {
        store = mock(IUserMemoryStore.class);
        summarizationService = mock(SummarizationService.class);
        meters = new SimpleMeterRegistry();
        dreamService = new DreamService(store, mock(IAgentStore.class), summarizationService, meters, new ObjectMapper());
        dreamService.initMetrics();

        dreamConfig = new AgentConfiguration.DreamConfig();
        dreamConfig.setEnabled(true);
        dreamConfig.setPruneStaleAfterDays(0);
        dreamConfig.setDetectContradictions(true);
        dreamConfig.setSummarizeInteractions(false);
    }

    private static UserMemoryEntry ownSelf(String id, Object value, Instant updatedAt) {
        return new UserMemoryEntry(id, USER, "language", value, "preference", Visibility.self, AGENT, List.of(), null, false, 0, OLDER,
                updatedAt);
    }

    private static UserMemoryEntry foreignSelf(String id, Object value, Instant updatedAt) {
        return new UserMemoryEntry(id, USER, "language", value, "preference", Visibility.self, "agent-2", List.of(), null, false, 0, OLDER,
                updatedAt);
    }

    private static UserMemoryEntry foreignGlobal(String id, Object value, Instant updatedAt) {
        return new UserMemoryEntry(id, USER, "language", value, "preference", Visibility.global, "agent-2", List.of(), null, false, 0, OLDER,
                updatedAt);
    }

    private double count(String meter) {
        return meters.counter(meter).count();
    }

    @Test
    @DisplayName("default keep_newest: detected and counted, no entry changed")
    void defaultChangesNothing() throws Exception {
        when(store.getAllEntries(USER)).thenReturn(List.of(ownSelf("1", "English", OLDER), foreignSelf("2", "German", NEWER)));

        var result = dreamService.process(USER, AGENT, dreamConfig);

        assertTrue(result.isSuccess());
        assertEquals(1, result.contradictionsFound());
        verify(store, never()).setAssessmentIfUnchanged(anyString(), any(), any(), any());
        verify(store, times(1)).getAllEntries(USER);
        assertEquals(0, count("eddi.dream.contradictions.contested"));
    }

    @Test
    @DisplayName("mark_contested flags this agent's own entry only, by compare-and-set on the updatedAt it read")
    void markContestedOwnEntryOnly() throws Exception {
        dreamConfig.setContradictionResolution("mark_contested");
        when(store.getAllEntries(USER)).thenReturn(List.of(ownSelf("1", "English", OLDER), foreignSelf("2", "German", NEWER)));
        when(store.setAssessmentIfUnchanged("1", Assessment.CONTESTED, null, OLDER)).thenReturn(true);

        var result = dreamService.process(USER, AGENT, dreamConfig);

        assertTrue(result.isSuccess());
        assertEquals(1, result.contradictionsFound());
        verify(store).setAssessmentIfUnchanged("1", Assessment.CONTESTED, null, OLDER);
        verify(store, never()).setAssessmentIfUnchanged(eq("2"), any(), any(), any());
        assertEquals(1, count("eddi.dream.contradictions.contested"));
        assertEquals(0, result.conflictsSkipped());
        // entries changed, so the later phases re-read them
        verify(store, times(2)).getAllEntries(USER);
    }

    @Test
    @DisplayName("mark_contested with crossAgentMaintenance flags both sides")
    void markContestedCrossAgent() throws Exception {
        dreamConfig.setContradictionResolution("mark_contested");
        dreamConfig.setCrossAgentMaintenance(true);
        when(store.getAllEntries(USER)).thenReturn(List.of(ownSelf("1", "English", OLDER), foreignSelf("2", "German", NEWER)));
        when(store.setAssessmentIfUnchanged(anyString(), eq(Assessment.CONTESTED), isNull(), any())).thenReturn(true);

        dreamService.process(USER, AGENT, dreamConfig);

        verify(store).setAssessmentIfUnchanged("1", Assessment.CONTESTED, null, OLDER);
        verify(store).setAssessmentIfUnchanged("2", Assessment.CONTESTED, null, NEWER);
        assertEquals(2, count("eddi.dream.contradictions.contested"));
    }

    @Test
    @DisplayName("an assessment that loses to a concurrent write counts as a skipped conflict")
    void lostCompareAndSetIsAConflict() throws Exception {
        dreamConfig.setContradictionResolution("mark_contested");
        when(store.getAllEntries(USER)).thenReturn(List.of(ownSelf("1", "English", OLDER), foreignSelf("2", "German", NEWER)));
        when(store.setAssessmentIfUnchanged("1", Assessment.CONTESTED, null, OLDER)).thenReturn(false);

        var result = dreamService.process(USER, AGENT, dreamConfig);

        assertTrue(result.isSuccess());
        assertEquals(1, result.conflictsSkipped());
        assertEquals(1, count("eddi.dream.conflicts.skipped"));
        assertEquals(0, count("eddi.dream.contradictions.contested"));
        verify(store, times(1)).getAllEntries(USER);
    }

    @Test
    @DisplayName("supersede_older retires this agent's older self entry behind a newer global one — never deletes it")
    void supersedeOlder() throws Exception {
        dreamConfig.setContradictionResolution("supersede_older");
        when(store.getAllEntries(USER)).thenReturn(List.of(ownSelf("1", "English", OLDER), foreignGlobal("2", "German", NEWER)));
        when(store.setAssessmentIfUnchanged("1", Assessment.SUPERSEDED, "2", OLDER)).thenReturn(true);

        var result = dreamService.process(USER, AGENT, dreamConfig);

        assertTrue(result.isSuccess());
        verify(store).setAssessmentIfUnchanged("1", Assessment.SUPERSEDED, "2", OLDER);
        verify(store, never()).deleteEntry(anyString());
        verify(store, never()).deleteEntryIfUnchanged(anyString(), any());
        assertEquals(1, count("eddi.dream.contradictions.superseded"));
    }

    @Test
    @DisplayName("an unknown strategy falls back to keep_newest: detection continues, nothing is written")
    void unknownStrategyFallsBack() throws Exception {
        dreamConfig.setContradictionResolution("keep_oldest");
        when(store.getAllEntries(USER)).thenReturn(List.of(ownSelf("1", "English", OLDER), foreignSelf("2", "German", NEWER)));

        var result = dreamService.process(USER, AGENT, dreamConfig);

        assertTrue(result.isSuccess());
        assertEquals(1, result.contradictionsFound());
        verify(store, never()).setAssessmentIfUnchanged(anyString(), any(), any(), any());
    }

    @Test
    @DisplayName("a contest whose rival is gone is lifted and counted as cleared")
    void staleContestCleared() throws Exception {
        var contested = ownSelf("1", "English", OLDER).withAssessment(Assessment.CONTESTED, null);
        when(store.getAllEntries(USER)).thenReturn(List.of(contested));
        when(store.setAssessmentIfUnchanged("1", Assessment.ACCEPTED, null, OLDER)).thenReturn(true);

        dreamService.process(USER, AGENT, dreamConfig);

        verify(store).setAssessmentIfUnchanged("1", Assessment.ACCEPTED, null, OLDER);
        assertEquals(1, count("eddi.dream.contradictions.cleared"));
    }

    @Test
    @DisplayName("contested and superseded entries are held back from consolidation")
    void nonAcceptedEntriesAreNotConsolidated() throws Exception {
        dreamConfig.setDetectContradictions(false);
        dreamConfig.setSummarizeInteractions(true);
        dreamConfig.setSummarizeMinEntries(5);
        var entries = new ArrayList<UserMemoryEntry>();
        for (int i = 0; i < 4; i++) {
            entries.add(new UserMemoryEntry("id-" + i, USER, "key-" + i, "value-" + i, "fact", Visibility.self, AGENT, List.of(), null, false, 0,
                    OLDER, OLDER));
        }
        entries.add(new UserMemoryEntry("id-c", USER, "key-c", "value-c", "fact", Visibility.self, AGENT, List.of(), null, true, 0, OLDER, OLDER,
                Assessment.CONTESTED, null));
        entries.add(new UserMemoryEntry("id-s", USER, "key-s", "value-s", "fact", Visibility.self, AGENT, List.of(), null, false, 0, OLDER, OLDER,
                Assessment.SUPERSEDED, "id-0"));
        when(store.getAllEntries(USER)).thenReturn(entries);

        var result = dreamService.process(USER, AGENT, dreamConfig);

        // four accepted entries are below summarizeMinEntries=5; with the two held-back
        // entries counted, the group would have been sent to the model
        assertTrue(result.isSuccess());
        verifyNoInteractions(summarizationService);
    }
}
