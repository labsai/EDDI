/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.cluster;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cluster-wide record of the deployments made with {@code autoDeploy=false} (B1
 * of the cluster production-readiness review).
 * <p>
 * Such a deployment is deliberately not written to the deployment collection —
 * it must not come back when EDDI restarts. In a cluster it used to exist only
 * as a {@code deployment.changed} event, so a lost undeploy, two events
 * delivered out of order from the outbox, or a node that joined later (an HPA
 * scale-up) left it deployed, or missing, on some nodes for good.
 * <p>
 * It is now recorded here, in the {@code <prefix>_DEPLOYMENTS} KV bucket, one
 * key per environment, agent and version, with a state:
 * <ul>
 * <li>{@code DEPLOYED} — every node serves it; a node that joins deploys it
 * from this record;</li>
 * <li>{@code UNDEPLOYED} — a tombstone written by the undeploy (or the deletion
 * of the agent); every node that still serves it takes it down.</li>
 * </ul>
 * The KV revision orders the writes, so the record is the latest decision
 * whatever order its events arrive in: an event is only a hint to look here now
 * rather than at the next reconciliation sweep (see
 * {@code AgentDeploymentManagement}).
 * <p>
 * <b>Lifetime.</b> A value expires after
 * {@code eddi.cluster.deployments.transient-ttl} (10 min) unless rewritten.
 * Nodes serving a {@code DEPLOYED} record refresh it (compare-and-set, so a
 * refresh never overwrites a newer tombstone) once it is older than a third of
 * that. So the deployment lasts as long as the cluster runs — through rolling
 * restarts — and is forgotten once no node has served it for the TTL, which is
 * what "not recorded" means for a cluster as a whole. Tombstones are not
 * refreshed; they only need to outlive the nodes that missed the undeploy by
 * one sweep.
 * <p>
 * <b>NATS unreachable.</b> A write that fails is kept here as pending and
 * retried by the next sweep (the latest write per key wins); a read throws
 * {@link ClusterUnavailableException}, which the sweep takes as "no
 * information" — it never undeploys on a failed read.
 * <p>
 * In single-node mode nothing is recorded: {@link #enabled()} is false.
 */
@ApplicationScoped
public class TransientDeployments {

    private static final Logger LOGGER = Logger.getLogger(TransientDeployments.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    static final String BUCKET_NAME = "DEPLOYMENTS";
    private static final String KEY_PREFIX = "d.";
    private static final int MAX_ENTRIES = 10_000;
    /** The payload format version of a record (C15: versioned NATS payloads). */
    public static final int PAYLOAD_VERSION = 1;

    /** What a record says. */
    public enum State {
        DEPLOYED, UNDEPLOYED
    }

    /** One record, with the revision and server time of its last write. */
    public record Entry(String environment, String agentId, int version, State state, long revision, long writtenAt) {

        public String key() {
            return keyOf(environment, agentId, version);
        }
    }

    private final ClusterConfig config;
    private final Instance<ISharedStateFactory> sharedState;
    private final Duration ttl;
    private final String nodeId;
    private volatile ISharedKv bucket;
    /** Writes that failed, latest per key; retried by {@link #retryPending()}. */
    private final Map<String, byte[]> pending = new ConcurrentHashMap<>();

    @Inject
    public TransientDeployments(ClusterConfig config, Instance<ISharedStateFactory> sharedState, Instance<NodeIdentity> node,
            @ConfigProperty(name = "eddi.cluster.deployments.transient-ttl", defaultValue = "10m") Duration ttl) {
        this.config = config;
        this.sharedState = sharedState;
        this.ttl = ttl;
        this.nodeId = config.isNats() && node.isResolvable() ? node.get().nodeId() : "local";
    }

    /** For tests: a bucket supplied directly. */
    public TransientDeployments(ISharedKv bucket, Duration ttl, String nodeId) {
        this.config = null;
        this.sharedState = null;
        this.ttl = ttl;
        this.nodeId = nodeId;
        this.bucket = bucket;
    }

    /** True in cluster mode: only then is anything recorded. */
    public boolean enabled() {
        return bucket != null || (config != null && config.isNats());
    }

    public Duration ttl() {
        return ttl;
    }

    private ISharedKv bucket() {
        ISharedKv kv = bucket;
        if (kv == null) {
            synchronized (this) {
                kv = bucket;
                if (kv == null) {
                    kv = sharedState.get().bucket(new SharedBucket(BUCKET_NAME, ttl, 1024));
                    bucket = kv;
                }
            }
        }
        return kv;
    }

    /** The key of a record: {@code d.<env>.<agentId>.<version>}. */
    public static String keyOf(String environment, String agentId, Object version) {
        return KEY_PREFIX + KvKeys.safe(environment) + "." + KvKeys.safe(agentId) + "." + KvKeys.safe(String.valueOf(version));
    }

    /** Records that {@code agentId} is deployed, unrecorded, on every node. */
    public void markDeployed(String environment, String agentId, Integer version) {
        write(environment, agentId, version, State.DEPLOYED);
    }

    /** Writes the tombstone: every node that still serves it takes it down. */
    public void markUndeployed(String environment, String agentId, Integer version) {
        write(environment, agentId, version, State.UNDEPLOYED);
    }

    /**
     * Tombstones every {@code DEPLOYED} record of {@code agentId} (all versions;
     * one environment, or all when {@code environment} is null) — the agent was
     * deleted. Best effort: an unreachable bucket leaves them to expire.
     */
    public void markAgentUndeployed(String environment, String agentId) {
        if (!enabled() || agentId == null) {
            return;
        }
        try {
            for (Entry entry : list()) {
                if (entry.state() == State.DEPLOYED && agentId.equals(entry.agentId())
                        && (environment == null || environment.equals(entry.environment()))) {
                    markUndeployed(entry.environment(), entry.agentId(), entry.version());
                }
            }
        } catch (ClusterUnavailableException e) {
            LOGGER.warnf("Could not tombstone the transient deployments of a deleted agent (they expire after %s): %s", ttl,
                    e.getMessage());
        }
    }

    private void write(String environment, String agentId, Integer version, State state) {
        if (!enabled() || environment == null || agentId == null || version == null) {
            return;
        }
        String key = keyOf(environment, agentId, version);
        byte[] value = encode(environment, agentId, version, state);
        try {
            bucket().put(key, value);
            pending.remove(key);
        } catch (ClusterUnavailableException e) {
            pending.put(key, value);
            LOGGER.warnf("Transient deployment record of %s/%s/%s (%s) not written, NATS unreachable — retried by the next sweep",
                    sanitize(environment), sanitize(agentId), version, state);
        }
    }

    /** Retries the writes that failed; stops at the first that fails again. */
    public void retryPending() {
        for (Map.Entry<String, byte[]> write : new ArrayList<>(pending.entrySet())) {
            try {
                bucket().put(write.getKey(), write.getValue());
                pending.remove(write.getKey(), write.getValue());
            } catch (ClusterUnavailableException e) {
                return;
            }
        }
    }

    /** True while a write for this key has not reached the bucket. */
    public boolean isPending(String environment, String agentId, Object version) {
        return pending.containsKey(keyOf(environment, agentId, version));
    }

    /**
     * The current record.
     *
     * @throws ClusterUnavailableException
     *             NATS is unreachable: the answer is unknown, not "none"
     */
    public Optional<Entry> read(String environment, String agentId, Object version) {
        if (!enabled()) {
            return Optional.empty();
        }
        return bucket().get(keyOf(environment, agentId, version)).flatMap(v -> decode(v));
    }

    /**
     * Every record (deployed and tombstones).
     *
     * @throws ClusterUnavailableException
     *             NATS is unreachable
     */
    public List<Entry> list() {
        if (!enabled()) {
            return List.of();
        }
        List<Entry> entries = new ArrayList<>();
        for (ISharedKv.Entry e : bucket().entries(KEY_PREFIX, MAX_ENTRIES)) {
            decode(e.value()).ifPresent(entries::add);
        }
        return entries;
    }

    /**
     * Rewrites a {@code DEPLOYED} record that is older than a third of the TTL, so
     * it does not expire while a node serves it. Compare-and-set: a tombstone
     * written since is never overwritten.
     *
     * @return true when the record was rewritten
     */
    public boolean refreshIfOld(Entry entry, long nowMillis) {
        if (entry.state() != State.DEPLOYED || entry.writtenAt() <= 0 || nowMillis - entry.writtenAt() < ttl.toMillis() / 3) {
            return false;
        }
        try {
            return bucket().update(entry.key(), encode(entry.environment(), entry.agentId(), entry.version(), State.DEPLOYED),
                    entry.revision()).isPresent();
        } catch (ClusterUnavailableException e) {
            return false;
        }
    }

    /**
     * Re-creates the record of a deployment this node serves whose record expired
     * (NATS kept nothing for longer than the TTL). {@code create}: fails when any
     * record — a tombstone above all — exists.
     *
     * @return true when the record was created
     */
    public boolean recreate(String environment, String agentId, Integer version) {
        try {
            return bucket().create(keyOf(environment, agentId, version), encode(environment, agentId, version, State.DEPLOYED))
                    .isPresent();
        } catch (ClusterUnavailableException e) {
            return false;
        }
    }

    private byte[] encode(String environment, String agentId, Integer version, State state) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("v", PAYLOAD_VERSION);
        record.put("env", environment);
        record.put("agentId", agentId);
        record.put("version", version);
        record.put("state", state.name());
        record.put("node", nodeId);
        record.put("ts", System.currentTimeMillis());
        try {
            return JSON.writeValueAsBytes(record);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Optional<Entry> decode(ISharedKv.Versioned versioned) {
        try {
            Map<String, Object> raw = JSON.readValue(versioned.value(), new TypeReference<Map<String, Object>>() {
            });
            String environment = String.valueOf(raw.get("env"));
            String agentId = String.valueOf(raw.get("agentId"));
            int version = Integer.parseInt(String.valueOf(raw.get("version")));
            State state = State.valueOf(String.valueOf(raw.get("state")));
            long writtenAt = versioned.writtenAt() > 0
                    ? versioned.writtenAt()
                    : raw.get("ts") instanceof Number ts ? ts.longValue() : 0L;
            return Optional.of(new Entry(environment, agentId, version, state, versioned.revision(), writtenAt));
        } catch (IOException | RuntimeException e) {
            LOGGER.debugf("Unreadable transient deployment record: %s", e.getMessage());
            return Optional.empty();
        }
    }
}
