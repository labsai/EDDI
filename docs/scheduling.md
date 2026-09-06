# Scheduled Execution & Heartbeat Triggers

## Overview

EDDI supports **scheduled agent execution** — agents can be triggered automatically on a timer without any user input. This enables proactive agents, background maintenance, periodic data processing, and memory consolidation.

### Use Cases

| Use Case | Description |
|----------|-------------|
| **Proactive Agents** | Check for updates, send notifications, or perform monitoring at regular intervals |
| **Dream Consolidation** | Background memory maintenance — prune stale entries, detect contradictions, summarize facts |
| **Data Pipelines** | Periodically fetch data from external APIs and process it through the agent pipeline |
| **Health Checks** | Run diagnostic agents that verify system health and report anomalies |
| **Report Generation** | Generate daily/weekly summary reports through conversational agents |

## Concepts

### Schedule

A **Schedule** defines when and how often an agent fires:

```json
{
  "agentId": "agent-123",
  "agentVersion": 0,
  "triggerType": "CRON",
  "cronExpression": "0 2 * * *",
  "conversationStrategy": "persistent",
  "message": "Run maintenance cycle",
  "userId": "system:scheduler",
  "timeZone": "Europe/Vienna",
  "enabled": true
}
```

### Trigger Types

| Type | Description | Default Strategy | Example |
|------|-------------|-----------------|---------|
| `CRON` | Wall-clock aligned cron expression | `new` | `0 2 * * *` (daily at 2am) |
| `HEARTBEAT` | Fixed-interval, drift-proof | `persistent` | Every 300 seconds |

### Conversation Strategies

| Strategy | Behavior | Use When |
|----------|----------|----------|
| `persistent` | Reuses the same conversation across all fires. Context accumulates. | Dream consolidation, ongoing monitoring, stateful agents |
| `new` | Creates a fresh conversation for each fire. Clean context each time. | Report generation, data pipelines, stateless tasks |

## Configuration

### Creating a Schedule

```bash
curl -X POST http://localhost:7070/schedulestore/schedules \
  -H "Content-Type: application/json" \
  -d '{
    "agentId": "agent-123",
    "agentVersion": 0,
    "triggerType": "CRON",
    "cronExpression": "*/30 * * * *",
    "conversationStrategy": "persistent",
    "message": "heartbeat ping",
    "timeZone": "UTC",
    "enabled": true
  }'
```

### Cron Expression Reference

EDDI uses **standard 5-field cron expressions**:

```text
┌────── minute (0-59)
│ ┌──── hour (0-23)
│ │ ┌── day of month (1-31)
│ │ │ ┌ month (1-12)
│ │ │ │ ┌ day of week (0-7, 0=Sun)
│ │ │ │ │
* * * * *
```

**Common patterns:**

| Expression | Schedule |
|------------|----------|
| `0 2 * * *` | Daily at 2:00 AM |
| `*/30 * * * *` | Every 30 minutes |
| `0 */4 * * *` | Every 4 hours |
| `0 9 * * 1-5` | Weekdays at 9:00 AM |
| `0 0 1 * *` | First day of each month at midnight |

### Heartbeat Configuration

For heartbeat triggers, use `heartbeatIntervalSeconds` instead of `cronExpression`:

```json
{
  "agentId": "agent-123",
  "triggerType": "HEARTBEAT",
  "heartbeatIntervalSeconds": 300,
  "conversationStrategy": "persistent",
  "message": "heartbeat check",
  "enabled": true
}
```

Heartbeats are **drift-proof** — the next fire is the time this fire was *due* plus the interval, not the moment the turn happened to finish. A 40 s turn on a 60 s heartbeat still fires every 60 s. (`lastFired + interval` would *be* the drifting formula: `lastFired` is the completion instant. The one exception is a fire that overran a whole interval — anchoring on the due time would put the next fire in the past, which is a re-fire loop rather than catching up, so it is clamped to `now + interval`.)

### Schedule Fields

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `agentId` | string | required | Agent to trigger |
| `agentVersion` | int | `0` (latest) | Agent version (0 = latest deployed) |
| `triggerType` | enum | `CRON` | `CRON` or `HEARTBEAT` |
| `cronExpression` | string | — | 5-field cron (for CRON type) |
| `heartbeatIntervalSeconds` | long | — | Interval in seconds (for HEARTBEAT type) |
| `conversationStrategy` | string | varies | `new` or `persistent` |
| `message` | string | — | Message text sent to the agent on each fire |
| `userId` | string | `system:scheduler` | User identity for the fire |
| `timeZone` | string | `UTC` | IANA timezone (e.g., `Europe/Vienna`) |
| `environment` | string | `production` | Deployment environment |
| `enabled` | boolean | `true` | Whether the schedule is active |
| `maxCostPerFire` | double | `-1` (unlimited) | Dollar ceiling per fire |

