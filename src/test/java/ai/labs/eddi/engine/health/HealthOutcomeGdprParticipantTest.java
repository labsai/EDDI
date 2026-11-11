/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.engine.gdpr.UserDataExport;
import ai.labs.eddi.engine.health.model.HealthDeletedRows;
import ai.labs.eddi.engine.health.model.HealthOutcome;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.model.ConversationListingSummary;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Session outcomes under GDPR, retention and the orphan sweep")
class HealthOutcomeGdprParticipantTest {

    static final Instant AT = Instant.parse("2026-11-01T10:00:00Z");

    static HealthOutcome row(int sessionNo) {
        return new HealthOutcome("c1", sessionNo, "agent", 2, "alice", "api", true, AT, AT.plusSeconds(60), 3, 0.5, 1, "FAILED", "structural",
                false, LocalDate.of(2026, 11, 1), null, 0, List.of("act"), AT);
    }

    @Test
    @DisplayName("erasure deletes the user's sessions; a blank user erases nothing")
    void erase() {
        IHealthOutcomeStore store = mock(IHealthOutcomeStore.class);
        when(store.deleteByUserId("alice")).thenReturn(3L);
        HealthOutcomeGdprParticipant participant = new HealthOutcomeGdprParticipant(store);
        assertEquals("agentHealthOutcomes", participant.name());
        assertEquals(3, participant.erase("alice"));
        assertEquals(0, participant.erase(" "));
        verify(store, never()).deleteByUserId(" ");
    }

    @Test
    @DisplayName("export: the user's sessions, newest first, without the user id, capped and marked truncated")
    @SuppressWarnings("unchecked")
    void export() {
        IHealthOutcomeStore store = mock(IHealthOutcomeStore.class);
        when(store.findByUserId("alice", HealthOutcomeGdprParticipant.MAX_EXPORTED_SESSIONS + 1)).thenReturn(List.of(row(5), row(0)));
        HealthOutcomeGdprParticipant participant = new HealthOutcomeGdprParticipant(store);
        Map<String, Object> section = (Map<String, Object>) participant.export("alice");
        List<Map<String, Object>> sessions = (List<Map<String, Object>>) section.get("sessions");
        assertEquals(2, sessions.size());
        assertEquals(5, sessions.get(0).get("sessionNo"));
        assertEquals("FAILED", sessions.get(0).get("outcome"));
        assertEquals("2026-11-01T10:01:00Z", sessions.get(0).get("endedAt"));
        assertFalse(sessions.get(0).containsKey("userId"));
        assertEquals(false, section.get(UserDataExport.SECTION_TRUNCATED));
        assertNull(participant.export("bob"));
        assertNull(participant.export(null));

        List<HealthOutcome> many = new ArrayList<>();
        for (int i = 0; i <= HealthOutcomeGdprParticipant.MAX_EXPORTED_SESSIONS; i++) {
            many.add(row(i));
        }
        when(store.findByUserId("carol", HealthOutcomeGdprParticipant.MAX_EXPORTED_SESSIONS + 1)).thenReturn(many);
        Map<String, Object> capped = (Map<String, Object>) participant.export("carol");
        assertEquals(HealthOutcomeGdprParticipant.MAX_EXPORTED_SESSIONS, ((List<?>) capped.get("sessions")).size());
        assertEquals(true, capped.get(UserDataExport.SECTION_TRUNCATED));
    }

