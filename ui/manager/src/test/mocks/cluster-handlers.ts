import { http, HttpResponse } from "msw";
import type {
  ActivityEvent,
  ClusterOverview,
  DeadLetterView,
  ItemOutcome,
  LeaseView,
} from "@/lib/api/cluster";

/**
 * A realistic 3-node cluster for the cluster console: n1-n3 behind a load
 * balancer, an R3 NATS cluster, a lease whose holder (n3) was lost, a fenced
 * dead letter, a secret one that cannot be replayed, and an activity timeline.
 * Shapes follow `ClusterAdminModels` in the backend. The state is mutable so a
 * replay, discard, release or drain changes what the next read returns;
 * `resetClusterFixture()` restores it between tests.
 */

const now = () => Date.now();

function freshNodes(): ClusterOverview["nodes"] {
  const t = now();
  const base = { host: null, version: "6.6.0", boot: null, natsRtt: 1, queueDepthTotal: 0, localDeadLetters: 0, hitlLeader: false, goneSince: null };
  return [
    { ...base, nodeId: "eddi-1", boot: "a1b2c3d4", host: "eddi-7c9f8d6b5-x2k4q", startedAt: t - 3_600_000, lastHeartbeat: t - 2_000, heartbeatAgeMs: 2_000, state: "LIVE", self: true, degraded: false, draining: false, activeConversations: 4, leasesHeld: 2, hitlLeader: true },
    { ...base, nodeId: "eddi-2", boot: "e5f6a7b8", host: "eddi-7c9f8d6b5-p9m2c", startedAt: t - 3_500_000, lastHeartbeat: t - 6_000, heartbeatAgeMs: 6_000, state: "LIVE", self: false, degraded: false, draining: false, activeConversations: 3, leasesHeld: 1, natsRtt: 2 },
    { ...base, nodeId: "eddi-3", boot: "c9d0e1f2", host: "eddi-7c9f8d6b5-r7t5w", startedAt: t - 3_400_000, lastHeartbeat: t - 41_000, heartbeatAgeMs: 41_000, state: "LOST", self: false, degraded: false, draining: false, activeConversations: 0, leasesHeld: 1, goneSince: t - 11_000, natsRtt: -1 },
  ];
}

function peers(leader: string) {
  return ["nats-0", "nats-1", "nats-2"].map((name, i) => ({
    name: i === 0 ? leader : name === leader ? "nats-0" : name,
    current: true,
    offline: false,
    lag: 0,
    activeMillis: i === 0 ? 0 : 120,
  }));
}

function freshOverview(): ClusterOverview {
  return {
    mode: "cluster",
    verdict: "DEGRADED",
    reasons: ["NODE_LOST", "DEAD_LETTERS_WAITING"],
    answeredBy: "eddi-1",
    generatedAt: now(),
    degradedTurnsPolicy: "local",
    degradedSince: null,
    nodes: freshNodes(),
    nats: {
      status: "CONNECTED",
      connectedUrl: "nats://nats-0.nats:4222",
      serverName: "nats-0",
      serverVersion: "2.11.4",
      clusterName: "eddi-nats",
      knownServers: ["nats://nats-0.nats:4222", "nats://nats-1.nats:4222", "nats://nats-2.nats:4222"],
      reconnects: 0,
      rttMillis: 1,
      streams: [
        { name: "EDDI_ACTIVITY", role: "activity", replicas: 3, messages: 412, bytes: 189_220, firstSequence: 1, lastSequence: 412, consumerCount: 2, leader: "nats-1", peers: peers("nats-1"), consumers: [{ name: "oc1", pending: 0, ackPending: 0, redelivered: 0 }] },
        { name: "EDDI_DEAD_LETTERS", role: "dead-letters", replicas: 3, messages: 3, bytes: 4_812, firstSequence: 14, lastSequence: 17, consumerCount: 0, leader: "nats-0", peers: peers("nats-0"), consumers: [] },
        { name: "EDDI_EVENTS", role: "events", replicas: 3, messages: 1_934, bytes: 610_448, firstSequence: 1, lastSequence: 1_934, consumerCount: 2, leader: "nats-2", peers: peers("nats-2"), consumers: [{ name: "oc2", pending: 0, ackPending: 0, redelivered: 0 }, { name: "oc3", pending: 3, ackPending: 0, redelivered: 0 }] },
      ],
      buckets: [
        { bucket: "EDDI_LEASES", name: "LEASES", replicas: 3, values: 4, bytes: 1_204, ttlMillis: 20_000, leader: "nats-0", peers: peers("nats-0") },
        { bucket: "EDDI_NODES", name: "NODES", replicas: 3, values: 2, bytes: 1_490, ttlMillis: 30_000, leader: "nats-1", peers: peers("nats-1") },
        { bucket: "EDDI_NONCES", name: "NONCES", replicas: 3, values: 18, bytes: 2_160, ttlMillis: 600_000, leader: "nats-2", peers: peers("nats-2") },
      ],
      leaseEpoch: { firstRevision: 1_791_045_643_000_000, createdAt: now() - 86_400_000, lastRevision: 1_791_045_643_049_538 },
      account: { memoryBytes: 0, storageBytes: 812_334, streams: 14, consumers: 4 },
      error: null,
    },
    deadLetters: { shared: 3, local: 0 },
    hitlLeader: "eddi-1",
    settings: { messagingType: "nats", leaseTtlMs: 20_000, leaseHeartbeatMs: 5_000, leaseAcquireTimeoutMs: 45_000, presenceIntervalMs: 10_000 },
  };
}

