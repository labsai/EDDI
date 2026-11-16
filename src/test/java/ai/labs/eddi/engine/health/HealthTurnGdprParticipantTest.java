/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.engine.gdpr.UserDataExport;
import ai.labs.eddi.engine.health.model.HealthDeletedRows;
import ai.labs.eddi.engine.health.model.HealthFailure;
import ai.labs.eddi.engine.health.model.HealthTurn;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.model.ConversationListingSummary;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("HealthTurnGdprParticipant — erasure and export of turn rows")
class HealthTurnGdprParticipantTest {

    private final IHealthTurnStore store = mock(IHealthTurnStore.class);
    private final HealthTurnGdprParticipant participant = new HealthTurnGdprParticipant(store);

    private static HealthTurn row(int i) {
        return HealthTurnAggregatorTest.row("c" + i, 1, "e" + i, 1, "api", 0.1, null, null, List.of(new HealthFailure("TIMEOUT", "m", "retry")),
                true, false);
    }

    @SuppressWarnings("unchecked")
    private static <T> Instance<T> instance(T bean) {
        Instance<T> instance = mock(Instance.class);
        when(instance.isResolvable()).thenReturn(bean != null);
        when(instance.get()).thenReturn(bean);
        return instance;
    }

    private HealthDirtyDays dirtyDays() {
        HealthDirtyDays dirty = mock(HealthDirtyDays.class);
        participant.dirtyDays = instance(dirty);
        return dirty;
    }

    @Test
    @DisplayName("erasure deletes the user's rows; a blank user erases nothing")
    void erase() {
        when(store.deleteByUserIdReturningDays("user-1")).thenReturn(new HealthDeletedRows(7L, Map.of()));
        assertEquals(7L, participant.erase("user-1"));
        assertEquals(0L, participant.erase(" "));
        assertEquals(0L, participant.erase(null));
        verify(store).deleteByUserIdReturningDays("user-1");
        assertEquals("agentHealthTurns", participant.name());
    }

    @Test
    @DisplayName("the days the delete returned are marked; no agent lookup is needed")
    void daysKnown() throws Exception {
        HealthDirtyDays dirty = dirtyDays();
        HealthDeletedRows deleted = new HealthDeletedRows(3L, Map.of("agent", Set.of(LocalDate.of(2026, 11, 10))));
        when(store.deleteByUserIdReturningDays("user-1")).thenReturn(deleted);
        IConversationMemoryStore conversations = mock(IConversationMemoryStore.class);
        participant.conversations = instance(conversations);

        assertEquals(3L, participant.erase("user-1"));
        verify(dirty).markErasedRows(Set.of(), deleted);
        verify(conversations, never()).getConversationIdsByUserId(any());
    }

    @Test
    @DisplayName("unknown days: the agents noted at step 0 are re-rolled")
    void unknownDaysUseStepZeroAgents() throws Exception {
        HealthDirtyDays dirty = dirtyDays();
        HealthDeletedRows unknown = new HealthDeletedRows(4L, null);
        when(store.deleteByUserIdReturningDays("user-1")).thenReturn(unknown);
        IConversationMemoryStore conversations = mock(IConversationMemoryStore.class);
        when(conversations.getConversationIdsByUserId("user-1")).thenReturn(List.of("c1", "c2"));
        when(conversations.loadListingSummaries(any())).thenReturn(Map.of("c1", summary("c1", "a1"), "c2", summary("c2", "a2")));
        HealthErasureFence fence = new HealthErasureFence();
        fence.conversations = instance(conversations);
        fence.stopInFlightWork("user-1");
        // The cascade deletes the conversations before the participant runs.
        when(conversations.getConversationIdsByUserId("user-1")).thenReturn(List.of());
        participant.fence = instance(fence);
        participant.conversations = instance(conversations);

        assertEquals(4L, participant.erase("user-1"));
        verify(dirty).markErasedRows(Set.of("a1", "a2"), unknown);
    }

