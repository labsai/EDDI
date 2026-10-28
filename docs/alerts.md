# Alerts

Alert rules tell the people running an agent when something needs them: a low
rating, a comment, a turn that failed. A rule names a **source**, a **condition**,
the agents it watches and where to deliver; every alert lands in the Manager's
**Alerts inbox**, and optionally in Slack and at a signed webhook.

Alerts are evaluated **outside the pipeline, after the fact** — a turn is never
slowed or failed by alerting, and an agent needs no wiring of its own. (The
pipeline route — `property.json` + `behavior.json` + an `httpcalls.json` Slack
call — stays valid, but it runs inside the turn, has no throttling, digest, retry
or inbox, and cannot report the turn's own failure.)

## Design note

**Where the hooks sit.**

- *Ratings*: `RestConversationRating` fires a CDI event (`ConversationRatingSaved`)
  after the rating is stored.
- *Turn outcomes*: `ConversationStepRunner.waitForExecutionFinishOrTimeout` — the one
  method every turn passes through when its outcome is final. The say path
  (`runGuardedConversationStep`) and the HITL resume path
  (`ConversationHitlService`) both wait there, it is where the watchdog decides a
  deadline was exceeded, and by the time `Future.get` returns the runtime has run
  the turn's completion callbacks (`BaseRuntime.runSubmitted`), so the memory holds
  the final state. It classifies the outcome from in-memory data only — `ERROR`,
  cancelled/`EXECUTION_INTERRUPTED`, watchdog timeout, or an
  `llm:spend_refused:*` record on the current step (cost ceiling / tenant budget) —
  and fires `TurnOutcome` only for those. A clean turn costs one state check and
  nothing else.
- Both observers (`AlertDispatcher`) only **enqueue** onto a bounded executor (one
  worker, a fixed queue). A full queue drops the work, counts
  `eddi_alerts_dropped_total` and logs a WARN — it never blocks, and never throws
  into, the caller. Matching, storage and delivery run on that worker.

**Rules.** `AlertRule` is a small admin resource (Mongo `alertrules` / Postgres
`alert_rules`). The rules that watch an agent are cached per agent (Caffeine, 60 s):
a save or delete on this node invalidates at once; another node picks the change up
within the TTL.

**Outbox.** Every fired alert is an `AlertEvent` (`alertevents` / `alert_events`),
written **before** anything is delivered, with one delivery record per target
(status, attempts, last error, next attempt). The row is written with
`nextAttemptAt = now + lease`, so if the node dies mid-delivery the event is picked
up by the maintenance job after the lease — nothing is lost on a crash, and a
delivery is at-least-once.

**Throttle, dedupe, digest — cluster-safe by construction.** Windows are fixed
buckets of `throttle.windowMinutes` aligned to the epoch.

- *Dedupe*: the event carries `dedupeKey = ruleId|agentId|conversationId|windowStart`
  under a unique index. A second insert with the same key fails at the database, on
  any replica, and only bumps `duplicates` on the first event.
- *Cap*: after a successful insert the worker increments a per-`(rule, window)`
  counter atomically (`$inc` upsert / `INSERT … ON CONFLICT DO UPDATE … RETURNING`).
  Up to `throttle.maxPerWindow` the event is delivered at once; past it the event is
  `DIGEST_PENDING` (or `INBOX_ONLY` when the rule has no digest) — recorded and
  visible in the inbox, delivered only in the next digest.
- *Digest*: when a rule's oldest `DIGEST_PENDING` event is older than the digest
  interval (`HOURLY` / `DAILY`), one digest event lists them and is delivered like
  any alert; the listed events become `DIGESTED` by a conditional update, so a
  second runner cannot digest them twice.

**Retries and digests run on the schedule machinery.** `ScheduleFireExecutor` /
`SchedulePollerService` are not only for agent conversations: HITL timeouts, Dream,
RAG ingestion and team cadences already use them as system jobs through metadata
fast-paths. Alerts add one more: a single system schedule, `alerts-maintenance`
(`HEARTBEAT`, `eddi.alerts.maintenance-interval`, default 60 s, metadata
`alertType=alert_maintenance`), created at startup if missing. The poller's
cluster-wide CAS claim and lease make one node run it at a time; each run

1. claims due deliveries (`nextAttemptAt ≤ now`, claimed per event by a conditional
   update, so two runners never send the same event at the same time; a node that
   dies after sending but before recording it can cause a repeat on retry) and retries the
   failed targets — backoff 30 s × 4ⁿ⁻¹, at most `eddi.alerts.max-attempts` (5),
   then `FAILED`;
2. assembles due digests;
3. deletes events past `eddi.alerts.event-retention` (90 days).

No scheduler thread of its own. If two nodes start at once and both create the
schedule, both copies run; every step above is guarded by a conditional update, so
a duplicate copy does no harm. With `eddi.schedule.enabled=false` retries and
digests stop (first deliveries still happen).

**Privacy.** A payload never carries a user id. It names the rule, the agent and
version, the conversation id with a Manager link, and *what* matched — the rule's
own constants and the error kind. Conversation content appears in two cases only,
each cut to 280 characters: a rating comment (the person addressed it to the team,
as the rating card says), and an `OUTPUT_MATCH` value when the rule sets
`includeValue` and the agent version has conversation review on. GDPR erasure of a
user blanks the comment excerpt in every alert event about their ratings.

## Writing a rule

`POST /alerts/rules` (the Manager has no Alerts page yet, so rules are written over REST):

```json
{
  "name": "Unhappy users",
  "enabled": true,
  "scope": { "allAgents": false, "agentIds": ["6aba70011a25f8be7920e055"] },
  "source": "RATING",
  "condition": { "maxNormalizedScore": 0.25, "onComment": true, "tags": ["Wrong answer"] },
  "delivery": [
    { "type": "SLACK", "channelId": "C0123456789" },
    { "type": "WEBHOOK", "url": "https://hooks.example.com/eddi", "signingSecret": "${vault:alert-signing-secret}" }
  ],
  "throttle": { "windowMinutes": 60, "maxPerWindow": 10 },
  "digest": "HOURLY"
}
```

| Field | Default | Meaning |
| --- | --- | --- |
| `scope` | — | `allAgents: true`, or `agentIds` (at most 100) |
| `source` | — | `RATING`, `CONVERSATION_ERROR` or `OUTPUT_MATCH` |
| `condition` | — | See below; a rule that sets another source's fields is refused |
| `delivery` | `[]` | Up to 5 Slack / webhook targets. The Manager inbox is always a target |
| `throttle.windowMinutes` | `60` | 1–1440. Fixed windows aligned to the epoch |
| `throttle.maxPerWindow` | `10` | 1–1000 alerts of the rule delivered per window |
| `digest` | `HOURLY` | `HOURLY`, `DAILY`, or `NONE` (overflow stays in the inbox) |

A save is validated and the error (`INVALID_RULE`) names every field that is wrong.

### Sources and conditions

| `source` | Fires when | `condition` |
| --- | --- | --- |
| `RATING` | a rating is created or changed | `maxNormalizedScore` (0..1, fire at or below), `onComment` (any comment), `tags` (any of these reason tags). OR-ed; at least one |
| `CONVERSATION_ERROR` | a turn ends badly | `errorKinds`, one or more of the table below |
| `OUTPUT_MATCH` | a turn completes and a value in its result matches | `path`, `op`, `value`, `includeValue` — see [Matching a turn's result](#matching-a-turns-result) |

| Error kind | The turn… |
| --- | --- |
| `TURN_ERROR` | failed: the conversation is in `ERROR` |
| `TURN_INTERRUPTED` | was cancelled while running (`EXECUTION_INTERRUPTED`): a cluster lease lost to another node, an administrator's cancel, a GDPR stop |
| `TURN_DEADLINE` | ran past the agent timeout and was abandoned by the watchdog |
| `COST_CEILING` | had a model call refused by the agent's `maxCostPerConversationUsd` |
| `TENANT_BUDGET` | had a model call refused by the tenant's monthly budget (or because its accounting could not answer) |

Both the regular turn path and the HITL resume path report outcomes. A tool loop
stopped mid-way by the cost ceiling is not reported on that turn (the loop ends
with a normal answer); the next turn's model call is refused and reported.

### Matching a turn's result

`OUTPUT_MATCH` compares one value of a finished turn with a constant the rule author
wrote. It is a deliberately small matcher: **no expression language, and no template
evaluation of runtime data** — the value at the path is compared with the constant,
nothing else.

| `path` | Reads |
| --- | --- |
| `properties.<name>` (`.<a.b>` for a nested value) | the conversation properties after the turn |
| `output.json.<a.b.c>` | the agent's output **parsed as JSON** — only when it is JSON (an object or array, at most 64 KB) |
| `actions` | the turn's action list |

Dot segments only (letters, digits, `_`, `-`), at most 8 after the source, no
wildcards.

| `op` | Value | Holds when |
| --- | --- | --- |
| `exists` | none | the path has a value |
| `eq`, `neq` | string, number, boolean | equal / not equal (numbers by value) |
| `in` | a list of 1–50 scalars | the value is one of them |
| `gt`, `gte`, `lt`, `lte` | number | the value is a number and compares so |
| `contains` | string | a string value contains it, or a list value has it as an element (`actions` takes `contains` and `exists` only) |

A value of the wrong type never matches an equality or ordered comparison (`"5" gt 3` is false, and so is `"5" eq 5`); `neq` is the exception and holds for it (`"5" neq 5` is true). The examples from the
plan:

```json
{ "source": "OUTPUT_MATCH", "condition": { "path": "properties.escalate", "op": "eq", "value": true } }
{ "source": "OUTPUT_MATCH", "condition": { "path": "output.json.sentiment", "op": "in", "value": ["angry", "frustrated"] } }
{ "source": "OUTPUT_MATCH", "condition": { "path": "actions", "op": "contains", "value": "handoff_requested" } }
```

**What the alert says.** By default the path, the operator and your constant
(`matched: ["properties.escalate eq true"]`) — never the runtime value. With
`"includeValue": true` the value that matched is added as `match.value` (cut to 280
characters), **only when the agent version the conversation ran on has
[conversation review](workspaces.md) enabled**: the value comes from the conversation,
so it is shown only where the people running the agent may read conversations anyway.
Without review the flag is ignored.

**Cost on a clean turn.** The turn hook checks an in-memory cache: an agent without
enabled `OUTPUT_MATCH` rules costs one lookup and nothing else. For an agent with such
rules, only the values those rules read are copied off the turn — the named
properties, the output text, the action list — and matched on the alert worker; the
live conversation memory never leaves the turn. On a cache miss (the first turn of an
agent after start-up, or after the 60-second refresh) the rules are loaded on the
worker and that one turn copies all three, so no match is lost.

### Delivery

- **Inbox** — always. `/manage/alerts`, with the delivery status of every target.
- **Slack** — `channelId` must be a channel an existing Slack
  [channel integration](slack-integration.md) serves: the message is posted with
  that integration's bot token, so no credential lives on the rule. Text that came
  from people (the rating comment) is escaped, so it cannot mention a channel or
  inject a link.
- **Webhook** — a JSON `POST` to an **`https`** URL (a plain `http` URL is refused when
  the rule is saved, and a `${vault:…}` URL that resolves to one is not delivered to)
  through EDDI's outbound HTTP client: strict SSRF
  policy (no private or metadata addresses, checked on the resolved address), no
  redirects, a 10-second timeout (`eddi.alerts.webhook-timeout`), a 64 KB body cap,
  and only the first 16 KB of the answer read. Any 2xx counts as delivered. Webhook
  targets are an **administrator's**: the URL is arbitrary and the signature is
  computed with a vault secret.

The webhook is signed exactly like EDDI's own inbound webhook channel (`eddi-v1`),
so one EDDI can receive another's alerts:

```
X-Eddi-Signature: t=1761307200,v1=5f2b…   # HMAC-SHA256(secret, t + "." + rawBody), lower-case hex
X-Eddi-Alert-Id: 6aba70011a25f8be7920e0ee
Content-Type: application/json
```

**A webhook URL is a secret.** Many embed a bearer token in the path or query
(`hooks.slack.com/services/…`), so no read ever returns one — not the rule list, not a
single read, not the answer to a create or update, not a test send, not MCP, not the
Platform Operator, and not to administrators either. A read answers scheme and host
plus `/***` and `"urlConfigured": true`. Every target also carries a `targetId`,
assigned by the server when the rule is saved, kept across updates and returned by
every read (it is not secret). On update, sending the masked value back (or no URL)
together with the target's `targetId` keeps that target's stored URL — matched by
the id alone, never by position or host, so removing or reordering targets cannot
hand one target another's URL. A new value replaces the stored one; a new target
(no `targetId`) must bring a real URL; a masked or empty URL whose `targetId` is
missing or unknown is refused (`INVALID_RULE`). Ids are the server's: an unknown id
on a target with a real URL is replaced, and a create assigns all of them. A rule
saved before ids existed shows ids derived from each target, the same on every read,
so its first update matches them and the save stores them. A test send of a saved
rule restores URLs the same way. The
URL may instead be a `${vault:…}` reference (`"url": "${vault:alert-hook-url}"`):
resolved only when the alert is sent, shown as the reference, and — like the signing
secret — an unresolvable one means no delivery.

Verify by recomputing the MAC over the **raw** body and comparing in constant time,
and reject a `t` more than five minutes away. `signingSecret` must be a
`${vault:…}` reference; a secret that cannot be resolved stops the delivery (it is
never sent unsigned). The body:

```json
{
  "schema": "eddi.alert.v1",
  "id": "6aba70011a25f8be7920e0ee",
  "kind": "ALERT",
  "firedAt": "2026-10-24T12:00:00Z",
  "ruleId": "6aba70011a25f8be7920e0aa",
  "ruleName": "Unhappy users",
  "source": "RATING",
  "agentId": "6aba70011a25f8be7920e055",
  "agentVersion": 3,
  "conversationId": "6aba70011a25f8be7920e0cc",
  "summary": "Unhappy users: conversation rating 1 (FIVE_STAR) — normalizedScore <= 0.25, comment (agent 6aba… v3)",
  "match": {
    "matched": ["normalizedScore <= 0.25", "comment"],
    "ratingId": "6aba70011a25f8be7920e0dd", "stepIndex": -1, "scale": "FIVE_STAR", "score": 1, "normalizedScore": 0.0,
    "tags": ["Wrong answer"], "commentExcerpt": "It kept looping on my order number."
  },
  "links": { "alert": "https://eddi.example.com/manage/alerts?event=6aba…", "conversation": "https://eddi.example.com/manage/conversationview/6aba…" }
}
```

`links` appears when `eddi.alerts.manager-url` is set. A digest has `kind: DIGEST`,
`digestCount` and up to 50 `digestItems`.

**Send test alert** (`POST /alerts/rules/test`, body: the rule, saved or not) sends a
clearly marked test message to every target now and reports each outcome. Nothing is
stored.

## The inbox

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/alerts/events` | Newest first; filters `agentId`, `ruleId`, `source`, `acknowledged`, `state`, `conversationId`, `from`, `to`; `index`, `limit` ≤ 100 |
| `GET` | `/alerts/events/unacknowledged-count` | `{count}` — the Manager's sidebar badge |
| `GET` | `/alerts/events/{eventId}` | One event |
| `POST` | `/alerts/events/{eventId}/acknowledge` | Records the caller and the time; twice changes nothing |
| `POST` | `/alerts/events/acknowledge` | `{ids: […]}` (all or nothing: each id is checked first; an empty list or entry is a 400 `INVALID_PARAMETER`), or leave `ids` out to acknowledge by the query filter |
| `POST` | `/alerts/events/{eventId}/retry` | Failed targets back to pending, delivered now in the background (409 `NOTHING_TO_RETRY` otherwise) |

An event's `state`: `PENDING` (delivering, or a target waits for its next retry),
`DELIVERED`, `FAILED` (a target gave up — each delivery shows `attempts` and
`lastError`), `INBOX_ONLY`, `DIGEST_PENDING`, `DIGESTED`. `duplicates` counts later
alerts folded into this one.

Rules: `GET /alerts/rules` (`agentId`), `GET`/`PUT`/`DELETE /alerts/rules/{ruleId}`,
`POST /alerts/rules`. Deleting a rule keeps its events.

## Who may do what

Roles `eddi-admin` and `eddi-editor`, enumerated (REST has no role hierarchy;
`eddi-viewer` is refused).

- **Administrators** write any rule and see every event.
- **Editors** work one agent at a time, holding `EDIT` on it: a rule's scope must be
  exactly that agent (`ADMIN_SCOPE_REQUIRED` otherwise), its targets Slack or the
  inbox (`WEBHOOK_ADMIN_ONLY`), and access is re-checked against the **stored** rule
  on update and delete. Lists and counts need `agentId` (`AGENT_ID_REQUIRED`). A
  webhook target appears to them by scheme and host only, without the secret
  reference. A digest of a rule over several agents names no agent and is an
  administrator's.

The author (`createdBy`, `updatedBy`) and the acknowledger are always the caller,
never taken from the body.

## MCP and the Platform Operator

- MCP: `list_alert_events` and `acknowledge_alert_event` (tier admin + editor, the
  same scoping, the acknowledger from the MCP caller's identity) — see
  [MCP Server](mcp-server.md#alert-tools-2).
- The Platform Operator reads `GET /alerts/events`, `/alerts/events/unacknowledged-count`,
  `/alerts/rules` and `/alerts/rules/{ruleId}` — with webhook URLs masked, so it never
  holds a usable URL. It cannot acknowledge, retry, send a
  test or change a rule: acknowledging is a person's statement, and the other three
  send real messages or decide where alert content goes.

## Privacy

**What leaves EDDI.** No Slack message or webhook payload carries a user id, and no
inbox read (REST, MCP, the operator) carries the id of anybody who chatted. An alert names the rule, the agent and version,
the conversation id, the error kind or the rating's score, scale and tags, and the
rule's own criteria that matched. Conversation content appears in two cases only,
each cut to 280 characters: the rating comment, which the person addressed to the
team running the agent (the rating card says so), and the value an `OUTPUT_MATCH`
rule matched — only when the rule sets `includeValue` and the agent version has
conversation review on, so it is shown only where the people running the agent may
read conversations anyway.

**What is stored.** A stored event keeps two user ids: the administrator or editor
who acknowledged it (`acknowledgedBy` — shown in the inbox to the people running
agents, never sent to Slack or a webhook), and — on an event that stores an
`OUTPUT_MATCH` value, and only there — the id of the person whose conversation the
value came from, an internal field the stores write and never read back, so it is
never shown or sent. Both stay
until GDPR erasure or until the event is deleted after `eddi.alerts.event-retention`
(90 days).

**GDPR erasure** of a user blanks the comment excerpt in every alert about their
ratings, blanks every `OUTPUT_MATCH` value stored from their conversations (and the
internal id with it), and forgets an administrator or editor as the acknowledger of
events and the author of rules.

Alert work waits in a queue, so content captured before an erasure can reach the
store after it. The erasure's first step therefore puts up a short-lived **fence**
for the user on every node (through the same cluster stop broadcast the conversation
service uses). An `OUTPUT_MATCH` value captured before the erasure started is not
stored — that alert still fires, without the value. A rating alert for a rating
given before the erasure started is dropped altogether: no event is written or
sent. A queued rating alert also reads the rating again and is dropped when the
rating is gone or has changed. An event written while the fence went up has its
value or excerpt blanked before it is delivered. A fence lasts 15 minutes, far longer than the queue takes to
drain; it only ever holds back content captured *before* the erasure, so a later
conversation of a reused id is not affected.

**No send starts with erased content once an erasure has completed.** Every send —
the first delivery and each retry, on any node — first takes a short claim on the
event in the store (an atomic conditional write, 30 seconds), then reads the event
and sends what is stored then, then releases the claim; a send that cannot take
the claim (another node is sending) waits for the next pass, and one left with too
little of its claim to finish inside it is put off. The erasure's redactions skip
a claimed event and the erasure repeats them until no event of the user still holds
the content, so it completes only after every send that had already read the
content has finished — or its claim has expired, for a node that died mid-send.
The wait is bounded (ten send leases): if an event still holds the content after that, the erasure step reports a failure for the next run to repeat, rather than claiming completion.
The one limit: a send that started before the erasure completed cannot be recalled.

**A GDPR export** (`alerts` in its `additionalData`) lists, for a person who runs
agents, the rules they created or last edited — id, name, times and which of the two —
and the events they acknowledged — id, rule name, source, agent, when it fired and
when they acknowledged it; for a person who chatted, the `OUTPUT_MATCH` values stored
from their conversations — the value, the conversation and when it was recorded. At
most 10,000 of each, without naming anybody else.

## Operating alerts

| Property | Default | Meaning |
| --- | --- | --- |
| `eddi.alerts.enabled` | `true` | Off: nothing is queued and the maintenance schedule is not created |
| `eddi.alerts.queue-capacity` | `1000` | Work the alert worker may have waiting before it drops |
| `eddi.alerts.maintenance-interval` | `60s` | How often retries and digests run (at least 15 s) |
| `eddi.alerts.max-attempts` | `5` | Delivery attempts per target before `FAILED` |
| `eddi.alerts.retry-base` | `30s` | Backoff: base × 4ⁿ⁻¹, at most six hours |
| `eddi.alerts.claim-lease` | `2m` | How long a node owns a delivery before another may retry it |
| `eddi.alerts.event-retention` | `90d` | Events older than this are deleted |
| `eddi.alerts.webhook-timeout` | `10s` | Whole-exchange deadline of a webhook call |
| `eddi.alerts.manager-url` | — | The Manager's base URL, for links in delivered alerts |

- The `alerts-maintenance` schedule appears in the schedule list as system-managed.
  Updating, deleting, enabling, disabling or dismissing it — over REST or the MCP
  `delete_schedule` tool, administrators included — is refused with 409
  `SYSTEM_SCHEDULE`; firing it by hand is allowed (it runs one maintenance pass). A
  schedule created by hand with `alertType` metadata is refused. As a backstop, the
  server recreates the schedule on startup and on every rule save.
- Metrics: `eddi_alerts_*` — see [Metrics](metrics.md#alert-metrics). A Grafana
  alert-rule example on failed deliveries and a dropping queue is in the
  [Monitoring Guide](monitoring/monitoring-guide.md#alerting-on-alert-delivery).
- `eddi_alerts_dropped_total` above zero means the worker could not keep up (a slow
  webhook, a slow database). Turns and ratings were not affected; the dropped alerts
  were never evaluated.

## Known limitations

- Group conversations: member turns run through the same turn path and are
  reported; the group discussion as a whole is not a source.
- A Slack channel served by several integrations uses the one the channel router
  resolves; a rule cannot pick an integration by name.
- `OUTPUT_MATCH` matches one value per rule; combine conditions by writing several
  rules.
