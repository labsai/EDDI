/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.*;
import org.mockito.InOrder;

import static org.mockito.Mockito.*;

/**
 * Tests that HistorizedResourceStore correctly manages versioning lifecycle
 * (create, read, update, delete) against a mocked IResourceStorage.
 */
@SuppressWarnings("unchecked")
class HistorizedResourceStoreTest {

    private IResourceStorage<String> storage;
    private HistorizedResourceStore<String> store;

    @BeforeEach
    void setUp() {
        storage = mock(IResourceStorage.class);
        store = new HistorizedResourceStore<>(storage);
    }

    @Test
    void shouldCreateResourceViaStorage() throws Exception {
        IResourceStorage.IResource<String> mockResource = mock(IResourceStorage.IResource.class);
        when(storage.newResource("content")).thenReturn(mockResource);
        when(mockResource.getId()).thenReturn("id1");
        when(mockResource.getVersion()).thenReturn(1);

        IResourceStore.IResourceId result = store.create("content");

        verify(storage).newResource("content");
        verify(storage).store(mockResource);
        assertNotNull(result);
    }

    @Test
    void shouldReadCurrentVersion() throws Exception {
        IResourceStorage.IResource<String> mockResource = mock(IResourceStorage.IResource.class);
        when(storage.read("id1", 1)).thenReturn(mockResource);
        when(mockResource.getData()).thenReturn("data");

        String result = store.read("id1", 1);

        assertEquals("data", result);
        verify(storage).read("id1", 1);
        verify(storage, never()).readHistory(anyString(), anyInt());
    }

    @Test
    void shouldReadFromHistoryWhenCurrentNotFound() throws Exception {
        when(storage.read("id1", 1)).thenReturn(null);

        IResourceStorage.IHistoryResource<String> historyResource = mock(IResourceStorage.IHistoryResource.class);
        when(storage.readHistory("id1", 1)).thenReturn(historyResource);
        when(historyResource.isDeleted()).thenReturn(false);
        when(historyResource.getData()).thenReturn("history-data");

        String result = store.read("id1", 1);

        assertEquals("history-data", result);
        verify(storage).readHistory("id1", 1);
    }

    @Test
    void shouldThrowNotFoundWhenDeletedInHistory() {
        when(storage.read("id1", 1)).thenReturn(null);

        IResourceStorage.IHistoryResource<String> historyResource = mock(IResourceStorage.IHistoryResource.class);
        when(storage.readHistory("id1", 1)).thenReturn(historyResource);
        when(historyResource.isDeleted()).thenReturn(true);

        assertThrows(IResourceStore.ResourceNotFoundException.class, () -> store.read("id1", 1));
    }

    @Test
    void shouldUpdateResourceAndStoreHistory() throws Exception {
        IResourceStorage.IResource<String> currentResource = mock(IResourceStorage.IResource.class);
        when(storage.read("id1", 1)).thenReturn(currentResource);
        when(currentResource.getId()).thenReturn("id1");
        when(currentResource.getVersion()).thenReturn(1);

        IResourceStorage.IHistoryResource<String> historyResource = mock(IResourceStorage.IHistoryResource.class);
        when(storage.newHistoryResourceFor(currentResource, false)).thenReturn(historyResource);

        IResourceStorage.IResource<String> newResource = mock(IResourceStorage.IResource.class);
        when(storage.newResource("id1", 2, "updated")).thenReturn(newResource);

        Integer newVersion = store.update("id1", 1, "updated");

        assertEquals(2, newVersion);
        // Archive + conditional store go to the storage layer as ONE call, so a
        // backend with transactions can make them atomic. Issuing them separately
        // let a crash in between leave the two rows disagreeing about the current
        // version.
        verify(storage).storeHistoryAndUpdate(historyResource, newResource, 1);
        verify(storage, never()).store(any(IResourceStorage.IHistoryResource.class));
        verify(storage, never()).storeIfCurrentVersion(any(), anyInt());
    }

    @Test
    void shouldThrowResourceModifiedWhenConcurrentUpdate() throws Exception {
        IResourceStorage.IResource<String> currentResource = mock(IResourceStorage.IResource.class);
        when(storage.read("id1", 1)).thenReturn(currentResource);
        when(currentResource.getId()).thenReturn("id1");
        when(currentResource.getVersion()).thenReturn(1);

        IResourceStorage.IHistoryResource<String> historyResource = mock(IResourceStorage.IHistoryResource.class);
        when(storage.newHistoryResourceFor(currentResource, false)).thenReturn(historyResource);

        IResourceStorage.IResource<String> newResource = mock(IResourceStorage.IResource.class);
        when(storage.newResource("id1", 2, "updated")).thenReturn(newResource);

        doThrow(new IResourceStore.ResourceModifiedException("concurrent edit"))
                .when(storage).storeHistoryAndUpdate(historyResource, newResource, 1);

        assertThrows(IResourceStore.ResourceModifiedException.class,
                () -> store.update("id1", 1, "updated"));

        verify(storage).storeHistoryAndUpdate(historyResource, newResource, 1);
    }

