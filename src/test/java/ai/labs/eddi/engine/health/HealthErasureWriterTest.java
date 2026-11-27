/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.engine.health.model.HealthDeletedRows;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.model.ConversationListingSummary;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("HealthErasureWriter — Agent Health's async writer of the erasure guarantee")
class HealthErasureWriterTest {

    private final HealthTurnRecorder recorder = mock(HealthTurnRecorder.class);
    private final IHealthTurnStore store = mock(IHealthTurnStore.class);
    private final HealthErasureWriter writer = new HealthErasureWriter(recorder, store);

    @Test
    @DisplayName("removeRow deletes exactly the row its id names")
    void removeRow() {
        String rowId = HealthErasureWriter.rowId("65a1b2c3d4e5f6a7b8c9d0e1", "6f1c2d3e-aaaa-bbbb-cccc-0123456789ab");
        writer.removeRow(rowId);
        verify(store).deleteRow("65a1b2c3d4e5f6a7b8c9d0e1", "6f1c2d3e-aaaa-bbbb-cccc-0123456789ab");

        writer.removeRow(HealthErasureWriter.rowId("conv", "legacy-3"));
        verify(store).deleteRow("conv", "legacy-3");
    }

    @Test
    @DisplayName("an id that names no row is refused, so its removal stays pending")
    void malformedRowId() {
        assertThrows(IllegalArgumentException.class, () -> writer.removeRow("no-separator"));
        assertThrows(IllegalArgumentException.class, () -> writer.removeRow("|exec"));
        assertThrows(IllegalArgumentException.class, () -> writer.removeRow("conv|"));
        assertThrows(IllegalArgumentException.class, () -> writer.removeRow(null));
    }

    @Test
    @DisplayName("name, drain, conversation delete and count go to the recorder and the store")
    void delegates() throws Exception {
        assertEquals("agentHealthTurns", writer.name());
        when(recorder.awaitDrained(Duration.ofSeconds(1))).thenReturn(true);
        assertTrue(writer.awaitDrained(Duration.ofSeconds(1)));
        when(store.deleteConversationReturningDays("conv")).thenReturn(new HealthDeletedRows(3L, Map.of()));
        when(store.countByConversationId("conv")).thenReturn(0L);
        assertEquals(3L, writer.eraseConversation("conv"));
        assertEquals(0L, writer.countForConversation("conv"));
    }

    @Test
    @DisplayName("every delete marks the days of the deleted rows dirty, the conversation's agent the fallback for unknown days")
    void marksDeletedDays() throws Exception {
        HealthDirtyDays dirty = mock(HealthDirtyDays.class);
        IConversationMemoryStore conversations = mock(IConversationMemoryStore.class);
        when(conversations.loadListingSummaries(List.of("conv")))
                .thenReturn(Map.of("conv", new ConversationListingSummary("conv", "user", null, null, "agent", 1, 2)));
        HealthErasureWriter marking = new HealthErasureWriter(recorder, store, instance(dirty), instance(conversations));

        HealthDeletedRows unknown = new HealthDeletedRows(2L, null);
        when(store.deleteConversationReturningDays("conv")).thenReturn(unknown);
        assertEquals(2L, marking.eraseConversation("conv"));
        verify(dirty).markDeletedRows("conv", List.of("agent"), unknown);

        // Known days need no agent lookup.
        HealthDeletedRows known = new HealthDeletedRows(1L, Map.of("agent", Set.of(LocalDate.of(2026, 11, 9))));
        when(store.deleteConversationReturningDays("known")).thenReturn(known);
        marking.eraseConversation("known");
        verify(conversations, never()).loadListingSummaries(List.of("known"));
        verify(dirty).markDeletedRows("known", List.of(), known);

        Map<String, Set<LocalDate>> days = Map.of("agent", Set.of(LocalDate.of(2026, 11, 10)));
        when(store.agentDaysOfConversation("conv")).thenReturn(days);
        when(store.deleteRow("conv", "exec")).thenReturn(1L);
        marking.removeRow(HealthErasureWriter.rowId("conv", "exec"));
        verify(dirty).markDeletedRows("conv", List.of(), new HealthDeletedRows(1L, days));

        // Nothing deleted, nothing marked.
        when(store.deleteConversationReturningDays("empty")).thenReturn(new HealthDeletedRows(0L, Map.of()));
        assertEquals(0L, marking.eraseConversation("empty"));
        verify(dirty, never()).markDeletedRows(eq("empty"), any(), any());
    }

