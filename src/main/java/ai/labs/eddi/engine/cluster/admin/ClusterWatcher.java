/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.cluster.admin;

import ai.labs.eddi.engine.cluster.ClusterConfig;
import ai.labs.eddi.engine.cluster.ClusterPresence;
import ai.labs.eddi.engine.cluster.ClusterStartable;
import ai.labs.eddi.engine.cluster.NatsConnectionManager;
import ai.labs.eddi.engine.cluster.events.ClusterEvent;
import ai.labs.eddi.engine.cluster.events.IClusterEventBus;
import ai.labs.eddi.engine.cluster.lease.LeaseInfo;
import ai.labs.eddi.engine.cluster.lease.NatsLeaseManager;
import ai.labs.eddi.engine.model.DeadLetterEntry;
import ai.labs.eddi.engine.runtime.internal.ClusterConversationCoordinator;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Turns what a node observes into activity entries (see
 * {@link ClusterActivityLog}) and remembers the nodes that went away, so the
 * console can show a lost node instead of silently dropping its card.
 * <p>
 * <b>Nodes.</b> Presence is compared every presence interval. A record that
 * disappeared while still fresh was deleted by a clean shutdown
 * ({@code node.left}); one that expired had stopped being written — the node
 * was killed, crashed or cut off from NATS ({@code node.lost}). A record whose
 * heartbeat is more than two intervals old is {@code node.stale}. Every node
 * makes the same observations; their deterministic ids let the activity stream
 * keep one of each.
 * <p>
 * <b>Degraded mode</b> on and off is this node's own observation, polled every
 * two seconds. Lease takeovers and dead letters come from listeners on the
 * lease manager and the coordinator; deployment changes from the cluster event
 * bus, one {@code deployment.propagated} entry per receiving node with the
 * delay; cache invalidations are counted and summarised once a minute.
 */
@ApplicationScoped
public class ClusterWatcher implements ClusterStartable {

    private static final Logger LOGGER = Logger.getLogger(ClusterWatcher.class);
    /** How long a node that went away is still shown. */
    static final long GONE_RETENTION_MILLIS = TimeUnit.MINUTES.toMillis(15);
    static final long CACHE_SUMMARY_MILLIS = TimeUnit.MINUTES.toMillis(1);

    /** A node that went away, as last seen. */
    public record GoneNode(Map<String, Object> lastRecord, long goneAt, boolean clean) {
    }

    private final ClusterConfig config;
    private final ClusterActivityLog activity;
    private final Instance<NatsConnectionManager> connections;
    private final Instance<ClusterPresence> presence;
    private final Instance<NatsLeaseManager> leases;
    private final Instance<ClusterConversationCoordinator> coordinator;
    private final IClusterEventBus eventBus;

    private final Map<String, Map<String, Object>> lastMembers = new HashMap<>();
    private final Map<String, GoneNode> gone = new ConcurrentHashMap<>();
    private final Map<String, Long> staleReported = new HashMap<>();
    private final Map<String, Integer> cacheCounts = new TreeMap<>();
    private boolean membersInitialised;
    private boolean wasDegraded;
    private boolean disconnectedSincePoll;
    private long reconnectGraceUntil;
    private long degradedSince;

    @Inject
    public ClusterWatcher(ClusterConfig config, ClusterActivityLog activity, Instance<NatsConnectionManager> connections,
            Instance<ClusterPresence> presence, Instance<NatsLeaseManager> leases, Instance<ClusterConversationCoordinator> coordinator,
            IClusterEventBus eventBus) {
        this.config = config;
        this.activity = activity;
        this.connections = connections;
        this.presence = presence;
        this.leases = leases;
        this.coordinator = coordinator;
        this.eventBus = eventBus;
    }

