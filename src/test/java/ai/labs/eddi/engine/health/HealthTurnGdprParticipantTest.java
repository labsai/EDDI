/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.engine.gdpr.UserDataExport;
import ai.labs.eddi.engine.gdpr.erasure.ErasureGuard;
import ai.labs.eddi.engine.gdpr.erasure.TestErasureGuards;
import ai.labs.eddi.engine.health.model.HealthDeletedRows;
import ai.labs.eddi.engine.health.model.HealthFailure;
import ai.labs.eddi.engine.health.model.HealthTurn;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
    @DisplayName("the days the delete returned are marked")
    void daysKnown() {
        HealthDirtyDays dirty = dirtyDays();
        HealthDeletedRows deleted = new HealthDeletedRows(3L, Map.of("agent", Set.of(LocalDate.of(2026, 11, 10))));
        when(store.deleteByUserIdReturningDays("user-1")).thenReturn(deleted);

        assertEquals(3L, participant.erase("user-1"));
        verify(dirty).markErasedRows(Set.of(), deleted);
    }

    @Test
    @DisplayName("unknown days: the erasure still succeeds, and markErasedRows gets no agent (it warns)")
    void unknownDays() {
        HealthDirtyDays dirty = dirtyDays();
        HealthDeletedRows unknown = new HealthDeletedRows(2L, null);
        when(store.deleteByUserIdReturningDays("user-1")).thenReturn(unknown);

        assertEquals(2L, participant.erase("user-1"));
        verify(dirty).markErasedRows(Set.of(), unknown);

        participant.dirtyDays = null;
        assertEquals(2L, participant.erase("user-1"));
    }

    @Test
    @DisplayName("with the erasure guard, erasure verifies: rows or markers left fail the step")
    void eraseVerifies() {
        ErasureGuard guard = TestErasureGuards.withoutWriters();
        HealthTurnGdprParticipant guarded = new HealthTurnGdprParticipant(store, guard);
        when(store.deleteByUserIdReturningDays("user-1")).thenReturn(new HealthDeletedRows(2L, Map.of()));
        when(store.countByUserId("user-1")).thenReturn(0L);
        assertEquals(2L, guarded.erase("user-1"));

        when(store.deleteByUserIdReturningDays("user-2")).thenReturn(new HealthDeletedRows(0L, Map.of()));
        when(store.countByUserId("user-2")).thenReturn(1L);
        assertThrows(IllegalStateException.class, () -> guarded.erase("user-2"));
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