    @Test
    @DisplayName("retention deletes sessions that ended before the cutoff; the orphan sweep checks evaluated conversations and deletes theirs")
    @SuppressWarnings("unchecked")
    void retentionAndOrphans() throws Exception {
        IHealthTurnStore turns = mock(IHealthTurnStore.class);
        IHealthOutcomeStore outcomes = mock(IHealthOutcomeStore.class);
        IConversationMemoryStore conversations = mock(IConversationMemoryStore.class);
        Instance<IConversationMemoryStore> conversationInstance = mock(Instance.class);
        when(conversationInstance.isResolvable()).thenReturn(true);
        when(conversationInstance.get()).thenReturn(conversations);
        Instance<IHealthOutcomeStore> outcomeInstance = mock(Instance.class);
        when(outcomeInstance.isResolvable()).thenReturn(true);
        when(outcomeInstance.get()).thenReturn(outcomes);
        doAnswer(inv -> {
            ((BiConsumer<String, String>) inv.getArgument(2)).accept("live", "agent-1");
            ((BiConsumer<String, String>) inv.getArgument(2)).accept("gone", "agent-1");
            return null;
        }).when(turns).forEachConversationAgentUpdatedSince(any(), anyInt(), any());
        doAnswer(inv -> {
            ((Consumer<String>) inv.getArgument(2)).accept("live");
            ((Consumer<String>) inv.getArgument(2)).accept("gone");
            return null;
        }).when(outcomes).forEachConversationEvaluatedSince(any(), anyInt(), any());
        when(conversations.loadListingSummaries(anyList())).thenReturn(Map.of("live", mock(ConversationListingSummary.class)));
        when(outcomes.deleteByConversationId("gone")).thenReturn(2L);

        Instant now = Instant.parse("2026-11-10T03:00:00Z");
        HealthTurnRetention retention = new HealthTurnRetention(turns, conversationInstance, HealthSettings.defaults(),
                new HealthMetrics(new SimpleMeterRegistry()), Clock.fixed(now, ZoneOffset.UTC));
        retention.outcomeStore = outcomeInstance;
        retention.sweep();

        verify(outcomes).deleteOlderThan(LocalDate.of(2026, 8, 12));
        verify(outcomes).deleteByConversationId("gone");
        verify(turns).deleteConversationReturningDays("gone");
        verify(turns, never()).deleteConversationReturningDays("live");
        verify(outcomes, never()).deleteByConversationId("live");
    }

    @Test
    @DisplayName("m7: the orphan sweep's cap is shared — outcome rows get their half even when turn rows alone would fill it")
    @SuppressWarnings("unchecked")
    void fairCap() throws Exception {
        IHealthTurnStore turns = mock(IHealthTurnStore.class);
        IHealthOutcomeStore outcomes = mock(IHealthOutcomeStore.class);
        IConversationMemoryStore conversations = mock(IConversationMemoryStore.class);
        Instance<IConversationMemoryStore> conversationInstance = mock(Instance.class);
        when(conversationInstance.isResolvable()).thenReturn(true);
        when(conversationInstance.get()).thenReturn(conversations);
        Instance<IHealthOutcomeStore> outcomeInstance = mock(Instance.class);
        when(outcomeInstance.isResolvable()).thenReturn(true);
        when(outcomeInstance.get()).thenReturn(outcomes);
        doAnswer(inv -> {
            int limit = inv.getArgument(1);
            for (int i = 0; i < Math.min(limit, 10); i++) {
                ((BiConsumer<String, String>) inv.getArgument(2)).accept("t" + i, "agent-1");
            }
            return null;
        }).when(turns).forEachConversationAgentUpdatedSince(any(), anyInt(), any());
        doAnswer(inv -> {
            int limit = inv.getArgument(1);
            for (int i = 0; i < Math.min(limit, 10); i++) {
                ((Consumer<String>) inv.getArgument(2)).accept("o" + i);
            }
            return null;
        }).when(outcomes).forEachConversationEvaluatedSince(any(), anyInt(), any());
        when(conversations.loadListingSummaries(anyList())).thenReturn(Map.of());

        HealthSettings settings = new HealthSettings(true, 10, Duration.ofDays(90), Duration.ofHours(48), 4);
        HealthTurnRetention retention = new HealthTurnRetention(turns, conversationInstance, settings, new HealthMetrics(new SimpleMeterRegistry()),
                Clock.fixed(Instant.parse("2026-11-10T03:00:00Z"), ZoneOffset.UTC));
        retention.outcomeStore = outcomeInstance;
        retention.removeOrphans();

        verify(outcomes).deleteByConversationId("o0");
        verify(outcomes).deleteByConversationId("o1");
        verify(turns).deleteConversationReturningDays("t0");
        verify(turns, never()).deleteConversationReturningDays("t5");
    }