### Managing Schedules

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/schedulestore/schedules` | Create a schedule |
| `GET` | `/schedulestore/schedules` | List schedules, newest first (optional `?agentId=` filter; `?limit=` default 500, max 1000; `?offset=` default 0) |
| `GET` | `/schedulestore/schedules/{id}` | Get a specific schedule |
| `PUT` | `/schedulestore/schedules/{id}` | Update a schedule |
| `DELETE` | `/schedulestore/schedules/{id}` | Delete a schedule |
| `POST` | `/schedulestore/schedules/{id}/enable` | Enable a schedule |
| `POST` | `/schedulestore/schedules/{id}/disable` | Disable a schedule |
| `POST` | `/schedulestore/schedules/{id}/fire` | Manually trigger a fire immediately |

> **Paging (wire change).** The listing used to be one hard-capped page of 500 in
> whatever order the store returned; past that, the surplus schedules could not be
> found, disabled or deleted through the list at all. It is now ordered (newest
> `createdAt` first, id breaking ties) and takes `limit`/`offset`. A response
> holding exactly `limit` entries may be truncated — ask for the next page to find
> out.
>
> **`limit=0` is now `400`, on all three listing endpoints.** It used to be passed
> through to the store, where the two backends read it opposite ways: the MongoDB
> driver treats `limit(0)` as *no limit* and dumped every row, while PostgreSQL's
> `LIMIT 0` returned nothing. A client that sent `limit=0` and got away with it
> must send a positive value.
>
> **Firing a one-shot consumes it.** `POST /{id}/fire` runs the same state machine
> a polled fire does, so a successful manual fire of a `oneTimeAt` schedule
> disables it — it is the run, not a rehearsal. Re-arm it with
> `POST /{id}/enable`.
>
> **Firing a heartbeat manually consumes its next scheduled fire.** Same reason:
> a successful fire re-arms the schedule from the fire it was *due* to make, so
> firing a daily heartbeat by hand in the morning moves the next one to a day
> after that due time — tonight's run is skipped, not brought forward.
>
> **A *skipped* manual fire does not.** If the coordinator drops the turn because
> the conversation is busy or `AWAITING_HUMAN`, nothing was delivered, so nothing
> is consumed: a due time still in the future is left exactly where it was and
> tonight's run happens as configured. Only a due time that has already passed is
> rolled forward to the next cadence.
>
> A manual fire is **synchronous**: the request holds open until the turn
> finishes or `eddi.schedule.fire-timeout` (default 5 minutes) elapses, so a
> proxy or client with a shorter read timeout may give up before the fire log
> comes back. The fire itself continues, and the schedule stays claimed until it
> ends — a retry in the meantime answers `409`.

### Admin Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/schedulestore/schedules/{id}/fires` | Read fire history, newest first (`?limit=` default 20, must be > 0, capped at 500) |
| `GET` | `/schedulestore/schedules/admin/failed` | List all failed/dead-lettered fires (`?limit=` default 50, must be > 0, capped at 500) |
| `POST` | `/schedulestore/schedules/{id}/retry` | Re-queue a dead-lettered schedule |
| `POST` | `/schedulestore/schedules/{id}/dismiss` | Reset dead-letter without immediate retry |

## Dream Consolidation

Dream Consolidation is a specialized schedule that performs **background memory maintenance** on an agent's persistent user memories. It's configured in the agent's `UserMemoryConfig`, not as a standalone schedule.

### What It Does

1. **Stale entry pruning** — Removes outdated facts that are no longer relevant
2. **Contradiction detection** — Identifies conflicting memories (e.g., "user likes coffee" vs "user hates coffee") and logs them for review. Resolution is planned for a future version.
3. **Fact summarization** — Consolidates verbose entries into concise summaries

### Configuration

Dream consolidation is configured in the agent configuration:

```json
{
  "agentConfiguration": {
    "enableMemoryTools": true,
    "userMemoryConfig": {
      "dream": {
        "enabled": true,
        "schedule": "0 3 * * *",
        "detectContradictions": true,
        "contradictionResolution": "keep_newest",
        "pruneStaleAfterDays": 90,
        "summarizeInteractions": true,
        "summarizeMinEntries": 5,
        "summarizeTargetEntries": 2,
        "summarizeGroupBy": "category",
        "preserveAgentProvenance": false,
        "llmProvider": "anthropic",
        "llmModel": "claude-sonnet-4-6",
        "maxCostPerRun": 0.50,
        "batchSize": 50,
        "maxUsersPerRun": 1000
      }
    }
  }
}
```

