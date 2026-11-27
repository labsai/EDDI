/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.engine.health.HealthAdminGdprParticipant.ExportedLink;
import ai.labs.eddi.engine.health.HealthAdminGdprParticipant.ExportedProfile;
import ai.labs.eddi.engine.health.model.HealthProfile;
import ai.labs.eddi.engine.health.model.HealthReportLink;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("HealthAdminGdprParticipant — erasure blanks, export lists without tokens")
class HealthAdminGdprParticipantTest {

    private final IHealthProfileStore profiles = mock(IHealthProfileStore.class);
    private final IHealthReportLinkStore links = mock(IHealthReportLinkStore.class);
    private final HealthProfiles cache = mock(HealthProfiles.class);
    private final HealthAdminGdprParticipant participant = new HealthAdminGdprParticipant(profiles, links, cache);

    @Test
    @DisplayName("export: the profiles saved last and the links created — ids, agents and dates, never a token hash")
    @SuppressWarnings("unchecked")
    void export() {
        when(profiles.listByAuthor("alice")).thenReturn(List.of(new HealthProfile("a", "sales", null, null, null, null, null, true,
                "2026-11-10T12:00:00Z", "alice")));
        when(links.listByCreator("alice")).thenReturn(List.of(new HealthReportLink("r-1", "a", "f".repeat(64), List.of("reliability"), "30d",
                Instant.parse("2026-11-01T00:00:00Z"), "alice", Instant.parse("2026-12-01T00:00:00Z"))));

        Map<String, Object> out = (Map<String, Object>) participant.export("alice");

        assertEquals(List.of(new ExportedProfile("a", "2026-11-10T12:00:00Z")), out.get("healthProfiles"));
        assertEquals(List.of(new ExportedLink("r-1", "a", List.of("reliability"), "30d", "2026-11-01T00:00:00Z", "2026-12-01T00:00:00Z")),
                out.get("reportLinks"));
        assertFalse(out.toString().contains("ffff"), "no token hash");
    }

    @Test
    @DisplayName("export: null for someone with neither; erasure blanks both and clears the profile cache")
    void emptyAndErase() {
        when(profiles.listByAuthor("bob")).thenReturn(List.of());
        when(links.listByCreator("bob")).thenReturn(List.of());
        assertNull(participant.export("bob"));

        when(profiles.clearAuthor("alice")).thenReturn(1L);
        when(links.clearCreator("alice")).thenReturn(2L);
        assertEquals(3, participant.erase("alice"));
        verify(cache).invalidateAll();
    }
}
