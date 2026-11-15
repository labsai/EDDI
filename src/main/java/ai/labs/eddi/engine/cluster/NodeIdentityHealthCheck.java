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
import org.eclipse.microprofile.health.Readiness;

/**
 * Readiness is DOWN while another live JVM uses this node's id (C6 of the
 * cluster production-readiness review; detected by {@link ClusterPresence}):
 * twins break lease mutual exclusion, so the second one must not take traffic.
 * Always UP in single-node mode.
 */
@Readiness
@ApplicationScoped
public class NodeIdentityHealthCheck implements HealthCheck {

    private final ClusterConfig config;
    private final Instance<ClusterPresence> presence;

    @Inject
    public NodeIdentityHealthCheck(ClusterConfig config, Instance<ClusterPresence> presence) {
        this.config = config;
        this.presence = presence;
    }

    @Override
    public HealthCheckResponse call() {
        var builder = HealthCheckResponse.named("cluster-node-identity");
        if (!config.isNats() || !presence.isResolvable() || !presence.get().isDuplicate()) {
            return builder.up().build();
        }
        // Says what is wrong without naming the other JVM: the public health
        // endpoint is not the place for cluster topology.
        return builder.down().withData("reason", "another live node uses this node id").build();
    }
}
