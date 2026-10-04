# Coordinator & Dead Letters

The conversation coordinator serialises work per conversation. A turn that fails is
**dead-lettered**: kept with its error and a description of the turn rather than dropped, so an
operator can look at it and decide whether to replay or discard it.

The counter `eddi_coordinator_total_dead_lettered` (exposed as
`eddi_coordinator_total_dead_lettered_total`) tells you it happened, and the gauge
`eddi_coordinator_dead_letters` how many entries are waiting — see
[Metrics & Monitoring](metrics.md). This page is the API that lets you do something about it.

All operations sit under `/administration/coordinator` and require the `eddi-admin` role. They
behave the same with both coordinators; in cluster mode (`eddi.messaging.type=nats`, see
[Clustering](clustering.md)) every node answers for the whole cluster's dead letters.

## Status

```http
GET /administration/coordinator/status
GET /administration/coordinator/status?scope=cluster
```

```json
{
  "coordinatorType": "nats",
  "connected": true,
  "connectionStatus": "CONNECTED",
  "activeConversations": 12,
  "totalProcessed": 48213,
  "totalDeadLettered": 3,
  "queueDepths": { "68b1f0c2d4e5a60012ab34cd": 1 },
  "nodeId": "eddi-7c9f8d6b5-x2k4q",
  "cluster": {
    "members": [ { "nodeId": "eddi-7c9f8d6b5-x2k4q", "version": "6.6.0", "natsRtt": 1 } ],
    "leasesHeld": 1,
    "natsStatus": "CONNECTED",
    "degraded": false
  }
}
```

`coordinatorType` is `in-memory` or `nats`, following `eddi.messaging.type`. The numbers and
`queueDepths` are those of the node that answered. In cluster mode `nodeId` names that node and
`cluster` adds the members the node can see, the leases it holds, its NATS connection and whether
it is running degraded (`degradedSince` when it is); `?scope=cluster` also asks every member for
its queue depths. In-memory mode leaves `nodeId` and `cluster` out, and `connected` is always true
there — there is nothing to connect to.

## Listing dead letters

```http
GET /administration/coordinator/dead-letters?limit=100&after=<id>
```

```json
[
  {
    "id": "17",
    "conversationId": "68b1f0c2d4e5a60012ab34cd",
    "error": "LifecycleException: Cannot store property 'api_token' with scope 'secret'...",
    "timestamp": 1757251920000,
    "payload": "...",
    "reason": "failed",
    "nodeId": "eddi-7c9f8d6b5-x2k4q",
    "fence": null,
    "turn": {
      "conversationId": "68b1f0c2d4e5a60012ab34cd",
      "agentId": "68b1e9a0d4e5a60012ab0001",
      "agentVersion": 3,
      "environment": "production",
      "userId": "user-42",
      "rerun": false,
      "input": "my key is ..."
    }
  }
]
```

Oldest first. `limit` caps a page (default `100`); pass the last `id` of a page as `after` to read
the next one.

Retention: in-memory, `eddi.coordinator.max-dead-letters` (default `1000`; `-1` unbounded, `0`
retains none), oldest evicted first. In cluster mode the entries live in the JetStream stream
`<prefix>_DEAD_LETTERS`, shared by every node and kept for `eddi.coordinator.dead-letter.max-age`
(default `7d`); while NATS is unreachable a node keeps its new entries locally (ids `local-…`) under
the same in-memory cap. GDPR erasure removes a user's entries.

> `turn.input` (and `turn.context`) is what the user sent. It can contain material they would not
> expect an administrator to read, so treat this endpoint as carrying conversation content. Set
> `eddi.coordinator.dead-letter.capture-input=false` to keep the input out of dead letters — such
> entries can then only be discarded, not replayed.

## Replaying one entry

```http
POST /administration/coordinator/dead-letters/{entryId}/replay
```

Submits the captured input as a **new turn** of the same conversation, as the calling admin, with
the context entry `replayOf=<entryId>`, and removes the entry once the turn was accepted. The
failed task itself is never re-run.