    @Override
    public void startCluster() {
        NatsConnectionManager manager = connections.get();
        leases.get().onTakeover(this::onTakeover);
        coordinator.get().onDeadLetter(this::onDeadLetter);
        eventBus.subscribe(ClusterEvent.DEPLOYMENT_CHANGED, this::onDeployment);
        eventBus.subscribe(ClusterEvent.CACHE_EVICT, this::onCacheEvent);
        eventBus.subscribe(ClusterEvent.CACHE_CLEAR, this::onCacheEvent);
        long presenceMillis = config.presenceInterval().toMillis();
        manager.scheduler().scheduleWithFixedDelay(this::pollDegraded, 2_000, 2_000, TimeUnit.MILLISECONDS);
        manager.scheduler().scheduleWithFixedDelay(this::pollMembers, presenceMillis, presenceMillis, TimeUnit.MILLISECONDS);
        manager.scheduler().scheduleWithFixedDelay(this::flushCacheCounts, CACHE_SUMMARY_MILLIS, CACHE_SUMMARY_MILLIS,
                TimeUnit.MILLISECONDS);
        Map<String, Object> self = new LinkedHashMap<>();
        self.put("nodeId", manager.node().nodeId());
        self.put("boot", manager.node().bootId());
        activity.record("node.joined", ClusterActivityLog.INFO, self, "node.joined:" + manager.node().nodeId() + ":" + manager.node().bootId());
    }

    /** The nodes that went away within the last 15 minutes, by node id. */
    public Map<String, GoneNode> goneNodes() {
        long cutoff = System.currentTimeMillis() - GONE_RETENTION_MILLIS;
        gone.values().removeIf(g -> g.goneAt() < cutoff);
        return Map.copyOf(gone);
    }

    // ---------------------------------------------------------------- polls

    synchronized void pollDegraded() {
        try {
            NatsConnectionManager manager = connections.get();
            boolean degraded = manager.isDegraded();
            if (degraded == wasDegraded) {
                return;
            }
            wasDegraded = degraded;
            String nodeId = manager.node().nodeId();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("nodeId", nodeId);
            payload.put("turnsPolicy", config.degradedTurns());
            if (degraded) {
                degradedSince = manager.unavailableSinceMillis();
                payload.put("since", degradedSince);
                activity.record("degraded.on", ClusterActivityLog.ERROR, payload, "degraded.on:" + nodeId + ":" + degradedSince);
            } else {
                payload.put("durationMs", degradedSince > 0 ? System.currentTimeMillis() - degradedSince : null);
                activity.record("degraded.off", ClusterActivityLog.INFO, payload, null);
            }
        } catch (RuntimeException e) {
            LOGGER.debugf("Degraded poll failed: %s", e.getMessage());
        }
    }

    synchronized void pollMembers() {
        try {
            if (!connections.get().isConnected()) {
                disconnectedSincePoll = true;
                return; // nothing can be told apart while we cannot read presence
            }
            long now = System.currentTimeMillis();
            long interval = config.presenceInterval().toMillis();
            if (disconnectedSincePoll) {
                // Every record aged while this node (and often every node) was cut off,
                // and some expired: what is missing now is not "lost" until the members
                // had an interval or two to write their records again.
                disconnectedSincePoll = false;
                reconnectGraceUntil = now + 2 * interval + 1_000;
            }
            boolean grace = now < reconnectGraceUntil;
            String self = connections.get().node().nodeId();
            Map<String, Map<String, Object>> current = new HashMap<>();
            for (Map<String, Object> member : presence.get().members()) {
                current.put(String.valueOf(member.get("node")), member);
            }
            if (!membersInitialised) {
                membersInitialised = true;
                lastMembers.putAll(current);
                return;
            }
            for (Map.Entry<String, Map<String, Object>> e : current.entrySet()) {
                Map<String, Object> before = lastMembers.get(e.getKey());
                String boot = String.valueOf(e.getValue().get("boot"));
                if (before == null || !boot.equals(String.valueOf(before.get("boot")))) {
                    gone.remove(e.getKey());
                    activity.record("node.joined", ClusterActivityLog.INFO, Map.of("nodeId", e.getKey(), "boot", boot),
                            "node.joined:" + e.getKey() + ":" + boot);
                }
                long updated = number(e.getValue().get("updatedAt"));
                if (!grace && !self.equals(e.getKey()) && updated > 0 && now - updated > 2 * interval
                        && !Long.valueOf(updated).equals(staleReported.get(e.getKey()))) {
                    staleReported.put(e.getKey(), updated);
                    activity.record("node.stale", ClusterActivityLog.WARNING,
                            Map.of("nodeId", e.getKey(), "heartbeatAgeMs", now - updated), "node.stale:" + e.getKey() + ":" + updated);
                }
            }
            if (grace) {
                // Keep remembering the members not seen yet; judge them after the grace.
                for (Map.Entry<String, Map<String, Object>> e : current.entrySet()) {
                    lastMembers.put(e.getKey(), e.getValue());
                }
                return;
            }
            for (Map.Entry<String, Map<String, Object>> e : lastMembers.entrySet()) {
                if (current.containsKey(e.getKey()) || self.equals(e.getKey())) {
                    continue;
                }
                long updated = number(e.getValue().get("updatedAt"));
                // A clean shutdown writes a leave marker before it deletes its record;
                // a record that vanished without one expired: the node was lost. (Timing
                // alone misread a clean shutdown as lost under scheduler jitter.)
                boolean clean = presence.get().leftCleanly(e.getKey(), String.valueOf(e.getValue().get("boot")));
                gone.put(e.getKey(), new GoneNode(e.getValue(), now, clean));
                staleReported.remove(e.getKey());
                String boot = String.valueOf(e.getValue().get("boot"));
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("nodeId", e.getKey());
                payload.put("boot", boot);
                payload.put("lastHeartbeat", updated);
                activity.record(clean ? "node.left" : "node.lost", clean ? ClusterActivityLog.INFO : ClusterActivityLog.ERROR, payload,
                        (clean ? "node.left:" : "node.lost:") + e.getKey() + ":" + boot);
            }
            lastMembers.clear();
            lastMembers.putAll(current);
        } catch (RuntimeException e) {
            LOGGER.debugf("Member poll failed: %s", e.getMessage());
        }
    }

