import type { TFunction } from "i18next";
import type {
  ActivityEvent,
  ClusterVerdict,
  DeadLetterView,
  Finding,
  ItemOutcomeCode,
  LeaseFlag,
  NodeState,
} from "@/lib/api/cluster";

/**
 * Every code the cluster API returns, in words an on-call admin can act on.
 *
 * Written as explicit `t("literal")` calls rather than `t(\`cluster.x.${code}\`)`
 * so `npm run i18n:check` can see each key: a dynamic key is invisible to it,
 * and an untranslated one would ship silently.
 */

// ---------------------------------------------------------------- verdict

export function verdictLabel(t: TFunction, verdict: ClusterVerdict): string {
  switch (verdict) {
    case "HEALTHY":
      return t("cluster.verdict.healthy", "Healthy");
    case "DEGRADED":
      return t("cluster.verdict.degraded", "Degraded");
    case "PARTITIONED":
      return t("cluster.verdict.partitioned", "Partitioned");
    case "SINGLE_NODE":
      return t("cluster.verdict.singleNode", "Single node");
  }
}

export function verdictExplanation(t: TFunction, verdict: ClusterVerdict, reasons: string[]): string {
  switch (verdict) {
    case "HEALTHY":
      return t(
        "cluster.verdict.healthyText",
        "Every node is heartbeating and connected to NATS. Turns of one conversation never overlap anywhere in the cluster, and every write is fenced.",
      );
    case "DEGRADED":
      if (reasons.includes("NATS_UNREACHABLE") || reasons.includes("NATS_RECONNECTING")) {
        return t(
          "cluster.verdict.degradedNatsText",
          "The node answering this page cannot reach NATS. It keeps serving, but without cluster coordination: no shared leases, no fence, no cluster-wide events.",
        );
      }
      return t(
        "cluster.verdict.degradedText",
        "NATS is reachable, but a node is missing, late or reports problems. Conversations its turns held are handed over within the lease TTL.",
      );
    case "PARTITIONED":
      return t(
        "cluster.verdict.partitionedText",
        "A NATS server holding a replica of a stream or bucket is offline: the NATS cluster itself is split. Writes continue while a quorum remains.",
      );
    case "SINGLE_NODE":
      return t(
        "cluster.verdict.singleNodeText",
        "This deployment runs one EDDI node with the in-memory coordinator. There is nothing to coordinate between nodes.",
      );
  }
}

export function verdictNextStep(t: TFunction, verdict: ClusterVerdict, reasons: string[]): string | null {
  if (verdict === "HEALTHY") {
    return reasons.includes("DEAD_LETTERS_WAITING")
      ? t("cluster.next.deadLetters", "Look at the waiting dead letters: replay the ones whose cause is fixed, discard the rest.")
      : null;
  }
  if (verdict === "SINGLE_NODE") {
    return t("cluster.next.singleNode", "To scale out, set eddi.messaging.type=nats and run more replicas — see the clustering guide.");
  }
  if (verdict === "PARTITIONED") {
    return t("cluster.next.partitioned", "Check the NATS servers listed as offline below; JetStream catches the replicas up once they return.");
  }
  if (reasons.includes("NATS_UNREACHABLE") || reasons.includes("NATS_RECONNECTING")) {
    return t(
      "cluster.next.nats",
      "Restore NATS reachability from this node (network, credentials, NATS pods). It reconnects and resyncs by itself.",
    );
  }
  if (reasons.includes("NODE_LOST")) {
    return t(
      "cluster.next.nodeLost",
      "Check why the lost node stopped (crash, OOM kill, network). Its leases expire within the lease TTL; a stuck one can be force-released.",
    );
  }
  return t("cluster.next.degraded", "Look at the nodes marked below and at the activity timeline for what changed.");
}

