/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.cluster;

import ai.labs.eddi.engine.cluster.lease.NatsLeaseManager;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;
import org.jboss.logging.Logger;

import java.util.List;

/**
 * The single place that starts the cluster layer.
 * <p>
 * Validates {@code eddi.messaging.type} on every boot (a typo fails the boot
 * instead of silently leaving the deployment single-node), warns once about
 * properties of the old build-profile NATS coordinator that no longer do
 * anything, and — only when {@code nats} is selected — starts the connection
 * (asynchronously; boot never waits for NATS), presence and the lease
 * heartbeat.
 * <p>
 * No NATS bean has a startup observer of its own, so in {@code in-memory} mode
 * nothing of the cluster layer is instantiated and nothing about NATS is
 * logged.
 */
@ApplicationScoped
public class ClusterBootstrap {

    private static final Logger LOGGER = Logger.getLogger(ClusterBootstrap.class);

    /** Properties of the removed build-profile coordinator; accepted, but inert. */
    static final List<String> REMOVED_PROPERTIES = List.of("eddi.nats.stream-name", "eddi.nats.max-retries", "eddi.nats.ack-wait-seconds",
            "eddi.nats.stream-max-age", "eddi.nats.stream-max-messages", "eddi.nats.stream-max-bytes");

    private final ClusterConfig config;
    private final Config rawConfig;
    private final Instance<NatsConnectionManager> connections;
    private final Instance<ClusterPresence> presence;
    private final Instance<NatsLeaseManager> leases;
    private final Instance<ClusterStartable> startables;

    @Inject
    public ClusterBootstrap(ClusterConfig config, Config rawConfig, Instance<NatsConnectionManager> connections,
            Instance<ClusterPresence> presence, Instance<NatsLeaseManager> leases, Instance<ClusterStartable> startables) {
        this.config = config;
        this.rawConfig = rawConfig;
        this.connections = connections;
        this.presence = presence;
        this.leases = leases;
        this.startables = startables;
    }

    void onStart(@Observes
    @Priority(50) StartupEvent event) {
        config.validate();
        for (String removed : REMOVED_PROPERTIES) {
            if (rawConfig.getOptionalValue(removed, String.class).isPresent()) {
                LOGGER.warnf("%s is set but no longer has any effect: the build-profile NATS coordinator it configured was "
                        + "replaced by the runtime-selected cluster mode (eddi.messaging.type=nats). Remove it.", removed);
            }
        }
        if (!config.isNats()) {
            return;
        }
        NatsConnectionManager manager = connections.get();
        manager.start();
        presence.get().start();
        manager.onShutdown(() -> presence.get().leave());
        leases.get().start();
        for (ClusterStartable startable : startables) {
            startable.startCluster();
        }
    }
}
