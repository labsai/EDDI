/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.cluster;

import ai.labs.eddi.engine.model.DeadLetterEntry;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Durable, cluster-wide dead letters of conversation turns. Every node sees
 * every entry, and an entry is removed exactly once — by discard, by a
 * successful replay, by purge, or by GDPR erasure of the conversation.
 * <p>
 * All methods throw {@link ClusterUnavailableException} while NATS is down.
 */
public interface IDeadLetterStore {

    /**
     * Stores an entry.
     *
     * @return the entry id (the stream sequence)
     */
    default String append(String conversationId, String error, long timestamp, Map<String, Object> turn) {
        return append(conversationId, error, timestamp, turn, null, null);
    }

    /**
     * Stores an entry with why it was dead-lettered.
     *
     * @param reason
     *            {@code fenced}, {@code timeout} or {@code failed}
     * @param fence
     *            the fencing tokens of a fenced write, else {@code null}
     * @return the entry id (the stream sequence)
     */
    String append(String conversationId, String error, long timestamp, Map<String, Object> turn, String reason, Map<String, Object> fence);

    /**
     * The entries of one conversation, oldest first — read through the
     * conversation's own subject, so it does not scan the others.
     */
    default List<DeadLetterEntry> listConversation(String conversationId, int limit) {
        return list(Integer.MAX_VALUE, null).stream().filter(e -> conversationId.equals(e.conversationId())).limit(limit).toList();
    }

    /**
     * Entries in id order.
     *
     * @param after
     *            exclusive lower bound, {@code null} for the start
     */
    List<DeadLetterEntry> list(int limit, String after);

    Optional<DeadLetterEntry> get(String id);

    /** @return false when no such entry exists */
    boolean delete(String id);

    int purge();

    /** Removes every entry of one conversation (GDPR erasure). */
    int purgeConversation(String conversationId);

    long count();

    /**
     * Turn dead letters only — {@link #count()} also counts audit entries parked in
     * the same stream, which cannot be listed, replayed or discarded here.
     */
    default long countTurns() {
        return count();
    }
}