    @Test
    @DisplayName("the orphan sweep gives capacity the turn rows leave unused to the remaining evaluated conversations")
    @SuppressWarnings("unchecked")
    void unusedCapacityGoesToOutcomes() throws Exception {
        IHealthTurnStore turns = mock(IHealthTurnStore.class);
        IHealthOutcomeStore outcomes = mock(IHealthOutcomeStore.class);
        IConversationMemoryStore conversations = mock(IConversationMemoryStore.class);
        Instance<IConversationMemoryStore> conversationInstance = mock(Instance.class);
        when(conversationInstance.isResolvable()).thenReturn(true);
        when(conversationInstance.get()).thenReturn(conversations);
        Instance<IHealthOutcomeStore> outcomeInstance = mock(Instance.class);
        when(outcomeInstance.isResolvable()).thenReturn(true);
        when(outcomeInstance.get()).thenReturn(outcomes);
        doAnswer(inv -> {
            ((BiConsumer<String, String>) inv.getArgument(2)).accept("t0", "agent-1");
            return null;
        }).when(turns).forEachConversationAgentUpdatedSince(any(), anyInt(), any());
        doAnswer(inv -> {
            int limit = inv.getArgument(1);
            for (int i = 0; i < Math.min(limit, 10); i++) {
                ((Consumer<String>) inv.getArgument(2)).accept("o" + i);
            }
            return null;
        }).when(outcomes).forEachConversationEvaluatedSince(any(), anyInt(), any());
        when(conversations.loadListingSummaries(anyList())).thenReturn(Map.of());

        HealthSettings settings = new HealthSettings(true, 10, Duration.ofDays(90), Duration.ofHours(48), 4);
        HealthTurnRetention retention = new HealthTurnRetention(turns, conversationInstance, settings, new HealthMetrics(new SimpleMeterRegistry()),
                Clock.fixed(Instant.parse("2026-11-10T03:00:00Z"), ZoneOffset.UTC));
        retention.outcomeStore = outcomeInstance;
        retention.removeOrphans();

        verify(turns).deleteConversationReturningDays("t0");
        verify(outcomes).deleteByConversationId("o0");
        verify(outcomes).deleteByConversationId("o1");
        verify(outcomes).deleteByConversationId("o2");
        verify(outcomes, never()).deleteByConversationId("o3");
    }

    @Test
    @DisplayName("a failed session-outcome delete does not skip the turn rows' delete or their day marks")
    @SuppressWarnings("unchecked")
    void outcomeDeleteFailureKeepsTurnMarks() throws Exception {
        IHealthTurnStore turns = mock(IHealthTurnStore.class);
        IHealthOutcomeStore outcomes = mock(IHealthOutcomeStore.class);
        IConversationMemoryStore conversations = mock(IConversationMemoryStore.class);
        Instance<IConversationMemoryStore> conversationInstance = mock(Instance.class);
        when(conversationInstance.isResolvable()).thenReturn(true);
        when(conversationInstance.get()).thenReturn(conversations);
        Instance<IHealthOutcomeStore> outcomeInstance = mock(Instance.class);
        when(outcomeInstance.isResolvable()).thenReturn(true);
        when(outcomeInstance.get()).thenReturn(outcomes);
        HealthDirtyDays dirty = mock(HealthDirtyDays.class);
        Instance<HealthDirtyDays> dirtyInstance = mock(Instance.class);
        when(dirtyInstance.isResolvable()).thenReturn(true);
        when(dirtyInstance.get()).thenReturn(dirty);
        doAnswer(inv -> {
            ((BiConsumer<String, String>) inv.getArgument(2)).accept("gone", "agent-1");
            return null;
        }).when(turns).forEachConversationAgentUpdatedSince(any(), anyInt(), any());
        HealthDeletedRows deleted = new HealthDeletedRows(3L, Map.of("agent-1", Set.of(LocalDate.of(2026, 11, 9))));
        when(turns.deleteConversationReturningDays("gone")).thenReturn(deleted);
        when(outcomes.deleteByConversationId("gone")).thenThrow(new HealthStoreException("down", null));
        when(conversations.loadListingSummaries(anyList())).thenReturn(Map.of());

        HealthTurnRetention retention = new HealthTurnRetention(turns, conversationInstance, HealthSettings.defaults(),
                new HealthMetrics(new SimpleMeterRegistry()), Clock.fixed(Instant.parse("2026-11-10T03:00:00Z"), ZoneOffset.UTC));
        retention.outcomeStore = outcomeInstance;
        retention.dirtyDays = dirtyInstance;

        assertEquals(3L, retention.removeOrphans());
        verify(dirty).markDeletedRows("gone", Set.of("agent-1"), deleted);
    }
}
