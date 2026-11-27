/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.agents;

import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.datastore.IResourceStore.ResourceNotFoundException;
import ai.labs.eddi.datastore.IResourceStore.ResourceStoreException;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Duration;
import java.util.Objects;

/**
 * Agent configurations by {@code (agentId, version)}, read from the store once.
 * <p>
 * A stored version never changes: an edit writes a new version, and the only
 * in-place writers in the platform are the descriptor stores and the startup
 * migrations, which run before any turn. So a version read once can be served
 * from memory for as long as anyone asks for it. The HITL service used to read
 * the pinned configuration from MongoDB on every turn, only to learn the
 * agent's tool-approval policy.
 * <p>
 * What is not cached: an absent version and a store failure. Both are re-asked
 * every time, so a version that appears later is found, and a failure stays a
 * failure the caller can fail closed on. The cache is bounded, entries nobody
 * asks for expire, and deploying or undeploying an agent drops its entries (see
 * {@code AgentFactory}), so a deleted version does not linger after its agent
 * leaves the runtime.
 * <p>
 * The returned object is shared between conversations: read it, never modify
 * it.
 * <p>
 * {@code WorkflowTraversal} (in {@code modules.llm.impl}) keeps its own cache
 * of whole traversals under the same rule, dropped by the same deployment
 * hooks.
 */
@ApplicationScoped
public class AgentConfigurationCache {

    static final int MAX_ENTRIES = 1_000;
    static final Duration EXPIRE_AFTER_ACCESS = Duration.ofMinutes(30);

    /**
     * {@code includingDeleted} keeps what {@link #readIncludingDeleted} read apart
     * from what {@link #read} read, so a running agent is never handed a deleted
     * version.
     */
    private record Key(String agentId, int version, boolean includingDeleted) {
    }

    private final IAgentStore agentStore;
    private final Cache<Key, AgentConfiguration> cache = Caffeine.newBuilder()
            .maximumSize(MAX_ENTRIES)
            .expireAfterAccess(EXPIRE_AFTER_ACCESS)
            .build();

    @Inject
    public AgentConfigurationCache(IAgentStore agentStore) {
        this.agentStore = agentStore;
    }

    /**
     * The configuration stored as {@code version} of the agent.
     *
     * @throws ResourceNotFoundException
     *             when the version does not exist (not remembered)
     * @throws ResourceStoreException
     *             when the store fails (not remembered)
     */
    public AgentConfiguration read(String agentId, int version) throws ResourceNotFoundException, ResourceStoreException {
        Objects.requireNonNull(agentId, "agentId");
        Key key = new Key(agentId, version, false);
        AgentConfiguration cached = cache.getIfPresent(key);
        if (cached != null) {
            return cached;
        }
        // Read outside the cache's own loading, which would hold a lock across the
        // round trip; two racing misses simply read the same immutable document twice.
        AgentConfiguration read = agentStore.read(agentId, version);
        if (read != null) {
            cache.put(key, read);
        }
        return read;
    }

    /**
     * As {@link #read}, but also finds a version of an agent deleted without
     * {@code permanent=true}: a soft delete flags that version and leaves it in the
     * store unchanged. For callers that look back at what a version declared — the
     * audit ledger checking an old entry's agent signature — never for running one.
     * A version {@link #read} already holds is served from there, since its content
     * is the same either way.
     *
     * @throws ResourceNotFoundException
     *             when the version does not exist, or was deleted permanently (not
     *             remembered)
     * @throws ResourceStoreException
     *             when the store fails (not remembered)
     */
    public AgentConfiguration readIncludingDeleted(String agentId, int version) throws ResourceNotFoundException, ResourceStoreException {
        Objects.requireNonNull(agentId, "agentId");
        AgentConfiguration live = cache.getIfPresent(new Key(agentId, version, false));
        if (live != null) {
            return live;
        }
        Key key = new Key(agentId, version, true);
        AgentConfiguration cached = cache.getIfPresent(key);
        if (cached != null) {
            return cached;
        }
        AgentConfiguration read = agentStore.readIncludingDeleted(agentId, version);
        if (read != null) {
            cache.put(key, read);
        }
        return read;
    }

    /** Drops every cached version of the agent. */
    public void invalidate(String agentId) {
        if (agentId != null) {
            cache.asMap().keySet().removeIf(key -> key.agentId().equals(agentId));
        }
    }

    /** Drops everything. */
    public void invalidateAll() {
        cache.invalidateAll();
    }
}
