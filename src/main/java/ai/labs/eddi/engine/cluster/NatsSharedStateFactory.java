/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.cluster;

import io.micrometer.core.instrument.MeterRegistry;
import io.nats.client.JetStreamApiException;
import io.nats.client.KeyValueManagement;
import io.nats.client.api.KeyValueConfiguration;
import io.nats.client.api.KeyValueStatus;
import io.nats.client.api.StorageType;
import io.nats.client.api.StreamConfiguration;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cluster-mode {@link ISharedStateFactory}: one NATS KV bucket per
 * {@link SharedBucket}, named {@code <eddi.nats.prefix>_<name>}, file storage,
 * {@code eddi.nats.replicas} replicas.
 * <p>
 * Buckets are provisioned idempotently on every (re)connect — create, or update
 * when the configuration drifted. A replica-count mismatch (the bucket was
 * created by a deployment with a different {@code eddi.nats.replicas}) is a
 * WARN, never a crash: JetStream cannot change it in place, and a node that
 * refused to start over it would turn a configuration nit into an outage.
 */
@ApplicationScoped
@Typed(NatsSharedStateFactory.class)
public class NatsSharedStateFactory implements ISharedStateFactory {

    private static final Logger LOGGER = Logger.getLogger(NatsSharedStateFactory.class);

    private final NatsConnectionManager connections;
    private final MeterRegistry meterRegistry;
    private final Map<String, SharedBucket> specs = new ConcurrentHashMap<>();
    private final Map<String, NatsSharedKv> buckets = new ConcurrentHashMap<>();
    private volatile boolean hooked;

    @Inject
    public NatsSharedStateFactory(NatsConnectionManager connections, MeterRegistry meterRegistry) {
        this.connections = connections;
        this.meterRegistry = meterRegistry;
    }

    private void hook() {
        if (!hooked) {
            synchronized (this) {
                if (!hooked) {
                    hooked = true;
                    connections.onConnected(this::provisionAll);
                }
            }
        }
    }

    public String serverName(SharedBucket spec) {
        return connections.config().natsPrefix() + "_" + spec.name();
    }

    @Override
    public ISharedKv bucket(SharedBucket spec) {
        hook();
        String name = serverName(spec);
        SharedBucket previous = specs.putIfAbsent(name, spec);
        if (previous == null && connections.isConnected()) {
            try {
                provision(spec);
            } catch (RuntimeException e) {
                LOGGER.warnf("Could not provision KV bucket %s now (%s) — retried on the next connect", name, e.getMessage());
            }
        }
        return buckets.computeIfAbsent(name, n -> new NatsSharedKv(connections, n, meterRegistry));
    }

    @Override
    public boolean isShared() {
        return true;
    }

    /** Provisions every bucket handed out so far. Runs after each (re)connect. */
    public void provisionAll() {
        for (SharedBucket spec : specs.values()) {
            try {
                provision(spec);
            } catch (RuntimeException e) {
                LOGGER.warnf("Could not provision KV bucket %s: %s", serverName(spec), e.getMessage());
            }
        }
    }

    /**
     * The bucket whose revisions are fencing tokens — see
     * {@link #createAboveEveryEarlierFence}.
     */
    static boolean fenced(SharedBucket spec) {
        return SharedBucket.LEASES.name().equals(spec.name());
    }

    /**
     * The first revision a newly created leases bucket hands out: its creation time
     * in microseconds.
     * <p>
     * A lease's revision is the fencing token the conversation stores keep the
     * highest of ({@code _fence}). A bucket that is recreated — after NATS lost its
     * data, or to change its replica count — would start again at 1, below every
     * stored fence, and every write to those conversations would be refused for
     * good. Starting each new bucket at its creation time keeps a later bucket
     * above an earlier one unless the earlier one handed out more than a million
     * revisions a second on average over its whole life.
     */
    static long firstRevisionAt(long epochMillis) {
        return epochMillis * 1000L;
    }

    /**
     * The stream behind a bucket, with direct get turned off.
     * <p>
     * jnats creates a KV bucket with {@code allow_direct}, which lets <i>any</i>
     * replica of the stream answer a get — the one on the server the client is
     * connected to, even when it lags the leader (after a restart or a catch-up, or
     * when the cluster is partitioned), so two nodes can read different records for
     * one key. Every compare-and-set still goes through the leader, so a stale read
     * can only make a CAS fail, never succeed wrongly; but the decisions made
     * <i>on</i> the read — is this lease stale, what is the counter, who holds it —
     * must not depend on which replica answered. With direct get off, a get is a
     * JetStream message get, which only the stream leader answers.
     */
    static StreamConfiguration leaderReads(KeyValueConfiguration desired) {
        return StreamConfiguration.builder(desired.getBackingConfig()).allowDirect(false).build();
    }

    private void createAboveEveryEarlierFence(KeyValueConfiguration desired) throws IOException, JetStreamApiException {
        StreamConfiguration backing = StreamConfiguration.builder(leaderReads(desired))
                .firstSequence(firstRevisionAt(System.currentTimeMillis())).build();
        connections.jetStreamManagement().addStream(backing);
    }

    void provision(SharedBucket spec) {
        String name = serverName(spec);
        int replicas = connections.config().natsReplicas();
        KeyValueConfiguration.Builder builder = KeyValueConfiguration.builder().name(name).ttl(spec.ttl())
                .storageType(StorageType.File).replicas(replicas).maxHistoryPerKey(1);
        if (spec.maxValueSize() > 0) {
            builder.maxValueSize(spec.maxValueSize());
        }
        KeyValueConfiguration desired = builder.build();
        KeyValueManagement kvm = connections.keyValueManagement();
        try {
            KeyValueStatus status;
            try {
                status = kvm.getStatus(name);
            } catch (JetStreamApiException notFound) {
                if (fenced(spec)) {
                    createAboveEveryEarlierFence(desired);
                } else {
                    connections.jetStreamManagement().addStream(leaderReads(desired));
                }
                LOGGER.infof("Created KV bucket %s (ttl %s, replicas %d)", name, spec.ttl(), replicas);
                return;
            }
            if (status.getReplicas() != replicas) {
                LOGGER.warnf("KV bucket %s has %d replicas but eddi.nats.replicas=%d — JetStream cannot change this in place. %s",
                        name, status.getReplicas(), replicas, fenced(spec)
                                ? "Recreating it is safe: a new " + name + " bucket starts its revisions above every fencing "
                                        + "token an earlier one handed out (stop all EDDI nodes, delete it, start them again)."
                                : "Recreate the bucket to apply it.");
                return;
            }
            StreamConfiguration current = status.getBackingStreamInfo().getConfiguration();
            if (!spec.ttl().equals(status.getTtl()) || current.getAllowDirect()) {
                // From the bucket's own stream configuration, so everything else — the first
                // sequence a fenced bucket was created with above all, which the desired
                // configuration does not carry — stays as it is.
                connections.jetStreamManagement()
                        .updateStream(StreamConfiguration.builder(current).maxAge(spec.ttl()).allowDirect(false).build());
                LOGGER.infof("Updated KV bucket %s (ttl %s, reads answered by the stream leader only)", name, spec.ttl());
            }
        } catch (IOException | JetStreamApiException e) {
            throw new ClusterUnavailableException("Provisioning KV bucket " + name + " failed: " + e.getMessage(), e);
        }
    }
}
