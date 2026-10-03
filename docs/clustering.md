# Clustering: horizontal scaling on NATS JetStream

One published image, selected at runtime:

- `eddi.messaging.type=in-memory` (the default) — a single node, no
  dependencies. Nothing described on this page is active; no NATS class is
  even instantiated.
- `eddi.messaging.type=nats` — any number of EDDI replicas behind a **plain
  round-robin load balancer** (no sticky sessions), sharing one database
  (MongoDB or PostgreSQL) and one NATS JetStream cluster.

Any other value fails the boot. Every property is in the
[Configuration Reference](configuration-reference.md#clustering-only-when-eddimessagingtypenats).

## Topology

```
            clients
               │
        ┌──────▼──────┐   round robin, no affinity
        │ load balancer│  (SSE: proxy_buffering off, read timeout ≥ 120 s)
        └──┬───┬───┬──┘
           │   │   │
      ┌────▼┐┌─▼──┐┌▼────┐
      │EDDI ││EDDI││EDDI │   N replicas, same image, eddi.cluster.node-id unique
      └─┬──┬┘└┬──┬┘└┬──┬─┘
        │  └──┼──┼──┘  │
        │  ┌──▼──▼──┐  │      NATS JetStream cluster (3 nodes, R3):
        │  │  NATS  │◄─┘      KV buckets, event stream, dead letters, RPC
        │  └────────┘
        ▼
   MongoDB / PostgreSQL       the only source of truth for conversations
```

Everything in NATS is coordination or a copy: losing the whole NATS cluster
loses no conversation, configuration or audit entry.

## What stays where

A turn **runs on the node that received the request** — SSE streaming, the
caller's identity, live group discussions and HITL keep working exactly as on
one node. NATS adds:

| Concern | Mechanism |
|---|---|
| Turns of one conversation never overlap anywhere | A conversation **lease** in the `<prefix>_LEASES` KV bucket (create = acquire, heartbeat every `lease.heartbeat-interval`, server-side TTL `lease.ttl`). Turns on one node stay FIFO. |
| A node that lost its lease cannot overwrite newer turns | The lease revision is a **fencing token**; both conversation stores refuse a write older than the `_fence` the document carries. The refused turn is dead-lettered with its input. |
| A crashed node does not block a conversation | Its lease expires (≤ `lease.ttl`), or is taken over at once when the node's presence record is gone or shows a new boot. |
| Caches stay coherent | Cluster events on the `<prefix>_EVENTS` stream: secrets, global variables, connections, deployments, deleted workflows, agent triggers, user conversations, conversation states, GDPR restriction verdicts. A node that missed events (gap, long disconnect, outbox overflow) flushes everything. |
| Undeploy reaches every node | A `deployment.changed` event (about a second), plus a two-way reconciliation sweep every 10 s that undeploys what the database no longer lists as deployed. |
| Cancel/end/GDPR stop reach the running turn | Node-addressed RPC to the lease holder; GDPR stop is a scatter to every node (the user travels as a hash). Group-discussion cancel is forwarded to the node holding the discussion's `g.<id>` lease. |
| Security and tool state is cluster-wide | KV buckets: replay nonces (`NONCES`), tool rate limits (`RATELIMIT`), per-conversation cost totals (`COSTS`), A2A task mappings (`A2A_*`), paginated tool responses (`TOOL_PAGES`), Slack event de-duplication (`DEDUP`), channel thread locks (`CHANNEL`), audit chain positions (`AUDIT_SEQ`). |
| Dead letters are shared | The `EDDI_DEAD_LETTERS` stream: every node lists, discards and replays the same entries. |
| HITL crash recovery does not hit live turns | No recovery at boot; a leader-elected sweep recovers only conversations without a lease that stayed unchanged for `hitl-recovery.min-age`. |

## Ordering contract

- While NATS is healthy, turns of one conversation never overlap anywhere in
  the cluster.
- Each turn runs on, and commits on top of, every turn committed before it
  (the turn reloads a superseded snapshot; its write carries the fence).
- Turns arriving at one node run in arrival order.
- Turns sent concurrently to different nodes are serialized in lease
  acquisition order — any order is a valid serialization; none is interleaved
  or lost.
- A client that waits for each response before sending the next gets strict
  program order.
- A turn that waited longer than `lease.acquire-timeout` for another node is
  answered **409 with `Retry-After: 2`**; its input was not consumed.

## Degraded mode (NATS unreachable)

A disconnection shorter than `eddi.cluster.degraded.grace` (5 s) is ridden
out. Longer, each area applies its policy:

| Area | Default | Alternative |
|---|---|---|
| Turns | run with node-local ordering only, **unfenced** (no loss — appends merge — but concurrent turns on different nodes may miss each other's context) | `degraded.turns=reject`: 409 + `Retry-After` |
| Replay nonces | **reject** (fail closed: signed envelopes are not accepted) | `degraded.nonces=local` |
| Tool rate limits | `local-share`: this node enforces the global limit ÷ known members, the per-conversation limit in full | `degraded.rate-limits=reject` |
| Cost budgets | local total | — |
| A2A mappings, tool pages, Slack dedup, thread locks | node-local copy | — |
| Audit chain positions | entries recorded unsequenced (verification: UNAVAILABLE, never BROKEN) | — |
| Cache events | outbox, flushed on reconnect; overflow ⇒ cluster-wide resync | — |
| Cancel / GDPR stop | this node + database CAS only | — |
| HITL recovery | paused | — |

Readiness stays UP while degraded — a NATS outage that took every replica out
of the load balancer at once would turn a coordination outage into a total
one. Set `eddi.cluster.readiness.require-nats=true` to change that. Liveness
never depends on NATS. Boot never waits for NATS either: a node started while
NATS is down serves in degraded mode and joins when NATS returns.

A node partitioned from NATS while the others are connected can run unfenced
turns concurrently with fenced turns elsewhere. Operators who cannot accept
that set `degraded.turns=reject`. Security state always fails closed.

## Failure modes

| Event | Effect |
|---|---|
| `kill -9` of an EDDI node mid-turn | Its clients see a reset. The conversation's lease expires within `lease.ttl` (or is taken over immediately after the pod restarts under the same name). The next turn runs normally; a late write from the dead node is refused by the fence. |
| One NATS node lost (R3) | Nothing visible: JetStream keeps quorum. |
| All NATS lost | Degraded mode as above; automatic recovery and resync when NATS returns. |
| Rolling update | Readiness goes DOWN first, the node drains (`eddi.shutdown.drain-timeout-seconds`), releases its leases and leaves presence; queued turns that never got a lease are answered 409 + `Retry-After` and retried by clients on another node. |
| Lease lost while a turn runs (long GC pause, partition) | The turn is cancelled at the next task boundary; if it still tries to write, the fence refuses it and the turn is dead-lettered. |

## Sizing

- **NATS**: three nodes, `eddi.nats.replicas=3`, file storage. Each turn costs
  about four small KV operations (create, delete, release notification,
  waiter marker on contention) plus one heartbeat per 5 s for long turns; an
  audit entry costs one compare-and-set. A NATS node with 1 vCPU and 1 GiB is
  ample for thousands of turns per second.
- **EDDI**: unchanged per-node sizing; add replicas for throughput. The
  database is the shared bottleneck.
- **Timings**: keep `lease.acquire-timeout` (45 s) below
  `systemRuntime.agentTimeoutInSeconds` (60 s) and above `lease.ttl` (20 s).

## Runbook

| Symptom | Look at | Action |
|---|---|---|
| `eddi_cluster_degraded == 1` | `/q/health/ready` (`cluster` check: `nats`, `degradedSince`), node log | Restore NATS reachability; the node recovers by itself. |
| Rising `eddi_cluster_lease_acquire_seconds_count{outcome="timeout"}` | `GET /administration/coordinator/status?scope=cluster` (queue depths per node) | A conversation is hammered from several clients at once, or turns are slower than the acquire timeout. |
| `eddi_cluster_fence_rejected_total` increases | Dead letters | Every refusal is a turn that was dead-lettered; replay or discard it. Frequent refusals mean nodes lose leases — check GC pauses and NATS latency (`natsRtt` in presence). |
| `eddi_coordinator_dead_letters > 0` | `GET /administration/coordinator/dead-letters` | Replay (`POST …/{id}/replay`) or discard (`DELETE …/{id}`) — see [Coordinator Admin](coordinator-admin.md). |
| An agent still answers after undeploy | Node log ("Undeployed agent … on this node") | It is undeployed within ~1 s (event) or 10–20 s (sweep). |

The JetStream objects are all named `<prefix>_<NAME>`, where the prefix is
`eddi.nats.prefix` (`EDDI` by default). KV buckets: `LEASES`, `NODES`, `NONCES`,
`RATELIMIT`, `COSTS`, `AUDIT_SEQ`, `A2A_*`, `TOOL_PAGES`, `DEDUP` and `CHANNEL`;
streams: `EVENTS` and `DEAD_LETTERS`. Subjects live
under `eddi.<prefix>.>`, so a NATS user restricted to `eddi.>`, `$JS.API.>`,
`$JS.ACK.>`, `$JS.FC.>`, `$KV.<prefix>_*.>` and `_INBOX.>` is sufficient — the Helm
chart's in-chart NATS grants exactly that.

### Upgrading from the build-profile NATS coordinator

The `nats` Maven build profile and its coordinator are gone; the published
image selects cluster mode at runtime. Its properties — `eddi.nats.stream-name`,
`eddi.nats.max-retries`, `eddi.nats.ack-wait-seconds`,
`eddi.nats.stream-max-age`, `eddi.nats.stream-max-messages`,
`eddi.nats.stream-max-bytes` — no longer do anything (a warning is logged once
if one is set), and the `EDDI_CONVERSATIONS` stream it created is unused and
can be deleted (`nats stream rm EDDI_CONVERSATIONS`). Its `eddi_nats_*`
meters no longer exist; see the [cluster metrics](metrics.md#cluster-metrics).

## Deployment

- **Helm** (chart 2.5.0+): `eddi.messagingType=nats` with `nats.enabled=true`
  (a three-node JetStream StatefulSet with authentication and optional TLS) or
  `nats.externalUrl`; then `eddi.replicas > 1`, `autoscaling.enabled` and a
  `RollingUpdate` are allowed, and the chart sets the drain, the termination
  grace, the node ids and a topology spread. See
  [Kubernetes](kubernetes.md#cluster-mode-horizontal-scaling).
- **Kustomize**: the `k8s/overlays/nats` component (three NATS nodes, three EDDI
  replicas), composed in `k8s/examples/postgres-ha`.
- **Docker Compose**: `docker-compose.cluster.yml` — three EDDI replicas, a
  three-node NATS cluster with a password, MongoDB and an nginx load balancer
  (`docker/cluster/nginx.conf`). `docker-compose.nats.yml` is a one-replica
  overlay for trying cluster mode next to the base file.
- **Alerts**: [`monitoring/eddi-cluster-alerts.yml`](monitoring/eddi-cluster-alerts.yml)
  holds Prometheus rules for the runbook above; the
  [cluster dashboard](monitoring/eddi-cluster-dashboard.json) charts the same series.
- **Demo**: [`scripts/cluster-demo/`](../scripts/cluster-demo/README.md) builds
  this topology in Docker — three nodes of one build, three NATS nodes, MongoDB
  or PostgreSQL, nginx and a mock LLM — and runs every failure scenario on this
  page against it, each with a pass/fail verdict.

## Residual limitations

- A Slack follow-up in the thread of a group discussion is routed only by the
  node that ran the discussion (the listener holds live state); on another
  node the reply is handled as an ordinary thread message.
- Paginated tool responses larger than the NATS payload limit (1 MiB by
  default) stay node-local; a page fetched through another node then fails as
  on a single node.
- An audit entry written on another node by work that the erasure had not yet
  stopped is pseudonymised only if the `gdpr.user-erased` event reached that
  node first.
- A turn whose write the fence refuses has usually already answered its
  caller: the reply is rendered inside the pipeline, before the write. The
  conversation history does not contain that turn and it is dead-lettered —
  replay it if the answer should count.
- A paginated tool response is not bound to its conversation: anyone who
  knows the random response id can fetch its pages from any node, exactly as on
  a single node (the tool has no conversation context to check against).
- On PostgreSQL, replicas that boot together run their startup one after
  another (an advisory lock around table creation and migrations), so the
  last of N replicas of a first install becomes ready later than the first.
