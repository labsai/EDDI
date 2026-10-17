/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.a2a;

import ai.labs.eddi.engine.a2a.A2AModels.A2ATaskRecord;
import ai.labs.eddi.engine.caching.ICache;
import ai.labs.eddi.engine.caching.ICacheFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * The default {@link IA2ATaskStore}: two {@code ICacheFactory} caches, bounded
 * and node-local on a single node. In cluster mode
 * ({@code eddi.messaging.type=nats}) {@code CacheFactory} backs both with
 * JetStream KV buckets ({@code A2A_TASKS}, {@code A2A_CONTEXTS}), so a task
 * issued on one node is found on every other; the records travel as JSON. A
 * miss is not an error — the handler falls back to the conversation store for
 * the tasks it issued itself.
 */
@ApplicationScoped
public class CachedA2ATaskStore implements IA2ATaskStore {

    static final String TASK_CACHE = "a2aTaskMapping";
    static final String CONTEXT_CACHE = "a2aTaskMapping:context";
    static final String ERASING_CACHE = "a2aTaskMapping:erasing";
    /**
     * How long writes for a principal stay refused after its erasure began — long
     * enough for the cascade, and for a request admitted just before it, to finish;
     * short enough that a person who comes back is not locked out.
     */
    static final long ERASURE_WINDOW_MINUTES = 5;

    private final ICache<String, A2ATaskRecord> tasks;
    private final ICache<String, String> contexts;
    private final ICache<String, Boolean> erasing;
    /**
     * Writes hold the read side, erasure the write side: a write that has passed
     * the erasure check finishes before a sweep collects its keys, and a write that
     * starts after the marker is set sees it. Without this, a write paused between
     * the check and the {@code put} landed after the sweep and survived it.
     * <p>
     * In cluster mode ({@code eddi.messaging.type=nats}) the task, context and
     * erasing caches are shared KV buckets, so the marker set by the erasing node
     * is what every other node's write check reads — but this lock is node-local. A
     * write on another node that read the marker as absent an instant before it was
     * set, and whose {@code put} then reaches the bucket only after the erasing
     * node's sweep, survives; that write would have to stall for the whole cascade
     * (the stop phase sets the marker, the sweep runs several steps later). The
     * residual is documented in {@code clustering.md}.
     */
    private final ReadWriteLock erasureLock = new ReentrantReadWriteLock();

    @Inject
    public CachedA2ATaskStore(ICacheFactory cacheFactory) {
        this.tasks = cacheFactory.getCache(TASK_CACHE);
        this.contexts = cacheFactory.getCache(CONTEXT_CACHE);
        this.erasing = cacheFactory.getCache(ERASING_CACHE);
    }

    @Override
    public Optional<A2ATaskRecord> findTask(String principal, String taskId) {
        return Optional.ofNullable(tasks.get(scopedKey(principal, taskId)));
    }

    @Override
    public void saveTask(String principal, A2ATaskRecord record) {
        erasureLock.readLock().lock();
        try {
            if (erasing.containsKey(principal)) {
                return;
            }
            tasks.put(scopedKey(principal, record.taskId()), record);
        } finally {
            erasureLock.readLock().unlock();
        }
    }

    @Override
    public Optional<String> findContextConversation(String principal, String contextId) {
        return Optional.ofNullable(contexts.get(scopedKey(principal, contextId)));
    }

    @Override
    public void bindContext(String principal, String contextId, String conversationId) {
        erasureLock.readLock().lock();
        try {
            if (erasing.containsKey(principal)) {
                return;
            }
            contexts.put(scopedKey(principal, contextId), conversationId);
        } finally {
            erasureLock.readLock().unlock();
        }
    }

    @Override
    public void beginErasure(String principal) {
        erasureLock.writeLock().lock();
        try {
            erasing.put(principal, Boolean.TRUE, ERASURE_WINDOW_MINUTES, TimeUnit.MINUTES);
        } finally {
            erasureLock.writeLock().unlock();
        }
    }

    @Override
    public List<A2ATaskRecord> tasksOf(String principal) {
        String prefix = principalPrefix(principal);
        return tasks.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(prefix))
                .map(Map.Entry::getValue)
                .toList();
    }

    @Override
    public long eraseForPrincipal(String principal) {
        // Under the write lock from marker to sweep: no write is between its erasure
        // check and its put while this runs, and none can start until it is done.
        erasureLock.writeLock().lock();
        try {
            erasing.put(principal, Boolean.TRUE, ERASURE_WINDOW_MINUTES, TimeUnit.MINUTES);
            String prefix = principalPrefix(principal);
            long removed = 0;
            // Both maps are ConcurrentMaps: collect first, then remove, so the removal
            // never races the iteration it is driven by.
            for (String key : tasks.keySet().stream().filter(key -> key.startsWith(prefix)).toList()) {
                if (tasks.remove(key) != null) {
                    removed++;
                }
            }
            for (String key : contexts.keySet().stream().filter(key -> key.startsWith(prefix)).toList()) {
                if (contexts.remove(key) != null) {
                    removed++;
                }
            }
            return removed;
        } finally {
            erasureLock.writeLock().unlock();
        }
    }

    /**
     * The prefix every key of {@code principal} starts with, and no other
     * principal's key does: the length prefix in {@link #scopedKey} makes
     * {@code "5:alice|"} distinct from {@code "11:alice|bob|"} however the names
     * are chosen.
     */
    static String principalPrefix(String principal) {
        return principal.length() + ":" + principal + "|";
    }

    /**
     * Compound key binding a caller-supplied id to the peer that supplied it. The
     * principal is length-prefixed so the encoding stays injective even if a
     * principal or an id contains the separator — without that, a peer could craft
     * an id that collides with another peer's key.
     */
    static String scopedKey(String principal, String id) {
        return principalPrefix(principal) + id;
    }
}