| Status | Meaning |
|---|---|
| `204` | Replay submitted; the entry is gone. |
| `404` | No such entry — discarded, purged or expired. |
| `409` | Not replayable (no captured input: a HITL resume, a group member's turn, or capture switched off), or the conversation cannot take a turn right now. The entry is kept. |

Replaying re-runs the turn's side effects, so replay a failure whose cause you have actually fixed,
not one you are still diagnosing.

## Discarding

```http
DELETE /administration/coordinator/dead-letters/{entryId}     → 204, or 404
DELETE /administration/coordinator/dead-letters               → 200, count purged
```

Both are permanent, and in cluster mode act on the shared stream, so every node sees the result.

## Live status

```http
GET /administration/coordinator/stream        (text/event-stream)
```

Emits a `status` event — the same object as `GET /status` — when you connect and every 2 seconds.

## The cluster console

The Manager's **Cluster** screen (`/manage/coordinator`) is built on the endpoints below, under
`/administration/cluster`. They work in both modes; on a single node the overview answers
`SINGLE_NODE` and the cluster-only actions answer `409 NOT_CLUSTERED`.

**Roles.** Operational metadata — ids, counts, states and timings — is readable by `eddi-admin`
and the read-only `eddi-viewer` (EDDI has no role hierarchy, so both are named). The dead-letter
listing, which carries the captured input, and every action are `eddi-admin` only.

| Endpoint | Roles | What it answers |
|---|---|---|
| `GET /administration/cluster/overview` | admin, viewer | The health verdict, one card per node, NATS and JetStream, dead-letter counts |
| `GET /administration/cluster/leases?q=&flagged=&limit=` | admin, viewer | Every lease, suspicious first |
| `GET /administration/cluster/activity?limit=&type=` | admin, viewer | The latest activity entries (at most 500) |
| `GET /administration/cluster/activity/stream` | admin, viewer | The same, live (SSE) |
| `GET /administration/cluster/diagnose/{conversationId}` | admin, viewer | "Why is this conversation stuck?" |
| `GET /administration/cluster/dead-letters/summary` | admin, viewer | Counts by reason, node and agent — no content |
| `GET /administration/cluster/dead-letters?reason=&nodeId=&agentId=&conversationId=&from=&to=&after=&limit=` | admin | A filtered page with the captured input |
| `POST /administration/cluster/dead-letters/replay` `{"ids": [...]}` | admin | Replays up to 100 entries, one outcome per id |
| `POST /administration/cluster/dead-letters/discard` `{"ids": [...]}` | admin | Discards up to 100 entries, one outcome per id |
| `POST /administration/cluster/dead-letters/forward-local` | admin | Moves dead letters kept on nodes while NATS was down into the shared stream |
| `POST /administration/cluster/leases/{conversationId}/release` `{"expectedRevision": n}` | admin | Force-releases a conversation lease |
| `POST /administration/cluster/caches/resync` | admin | Every node flushes its invalidatable caches |
| `POST /administration/cluster/deployments/reconcile` | admin | Runs the deployment sweep on every node now |
| `POST /administration/cluster/nodes/{nodeId}/drain` and `/undrain` | admin | Stops (or resumes) a node taking turns |

A refused action answers `409` with `{"code": …, "message": …}` — `NOT_CLUSTERED`, `LAST_NODE`,
`NODE_UNREACHABLE` or `NATS_UNREACHABLE` (also what a read of the leases answers while the node
is cut off from NATS). Never a `503`: the shipped nginx configuration retries the next node on
`503`, so a `503` from every node during a NATS outage would take the whole API out of the load
balancer for its fail timeout.

An action that ran answers `200` with an `outcome` saying how far it got: `DONE` (it reached
every node), `QUEUED` (a cache resync while this node is cut off from NATS: this node flushed,
and the request to the others waits in the outbox until it reconnects) or `PARTIAL` (a reconcile
or forward that some nodes did not answer, named in `details.missing`, or a forward that left
entries kept locally, counted in `details.remaining`) or `STARTED` (a reconcile that every node
answered, but whose sweep was still running on some — named in `details.running` — when the
answer was due; it finishes on its own). The console shows anything but `DONE` as
a warning, and the audit entry records the same outcome.

### The health verdict

| Verdict | Means | Typical reason codes |
|---|---|---|
| `HEALTHY` | NATS reachable, every node heartbeating | — (`DEAD_LETTERS_WAITING`, `NODE_DRAINING` may still be listed: they need attention, not repair) |
| `DEGRADED` | The answering node cannot reach NATS — degraded mode, see [Clustering](clustering.md#degraded-mode-nats-unreachable) — or a node was lost, is late or reports itself degraded | `NATS_UNREACHABLE`, `NATS_RECONNECTING`, `NODE_LOST`, `NODE_STALE`, `MEMBER_DEGRADED`, `NATS_REPLICA_BEHIND`, `LOCAL_DEAD_LETTERS` |
| `PARTITIONED` | NATS is reachable, but a JetStream replica of a stream or bucket is offline: the NATS cluster itself is split. A NATS server restarted on purpose shows the same way until it is back — the verdict cannot tell a planned restart from an outage | `NATS_PEER_OFFLINE` |
| `SINGLE_NODE` | `eddi.messaging.type=in-memory` | — |

The answer is the view of the node that served the request. Through a load balancer the next
request can reach another node — which is why a node cut off from NATS answers `DEGRADED`
about itself rather than guessing about the others. A node card is `LIVE`, `STALE` (heartbeat
more than two presence intervals old), `LOST` (its record vanished without the leave marker a
clean shutdown writes: killed, crashed or
partitioned from NATS) or `LEFT` (it shut down cleanly); a lost or departed node stays on the
screen for 15 minutes.

### Leases

Each lease shows its holder, age, revision (the fencing token), its last renewal (the server's
write time of the current revision) and these flags:

| Flag | Means |
|---|---|
| `HOLDER_GONE` | The holder has no presence record — it died or is partitioned; the lease expires within `eddi.cluster.lease.ttl` |
| `HOLDER_RESTARTED` | The holder's node is back with a new boot; its next connect sweeps the lease |
| `NOT_RENEWED` | No renewal for two heartbeat intervals — the holder is hung, paused or cut off (its lease expires at the TTL) |
| `LONG_RUNNING` | Held longer than `eddi.cluster.lease.acquire-timeout`: turns queued behind it are being answered `409` |
| `CONTENDED` | Another node is waiting for it |

### Dead letters in the console

Every entry now records **why** it was dead-lettered (`reason`: `fenced`, `lease-lost` (the
node lost the lease while the turn ran, so the turn was stopped before it was stored), `timeout`
or `failed`; the console shows any other reason by its code), the node it failed on (`nodeId`) and, for a fenced write, both tokens (`fence`:
`token` — what the refused write carried — and `storedFence`, what the conversation already
had). An entry that cannot be replayed says why (`notReplayableReason`): `SECRET_INPUT` (the
client flagged the turn `secretInput`, so its input was never stored), `INPUT_NOT_CAPTURED`
(`eddi.coordinator.dead-letter.capture-input=false`) or `NOT_A_TURN` (a HITL resume or a group
member's turn). A replay claims its entry first (`replay.<id>` in the `<prefix>_ADMIN` KV bucket,
TTL 60 s), so two administrators replaying one entry run its turn once; the other gets
`IN_PROGRESS`. Bulk replay and discard report one outcome per id — `REPLAYED`, `DISCARDED`, `IN_PROGRESS`,
`NOT_FOUND`, `NOT_REPLAYABLE`, `REJECTED` (the conversation would not take the turn; the entry
is kept) or `UNAVAILABLE` (NATS unreachable).

### Activity

A cluster-wide timeline, kept in the JetStream stream `<prefix>_ACTIVITY` for
`eddi.cluster.activity.max-age` (24 h, at most 10,000 entries) and read back by every node, so
whichever node answers shows the same history. Entry types: `node.joined`, `node.left`,
`node.lost`, `node.stale`, `degraded.on`/`degraded.off`, `lease.takeover`, `fence.rejected`,
`deadletter.created`, `deployment.propagated` (one per receiving node, with the delay),
`cache.invalidations` (counts per cache, once a minute) and `admin.*` for every recovery action.
Payloads carry ids, counts and reasons only; a read-only caller sees `an administrator` in place
of the acting admin's name. The SSE stream keeps at most 12 of its 16 per-node slots for
read-only callers (the rest stay free for administrators), closes with an `expired` event when the
caller's access token expires, and sends an `activity` event per entry
and a `ping` every 15 seconds, and admits 16 subscribers per node (the 17th gets one `busy`
event).

### Recovery actions

Each action is idempotent, recorded in the audit ledger under the trail `cluster-admin`
(`GET /auditstore/cluster-admin`, with the admin's name as `userId`) and on the activity
timeline, and counted on `eddi_cluster_admin_actions_total{action,outcome}`.

| Action | Why it is safe |
|---|---|
| Force-release a lease | A compare-and-set delete at the revision found — or refused as `RENEWED` when `expectedRevision` no longer matches, which means the holder is alive and renewing. The next holder's lease has a higher revision, which it raises on the conversation before its turn runs, so a still-alive former holder's late write is **refused by the fence and dead-lettered**; its next heartbeat finds the lease gone and cancels its turn at the next task boundary. A lease nobody holds answers `ALREADY_RELEASED`. |
| Resync caches | The flush a node already does by itself after missing events; caches reload from the database. Costs a short burst of reads, changes no data. |
| Reconcile deployments | Runs the 10-second deployment sweep now on every node. |
| Drain / undrain a node | The node takes no new leases — turns that reach it are answered `409` with `Retry-After` and retried elsewhere — and its readiness check reports `DOWN` (`draining: true`), so a Kubernetes Service stops routing to it; running turns finish. Refused (`409 LAST_NODE`) for the last node still taking turns — decided under a cluster-wide `gate.drain` (a concurrent decision answers `409 BUSY`) and counted from the `drain.<node>` keys every drained node keeps in the `<prefix>_ADMIN` bucket, not from presence. Lasts until undrain or restart. |
| Forward local dead letters | Each node appends the entries it kept while NATS was down to the shared stream, then removes its copy; serialised per node, so two runs never forward an entry twice. Refused while the answering node has no NATS. |

Not offered, on purpose: deleting a stream or bucket, purging every dead letter from the console
(the `DELETE …/dead-letters` endpoint above still exists for scripts), lowering a fence, or
handing a lease to a chosen node — each could lose data or bypass fencing.

## See also

- [Configuration Reference](configuration-reference.md) — `eddi.coordinator.*`, `eddi.cluster.*`
- [Clustering](clustering.md) — when a turn is dead-lettered in cluster mode: a fenced write (`reason: fenced`) or a turn stopped by a lost lease (`reason: lease-lost`)
- [Metrics & Monitoring](metrics.md) — the coordinator gauges and the dead-letter alert
