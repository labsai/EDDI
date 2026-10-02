/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.cluster.events;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;

import java.util.Map;
import java.util.function.Consumer;

/**
 * Single-node event bus: there is nobody to tell. The in-process invalidation
 * calls that existed before clustering stay exactly where they were.
 */
@ApplicationScoped
@Typed(LocalEventBus.class)
public class LocalEventBus implements IClusterEventBus {

    @Override
    public void publish(String type, Map<String, Object> payload) {
        // single node: the local change was already applied by the caller
    }

    @Override
    public void subscribe(String type, Consumer<ClusterEvent> handler) {
        // no remote events
    }

    @Override
    public void onResync(Runnable flush) {
        // nothing can be missed
    }
}
