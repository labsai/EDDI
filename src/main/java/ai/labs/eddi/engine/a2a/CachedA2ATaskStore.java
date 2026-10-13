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

import java.util.Optional;

/**
 * The default {@link IA2ATaskStore}: two {@code ICacheFactory} caches, bounded
 * and node-local like every cache the factory hands out (see
 * {@code CacheFactory}). A miss is not an error — the handler falls back to the
 * conversation store for the tasks it issued itself.
 */
@ApplicationScoped
public class CachedA2ATaskStore implements IA2ATaskStore {

    static final String TASK_CACHE = "a2aTaskMapping";
    static final String CONTEXT_CACHE = "a2aTaskMapping:context";

    private final ICache<String, A2ATaskRecord> tasks;
    private final ICache<String, String> contexts;

    @Inject
    public CachedA2ATaskStore(ICacheFactory cacheFactory) {
        this.tasks = cacheFactory.getCache(TASK_CACHE);
        this.contexts = cacheFactory.getCache(CONTEXT_CACHE);
    }

    @Override
    public Optional<A2ATaskRecord> findTask(String principal, String taskId) {
        return Optional.ofNullable(tasks.get(scopedKey(principal, taskId)));
    }

    @Override
    public void saveTask(String principal, A2ATaskRecord record) {
        tasks.put(scopedKey(principal, record.taskId()), record);
    }

    @Override
    public Optional<String> findContextConversation(String principal, String contextId) {
        return Optional.ofNullable(contexts.get(scopedKey(principal, contextId)));
    }

    @Override
    public void bindContext(String principal, String contextId, String conversationId) {
        contexts.put(scopedKey(principal, contextId), conversationId);
    }

    /**
     * Compound key binding a caller-supplied id to the peer that supplied it. The
     * principal is length-prefixed so the encoding stays injective even if a
     * principal or an id contains the separator — without that, a peer could craft
     * an id that collides with another peer's key.
     */
    static String scopedKey(String principal, String id) {
        return principal.length() + ":" + principal + "|" + id;
    }
}