    // ---------------------------------------------------------------- listeners

    void onTakeover(String key, LeaseInfo previous) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("key", key);
        payload.put("conversationId", key.startsWith("c.") ? key.substring(2) : null);
        payload.put("previousNode", previous.node());
        payload.put("previousBoot", previous.boot());
        payload.put("previousRevision", String.valueOf(previous.revision()));
        activity.record("lease.takeover", ClusterActivityLog.WARNING, payload, "lease.takeover:" + key + ":" + previous.revision());
    }

    void onDeadLetter(DeadLetterEntry entry) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("entryId", entry.id());
        payload.put("conversationId", entry.conversationId());
        payload.put("agentId", entry.turn() == null ? null : entry.turn().get("agentId"));
        payload.put("reason", entry.reason());
        if (entry.fence() != null) {
            Map<String, Object> fence = new LinkedHashMap<>();
            entry.fence().forEach((k, v) -> fence.put(k, String.valueOf(v)));
            payload.put("fence", fence);
        }
        String type = DeadLetterEntry.REASON_FENCED.equals(entry.reason()) ? "fence.rejected" : "deadletter.created";
        activity.record(type, ClusterActivityLog.WARNING, payload, null);
    }

    void onDeployment(ClusterEvent event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId", event.id());
        payload.put("originNode", event.originNode());
        payload.put("agentId", event.getString("agentId"));
        payload.put("version", event.get("version"));
        payload.put("environment", event.getString("env"));
        payload.put("status", event.getString("status"));
        payload.put("latencyMs", event.ts() > 0 ? Math.max(0, System.currentTimeMillis() - event.ts()) : null);
        activity.record("deployment.propagated", ClusterActivityLog.INFO, payload, null);
    }

    synchronized void onCacheEvent(ClusterEvent event) {
        String cache = event.getString("cache");
        cacheCounts.merge(cache == null ? "unknown" : cache, 1, Integer::sum);
    }

    synchronized void flushCacheCounts() {
        if (cacheCounts.isEmpty()) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("counts", new TreeMap<>(cacheCounts));
        payload.put("total", cacheCounts.values().stream().mapToInt(Integer::intValue).sum());
        payload.put("windowSeconds", CACHE_SUMMARY_MILLIS / 1000);
        cacheCounts.clear();
        activity.record("cache.invalidations", ClusterActivityLog.INFO, payload, null);
    }

    private static long number(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }

    /** For tests: the members known from the last poll. */
    synchronized List<String> knownMembers() {
        return List.copyOf(lastMembers.keySet());
    }
}