export function reasonLabel(t: TFunction, code: string): string {
  switch (code) {
    case "NATS_UNREACHABLE":
      return t("cluster.reason.natsUnreachable", "NATS unreachable from this node");
    case "NATS_RECONNECTING":
      return t("cluster.reason.natsReconnecting", "Reconnecting to NATS");
    case "NODE_LOST":
      return t("cluster.reason.nodeLost", "A node was lost");
    case "NODE_STALE":
      return t("cluster.reason.nodeStale", "A node's heartbeat is late");
    case "MEMBER_DEGRADED":
      return t("cluster.reason.memberDegraded", "A node reports degraded mode");
    case "NODE_DRAINING":
      return t("cluster.reason.nodeDraining", "A node is drained");
    case "NATS_PEER_OFFLINE":
      return t("cluster.reason.natsPeerOffline", "A NATS replica is offline");
    case "NATS_REPLICA_BEHIND":
      return t("cluster.reason.natsReplicaBehind", "A NATS replica is catching up");
    case "LOCAL_DEAD_LETTERS":
      return t("cluster.reason.localDeadLetters", "Dead letters kept on a node");
    case "DEAD_LETTERS_WAITING":
      return t("cluster.reason.deadLettersWaiting", "Dead letters waiting");
    default:
      return code;
  }
}

// ---------------------------------------------------------------- nodes

export function nodeStateLabel(t: TFunction, state: NodeState): string {
  switch (state) {
    case "LIVE":
      return t("cluster.node.live", "Live");
    case "STALE":
      return t("cluster.node.stale", "Heartbeat late");
    case "LOST":
      return t("cluster.node.lost", "Lost");
    case "LEFT":
      return t("cluster.node.left", "Left");
    case "UNKNOWN":
      return t("cluster.node.unknown", "Unknown");
  }
}

// ---------------------------------------------------------------- leases

export function leaseFlagLabel(t: TFunction, flag: LeaseFlag): string {
  switch (flag) {
    case "HOLDER_GONE":
      return t("cluster.leaseFlag.holderGone", "Holder gone");
    case "HOLDER_RESTARTED":
      return t("cluster.leaseFlag.holderRestarted", "Holder restarted");
    case "NOT_RENEWED":
      return t("cluster.leaseFlag.notRenewed", "Not renewed");
    case "LONG_RUNNING":
      return t("cluster.leaseFlag.longRunning", "Long-running");
    case "CONTENDED":
      return t("cluster.leaseFlag.contended", "Another node waits");
  }
}

export function leaseFlagHelp(t: TFunction, flag: LeaseFlag): string {
  switch (flag) {
    case "HOLDER_GONE":
      return t("cluster.leaseFlag.holderGoneHelp", "The holding node has no presence record — it died or lost NATS. The lease expires within the TTL.");
    case "HOLDER_RESTARTED":
      return t("cluster.leaseFlag.holderRestartedHelp", "The holding node came back with a new boot; it clears its old leases when it reconnects.");
    case "NOT_RENEWED":
      return t("cluster.leaseFlag.notRenewedHelp", "No heartbeat renewal for two intervals: the holder is hung, paused or cut off from NATS.");
    case "LONG_RUNNING":
      return t("cluster.leaseFlag.longRunningHelp", "Held longer than the acquire timeout: turns queued behind it are answered 409.");
    case "CONTENDED":
      return t("cluster.leaseFlag.contendedHelp", "A turn on another node is waiting for this lease.");
  }
}

// ---------------------------------------------------------------- dead letters

export function deadLetterReasonLabel(t: TFunction, reason: string): string {
  switch (reason) {
    case "fenced":
      return t("cluster.dlReason.fenced", "Fenced write");
    case "timeout":
      return t("cluster.dlReason.timeout", "Timeout");
    default:
      return t("cluster.dlReason.failed", "Failed");
  }
}

export function deadLetterReasonHelp(t: TFunction, entry: Pick<DeadLetterView, "reason" | "fence">): string {
  switch (entry.reason) {
    case "fenced":
      return t(
        "cluster.dlReason.fencedHelp",
        "This node lost the conversation's lease while the turn ran, and a newer turn committed meanwhile. The database refused this turn's write (token {{token}} < stored {{stored}}) so it could not overwrite the newer one. The reply may already have reached the user; the conversation history does not contain it.",
        { token: entry.fence?.token ?? "?", stored: entry.fence?.storedFence ?? "?" },
      );
    case "timeout":
      return t("cluster.dlReason.timeoutHelp", "The turn ran out of time. Check the agent's model or tool latency before replaying.");
    default:
      return t("cluster.dlReason.failedHelp", "The turn failed after it started. Fix the cause shown in the error before replaying.");
  }
}

