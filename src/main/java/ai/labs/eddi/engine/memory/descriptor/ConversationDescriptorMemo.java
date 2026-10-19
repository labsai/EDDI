/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory.descriptor;

import ai.labs.eddi.datastore.IResourceStore.ResourceNotFoundException;
import ai.labs.eddi.datastore.IResourceStore.ResourceStoreException;
import ai.labs.eddi.engine.memory.descriptor.model.ConversationDescriptor;
import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.inject.Instance;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remembers, for the length of one request, which conversation descriptor the
 * request has already resolved.
 * <p>
 * A REST turn used to read the same descriptor three times before it started:
 * the ownership check in {@code RestAgentEngine}, the same check again in
 * {@code ConversationService.say}, and the soft-delete check right after it.
 * Each is a MongoDB round trip on the request thread, where the load test saw
 * most of its connection-pool waits. They now share one read.
 * <p>
 * Only the descriptor is remembered, never a decision: every caller still
 * compares the owner against its own identity, and the soft-delete check still
 * looks at where the descriptor was found. A store failure is not remembered
 * either, so the next caller retries and fails closed on its own. Any write
 * through {@link IConversationDescriptorStore} forgets the conversation (see
 * {@link #forget}), so a request that changes a descriptor never reads back its
 * own earlier view.
 * <p>
 * Outside a request (worker threads, scheduled jobs) there is no memo and every
 * lookup reads the store, as before.
 */
@RequestScoped
public class ConversationDescriptorMemo {

    /**
     * Conversation descriptors are written once and updated in place, so they never
     * leave version 0 — the archived copy a soft delete leaves keeps it too.
     */
    private static final int DESCRIPTOR_VERSION = 0;

    /**
     * Where a conversation's descriptor was found.
     *
     * @param descriptor
     *            the descriptor, or {@code null} when there is none at all
     * @param archived
     *            {@code true} when only the archived copy exists — the conversation
     *            was soft-deleted
     */
    public record Resolved(ConversationDescriptor descriptor, boolean archived) {
        public static final Resolved NONE = new Resolved(null, false);
    }

    // A request can hand work to another thread with its context propagated.
    private final Map<String, Resolved> resolved = new ConcurrentHashMap<>();

    /**
     * The live descriptor, else its archived copy, else {@link Resolved#NONE} —
     * read once per request when {@code memo} is available, else from the store
     * every time.
     *
     * @param memo
     *            the request-scoped memo, or {@code null} (a bean built outside
     *            CDI)
     * @throws ResourceStoreException
     *             when the store fails; nothing is remembered then
     */
    public static Resolved resolve(IConversationDescriptorStore store, Instance<ConversationDescriptorMemo> memo, String conversationId)
            throws ResourceStoreException {
        ConversationDescriptorMemo active = conversationId == null ? null : activeMemo(memo);
        if (active == null) {
            return read(store, conversationId);
        }
        Resolved known = active.lookup(conversationId);
        if (known != null) {
            return known;
        }
        Resolved read = read(store, conversationId);
        active.remember(conversationId, read);
        return read;
    }

    /**
     * Drops what this request remembered about the conversation. Called on every
     * descriptor write; a no-op outside a request.
     */
    public static void forget(Instance<ConversationDescriptorMemo> memo, String conversationId) {
        ConversationDescriptorMemo active = activeMemo(memo);
        if (active != null && conversationId != null) {
            active.drop(conversationId);
        }
    }

    private static ConversationDescriptorMemo activeMemo(Instance<ConversationDescriptorMemo> memo) {
        if (memo == null || !memo.isResolvable()) {
            return null;
        }
        try {
            ConversationDescriptorMemo active = memo.get();
            // A client proxy: an inactive request context only throws on first use.
            active.lookup("");
            return active;
        } catch (ContextNotActiveException e) {
            return null;
        }
    }

    // Instance state is reached through methods only: memo.get() hands out a client
    // proxy, and a field read on the proxy would bypass the request's instance.

    Resolved lookup(String conversationId) {
        return resolved.get(conversationId);
    }

    void remember(String conversationId, Resolved value) {
        resolved.put(conversationId, value);
    }

    void drop(String conversationId) {
        resolved.remove(conversationId);
    }

    private static Resolved read(IConversationDescriptorStore store, String conversationId) throws ResourceStoreException {
        try {
            try {
                var live = store.readDescriptor(conversationId, DESCRIPTOR_VERSION);
                if (live != null) {
                    return new Resolved(live, false);
                }
            } catch (ResourceNotFoundException e) {
                // Fall through to the archive: a soft delete drops only the live row.
            }
            var archived = store.readDescriptorWithHistory(conversationId, DESCRIPTOR_VERSION);
            return archived == null ? Resolved.NONE : new Resolved(archived, true);
        } catch (ResourceNotFoundException e) {
            return Resolved.NONE;
        }
    }
}