    @Test
    @DisplayName("unknown days and nothing noted at step 0: the agents of conversations still stored are re-rolled")
    void unknownDaysUseLiveConversations() throws Exception {
        HealthDirtyDays dirty = dirtyDays();
        HealthDeletedRows unknown = new HealthDeletedRows(1L, null);
        when(store.deleteByUserIdReturningDays("user-1")).thenReturn(unknown);
        IConversationMemoryStore conversations = mock(IConversationMemoryStore.class);
        when(conversations.getConversationIdsByUserId("user-1")).thenReturn(List.of("c1"));
        when(conversations.loadListingSummaries(any())).thenReturn(Map.of("c1", summary("c1", "a1")));
        participant.fence = instance(new HealthErasureFence());
        participant.conversations = instance(conversations);

        participant.erase("user-1");
        verify(dirty).markErasedRows(Set.of("a1"), unknown);
    }

    @Test
    @DisplayName("nothing known: the erasure still succeeds, and markErasedRows gets no agent (it warns)")
    void nothingKnown() throws Exception {
        HealthDirtyDays dirty = dirtyDays();
        HealthDeletedRows unknown = new HealthDeletedRows(2L, null);
        when(store.deleteByUserIdReturningDays("user-1")).thenReturn(unknown);
        IConversationMemoryStore conversations = mock(IConversationMemoryStore.class);
        when(conversations.getConversationIdsByUserId("user-1")).thenThrow(new IllegalStateException("down"));
        participant.conversations = instance(conversations);

        assertEquals(2L, participant.erase("user-1"));
        verify(dirty).markErasedRows(Set.of(), unknown);

        participant.conversations = null;
        participant.fence = null;
        assertEquals(2L, participant.erase("user-1"));
    }

    private static ConversationListingSummary summary(String conversationId, String agentId) {
        return new ConversationListingSummary(conversationId, "user-1", null, null, agentId, 1, 1);
    }

    @Test
    @DisplayName("export carries the rows as plain JSON values; nothing held is null")
    @SuppressWarnings("unchecked")
    void export() {
        when(store.findByUserId("user-1", HealthTurnGdprParticipant.MAX_EXPORTED_TURNS + 1)).thenReturn(List.of(row(1)));
        Map<String, Object> section = (Map<String, Object>) participant.export("user-1");
        List<Map<String, Object>> turns = (List<Map<String, Object>>) section.get("turns");
        assertEquals(1, turns.size());
        assertEquals("2026-11-10", turns.get(0).get("day"));
        assertEquals(false, section.get(UserDataExport.SECTION_TRUNCATED));
        // Serialises with a plain mapper — no java.time module needed.
        assertDoesNotThrow(() -> new ObjectMapper().writeValueAsString(section));

        when(store.findByUserId("nobody", HealthTurnGdprParticipant.MAX_EXPORTED_TURNS + 1)).thenReturn(List.of());
        assertNull(participant.export("nobody"));
        assertNull(participant.export(null));
    }

    @Test
    @DisplayName("past the cap the export is truncated and says so")
    @SuppressWarnings("unchecked")
    void truncated() {
        List<HealthTurn> many = new ArrayList<>();
        IntStream.range(0, HealthTurnGdprParticipant.MAX_EXPORTED_TURNS + 1).forEach(i -> many.add(row(i)));
        when(store.findByUserId("heavy", HealthTurnGdprParticipant.MAX_EXPORTED_TURNS + 1)).thenReturn(many);
        Map<String, Object> section = (Map<String, Object>) participant.export("heavy");
        assertEquals(HealthTurnGdprParticipant.MAX_EXPORTED_TURNS, ((List<?>) section.get("turns")).size());
        assertTrue((Boolean) section.get(UserDataExport.SECTION_TRUNCATED));
        verify(store, never()).deleteByUserIdReturningDays(anyString());
    }
}