    @Test
    @DisplayName("a row removal whose day read fails still marks its days, through the conversation's agent")
    void removeRowWithUnreadableDays() throws Exception {
        HealthDirtyDays dirty = mock(HealthDirtyDays.class);
        IConversationMemoryStore conversations = mock(IConversationMemoryStore.class);
        when(conversations.loadListingSummaries(List.of("conv")))
                .thenReturn(Map.of("conv", new ConversationListingSummary("conv", "user", null, null, "agent", 1, 2)));
        HealthErasureWriter marking = new HealthErasureWriter(recorder, store, instance(dirty), instance(conversations));
        when(store.agentDaysOfConversation("conv")).thenThrow(new HealthStoreException("store down", null));
        when(store.deleteRow("conv", "exec")).thenReturn(1L);

        marking.removeRow(HealthErasureWriter.rowId("conv", "exec"));

        verify(dirty).markDeletedRows("conv", List.of("agent"), new HealthDeletedRows(1L, null));

        // Neither days nor agent: still handed over, so markDeletedRows logs the WARN.
        when(store.agentDaysOfConversation("gone")).thenThrow(new HealthStoreException("store down", null));
        when(store.deleteRow("gone", "exec")).thenReturn(1L);
        marking.removeRow(HealthErasureWriter.rowId("gone", "exec"));
        verify(dirty).markDeletedRows("gone", List.of(), new HealthDeletedRows(1L, null));
    }

    @Test
    @DisplayName("session outcomes are rows of the same writer: removed by their own id, erased and counted with the conversation, drained")
    void sessionOutcomes() throws Exception {
        IHealthOutcomeStore outcomes = mock(IHealthOutcomeStore.class);
        HealthOutcomeEvaluator evaluator = mock(HealthOutcomeEvaluator.class);
        HealthErasureWriter both = new HealthErasureWriter(recorder, store, null, null, instance(outcomes), instance(evaluator));

        String id = HealthErasureWriter.sessionRowId("conv", 3);
        assertEquals("session:conv|3", id);
        both.removeRow(id);
        verify(outcomes).deleteSessions("conv", List.of(3));
        verify(store, never()).deleteRow(anyString(), anyString());
        assertThrows(IllegalArgumentException.class, () -> both.removeRow("session:conv|x"));
        assertThrows(IllegalArgumentException.class, () -> both.removeRow("session:conv"));

        when(store.deleteConversationReturningDays("conv")).thenReturn(new HealthDeletedRows(2L, null));
        when(outcomes.deleteByConversationId("conv")).thenReturn(3L);
        assertEquals(5, both.eraseConversation("conv"));
        when(store.countByConversationId("conv")).thenReturn(1L);
        when(outcomes.countByConversationId("conv")).thenReturn(2L);
        assertEquals(3, both.countForConversation("conv"));
        // A conversation without turn rows still has its sessions erased.
        when(store.deleteConversationReturningDays("only-sessions")).thenReturn(null);
        when(outcomes.deleteByConversationId("only-sessions")).thenReturn(1L);
        assertEquals(1, both.eraseConversation("only-sessions"));

        when(recorder.awaitDrained(any())).thenReturn(true);
        when(evaluator.awaitDrained(any())).thenReturn(false);
        assertFalse(both.awaitDrained(Duration.ofSeconds(1)), "an evaluation in flight keeps the barrier open");
        when(evaluator.awaitDrained(any())).thenReturn(true);
        assertTrue(both.awaitDrained(Duration.ofSeconds(1)));
        when(recorder.awaitDrained(any())).thenReturn(false);
        assertFalse(both.awaitDrained(Duration.ofSeconds(1)));
    }

    @Test
    @DisplayName("a session removal without an outcome store fails, so it stays pending")
    void sessionRemovalWithoutStore() {
        assertThrows(IllegalStateException.class, () -> writer.removeRow(HealthErasureWriter.sessionRowId("conv", 0)));
    }

    @SuppressWarnings("unchecked")
    private static <T> Instance<T> instance(T bean) {
        Instance<T> instance = mock(Instance.class);
        when(instance.isResolvable()).thenReturn(true);
        when(instance.get()).thenReturn(bean);
        return instance;
    }
}
