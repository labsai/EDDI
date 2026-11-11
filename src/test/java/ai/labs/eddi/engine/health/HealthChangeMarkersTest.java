/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.engine.health.model.HealthMarker;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("HealthChangeMarkers, HealthSchedules and retention of the H2b stores")
class HealthChangeMarkersTest {

    private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");

    private IHealthMarkerStore store;
    private HealthChangeMarkers markers;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        store = mock(IHealthMarkerStore.class);
        Instance<IHealthMarkerStore> instance = mock(Instance.class);
        when(instance.get()).thenReturn(store);
        markers = new HealthChangeMarkers(instance, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("a deployment marker belongs to the agent and names version and environment")
    void deployment() {
        markers.deployed("agent", 8, "production");
        ArgumentCaptor<HealthMarker> marker = ArgumentCaptor.forClass(HealthMarker.class);
        verify(store).append(marker.capture());
        assertEquals("agent", marker.getValue().agentId());
        assertEquals(HealthMarker.KIND_DEPLOYMENT, marker.getValue().kind());
        assertEquals("v8 deployed (production)", marker.getValue().label());
        assertEquals(8, marker.getValue().version());
        assertEquals(NOW, marker.getValue().at());
        assertTrue(marker.getValue().id().startsWith("m-"));
        markers.deployed(null, 1, "test");
        verify(store, times(1)).append(any());
    }

    @Test
    @DisplayName("global variable and snippet markers name nothing, carry their tenant, and coalesce per kind, tenant and hour")
    void deploymentWide() {
        markers.globalVariableChanged("t.team.1");
        markers.globalVariableChanged("t.team.1");
        markers.globalVariableChanged(null);
        markers.snippetChanged();
        ArgumentCaptor<HealthMarker> marker = ArgumentCaptor.forClass(HealthMarker.class);
        verify(store, times(4)).append(marker.capture());
        HealthMarker first = marker.getAllValues().get(0);
        assertNull(first.agentId());
        assertEquals("t.team.1", first.tenantId());
        assertEquals(HealthMarker.KIND_GLOBAL_VARIABLE_CHANGE, first.kind());
        assertEquals(first.id(), marker.getAllValues().get(1).id(), "same kind, tenant and hour: one marker");
        assertEquals(Instant.parse("2026-11-10T12:00:00Z"), first.at());
        assertEquals(HealthMarker.DEFAULT_TENANT, marker.getAllValues().get(2).tenantId());
        assertEquals(HealthMarker.KIND_SNIPPET_CHANGE, marker.getAllValues().get(3).kind());
        assertEquals("Prompt snippets changed", marker.getAllValues().get(3).label());
    }

    @Test
    @DisplayName("a failing store never fails the change being recorded")
    void neverThrows() {
        doThrow(new HealthStoreException("down", null)).when(store).append(any());
        assertDoesNotThrow(() -> markers.deployed("agent", 1, "production"));
    }

    @Test
    @DisplayName("the rollup schedule is recognised by its marker; a client may claim neither its name nor its marker")
    void schedules() {
        assertTrue(HealthSchedules.isRollup(HealthSchedules.rollupMetadata()));
        assertFalse(HealthSchedules.isRollup(Map.of("healthType", "other")));
        ScheduleConfiguration named = new ScheduleConfiguration();
        named.setName(" Health-Rollup ");
        assertTrue(HealthSchedules.claims(named));
        ScheduleConfiguration marked = new ScheduleConfiguration();
        marked.setMetadata(Map.of(HealthSchedules.METADATA_TYPE_KEY, "x"));
        assertTrue(HealthSchedules.claims(marked));
        assertTrue(HealthSchedules.isSystem(marked));
        assertFalse(HealthSchedules.claims(new ScheduleConfiguration()));
    }

    @Test
    @DisplayName("the daily sweep also deletes daily rows and markers past the daily retention")
    void retention() {
        IHealthTurnStore turns = mock(IHealthTurnStore.class);
        IHealthDailyStore daily = mock(IHealthDailyStore.class);
        doThrow(new HealthStoreException("down", null)).when(turns).deleteOlderThan(any());
        new HealthTurnRetention(turns, null, daily, store, HealthSettings.defaults(), new HealthMetrics(new SimpleMeterRegistry()),
                Clock.fixed(NOW, ZoneOffset.UTC)).sweep();
        verify(daily).deleteOlderThan(LocalDate.of(2026, 11, 10).minusDays(730));
        verify(store).deleteOlderThan(LocalDate.of(2026, 11, 10).minusDays(730).atStartOfDay(ZoneOffset.UTC).toInstant());
        verify(store, never()).append(any());
    }
}
