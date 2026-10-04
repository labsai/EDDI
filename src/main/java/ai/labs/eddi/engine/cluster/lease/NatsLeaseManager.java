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
import io.micrometer.core.instrument.MeterRegistry;
import io.nats.client.Connection;
import io.nats.client.Dispatcher;
import jakarta.inject.Singleton;
import jakarta.enterprise.inject.Typed;
import jakarta.inject.Inject;

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

    @Inject
    public NatsLeaseManager(NatsConnectionManager connections, NatsSharedStateFactory sharedState, ClusterPresence presence,
            MeterRegistry meterRegistry) {
        super(sharedState.bucket(SharedBucket.LEASES.withTtl(connections.config().leaseTtl())), connections.node(),
                connections.config(), connections.scheduler(), Executors.newVirtualThreadPerTaskExecutor(),
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
                }, meterRegistry);
        connections.onConnected(this::sweepOwnStaleLeases);
        connections.onShutdown(this::releaseAll);
    }

    /**
     * Release notifications over core NATS; re-subscribed on every new connection.
     */
    static final class NatsNotifier implements LeaseNotifier {
        private final NatsConnectionManager connections;
        private final ClusterSubjects subjects;
        private final CopyOnWriteArrayList<Consumer<String>> listeners = new CopyOnWriteArrayList<>();
        private volatile Connection subscribedOn;

        NatsNotifier(NatsConnectionManager connections) {
            this.connections = connections;
            this.subjects = new ClusterSubjects(connections.config().natsPrefix());
            connections.onConnected(this::subscribe);
        }

        private synchronized void subscribe() {
            Connection connection = connections.requireConnected();
            if (subscribedOn == connection) {
                return; // the client re-establishes core subscriptions after a reconnect
            }
            String prefix = subjects.leaseReleased("");
            Dispatcher dispatcher = connection.createDispatcher(msg -> {
                String key = msg.getSubject().substring(prefix.length());
                for (Consumer<String> listener : listeners) {
                    listener.accept(key);
                }
            });
            dispatcher.subscribe(subjects.leaseReleasedWildcard());
            subscribedOn = connection;
        }

        @Override
        public void publishReleased(String key) {
            connections.requireConnected().publish(subjects.leaseReleased(key), "1".getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public void onReleased(Consumer<String> listener) {
            listeners.add(listener);
        }
    }
}
