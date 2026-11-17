/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.engine.gdpr.UserDataExport;
import ai.labs.eddi.engine.health.model.HealthEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("HealthEventGdprParticipant — matched business events are erased and exported with their user (H5)")
class HealthEventGdprParticipantTest {

    static HealthEvent matched(String id) {
        Instant at = Instant.parse("2026-11-10T10:00:00Z");
        return new HealthEvent(id, "int", "agent", "order.placed", "orderId#ab", null, "c1", 9.5, at, at, LocalDate.of(2026, 11, 10),
                HealthEvent.MATCHED, 0, 3, "api", "user", HealthOutcomes.EVIDENCE_EXTERNAL_EVENT, at);
    }

    @Test
    @DisplayName("erase deletes the user's events; a blank user erases nothing")
    void erase() {
        IHealthEventStore store = mock(IHealthEventStore.class);
        when(store.deleteByUserId("user")).thenReturn(4L);
        HealthEventGdprParticipant participant = new HealthEventGdprParticipant(store);
        assertEquals(4L, participant.erase("user"));
        assertEquals(0L, participant.erase(" "));
        verify(store, never()).deleteByUserId(" ");
        assertEquals("agentHealthEvents", participant.name());
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("export lists the events — ref as stored, no user id field — and says when it was cut")
    void export() {
        IHealthEventStore store = mock(IHealthEventStore.class);
        when(store.findByUserId("user", HealthEventGdprParticipant.MAX_EXPORTED_EVENTS + 1)).thenReturn(List.of(matched("e1")));
        HealthEventGdprParticipant participant = new HealthEventGdprParticipant(store);
        Map<String, Object> section = (Map<String, Object>) participant.export("user");
        List<Map<String, Object>> events = (List<Map<String, Object>>) section.get("events");
        assertEquals("orderId#ab", events.get(0).get("ref"));
        assertEquals("order.placed", events.get(0).get("eventType"));
        assertFalse(events.get(0).containsKey("userId"));
        assertEquals(false, section.get(UserDataExport.SECTION_TRUNCATED));

        List<HealthEvent> many = new ArrayList<>();
        for (int i = 0; i <= HealthEventGdprParticipant.MAX_EXPORTED_EVENTS; i++) {
            many.add(matched("e" + i));
        }
        when(store.findByUserId("big", HealthEventGdprParticipant.MAX_EXPORTED_EVENTS + 1)).thenReturn(many);
        Map<String, Object> big = (Map<String, Object>) participant.export("big");
        assertTrue((Boolean) big.get(UserDataExport.SECTION_TRUNCATED));
        assertEquals(HealthEventGdprParticipant.MAX_EXPORTED_EVENTS, ((List<?>) big.get("events")).size());

        when(store.findByUserId(anyString(), anyInt())).thenReturn(List.of());
        assertNull(participant.export("nobody"));
        assertNull(participant.export(null));
    }
}
