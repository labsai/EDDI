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
  "agentVersion": 1,
  "triggerType": "CRON",
  "cronExpression": "0 0 2 * * ?",
  "conversationStrategy": "persistent",
  "enabled": true
}
```

### Trigger Types

| Type | Description | Example |
|------|-------------|---------|
| `CRON` | Standard cron expression | `0 0 2 * * ?` (daily at 2am) |
| `HEARTBEAT` | Periodic interval | Every 5 minutes |

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
    "agentVersion": 1,
    "triggerType": "CRON",
    "cronExpression": "0 */30 * * * ?",
    "conversationStrategy": "persistent",
    "enabled": true
  }'
```

### Cron Expression Reference

EDDI uses standard 6-field cron expressions (Quartz format):

```
┌──────── second (0-59)
│ ┌────── minute (0-59)
│ │ ┌──── hour (0-23)
│ │ │ ┌── day of month (1-31)
│ │ │ │ ┌ month (1-12)
│ │ │ │ │ ┌ day of week (0-7, 0=Sun)
│ │ │ │ │ │
* * * * * ?
```

**Common patterns:**

| Expression | Schedule |
|------------|----------|
| `0 0 2 * * ?` | Daily at 2:00 AM |
| `0 */30 * * * ?` | Every 30 minutes |
| `0 0 */4 * * ?` | Every 4 hours |
| `0 0 9 * * MON-FRI` | Weekdays at 9:00 AM |
| `0 0 0 1 * ?` | First day of each month at midnight |

### Managing Schedules

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/schedulestore/schedules` | Create a schedule |
| `GET` | `/schedulestore/schedules` | List all schedules |
| `GET` | `/schedulestore/schedules/{id}` | Get a specific schedule |
| `PUT` | `/schedulestore/schedules/{id}` | Update a schedule |
| `DELETE` | `/schedulestore/schedules/{id}` | Delete a schedule |

## Dream Consolidation

Dream Consolidation is a specialized schedule that performs **background memory maintenance** on an agent's persistent user memories. It runs as a scheduled conversation with LLM-powered analysis.

### What It Does

1. **Stale entry pruning** — Removes outdated facts that are no longer relevant
2. **Contradiction detection** — Identifies and resolves conflicting memories (e.g., "user likes coffee" vs "user hates coffee")
3. **Fact summarization** — Consolidates verbose entries into concise summaries

### Configuration

Dream consolidation is configured in the agent's LLM task configuration:

```json
{
  "tasks": [
    {
      "actions": ["dream_consolidation"],
      "type": "openai",
      "enableBuiltInTools": true,
      "builtInTools": ["userMemory"],
      "enableCostTracking": true,
      "maxBudgetPerConversation": 0.50,
      "parameters": {
        "modelName": "gpt-4o-mini",
        "systemMessage": "You are a memory maintenance agent. Review and consolidate the user's memories."
      }
    }
  ]
}
```

### Cost Control

Dream cycles consume LLM tokens. Use `maxBudgetPerConversation` to set a dollar ceiling per run:

```json
{
  "enableCostTracking": true,
  "maxBudgetPerConversation": 0.50
}
```

When the budget is exceeded, the agent stops processing. This prevents runaway costs on large memory stores.

> **Tip:** Use a cheaper model (e.g., `gpt-4o-mini`) for dream consolidation — the task doesn't require top-tier reasoning.

## Fire Logging

Every scheduled execution is logged with:

| Field | Description |
|-------|-------------|
| `fireId` | Unique execution identifier |
| `scheduledAt` | When the fire was scheduled |
| `startedAt` | When execution began |
| `completedAt` | When execution finished |
| `status` | `SUCCESS`, `FAILED`, or `TIMEOUT` |
| `duration` | Execution time in milliseconds |
| `cost` | Total LLM cost (if cost tracking is enabled) |
| `conversationId` | The conversation used for this fire |
| `errorMessage` | Error details (if failed) |

### Viewing Fire History

```bash
# List recent fires for a schedule
curl http://localhost:7070/schedulestore/schedules/{scheduleId}/fires

# Get details of a specific fire
curl http://localhost:7070/schedulestore/schedules/{scheduleId}/fires/{fireId}
```

## Cluster Awareness

The `ScheduleFireExecutor` is cluster-aware — in multi-instance deployments, only one instance executes each scheduled fire. This prevents duplicate execution when running EDDI behind a load balancer.

## Best Practices

1. **Start with longer intervals** — Begin with hourly or daily schedules and increase frequency only if needed
2. **Use `persistent` strategy for stateful work** — Dream consolidation and monitoring agents benefit from accumulated context
3. **Set cost ceilings** — Always configure `maxBudgetPerConversation` for LLM-powered scheduled tasks
4. **Monitor fire logs** — Check for recurring failures that might indicate configuration issues
5. **Use cheap models for maintenance** — Background tasks rarely need expensive frontier models

## See Also

- [Managed Agents](managed-agents.md) — Intent-based agent routing
- [User Memory](user-memory.md) — Persistent user memory (target of dream consolidation)
- [LLM Configuration](langchain.md) — Agent configuration reference
- [Metrics](metrics.md) — Monitoring scheduled execution performance
