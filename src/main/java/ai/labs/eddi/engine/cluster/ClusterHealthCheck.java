/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.cluster;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Readiness;
import ai.labs.eddi.engine.cluster.lease.NatsLeaseManager;

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
 * <p>
 * The details — mode, NATS state, node id, member count, drain state — are
 * reported only with {@code eddi.health.verbose=true}. {@code /q/health} is
 * public (probes need it), and the node ids and cluster size are reconnaissance
 * for anyone who can reach the port; the status alone is what a load balancer
 * or kubelet reads. The authenticated
 * {@code GET /administration/cluster/overview} reports all of it regardless.
 */
@Readiness
@ApplicationScoped
public class ClusterHealthCheck implements HealthCheck {

    private final ClusterConfig config;
    private final Instance<NatsConnectionManager> connections;
    private final Instance<ClusterPresence> presence;
    private final Instance<NatsLeaseManager> leases;
    private final boolean verbose;

    @Inject
    public ClusterHealthCheck(ClusterConfig config, Instance<NatsConnectionManager> connections, Instance<ClusterPresence> presence,
            Instance<NatsLeaseManager> leases, @ConfigProperty(name = "eddi.health.verbose", defaultValue = "false") boolean verbose) {
        this.config = config;
        this.connections = connections;
        this.presence = presence;
        this.leases = leases;
        this.verbose = verbose;
    }

    @Override
    public HealthCheckResponse call() {
        HealthCheckResponseBuilder builder = HealthCheckResponse.named("cluster");
        if (!config.isNats()) {
            if (verbose) {
                builder.withData("mode", ClusterConfig.IN_MEMORY);
            }
            return builder.up().build();
        }
        NatsConnectionManager manager = connections.get();
        boolean degraded = manager.isDegraded();
        // An administrator drained this node to restart it: out of the load balancer,
        // whatever NATS says. Undrained (or restarted), it is ready again.
        boolean draining = leases.isResolvable() && leases.get().isDraining();
        if (verbose) {
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
            builder.withData("draining", draining);
        }
        boolean down = (degraded && config.readinessRequireNats()) || draining;
        return (down ? builder.down() : builder.up()).build();
    }
}
