/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.audit;

import java.util.OptionalLong;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * What the audit ledger needs from the cluster layer when
 * {@code eddi.messaging.type=nats}: chain positions allocated cluster-wide, a
 * shared dead-letter sink, and the broadcast of a started erasure.
 * <p>
 * {@link #isClustered()} is false on a single node, and the ledger then keeps
 * its original node-local code paths unchanged.
 */
public interface IAuditClusterSupport {

    boolean isClustered();

    /**
     * Allocates the next chain position of {@code conversationId} cluster-wide (a
     * compare-and-set increment of one shared counter per conversation).
     *
     * @param seed
     *            the next free position as the store sees it, used when the counter
     *            does not exist yet
     * @return the position, or empty when the counter could not be reached — the
     *         entry is then recorded unsequenced, which verification reports as
     *         UNAVAILABLE rather than risk a duplicate position (BROKEN)
     */
    OptionalLong nextSequence(String conversationId, LongSupplier seed);

    /**
     * Stores an audit entry that could not be persisted; false when it could not.
     */
    boolean publishDeadLetter(String json);

    /** Tells every other node that the erasure of {@code userId} started. */
    void announceUserErased(String userId);

    /** Receives the hashes of users erased on other nodes. */
    void onUserErased(Consumer<String> userIdHashHandler);
}
