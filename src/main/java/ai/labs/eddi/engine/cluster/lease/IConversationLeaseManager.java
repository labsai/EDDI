/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.cluster.lease;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/**
 * Cluster-wide mutual exclusion for a conversation (and, under other key
 * prefixes, for a group discussion or a leader role).
 * <p>
 * A conversation turn runs on the node that received the request, but only
 * while that node holds the conversation's lease; turns of one conversation
 * therefore never overlap anywhere in the cluster. The lease's revision is a
 * <em>fencing token</em> that only grows: the database refuses a write carrying
 * a token older than the newest one it has seen, so a node that lost its lease
 * (it hung, was partitioned, or was killed and its lease expired) cannot
 * overwrite the turn that ran after it.
 * <p>
 * In-memory mode uses {@link LocalLeaseManager}, which hands out an
 * already-held, unfenced lease: a strict no-op.
 */
public interface IConversationLeaseManager {

    /** Key prefix of conversation leases. */
    String CONVERSATION = "c.";
    /** Key prefix of group-discussion leases. */
    String GROUP = "g.";
    /** Key prefix of leader roles. */
    String LEADER = "leader.";

    /**
     * Acquires the lease of {@code conversationId}.
     *
     * @return completes with the held lease, or exceptionally with
     *         {@link LeaseUnavailableException} when {@code maxWait} passed
     *         (another node kept it) or NATS is down and the degraded policy is
     *         {@code reject}
     */
    default CompletionStage<LeaseHandle> acquire(String conversationId, Duration maxWait) {
        return acquireKey(CONVERSATION + conversationId, maxWait);
    }

    /** Acquires an arbitrary lease key (see the prefixes above). */
    CompletionStage<LeaseHandle> acquireKey(String key, Duration maxWait);

    /**
     * One attempt, no waiting — for leader election.
     *
     * @return the lease, or empty when somebody holds it or NATS is unreachable
     */
    Optional<LeaseHandle> tryAcquireKey(String key);

    /** Releases a lease. Idempotent; a lease lost meanwhile is not an error. */
    void release(LeaseHandle handle);

    /** Who holds the lease of {@code conversationId}, if anybody does. */
    default Optional<LeaseInfo> peek(String conversationId) {
        return peekKey(CONVERSATION + conversationId);
    }

    Optional<LeaseInfo> peekKey(String key);

    /** Leases this node currently holds. */
    int heldCount();

    /** Releases every lease this node holds — part of a clean shutdown. */
    default void releaseAll() {
    }

    /** True when leases are cluster-wide (NATS mode). */
    default boolean isClustered() {
        return false;
    }
}
