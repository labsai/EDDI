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

Heartbeats are **drift-proof** — after a fire completes, the next fire is calculated as `lastFired + interval`, not `now + interval`.

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
| `GET` | `/schedulestore/schedules` | List all schedules (optional `?agentId=` filter) |
| `GET` | `/schedulestore/schedules/{id}` | Get a specific schedule |
| `PUT` | `/schedulestore/schedules/{id}` | Update a schedule |
| `DELETE` | `/schedulestore/schedules/{id}` | Delete a schedule |
| `POST` | `/schedulestore/schedules/{id}/enable` | Enable a schedule |
| `POST` | `/schedulestore/schedules/{id}/disable` | Disable a schedule |
| `POST` | `/schedulestore/schedules/{id}/fire` | Manually trigger a fire immediately |

### Admin Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/schedulestore/schedules/{id}/fires` | Read fire history (optional `?limit=20`) |
| `GET` | `/schedulestore/schedules/admin/failed` | List all failed/dead-lettered fires |
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

### State Machine

Each schedule follows a state machine:

```text
PENDING → CLAIMED → EXECUTING → COMPLETED
                              → FAILED → (retry) → PENDING
                              → DEAD_LETTERED → (manual retry/dismiss)
```

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

### Observability

| Metric | Type | Read it for |
|---|---|---|
| `eddi.schedule.poll.count` | Counter | Poller liveness. Flat means the poller is not running — check `eddi.schedule.enabled` |
| `eddi.schedule.fire.count` | Counter | Fires executed |
| `eddi.schedule.fire.failed` | Counter | Fires that raised. Compare against `fire.count` for a failure rate |
| `eddi.schedule.fire.deadlettered` | Counter | Fires that exhausted `max-retries`. **Alert on any increase** — these need manual retry or dismissal |
| `eddi.schedule.fire.duration` | Timer | If p99 approaches `lease-timeout`, double execution is imminent |
| `eddi.schedule.claim.conflict` | Counter | Instances racing for the same schedule. Normal and expected in a cluster; a sharp rise alongside falling `fire.count` suggests contention rather than work |

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
