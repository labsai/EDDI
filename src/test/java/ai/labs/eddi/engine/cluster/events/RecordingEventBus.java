/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.cluster.events;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Test double: records what a component publishes and lets a test deliver an
 * event "from another node" to what it subscribed.
 */
public class RecordingEventBus implements IClusterEventBus {

    public record Published(String type, Map<String, Object> payload) {
    }

    public final List<Published> published = new CopyOnWriteArrayList<>();
    private final Map<String, List<Consumer<ClusterEvent>>> handlers = new ConcurrentHashMap<>();
    public final List<Runnable> resyncs = new CopyOnWriteArrayList<>();

    @Override
    public void publish(String type, Map<String, Object> payload) {
        published.add(new Published(type, payload));
    }

    @Override
    public void subscribe(String type, Consumer<ClusterEvent> handler) {
        handlers.computeIfAbsent(type, t -> new CopyOnWriteArrayList<>()).add(handler);
    }

    @Override
    public void onResync(Runnable flush) {
        resyncs.add(flush);
    }

    @Override
    public boolean isClustered() {
        return true;
    }

    /** Delivers an event as if another node had published it. */
    public void deliver(String type, Map<String, Object> payload) {
        ClusterEvent event = new ClusterEvent(1, "id", type, "other-node", "other-boot", 0L, null, payload);
        for (Consumer<ClusterEvent> handler : new ArrayList<>(handlers.getOrDefault(type, List.of()))) {
            handler.accept(event);
        }
    }

    public List<Published> ofType(String type) {
        return published.stream().filter(p -> p.type().equals(type)).toList();
    }
}
