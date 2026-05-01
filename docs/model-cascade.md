# Multi-Model Cascading Routing

> Cost-optimized LLM execution via sequential model escalation with confidence-based routing.

## Overview

Multi-model cascading allows an LLM task to try a fast, cheap model first and only escalate to a more expensive model if the confidence in the response is below a threshold. This dramatically reduces costs for queries that simpler models can handle well.

**Flow:** `User query → Model A (fast/cheap) → Confidence check → if low → Model B (powerful) → Confidence check → ...`

## Configuration

Cascading is configured per-task in a `langchain.json` resource:

```json
{
  "tasks": [
    {
      "id": "cascade-task",
      "type": "openai",
      "actions": ["*"],
      "parameters": {
        "systemMessage": "You are a helpful assistant.",
        "apiKey": "${vault:openai-key}",
        "logSizeLimit": "10"
      },
      "modelCascade": {
        "enabled": true,
        "strategy": "cascade",
        "evaluationStrategy": "structured_output",
        "enableInAgentMode": true,
        "steps": [
          {
            "type": "openai",
            "parameters": { "model": "gpt-4o-mini" },
            "confidenceThreshold": 0.7,
            "timeoutMs": 10000
          },
          {
            "type": "openai",
            "parameters": { "model": "gpt-4o" },
            "confidenceThreshold": null,
            "timeoutMs": 30000
          }
        ]
      }
    }
  ]
}
```

### Configuration Fields

| Field | Type | Default | Description |
|---|---|---|---|
| `enabled` | boolean | `false` | Master toggle for cascading |
| `strategy` | string | `"cascade"` | Execution strategy. `cascade` = sequential. `parallel` reserved for future. |
| `evaluationStrategy` | string | `"structured_output"` | How confidence is evaluated (see below) |
| `enableInAgentMode` | boolean | `true` | Whether cascade activates when tools/agents are configured |
| `steps` | array | — | Ordered list of cascade steps |

### Step Configuration

| Field | Type | Default | Description |
|---|---|---|---|
| `type` | string | — | Provider type (e.g., `openai`, `anthropic`, `ollama`) |
| `parameters` | object | `{}` | Provider-specific params. Merged with base task parameters. |
| `confidenceThreshold` | Double | `null` | Minimum confidence to accept response. `null` = always accept (final step). |
| `timeoutMs` | long | `30000` | Per-step timeout in milliseconds |

> **Note:** Step parameters are **merged** with the base task parameters. Steps only need to specify overrides (e.g., a different `model`). Shared params like `apiKey` or `systemMessage` are inherited.

## Confidence Evaluation Strategies

### `structured_output` (default)

Appends a JSON format instruction to the system prompt, asking the model to respond with:

```json
{ "response": "The actual answer...", "confidence": 0.85 }
```

The evaluator parses the JSON, extracts the confidence score, and uses the `response` field as the actual output. Falls back to `heuristic` if JSON parsing fails.

### `heuristic`

Analyzes the response text for signals of uncertainty:

| Signal | Confidence |
|---|---|
| Empty/null response | `0.0` |
| Very short (< 20 chars) | `0.3` |
| Refusal phrases ("I cannot", "I'm sorry but") | `0.2` |
| Hedging language ("I'm not sure", "might be") | `0.4` |
| Confident, full response | `0.8` |

### `judge_model`

Sends the response to a secondary LLM (the "judge") which evaluates the quality and assigns a confidence score. Falls back to `heuristic` if no judge model is configured. *(Requires additional configuration — see advanced usage.)*

### `none`

Always returns `1.0` — effectively disables confidence gating. The first step's response is always accepted. Useful for A/B testing or when you want cascading for timeout/error recovery only.

## Error Handling

The cascade handles errors gracefully:

| Error Type | Behavior |
|---|---|
| **Rate limited (429)** | Auto-escalate to next step |
| **Server error (503)** | Auto-escalate to next step |
| **Timeout** | Auto-escalate to next step, log warning |
| **Other errors** | Log warning, escalate to next step |
| **All steps fail** | Throw `LifecycleException` with full trace |

The cascade also tracks the "best response" seen so far. If a later step fails but an earlier step produced a usable response, that response is returned rather than throwing an error.

## SSE Events

Two new SSE event types provide real-time visibility into cascade execution:

| Event | Fields | When |
|---|---|---|
| `cascade_step_start` | `stepIndex`, `modelType` | Before each step begins |
| `cascade_escalation` | `fromStep`, `toStep`, `confidence`, `reason` | When escalating to next step |

These events are emitted through the existing `ConversationEventSink` interface and are available on the `/agents/{conversationId}/stream` SSE endpoint.

## Audit Trail

When the audit collector is active, the cascade writes:

| Memory Key | Content |
|---|---|
| `audit:cascade_model` | The model type + params of the step that produced the final response |
| `audit:cascade_confidence` | The confidence score of the accepted response |
| `cascade:trace` | Full execution trace (all steps attempted, timing, errors) |

## Agent Mode

When `enableInAgentMode` is `true` (default), the cascade also works when tools (built-in, MCP, HTTP calls, A2A) are configured. Each cascade step can independently invoke the tool-calling loop via `AgentOrchestrator`.

When `enableInAgentMode` is `false`, cascading is skipped in agent mode and the standard single-model execution path is used.

## Backward Compatibility

- Existing configurations without `modelCascade` work exactly as before — no changes needed
- Setting `enabled: false` (default) keeps standard execution
- The cascade is entirely within `LlmTask` — no engine pipeline changes

## Example: Cost Optimization

A typical 3-tier cascade for a customer support agent:

```json
{
  "modelCascade": {
    "enabled": true,
    "evaluationStrategy": "heuristic",
    "steps": [
      {
        "type": "ollama",
        "parameters": { "model": "llama3.2:3b", "baseUrl": "http://localhost:11434" },
        "confidenceThreshold": 0.7,
        "timeoutMs": 5000
      },
      {
        "type": "openai",
        "parameters": { "model": "gpt-4o-mini" },
        "confidenceThreshold": 0.8,
        "timeoutMs": 15000
      },
      {
        "type": "anthropic",
        "parameters": { "model": "claude-sonnet-4-20250514" },
        "confidenceThreshold": null,
        "timeoutMs": 30000
      }
    ]
  }
}
```

This routes simple FAQs to a local Ollama model (free), medium queries to GPT-4o-mini (~$0.15/1M tokens), and only complex queries to Claude Sonnet ($3/1M tokens).