    @Test
    void shouldDeleteAndStoreHistoryMarkedDeleted() throws Exception {
        IResourceStorage.IResource<String> currentResource = mock(IResourceStorage.IResource.class);
        when(storage.read("id1", 1)).thenReturn(currentResource);

        IResourceStorage.IHistoryResource<String> deletedHistory = mock(IResourceStorage.IHistoryResource.class);
        when(storage.newHistoryResourceFor(currentResource, true)).thenReturn(deletedHistory);

        store.delete("id1", 1);

        // Same reason as update: an archive that lands without the matching removal
        // leaves the resource archived-as-deleted while the live row is still there.
        // Version-checked, so an update that committed after the read is not erased.
        verify(storage).storeHistoryAndRemove(deletedHistory, "id1", 1);
        verify(storage, never()).remove(anyString());
    }

    @Test
    void deleteRacingAnUpdateIsRefusedRatherThanErasingTheNewVersion() throws Exception {
        IResourceStorage.IResource<String> currentResource = mock(IResourceStorage.IResource.class);
        when(storage.read("id1", 1)).thenReturn(currentResource);
        IResourceStorage.IHistoryResource<String> deletedHistory = mock(IResourceStorage.IHistoryResource.class);
        when(storage.newHistoryResourceFor(currentResource, true)).thenReturn(deletedHistory);
        // An update moved the resource to v2 between the read above and the delete.
        doThrow(new IResourceStore.ResourceModifiedException("moved on"))
                .when(storage).storeHistoryAndRemove(deletedHistory, "id1", 1);

        assertThrows(IResourceStore.ResourceModifiedException.class, () -> store.delete("id1", 1));
    }

    @Test
    void combinedWriteDefaultsPreserveHistoryFirstOrdering() throws Exception {
        // The SPI default is what backends without transactions run. It must keep
        // archiving BEFORE mutating the current row, so a failure of the second
        // write can never lose the archived predecessor.
        IResourceStorage.IHistoryResource<String> history = mock(IResourceStorage.IHistoryResource.class);
        IResourceStorage.IResource<String> newResource = mock(IResourceStorage.IResource.class);

        IResourceStorage<String> updateDefaults = mock(IResourceStorage.class, CALLS_REAL_METHODS);
        updateDefaults.storeHistoryAndUpdate(history, newResource, 1);
        InOrder updateOrder = inOrder(updateDefaults);
        updateOrder.verify(updateDefaults).store(history);
        updateOrder.verify(updateDefaults).storeIfCurrentVersion(newResource, 1);

        // A delete has no default: an unconditional one is exactly what let a delete
        // racing an update erase the version the update had just committed.
        IResourceStorage<String> deleteDefaults = mock(IResourceStorage.class, CALLS_REAL_METHODS);
        assertThrows(UnsupportedOperationException.class, () -> deleteDefaults.storeHistoryAndRemove(history, "id1", 1));
        verify(deleteDefaults, never()).remove(anyString());
    }

    @Test
    void shouldGetCurrentResourceIdFromStorage() throws Exception {
        when(storage.getCurrentVersion("id1")).thenReturn(3);

        IResourceStore.IResourceId result = store.getCurrentResourceId("id1");

        assertEquals("id1", result.getId());
        assertEquals(3, result.getVersion());
    }

    @Test
    void shouldThrowNotFoundWhenNoCurrentVersion() {
        when(storage.getCurrentVersion("id1")).thenReturn(-1);

        assertThrows(IResourceStore.ResourceNotFoundException.class, () -> store.getCurrentResourceId("id1"));
    }

    @Test
    void shouldReadIncludingDeletedFromHistory() throws Exception {
        when(storage.read("id1", 1)).thenReturn(null);

        IResourceStorage.IHistoryResource<String> historyResource = mock(IResourceStorage.IHistoryResource.class);
        when(storage.readHistory("id1", 1)).thenReturn(historyResource);
        when(historyResource.isDeleted()).thenReturn(true);
        when(historyResource.getData()).thenReturn("deleted-data");

        // readIncludingDeleted should NOT throw even for deleted resources
        String result = store.readIncludingDeleted("id1", 1);

        assertEquals("deleted-data", result);
    }

    @Test
    void shouldDeleteAllPermanently() {
        store.deleteAllPermanently("id1");

        verify(storage).removeAllPermanently("id1");
    }

