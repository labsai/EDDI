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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

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