function freshLeases(): LeaseView[] {
  const t = now();
  return [
    { key: "c.68b1f0c2d4e5a60012ab34cd", kind: "conversation", conversationId: "68b1f0c2d4e5a60012ab34cd", agentId: "68b1e9a0d4e5a60012ab0001", conversationState: "IN_PROGRESS", holderNode: "eddi-3", holderBoot: "c9d0e1f2", revision: 1_791_045_643_049_501, since: t - 52_000, ageMs: 52_000, renewedAt: t - 43_000, sinceRenewalMs: 43_000, holderStatus: "GONE", flags: ["HOLDER_GONE", "NOT_RENEWED", "LONG_RUNNING", "CONTENDED"], waitingNode: "eddi-2" },
    { key: "c.68b1f0c2d4e5a60012ab9911", kind: "conversation", conversationId: "68b1f0c2d4e5a60012ab9911", agentId: "68b1e9a0d4e5a60012ab0002", conversationState: "IN_PROGRESS", holderNode: "eddi-1", holderBoot: "a1b2c3d4", revision: 1_791_045_643_049_537, since: t - 3_000, ageMs: 3_000, renewedAt: t - 900, sinceRenewalMs: 900, holderStatus: "LIVE", flags: [], waitingNode: null },
    { key: "c.68b1f0c2d4e5a60012ab7722", kind: "conversation", conversationId: "68b1f0c2d4e5a60012ab7722", agentId: "68b1e9a0d4e5a60012ab0001", conversationState: "IN_PROGRESS", holderNode: "eddi-2", holderBoot: "e5f6a7b8", revision: 1_791_045_643_049_538, since: t - 1_200, ageMs: 1_200, renewedAt: t - 1_200, sinceRenewalMs: 1_200, holderStatus: "LIVE", flags: [], waitingNode: null },
    { key: "leader.hitl-recovery", kind: "leader", conversationId: null, agentId: null, conversationState: null, holderNode: "eddi-1", holderBoot: "a1b2c3d4", revision: 1_791_045_643_049_530, since: t - 3_000_000, ageMs: 3_000_000, renewedAt: t - 2_000, sinceRenewalMs: 2_000, holderStatus: "LIVE", flags: [], waitingNode: null },
  ];
}

function freshDeadLetters(): DeadLetterView[] {
  const t = now();
  const base = { agentVersion: 3, environment: "production", local: false, secretInput: false, replayable: true, notReplayableReason: null, fence: null };
  return [
    { ...base, id: "14", conversationId: "68b1f0c2d4e5a60012ab34cd", agentId: "68b1e9a0d4e5a60012ab0001", error: "Conversation '68b1f0c2d4e5a60012ab34cd' was taken over by another node — this turn's write (fencing token 1791045643049531) was refused because the conversation already carries token 1791045643049538; the message was not stored", timestamp: t - 600_000, reason: "fenced", nodeId: "eddi-3", fence: { token: 1_791_045_643_049_531, storedFence: 1_791_045_643_049_538 }, input: "Can you move my booking to Friday?" },
    { ...base, id: "15", conversationId: "68b1f0c2d4e5a60012ab5566", agentId: "68b1e9a0d4e5a60012ab0002", error: "Timed out waiting for the model after 60s", timestamp: t - 1_800_000, reason: "timeout", nodeId: "eddi-2", input: "Summarise last month's tickets" },
    { ...base, id: "17", conversationId: "68b1f0c2d4e5a60012ab5577", agentId: "68b1e9a0d4e5a60012ab0002", error: "LifecycleException: Cannot store property 'api_token' with scope 'secret'", timestamp: t - 3_600_000, reason: "failed", nodeId: "eddi-1", input: null, secretInput: true, replayable: false, notReplayableReason: "SECRET_INPUT" },
  ];
}

