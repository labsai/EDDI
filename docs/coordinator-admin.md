# Coordinator & Dead Letters

The conversation coordinator serialises work per conversation. A turn that fails past its retries
is **dead-lettered**: kept with its payload and error rather than dropped, so an operator can look
at it and decide whether to replay or discard it.

The meter `eddi_nats_dead_letter_count` tells you it happened. Micrometer's Prometheus
exposition appends `_total` to a counter, so the series you query is
`eddi_nats_dead_letter_count_total` — see [Metrics & Monitoring](metrics.md). This page is the
API that lets you do something about it.

All operations sit under `/administration/coordinator` and require the `eddi-admin` role.

## Status

```http
GET /administration/coordinator/status
```

```json
{
  "coordinatorType": "in-memory",
  "connected": true,
  "connectionStatus": "CONNECTED",
  "activeConversations": 12,
  "totalProcessed": 48213,
  "totalDeadLettered": 3,
  "queueDepths": { "default": 0 }
}
```

`coordinatorType` is `in-memory` or `nats`, following `eddi.messaging.type`. For the in-memory
coordinator `connected` is always true — there is nothing to connect to — so read it as meaningful
only under NATS.

## Listing dead letters

```http
GET /administration/coordinator/dead-letters
```

```json
[
  {
    "id": "dl-9f3c1a",
    "conversationId": "68b1f0c2d4e5a60012ab34cd",
    "error": "LifecycleException: Cannot store property 'api_token' with scope 'secret'...",
    "timestamp": 1757251920000,
    "payload": "{\"input\":\"my key is ...\"}"
  }
]
```

Retention is bounded by `eddi.coordinator.max-dead-letters` (default `1000`; `-1` unbounded, `0`
retains none). The oldest entries are evicted first, so a flood of failures can push an older
entry out before anyone looks at it.

> The `payload` is the turn's input. It can contain whatever the user typed, including material
> they would not expect an administrator to read. Treat this endpoint as carrying conversation
> content, not just diagnostics.

## Replaying one entry

```http
POST /administration/coordinator/dead-letters/{entryId}/replay
```

Re-injects the entry into the processing pipeline. Answers `200` on success and `404` if the entry
is gone — which it will be if retention evicted it in the meantime. Replaying re-runs the turn's
side effects, so replay a failure whose cause you have actually fixed, not one you are still
diagnosing.

## Discarding

```http
DELETE /administration/coordinator/dead-letters/{entryId}     → 204, or 404
DELETE /administration/coordinator/dead-letters               → 200, count purged
```

Both are permanent. The bulk form returns the number of entries it removed.

## Live tail

```http
GET /administration/coordinator/stream        (text/event-stream)
```

Server-sent events for `task_submitted`, `task_completed`, `task_failed` and
`task_dead_lettered`. Useful while reproducing a failure; it is a tail, not a backlog, so it shows
only what happens after you connect.

## See also

- [Configuration Reference](configuration-reference.md) — `eddi.coordinator.*`
- [Metrics & Monitoring](metrics.md) — the coordinator gauges and the dead-letter alert
