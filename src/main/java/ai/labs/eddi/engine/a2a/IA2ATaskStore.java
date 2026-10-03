/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.a2a;

import ai.labs.eddi.engine.a2a.A2AModels.A2ATaskRecord;

import java.util.Optional;

/**
 * Where A2A remembers its tasks and contexts between requests.
 * <p>
 * Every lookup is scoped to the calling peer's principal: task and context ids
 * are payload a peer chooses or was handed, never proof of ownership, so an id
 * belonging to another peer must be indistinguishable from an unknown one. An
 * implementation keys on the pair, never on the id alone.
 * <p>
 * This is the seam for clustered deployments. The default,
 * {@link CachedA2ATaskStore}, keeps both maps in {@code ICacheFactory} caches,
 * which are node-local unless the cache factory itself is shared — so a
 * {@code tasks/get} that lands on another node, or arrives after a restart,
 * misses here. {@link A2ATaskHandler} therefore treats this store as a cache in
 * front of the conversation store: on a miss it re-derives a task it issued
 * from the conversation the task ran in. A shared implementation removes the
 * miss; it does not change any answer.
 */
public interface IA2ATaskStore {

    /**
     * The task the peer {@code principal} created under {@code taskId}, if known.
     */
    Optional<A2ATaskRecord> findTask(String principal, String taskId);

    /** Records (or replaces) a task for {@code principal}. */
    void saveTask(String principal, A2ATaskRecord record);

    /** The conversation the peer's {@code contextId} is bound to, if known. */
    Optional<String> findContextConversation(String principal, String contextId);

    /**
     * Binds the peer's {@code contextId} to a conversation, replacing any earlier
     * binding.
     */
    void bindContext(String principal, String contextId, String conversationId);
}
