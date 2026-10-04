## Cluster console — health, leases, dead letters, activity and safe recovery actions (2026-10-04)

**Repo:** EDDI · **Branch:** `feat/nats-cluster-console` (part 4 of the NATS stack, into `feat/nats-cluster-deploy`)

The Manager's coordinator screen targeted the single-node API and ignored every cluster datum.
It is now a cluster console built for an on-call admin: is the cluster healthy, which node is
doing what, what went wrong, and what can safely be done about it.

### Backend — `/administration/cluster` (`IRestClusterAdmin`, `ClusterAdminService`)

- **Overview**: a verdict (`HEALTHY`, `DEGRADED`, `PARTITIONED`, `SINGLE_NODE`) with reason
  codes; one card per node — live, `STALE`, `LOST` (expired without leaving) or `LEFT` (clean
  shutdown), kept for 15 minutes by `ClusterWatcher`; host, version, uptime, heartbeat age, NATS
  RTT, running turns, leases, queue, locally kept dead letters, drained, HITL leader; NATS and
  JetStream as the answering node sees them (server, known servers, every stream and KV bucket of
  the deployment with replicas, peer state and consumer lag, the leases-bucket generation).
- **Leases**: one KV watch lists every lease with holder, age, revision (fencing token), the
  server's renewal time and flags (`HOLDER_GONE`, `HOLDER_RESTARTED`, `NOT_RENEWED`,
  `LONG_RUNNING`, `CONTENDED`), suspicious first, agent and state read in one query.
- **Dead letters** now record `reason` (`fenced`, `timeout`, `failed`), `nodeId` and, for a
  fenced write, both tokens; `notReplayableReason` says why one cannot be replayed (`SECRET_INPUT`,
  `INPUT_NOT_CAPTURED`, `NOT_A_TURN`). Filtered, cursor-paged listing (scan bounded at 2,000 per
  call), a content-free summary, and bulk replay/discard (≤ 100 ids) with one outcome per id.
- **Activity timeline**: the new `<prefix>_ACTIVITY` JetStream stream
  (`eddi.cluster.activity.max-age`, 24 h, ≤ 10,000 entries) read back by every node, so any node
  answers with the same history; deterministic ids collapse the same observation from several
  nodes. Node joined/left/lost/stale, degraded on/off, lease takeovers, fenced writes, dead
  letters, deployment propagation per node with its delay, cache invalidations per minute, every
  admin action. SSE stream with a 15 s ping, at most 16 subscribers per node.
- **Diagnosis** of one conversation: lease and holder, turns queued per node (RPC scatter),
  state, dead letters, findings with a suggested action.
- **Recovery actions** (admin only, audited in the `cluster-admin` audit trail, recorded on the
  timeline, counted on `eddi_cluster_admin_actions_total`): force-release a lease (CAS delete,
  refused as `RENEWED` when the holder renewed since the admin looked; safe because of the fence),
  resync caches cluster-wide, reconcile deployments on every node, drain/undrain a node (no new
  leases, 409 + `Retry-After`, readiness DOWN; refused for the last undrained node), forward
  locally kept dead letters (now serialised per node).
- **Roles**: overview, leases, activity, diagnosis and the dead-letter summary are
  `eddi-admin` + `eddi-viewer`; the dead-letter listing (captured input) and every action are
  `eddi-admin`.

### Manager

`/manage/coordinator` is the **Cluster** screen: tabs Overview, Leases, Dead letters, Activity,
Stuck conversation (deep-linkable with `?tab=` and `?conversation=`). Verdict card with the
degraded-mode banner explaining the `local`/`reject` policy, node cards with drain/undrain,
NATS & JetStream panel, recovery actions with consequence-stating confirmations, the audit trail
of those actions, a filterable live activity feed that buffers while paused and backfills after
a reconnect, a dead-letter table with filters, selection, bulk actions with per-item results and
a focus-trapped detail drawer (fence tokens, masked secret input, "not replayable" with the
reason). Single-node mode explains what cluster mode adds. `eddi-viewer` sees insights only; other
roles see why the screen is closed. All strings in the 11 locales.

**Platform Operator** (revision 3): reads overview, leases, activity, diagnosis and the
dead-letter summary; a new prompt section explains verdicts, degraded policies and orphaned
leases. The dead-letter listing (user input) and every recovery action stay off the allow-list.

### Found on the live 3-node cluster, fixed on this branch

- A refused action answered `503` while NATS was down; the shipped nginx configuration retries
  the next node on `503`, so every node got marked down and the whole API (turns included)
  answered `502` for the fail timeout. Refusals are `409` with a code now.
- After a NATS outage the watcher reported every node — itself included — as lost, because their
  presence records had expired meanwhile. It now waits two presence intervals after a reconnect
  and never reports itself.
- Activity entries recorded during an outage stayed in the outbox when the first publish after
  the reconnect hit a stream still electing its leader; the outbox is now retried every 5 s.
- Without NATS the other nodes were shown "heartbeat late"; they are `UNKNOWN` now, and this
  node's card uses its live numbers (it hid the dead letters kept locally).
- A paused node behind the load balancer froze the console's numbers (its requests hung for
  the proxy's read timeout); console reads fail after 8 s and show the stale-data notice.
- `NOT_RENEWED` now flags two missed heartbeats instead of three — with a 20 s TTL and a 5 s
  heartbeat, three left almost no window before the lease expired by itself.
- A restarted node's sweep of its previous boot's leases now shows up as a takeover.

### Decisions

```decision-log
| 2026-10-04 | Cluster console: read-only insights for eddi-viewer, content and actions for eddi-admin | Ids, counts and timings help a read-only on-call role; a dead letter's input is what a user typed, and every action changes the live cluster |
| 2026-10-04 | No purge-all, no stream/bucket delete, no lease hand-over in the console | Each could lose data or bypass fencing; per-item discard and the scripted DELETE endpoint remain |
| 2026-10-04 | Activity timeline in its own JetStream stream rather than node-local rings | Behind a round-robin load balancer a node-local timeline changes on every refresh |
| 2026-10-04 | Operator gets cluster reads but no recovery action | Actions act on the live cluster, not on a reviewable document; not in operator-write-scope-plan's write set |
```
