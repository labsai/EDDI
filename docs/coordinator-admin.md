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

## See also

- [Configuration Reference](configuration-reference.md) — `eddi.coordinator.*`, `eddi.cluster.*`
- [Clustering](clustering.md) — when a turn is dead-lettered in cluster mode: a fenced write (`reason: fenced`) or a turn stopped by a lost lease (`reason: lease-lost`)
- [Metrics & Monitoring](metrics.md) — the coordinator gauges and the dead-letter alert