function freshActivity(): ActivityEvent[] {
  const t = now();
  return [
    { id: "a1", type: "node.joined", severity: "info", node: "eddi-1", ts: t - 3_600_000, payload: { nodeId: "eddi-1", boot: "a1b2c3d4" } },
    { id: "a2", type: "deployment.propagated", severity: "info", node: "eddi-2", ts: t - 900_000, payload: { originNode: "eddi-1", agentId: "68b1e9a0d4e5a60012ab0002", version: 3, environment: "production", status: "READY", latencyMs: 31 } },
    { id: "a3", type: "fence.rejected", severity: "warning", node: "eddi-3", ts: t - 600_000, payload: { entryId: "14", conversationId: "68b1f0c2d4e5a60012ab34cd", reason: "fenced", fence: { token: 1_791_045_643_049_531, storedFence: 1_791_045_643_049_538 } } },
    { id: "a4", type: "cache.invalidations", severity: "info", node: "eddi-2", ts: t - 120_000, payload: { counts: { agents: 4, secrets: 1 }, total: 5, windowSeconds: 60 } },
    { id: "node.lost:eddi-3:c9d0e1f2", type: "node.lost", severity: "error", node: "eddi-1", ts: t - 11_000, payload: { nodeId: "eddi-3", boot: "c9d0e1f2", lastHeartbeat: t - 41_000 } },
  ];
}

const state = {
  overview: freshOverview(),
  leases: freshLeases(),
  deadLetters: freshDeadLetters(),
  activity: freshActivity(),
};

export function resetClusterFixture() {
  state.overview = freshOverview();
  state.leases = freshLeases();
  state.deadLetters = freshDeadLetters();
  state.activity = freshActivity();
}

/** The single-node answer of an in-memory deployment. */
export const SINGLE_NODE_OVERVIEW: ClusterOverview = {
  mode: "single-node",
  verdict: "SINGLE_NODE",
  reasons: [],
  answeredBy: "local",
  generatedAt: Date.now(),
  degradedTurnsPolicy: "local",
  degradedSince: null,
  nodes: [
    { nodeId: "local", boot: null, host: null, version: "6.6.0", startedAt: 0, lastHeartbeat: Date.now(), heartbeatAgeMs: 0, state: "LIVE", self: true, degraded: false, draining: false, natsRtt: -1, activeConversations: 1, leasesHeld: 0, queueDepthTotal: 1, localDeadLetters: 0, hitlLeader: false, goneSince: null },
  ],
  nats: null,
  deadLetters: { shared: 0, local: 0 },
  hitlLeader: null,
  settings: { messagingType: "in-memory" },
};

/** NATS lost from the answering node: what every node says during an outage. */
export function degradedOverview(policy: "local" | "reject" = "local"): ClusterOverview {
  const o = freshOverview();
  return {
    ...o,
    verdict: "DEGRADED",
    reasons: ["NATS_UNREACHABLE"],
    degradedTurnsPolicy: policy,
    degradedSince: Date.now() - 95_000,
    nodes: o.nodes.slice(0, 1).map((n) => ({ ...n, degraded: true })),
    nats: { ...o.nats!, status: "DEGRADED", streams: [], buckets: [], knownServers: [], leaseEpoch: null, account: null, error: "NATS is not connected from this node" },
  };
}

