/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.oversight.analytics;

import ai.labs.eddi.engine.audit.IAuditStore;
import ai.labs.eddi.engine.audit.model.AuditEntry;
import ai.labs.eddi.engine.hitl.audit.HitlApprovalAudit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static ai.labs.eddi.engine.hitl.oversight.OversightFixtures.NOW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

@DisplayName("ApprovalBackfillService")
class ApprovalBackfillServiceTest {

    private IAuditStore auditStore;
    private ApprovalDecisionProjector projector;
    private ApprovalBackfillService backfill;

    @BeforeEach
    void setUp() {
        auditStore = mock(IAuditStore.class, withSettings().stubOnly());
        projector = mock(ApprovalDecisionProjector.class, withSettings().stubOnly());
        when(projector.project(any(), anyBoolean())).thenReturn(2);
        backfill = new ApprovalBackfillService(auditStore, projector);
    }

    private static AuditEntry entry(String id, Instant at) {
        return new AuditEntry(id, "conv", "agent", 1, "user", "production", -1, HitlApprovalAudit.TASK_ID, "hitl", -1, 0L, Map.of(), null,
                null, null, List.of(), 0.0, at, null, null);
    }

    @Test
    @DisplayName("entries newer than the window are skipped, the first older one ends the rebuild, and the rest are stored")
    void windowBounds() {
        Instant from = NOW.minusSeconds(1000);
        Instant to = NOW.minusSeconds(100);
        when(auditStore.getEntriesByTask(eq(HitlApprovalAudit.TASK_ID), isNull(), eq(0), eq(ApprovalBackfillService.PAGE_SIZE)))
                .thenReturn(List.of(entry("newer", NOW), entry("atTo", to), entry("inside", NOW.minusSeconds(500)),
                        entry("older", NOW.minusSeconds(2000)), entry("never read", NOW.minusSeconds(3000))));

        ApprovalBackfillService.Result result = backfill.rebuild(from, to);

        assertEquals(4, result.entriesRead(), "reading stops at the first entry before the window");
        assertEquals(2, result.unitsStored(), "only the entry inside the window is projected");
        assertFalse(result.truncated());
        assertFalse(backfill.isRunning());
    }

    @Test
    @DisplayName("an entry without a timestamp is projected, and an open window projects everything")
    void openWindow() {
        when(auditStore.getEntriesByTask(any(), any(), eq(0), anyInt()))
                .thenReturn(List.of(entry("a", null), entry("b", NOW), entry("c", NOW.minusSeconds(10_000_000))));

        ApprovalBackfillService.Result result = backfill.rebuild(null, null);

        assertEquals(3, result.entriesRead());
        assertEquals(6, result.unitsStored());
        assertFalse(result.truncated());
    }

    @Test
    @DisplayName("a full page leads to the next page; a short page ends the rebuild")
    void paging() {
        List<AuditEntry> full = new ArrayList<>();
        for (int i = 0; i < ApprovalBackfillService.PAGE_SIZE; i++) {
            full.add(entry("e" + i, NOW));
        }
        when(auditStore.getEntriesByTask(any(), any(), eq(0), anyInt())).thenReturn(full);
        when(auditStore.getEntriesByTask(any(), any(), eq(ApprovalBackfillService.PAGE_SIZE), anyInt())).thenReturn(List.of(entry("last", NOW)));

        ApprovalBackfillService.Result result = backfill.rebuild(null, null);

        assertEquals(ApprovalBackfillService.PAGE_SIZE + 1, result.entriesRead());
        assertFalse(result.truncated());
    }

    @Test
    @DisplayName("an unending ledger is read up to the page limit and reported as truncated")
    void pageLimit() {
        List<AuditEntry> full = new ArrayList<>();
        AuditEntry shared = entry("e", NOW);
        for (int i = 0; i < ApprovalBackfillService.PAGE_SIZE; i++) {
            full.add(shared);
        }
        when(auditStore.getEntriesByTask(any(), any(), anyInt(), anyInt())).thenReturn(full);

        ApprovalBackfillService.Result result = backfill.rebuild(null, null);

        assertTrue(result.truncated());
        assertEquals((long) ApprovalBackfillService.MAX_PAGES * ApprovalBackfillService.PAGE_SIZE, result.entriesRead());
    }

    @Test
    @DisplayName("an audit store that cannot list by task reports a truncated, empty rebuild and frees the lock")
    void unsupportedStore() {
        when(auditStore.getEntriesByTask(any(), any(), anyInt(), anyInt())).thenThrow(new UnsupportedOperationException("no listing"));

        ApprovalBackfillService.Result result = backfill.rebuild(null, null);

        assertEquals(0, result.entriesRead());
        assertTrue(result.truncated());
        assertFalse(backfill.isRunning());
    }

    @Test
    @DisplayName("a rebuild started while another runs on this node is refused with null")
    void oneAtATime() {
        AtomicReference<ApprovalBackfillService.Result> inner = new AtomicReference<>();
        AtomicReference<Boolean> runningInside = new AtomicReference<>();
        when(auditStore.getEntriesByTask(any(), any(), anyInt(), anyInt())).thenAnswer(inv -> {
            runningInside.set(backfill.isRunning());
            inner.set(backfill.rebuild(null, null));
            return List.of();
        });

        ApprovalBackfillService.Result outer = backfill.rebuild(null, null);

        assertTrue(runningInside.get());
        assertNull(inner.get());
        assertEquals(0, outer.entriesRead());
    }
}
