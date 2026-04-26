/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.mongo;

import ai.labs.eddi.datastore.IResourceStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.*;

/**
 * Tests that AbstractMongoResourceStore correctly delegates all CRUD operations
 * to the underlying HistorizedResourceStore.
 */
@SuppressWarnings("deprecation")
class AbstractMongoResourceStoreTest {

    private HistorizedResourceStore<String> historizedStore;
    private AbstractMongoResourceStore<String> store;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        historizedStore = mock(HistorizedResourceStore.class);
        // Use the second constructor that accepts a pre-built HistorizedResourceStore
        store = new AbstractMongoResourceStore<>(historizedStore) {
        };
    }

    @Test
    void shouldDelegateReadIncludingDeleted() throws Exception {
        when(historizedStore.readIncludingDeleted("id1", 1)).thenReturn("result");

        String result = store.readIncludingDeleted("id1", 1);

        assertEquals("result", result);
        verify(historizedStore).readIncludingDeleted("id1", 1);
    }

    @Test
    void shouldDelegateCreate() throws Exception {
        IResourceStore.IResourceId mockId = mock(IResourceStore.IResourceId.class);
        when(historizedStore.create("content")).thenReturn(mockId);

        IResourceStore.IResourceId result = store.create("content");

        assertSame(mockId, result);
        verify(historizedStore).create("content");
    }

    @Test
    void shouldDelegateRead() throws Exception {
        when(historizedStore.read("id1", 2)).thenReturn("data");

        String result = store.read("id1", 2);

        assertEquals("data", result);
        verify(historizedStore).read("id1", 2);
    }

    @Test
    void shouldDelegateUpdate() throws Exception {
        when(historizedStore.update("id1", 1, "updated")).thenReturn(2);

        Integer newVersion = store.update("id1", 1, "updated");

        assertEquals(2, newVersion);
        verify(historizedStore).update("id1", 1, "updated");
    }

    @Test
    void shouldDelegateDelete() throws Exception {
        store.delete("id1", 1);

        verify(historizedStore).delete("id1", 1);
    }

    @Test
    void shouldDelegateDeleteAllPermanently() {
        store.deleteAllPermanently("id1");

        verify(historizedStore).deleteAllPermanently("id1");
    }

    @Test
    void shouldDelegateGetCurrentResourceId() throws Exception {
        IResourceStore.IResourceId mockId = mock(IResourceStore.IResourceId.class);
        when(historizedStore.getCurrentResourceId("id1")).thenReturn(mockId);

        IResourceStore.IResourceId result = store.getCurrentResourceId("id1");

        assertSame(mockId, result);
        verify(historizedStore).getCurrentResourceId("id1");
    }
}
