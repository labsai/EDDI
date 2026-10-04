/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.cluster.admin;

import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.audit.AuditLedgerService;
import ai.labs.eddi.engine.audit.model.AuditEntry;
import ai.labs.eddi.engine.cluster.ClusterConfig;
import ai.labs.eddi.engine.cluster.ClusterPresence;
import ai.labs.eddi.engine.cluster.ClusterStartable;
import ai.labs.eddi.engine.cluster.ClusterUnavailableException;
import ai.labs.eddi.engine.cluster.NatsConnectionManager;
import ai.labs.eddi.engine.cluster.events.JetStreamEventBus;
import ai.labs.eddi.engine.cluster.lease.IConversationLeaseManager;
import ai.labs.eddi.engine.cluster.lease.KvLeaseManager.ForceRelease;
import ai.labs.eddi.engine.cluster.lease.KvLeaseManager.LeaseSnapshot;
import ai.labs.eddi.engine.cluster.lease.NatsLeaseManager;
import ai.labs.eddi.engine.cluster.rpc.IClusterRpc;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.model.ConversationListingSummary;
import ai.labs.eddi.engine.model.ClusterAdminModels.AccountView;
import ai.labs.eddi.engine.model.ClusterAdminModels.ActionResult;
import ai.labs.eddi.engine.model.ClusterAdminModels.BucketView;
import ai.labs.eddi.engine.model.ClusterAdminModels.BulkResult;
import ai.labs.eddi.engine.model.ClusterAdminModels.ClusterNode;
import ai.labs.eddi.engine.model.ClusterAdminModels.ClusterOverview;
import ai.labs.eddi.engine.model.ClusterAdminModels.ConsumerView;
import ai.labs.eddi.engine.model.ClusterAdminModels.DeadLetterCounts;
import ai.labs.eddi.engine.model.ClusterAdminModels.DeadLetterPage;
import ai.labs.eddi.engine.model.ClusterAdminModels.DeadLetterSummary;
import ai.labs.eddi.engine.model.ClusterAdminModels.DeadLetterView;
import ai.labs.eddi.engine.model.ClusterAdminModels.Diagnosis;
import ai.labs.eddi.engine.model.ClusterAdminModels.Finding;
import ai.labs.eddi.engine.model.ClusterAdminModels.ItemOutcome;
import ai.labs.eddi.engine.model.ClusterAdminModels.LeaseEpoch;
import ai.labs.eddi.engine.model.ClusterAdminModels.LeasePage;
import ai.labs.eddi.engine.model.ClusterAdminModels.LeaseView;
import ai.labs.eddi.engine.model.ClusterAdminModels.NatsView;
import ai.labs.eddi.engine.model.ClusterAdminModels.PeerView;
import ai.labs.eddi.engine.model.ClusterAdminModels.StreamView;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.model.DeadLetterEntry;
import ai.labs.eddi.engine.model.InputData;
import ai.labs.eddi.engine.runtime.IAgentDeploymentManagement;
import ai.labs.eddi.engine.runtime.IConversationCoordinator;
import ai.labs.eddi.engine.runtime.internal.ClusterConversationCoordinator;
import io.micrometer.core.instrument.MeterRegistry;
import io.nats.client.Connection;
import io.nats.client.JetStreamApiException;
import io.nats.client.JetStreamManagement;
import io.nats.client.api.AccountStatistics;
import io.nats.client.api.ClusterInfo;
import io.nats.client.api.ConsumerInfo;
import io.nats.client.api.Replica;
import io.nats.client.api.ServerInfo;
import io.nats.client.api.StreamInfo;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;
import java.lang.management.ManagementFactory;
import ai.labs.eddi.engine.cluster.ISharedKv;
import ai.labs.eddi.engine.cluster.ISharedStateFactory;
import ai.labs.eddi.engine.cluster.KvKeys;
import ai.labs.eddi.engine.cluster.SharedBucket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Everything behind the cluster console: the health verdict, the node cards,
 * NATS and JetStream as seen from here, the leases, dead-letter queries and
 * bulk operations, the "why is this conversation stuck?" diagnosis, and the
 * recovery actions.
 * <p>
 * <b>Recovery actions are deliberately few</b> and each one is safe to repeat
 * and safe under concurrency:
 * <ul>
 * <li><b>Force-release a lease</b> — a compare-and-set delete; the fence makes
 * it safe (the next holder's higher token is raised on the conversation before
 * its turn runs, so a still-alive former holder's late write is refused and
 * dead-lettered).</li>
 * <li><b>Resync caches</b> — every node flushes its invalidatable caches and
 * reloads from the database; the same flush a node does by itself after missing
 * events.</li>
 * <li><b>Reconcile deployments</b> — runs the periodic deployment sweep now on
 * every node.</li>
 * <li><b>Drain / undrain a node</b> — the node takes no new leases (turns that
 * reach it are answered 409 + {@code Retry-After}) and reports itself not
 * ready; refused for the last undrained node.</li>
 * <li><b>Forward local dead letters</b> — moves dead letters a node kept while
 * NATS was down into the shared stream (append, then remove; serialised per
 * node).</li>
 * </ul>
 * Each is recorded in the audit ledger (trail {@value #AUDIT_TRAIL}) and on the
 * activity timeline. Nothing here deletes a stream or bucket, changes a fence,
 * or hands a lease to a chosen node — the operations that could lose data or
 * bypass fencing are not offered.
 */
@ApplicationScoped
public class ClusterAdminService implements ClusterStartable {

    private static final Logger LOGGER = Logger.getLogger(ClusterAdminService.class);

    /** The audit trail the recovery actions are recorded in. */
    public static final String AUDIT_TRAIL = "cluster-admin";
    static final String RPC_DRAIN = "admin-drain";
    static final String RPC_RECONCILE = "admin-reconcile";
    static final String RPC_FORWARD = "admin-forward-dead-letters";

    static final int MAX_LEASE_SCAN = 5_000;
    static final int MAX_PAGE = 500;
    static final int MAX_BULK = 100;
    static final int DEAD_LETTER_SCAN_BUDGET = 2_000;
    static final int SUMMARY_SCAN_BUDGET = 5_000;
    private static final long NATS_VIEW_CACHE_MILLIS = 3_000;

    private final ClusterConfig config;
    private final ClusterActivityLog activity;
    private final ClusterWatcher watcher;
    private final IConversationCoordinator coordinator;
    private final IConversationLeaseManager leaseManager;
    private final IClusterRpc rpc;
    private final Instance<NatsConnectionManager> connections;
    private final Instance<ClusterPresence> presence;
    private final Instance<NatsLeaseManager> natsLeases;
    private final Instance<JetStreamEventBus> eventBus;
    private final IConversationMemoryStore memoryStore;
    private final IConversationService conversationService;
    private final IAgentDeploymentManagement deployments;
    private final AuditLedgerService auditLedger;
    private final MeterRegistry meterRegistry;

    private final ISharedStateFactory sharedState;
    private volatile ISharedKv claims;
    /**
     * Replay claims of node-local dead letters ({@code local-…}): those exist on
     * this node only, so a claim in this JVM is the whole cluster's.
     */
    private final Set<String> localReplayClaims = ConcurrentHashMap.newKeySet();
    private volatile NatsView cachedNats;
    private volatile long cachedNatsAt;

    @Inject
    public ClusterAdminService(ClusterConfig config, ClusterActivityLog activity, ClusterWatcher watcher, IConversationCoordinator coordinator,
            IConversationLeaseManager leaseManager, IClusterRpc rpc, Instance<NatsConnectionManager> connections,
            Instance<ClusterPresence> presence, Instance<NatsLeaseManager> natsLeases, Instance<JetStreamEventBus> eventBus,
            IConversationMemoryStore memoryStore, IConversationService conversationService, IAgentDeploymentManagement deployments,
            AuditLedgerService auditLedger, MeterRegistry meterRegistry, ISharedStateFactory sharedState) {
        this.config = config;
        this.sharedState = sharedState;
        this.activity = activity;
        this.watcher = watcher;
        this.coordinator = coordinator;
        this.leaseManager = leaseManager;
        this.rpc = rpc;
        this.connections = connections;
        this.presence = presence;
        this.natsLeases = natsLeases;
        this.eventBus = eventBus;
        this.memoryStore = memoryStore;
        this.conversationService = conversationService;
        this.deployments = deployments;
        this.auditLedger = auditLedger;
        this.meterRegistry = meterRegistry;
    }

    /** The node-addressed operations the actions fan out to. */
    @Override
    public void startCluster() {
        presence.get().contribute(() -> Map.of("draining", natsLeases.get().isDraining()));
        NatsConnectionManager manager = connections.get();
        // The drain key lives in a bucket with a TTL: a drained node keeps rewriting
        // it,
        // and a node that is not drained (a restart ends a drain) removes a leftover
        // one.
        manager.onConnected(this::syncOwnDrainKey);
        long every = config.presenceInterval().toMillis();
        manager.scheduler().scheduleWithFixedDelay(this::syncOwnDrainKey, every, every, TimeUnit.MILLISECONDS);
        rpc.handle(RPC_DRAIN, request -> {
            boolean drain = Boolean.parseBoolean(String.valueOf(request.get("drain")));
            applyDrainLocally(drain);
            return Map.of("draining", drain);
        });
        rpc.handle(RPC_RECONCILE, request -> {
            deployments.reconcileNow();
            return Map.of("reconciled", true);
        });
        rpc.handle(RPC_FORWARD, request -> Map.of("forwarded", clusterCoordinator().map(ClusterConversationCoordinator::forwardLocalDeadLetters)
                .orElse(0)));
    }

    public boolean isClustered() {
        return config.isNats();
    }

    private Optional<ClusterConversationCoordinator> clusterCoordinator() {
        return coordinator instanceof ClusterConversationCoordinator c ? Optional.of(c) : Optional.empty();
    }

    // ================================================================ overview

    public ClusterOverview overview() {
        long now = System.currentTimeMillis();
        Map<String, Object> settings = settings();
        if (!isClustered()) {
            ClusterNode single = new ClusterNode("local", null, null, version(), ManagementFactory.getRuntimeMXBean().getStartTime(), now, 0, "LIVE",
                    true, false, false, -1,
                    coordinator.getQueueDepths().size(), 0, coordinator.getQueueDepths().values().stream().mapToInt(Integer::intValue).sum(),
                    coordinator.getDeadLetters().size(), false, null);
            return new ClusterOverview("single-node", "SINGLE_NODE", List.of(), "local", now, config.degradedTurns(), null, List.of(single),
                    null, new DeadLetterCounts(coordinator.getDeadLetters().size(), 0), null, settings);
        }
        NatsConnectionManager manager = connections.get();
        String self = manager.node().nodeId();
        String leader = leaseManager.peekKey(IConversationLeaseManager.LEADER + "hitl-recovery").map(l -> l.node()).orElse(null);
        long interval = config.presenceInterval().toMillis();

        List<ClusterNode> nodes = new ArrayList<>();
        LinkedHashSet<String> reasons = new LinkedHashSet<>();
        boolean connected = manager.isConnected();
        Map<String, Map<String, Object>> members = new TreeMap<>();
        for (Map<String, Object> member : presence.get().members()) {
            members.put(String.valueOf(member.get("node")), member);
        }
        // This node's own card always shows its live numbers, not its last published
        // record: without NATS that record is as old as the outage (and would hide, for
        // one, the dead letters this node is keeping locally meanwhile).
        members.put(self, presence.get().selfRecord());
        for (Map.Entry<String, Map<String, Object>> e : members.entrySet()) {
            Map<String, Object> m = e.getValue();
            long updated = num(m.get("updatedAt"));
            long age = updated > 0 ? now - updated : 0;
            boolean isSelf = self.equals(e.getKey());
            // Cut off from NATS, this node only has the records it read last: whether
            // another node is still running is unknown, not "late".
            String state = isSelf ? "LIVE" : !connected ? "UNKNOWN" : age > 2 * interval ? "STALE" : "LIVE";
            if ("STALE".equals(state)) {
                reasons.add("NODE_STALE");
            }
            boolean degraded = isSelf ? manager.isDegraded() : Boolean.TRUE.equals(m.get("degraded"));
            boolean draining = isSelf ? natsLeases.get().isDraining() : Boolean.TRUE.equals(m.get("draining"));
            if (!isSelf && degraded) {
                reasons.add("MEMBER_DEGRADED");
            }
            if (draining) {
                reasons.add("NODE_DRAINING");
            }
            int local = (int) num(m.get("localDeadLetters"));
            if (local > 0) {
                reasons.add("LOCAL_DEAD_LETTERS");
            }
            nodes.add(new ClusterNode(e.getKey(), str(m.get("boot")), str(m.get("host")), str(m.get("version")), num(m.get("startedAt")),
                    updated, age, state, isSelf, degraded, draining, isSelf ? manager.rttMillis() : num(m.get("natsRtt")),
                    (int) num(m.get("activeConversations")), (int) num(m.get("leasesHeld")), (int) num(m.get("queueDepthTotal")), local,
                    e.getKey().equals(leader), null));
        }
        for (Map.Entry<String, ClusterWatcher.GoneNode> e : watcher.goneNodes().entrySet()) {
            if (members.containsKey(e.getKey())) {
                continue;
            }
            Map<String, Object> m = e.getValue().lastRecord();
            long updated = num(m.get("updatedAt"));
            if (!e.getValue().clean()) {
                reasons.add("NODE_LOST");
            }
            nodes.add(new ClusterNode(e.getKey(), str(m.get("boot")), str(m.get("host")), str(m.get("version")), num(m.get("startedAt")),
                    updated, updated > 0 ? now - updated : 0, e.getValue().clean() ? "LEFT" : "LOST", false, false, false, -1, 0,
                    (int) num(m.get("leasesHeld")), 0, 0, false, e.getValue().goneAt()));
        }

        NatsView nats = natsView(manager);
        String verdict = "HEALTHY";
        if (!connected) {
            reasons.add(manager.isDegraded() ? "NATS_UNREACHABLE" : "NATS_RECONNECTING");
            verdict = "DEGRADED";
        } else {
            boolean offline = false;
            boolean behind = false;
            for (StreamView s : nats.streams()) {
                for (PeerView p : s.peers()) {
                    offline |= p.offline();
                    behind |= !p.current();
                }
            }
            for (BucketView b : nats.buckets()) {
                for (PeerView p : b.peers()) {
                    offline |= p.offline();
                    behind |= !p.current();
                }
            }
            if (offline) {
                reasons.add("NATS_PEER_OFFLINE");
                verdict = "PARTITIONED";
            } else if (behind) {
                reasons.add("NATS_REPLICA_BEHIND");
            }
            if (!"PARTITIONED".equals(verdict)
                    && (reasons.contains("NODE_LOST") || reasons.contains("NODE_STALE") || reasons.contains("MEMBER_DEGRADED")
                            || reasons.contains("NATS_REPLICA_BEHIND"))) {
                verdict = "DEGRADED";
            }
        }
        long shared = connected ? clusterCoordinator().map(ClusterConversationCoordinator::sharedDeadLetterCount).orElse(0L) : 0L;
        int localDeadLetters = nodes.stream().mapToInt(ClusterNode::localDeadLetters).sum();
        if (shared > 0) {
            reasons.add("DEAD_LETTERS_WAITING");
        }
        long since = manager.unavailableSinceMillis();
        return new ClusterOverview("cluster", verdict, List.copyOf(reasons), self, now, config.degradedTurns(),
                manager.isDegraded() && since > 0 ? since : null, nodes, nats, new DeadLetterCounts(shared, localDeadLetters), leader,
                settings);
    }

    private Map<String, Object> settings() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("messagingType", config.messagingType());
        if (isClustered()) {
            s.put("natsPrefix", config.natsPrefix());
            s.put("natsReplicas", config.natsReplicas());
            s.put("leaseTtlMs", config.leaseTtl().toMillis());
            s.put("leaseHeartbeatMs", config.leaseHeartbeatInterval().toMillis());
            s.put("leaseAcquireTimeoutMs", config.leaseAcquireTimeout().toMillis());
            s.put("degradedGraceMs", config.degradedGrace().toMillis());
            s.put("presenceIntervalMs", config.presenceInterval().toMillis());
            s.put("readinessRequireNats", config.readinessRequireNats());
            s.put("deadLetterMaxAgeMs", config.deadLetterMaxAge().toMillis());
            s.put("deadLetterCaptureInput", config.deadLetterCaptureInput());
        }
        return s;
    }

    private static String version() {
        return Optional.ofNullable(ClusterAdminService.class.getPackage().getImplementationVersion()).orElse("dev");
    }

    // ---------------------------------------------------------------- NATS view

    NatsView natsView(NatsConnectionManager manager) {
        long now = System.currentTimeMillis();
        NatsView cached = cachedNats;
        if (cached != null && now - cachedNatsAt < NATS_VIEW_CACHE_MILLIS) {
            return cached;
        }
        String status = manager.isDegraded() ? "DEGRADED" : manager.status().name();
        if (!manager.isConnected()) {
            return new NatsView(status, null, null, null, null, List.of(), manager.reconnectCount(), -1, List.of(), List.of(), null, null,
                    "NATS is not connected from this node");
        }
        Connection connection;
        try {
            connection = manager.requireConnected();
        } catch (ClusterUnavailableException e) {
            connection = null;
        }
        if (connection == null) {
            return new NatsView(status, null, null, null, null, List.of(), manager.reconnectCount(), -1, List.of(), List.of(), null, null,
                    "NATS is not connected from this node");
        }
        ServerInfo server = connection.getServerInfo();
        List<StreamView> streams = new ArrayList<>();
        List<BucketView> buckets = new ArrayList<>();
        LeaseEpoch epoch = null;
        AccountView account = null;
        String error = null;
        String prefix = config.natsPrefix();
        try {
            JetStreamManagement jsm = manager.jetStreamManagement();
            for (StreamInfo info : jsm.getStreams()) {
                String name = info.getConfiguration().getName();
                List<PeerView> peers = peers(info.getClusterInfo());
                String leader = info.getClusterInfo() == null ? null : info.getClusterInfo().getLeader();
                if (name.startsWith("KV_" + prefix + "_")) {
                    String bucket = name.substring(3);
                    var ttl = info.getConfiguration().getMaxAge();
                    buckets.add(new BucketView(bucket, bucket.substring(prefix.length() + 1), info.getConfiguration().getReplicas(),
                            info.getStreamState().getMsgCount(), info.getStreamState().getByteCount(),
                            ttl == null || ttl.isZero() ? null : ttl.toMillis(), leader, peers));
                    if (bucket.equals(prefix + "_LEASES")) {
                        epoch = new LeaseEpoch(info.getConfiguration().getFirstSequence(),
                                info.getCreateTime() == null ? null : info.getCreateTime().toInstant().toEpochMilli(),
                                info.getStreamState().getLastSequence());
                    }
                } else if (name.startsWith(prefix + "_") || name.equals(config.deadLetterStreamName()) || name.startsWith("OBJ_" + prefix + "_")) {
                    List<ConsumerView> consumers = new ArrayList<>();
                    if (info.getStreamState().getConsumerCount() > 0 && info.getStreamState().getConsumerCount() <= 32) {
                        for (ConsumerInfo ci : jsm.getConsumers(name)) {
                            consumers.add(new ConsumerView(ci.getName(), ci.getNumPending(), ci.getNumAckPending(), ci.getRedelivered()));
                        }
                    }
                    streams.add(new StreamView(name, streamRole(name, prefix), info.getConfiguration().getReplicas(),
                            info.getStreamState().getMsgCount(), info.getStreamState().getByteCount(), info.getStreamState().getFirstSequence(),
                            info.getStreamState().getLastSequence(), info.getStreamState().getConsumerCount(), leader, peers, consumers));
                }
            }
            AccountStatistics stats = jsm.getAccountStatistics();
            account = new AccountView(stats.getMemory(), stats.getStorage(), stats.getStreams(), stats.getConsumers());
        } catch (IOException | JetStreamApiException | RuntimeException e) {
            error = e.getMessage();
            LOGGER.debugf("JetStream view incomplete: %s", e.getMessage());
        }
        streams.sort(Comparator.comparing(StreamView::name));
        buckets.sort(Comparator.comparing(BucketView::name));
        List<String> known = new ArrayList<>();
        for (String url : connection.getServers()) {
            known.add(ClusterConfig.redactUserInfo(url));
        }
        NatsView view = new NatsView(status, ClusterConfig.redactUserInfo(connection.getConnectedUrl()), server.getServerName(),
                server.getVersion(), server.getCluster(), known, manager.reconnectCount(), manager.rttMillis(), streams, buckets, epoch, account,
                error);
        cachedNats = view;
        cachedNatsAt = now;
        return view;
    }

    private String streamRole(String name, String prefix) {
        if (name.equals(prefix + "_EVENTS")) {
            return "events";
        }
        if (name.equals(config.deadLetterStreamName())) {
            return "dead-letters";
        }
        if (name.equals(prefix + "_ACTIVITY")) {
            return "activity";
        }
        if (name.startsWith("OBJ_")) {
            return "archives";
        }
        return "other";
    }

    private static List<PeerView> peers(ClusterInfo cluster) {
        List<PeerView> peers = new ArrayList<>();
        if (cluster == null) {
            return peers;
        }
        if (cluster.getLeader() != null) {
            peers.add(new PeerView(cluster.getLeader(), true, false, 0, 0L));
        }
        if (cluster.getReplicas() != null) {
            for (Replica r : cluster.getReplicas()) {
                peers.add(new PeerView(r.getName(), r.isCurrent(), r.isOffline(), r.getLag(),
                        r.getActive() == null ? null : r.getActive().toMillis()));
            }
        }
        return peers;
    }

    // ================================================================ leases

    /**
     * @param query
     *            substring of the conversation id, key, holder node or agent id
     * @param flaggedOnly
     *            only leases with a suspicious flag
     */
    public LeasePage leases(String query, boolean flaggedOnly, int limit) {
        if (!isClustered()) {
            return new LeasePage(List.of(), 0, 0, false);
        }
        List<LeaseSnapshot> snapshots = natsLeases.get().snapshot("", MAX_LEASE_SCAN);
        boolean truncated = snapshots.size() >= MAX_LEASE_SCAN;
        Map<String, Map<String, Object>> members = membersById();
        long now = System.currentTimeMillis();
        List<LeaseView> views = new ArrayList<>();
        for (LeaseSnapshot s : snapshots) {
            views.add(leaseView(s, members, now, null));
        }
        // Agents for the conversations, in one read.
        Map<String, ConversationListingSummary> summaries = summaries(
                views.stream().map(LeaseView::conversationId).filter(id -> id != null).toList());
        List<LeaseView> enriched = new ArrayList<>();
        for (LeaseView v : views) {
            ConversationListingSummary summary = v.conversationId() == null ? null : summaries.get(v.conversationId());
            enriched.add(summary == null ? v : withConversation(v, summary));
        }
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<LeaseView> matching = enriched.stream()
                .filter(v -> q.isEmpty() || contains(v.key(), q) || contains(v.holderNode(), q) || contains(v.agentId(), q))
                .filter(v -> !flaggedOnly || !v.flags().isEmpty())
                .sorted(Comparator.comparing((LeaseView v) -> v.flags().isEmpty()).thenComparing(LeaseView::ageMs, Comparator.reverseOrder()))
                .toList();
        int suspicious = (int) enriched.stream().filter(v -> !v.flags().isEmpty()).count();
        int max = Math.max(1, Math.min(limit <= 0 ? 100 : limit, MAX_PAGE));
        return new LeasePage(matching.size() <= max ? matching : matching.subList(0, max), matching.size(), suspicious, truncated);
    }

    private static boolean contains(String value, String q) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(q);
    }

    private Map<String, Map<String, Object>> membersById() {
        Map<String, Map<String, Object>> members = new HashMap<>();
        for (Map<String, Object> m : presence.get().members()) {
            members.put(String.valueOf(m.get("node")), m);
        }
        return members;
    }

    private Map<String, ConversationListingSummary> summaries(List<String> conversationIds) {
        if (conversationIds.isEmpty()) {
            return Map.of();
        }
        try {
            return memoryStore.loadListingSummaries(conversationIds);
        } catch (Exception e) {
            LOGGER.debugf("Conversation summaries unavailable: %s", e.getMessage());
            return Map.of();
        }
    }

    private static LeaseView withConversation(LeaseView v, ConversationListingSummary s) {
        return new LeaseView(v.key(), v.kind(), v.conversationId(), s.agentId(),
                s.conversationState() == null ? null : s.conversationState().name(), v.holderNode(), v.holderBoot(), v.revision(), v.since(),
                v.ageMs(), v.renewedAt(), v.sinceRenewalMs(), v.holderStatus(), v.flags(), v.waitingNode());
    }

    LeaseView leaseView(LeaseSnapshot s, Map<String, Map<String, Object>> members, long now, ConversationListingSummary summary) {
        String key = s.key();
        String kind = key.startsWith(IConversationLeaseManager.CONVERSATION)
                ? "conversation"
                : key.startsWith(IConversationLeaseManager.GROUP) ? "group" : key.startsWith(IConversationLeaseManager.LEADER) ? "leader" : "other";
        String conversationId = "conversation".equals(kind) ? key.substring(IConversationLeaseManager.CONVERSATION.length()) : null;
        Map<String, Object> holder = members.get(s.holder().node());
        String holderStatus = holder == null ? "GONE" : String.valueOf(holder.get("boot")).equals(s.holder().boot()) ? "LIVE" : "RESTARTED";
        List<String> flags = new ArrayList<>();
        if ("GONE".equals(holderStatus)) {
            flags.add("HOLDER_GONE");
        } else if ("RESTARTED".equals(holderStatus)) {
            flags.add("HOLDER_RESTARTED");
        }
        long sinceRenewal = s.renewedAt() > 0 ? Math.max(0, now - s.renewedAt()) : -1;
        if (sinceRenewal > config.leaseHeartbeatInterval().multipliedBy(2).toMillis()) {
            flags.add("NOT_RENEWED");
        }
        long age = s.holder().since() > 0 ? Math.max(0, now - s.holder().since()) : 0;
        if ("conversation".equals(kind) && age > config.leaseAcquireTimeout().toMillis()) {
            flags.add("LONG_RUNNING");
        }
        if (s.waitingNode() != null) {
            flags.add("CONTENDED");
        }
        return new LeaseView(key, kind, conversationId, summary == null ? null : summary.agentId(),
                summary == null || summary.conversationState() == null ? null : summary.conversationState().name(), s.holder().node(),
                s.holder().boot(), s.holder().revision(), s.holder().since(), age, s.renewedAt(), sinceRenewal, holderStatus, flags,
                s.waitingNode());
    }

    // ================================================================ dead letters

    /**
     * One page of entries, oldest first, from the shared stream then node-local
     * ones.
     */
    private List<DeadLetterEntry> page(int limit, String after) {
        Optional<ClusterConversationCoordinator> cluster = clusterCoordinator();
        if (cluster.isPresent()) {
            return cluster.get().getDeadLetters(limit, after);
        }
        List<DeadLetterEntry> all = coordinator.getDeadLetters();
        int start = 0;
        if (after != null) {
            for (int i = 0; i < all.size(); i++) {
                if (after.equals(all.get(i).id())) {
                    start = i + 1;
                    break;
                }
            }
        }
        return all.subList(Math.min(start, all.size()), Math.min(all.size(), start + limit));
    }

    /** Filters for {@link #deadLetters}; every field optional. */
    public record DeadLetterFilter(String reason, String nodeId, String agentId, String conversationId, Long from, Long to) {

        boolean matches(DeadLetterEntry e) {
            if (reason != null && !reason.isBlank() && !reason.equals(e.reason() == null ? DeadLetterEntry.REASON_FAILED : e.reason())) {
                return false;
            }
            if (nodeId != null && !nodeId.isBlank() && !nodeId.equals(e.nodeId())) {
                return false;
            }
            if (agentId != null && !agentId.isBlank() && (e.turn() == null || !agentId.equals(String.valueOf(e.turn().get("agentId"))))) {
                return false;
            }
            if (conversationId != null && !conversationId.isBlank()
                    && (e.conversationId() == null || !e.conversationId().contains(conversationId.trim()))) {
                return false;
            }
            if (from != null && e.timestamp() < from) {
                return false;
            }
            return to == null || e.timestamp() <= to;
        }
    }

    /**
     * A filtered page. Reads at most {@value #DEAD_LETTER_SCAN_BUDGET} entries per
     * call; when the budget runs out before the page is full, {@code nextCursor}
     * says where to continue.
     */
    public DeadLetterPage deadLetters(int limit, String after, DeadLetterFilter filter, boolean includeInput) {
        int max = Math.max(1, Math.min(limit <= 0 ? 50 : limit, 200));
        List<DeadLetterView> entries = new ArrayList<>();
        String cursor = after;
        int scanned = 0;
        String next = null;
        outer : while (true) {
            List<DeadLetterEntry> batch = page(200, cursor);
            if (batch.isEmpty()) {
                break;
            }
            for (DeadLetterEntry e : batch) {
                cursor = e.id();
                scanned++;
                if (filter.matches(e)) {
                    entries.add(view(e, includeInput));
                    if (entries.size() >= max) {
                        next = cursor;
                        break outer;
                    }
                }
                if (scanned >= DEAD_LETTER_SCAN_BUDGET) {
                    next = cursor;
                    break outer;
                }
            }
            if (batch.size() < 200) {
                break;
            }
        }
        if (next != null && page(1, next).isEmpty()) {
            next = null;
        }
        return new DeadLetterPage(entries, next, scanned);
    }

    /** Counts without content — safe for a read-only role. */
    public DeadLetterSummary deadLetterSummary() {
        Map<String, Long> byReason = new TreeMap<>();
        Map<String, Long> byNode = new TreeMap<>();
        Map<String, Long> byAgent = new TreeMap<>();
        Long oldest = null;
        Long newest = null;
        int scanned = 0;
        int local = 0;
        List<DeadLetterEntry> recent = new ArrayList<>();
        String cursor = null;
        boolean truncated = false;
        while (true) {
            List<DeadLetterEntry> batch = page(200, cursor);
            if (batch.isEmpty()) {
                break;
            }
            for (DeadLetterEntry e : batch) {
                cursor = e.id();
                scanned++;
                byReason.merge(e.reason() == null ? DeadLetterEntry.REASON_FAILED : e.reason(), 1L, Long::sum);
                byNode.merge(e.nodeId() == null ? "unknown" : e.nodeId(), 1L, Long::sum);
                Object agent = e.turn() == null ? null : e.turn().get("agentId");
                byAgent.merge(agent == null ? "unknown" : agent.toString(), 1L, Long::sum);
                oldest = oldest == null ? e.timestamp() : Math.min(oldest, e.timestamp());
                newest = newest == null ? e.timestamp() : Math.max(newest, e.timestamp());
                if (isLocal(e.id())) {
                    local++;
                }
                recent.add(e);
                if (recent.size() > 5) {
                    recent.remove(0);
                }
            }
            if (scanned >= SUMMARY_SCAN_BUDGET) {
                truncated = !page(1, cursor).isEmpty();
                break;
            }
            if (batch.size() < 200) {
                break;
            }
        }
        return new DeadLetterSummary(scanned, local, byReason, byNode, byAgent, oldest, newest, scanned, truncated,
                recent.stream().map(e -> view(e, false)).toList());
    }

    static boolean isLocal(String id) {
        return id != null && id.startsWith("local-");
    }

    static DeadLetterView view(DeadLetterEntry e, boolean includeInput) {
        Map<String, Object> turn = e.turn() == null ? Map.of() : e.turn();
        Object agentVersion = turn.get("agentVersion");
        Object input = turn.get("input");
        // The error text is an exception message and can quote what a user typed or a
        // value from a configuration, so — like the input — only the admin listing has
        // it.
        return new DeadLetterView(e.id(), e.conversationId(), str(turn.get("agentId")),
                agentVersion instanceof Number n ? n.intValue() : null, str(turn.get("environment")), includeInput ? e.error() : null, e.timestamp(),
                e.reason() == null ? DeadLetterEntry.REASON_FAILED : e.reason(), e.nodeId(), e.fence(),
                includeInput && input != null ? input.toString() : null, Boolean.TRUE.equals(turn.get("secretInput")), e.isReplayable(),
                e.notReplayableReason(), isLocal(e.id()));
    }

    public BulkResult replay(List<String> ids, String actor) {
        List<String> unique = bounded(ids);
        List<ItemOutcome> results = new ArrayList<>();
        for (String id : unique) {
            results.add(replayOne(id));
        }
        BulkResult result = summarize(results, "REPLAYED");
        audit("deadletters.replay", actor, Map.of("ids", unique), Map.of("succeeded", result.succeeded(), "failed", result.failed(),
                "outcomes", outcomeMap(results)));
        activity.record("admin.deadletters.replay", ClusterActivityLog.INFO, Map.of("actor", actor, "count", unique.size(), "succeeded",
                result.succeeded()), null);
        count("deadletters.replay", result.failed() == 0 ? "ok" : "partial");
        return result;
    }

    /**
     * Replays one entry exactly once, even when two administrators (on any nodes)
     * replay it at the same moment.
     * <p>
     * The entry is <b>claimed</b> before anything is read: a create of
     * {@code replay.<id>} in the shared {@code ADMIN} bucket, which only one caller
     * can win (the bucket's leader decides). The winner then reads the entry —
     * after the claim, so a replay that finished in the meantime, which deletes the
     * entry before it drops its claim, is seen as gone — submits the turn, deletes
     * the entry and only then drops the claim. Whoever loses gets
     * {@code IN_PROGRESS} and nothing runs.
     * <p>
     * Nothing is lost: the entry stays in the stream until its turn was accepted; a
     * rejected turn drops the claim and keeps the entry; a node that dies while
     * holding a claim leaves the entry in place, and the claim expires with the
     * bucket's TTL ({@value #CLAIM_TTL_SECONDS} s). The one window left is a node
     * dying between the turn being accepted and the entry being deleted — then the
     * entry is listed again after the TTL; the replayed turn carries
     * {@code replayOf=<id>} in its context, which tells the two apart.
     */
    ItemOutcome replayOne(String id) {
        boolean local = isLocal(id);
        String claimKey = "replay." + KvKeys.safe(id);
        if (local) {
            if (!localReplayClaims.add(id)) {
                return new ItemOutcome(id, "IN_PROGRESS", "another administrator is replaying this entry right now");
            }
        } else {
            try {
                if (claims().create(claimKey, nodeName().getBytes(StandardCharsets.UTF_8)).isEmpty()) {
                    return new ItemOutcome(id, "IN_PROGRESS", "another administrator is replaying this entry right now");
                }
            } catch (ClusterUnavailableException e) {
                return new ItemOutcome(id, "UNAVAILABLE", "NATS is unreachable — try again when the cluster is connected");
            }
        }
        boolean releaseClaim = true;
        try {
            ItemOutcome outcome = replayClaimed(id);
            // Submitted but the entry could not be deleted: keep the claim, so nobody
            // replays it again until the claim expires (and someone may delete it).
            releaseClaim = !("REPLAYED".equals(outcome.outcome()) && outcome.message() != null);
            return outcome;
        } finally {
            if (local) {
                localReplayClaims.remove(id);
            } else if (releaseClaim) {
                try {
                    claims().delete(claimKey);
                } catch (ClusterUnavailableException e) {
                    LOGGER.debugf("Replay claim %s not released (expires with the TTL): %s", sanitize(id), e.getMessage());
                }
            }
        }
    }

    private ItemOutcome replayClaimed(String id) {
        Optional<DeadLetterEntry> found;
        try {
            found = coordinator.getDeadLetter(id);
        } catch (ClusterUnavailableException e) {
            return new ItemOutcome(id, "UNAVAILABLE", "NATS is unreachable — try again when the cluster is connected");
        }
        if (found.isEmpty()) {
            return new ItemOutcome(id, "NOT_FOUND", "already replayed, discarded or expired");
        }
        DeadLetterEntry entry = found.get();
        if (!entry.isReplayable()) {
            return new ItemOutcome(id, "NOT_REPLAYABLE", entry.notReplayableReason());
        }
        Map<String, Context> context = new HashMap<>();
        context.put("replayOf", new Context(Context.ContextType.string, id));
        InputData input = new InputData(String.valueOf(entry.turn().get("input")), context);
        try {
            conversationService.say(entry.conversationId(), false, true, List.of(), input, false, snapshot -> {
            });
        } catch (Exception e) {
            return new ItemOutcome(id, "REJECTED", e.getClass().getSimpleName() + ": " + sanitize(String.valueOf(e.getMessage())));
        }
        try {
            coordinator.replayDeadLetter(id);
        } catch (ClusterUnavailableException e) {
            return new ItemOutcome(id, "REPLAYED", "submitted; the entry could not be removed yet (NATS unreachable)");
        }
        return new ItemOutcome(id, "REPLAYED", null);
    }

    public BulkResult discard(List<String> ids, String actor) {
        List<String> unique = bounded(ids);
        List<ItemOutcome> results = new ArrayList<>();
        for (String id : unique) {
            try {
                results.add(coordinator.discardDeadLetter(id)
                        ? new ItemOutcome(id, "DISCARDED", null)
                        : new ItemOutcome(id, "NOT_FOUND", "already replayed, discarded or expired"));
            } catch (ClusterUnavailableException e) {
                results.add(new ItemOutcome(id, "UNAVAILABLE", "NATS is unreachable — try again when the cluster is connected"));
            }
        }
        BulkResult result = summarize(results, "DISCARDED");
        audit("deadletters.discard", actor, Map.of("ids", unique), Map.of("succeeded", result.succeeded(), "failed", result.failed(),
                "outcomes", outcomeMap(results)));
        activity.record("admin.deadletters.discard", ClusterActivityLog.INFO, Map.of("actor", actor, "count", unique.size(), "succeeded",
                result.succeeded()), null);
        count("deadletters.discard", result.failed() == 0 ? "ok" : "partial");
        return result;
    }

    private static List<String> bounded(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new IllegalArgumentException("ids must not be empty");
        }
        List<String> unique = new ArrayList<>(new LinkedHashSet<>(ids.stream().filter(id -> id != null && !id.isBlank()).toList()));
        if (unique.size() > MAX_BULK) {
            throw new IllegalArgumentException("at most " + MAX_BULK + " ids per request");
        }
        return unique;
    }

    private static BulkResult summarize(List<ItemOutcome> results, String success) {
        int ok = (int) results.stream().filter(r -> success.equals(r.outcome())).count();
        return new BulkResult(results, ok, results.size() - ok);
    }

    private static Map<String, Object> outcomeMap(List<ItemOutcome> results) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (ItemOutcome r : results) {
            map.put(r.id(), r.outcome());
        }
        return map;
    }

    // ================================================================ diagnosis

    public Diagnosis diagnose(String conversationId) {
        String id = conversationId == null ? "" : conversationId.trim();
        ConversationListingSummary summary = summaries(List.of(id)).get(id);
        List<Finding> findings = new ArrayList<>();
        LeaseView lease = null;
        Map<String, Integer> queuedOn = new TreeMap<>();
        Integer localDepth = coordinator.getQueueDepths().get(id);
        String self = isClustered() ? connections.get().node().nodeId() : "local";
        if (localDepth != null && localDepth > 0) {
            queuedOn.put(self, localDepth);
        }
        if (isClustered()) {
            try {
                Optional<LeaseSnapshot> snapshot = natsLeases.get().peekSnapshot(IConversationLeaseManager.CONVERSATION + id);
                if (snapshot.isPresent()) {
                    lease = leaseView(snapshot.get(), membersById(), System.currentTimeMillis(), summary);
                }
            } catch (ClusterUnavailableException e) {
                findings.add(new Finding("NATS_UNREACHABLE", "error", "WAIT", Map.of()));
            }
            rpc.callAll(IClusterRpc.COORDINATOR_STATUS, Map.of()).forEach((node, reply) -> {
                if (reply.get("queueDepths") instanceof Map<?, ?> depths && depths.get(id) instanceof Number n && n.intValue() > 0) {
                    queuedOn.put(node, n.intValue());
                }
            });
        }
        List<DeadLetterView> deadLetters = clusterCoordinator().map(c -> c.getDeadLettersOf(id, 20)).orElseGet(
                () -> coordinator.getDeadLetters().stream().filter(e -> id.equals(e.conversationId())).limit(20).toList()).stream()
                .map(e -> view(e, false)).toList();

        if (summary == null && lease == null && deadLetters.isEmpty()) {
            findings.add(new Finding("NOT_FOUND", "info", "NONE", Map.of()));
            return new Diagnosis(id, "NOT_FOUND", false, null, null, null, 0, null, queuedOn, deadLetters, findings);
        }
        String state = summary == null || summary.conversationState() == null ? null : summary.conversationState().name();
        if (lease != null) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("holderNode", lease.holderNode());
            details.put("ageMs", lease.ageMs());
            details.put("sinceRenewalMs", lease.sinceRenewalMs());
            details.put("revision", String.valueOf(lease.revision()));
            if (lease.flags().contains("HOLDER_GONE") || lease.flags().contains("HOLDER_RESTARTED") || lease.flags().contains("NOT_RENEWED")) {
                findings.add(new Finding("LEASE_ORPHANED", "error", "FORCE_RELEASE", details));
            } else if (lease.flags().contains("LONG_RUNNING")) {
                findings.add(new Finding("TURN_LONG_RUNNING", "warning", "CANCEL", details));
            } else {
                findings.add(new Finding("TURN_RUNNING", "info", "WAIT", details));
            }
            if (lease.waitingNode() != null) {
                findings.add(new Finding("TURN_WAITING_ELSEWHERE", "info", "WAIT", Map.of("waitingNode", lease.waitingNode())));
            }
        }
        if (!queuedOn.isEmpty()) {
            findings.add(new Finding("TURNS_QUEUED", "info", "WAIT", new LinkedHashMap<>(queuedOn)));
        }
        if ("AWAITING_HUMAN".equals(state)) {
            findings.add(new Finding("AWAITING_HUMAN", "warning", "OPEN_APPROVALS", Map.of()));
        } else if ("IN_PROGRESS".equals(state) && lease == null && queuedOn.isEmpty()) {
            Map<String, Object> d = new LinkedHashMap<>();
            if (isClustered()) {
                d.put("recoveryMinAgeMs", config.hitlRecoveryMinAge().toMillis());
            }
            findings.add(new Finding("IN_PROGRESS_WITHOUT_TURN", "warning", isClustered() ? "WAIT" : "CANCEL", d));
        } else if ("ERROR".equals(state)) {
            findings.add(new Finding("STATE_ERROR", "warning", "NONE", Map.of()));
        }
        if (!deadLetters.isEmpty()) {
            long replayable = deadLetters.stream().filter(DeadLetterView::replayable).count();
            findings.add(new Finding("DEAD_LETTERED", "warning", replayable > 0 ? "REPLAY" : "NONE",
                    Map.of("count", deadLetters.size(), "replayable", replayable)));
        }
        String verdict = "OK";
        for (Finding f : findings) {
            if ("error".equals(f.severity())) {
                verdict = "STUCK";
                break;
            }
            if ("warning".equals(f.severity())) {
                verdict = "NEEDS_ATTENTION";
            } else if ("OK".equals(verdict) && ("TURN_RUNNING".equals(f.code()) || "TURNS_QUEUED".equals(f.code()))) {
                verdict = "BUSY";
            }
        }
        if (findings.isEmpty()) {
            findings.add(new Finding("IDLE", "info", "NONE", Map.of()));
        }
        return new Diagnosis(id, verdict, summary != null, summary == null ? null : summary.agentId(),
                summary == null ? null : summary.agentVersion(), state, summary == null ? 0 : summary.conversationStepCount(), lease, queuedOn,
                deadLetters, findings);
    }

    // ================================================================ recovery
    // actions

    /**
     * Thrown when an action is refused; carries the HTTP status to answer.
     * <p>
     * Never a 5xx: a load balancer that retries the next node on 503 (the shipped
     * nginx configuration does, for draining nodes) marks every node down when they
     * all answer "NATS is unreachable" — and then refuses the whole API, turns
     * included, for its fail timeout. Seen live during a NATS outage.
     */
    public static class ActionRefusedException extends RuntimeException {
        private final int status;
        private final String code;

        public ActionRefusedException(int status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }

        public int status() {
            return status;
        }

        public String code() {
            return code;
        }
    }

    private void requireCluster(String action) {
        if (!isClustered()) {
            throw new ActionRefusedException(409, "NOT_CLUSTERED", action + " needs cluster mode (eddi.messaging.type=nats)");
        }
    }

    public ActionResult forceRelease(String conversationId, Long expectedRevision, String actor) {
        requireCluster("Releasing a lease");
        String key = IConversationLeaseManager.CONVERSATION + conversationId;
        ForceRelease result;
        try {
            result = natsLeases.get().forceRelease(key, expectedRevision);
        } catch (ClusterUnavailableException e) {
            throw new ActionRefusedException(409, "NATS_UNREACHABLE", "NATS is unreachable from this node: " + e.getMessage());
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("conversationId", conversationId);
        details.put("currentRevision", String.valueOf(result.currentRevision()));
        if (result.holder() != null) {
            details.put("holderNode", result.holder().node());
            details.put("holderBoot", result.holder().boot());
        }
        String outcome = result.outcome().name();
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("conversationId", conversationId);
        input.put("expectedRevision", expectedRevision);
        audit("lease.release", actor, input, Map.of("outcome", outcome, "details", details));
        Map<String, Object> event = new LinkedHashMap<>(details);
        event.put("actor", actor);
        event.put("outcome", outcome);
        activity.record("admin.lease.release", ClusterActivityLog.WARNING, event, null);
        count("lease.release", outcome.toLowerCase(Locale.ROOT));
        String message = switch (result.outcome()) {
            case RELEASED -> "Lease released. The next turn acquires a newer fencing token; a late write of the former holder is refused "
                    + "and dead-lettered.";
            case ALREADY_RELEASED -> "Nobody holds this conversation's lease; nothing was changed.";
            case RENEWED -> "The holder renewed the lease after you looked — it is alive. Nothing was changed.";
        };
        return new ActionResult("lease.release", outcome, message, details);
    }

    public ActionResult resyncCaches(String actor) {
        requireCluster("A cluster-wide cache resync");
        String reason = "requested by an administrator";
        eventBus.get().requestResyncAll(reason);
        audit("caches.resync", actor, Map.of(), Map.of("outcome", "DONE"));
        activity.record("admin.caches.resync", ClusterActivityLog.INFO, Map.of("actor", actor), null);
        count("caches.resync", "done");
        return new ActionResult("caches.resync", "DONE", "Every node flushes its invalidatable caches and reloads from the database.",
                Map.of());
    }

    public ActionResult reconcileDeployments(String actor) {
        deployments.reconcileNow();
        List<String> answered = new ArrayList<>();
        answered.add(isClustered() ? connections.get().node().nodeId() : "local");
        if (isClustered()) {
            answered.addAll(rpc.callAll(RPC_RECONCILE, Map.of()).keySet());
        }
        audit("deployments.reconcile", actor, Map.of(), Map.of("outcome", "DONE", "nodes", answered));
        activity.record("admin.deployments.reconcile", ClusterActivityLog.INFO, Map.of("actor", actor, "nodes", answered), null);
        count("deployments.reconcile", "done");
        return new ActionResult("deployments.reconcile", "DONE", "The deployment sweep ran on " + answered.size() + " node(s).",
                Map.of("nodes", answered));
    }

    public ActionResult forwardLocalDeadLetters(String actor) {
        requireCluster("Forwarding local dead letters");
        if (!connections.get().isConnected()) {
            throw new ActionRefusedException(409, "NATS_UNREACHABLE", "NATS is unreachable from this node; local dead letters stay where they are");
        }
        Map<String, Object> perNode = new TreeMap<>();
        perNode.put(connections.get().node().nodeId(), clusterCoordinator().map(ClusterConversationCoordinator::forwardLocalDeadLetters).orElse(0));
        rpc.callAll(RPC_FORWARD, Map.of()).forEach((node, reply) -> perNode.put(node, reply.getOrDefault("forwarded", 0)));
        int total = perNode.values().stream().mapToInt(v -> v instanceof Number n ? n.intValue() : 0).sum();
        audit("deadletters.forward-local", actor, Map.of(), Map.of("outcome", "DONE", "forwarded", perNode));
        activity.record("admin.deadletters.forward", ClusterActivityLog.INFO, Map.of("actor", actor, "forwarded", total), null);
        count("deadletters.forward-local", "done");
        return new ActionResult("deadletters.forward-local", "DONE", total + " dead letter(s) moved to the shared stream.",
                Map.of("perNode", perNode, "total", total));
    }

    public ActionResult drain(String nodeId, boolean drain, String actor) {
        requireCluster(drain ? "Draining a node" : "Undraining a node");
        NatsConnectionManager manager = connections.get();
        String self = manager.node().nodeId();
        Map<String, Map<String, Object>> members = membersById();
        if (!self.equals(nodeId) && !members.containsKey(nodeId)) {
            throw new ActionRefusedException(404, "NODE_NOT_FOUND", "No live node " + sanitize(nodeId));
        }
        // One drain decision at a time, cluster-wide: the guard below counts the
        // drained
        // nodes from the shared drain keys (leader reads), and the key of this decision
        // is
        // written before the gate opens again — so two administrators draining the last
        // two nodes at once cannot both pass it.
        ISharedKv kv = claims();
        try {
            if (kv.create(DRAIN_GATE, self.getBytes(StandardCharsets.UTF_8)).isEmpty()) {
                throw new ActionRefusedException(409, "BUSY",
                        "Another drain or undrain is being applied right now — try again in a moment.");
            }
        } catch (ClusterUnavailableException e) {
            throw new ActionRefusedException(409, "NATS_UNREACHABLE", "NATS is unreachable from this node; nothing was changed.");
        }
        try {
            if (drain) {
                Set<String> drained = drainedNodes(kv);
                long now = System.currentTimeMillis();
                long staleAfter = 2 * config.presenceInterval().toMillis();
                long others = members.entrySet().stream().filter(e -> !e.getKey().equals(nodeId))
                        .filter(e -> !drained.contains(e.getKey()))
                        .filter(e -> !(e.getKey().equals(self) && natsLeases.get().isDraining()))
                        // A record that stopped being rewritten is a node that may be gone.
                        .filter(e -> e.getKey().equals(self) || now - num(e.getValue().get("updatedAt")) <= staleAfter)
                        .count();
                if (others == 0) {
                    throw new ActionRefusedException(409, "LAST_NODE",
                            "Refused: " + sanitize(nodeId) + " is the last node taking turns — draining it would stop the whole cluster.");
                }
                kv.put(DRAIN_PREFIX + nodeId, "1".getBytes(StandardCharsets.UTF_8));
            }
            boolean applied;
            if (self.equals(nodeId)) {
                applyDrainLocally(drain);
                applied = true;
            } else {
                Optional<Map<String, Object>> reply = rpc.call(nodeId, RPC_DRAIN, Map.of("drain", drain));
                applied = reply.isPresent() && reply.get().get("error") == null;
            }
            if (!applied) {
                if (drain) {
                    kv.delete(DRAIN_PREFIX + nodeId);
                }
                throw new ActionRefusedException(409, "NODE_UNREACHABLE", "Node " + sanitize(nodeId) + " did not answer; nothing was changed.");
            }
            if (!drain) {
                kv.delete(DRAIN_PREFIX + nodeId);
            }
        } catch (ClusterUnavailableException e) {
            throw new ActionRefusedException(409, "NATS_UNREACHABLE", "NATS is unreachable from this node: " + e.getMessage());
        } finally {
            try {
                kv.delete(DRAIN_GATE);
            } catch (ClusterUnavailableException e) {
                LOGGER.debugf("Drain gate not released (expires with the TTL): %s", e.getMessage());
            }
        }
        String outcome = drain ? "DRAINED" : "UNDRAINED";
        audit(drain ? "node.drain" : "node.undrain", actor, Map.of("nodeId", nodeId), Map.of("outcome", outcome));
        activity.record(drain ? "admin.node.drain" : "admin.node.undrain", ClusterActivityLog.WARNING, Map.of("actor", actor, "nodeId", nodeId),
                null);
        count(drain ? "node.drain" : "node.undrain", "done");
        return new ActionResult(drain ? "node.drain" : "node.undrain", outcome,
                drain
                        ? "The node takes no new turns and reports itself not ready; running turns finish. Undrain it or restart it."
                        : "The node takes turns again.",
                Map.of("nodeId", nodeId));
    }

    private void applyDrainLocally(boolean drain) {
        natsLeases.get().setDraining(drain);
        syncOwnDrainKey();
        presence.get().publishNow();
        LOGGER.warnf("Node %s %s by an administrator", connections.get().node().nodeId(), drain ? "DRAINED" : "undrained");
    }

    // ================================================================ shared admin
    // state

    static final String DRAIN_GATE = "gate.drain";
    static final String DRAIN_PREFIX = "drain.";
    static final long CLAIM_TTL_SECONDS = 60;
    /**
     * Replay claims, the drain gate and drain keys; expires what a dead node left
     * behind.
     */
    static final SharedBucket ADMIN_BUCKET = new SharedBucket("ADMIN", Duration.ofSeconds(CLAIM_TTL_SECONDS), 1024);

    private ISharedKv claims() {
        ISharedKv kv = claims;
        if (kv == null) {
            kv = sharedState.bucket(ADMIN_BUCKET);
            claims = kv;
        }
        return kv;
    }

    private String nodeName() {
        return isClustered() ? connections.get().node().nodeId() : "local";
    }

    /** The nodes with a drain key, read from the bucket's leader. */
    private static Set<String> drainedNodes(ISharedKv kv) {
        Set<String> drained = new HashSet<>();
        for (String key : kv.keys()) {
            if (key.startsWith(DRAIN_PREFIX) && kv.getConsistent(key).isPresent()) {
                drained.add(key.substring(DRAIN_PREFIX.length()));
            }
        }
        return drained;
    }

    /** Keeps this node's drain key in step with its drain flag. */
    void syncOwnDrainKey() {
        if (!isClustered()) {
            return;
        }
        try {
            String key = DRAIN_PREFIX + connections.get().node().nodeId();
            if (natsLeases.get().isDraining()) {
                claims().put(key, "1".getBytes(StandardCharsets.UTF_8));
            } else if (claims().get(key).isPresent()) {
                claims().delete(key);
            }
        } catch (ClusterUnavailableException e) {
            LOGGER.debugf("Drain key not synced: %s", e.getMessage());
        }
    }

    // ================================================================ audit &
    // metrics

    void audit(String action, String actor, Map<String, Object> input, Map<String, Object> output) {
        try {
            Map<String, Object> in = new LinkedHashMap<>(input);
            in.put("action", action);
            in.put("node", isClustered() ? connections.get().node().nodeId() : "local");
            auditLedger.submit(new AuditEntry(UUID.randomUUID().toString(), AUDIT_TRAIL, null, null, actor, null, 0, "ai.labs.cluster.admin",
                    "cluster-admin", 0, 0, in, new LinkedHashMap<>(output), null, null, List.of(action), 0.0, Instant.now(), null, null));
        } catch (RuntimeException e) {
            LOGGER.warnf("Could not write the audit entry of cluster action %s: %s", action, e.getMessage());
        }
    }

    private void count(String action, String outcome) {
        meterRegistry.counter("eddi.cluster.admin.actions", "action", action, "outcome", outcome).increment();
    }

    private static long num(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }

    private static String str(Object value) {
        return value == null ? null : value.toString();
    }
}