> **Scope:** a dream cycle only touches memories the **firing agent** wrote (`sourceAgentId`). Set `crossAgentMaintenance: true` to maintain the user's whole memory set across agents — without it, agent A's `pruneStaleAfterDays` would delete agent B's memories and A's model endpoint would see B's private text.
>
> `maxSummarizationCalls` is **deprecated** in favour of `maxCostPerRun` (a call count is a poor budget — consolidations differ wildly in cost). It is still honoured as a secondary backstop if a stored config sets it explicitly, so existing configurations keep their bound.

### Cost Control

Dream cycles consume LLM tokens. Use `maxCostPerRun` (in the **Agent Configuration**) to set a dollar ceiling per run:

```json
{
  "agentConfiguration": {
    "userMemoryConfig": {
      "dream": {
        "maxCostPerRun": 0.50
      }
    }
  }
}
```

When the budget is exceeded, the agent stops processing. This prevents runaway costs on large memory stores.

> **Tip:** Use a cheaper model (e.g., `claude-sonnet-4-6` or `gpt-4o-mini`) for dream consolidation — the task doesn't require top-tier reasoning.

## Fire Logging

Every scheduled execution is logged. View fire history via the REST API:

```bash
# List recent fires for a schedule
curl http://localhost:7070/schedulestore/schedules/{scheduleId}/fires?limit=20

# List all failed fires across all schedules
curl http://localhost:7070/schedulestore/schedules/admin/failed?limit=50
```

> **What `cost` means depends on the fire path, and the two are not the same
> quantity.** A conversation fire reports the **tool** spend of that turn — the
> `ToolCostTracker` delta — so a schedule whose agent only talks to the model,
> with no tool calls, reports `0.00` however many tokens it used. A dream
> consolidation fire reports its own **estimated LLM** cost. Compare a fire log
> against others on the same path, and use `maxCostPerFire` / `maxCostPerRun`
> rather than the logged number to bound spend.

### State Machine

Each schedule follows a state machine:

```text
PENDING → CLAIMED → EXECUTING → COMPLETED
                              → SKIPPED → (re-arm, no failure counted) → PENDING
                              → FAILED → (retry) → PENDING
                              → DEAD_LETTERED → (manual retry/dismiss)
```

`SKIPPED` is a **fire-log status only** — it is never stored on the schedule
itself. It means the coordinator dropped the scheduled turn without consuming
the input because the conversation was already busy or `AWAITING_HUMAN`. That is
the normal state of a `persistent` heartbeat while a human is chatting in its
conversation, or while a previous fire waits on a HITL approval, so a skip is
logged and counted but **never** enters the retry/backoff/dead-letter machine:
`failCount` is left exactly as it was and the claim is released.

Re-arming is deliberately conservative, because a skip delivered nothing:

- **Due time already passed** (every polled skip, and a manual fire of an overdue
  schedule) → advanced to the next cadence, on the same drift-proof anchor a
  successful fire uses.
- **Due time still in the future** (only reachable through `POST /{id}/fire`,
  which claims regardless of `nextFire`) → left untouched. The pending fire *is*
  the next cadence, so advancing past it would silently cancel a scheduled
  delivery that nothing replaced.
- **A one-shot whose moment has passed** has no cadence to re-arm to, so it does
  go through retry/backoff — its single delivery genuinely never happened.

## Cluster Awareness

The `SchedulePollerService` is cluster-aware — in multi-instance deployments, only one instance executes each scheduled fire. This is achieved via atomic claim operations (`tryClaim`), preventing duplicate execution when running EDDI behind a load balancer.

Two consequences are worth stating plainly, because they shape how a scheduled
target must be written:

- **Claiming is per-lease compare-and-set**, so exactly one instance wins a given
  poll. But **delivery is at-least-once**: if the winner dies mid-fire, the lease
  expires (`eddi.schedule.lease-timeout`) and another instance re-claims the same
  fire. **Scheduled targets must be idempotent.**
- Instances identify themselves by `eddi.schedule.instance-id`, which is
  auto-derived from the hostname when left empty. In an environment where
  hostnames are recycled or duplicated (some container schedulers), set it
  explicitly — two instances sharing an ID makes claim ownership ambiguous.

## Deployment Configuration

