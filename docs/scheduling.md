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

```
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
2. **Contradiction detection** — Identifies and resolves conflicting memories (e.g., "user likes coffee" vs "user hates coffee")
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
        "summarizeInteractions": false,
        "llmProvider": "anthropic",
        "llmModel": "claude-sonnet-4-6",
        "maxCostPerRun": 5.00,
        "batchSize": 50,
        "maxUsersPerRun": 1000
      }
    }
  }
}
```

### Cost Control

Dream cycles consume LLM tokens. Use `maxCostPerRun` to set a dollar ceiling per run:

```json
{
  "maxCostPerRun": 0.50
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

```
PENDING → CLAIMED → EXECUTING → COMPLETED
                              → FAILED → (retry) → PENDING
                              → DEAD_LETTERED → (manual retry/dismiss)
```

## Cluster Awareness

The `SchedulePollerService` is cluster-aware — in multi-instance deployments, only one instance executes each scheduled fire. This is achieved via atomic claim operations (`tryClaim`), preventing duplicate execution when running EDDI behind a load balancer.

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
