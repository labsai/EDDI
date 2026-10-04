/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.cluster;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Readiness;

/**
 * Cluster readiness, present in both modes.
 * <p>
 * In-memory: always UP. Cluster mode: UP with the NATS connection state,
 * whether the node is degraded and since when, the node id and the member count
 * — and DOWN only when the operator opted in with
 * {@code eddi.cluster.readiness.require-nats=true} and the node is degraded.
 * <p>
 * The old NATS health check reported DOWN on any NATS blip. Since every replica
 * sees the same NATS outage at the same moment, that emptied the load balancer
 * of every node at once — a total outage caused by a component the turns did
 * not even need. Liveness never depends on NATS.
 */
@Readiness
@ApplicationScoped
public class ClusterHealthCheck implements HealthCheck {

    private final ClusterConfig config;
    private final Instance<NatsConnectionManager> connections;
    private final Instance<ClusterPresence> presence;

    @Inject
    public ClusterHealthCheck(ClusterConfig config, Instance<NatsConnectionManager> connections, Instance<ClusterPresence> presence) {
        this.config = config;
        this.connections = connections;
        this.presence = presence;
    }

    @Override
    public HealthCheckResponse call() {
        HealthCheckResponseBuilder builder = HealthCheckResponse.named("cluster");
        if (!config.isNats()) {
            return builder.up().withData("mode", ClusterConfig.IN_MEMORY).build();
        }
        NatsConnectionManager manager = connections.get();
        boolean degraded = manager.isDegraded();
        builder.withData("mode", ClusterConfig.NATS)
                .withData("nats", manager.isConnected() ? "CONNECTED" : "DISCONNECTED")
                .withData("degraded", degraded)
                .withData("nodeId", manager.node().nodeId());
        long since = manager.unavailableSinceMillis();
        if (degraded && since > 0) {
            builder.withData("degradedSince", since);
        }
        if (manager.isConnected()) {
            builder.withData("members", presence.get().members().size());
        }
        boolean down = degraded && config.readinessRequireNats();
        return (down ? builder.down() : builder.up()).build();
    }
}