Individual schedules are configuration documents; the *poller* that runs them is
tuned deployment-wide in `application.properties` (or the matching environment
variables — Quarkus maps `eddi.schedule.poll-interval` to
`EDDI_SCHEDULE_POLL_INTERVAL` — every non-alphanumeric character becomes `_`).

| Property | Default | What it controls |
|---|---|---|
| `eddi.schedule.enabled` | `true` | Master switch. `false` stops all polling — schedules remain stored and simply never fire |
| `eddi.schedule.poll-interval` | `15s` | How often each instance looks for due schedules. This is the floor on firing punctuality: a schedule due at `12:00:00` fires somewhere in `[12:00:00, 12:00:15)` |
| `eddi.schedule.poll-batch-size` | `100` | Max schedules claimed per poll cycle. Claimed schedules dispatch concurrently on virtual threads; raise it to drain large bursts (e.g. many one-shot HITL approval timeouts expiring together) |
| `eddi.schedule.lease-timeout` | `5m` | How long a claimed schedule is considered owned before another instance may re-claim it. Set it comfortably above your longest fire, or a slow run gets executed twice |
| `eddi.schedule.max-retries` | `5` | Attempts before a fire is `DEAD_LETTERED` |
| `eddi.schedule.backoff-base-seconds` | `15` | Retry delay = `base × multiplier^(attempt-1)` seconds |
| `eddi.schedule.backoff-multiplier` | `4` | With the defaults: 15s, 60s, 4m, 16m, 64m |
| `eddi.schedule.min-interval-seconds` | `60` | Smallest cron interval a schedule may request. Guards against schedule bombing; a rejected create returns a message naming this property |
| `eddi.schedule.instance-id` | *(hostname)* | Identity used for cluster claim tracking |
| `eddi.schedule.default-timezone` | `UTC` | IANA zone applied to schedules that do not name one |
| `eddi.schedule.fire-timeout` | `5m` | How long one conversation fire may run before it is abandoned as failed. Keep it at or below `lease-timeout` — past the lease another instance may reclaim the schedule regardless |
| `eddi.schedule.fire-log-retention` | `90d` | Fire logs older than this are deleted by a periodic sweep. `0` keeps everything — note that a 60-second heartbeat alone writes ~525,600 rows a year |
| `eddi.schedule.fire-log-prune-interval` | `1h` | How often that sweep runs. The DELETE is by timestamp and therefore idempotent, so it needs no cluster claim |

### Observability

| Metric | Type | Read it for |
|---|---|---|
| `eddi.schedule.poll.count` | Counter | Poller liveness. Flat means the poller is not running — check `eddi.schedule.enabled` |
| `eddi.schedule.fire.count` | Counter | Fires executed |
| `eddi.schedule.fire.failed` | Counter | Fires that raised. Compare against `fire.count` for a failure rate |
| `eddi.schedule.fire.skipped` | Counter | Fires dropped because the target conversation was busy or awaiting a human. Not failures and never dead-lettered, but a heartbeat that only ever skips is delivering nothing — compare against `fire.count` |
| `eddi.schedule.fire.deadlettered` | Counter | Fires that exhausted `max-retries`. **Alert on any increase** — these need manual retry or dismissal |
| `eddi.schedule.fire.duration` | Timer | If p99 approaches `lease-timeout`, double execution is imminent |
| `eddi.schedule.claim.conflict` | Counter | Instances racing for the same schedule. Normal and expected in a cluster; a sharp rise alongside falling `fire.count` suggests contention rather than work |
| `eddi.schedule.firelog.pruned` | Counter | Fire logs removed by the retention sweep. Flat while the table grows means retention is disabled (`fire-log-retention=0`) or the sweep is failing — check the logs |

## Best Practices

1. **Start with longer intervals** — Begin with hourly or daily schedules and increase frequency only if needed
2. **Use `persistent` strategy for stateful work** — Dream consolidation and monitoring agents benefit from accumulated context
3. **Set cost ceilings** — Always configure `maxCostPerFire` or `maxCostPerRun` for LLM-powered scheduled tasks
4. **Monitor fire logs** — Check for recurring failures that might indicate configuration issues
5. **Use cheap models for maintenance** — Background tasks rarely need expensive frontier models

## See Also

- [Managed Agents](managed-agents.md) — Intent-based agent routing
- [User Memory](user-memory.md) — Persistent user memory (target of dream consolidation)
- [LLM Configuration](langchain.md) — Agent configuration reference
- [Metrics](metrics.md) — Monitoring scheduled execution performance
