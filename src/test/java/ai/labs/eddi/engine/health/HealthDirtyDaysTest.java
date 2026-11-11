/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("HealthDirtyDays — debounced dirty marks")
class HealthDirtyDaysTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 10);

    private IHealthDirtyDayStore store;
    private HealthDirtyDays dirty;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        store = mock(IHealthDirtyDayStore.class);
        Instance<IHealthDirtyDayStore> instance = mock(Instance.class);
        when(instance.get()).thenReturn(store);
        dirty = new HealthDirtyDays(instance, HealthSettings.defaults(), Clock.fixed(Instant.parse("2026-11-10T12:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    @DisplayName("a thousand turns of one agent-day become one mark at the next flush")
    @SuppressWarnings("unchecked")
    void debounces() {
        for (int i = 0; i < 1_000; i++) {
            dirty.note("agent", Set.of(TODAY));
        }
        dirty.note("agent", Set.of(TODAY.minusDays(1)));
        dirty.note("other", Set.of(TODAY));
        dirty.flush();

        ArgumentCaptor<Collection<LocalDate>> days = ArgumentCaptor.forClass(Collection.class);
        verify(store).mark(eq("agent"), days.capture());
        assertEquals(Set.of(TODAY, TODAY.minusDays(1)), Set.copyOf(days.getValue()));
        verify(store).mark(eq("other"), any());
        assertEquals(0, dirty.pendingCount());

        dirty.flush();
        verify(store, times(2)).mark(any(), any());
    }

    @Test
    @DisplayName("a failed flush keeps the days for the next one")
    void keepsOnFailure() {
        doThrow(new HealthStoreException("down", null)).when(store).mark(any(), any());
        dirty.note("agent", Set.of(TODAY));
        dirty.flush();
        assertEquals(1, dirty.pendingCount());
    }

    @Test
    @DisplayName("markRange marks every day at once, but never one older than the turn retention")
    @SuppressWarnings("unchecked")
    void markRange() {
        assertEquals(3, dirty.markRange("agent", TODAY.minusDays(2), TODAY));
        ArgumentCaptor<Collection<LocalDate>> days = ArgumentCaptor.forClass(Collection.class);
        verify(store).mark(eq("agent"), days.capture());
        assertEquals(List.of(TODAY.minusDays(2), TODAY.minusDays(1), TODAY), List.copyOf(days.getValue()));

        assertEquals(91, dirty.markRange("agent", TODAY.minusDays(400), TODAY), "today and the 90 retained days before it");
        assertEquals(0, dirty.markRange("agent", TODAY, TODAY.minusDays(1)));
        dirty.note(null, Set.of(TODAY));
        dirty.note("agent", null);
        verify(store, never()).mark(eq(null), any());
    }
}