    /**
     * The store is one instance per resource type, so a lock on the store made
     * every delete of that type on this node queue behind the one in progress, each
     * holding it across several majority-acknowledged round trips (the GDPR erasure
     * loop deletes one conversation descriptor after another). The lock is per id
     * now: "a" and "b" fall into different stripes, so neither waits.
     */
    @Test
    void deletesOfDifferentResourcesDoNotWaitForEachOther() throws Exception {
        assertNotEquals(HistorizedResourceStore.stripeOf("a"), HistorizedResourceStore.stripeOf("b"));
        when(storage.read("a", 1)).thenReturn(mock(IResourceStorage.IResource.class));
        when(storage.read("b", 1)).thenReturn(mock(IResourceStorage.IResource.class));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            release.await(10, SECONDS);
            return null;
        }).when(storage).storeHistoryAndRemove(any(), eq("a"), eq(1));

        assertRunsWhileAnotherIsInsideTheStorage(() -> {
            store.delete("a", 1);
            return null;
        }, entered, release, () -> {
            store.delete("b", 1);
            return null;
        });

        verify(storage).storeHistoryAndRemove(any(), eq("b"), eq(1));
    }

    @Test
    void permanentDeletesOfDifferentResourcesDoNotWaitForEachOther() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            release.await(10, SECONDS);
            return null;
        }).when(storage).removeAllPermanently("a");

        assertRunsWhileAnotherIsInsideTheStorage(() -> {
            store.deleteAllPermanently("a");
            return null;
        }, entered, release, () -> {
            store.deleteAllPermanently("b");
            return null;
        });

        verify(storage).removeAllPermanently("b");
    }

    /**
     * Without a transaction (MongoDB standalone), a delete that read the resource
     * before a permanent removal ran to completion would then upsert its tombstone
     * — the erased resource's data — into the emptied history, after the erasure
     * had reported success. The delete must not even read until the removal has
     * left the storage.
     */
    @Test
    void deleteOfTheSameIdWaitsForAPermanentDeleteInProgress() throws Exception {
        when(storage.read("a", 1)).thenReturn(mock(IResourceStorage.IResource.class));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            release.await(10, SECONDS);
            return null;
        }).when(storage).removeAllPermanently("a");

        assertWaitsWhileAnotherIsInsideTheStorage(() -> store.deleteAllPermanently("a"), entered, release, () -> {
            try {
                store.delete("a", 1);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });

        InOrder order = inOrder(storage);
        order.verify(storage).removeAllPermanently("a");
        order.verify(storage).read("a", 1);
        order.verify(storage).storeHistoryAndRemove(any(), eq("a"), eq(1));
    }

    /**
     * The same race through update: on MongoDB it archives the predecessor before
     * its version-checked replace, so an update that read before the erasure would
     * leave that history row behind even though its replace then fails.
     */
    @Test
    void updateOfTheSameIdWaitsForAPermanentDeleteInProgress() throws Exception {
        IResourceStorage.IResource<String> current = mock(IResourceStorage.IResource.class);
        when(current.getId()).thenReturn("a");
        when(current.getVersion()).thenReturn(1);
        when(storage.read("a", 1)).thenReturn(current);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            release.await(10, SECONDS);
            return null;
        }).when(storage).removeAllPermanently("a");

        assertWaitsWhileAnotherIsInsideTheStorage(() -> store.deleteAllPermanently("a"), entered, release, () -> {
            try {
                store.update("a", 1, "new content");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });

        InOrder order = inOrder(storage);
        order.verify(storage).removeAllPermanently("a");
        order.verify(storage).read("a", 1);
        order.verify(storage).storeHistoryAndUpdate(any(), any(), eq(1));
    }

    /**
     * Starts {@code blocked}, waits until it is inside the storage call that holds
     * on {@code release}, starts {@code other} and requires it to park on the lock
     * without touching the storage. Then releases the first and lets both finish.
     * No sleeps: the wait is for the thread's state, bounded by a deadline.
     */
    private void assertWaitsWhileAnotherIsInsideTheStorage(Runnable blocked, CountDownLatch entered, CountDownLatch release, Runnable other)
            throws Exception {
        var firstError = new AtomicReference<Throwable>();
        var secondError = new AtomicReference<Throwable>();
        Thread first = new Thread(() -> runCapturing(blocked, firstError));
        Thread second = new Thread(() -> runCapturing(other, secondError));
        try {
            first.start();
            assertTrue(entered.await(5, SECONDS), "the first call never reached the storage");
            second.start();
            long deadline = System.nanoTime() + SECONDS.toNanos(5);
            while (second.getState() != Thread.State.WAITING && second.isAlive() && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertEquals(Thread.State.WAITING, second.getState(), "the second call did not wait for the first one to leave the storage");
            verify(storage, never()).read(any(), any());
        } finally {
            release.countDown();
            first.join(SECONDS.toMillis(5));
            second.join(SECONDS.toMillis(5));
        }
        assertNull(firstError.get());
        assertNull(secondError.get());
    }

    private static void runCapturing(Runnable action, AtomicReference<Throwable> error) {
        try {
            action.run();
        } catch (Throwable t) {
            error.set(t);
        }
    }

    /**
     * Starts {@code blocked}, waits until it is inside the storage call that holds
     * on {@code release}, then requires {@code other} to finish while it is still
     * there. Latches, no sleeps: with a lock on the store the second call cannot
     * start and its {@code get} times out.
     */
    private static void assertRunsWhileAnotherIsInsideTheStorage(Callable<Void> blocked, CountDownLatch entered, CountDownLatch release,
                                                                 Callable<Void> other)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Void> first = pool.submit(blocked);
            assertTrue(entered.await(5, SECONDS), "the first call never reached the storage");
            Future<Void> second = pool.submit(other);
            assertDoesNotThrow(() -> second.get(5, SECONDS), "the second call waited for the first one to leave the storage");
            release.countDown();
            first.get(5, SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }
}
