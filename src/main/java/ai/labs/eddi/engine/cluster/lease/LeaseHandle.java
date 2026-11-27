/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.cluster.lease;

/**
 * A held lease.
 * <p>
 * {@link #fence()} is the fencing token the turn's database writes carry, or
 * {@code null} for an unfenced lease (in-memory mode, or a degraded-mode turn
 * that ran without NATS). {@link #onLost(Runnable)} fires once if the lease is
 * taken over while the turn is still running; the conversation layer uses it to
 * cancel the pipeline at the next task boundary.
 */
public interface LeaseHandle {

    /** The full lease key, e.g. {@code c.<conversationId>}. */
    String key();

    /** The conversation id, or the key without its prefix for other kinds. */
    String conversationId();

    /** The fencing token, or {@code null} when unfenced. */
    Long fence();

    boolean isLost();

    /**
     * Runs {@code callback} once if the lease is lost; at once if it already was.
     */
    void onLost(Runnable callback);

    /**
     * Asks the lease store's authority (in NATS mode the bucket's stream leader)
     * whether this lease is still the key's current value. A network round trip;
     * {@code false} when it is not, when it cannot be told, and for leases that
     * cannot be confirmed at all.
     */
    default boolean confirmHeld() {
        return false;
    }

    /**
     * Raises {@link #fence()} to {@code fence} for this and every later write under
     * the lease — never lowers it. For a holder that confirmed its lease and moved
     * the conversation's stored fence above a fence from a revision range the lease
     * bucket lost (see {@code IConversationMemoryStore#liftFence}).
     */
    default void liftFence(long fence) {
        throw new UnsupportedOperationException("this lease has no fence to lift");
    }

    /**
     * Whether this handle has itself put its current {@link #fence()} on the
     * conversation — by a raise that moved the stored fence, or a lift. Only then
     * is a stored fence equal to this lease's its own (a turn the lease was handed
     * to raises it again); for any other holder an equal number is a collision.
     */
    default boolean fenceEstablished() {
        return false;
    }

    /** Records that this handle put its current fence on the conversation. */
    default void establishFence() {
        // nothing to remember for leases without a fence
    }

    /** Whether the lease was taken over from a holder that had died. */
    default boolean wasTakenOver() {
        return false;
    }
}
