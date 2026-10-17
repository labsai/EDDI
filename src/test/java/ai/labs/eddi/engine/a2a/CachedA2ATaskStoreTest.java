/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.a2a;

import ai.labs.eddi.engine.a2a.A2AModels.A2ATaskRecord;
import ai.labs.eddi.engine.a2a.A2AModels.TaskState;
import ai.labs.eddi.engine.caching.ICache;
import ai.labs.eddi.engine.caching.ICacheFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The per-principal sweep and listing GDPR erasure and export run against the
 * A2A task store (M10 of the 2026-10-15 security review).
 */
class CachedA2ATaskStoreTest {

    private CachedA2ATaskStore store;

    @BeforeEach
    void setUp() {
        ICacheFactory cacheFactory = mock(ICacheFactory.class);
        when(cacheFactory.<String, Object>getCache(anyString())).thenAnswer(invocation -> new MapCache<>());
        store = new CachedA2ATaskStore(cacheFactory);
    }

    private static A2ATaskRecord record(String taskId, String text) {
        return new A2ATaskRecord(taskId, "ctx-" + taskId, "conv-" + taskId, "agent", TaskState.completed, null, "reply to " + text,
                text, Instant.EPOCH);
    }

    @Test
    @DisplayName("tasksOf lists exactly the principal's tasks, none of a neighbour's")
    void tasksOfIsScopedToThePrincipal() {
        store.saveTask("alice", record("t1", "alice one"));
        store.saveTask("alice", record("t2", "alice two"));
        store.saveTask("bob", record("t1", "bob one"));

        List<A2ATaskRecord> alice = store.tasksOf("alice");

        assertEquals(2, alice.size());
        assertTrue(alice.stream().allMatch(r -> r.userText().startsWith("alice")));
        assertEquals(1, store.tasksOf("bob").size());
        assertTrue(store.tasksOf("carol").isEmpty());
    }

    @Test
    @DisplayName("eraseForPrincipal removes the principal's tasks and context bindings and leaves everyone else's")
    void eraseForPrincipalRemovesOnlyThatPrincipal() {
        store.saveTask("alice", record("t1", "alice one"));
        store.saveTask("alice", record("t2", "alice two"));
        store.bindContext("alice", "ctx-a", "conv-a");
        store.saveTask("bob", record("t1", "bob one"));
        store.bindContext("bob", "ctx-b", "conv-b");

        long removed = store.eraseForPrincipal("alice");

        assertEquals(3, removed);
        assertTrue(store.findTask("alice", "t1").isEmpty());
        assertTrue(store.findTask("alice", "t2").isEmpty());
        assertTrue(store.findContextConversation("alice", "ctx-a").isEmpty());
        assertTrue(store.tasksOf("alice").isEmpty());
        assertEquals("conv-b", store.findContextConversation("bob", "ctx-b").orElse(null));
        assertTrue(store.findTask("bob", "t1").isPresent());
        assertEquals(0, store.eraseForPrincipal("alice"), "a second erasure finds nothing");
    }

    /**
     * The length prefix is what keeps "alice" from matching "alice|x" or "alice-2":
     * a plain string prefix on the principal would sweep a neighbour.
     */
    @Test
    @DisplayName("a principal that is a prefix of another principal's name does not match the other's keys")
    void prefixPrincipalsDoNotCollide() {
        store.saveTask("alice", record("t1", "alice"));
        store.saveTask("alice|t1", record("x", "crafted"));
        store.saveTask("alice-2", record("t1", "alice-2"));

        assertEquals(1, store.tasksOf("alice").size());
        assertEquals(1, store.eraseForPrincipal("alice"));
        assertTrue(store.findTask("alice|t1", "x").isPresent());
        assertTrue(store.findTask("alice-2", "t1").isPresent());
    }

    @Test
    @DisplayName("once erasure began, the principal's writes are dropped — including one that lands after the sweep")
    void writesDuringAndAfterErasureAreDropped() {
        store.saveTask("alice", record("t1", "before"));
        store.beginErasure("alice");

        store.saveTask("alice", record("t2", "during the cascade"));
        store.bindContext("alice", "ctx-late", "conv-late");
        assertTrue(store.findTask("alice", "t2").isEmpty());
        assertTrue(store.findContextConversation("alice", "ctx-late").isEmpty());

        assertEquals(1, store.eraseForPrincipal("alice"));
        store.saveTask("alice", record("t3", "after the sweep"));
        assertTrue(store.tasksOf("alice").isEmpty(), "a write after the sweep must not survive it");

        store.saveTask("bob", record("t1", "unaffected"));
        assertTrue(store.findTask("bob", "t1").isPresent(), "another principal's writes are unaffected");
    }

