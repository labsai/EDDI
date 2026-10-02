# Memory Policy — Commit Flags & Strict Write Discipline

## Overview

Memory Policy controls what happens when a lifecycle task fails during a conversation turn. By default, failed task output (stack traces, HTTP error bodies, raw error messages) is written to conversation memory and becomes visible to the LLM on subsequent turns. This pollutes the LLM's context with noise it can't act on.

**Strict Write Discipline** solves this by marking failed task output as **uncommitted** — excluded from the LLM's view — and injecting a concise **error digest** that the LLM can understand and react to.

## Configuration

Memory Policy is configured at the agent level in the agent configuration JSON:

```json
{
  "memoryPolicy": {
    "strictWriteDiscipline": {
      "enabled": true,
      "onFailure": "digest"
    }
  }
}
```

### Options

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `enabled` | boolean | `false` | Enable strict write discipline — while this is `false`, `onFailure` has no effect |
| `onFailure` | string | `"digest"` | What to do with failed task output |
| `continueOnFailure` | boolean | `false` | Keep running the remaining workflow tasks after the failure is recorded, so a later task can answer **in the same turn** — see [Same-turn fallback](#same-turn-fallback). Off, the turn ends in `ERROR` at the failing task |

### Failure Modes

| Mode | Behavior |
|------|----------|
| `digest` | Default mode — failed task output is marked uncommitted (hidden from LLM). A concise error digest is injected so the LLM knows what failed and can adapt. **Recommended.** |
| `exclude_all` | Failed task output is marked uncommitted. No error digest is injected. The LLM sees nothing about the failure. |
| `keep_all` | Opt-in backwards-compatible mode — failed task output remains committed and visible to the LLM. The `task_failed_<taskId>` action is still emitted. |

## How It Works

### Without Strict Write Discipline (Default)

```text
Turn 1: User asks "What's the weather?"
  → WeatherTool fails with HTTP 503
  → Raw error: "java.net.ConnectException: Connection refused..."
  → Error is stored in memory
  → LLM sees full stack trace on next turn
  → LLM may hallucinate about server errors or try to "fix" the code
```

### With Strict Write Discipline (`digest` mode)

```text
Turn 1: User asks "What's the weather?"
  → The httpcalls task (the weather API call) fails with HTTP 503
  → Raw error is marked as UNCOMMITTED (hidden from LLM)
  → Error digest injected: {"type": "errorDigest", "taskId": "ai.labs.httpcalls", "taskType": "httpCalls",
                            "text": "Task 'eddi://ai.labs.httpcalls' failed: ..."}
  → Action emitted: "task_failed_ai.labs.httpcalls"
  → LLM sees concise digest on next turn
  → LLM can respond: "I'm sorry, I couldn't check the weather right now."
  → Behavior rules can react to "task_failed_ai.labs.httpcalls" action
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

`<taskId>` is the id of the **lifecycle task** that failed — the workflow step type without its `eddi://` prefix, such as `ai.labs.httpcalls`, `ai.labs.mcpcalls` or `ai.labs.llm` — not the name of the HTTP call, tool or LLM task configured inside it. Every failure of that step type emits the same action: `task_failed_ai.labs.httpcalls` does not say which of the configured calls failed.

## Error Digest Format

The error digest is stored as a special output type:

```json
{
  "type": "errorDigest",
  "taskId": "ai.labs.httpcalls",
  "taskType": "httpCalls",
  "text": "Task 'eddi://ai.labs.httpcalls' failed: <reason>"
}
```

`text` is always `Task '<eddi:// task id>' failed: ` followed by the reason. The reason is kept short
on purpose: URLs are replaced with `[url]`, stack frames and fully-qualified exception class names are
stripped, a provider's raw JSON error body is reduced to its `message`, secrets are redacted, and the
reason is then cut at 200 characters (with `...` appended when cut). The cap applies to the reason
only — the `Task '…' failed: ` prefix comes on top of it. A failing model call reads, for example,
`Task 'eddi://ai.labs.llm' failed: Chat model execution failed: model 'x' not found`.

The same text is also stored as step data under `taskError:eddi://<taskId>`.

The UI can render error digests with distinct styling (warning icon, collapsible panel). The LLM receives the concise `text` summary rather than raw error noise.

### A failed turn always tells the caller why

The same `taskErrors` entry is written **without** strict write discipline too — so by default, not
only in `digest` mode. Before, a turn whose task failed (a model provider rejecting the request, an
unreachable API) came back as `conversationState: "ERROR"` with an output that was simply empty, on
the plain and the streaming path alike: the client had no reply and no reason, and the reason was only
in the server log.

In that default mode the entry is for the **caller only**. Nothing reads `taskErrors` back into what
the model sees, no data is stored and no `task_failed_*` action is emitted — those remain strict-write
behaviour. With strict write enabled, the modes above decide as before, so `exclude_all` still
reports nothing.

The `text` names the task and the most specific reason in the failure's cause chain — for a provider
rejection, the `message` from the provider's error body, e.g. `` Task 'eddi://ai.labs.llm' failed:
Streaming chat failed: `temperature` is deprecated for this model. `` It is redacted with the same
secret filter as the logs, URLs are removed, and the reason is capped at 200 characters (the
`Task '…' failed: ` prefix is not counted). The streaming
`task_failed` event carries the same unwrapped reason.

The Manager's chat shows it in place of the empty reply. The Chat UI shows end users its own generic
"Something went wrong" banner with **Try again** instead, and never the provider's text.

## Same-turn fallback

By default a failing task still ends the turn: the pipeline stops, the conversation is in `ERROR`, and
the reply is empty. The digest and the `task_failed_<taskId>` action are then only visible to the
**next** turn's behavior rules — and when the failing task is the LLM itself, the turn after the
fallback runs it again.

With `continueOnFailure: true` the remaining tasks keep running after the failure is recorded, so the
turn can answer for itself:

- an output set keyed on `task_failed_<taskId>` renders a fallback message in the same turn;
- an LLM task placed after a failed HTTP call sees the digest and can explain what went wrong.

```json
{
  "memoryPolicy": {
    "strictWriteDiscipline": { "enabled": true, "onFailure": "digest", "continueOnFailure": true }
  }
}
```

```json
{
  "outputSet": [ {
    "action": "task_failed_ai.labs.llm",
    "outputs": [ { "valueAlternatives": [ { "type": "text", "text": "Sorry — I can't answer right now. Please try again." } ] } ]
  } ]
}
```

A graceful-shutdown interrupt is never continued past.

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
          "type": "actionmatcher",
          "configs": {
            "actions": "task_failed_ai.labs.httpcalls"
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
4. **Set `keep_all` for development** — Full error output is useful during agent development and debugging

## See Also

- [Architecture](architecture.md) — Lifecycle pipeline and conversation memory model
- [Conversation Memory](conversation-memory.md) — How data flows through the pipeline
- [Behavior Rules](behavior-rules.md) — Routing based on actions and conditions
