/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@DisplayName("HitlToolJournalMaintenance")
class HitlToolJournalMaintenanceTest {

    @Test
    @DisplayName("each pass asks the journal store to reconcile its retention index")
    void reconcilesEachPass() {
        IHitlToolJournalStore store = mock(IHitlToolJournalStore.class);
        new HitlToolJournalMaintenance(store).reconcile();
        verify(store).reconcileRetention();
    }

    @Test
    @DisplayName("a failing reconcile (database unreachable) does not escape the scheduled job")
    void failureIsContained() {
        IHitlToolJournalStore store = mock(IHitlToolJournalStore.class);
        doThrow(new IllegalStateException("db down")).when(store).reconcileRetention();
        assertDoesNotThrow(() -> new HitlToolJournalMaintenance(store).reconcile());
    }
}
