# Memory Policy — Commit Flags & Strict Write Discipline

## Overview

Memory Policy controls what happens when a lifecycle task fails during a conversation turn. By default, failed task output (stack traces, HTTP error bodies, raw error messages) is written to conversation memory and becomes visible to the LLM on subsequent turns. This pollutes the LLM's context with noise it can't act on.

**Strict Write Discipline** solves this by marking failed task output as **uncommitted** — excluded from the LLM's view — and injecting a concise **error digest** that the LLM can understand and react to.

## Configuration

Memory Policy is configured at the agent level in the agent configuration JSON:

```json
{
  "agentConfiguration": {
    "memoryPolicy": {
      "strictWriteDiscipline": {
        "enabled": true,
        "onFailure": "digest"
      }
    }
  }
}
```

### Options

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `enabled` | boolean | `false` | Enable strict write discipline |
| `onFailure` | string | `"digest"` | What to do with failed task output |

### Failure Modes

| Mode | Behavior |
|------|----------|
| `digest` | Failed task output is marked uncommitted (hidden from LLM). A concise error digest is injected so the LLM knows what failed and can adapt. **Recommended.** |
| `exclude_all` | Failed task output is marked uncommitted. No error digest is injected. The LLM sees nothing about the failure. |
| `keep_all` | Default behavior — failed task output remains committed and visible to the LLM. Backwards-compatible. |

## How It Works

### Without Strict Write Discipline (Default)

```
Turn 1: User asks "What's the weather?"
  → WeatherTool fails with HTTP 503
  → Raw error: "java.net.ConnectException: Connection refused..."
  → Error is stored in memory
  → LLM sees full stack trace on next turn
  → LLM may hallucinate about server errors or try to "fix" the code
```

### With Strict Write Discipline (`digest` mode)

```
Turn 1: User asks "What's the weather?"
  → WeatherTool fails with HTTP 503
  → Raw error is marked as UNCOMMITTED (hidden from LLM)
  → Error digest injected: {"type": "errorDigest", "taskId": "weather", "text": "Weather lookup failed"}
  → Action emitted: "task_failed_weather"
  → LLM sees concise digest on next turn
  → LLM can respond: "I'm sorry, I couldn't check the weather right now."
  → Behavior rules can react to "task_failed_weather" action
```

## Commit Flags

Every piece of data in conversation memory (`IData<T>`) carries a **committed** flag:

| Flag | Meaning |
|------|---------|
| `committed = true` (default) | Data is included in the LLM's context window |
| `committed = false` | Data is stored in memory but excluded from the LLM's context |

When strict write discipline is enabled and a task fails:
1. All data written by the failed task during that turn is marked `committed = false`
2. The conversation output added by the failed task is rolled back
3. An error digest replaces the raw output
4. A `task_failed_<taskId>` action is emitted for behavior rule routing

## Error Digest Format

The error digest is stored as a special output type:

```json
{
  "type": "errorDigest",
  "taskId": "ai.labs.apicalls",
  "text": "API call to payment-service failed: HTTP 500"
}
```

The UI can render error digests with distinct styling (warning icon, collapsible panel). The LLM receives the concise `text` summary rather than raw error noise.

## Behavior Rule Integration

When a task fails with strict write discipline enabled, the action `task_failed_<taskId>` is emitted. You can use this in behavior rules to route to fallback logic:

```json
{
  "behaviorRules": [
    {
      "name": "Handle Weather Failure",
      "actions": ["fallback_response"],
      "conditions": [
        {
          "type": "actionMatcher",
          "values": {
            "actions": "task_failed_ai.labs.apicalls"
          }
        }
      ]
    }
  ]
}
```

## Best Practices

1. **Enable `digest` mode for production agents** — It prevents LLM context pollution while preserving observability
2. **Use behavior rules for graceful degradation** — React to `task_failed_*` actions to provide fallback responses
3. **Monitor error digests** — They appear in conversation memory for debugging even though the LLM only sees the summary
4. **Leave `keep_all` for development** — Full error output is useful during agent development and debugging

## See Also

- [Architecture](architecture.md) — Lifecycle pipeline and conversation memory model
- [Conversation Memory](conversation-memory.md) — How data flows through the pipeline
- [Behavior Rules](behavior-rules.md) — Routing based on actions and conditions