export function notReplayableLabel(t: TFunction, code: string | null): string {
  switch (code) {
    case "SECRET_INPUT":
      return t("cluster.notReplayable.secret", "Not replayable: the client marked the input secret, so it was never stored.");
    case "INPUT_NOT_CAPTURED":
      return t("cluster.notReplayable.notCaptured", "Not replayable: input capture is switched off (eddi.coordinator.dead-letter.capture-input=false).");
    case "NOT_A_TURN":
      return t("cluster.notReplayable.notATurn", "Not replayable: this was not a user turn (a HITL resume or a group member's turn).");
    default:
      return t("cluster.notReplayable.unknown", "Not replayable.");
  }
}

export function outcomeLabel(t: TFunction, outcome: ItemOutcomeCode): string {
  switch (outcome) {
    case "REPLAYED":
      return t("cluster.outcome.replayed", "Replayed");
    case "DISCARDED":
      return t("cluster.outcome.discarded", "Discarded");
    case "NOT_FOUND":
      return t("cluster.outcome.notFound", "Already gone");
    case "NOT_REPLAYABLE":
      return t("cluster.outcome.notReplayable", "Not replayable");
    case "REJECTED":
      return t("cluster.outcome.rejected", "Rejected — kept");
    case "UNAVAILABLE":
      return t("cluster.outcome.unavailable", "NATS unreachable — kept");
    case "IN_PROGRESS":
      return t("cluster.outcome.inProgress", "Being replayed by someone else — not run twice");
  }
}

// ---------------------------------------------------------------- activity

export function activityTitle(t: TFunction, e: ActivityEvent): string {
  const p = e.payload ?? {};
  const s = (key: string) => String(p[key] ?? "?");
  switch (e.type) {
    case "node.joined":
      return t("cluster.activity.nodeJoined", "Node {{node}} joined", { node: s("nodeId") });
    case "node.left":
      return t("cluster.activity.nodeLeft", "Node {{node}} left cleanly", { node: s("nodeId") });
    case "node.lost":
      return t("cluster.activity.nodeLost", "Node {{node}} was lost (stopped heartbeating)", { node: s("nodeId") });
    case "node.stale":
      return t("cluster.activity.nodeStale", "Node {{node}} heartbeat is late", { node: s("nodeId") });
    case "degraded.on":
      return t("cluster.activity.degradedOn", "Node {{node}} lost NATS — degraded mode ({{policy}})", {
        node: s("nodeId"),
        policy: s("turnsPolicy"),
      });
    case "degraded.off":
      return t("cluster.activity.degradedOff", "Node {{node}} reconnected to NATS", { node: s("nodeId") });
    case "lease.takeover":
      return t("cluster.activity.leaseTakeover", "Lease of {{conversation}} taken over from {{from}}", {
        conversation: String(p["conversationId"] ?? p["key"] ?? "?"),
        from: s("previousNode"),
      });
    case "fence.rejected":
      return t("cluster.activity.fenceRejected", "Fenced write refused for {{conversation}} — dead-lettered", { conversation: s("conversationId") });
    case "deadletter.created":
      return t("cluster.activity.deadLetter", "Turn of {{conversation}} dead-lettered ({{reason}})", {
        conversation: s("conversationId"),
        reason: s("reason"),
      });
    case "deployment.propagated":
      return t("cluster.activity.deployment", "Agent {{agent}} v{{version}} {{status}} on {{origin}} — reached {{node}} after {{ms}} ms", {
        node: e.node,
        agent: s("agentId"),
        version: s("version"),
        status: s("status"),
        origin: s("originNode"),
        ms: s("latencyMs"),
      });
    case "cache.invalidations":
      return t("cluster.activity.caches", "{{n}} cache invalidations in the last minute", { n: s("total") });
    case "admin.lease.release":
      return t("cluster.activity.adminRelease", "{{actor}} force-released the lease of {{conversation}} ({{outcome}})", {
        actor: s("actor"),
        conversation: s("conversationId"),
        outcome: s("outcome"),
      });
    case "admin.caches.resync":
      return t("cluster.activity.adminResync", "{{actor}} resynced every cache", { actor: s("actor") });
    case "admin.deployments.reconcile":
      return t("cluster.activity.adminReconcile", "{{actor}} reconciled deployments", { actor: s("actor") });
    case "admin.deadletters.forward":
      return t("cluster.activity.adminForward", "{{actor}} forwarded {{n}} local dead letters", { actor: s("actor"), n: s("forwarded") });
    case "admin.deadletters.replay":
      return t("cluster.activity.adminReplay", "{{actor}} replayed dead letters ({{ok}} of {{n}})", {
        actor: s("actor"),
        ok: s("succeeded"),
        n: s("count"),
      });
    case "admin.deadletters.discard":
      return t("cluster.activity.adminDiscard", "{{actor}} discarded dead letters ({{ok}} of {{n}})", {
        actor: s("actor"),
        ok: s("succeeded"),
        n: s("count"),
      });
    case "admin.node.drain":
      return t("cluster.activity.adminDrain", "{{actor}} drained node {{node}}", { actor: s("actor"), node: s("nodeId") });
    case "admin.node.undrain":
      return t("cluster.activity.adminUndrain", "{{actor}} undrained node {{node}}", { actor: s("actor"), node: s("nodeId") });
    default:
      return e.type;
  }
}

