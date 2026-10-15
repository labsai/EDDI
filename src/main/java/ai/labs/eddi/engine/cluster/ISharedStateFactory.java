/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.cluster;

/**
 * Hands out shared buckets.
 * <p>
 * {@link #isShared()} tells a caller whether a bucket really is cluster-wide.
 * In-memory mode returns {@code false}, and every component that has a
 * cluster-wide variant then keeps its original node-local code path verbatim —
 * the single-node default behaves exactly as it did before clustering existed.
 */
public interface ISharedStateFactory {

    ISharedKv bucket(SharedBucket spec);

    /** True when the buckets are shared between nodes (NATS mode). */
    boolean isShared();
}
