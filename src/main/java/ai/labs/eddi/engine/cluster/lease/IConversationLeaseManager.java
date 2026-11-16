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

    /**
     * Like {@link #peek}, but an unreachable lease store is an exception, not "no
     * lease": a caller that acts on "nobody holds it" (a recovery that resumes a
     * conversation) must not mistake a NATS outage for that. Throws
     * {@code ClusterUnavailableException} when the holder could not be read.
     */
    default Optional<LeaseInfo> peekStrict(String conversationId) {
        return peekKeyStrict(CONVERSATION + conversationId);
    }

    /** {@link #peekStrict} for an arbitrary lease key. */
    default Optional<LeaseInfo> peekKeyStrict(String key) {
        return peekKey(key);
    }

    /**
     * Offers a held lease to the next turn of the same conversation queued on this
     * node, instead of releasing it and acquiring it again. No I/O.
     * <p>
     * Answers {@code true} only while the lease is still held, has enough validity
     * left for another turn, and no other node is known to be waiting for it (and
     * only a bounded number of times in a row, so a busy node cannot keep a
     * conversation from a node whose interest it never heard of). The handle is
     * then reset for its next turn: lost callbacks registered by the finished turn
     * are dropped and {@link LeaseHandle#wasTakenOver()} reads {@code false}. The
     * caller keeps the lease and must still release it, either after the next turn
     * or at once when no turn takes it.
     */
    default boolean handOver(LeaseHandle handle) {
        return false;
    }

    /** Leases this node currently holds. */
    int heldCount();

    /**
     * First step of a clean shutdown: refuses every new acquisition and fails the
     * pending ones with {@link LeaseUnavailableException.Reason#SHUTTING_DOWN}, so
     * a turn still waiting for its lease is answered (409 + Retry-After, retried on
     * another node) instead of holding the drain until its acquire timeout. Leases
     * already held stay held until their turns finish.
     */
    default void stopAcquiring() {
    }

    /**
     * Last step of a clean shutdown: refuses new acquisitions and releases every
     * lease this node still holds, so the next turn of those conversations runs
     * elsewhere at once instead of after the lease TTL.
     */
    default void releaseAll() {
    }

    /** True when leases are cluster-wide (NATS mode). */
    default boolean isClustered() {
        return false;
    }
}
