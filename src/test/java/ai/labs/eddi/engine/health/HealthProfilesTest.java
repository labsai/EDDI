/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.engine.health.HealthProfiles.InvalidProfileException;
import ai.labs.eddi.engine.health.model.HealthProfile;
import ai.labs.eddi.engine.health.model.HealthProfile.Limits;
import ai.labs.eddi.engine.health.model.HealthProfile.Slo;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("HealthProfiles — validate, store, mark, recount, cache")
class HealthProfilesTest {

    static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");

    private IHealthProfileStore store;
    private HealthChangeMarkers markers;
    private HealthDirtyDays dirty;
    private HealthProfiles profiles;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        store = mock(IHealthProfileStore.class);
        markers = mock(HealthChangeMarkers.class);
        dirty = mock(HealthDirtyDays.class);
        Instance<HealthDirtyDays> dirtyDays = mock(Instance.class);
        when(dirtyDays.isResolvable()).thenReturn(true);
        when(dirtyDays.get()).thenReturn(dirty);
        when(store.read(anyString())).thenReturn(Optional.empty());
        profiles = new HealthProfiles(store, markers, dirtyDays, HealthSettings.defaults(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    static HealthProfile body(Slo slo) {
        return new HealthProfile(null, "sales", null, Map.of("resolutionRate", new Limits(0.5, 0.2)), null, slo, null, null, null, null);
    }

    @Test
    @DisplayName("an invalid profile is refused with every violation and nothing is written")
    void invalid() {
        HealthProfile bad = new HealthProfile(null, "retail", null, Map.of("resolutionRate", new Limits(0.2, 0.5)), null, null, null, null, null,
                null);
        InvalidProfileException e = assertThrows(InvalidProfileException.class, () -> profiles.save("a", bad, "alice", "support"));
        assertEquals(2, e.violations().size());
        verify(store, never()).write(any());
        verify(markers, never()).profileChanged(anyString());
    }

    @Test
    @DisplayName("a save stores the body with agent, time and author, leaves a marker and answers the effective profile")
    void save() {
        when(store.read("a")).thenReturn(Optional.empty()).thenReturn(Optional.of(body(null).withMeta("a", true, NOW.toString(), "alice")));
        HealthProfile saved = profiles.save("a", body(null), "alice", "support");
        ArgumentCaptor<HealthProfile> written = ArgumentCaptor.forClass(HealthProfile.class);
        verify(store).write(written.capture());
        assertEquals("a", written.getValue().agentId());
        assertEquals("alice", written.getValue().updatedBy());
        assertEquals(NOW.toString(), written.getValue().updatedAt());
        assertEquals("sales", written.getValue().preset());
        verify(markers).profileChanged("a");
        verify(dirty, never()).markRange(anyString(), any(), any());
        assertEquals("sales", saved.preset());
    }

    @Test
    @DisplayName("a changed Apdex threshold recounts the retained days; an unchanged one does not")
    void apdexRecount() {
        profiles.save("a", body(new Slo(null, 2000L, null)), "alice", "support");
        verify(dirty).markRange("a", LocalDate.of(2026, 11, 10).minusDays(90), LocalDate.of(2026, 11, 10));
        when(store.read("a")).thenReturn(Optional.of(body(new Slo(null, 2000L, null)).withMeta("a", true, NOW.toString(), "alice")));
        profiles.invalidate("a");
        profiles.save("a", body(new Slo(0.995, 2000L, null)), "alice", "support");
        verify(dirty, times(1)).markRange(anyString(), any(), any());
        assertEquals(2000L, profiles.apdexTMs("a"));
        assertEquals(HealthSettings.defaults().apdexTMs(), profiles.apdexTMs("b"));
    }

    @Test
    @DisplayName("the rollup's Apdex T is read past the cache: a node that cached the old profile recounts under the new T")
    void apdexUncached() {
        when(store.read("a")).thenReturn(Optional.of(body(new Slo(null, 2000L, null)).withMeta("a", true, NOW.toString(), "alice")));
        assertEquals(2000L, profiles.apdexTMs("a"), "cached now");
        // Another node saves a new T; this node's cache still holds 2000.
        when(store.read("a")).thenReturn(Optional.of(body(new Slo(null, 3000L, null)).withMeta("a", true, NOW.toString(), "bob")));
        assertEquals(2000L, profiles.apdexTMs("a"), "the cached read is stale within the TTL");
        assertEquals(3000L, profiles.apdexTMsUncached("a"), "the rollup's read is not");
        assertEquals(3000L, profiles.apdexTMs("a"), "and it refreshes the cache");
        when(store.read("b")).thenThrow(new HealthStoreException("down", null));
        assertThrows(HealthStoreException.class, () -> profiles.apdexTMsUncached("b"), "a failed read fails the day instead of guessing T");
    }

    @Test
    @DisplayName("delete: a marker only when something was deleted")
    void delete() {
        when(store.delete("a")).thenReturn(true);
        assertTrue(profiles.delete("a"));
        verify(markers).profileChanged("a");
        assertFalse(profiles.delete("b"));
        verify(markers, never()).profileChanged("b");
    }

    @Test
    @DisplayName("view: defaults with stored=false when nothing is stored; reads are cached, a store failure falls back to the preset")
    void viewAndCache() {
        HealthProfile view = profiles.view("a", "task");
        assertFalse(view.stored());
        assertEquals("task", view.preset());
        profiles.stored("a");
        verify(store, times(1)).read("a");
        when(store.read("z")).thenThrow(new HealthStoreException("down", null));
        assertEquals("support", profiles.effective("z", "support").preset());
        when(store.readAll(any())).thenReturn(Map.of("c", body(null).withMeta("c", true, null, null)));
        assertEquals(List.of("c"), List.copyOf(profiles.storedAll(List.of("a", "c")).keySet()));
    }
}
