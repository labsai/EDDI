/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.mongo;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The attachment indexes are built at startup, on MongoDB only — not on the
 * first request that happens to touch an attachment.
 */
class GridFsIndexInitializerTest {

    @SuppressWarnings("unchecked")
    private final Instance<GridFsAttachmentStore> instance = mock(Instance.class);
    private final GridFsAttachmentStore store = mock(GridFsAttachmentStore.class);

    @Test
    void mongoStartupBuildsTheIndexes() {
        when(instance.get()).thenReturn(store);

        new GridFsIndexInitializer(instance, "mongodb").onStart(mock(StartupEvent.class));

        verify(store).ensureIndexes();
    }

    @Test
    void postgresStartupNeverTouchesGridFs() {
        new GridFsIndexInitializer(instance, "postgres").onStart(mock(StartupEvent.class));

        verify(instance, never()).get();
    }

    @Test
    void anIndexFailureDoesNotStopStartup() {
        when(instance.get()).thenReturn(store);
        doThrow(new IllegalStateException("unreachable")).when(store).ensureIndexes();

        assertDoesNotThrow(() -> new GridFsIndexInitializer(instance, "mongodb").onStart(mock(StartupEvent.class)));
    }
}
