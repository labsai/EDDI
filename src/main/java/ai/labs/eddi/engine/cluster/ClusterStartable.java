/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.cluster;

/**
 * A cluster component that needs to start work (subscriptions, periodic sweeps)
 * once the NATS connection manager is running. Called by
 * {@link ClusterBootstrap} only when {@code eddi.messaging.type=nats}; an
 * in-memory deployment never resolves an implementation.
 */
public interface ClusterStartable {
    void startCluster();
}
