/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.cluster.events;

import java.util.Map;
import java.util.function.Consumer;

/**
 * Tells the other nodes of the cluster that something they may have cached
 * changed here.
 * <p>
 * The publishing node has already applied the change locally, synchronously —
 * this bus only exists so the <em>other</em> nodes evict or reconcile too.
 * Receivers therefore ignore events from their own boot, and a received event
 * is never re-published. In single-node mode ({@link LocalEventBus}) both
 * methods are no-ops.
 */
public interface IClusterEventBus {

    /** Publishes an event to every other node. Never blocks, never throws. */
    void publish(String type, Map<String, Object> payload);

    /** Registers a handler for events of {@code type} from other nodes. */
    void subscribe(String type, Consumer<ClusterEvent> handler);

    /**
     * Registers a full local flush, run when this node may have missed events (a
     * gap in the event stream, a disconnection longer than the event retention, an
     * outbox overflow somewhere in the cluster).
     */
    void onResync(Runnable flush);

    /** True when events actually travel between nodes. */
    default boolean isClustered() {
        return false;
    }
}
