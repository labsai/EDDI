# Multi-Model Cascading Routing

> Cost-optimized LLM execution via sequential model escalation with confidence-based routing.

## Overview

Multi-model cascading lets an LLM task try a fast, cheap model first and only escalate to a more expensive model if the confidence in the response is below a threshold. This reduces cost for queries that simpler models handle well, while preserving quality for hard queries.

**Flow:** `User query → Model A (fast/cheap) → Confidence check → if low → Model B (powerful) → Confidence check → ...`

The cascade is a self-contained branch inside `LlmTask` — no engine pipeline changes, and configs without `modelCascade` (or with `enabled: false`) behave exactly as before.

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
        "maxTotalDurationMs": 45000,
        "maxCostPerRun": 0.05,
        "steps": [
          {
            "type": "openai",
            "parameters": { "model": "gpt-4o-mini" },
            "confidenceThreshold": 0.7,
            "timeoutMs": 10000,
            "inputPricePer1M": 0.15,
            "outputPricePer1M": 0.60
          },
          {
            "type": "openai",
            "parameters": { "model": "gpt-4o" },
            "confidenceThreshold": null,
            "timeoutMs": 30000,
            "inputPricePer1M": 2.50,
            "outputPricePer1M": 10.00
          }
        ]
      }
    }
  ]
}
```

### Cascade fields

| Field | Type | Default | Description |
|---|---|---|---|
| `enabled` | boolean | `false` | Master toggle for cascading |
| `strategy` | string | `"cascade"` | Execution strategy. Only `cascade` (sequential) is implemented; `parallel` and any unknown value warn at deploy time and run sequentially. |
| `evaluationStrategy` | string | `"structured_output"` | How confidence is evaluated (see below) |
| `enableInAgentMode` | boolean | `true` | Whether cascade activates when tools/agents are configured |
| `judgeModel` | object | — | Model for the `judge_model` strategy: `{ "type": "...", "parameters": {...} }`. Expected when `evaluationStrategy` is `judge_model`; if omitted or unbuildable, deployment logs a warning and confidence evaluation falls back to `heuristic` at runtime. |
| `heuristic` | object | — | Overrides for the `heuristic` strategy (see below). Optional. |
| `maxTotalDurationMs` | long | — | Wall-clock ceiling across the whole cascade. When reached, escalation stops and the best response so far is returned. Also caps each step's timeout by the remaining budget. |
| `maxCostPerRun` | double | — | Dollar ceiling for a single run, computed from token usage × per-step pricing. When reached, escalation stops and the best response so far is returned. |
| `inputPricePer1M` / `outputPricePer1M` | double | — | Cascade-level default token pricing (steps may override). Used for cost reporting and the cost ceiling. |
| `returnBestAcrossSteps` | boolean | `false` | When true, if an earlier (escalated) step scored strictly higher than the finally-accepted step, the earlier step's response is returned. |
| `steps` | array | — | Ordered list of cascade steps (cheap → expensive) |

### Step fields

| Field | Type | Default | Description |
|---|---|---|---|
| `type` | string | task `type` | Provider type (e.g., `openai`, `anthropic`, `ollama`). Resolved through global variables, like the task type. |
| `parameters` | object | `{}` | Provider-specific params. Merged over the base task parameters (step wins). Values are resolved for `${vault:...}` secrets, global variables, and Qute templates — parity with task params. |
| `confidenceThreshold` | Double | `null` | Minimum confidence to accept this step. Below it, escalate. **A non-last step should set a threshold** (a null threshold there is always-accepted, making later steps unreachable — flagged with a deploy-time warning). The last step's threshold is ignored (always accepted). |
| `timeoutMs` | long | `30000` | Per-step timeout in milliseconds. Also bounded by the remaining `maxTotalDurationMs` budget. |
| `inputPricePer1M` / `outputPricePer1M` | double | cascade default | Per-step token pricing (overrides the cascade-level default). |

> **Merge note:** Step parameters are merged over base task parameters (step wins). Steps only specify overrides (e.g., a different `model`); shared params like `systemMessage` are inherited.
>
> **⚠️ Cross-provider credentials:** Because parameters are inherited, a step (or `judgeModel`) that targets a **different provider** than the task must supply **its own credentials** — otherwise it silently inherits the task's `apiKey`, which is wrong for a different provider and fails at runtime as a 401 (which the cascade then treats as an escalation). A different-provider step/judge that omits its own `apiKey` is flagged with a deploy-time warning. Give each cross-provider step its own full parameter set (`apiKey`, `baseUrl`, etc.). Same-provider steps may safely inherit the task's credentials.

## Confidence Evaluation Strategies

### `structured_output` (default)

Appends a JSON-format instruction to the system prompt asking the model to respond with a single JSON object:

```json
{ "response": "The actual answer...", "confidence": 0.85 }
```

The evaluator tries a **real JSON parse first** (Jackson), and only treats the response as a confidence wrapper when the whole response is a single JSON object — so a stray `"confidence": ...` inside legitimate answer content (e.g. a code sample) is **not** mistaken for the score. A regex fallback handles a malformed-but-object-shaped wrapper. If the response is not a JSON-object wrapper, it falls back to `heuristic`.

> **Agent mode / convertToObject:** the wrapper cannot be used with tools or with `convertToObject: true` (it would collide with the raw-schema JSON). In those cases the cascade automatically uses `judge_model` (if a judge is configured) or `heuristic`. The `convertToObject` + `structured_output` combination is flagged with a deploy-time warning; the agent-mode downgrade is logged at debug level at runtime (agent mode is only known when the task runs). See below.

### `heuristic`

Analyzes the response text for uncertainty signals. Phrases and thresholds are **configurable** via `heuristic` (English defaults). When no configured phrase matches, a language-agnostic default score is used.

| Signal | Confidence (default) |
|---|---|
| Empty/null response | `0.0` |
| Very short (< `shortLengthThreshold`, default 20 chars) | `shortScore` (`0.3`) |
| Refusal phrase (e.g. "I cannot fulfill") | `refusalScore` (`0.2`) |
| Hedging phrase (e.g. "I'm not sure") | `hedgingScore` (`0.4`) |
| No red flags | `defaultScore` (`0.8`) |

`heuristic` config fields (all optional): `lowConfidencePhrases`, `refusalPhrases`, `shortLengthThreshold`, `shortScore`, `refusalScore`, `hedgingScore`, `defaultScore`. Localize the phrase lists for non-English deployments — without configured phrases, the evaluator cannot distinguish hedging from confidence and returns the default score.

### `judge_model`

A separate (typically cheap) model rates the response's confidence. Requires a `judgeModel` config block; the judge is built once via the model registry (with vault + global-variable resolution). If the judge cannot be built or the call fails, it falls back to `heuristic`.

```json
"judgeModel": { "type": "openai", "parameters": { "model": "gpt-4o-mini", "apiKey": "${vault:openai-key}" } }
```

### `none`

Always returns `1.0` — effectively disables confidence gating. The first step's response is always accepted. Useful for timeout/error recovery only, or A/B testing.

## Error Handling

| Error Type | Behavior |
|---|---|
| **Rate limited (429) / 5xx** | Retried **in-step** up to the task's `retry.maxAttempts` (with backoff) before escalating to the next step. |
| **Timeout** | The step is cancelled and the cascade escalates; a warning is logged. |
| **Other errors** | Logged; escalate to the next step. |
| **Duration / cost ceiling reached** | Stop escalating, return the best response so far. |
| **All steps fail** | Return the best response seen so far, or throw `LifecycleException` if none produced a result. |

The cascade tracks the "best response" seen so far — if a later step fails but an earlier step produced a usable response, that response is returned rather than throwing.

## SSE Events

Two SSE event types provide real-time visibility, emitted through `ConversationEventSink` → `StreamingResponseHandler` → the `/agents/{conversationId}/stream` SSE endpoint:

| Event | Fields |
|---|---|
| `cascade_step_start` | `stepIndex`, `modelType`, `modelName`, `totalSteps` |
| `cascade_escalation` | `fromStep`, `toStep`, `confidence`, `threshold`, `reason`, `durationMs` |

`reason` is one of `low_confidence`, `timeout`, `error`, `retryable_error`.

## Streaming the Final Step

When streaming (SSE), the always-accepted final step is streamed **live** token-by-token — as long as it runs in legacy (no-tools) mode, uses a non-wrapper strategy (`heuristic`, `judge_model`, or `none`), and the provider supports streaming. Earlier steps are buffered (their full text is needed to evaluate confidence). In agent mode, the cascade emits the final response as a single chunk.

> **Bounds & consistency:** a live-streamed step is **not** subject to the per-step / duration timeout (which would otherwise cancel it mid-stream while the provider keeps emitting tokens); it runs under the streaming executor's own internal bound (~120 s) and its result — even if partial at that bound — is the accepted answer, so the client never receives tokens for a response that is then replaced. `returnBestAcrossSteps` also never supersedes a step that was streamed live. Only genuinely-terminal steps (last step, null-threshold step, or `none`-strategy step) are streamed live.

## Observability

### Trace

The full per-step trace is stored in conversation memory under `langchain:cascade:trace:<taskId>`. Each entry contains: `step`, `model`, `modelType`, `confidence`, `durationMs`, `tokenUsage` (`inputTokens`/`outputTokens`/`totalTokens`), `costUsd`, and `status` (`accepted`, `escalated`, `timeout`, `error`, `retryable_error`).

### Response metadata

If `responseMetadataObjectName` is set, the cascade populates it with real token usage plus `cascadeCostUsd`, `cascadeModel` (`provider/model`), `cascadeStep`, and `cascadeConfidence`.

### Metrics (Micrometer, `/q/metrics`)

`eddi.llm.cascade.executions` (tag `agentMode`), `eddi.llm.cascade.escalations` (tag `reason`), `eddi.llm.cascade.accepted.step` (tag `step`), `eddi.llm.cascade.step.latency` (timer, tag `provider`), `eddi.llm.cascade.confidence` (distribution), `eddi.llm.cascade.step.errors` (tags `provider`, `type`), `eddi.llm.cascade.tokens` / `eddi.llm.cascade.cost` (tag `provider`), `eddi.llm.cascade.ceiling.exceeded` (tag `kind` = `duration`|`cost`).

## Audit Trail

When the audit collector is active, the cascade writes:

| Memory Key | Content |
|---|---|
| `audit:model_name` | The **actual** winning model, `provider/model` |
| `audit:cascade_model` | `provider/model (step N)` |
| `audit:cascade_confidence` | Confidence of the accepted response |
| `audit:cascade_cost` | Aggregate run cost in dollars |
| `audit:cascade_token_usage` | Token usage of the accepted step |

## Agent Mode

When `enableInAgentMode` is `true` (default), the cascade also works when tools (built-in, MCP, HTTP calls, A2A) are configured — each step can independently invoke the tool-calling loop. Because the `structured_output` wrapper cannot be injected around the tool loop, agent-mode confidence uses `judge_model` (if configured) or `heuristic`.

**Cancellation:** when a step times out, the orchestrator checks for interruption between tool-loop iterations and before each tool, so it stops launching further side-effectful tools. A tool already in flight when the timeout fires may still complete — keep cascade-in-agent-mode tools idempotent where possible.

When `enableInAgentMode` is `false`, cascading is skipped in agent mode and the standard single-model path is used.

## Configure-time Validation

Cascade configs are validated at deploy (`LlmTask.configure`), in two tiers so an upgrade never stops a previously-loading agent from deploying:

- **Hard error (deployment fails)** — only the **new** numeric fields, since no stored config predating this release can contain them: non-positive `maxTotalDurationMs`, negative `maxCostPerRun`, negative per-step / cascade `inputPricePer1M` / `outputPricePer1M`.
- **Warning (logged, deployment proceeds)** — conditions older releases tolerated at load and that still fail/degrade at runtime exactly as before: empty steps, unknown `strategy`, unknown `evaluationStrategy`, `judge_model` without a `judgeModel`, `confidenceThreshold` outside `[0.0, 1.0]`, a non-last step with a null threshold (dead-step trap), non-positive `timeoutMs`, a cross-provider step/judge missing its own `apiKey`, and `convertToObject: true` with `structured_output` (auto-downgraded at runtime).

## Backward Compatibility

- Configs without `modelCascade` work exactly as before.
- `enabled: false` (default) keeps standard execution.
- All new config fields are optional with today's behavior as defaults.
- The cascade lives entirely within `LlmTask`; `StreamingResponseHandler`'s cascade methods are `default`, so other implementers are unaffected.

## Example: Cost Optimization

A 3-tier cascade for a customer support agent, with pricing so savings are measurable:

```json
{
  "modelCascade": {
    "enabled": true,
    "evaluationStrategy": "heuristic",
    "maxCostPerRun": 0.02,
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
        "timeoutMs": 15000,
        "inputPricePer1M": 0.15,
        "outputPricePer1M": 0.60
      },
      {
        "type": "anthropic",
        "parameters": { "model": "claude-sonnet-4-20250514" },
        "confidenceThreshold": null,
        "timeoutMs": 30000,
        "inputPricePer1M": 3.00,
        "outputPricePer1M": 15.00
      }
    ]
  }
}
```

This routes simple FAQs to a local Ollama model (free), medium queries to GPT-4o-mini, and only complex queries to Claude Sonnet — with per-turn cost recorded in the trace, audit ledger, and metrics so the savings are provable.
