/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl;

import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.model.PendingApprovalSummary;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("pending HITL approval gauges")
class HitlPendingMetricsTest {

    private static final Instant NOW = Instant.parse("2026-10-03T12:00:00Z");

    private IConversationMemoryStore store;
    private SimpleMeterRegistry registry;
    private HitlPendingMetrics metrics;

    @BeforeEach
    void setUp() {
        store = mock(IConversationMemoryStore.class);
        registry = new SimpleMeterRegistry();
        metrics = new HitlPendingMetrics(store, registry);
        metrics.registerGauges();
    }

    private double gauge(String name) {
        return registry.get(name).gauge().value();
    }

    private static PendingApprovalSummary pausedAt(Instant at) {
        return new PendingApprovalSummary("c-" + at.toEpochMilli(), "agent", "user", at, "approval", null);
    }

    @Test
    @DisplayName("unknown, not zero, before the first refresh")
    void unknownBeforeFirstRefresh() {
        assertTrue(Double.isNaN(gauge("eddi.hitl.pending")));
        assertTrue(Double.isNaN(gauge("eddi.hitl.pending.oldest_age_seconds")));
    }

    @Test
    @DisplayName("counts what is waiting and how long the oldest has waited")
    void countsBacklogAndOldestAge() throws Exception {
        when(store.findPendingApprovalSummaries(HitlPendingMetrics.SCAN_LIMIT)).thenReturn(List.of(
                pausedAt(NOW.minusSeconds(30)),
                pausedAt(NOW.minusSeconds(3600)),
                // A summary without a pause time counts, but cannot set the age.
                new PendingApprovalSummary("c-x", "agent", "user", null, "approval", null)));

        metrics.refresh(NOW);

        assertEquals(3.0, gauge("eddi.hitl.pending"));
        assertEquals(3600.0, gauge("eddi.hitl.pending.oldest_age_seconds"));
    }

    @Test
    @DisplayName("an empty inbox reads zero for both")
    void emptyInboxIsZero() throws Exception {
        when(store.findPendingApprovalSummaries(HitlPendingMetrics.SCAN_LIMIT)).thenReturn(List.of());

        metrics.refresh(NOW);

        assertEquals(0.0, gauge("eddi.hitl.pending"));
        assertEquals(0.0, gauge("eddi.hitl.pending.oldest_age_seconds"));
    }

    @Test
    @DisplayName("a failed read goes back to unknown instead of keeping a stale number")
    void failedReadIsUnknown() throws Exception {
        when(store.findPendingApprovalSummaries(HitlPendingMetrics.SCAN_LIMIT))
                .thenReturn(List.of(pausedAt(NOW.minusSeconds(10))))
                .thenThrow(new IResourceStore.ResourceStoreException("store down"));

        metrics.refresh(NOW);
        assertEquals(1.0, gauge("eddi.hitl.pending"));

        metrics.refresh(NOW);
        assertTrue(Double.isNaN(gauge("eddi.hitl.pending")));
        assertTrue(Double.isNaN(gauge("eddi.hitl.pending.oldest_age_seconds")));
    }
}