/** The filter groups of the activity feed: label key → type prefixes. */
export const ACTIVITY_GROUPS = ["nodes", "leases", "deadLetters", "degraded", "deployments", "caches", "admin"] as const;
export type ActivityGroup = (typeof ACTIVITY_GROUPS)[number];

export function activityGroup(type: string): ActivityGroup {
  if (type.startsWith("node.")) return "nodes";
  if (type.startsWith("lease.")) return "leases";
  if (type.startsWith("fence.") || type.startsWith("deadletter.")) return "deadLetters";
  if (type.startsWith("degraded.")) return "degraded";
  if (type.startsWith("deployment.")) return "deployments";
  if (type.startsWith("cache.")) return "caches";
  return "admin";
}

export function activityGroupLabel(t: TFunction, group: ActivityGroup): string {
  switch (group) {
    case "nodes":
      return t("cluster.activityGroup.nodes", "Nodes");
    case "leases":
      return t("cluster.activityGroup.leases", "Leases");
    case "deadLetters":
      return t("cluster.activityGroup.deadLetters", "Dead letters");
    case "degraded":
      return t("cluster.activityGroup.degraded", "Degraded mode");
    case "deployments":
      return t("cluster.activityGroup.deployments", "Deployments");
    case "caches":
      return t("cluster.activityGroup.caches", "Caches");
    case "admin":
      return t("cluster.activityGroup.admin", "Admin actions");
  }
}

// ---------------------------------------------------------------- diagnosis

export function diagnosisVerdictLabel(t: TFunction, verdict: string): string {
  switch (verdict) {
    case "OK":
      return t("cluster.diagnose.ok", "Not stuck");
    case "BUSY":
      return t("cluster.diagnose.busy", "Busy — a turn is running");
    case "STUCK":
      return t("cluster.diagnose.stuck", "Stuck");
    case "NEEDS_ATTENTION":
      return t("cluster.diagnose.attention", "Needs attention");
    default:
      return t("cluster.diagnose.notFound", "Not found");
  }
}

