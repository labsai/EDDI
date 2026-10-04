/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.cluster;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Who is in the cluster right now.
 * <p>
 * Every node writes {@code n.<nodeId>} into the {@code NODES} bucket every
 * {@code eddi.cluster.presence.interval} (10 s); the bucket's 30 s TTL removes
 * a node that stopped writing. The value carries the node's boot id (for fast
 * lease takeover after a restart), its version and a few load numbers for the
 * admin cluster view. Nothing here is on a turn's path.
 */
@ApplicationScoped
@Typed(ClusterPresence.class)
public class ClusterPresence {

    private static final Logger LOGGER = Logger.getLogger(ClusterPresence.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String KEY_PREFIX = "n.";
    private static final long MEMBER_CACHE_MILLIS = 3_000;

    private final NatsConnectionManager connections;
    private final ISharedKv nodes;
    private final MeterRegistry meterRegistry;
    private final long startedAt = System.currentTimeMillis();
    private final List<Supplier<Map<String, Object>>> contributors = new CopyOnWriteArrayList<>();
    private volatile List<Map<String, Object>> cachedMembers = List.of();
    private volatile long cachedAt;
    private volatile int lastKnownMembers = 1;

    @Inject
    public ClusterPresence(NatsConnectionManager connections, NatsSharedStateFactory sharedState, MeterRegistry meterRegistry) {
        this(connections, sharedState.bucket(SharedBucket.NODES.withTtl(connections.config().presenceInterval().multipliedBy(3))),
                meterRegistry);
    }

    ClusterPresence(NatsConnectionManager connections, ISharedKv nodes, MeterRegistry meterRegistry) {
        this.connections = connections;
        this.nodes = nodes;
        this.meterRegistry = meterRegistry;
    }

    /** Adds load numbers to this node's presence record. */
    public void contribute(Supplier<Map<String, Object>> contributor) {
        contributors.add(contributor);
    }

    public void start() {
        Gauge.builder("eddi.cluster.members", this, p -> p.lastKnownMembers).description("Cluster members seen in the presence bucket")
                .register(meterRegistry);
        long period = connections.config().presenceInterval().toMillis();
        connections.scheduler().scheduleAtFixedRate(this::publishSafely, 0, period, TimeUnit.MILLISECONDS);
        connections.onConnected(this::publishSafely);
    }

    void publishSafely() {
        if (!connections.isConnected()) {
            return;
        }
        try {
            nodes.put(KEY_PREFIX + connections.node().nodeId(), JSON.writeValueAsBytes(record()));
        } catch (IOException | RuntimeException e) {
            LOGGER.debugf("Presence write failed: %s", e.getMessage());
        }
    }

    Map<String, Object> record() {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("node", connections.node().nodeId());
        record.put("boot", connections.node().bootId());
        record.put("version", Optional.ofNullable(ClusterPresence.class.getPackage().getImplementationVersion()).orElse("dev"));
        record.put("startedAt", startedAt);
        record.put("updatedAt", System.currentTimeMillis());
        record.put("natsRtt", connections.rttMillis());
        record.put("degraded", connections.isDegraded());
        for (Supplier<Map<String, Object>> contributor : contributors) {
            try {
                record.putAll(contributor.get());
            } catch (RuntimeException e) {
                LOGGER.debugf("Presence contributor failed: %s", e.getMessage());
            }
        }
        return record;
    }

    /**
     * The presence records of every live member, sorted by node id. Cached ~3 s.
     */
    public List<Map<String, Object>> members() {
        long now = System.currentTimeMillis();
        if (now - cachedAt < MEMBER_CACHE_MILLIS) {
            return cachedMembers;
        }
        List<Map<String, Object>> members = new ArrayList<>();
        try {
            for (String key : nodes.keys()) {
                if (!key.startsWith(KEY_PREFIX)) {
                    continue;
                }
                nodes.get(key).ifPresent(v -> {
                    try {
                        members.add(JSON.readValue(v.value(), new TypeReference<Map<String, Object>>() {
                        }));
                    } catch (IOException e) {
                        LOGGER.debugf("Unreadable presence record %s", key);
                    }
                });
            }
        } catch (ClusterUnavailableException e) {
            return cachedMembers;
        }
        members.sort(Comparator.comparing(m -> String.valueOf(m.get("node"))));
        cachedMembers = List.copyOf(members);
        cachedAt = now;
        lastKnownMembers = Math.max(1, members.size());
        return cachedMembers;
    }

    /** The presence record of {@code nodeId}, if it is alive. */
    public Optional<Map<String, Object>> member(String nodeId) {
        try {
            return memberStrict(nodeId);
        } catch (ClusterUnavailableException e) {
            return Optional.empty();
        }
    }

    /**
     * Like {@link #member}, but a lookup that failed throws instead of answering
     * "not present": a caller deciding whether a node is gone must tell the two
     * apart.
     */
    public Optional<Map<String, Object>> memberStrict(String nodeId) {
        return nodes.get(KEY_PREFIX + nodeId).map(v -> {
            try {
                return JSON.readValue(v.value(), new TypeReference<Map<String, Object>>() {
                });
            } catch (IOException e) {
                return null;
            }
        });
    }

    /** The member count last observed; never below 1 (this node). */
    public int lastKnownMembers() {
        return lastKnownMembers;
    }

    /** Removes this node's record — part of a clean shutdown. */
    public void leave() {
        try {
            nodes.delete(KEY_PREFIX + connections.node().nodeId());
        } catch (RuntimeException e) {
            LOGGER.debugf("Presence delete failed: %s", e.getMessage());
        }
    }
}
