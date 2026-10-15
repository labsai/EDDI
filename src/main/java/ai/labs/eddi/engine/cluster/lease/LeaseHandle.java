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

    /** Whether the lease was taken over from a holder that had died. */
    default boolean wasTakenOver() {
        return false;
    }
}
