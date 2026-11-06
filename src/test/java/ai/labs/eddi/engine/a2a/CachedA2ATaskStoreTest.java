/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.a2a;

import ai.labs.eddi.engine.a2a.A2AModels.A2ATaskRecord;
import ai.labs.eddi.engine.a2a.A2AModels.TaskState;
import ai.labs.eddi.engine.a2a.CachedA2ATaskStore.PrincipalIndex;
import ai.labs.eddi.engine.caching.CacheFactory;
import ai.labs.eddi.engine.caching.ICache;
import ai.labs.eddi.engine.caching.ICacheFactory;
import ai.labs.eddi.engine.caching.SharedKvCache;
import ai.labs.eddi.engine.cluster.InMemorySharedKv;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

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
     * Cluster mode as it runs: every cache of a node is a {@link SharedKvCache}
     * over that node's own local cache and a bucket all nodes share.
     */
    private static ICacheFactory clusterNode(Map<String, InMemorySharedKv> buckets) {
        CacheFactory local = new CacheFactory();
        ICacheFactory node = mock(ICacheFactory.class);
        when(node.<String, Object>getCache(anyString())).thenAnswer(invocation -> {
            String name = invocation.getArgument(0);
            return new SharedKvCache<String, Object>(local.getCache(name),
                    buckets.computeIfAbsent(name, n -> new InMemorySharedKv("A2A", Duration.ofHours(24))), false);
        });
        return node;
    }

    /**
     * Two nodes over the same buckets, each with its own local mirror. A record
     * written on one node must be exported and erased from the other, which never
     * saw it in its mirror — and counted: the count is the {@code a2aTasks} entry
     * of the GDPR erasure result, and it read 0 while the records were deleted.
     */
    @Test
    @DisplayName("a record written on another node is exported, erased and counted through the shared index")
    void recordsWrittenOnAnotherNodeAreReachedThroughTheIndex() {
        Map<String, InMemorySharedKv> buckets = new HashMap<>();
        CachedA2ATaskStore nodeA = new CachedA2ATaskStore(clusterNode(buckets));
        CachedA2ATaskStore nodeB = new CachedA2ATaskStore(clusterNode(buckets));

        nodeA.saveTask("alice", record("t1", "written on A"));
        nodeA.bindContext("alice", "ctx-a", "conv-a");
        nodeA.saveTask("bob", record("t1", "bob on A"));

        assertEquals(1, nodeB.tasksOf("alice").size(), "node B exports what node A wrote");
        assertEquals(2, nodeB.eraseForPrincipal("alice"), "node B erases and counts the task and the binding node A wrote");
        assertTrue(nodeA.findTask("alice", "t1").isEmpty());
        assertTrue(nodeA.findContextConversation("alice", "ctx-a").isEmpty());
        assertTrue(nodeA.tasksOf("alice").isEmpty(), "nothing of alice is left in the index either");
        assertTrue(nodeA.findTask("bob", "t1").isPresent());
        assertTrue(nodeA.saveTaskIsRefused("alice"), "node A sees node B's erasure marker");
    }

    /**
     * The index used to be one value per principal, keyed by the bare principal. A
     * node of the previous release writes it during a rolling upgrade, and it lives
     * for the bucket's TTL afterwards, so it is still read and erased.
     */
    @Test
    @DisplayName("an index written by the previous release is still exported and erased")
    void legacyUnshardedIndexIsStillReached() {
        Map<String, InMemorySharedKv> buckets = new HashMap<>();
        ICacheFactory oldNode = clusterNode(buckets);
        oldNode.<String, A2ATaskRecord>getCache(CachedA2ATaskStore.TASK_CACHE)
                .put(CachedA2ATaskStore.scopedKey("alice", "t-old"), record("t-old", "written by the old release"));
        oldNode.<String, String>getCache(CachedA2ATaskStore.CONTEXT_CACHE).put(CachedA2ATaskStore.scopedKey("alice", "ctx-old"), "conv-old");
        ICache<String, PrincipalIndex> legacyIndex = oldNode.getCache(CachedA2ATaskStore.INDEX_CACHE);
        legacyIndex.put("alice", new PrincipalIndex(List.of("t-old"), List.of("ctx-old")));
        CachedA2ATaskStore newNode = new CachedA2ATaskStore(clusterNode(buckets));

        assertEquals(1, newNode.tasksOf("alice").size(), "the export reaches the old release's record");
        assertEquals(2, newNode.eraseForPrincipal("alice"));
        assertNull(legacyIndex.get("alice"), "the old index is removed with the records");
        assertTrue(newNode.findContextConversation("alice", "ctx-old").isEmpty());
    }

    /**
     * NATS refuses a value over its payload limit (1 MB by default) and the shared
     * cache then keeps it on the writing node only — so the index, the one thing
     * that lets another node erase or export the principal's records, stopped being
     * shared exactly when a principal held many tasks with long caller-chosen ids:
     * 10,000 of each at 256 characters made one value of about 5 MB.
     */
    @Test
    @DisplayName("a principal at the cap with 256-character ids keeps every index value far below the NATS payload limit")
    void indexValuesStayFarBelowThePayloadLimit() throws Exception {
        var largest = new AtomicReference<>(PrincipalIndex.empty());
        ICacheFactory factory = mock(ICacheFactory.class);
        when(factory.<String, Object>getCache(anyString())).thenAnswer(invocation -> {
            if (!CachedA2ATaskStore.INDEX_CACHE.equals(invocation.getArgument(0))) {
                return new MapCache<>();
            }
            return new MapCache<String, PrincipalIndex>() {
                private PrincipalIndex recorded(PrincipalIndex value) {
                    if (value.taskIds().size() + value.contextIds().size() > largest.get().taskIds().size()
                            + largest.get().contextIds().size()) {
                        largest.set(value);
                    }
                    return value;
                }

                @Override
                public PrincipalIndex put(String key, PrincipalIndex value) {
                    return super.put(key, recorded(value));
                }

                @Override
                public PrincipalIndex putIfAbsent(String key, PrincipalIndex value) {
                    return super.putIfAbsent(key, recorded(value));
                }

                @Override
                public boolean replace(String key, PrincipalIndex oldValue, PrincipalIndex newValue) {
                    return super.replace(key, oldValue, recorded(newValue));
                }
            };
        });
        CachedA2ATaskStore capped = new CachedA2ATaskStore(factory);

        for (int i = 0; i < CachedA2ATaskStore.MAX_INDEXED_IDS; i++) {
            String id = String.format("%05d", i) + "x".repeat(251);
            capped.saveTask("alice", record(id, "task " + i));
            capped.bindContext("alice", "ctx-" + id, "conv-" + i);
        }

        int bytes = new ObjectMapper().writeValueAsBytes(largest.get()).length;
        assertTrue(bytes < 512 * 1024, "the largest index value was " + bytes + " bytes");
    }

    /** The task ids the index holds for {@code principal}, over every shard. */
    private static List<String> indexedTaskIds(ICache<String, Object> index, String principal) {
        List<String> ids = new ArrayList<>();
        for (String key : CachedA2ATaskStore.indexKeysOf(principal)) {
            if (index.get(key) instanceof PrincipalIndex shard) {
                ids.addAll(shard.taskIds());
            }
        }
        return ids;
    }

    @Test
    @DisplayName("an id that falls off the end of its shard takes its record with it, oldest first")
    void capEvictsTheRecordWithTheId() {
        List<String> oneShard = new ArrayList<>();
        for (int n = 0; oneShard.size() < CachedA2ATaskStore.MAX_IDS_PER_SHARD + 5; n++) {
            if (CachedA2ATaskStore.shardOf("t" + n) == 0) {
                oneShard.add("t" + n);
            }
        }
        oneShard.forEach(id -> store.saveTask("alice", record(id, "task " + id)));

        assertEquals(CachedA2ATaskStore.MAX_IDS_PER_SHARD, store.tasksOf("alice").size());
        for (String evicted : oneShard.subList(0, 5)) {
            assertTrue(store.findTask("alice", evicted).isEmpty(), evicted + " fell off the index and must be gone");
        }
        assertTrue(store.findTask("alice", oneShard.get(5)).isPresent());
        assertTrue(store.findTask("alice", oneShard.getLast()).isPresent());
        assertEquals(CachedA2ATaskStore.MAX_IDS_PER_SHARD, store.eraseForPrincipal("alice"), "the sweep reaches every live record");
    }

    @Test
    @DisplayName("a principal holds at most MAX_INDEXED_IDS tasks, and the index names every one it holds")
    void principalCapHoldsAcrossShards() {
        Map<String, ICache<String, Object>> caches = new HashMap<>();
        ICacheFactory factory = mock(ICacheFactory.class);
        when(factory.<String, Object>getCache(anyString()))
                .thenAnswer(invocation -> caches.computeIfAbsent(invocation.getArgument(0), name -> new MapCache<>()));
        CachedA2ATaskStore capped = new CachedA2ATaskStore(factory);

        for (int i = 0; i < CachedA2ATaskStore.MAX_INDEXED_IDS + 1_000; i++) {
            capped.saveTask("alice", record("t" + i, "task " + i));
        }

        int held = capped.tasksOf("alice").size();
        assertTrue(held <= CachedA2ATaskStore.MAX_INDEXED_IDS, held + " tasks held");
        assertEquals(held, indexedTaskIds(caches.get(CachedA2ATaskStore.INDEX_CACHE), "alice").size(),
                "every record still held is in the index, so another node can reach it");
        assertEquals(held, capped.eraseForPrincipal("alice"));
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

        assertEquals(writers * perWriter, indexedTaskIds(caches.get(CachedA2ATaskStore.INDEX_CACHE), "alice").size(),
                "every concurrently written id is in the index");
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
