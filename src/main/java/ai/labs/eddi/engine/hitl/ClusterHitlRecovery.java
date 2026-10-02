/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl;

import ai.labs.eddi.engine.cluster.ClusterConfig;
import ai.labs.eddi.engine.cluster.ClusterStartable;
import ai.labs.eddi.engine.cluster.NatsConnectionManager;
import ai.labs.eddi.engine.cluster.lease.IConversationLeaseManager;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * HITL crash recovery in cluster mode: a leader-elected periodic sweep instead
 * of a pass at every node's startup.
 * <p>
 * Every {@code eddi.cluster.hitl-recovery.interval} each node tries the
 * {@code leader.hitl-recovery} lease; the holder re-arms lost timeout schedules
 * and recovers stuck IN_PROGRESS conversations. A conversation is recovered
 * only when it holds no conversation lease (nobody runs a turn or resume on it)
 * and was seen IN_PROGRESS at the same revision for at least
 * {@code eddi.cluster.hitl-recovery.min-age} — longer than an agent timeout
 * plus the lease TTL, so a resume that is merely queued for its lease is never
 * taken for a crashed one. The state CAS in the recovery itself makes every
 * remaining race benign. Holding the leader lease is not needed for
 * correctness, only to avoid N nodes doing the same work.
 */
@ApplicationScoped
public class ClusterHitlRecovery implements ClusterStartable {

    private static final Logger LOGGER = Logger.getLogger(ClusterHitlRecovery.class);
    private static final String LEADER_KEY = IConversationLeaseManager.LEADER + "hitl-recovery";

    private final ClusterConfig config;
    private final Instance<NatsConnectionManager> connections;
    private final IConversationLeaseManager leases;
    private final IConversationMemoryStore memoryStore;
    private final HitlCrashRecoveryObserver observer;
    /**
     * conversation → (revision, first seen millis) of the IN_PROGRESS sightings.
     */
    private final Map<String, long[]> sightings = new HashMap<>();

    @Inject
    public ClusterHitlRecovery(ClusterConfig config, Instance<NatsConnectionManager> connections, IConversationLeaseManager leases,
            IConversationMemoryStore memoryStore, HitlCrashRecoveryObserver observer) {
        this.config = config;
        this.connections = connections;
        this.leases = leases;
        this.memoryStore = memoryStore;
        this.observer = observer;
    }

    @Override
    public void startCluster() {
        long interval = config.hitlRecoveryInterval().toMillis();
        connections.get().scheduler().scheduleWithFixedDelay(this::sweep, interval, interval, TimeUnit.MILLISECONDS);
    }

    synchronized void sweep() {
        try {
            if (leases.tryAcquireKey(LEADER_KEY).isEmpty()) {
                sightings.clear(); // another node leads; our observations go stale
                return;
            }
            observer.runClusterRecovery(this::eligible);
        } catch (RuntimeException e) {
            LOGGER.warnf("HITL cluster recovery pass failed: %s", e.getMessage());
        }
    }

    /** No lease, and unchanged for at least the minimum age. */
    synchronized boolean eligible(String conversationId) {
        if (leases.peek(conversationId).isPresent()) {
            sightings.remove(conversationId);
            return false;
        }
        Long revision = memoryStore.getRevision(conversationId);
        long rev = revision == null ? -1 : revision;
        long now = System.currentTimeMillis();
        long[] seen = sightings.get(conversationId);
        if (seen == null || seen[0] != rev) {
            sightings.put(conversationId, new long[]{rev, now});
            return false;
        }
        if (now - seen[1] < config.hitlRecoveryMinAge().toMillis()) {
            return false;
        }
        sightings.remove(conversationId);
        return true;
    }
}