export const clusterHandlers = [
  http.get("*/administration/cluster/overview", () => HttpResponse.json({ ...state.overview, generatedAt: Date.now() })),

  http.get("*/administration/cluster/leases", ({ request }) => {
    const url = new URL(request.url);
    const q = (url.searchParams.get("q") ?? "").toLowerCase();
    const flagged = url.searchParams.get("flagged") === "true";
    const leases = state.leases.filter(
      (l) => (!q || l.key.toLowerCase().includes(q) || l.holderNode.includes(q) || (l.agentId ?? "").includes(q)) && (!flagged || l.flags.length > 0),
    );
    return HttpResponse.json({ leases, total: leases.length, suspicious: state.leases.filter((l) => l.flags.length > 0).length, truncated: false });
  }),

  http.post("*/administration/cluster/leases/:conversationId/release", async ({ params, request }) => {
    const body = (await request.json().catch(() => ({}))) as { expectedRevision?: number | null };
    const lease = state.leases.find((l) => l.conversationId === params.conversationId);
    if (!lease) {
      return HttpResponse.json({ action: "lease.release", outcome: "ALREADY_RELEASED", message: "Nobody holds this conversation's lease; nothing was changed.", details: { currentRevision: 0 } });
    }
    if (body.expectedRevision != null && body.expectedRevision !== lease.revision) {
      return HttpResponse.json({ action: "lease.release", outcome: "RENEWED", message: "The holder renewed the lease after you looked — it is alive. Nothing was changed.", details: { currentRevision: lease.revision, holderNode: lease.holderNode } });
    }
    state.leases = state.leases.filter((l) => l !== lease);
    state.activity = [...state.activity, { id: `rel-${Date.now()}`, type: "admin.lease.release", severity: "warning", node: "eddi-1", ts: Date.now(), payload: { actor: "admin", conversationId: lease.conversationId, outcome: "RELEASED" } }];
    return HttpResponse.json({ action: "lease.release", outcome: "RELEASED", message: "Lease released. The next turn acquires a newer fencing token; a late write of the former holder is refused and dead-lettered.", details: { holderNode: lease.holderNode, currentRevision: lease.revision } });
  }),

  http.get("*/administration/cluster/activity", () => HttpResponse.json([...state.activity].sort((a, b) => a.ts - b.ts))),

  http.get("*/administration/cluster/activity/stream", () =>
    new HttpResponse("event: ping\ndata: 0\n\n", { status: 200, headers: { "Content-Type": "text/event-stream" } }),
  ),

  http.get("*/administration/cluster/diagnose/:conversationId", ({ params }) => {
    const id = String(params.conversationId);
    const lease = state.leases.find((l) => l.conversationId === id) ?? null;
    const dls = state.deadLetters.filter((d) => d.conversationId === id).map((d) => ({ ...d, input: null }));
    if (!lease && dls.length === 0 && !id.startsWith("68b1")) {
      return HttpResponse.json({ conversationId: id, verdict: "NOT_FOUND", exists: false, agentId: null, agentVersion: null, state: null, steps: 0, lease: null, queuedOn: {}, deadLetters: [], findings: [{ code: "NOT_FOUND", severity: "info", action: "NONE", details: {} }] });
    }
    const findings = [];
    if (lease?.flags.includes("HOLDER_GONE")) {
      findings.push({ code: "LEASE_ORPHANED", severity: "error", action: "FORCE_RELEASE", details: { holderNode: lease.holderNode, ageMs: lease.ageMs, sinceRenewalMs: lease.sinceRenewalMs, revision: lease.revision } });
      findings.push({ code: "TURN_WAITING_ELSEWHERE", severity: "info", action: "WAIT", details: { waitingNode: lease.waitingNode } });
    } else if (lease) {
      findings.push({ code: "TURN_RUNNING", severity: "info", action: "WAIT", details: { holderNode: lease.holderNode, ageMs: lease.ageMs } });
    }
    if (dls.length > 0) findings.push({ code: "DEAD_LETTERED", severity: "warning", action: "REPLAY", details: { count: dls.length, replayable: dls.filter((d) => d.replayable).length } });
    if (findings.length === 0) findings.push({ code: "IDLE", severity: "info", action: "NONE", details: {} });
    const verdict = findings.some((f) => f.severity === "error") ? "STUCK" : findings.some((f) => f.severity === "warning") ? "NEEDS_ATTENTION" : lease ? "BUSY" : "OK";
    return HttpResponse.json({ conversationId: id, verdict, exists: true, agentId: lease?.agentId ?? "68b1e9a0d4e5a60012ab0001", agentVersion: 3, state: lease ? "IN_PROGRESS" : "READY", steps: 12, lease, queuedOn: lease?.waitingNode ? { [lease.waitingNode]: 1 } : {}, deadLetters: dls, findings });
  }),

  http.get("*/administration/cluster/dead-letters/summary", () => {
    const byReason: Record<string, number> = {};
    const byNode: Record<string, number> = {};
    const byAgent: Record<string, number> = {};
    for (const d of state.deadLetters) {
      byReason[d.reason] = (byReason[d.reason] ?? 0) + 1;
      byNode[d.nodeId ?? "unknown"] = (byNode[d.nodeId ?? "unknown"] ?? 0) + 1;
      byAgent[d.agentId ?? "unknown"] = (byAgent[d.agentId ?? "unknown"] ?? 0) + 1;
    }
    return HttpResponse.json({ total: state.deadLetters.length, local: 0, byReason, byNode, byAgent, oldest: null, newest: null, scanned: state.deadLetters.length, truncated: false, recent: state.deadLetters.map((d) => ({ ...d, input: null })) });
  }),

  http.get("*/administration/cluster/dead-letters", ({ request }) => {
    const url = new URL(request.url);
    const reason = url.searchParams.get("reason");
    const nodeId = url.searchParams.get("nodeId");
    const conversationId = url.searchParams.get("conversationId");
    const entries = state.deadLetters.filter(
      (d) => (!reason || d.reason === reason) && (!nodeId || d.nodeId === nodeId) && (!conversationId || d.conversationId.includes(conversationId)),
    );
    return HttpResponse.json({ entries, nextCursor: null, scanned: state.deadLetters.length });
  }),

  http.post("*/administration/cluster/dead-letters/replay", async ({ request }) => {
    const { ids } = (await request.json()) as { ids: string[] };
    const results: ItemOutcome[] = ids.map((id) => {
      const entry = state.deadLetters.find((d) => d.id === id);
      if (!entry) return { id, outcome: "NOT_FOUND", message: "already replayed, discarded or expired" };
      if (!entry.replayable) return { id, outcome: "NOT_REPLAYABLE", message: entry.notReplayableReason };
      state.deadLetters = state.deadLetters.filter((d) => d.id !== id);
      return { id, outcome: "REPLAYED", message: null };
    });
    const ok = results.filter((r) => r.outcome === "REPLAYED").length;
    return HttpResponse.json({ results, succeeded: ok, failed: results.length - ok });
  }),

  http.post("*/administration/cluster/dead-letters/discard", async ({ request }) => {
    const { ids } = (await request.json()) as { ids: string[] };
    const results: ItemOutcome[] = ids.map((id) => {
      const before = state.deadLetters.length;
      state.deadLetters = state.deadLetters.filter((d) => d.id !== id);
      return before === state.deadLetters.length ? { id, outcome: "NOT_FOUND", message: null } : { id, outcome: "DISCARDED", message: null };
    });
    const ok = results.filter((r) => r.outcome === "DISCARDED").length;
    return HttpResponse.json({ results, succeeded: ok, failed: results.length - ok });
  }),

  http.post("*/administration/cluster/dead-letters/forward-local", () =>
    HttpResponse.json({ action: "deadletters.forward-local", outcome: "DONE", message: "0 dead letter(s) moved to the shared stream.", details: { total: 0 } }),
  ),
  http.post("*/administration/cluster/caches/resync", () =>
    HttpResponse.json({ action: "caches.resync", outcome: "DONE", message: "Every node flushes its invalidatable caches and reloads from the database.", details: {} }),
  ),
  http.post("*/administration/cluster/deployments/reconcile", () =>
    HttpResponse.json({ action: "deployments.reconcile", outcome: "DONE", message: "The deployment sweep ran on 2 node(s).", details: { nodes: ["eddi-1", "eddi-2"] } }),
  ),
  http.post("*/administration/cluster/nodes/:nodeId/:op", ({ params }) => {
    const drain = params.op === "drain";
    const live = state.overview.nodes.filter((n) => n.state === "LIVE" && !n.draining && n.nodeId !== params.nodeId);
    if (drain && live.length === 0) {
      return HttpResponse.json({ code: "LAST_NODE", message: `Refused: ${String(params.nodeId)} is the last node taking turns.` }, { status: 409 });
    }
    state.overview = { ...state.overview, nodes: state.overview.nodes.map((n) => (n.nodeId === params.nodeId ? { ...n, draining: drain } : n)) };
    return HttpResponse.json({ action: drain ? "node.drain" : "node.undrain", outcome: drain ? "DRAINED" : "UNDRAINED", message: drain ? "The node takes no new turns." : "The node takes turns again.", details: { nodeId: params.nodeId } });
  }),
];