export function findingText(t: TFunction, f: Finding): string {
  const d = f.details ?? {};
  const s = (key: string) => String(d[key] ?? "?");
  switch (f.code) {
    case "LEASE_ORPHANED":
      return t(
        "cluster.finding.orphaned",
        "The lease is held by {{node}}, which is gone or no longer renewing it (last renewal {{renewal}} ago). New turns wait until it expires.",
        { node: s("holderNode"), renewal: formatDuration(Number(d["sinceRenewalMs"] ?? 0)) },
      );
    case "TURN_LONG_RUNNING":
      return t("cluster.finding.longRunning", "A turn has been running on {{node}} for {{age}} — longer than the acquire timeout; turns queued behind it are answered 409.", {
        node: s("holderNode"),
        age: formatDuration(Number(d["ageMs"] ?? 0)),
      });
    case "TURN_RUNNING":
      return t("cluster.finding.running", "A turn is running on {{node}} ({{age}}); its lease is renewed normally.", {
        node: s("holderNode"),
        age: formatDuration(Number(d["ageMs"] ?? 0)),
      });
    case "TURN_WAITING_ELSEWHERE":
      return t("cluster.finding.waiting", "A turn on {{node}} is waiting for this lease.", { node: s("waitingNode") });
    case "TURNS_QUEUED":
      return t("cluster.finding.queued", "Turns are queued for it: {{queues}}.", {
        queues: Object.entries(d)
          .map(([node, n]) => `${node} × ${String(n)}`)
          .join(", "),
      });
    case "AWAITING_HUMAN":
      return t("cluster.finding.awaitingHuman", "It is paused for a human approval — nothing runs until someone decides.");
    case "IN_PROGRESS_WITHOUT_TURN":
      return t(
        "cluster.finding.inProgress",
        "It is marked in progress, but no turn holds its lease or is queued — a node probably died mid-turn. The HITL recovery sweep resets it after its minimum age.",
      );
    case "STATE_ERROR":
      return t("cluster.finding.error", "Its last turn ended in an error. The next message runs normally.");
    case "DEAD_LETTERED":
      return t("cluster.finding.deadLettered", "{{n}} of its turns were dead-lettered ({{replayable}} replayable).", {
        n: s("count"),
        replayable: s("replayable"),
      });
    case "NATS_UNREACHABLE":
      return t("cluster.finding.nats", "NATS is unreachable from this node, so its lease cannot be read.");
    case "NOT_FOUND":
      return t("cluster.finding.notFound", "No conversation, lease or dead letter with this id.");
    case "IDLE":
      return t("cluster.finding.idle", "No turn is running or queued. The conversation is idle and takes the next message at once.");
    default:
      return f.code;
  }
}

export function findingActionLabel(t: TFunction, action: Finding["action"]): string | null {
  switch (action) {
    case "WAIT":
      return t("cluster.findingAction.wait", "Wait — it resolves by itself");
    case "FORCE_RELEASE":
      return t("cluster.findingAction.release", "Force-release the lease");
    case "REPLAY":
      return t("cluster.findingAction.replay", "Replay or discard its dead letters");
    case "OPEN_APPROVALS":
      return t("cluster.findingAction.approvals", "Open the approvals queue");
    case "CANCEL":
      return t("cluster.findingAction.cancel", "Cancel the running turn from the conversation page");
    default:
      return null;
  }
}

// ---------------------------------------------------------------- formatting

/** 950 ms → "950 ms", 65 s → "1 min 5 s", 2 h → "2 h 0 min". Locale-neutral units. */
export function formatDuration(ms: number): string {
  if (!Number.isFinite(ms) || ms < 0) return "—";
  if (ms < 1000) return `${Math.round(ms)} ms`;
  const s = Math.floor(ms / 1000);
  if (s < 60) return `${s} s`;
  const m = Math.floor(s / 60);
  if (m < 60) return `${m} min ${s % 60} s`;
  const h = Math.floor(m / 60);
  if (h < 48) return `${h} h ${m % 60} min`;
  return `${Math.floor(h / 24)} d ${h % 24} h`;
}

export function formatBytes(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes < 0) return "—";
  if (bytes < 1024) return `${bytes} B`;
  const units = ["KiB", "MiB", "GiB", "TiB"];
  let value = bytes / 1024;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit++;
  }
  return `${value.toFixed(value < 10 ? 1 : 0)} ${units[unit]}`;
}
