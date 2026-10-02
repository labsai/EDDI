/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.cluster;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.InetAddress;
import java.util.Optional;
import java.util.UUID;

/**
 * Who this JVM is in the cluster.
 * <p>
 * {@link #nodeId()} is stable across restarts of the same pod (it defaults to
 * the host name, which a StatefulSet or the downward API keeps);
 * {@link #bootId()} is new on every start. The pair is what lets a restarted
 * node recognise and delete the leases its previous incarnation still held,
 * instead of waiting for them to expire.
 * <p>
 * The node id is restricted to characters that are safe as a NATS subject token
 * and a KV key segment; anything else is replaced with {@code _}.
 */
@ApplicationScoped
public class NodeIdentity {

    private final String nodeId;
    private final String bootId;

    @Inject
    public NodeIdentity(ClusterConfig config,
            @ConfigProperty(name = "eddi.schedule.instance-id") Optional<String> scheduleInstanceId) {
        this(resolve(config.nodeId(), scheduleInstanceId), UUID.randomUUID().toString().substring(0, 8));
    }

    public NodeIdentity(String nodeId, String bootId) {
        this.nodeId = safeToken(nodeId);
        this.bootId = bootId;
    }

    static String resolve(Optional<String> configured, Optional<String> scheduleInstanceId) {
        if (configured.isPresent() && !configured.get().isBlank()) {
            return configured.get().trim();
        }
        if (scheduleInstanceId.isPresent() && !scheduleInstanceId.get().isBlank()) {
            return scheduleInstanceId.get().trim();
        }
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "node-" + ProcessHandle.current().pid();
        }
    }

    /** Replaces everything that is not safe in a NATS subject token or KV key. */
    static String safeToken(String raw) {
        String token = raw == null ? "" : raw.replaceAll("[^A-Za-z0-9_-]", "_");
        return token.isEmpty() ? "node" : token;
    }

    public String nodeId() {
        return nodeId;
    }

    public String bootId() {
        return bootId;
    }

    @Override
    public String toString() {
        return nodeId + "/" + bootId;
    }
}
