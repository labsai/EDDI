/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.engine.gdpr.UserDataExport;
import ai.labs.eddi.engine.health.model.HealthFailure;
import ai.labs.eddi.engine.health.model.HealthTurn;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    @Test
    @DisplayName("erasure deletes the user's rows; a blank user erases nothing")
    void erase() {
        when(store.deleteByUserId("user-1")).thenReturn(7L);
        assertEquals(7L, participant.erase("user-1"));
        assertEquals(0L, participant.erase(" "));
        assertEquals(0L, participant.erase(null));
        verify(store).deleteByUserId("user-1");
        assertEquals("agentHealthTurns", participant.name());
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
        verify(store, never()).deleteByUserId(anyString());
    }
}
