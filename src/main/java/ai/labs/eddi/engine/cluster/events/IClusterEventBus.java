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

    /**
     * Publishes a transient event to the nodes connected right now: at most once,
     * never stored, never replayed, never queued. When this node is disconnected
     * the event is dropped — it never takes a place in the outbox that cache
     * invalidations depend on, so it can neither overflow it (and force a cluster
     * wide resync) nor push invalidations out of the stream's retention. For hints
     * whose loss a client recovers from by refetching, such as the per-user event
     * stream. Delivered to the same {@link #subscribe} handlers. Never blocks,
     * never throws; a no-op in single-node mode.
     */
    default void publishTransient(String type, Map<String, Object> payload) {
        // single node: nobody to tell
    }

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