    /**
     * The interleaving a marker alone cannot stop: a write passes the erasure
     * check, pauses, the sweep runs, the write lands. The write is held inside the
     * cache's {@code put}, the sweep is started meanwhile, and the write is then
     * released; the sweep must not have run in between.
     */
    @Test
    @DisplayName("a write that passed the erasure check finishes before the sweep, so it cannot land after it")
    void writePausedBetweenCheckAndPutCannotSurviveTheSweep() throws Exception {
        CountDownLatch writerInsidePut = new CountDownLatch(1);
        CountDownLatch releaseWriter = new CountDownLatch(1);
        ICacheFactory cacheFactory = mock(ICacheFactory.class);
        when(cacheFactory.<String, Object>getCache(anyString())).thenAnswer(invocation -> {
            if (CachedA2ATaskStore.TASK_CACHE.equals(invocation.getArgument(0))) {
                return new MapCache<String, Object>() {
                    @Override
                    public Object put(String key, Object value) {
                        writerInsidePut.countDown();
                        try {
                            assertTrue(releaseWriter.await(5, TimeUnit.SECONDS), "the test releases the writer");
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return super.put(key, value);
                    }
                };
            }
            return new MapCache<>();
        });
        CachedA2ATaskStore paused = new CachedA2ATaskStore(cacheFactory);

        Thread writer = new Thread(() -> paused.saveTask("alice", record("t1", "admitted before the cascade")));
        writer.start();
        assertTrue(writerInsidePut.await(5, TimeUnit.SECONDS), "the writer must be past the erasure check");

        var removed = new AtomicLong(-1);
        Thread eraser = new Thread(() -> removed.set(paused.eraseForPrincipal("alice")));
        eraser.start();
        // Give the sweep the chance to run ahead of the paused write — which is the
        // bug: without the lock it collects nothing, and the write lands after it.
        Thread.sleep(200);
        releaseWriter.countDown();
        writer.join(5000);
        eraser.join(5000);

        assertEquals(1, removed.get(), "the sweep must have waited for the admitted write and removed it");
        assertTrue(paused.tasksOf("alice").isEmpty(), "nothing of the principal may survive the sweep");
        assertTrue(paused.findTask("alice", "t1").isEmpty());
    }

    /**
     * Cluster mode, modelled: two store instances (two nodes) over the same shared
     * caches, each with its own local mirror. A record written on one node must be
     * exported and erased from the other, which never saw it in its mirror.
     */
    @Test
    @DisplayName("a record written on another node is exported and erased through the shared index")
    void recordsWrittenOnAnotherNodeAreReachedThroughTheIndex() {
        Map<String, ICache<String, Object>> sharedBuckets = new HashMap<>();
        ICacheFactory sharedFactory = mock(ICacheFactory.class);
        when(sharedFactory.<String, Object>getCache(anyString()))
                .thenAnswer(invocation -> sharedBuckets.computeIfAbsent(invocation.getArgument(0), name -> new MapCache<>()));
        // The second node's mirror would be its own local cache; what the test needs is
        // that it has NOT written the record itself and still reaches it. Sharing the
        // bucket map between the two instances models the shared KV buckets.
        CachedA2ATaskStore nodeA = new CachedA2ATaskStore(sharedFactory);
        CachedA2ATaskStore nodeB = new CachedA2ATaskStore(sharedFactory);

        nodeA.saveTask("alice", record("t1", "written on A"));
        nodeA.bindContext("alice", "ctx-a", "conv-a");
        nodeA.saveTask("bob", record("t1", "bob on A"));

        assertEquals(1, nodeB.tasksOf("alice").size(), "node B exports what node A wrote");
        assertEquals(2, nodeB.eraseForPrincipal("alice"), "node B erases the task and the binding node A wrote");
        assertTrue(nodeA.findTask("alice", "t1").isEmpty());
        assertTrue(nodeA.findContextConversation("alice", "ctx-a").isEmpty());
        assertTrue(nodeA.findTask("bob", "t1").isPresent());
        assertTrue(nodeA.saveTaskIsRefused("alice"), "node A sees node B's erasure marker");
    }

    @Test
    @DisplayName("an id that falls off the end of the index takes its record with it, so every live record stays discoverable")
    void capEvictsTheRecordWithTheId() {
        for (int i = 0; i < CachedA2ATaskStore.MAX_INDEXED_IDS + 5; i++) {
            store.saveTask("alice", record("t" + i, "task " + i));
        }

        assertEquals(CachedA2ATaskStore.MAX_INDEXED_IDS, store.tasksOf("alice").size());
        for (int i = 0; i < 5; i++) {
            assertTrue(store.findTask("alice", "t" + i).isEmpty(), "t" + i + " fell off the index and must be gone");
        }
        assertTrue(store.findTask("alice", "t5").isPresent());
        assertTrue(store.findTask("alice", "t" + (CachedA2ATaskStore.MAX_INDEXED_IDS + 4)).isPresent());
        assertEquals(CachedA2ATaskStore.MAX_INDEXED_IDS, store.eraseForPrincipal("alice"), "the sweep reaches every live record");
    }

    /**
     * Concurrent writers on one principal: with a read-modify-write the later
     * {@code put} dropped the other writer's id; the compare-and-set loop lands
     * them all, so the index names every record and a sweep removes every one.
     */
    @Test
    @DisplayName("concurrent writers all land their ids in the index")
    void concurrentWritersAllLandInTheIndex() throws Exception {
        // The index is what another node sweeps by; this node's local scan would find
        // every record regardless, so the assertion reads the index entry itself.
        Map<String, ICache<String, Object>> caches = new HashMap<>();
        ICacheFactory factory = mock(ICacheFactory.class);
        when(factory.<String, Object>getCache(anyString()))
                .thenAnswer(invocation -> caches.computeIfAbsent(invocation.getArgument(0), name -> new MapCache<>()));
        CachedA2ATaskStore store = new CachedA2ATaskStore(factory);
        int writers = 8;
        int perWriter = 200;
        var start = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        for (int w = 0; w < writers; w++) {
            final int writer = w;
            Thread t = new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int i = 0; i < perWriter; i++) {
                    store.saveTask("alice", record("w" + writer + "-" + i, "x"));
                }
            });
            threads.add(t);
            t.start();
        }
        start.countDown();
        for (Thread t : threads) {
            t.join(30_000);
        }

        var index = (CachedA2ATaskStore.PrincipalIndex) caches.get(CachedA2ATaskStore.INDEX_CACHE).get("alice");
        assertEquals(writers * perWriter, index.taskIds().size(), "every concurrently written id is in the index");
        assertEquals(writers * perWriter, store.eraseForPrincipal("alice"));
        assertTrue(store.tasksOf("alice").isEmpty());
    }

    @Test
    @DisplayName("the participant's stop phase closes the store to the principal before the sweep")
    void participantStopPhaseClosesTheStore() throws Exception {
        var participant = new A2ATaskGdprParticipant(store);

        assertEquals(0, participant.stopInFlightWork("alice"));
        store.saveTask("alice", record("t1", "late"));

        assertTrue(store.tasksOf("alice").isEmpty());
        assertEquals("a2aTasks", participant.erasureStepName());
    }

    @Test
    @DisplayName("the GDPR participant erases through the store and exports null when nothing is held")
    void participantDelegatesToTheStore() throws Exception {
        store.saveTask("alice", record("t1", "alice one"));
        var participant = new A2ATaskGdprParticipant(store);

        assertEquals("a2aTasks", participant.name());
        @SuppressWarnings("unchecked")
        List<A2ATaskRecord> exported = (List<A2ATaskRecord>) participant.export("alice");
        assertEquals(1, exported.size());
        assertEquals("alice one", exported.get(0).userText());
        assertNull(participant.export("nobody"));

        assertEquals(1, participant.erase("alice"));
        assertNull(participant.export("alice"));
    }

    private static class MapCache<K, V> extends ConcurrentHashMap<K, V> implements ICache<K, V> {

        @Override
        public String getCacheName() {
            return "test-cache";
        }

        @Override
        public V put(K key, V value, long lifespan, TimeUnit unit) {
            return put(key, value);
        }

        @Override
        public V putIfAbsent(K key, V value, long lifespan, TimeUnit unit) {
            return putIfAbsent(key, value);
        }

        @Override
        public void putAll(Map<? extends K, ? extends V> map, long lifespan, TimeUnit unit) {
            putAll(map);
        }

        @Override
        public V replace(K key, V value, long lifespan, TimeUnit unit) {
            return replace(key, value);
        }

        @Override
        public boolean replace(K key, V oldValue, V value, long lifespan, TimeUnit unit) {
            return replace(key, oldValue, value);
        }

        @Override
        public V put(K key, V value, long lifespan, TimeUnit lifespanUnit, long maxIdleTime, TimeUnit maxIdleTimeUnit) {
            return put(key, value);
        }

        @Override
        public V putIfAbsent(K key, V value, long lifespan, TimeUnit lifespanUnit, long maxIdleTime, TimeUnit maxIdleTimeUnit) {
            return putIfAbsent(key, value);
        }
    }
}
