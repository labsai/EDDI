/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.cluster.lease;

import ai.labs.eddi.engine.cluster.ClusterPresence;
import ai.labs.eddi.engine.cluster.ClusterSubjects;
import ai.labs.eddi.engine.cluster.NatsConnectionManager;
import ai.labs.eddi.engine.cluster.NatsSharedStateFactory;
import ai.labs.eddi.engine.cluster.SharedBucket;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.MeterRegistry;
import io.nats.client.Connection;
import io.nats.client.Dispatcher;
import jakarta.inject.Singleton;
import jakarta.enterprise.inject.Typed;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * The cluster-mode {@link IConversationLeaseManager}: {@link KvLeaseManager} on
 * the {@code <prefix>_LEASES} KV bucket, with release notifications on core
 * NATS and presence-based takeover. Only instantiated when
 * {@code eddi.messaging.type=nats}.
 */
@Singleton
@Typed(NatsLeaseManager.class)
public class NatsLeaseManager extends KvLeaseManager {

    private static final Logger LOGGER = Logger.getLogger(NatsLeaseManager.class);

    @Inject
    public NatsLeaseManager(NatsConnectionManager connections, NatsSharedStateFactory sharedState, ClusterPresence presence,
            MeterRegistry meterRegistry) {
        super(sharedState.bucket(SharedBucket.LEASES.withTtl(connections.config().leaseTtl())), connections.node(),
                connections.config(), connections.leaseScheduler(), Executors.newVirtualThreadPerTaskExecutor(),
                new NatsNotifier(connections), new ClusterView() {
                    @Override
                    public boolean isConnected() {
                        return connections.isConnected();
                    }

                    @Override
                    public boolean isDegraded() {
                        return connections.isDegraded();
                    }

                    @Override
                    public Optional<String> liveBoot(String nodeId) {
                        return presence.memberStrict(nodeId).map(m -> String.valueOf(m.get("boot")));
                    }

                    @Override
                    public boolean isDuplicateNode() {
                        return presence.isDuplicate();
                    }

                    @Override
                    public boolean isNodeIdentityConfirmed() {
                        return presence.isIdentityConfirmed();
                    }
                }, meterRegistry);
        connections.onConnected(this::sweepOwnStaleLeases);
        // The first connect usually comes before presence has settled the id (one
        // presence interval later), so the sweep is retried when it does.
        presence.onIdentityConfirmed(this::sweepOwnStaleLeases);
        connections.onShutdown(this::releaseAll);
    }

    /**
     * Lease notifications over core NATS, re-subscribed on every new connection.
     * <p>
     * Two kinds of subject:
     * <ul>
     * <li>{@code <root>.lease.released.<key>} — the broadcast of earlier versions,
     * which every node received for every release. This version still listens to it
     * (nodes of an earlier version send it during a rolling upgrade, and an
     * administrative force-release still broadcasts), but no longer sends it for a
     * plain release.</li>
     * <li>{@code <root>.lease.to.<nodeId>} — addressed to one node: "I am waiting
     * for this key" (to the holder) and "released, at this delete-marker revision"
     * (to each node that said it was waiting). A release reaches only the nodes
     * that wait for it.</li>
     * </ul>
     * Both are hints: losing one costs a poll interval, never mutual exclusion.
     */
    static final class NatsNotifier implements LeaseNotifier {
        private static final ObjectMapper JSON = new ObjectMapper();
        /** Version of the addressed payload. */
        static final int VERSION = 1;

        private final NatsConnectionManager connections;
        private final ClusterSubjects subjects;
        private final CopyOnWriteArrayList<Consumer<String>> listeners = new CopyOnWriteArrayList<>();
        private volatile AddressedListener addressed;
        private volatile String addressedNode;
        private volatile Connection subscribedOn;
        private volatile Dispatcher dispatcher;
        private volatile String subscribedNode;

        NatsNotifier(NatsConnectionManager connections) {
            this.connections = connections;
            this.subjects = new ClusterSubjects(connections.config().natsPrefix());
            connections.onConnected(this::subscribe);
        }

        /** The subject of the notifications addressed to {@code nodeId}. */
        String addressedTo(String nodeId) {
            return subjects.root() + ".lease.to." + nodeId;
        }

        private synchronized void subscribe() {
            Connection connection = connections.requireConnected();
            if (subscribedOn != connection) {
                // A new connection; on a reconnect the client re-establishes the core
                // subscriptions of the old one itself.
                String prefix = subjects.leaseReleased("");
                dispatcher = connection.createDispatcher(msg -> {
                    if (msg.getSubject().startsWith(prefix)) {
                        String key = msg.getSubject().substring(prefix.length());
                        for (Consumer<String> listener : listeners) {
                            listener.accept(key);
                        }
                    } else {
                        deliverAddressed(msg.getData());
                    }
                });
                dispatcher.subscribe(subjects.leaseReleasedWildcard());
                subscribedOn = connection;
                subscribedNode = null;
            }
            String nodeId = addressedNode;
            if (nodeId != null && !nodeId.equals(subscribedNode)) {
                dispatcher.subscribe(addressedTo(nodeId));
                subscribedNode = nodeId;
            }
        }

        void deliverAddressed(byte[] payload) {
            AddressedListener listener = addressed;
            if (listener == null || payload == null) {
                return;
            }
            try {
                JsonNode n = JSON.readTree(payload);
                String key = n.path("key").asText("");
                if (key.isEmpty()) {
                    return;
                }
                switch (n.path("op").asText("")) {
                    case "released" -> listener.released(key, n.path("rev").asLong(0));
                    case "waiting" -> listener.waiting(key, n.path("from").asText(null));
                    default -> {
                        // an op of a later version: ignored
                    }
                }
            } catch (IOException | RuntimeException e) {
                LOGGER.debugf("Unreadable lease notification ignored: %s", e.getMessage());
            }
        }

        @Override
        public void publishReleased(String key) {
            connections.requireConnected().publish(subjects.leaseReleased(key), "1".getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public void onReleased(Consumer<String> listener) {
            listeners.add(listener);
        }

        @Override
        public void publishReleasedTo(String toNode, String key, long deleteRevision) {
            ObjectNode payload = JSON.createObjectNode().put("v", VERSION).put("op", "released").put("key", key).put("rev", deleteRevision);
            publish(toNode, payload);
        }

        @Override
        public void publishWaiting(String toNode, String key, String fromNode) {
            ObjectNode payload = JSON.createObjectNode().put("v", VERSION).put("op", "waiting").put("key", key).put("from", fromNode);
            publish(toNode, payload);
        }

        private void publish(String toNode, ObjectNode payload) {
            try {
                connections.requireConnected().publish(addressedTo(toNode), JSON.writeValueAsBytes(payload));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public synchronized void onAddressed(String nodeId, AddressedListener listener) {
            this.addressed = listener;
            this.addressedNode = nodeId;
            if (connections.isConnected()) {
                try {
                    subscribe();
                } catch (RuntimeException e) {
                    LOGGER.debugf("Lease notifications not subscribed yet (subscribed on the next connect): %s", e.getMessage());
                }
            }
        }
    }
}
