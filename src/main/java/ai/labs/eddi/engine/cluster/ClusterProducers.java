/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.cluster;

import ai.labs.eddi.engine.cluster.events.IClusterEventBus;
import ai.labs.eddi.engine.cluster.events.JetStreamEventBus;
import ai.labs.eddi.engine.cluster.events.LocalEventBus;
import ai.labs.eddi.engine.cluster.lease.IConversationLeaseManager;
import ai.labs.eddi.engine.cluster.lease.LocalLeaseManager;
import ai.labs.eddi.engine.cluster.lease.NatsLeaseManager;
import ai.labs.eddi.engine.cluster.rpc.IClusterRpc;
import ai.labs.eddi.engine.cluster.rpc.LocalClusterRpc;
import ai.labs.eddi.engine.cluster.rpc.NatsClusterRpc;
import ai.labs.eddi.engine.runtime.IConversationCoordinator;
import ai.labs.eddi.engine.runtime.internal.ClusterConversationCoordinator;
import ai.labs.eddi.engine.runtime.internal.InMemoryConversationCoordinator;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

/**
 * Runtime selection of the messaging implementation, following the
 * {@code DataStoreProducers} pattern: {@code eddi.messaging.type} is read at
 * runtime, and only the selected implementation is ever resolved from its
 * {@code Instance}. Every concrete bean is {@code @Typed} to its own class, so
 * the interface types have exactly one bean each — the producer here.
 * <p>
 * In {@code in-memory} mode no NATS class is instantiated: the
 * {@code Instance<Nats…>} handles are never resolved.
 */
@ApplicationScoped
public class ClusterProducers {

    private final ClusterConfig config;

    @Inject
    public ClusterProducers(ClusterConfig config) {
        this.config = config;
    }

    @Produces
    @ApplicationScoped
    public IConversationCoordinator coordinator(Instance<InMemoryConversationCoordinator> inMemory,
                                                Instance<ClusterConversationCoordinator> cluster) {
        return config.isNats() ? cluster.get() : inMemory.get();
    }

    @Produces
    @ApplicationScoped
    public IConversationLeaseManager leaseManager(Instance<LocalLeaseManager> local, Instance<NatsLeaseManager> nats) {
        return config.isNats() ? nats.get() : local.get();
    }

    @Produces
    @ApplicationScoped
    public ISharedStateFactory sharedStateFactory(Instance<LocalSharedStateFactory> local, Instance<NatsSharedStateFactory> nats) {
        return config.isNats() ? nats.get() : local.get();
    }

    @Produces
    @ApplicationScoped
    public IClusterEventBus eventBus(Instance<LocalEventBus> local, Instance<JetStreamEventBus> nats) {
        return config.isNats() ? nats.get() : local.get();
    }

    @Produces
    @ApplicationScoped
    public IClusterRpc rpc(Instance<LocalClusterRpc> local, Instance<NatsClusterRpc> nats) {
        return config.isNats() ? nats.get() : local.get();
    }
}
