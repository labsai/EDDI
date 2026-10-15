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
            "parameters": { "modelName": "gpt-4o-mini" },
            "confidenceThreshold": 0.7,
            "timeoutMs": 10000,
            "inputPricePer1M": 0.15,
            "outputPricePer1M": 0.60
          },
          {
            "type": "openai",
            "parameters": { "modelName": "gpt-4o" },
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
| `judgeModel` | object | — | Model for the `judge_model` strategy: `{ "type": "...", "parameters": {...}, "inputPricePer1M": 0.15, "outputPricePer1M": 0.6 }` (prices optional; they make the judge's spend count toward `maxCostPerRun` and the reported cost; negative values fail deployment). Expected when `evaluationStrategy` is `judge_model`; if omitted or unbuildable, deployment logs a warning and confidence evaluation falls back to `heuristic` at runtime. |
| `heuristic` | object | — | Overrides for the `heuristic` strategy (see below). Optional. |
| `maxTotalDurationMs` | long | — | Wall-clock ceiling across the whole cascade. When reached, escalation stops and the best response so far is returned. Also caps each **buffered** step's timeout by the remaining budget — a step streamed live is exempt (see [Streaming the Final Step](#streaming-the-final-step)). |
| `maxCostPerRun` | double | — | Dollar ceiling for a single run: the steps' token cost (per-step pricing) **plus** the judge model's token cost (`judgeModel` pricing) **plus** the tracked cost of the tools agent-mode steps ran. When reached, escalation stops and the best response so far is returned. |
| `carryToolResultsOnEscalation` | boolean | `true` | Agent mode: when a step that already executed tools escalates, the next step starts from that step's tool calls and results instead of re-running the tool loop from the conversation alone (which executed every side-effecting tool again). Set `false` if a cross-provider escalation rejects the earlier provider's tool-call transcript. |
| `inputPricePer1M` / `outputPricePer1M` | double | — | Cascade-level default token pricing (steps may override). Used for cost reporting and the cost ceiling. |
| `returnBestAcrossSteps` | boolean | `false` | When true, if an earlier (escalated) step scored strictly higher than the finally-accepted step, the earlier step's response is returned. |
| `steps` | array | — | Ordered list of cascade steps (cheap → expensive) |

### Step fields

| Field | Type | Default | Description |
|---|---|---|---|
| `type` | string | task `type` | Provider type (e.g., `openai`, `anthropic`, `ollama`, or a named OpenAI-compatible type such as `xai`, `deepseek`, `moonshot`, `qwen`, `zhipu`, `minimax`, `openrouter`, `groq`). Resolved through global variables, like the task type. |
| `parameters` | object | `{}` | Provider-specific params. Merged over the base task parameters (step wins). Values are resolved for `${vault:...}` secrets, global variables, and Qute templates — parity with task params. The key naming the model is provider-specific: `modelName` for `openai`, the named OpenAI-compatible types, `anthropic`, `gemini`, `mistral`, `jlama` and `oracle-genai`; `model` for `ollama`; `modelId` for `bedrock`, `gemini-vertex` and `huggingface`; `deploymentName` for `azure-openai`. An unrecognised key is logged as having no effect and the step falls back to the provider default. |
| `confidenceThreshold` | Double | `null` | Minimum confidence to accept this step. Below it, escalate. **A non-last step should set a threshold** (a null threshold there is always-accepted, making later steps unreachable — flagged with a deploy-time warning). The last step's threshold is ignored (always accepted). |
| `timeoutMs` | long | `30000` | Per-step timeout in milliseconds, for **buffered** (non-streamed) steps — also bounded by the remaining `maxTotalDurationMs` budget. A step streamed live ignores this and instead runs under an internal ~120 s bound (see [Streaming the Final Step](#streaming-the-final-step)). |
| `inputPricePer1M` / `outputPricePer1M` | double | cascade default | Per-step token pricing (overrides the cascade-level default). |
| `maxFormatRetries` | int | task `responseValidation.maxRetries` | Same-model corrective re-asks this step gets before the cascade escalates (`0..3`). `0` for an expensive last-resort model. See [Format recovery and escalation](#format-recovery-and-escalation). |

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

`heuristic` config fields (all optional): `lowConfidencePhrases`, `refusalPhrases`, `shortLengthThreshold`, `shortScore`, `refusalScore`, `hedgingScore`, `defaultScore`. Localize the phrase lists for non-English deployments — without configured phrases, the evaluator cannot distinguish hedging from confidence and returns the default score. Configured score values are clamped to `[0.0, 1.0]`, so a mis-set value can't produce an out-of-range confidence.

### `judge_model`

A separate (typically cheap) model rates the response's confidence. Requires a `judgeModel` config block; the judge is built once via the model registry (with vault + global-variable resolution). If the judge cannot be built or the call fails, it falls back to `heuristic`.

```json
"judgeModel": { "type": "openai", "parameters": { "modelName": "gpt-4o-mini", "apiKey": "${vault:openai-key}" } }
```

### `none`

Always returns `1.0` — effectively disables confidence gating. The first step's response is always accepted. Useful for timeout/error recovery only, or A/B testing.

> Failure classes, the order of recovery across retry, re-ask, escalation and fallback, and a worked recipe are in
> [LLM Turn Resilience](llm-resilience.md).

### Cascade as failover

A cascade does not have to be a cost ladder. As **failover** it answers with the first model and only moves on
when that model fails or returns nothing usable: use `evaluationStrategy: "heuristic"` with a first-step
`confidenceThreshold` of `0.1` (the heuristic scores an empty reply `0.0` and everything else at least `0.2`), or
`evaluationStrategy: "none"` (confidence is always `1.0`, so low confidence never escalates; errors, timeouts and
unusable replies still do). Put a second model, ideally another version or provider, behind it.

## Error Handling

| Error Type | Behavior |
|---|---|
| **Rate limited (429) / 5xx (500 included)** | Retried **in-step** up to the task's `retry.maxAttempts` (with backoff, or the provider's own retry delay) before escalating to the next step. A provider delay longer than `retry.maxRetryAfterMs` skips the in-step retries and escalates at once. |
| **Spent quota (e.g. a per-day quota)** | Not retried — a 429 that names a per-day quota or `insufficient_quota` escalates immediately (failure class `QUOTA_EXHAUSTED`). |
| **Timeout** | The step is cancelled and the cascade escalates; a warning is logged. A step streamed live is exempt from cancellation — see [Streaming the Final Step](#streaming-the-final-step). |
| **Other errors** | Logged; escalate to the next step. |
| **Circuit open** | With the task's opt-in [`circuitBreaker`](langchain.md#circuit-breaker-skip-a-model-that-keeps-failing), a step whose model keeps failing with the same permanent class (invalid output, bad request, model not found; auth or quota at once) is **skipped without a call** until its cool-down ends, then probed once. Per model: the other steps still run. Trace `status: circuit_open`. If the last step is open too, the best response so far is returned, else the turn fails with an `LlmCircuitOpenException` (or serves the `onError` fallback). |
| **Duration / cost ceiling reached** | Stop escalating, return the best response so far. |
| **All steps fail** | Return the best response seen so far, or throw `LifecycleException` if none produced a result. |

Every escalation, re-ask and skipped step also leaves one structured INFO line (`LLM recovery ... action=escalate|retry|circuit_skip`) and counts `eddi.llm.recovery{action,outcome,trigger}`; see [LLM Turn Resilience](llm-resilience.md#in-the-log). A `${vars:...}` model name is shown **resolved** in the trace, the audit and the circuit key.

The cascade tracks the "best response" seen so far — if a later step fails but an earlier step produced a usable response, that response is returned rather than throwing.

## Format recovery and escalation

When the task's `responseValidation` has a `retry` policy (see [Recovery policies](langchain.md#recovery-policies-retry-where-it-helps)), a step whose reply is unusable (invalid JSON, empty, truncated, filtered) does **not** escalate immediately. The order is cheapest first:

1. the step's model answers;
2. the parser repairs what it can locally;
3. the **same model** is asked again with a corrective message, up to `maxRetries` (the step's `maxFormatRetries` wins), within the step's own `timeoutMs`: if less than `minAttemptMs` remains, the re-ask is skipped;
4. if the reply is still unusable, the cascade **escalates** to the next step with the reason **`invalid_output`**, and that step runs the same sequence with its own re-asks;
5. after the last step, `fallbackAction` applies (default: the configured fallback).

An escalation for this reason shows up as `status: escalated`, `reason: invalid_output`, `invalidOutput` (`empty`, `truncated`, `content_filter` or `invalid_json`) and `formatRetries` on the step's trace entry, as `eddi.llm.cascade.escalations{reason=invalid_output}`, as `eddi.llm.recovery{action=escalate, outcome=invalid_output}`, and as a `cascade_escalation` SSE event with `reason: "invalid_output"`. An unusable step never outranks a usable answer: if the last step is unusable and an earlier step produced a usable (low-confidence) answer, that answer is returned. A step that may re-ask is buffered even when it would otherwise stream live; an `llm_retry` SSE event `{reason, attempt}` precedes each re-ask. In agent mode only the step's final model call is re-asked; no tool runs twice.

## SSE Events

Three SSE event types provide real-time visibility, emitted through `ConversationEventSink` → `StreamingResponseHandler` → the `/agents/{conversationId}/stream` SSE endpoint:

| Event | Fields |
|---|---|
| `cascade_step_start` | `stepIndex`, `modelType`, `modelName`, `totalSteps` |
| `cascade_escalation` | `fromStep`, `toStep`, `confidence`, `threshold`, `reason`, `durationMs` |

`reason` is one of `low_confidence`, `timeout`, `error`, `retryable_error`, `invalid_output`. A third event, `llm_retry` (`reason`, `attempt`), is sent before a same-model re-ask (see [Format recovery and escalation](#format-recovery-and-escalation)).

## Streaming the Final Step

When streaming (SSE), a **guaranteed-accept step** is streamed **live** token-by-token: the last step, a step with a null `confidenceThreshold`, or a `none`-strategy step whose threshold is `≤ 1.0` (its confidence is always `1.0`, so it will always accept). This requires legacy (no-tools) mode, a non-wrapper strategy (`heuristic`, `judge_model`, or `none`), and a streaming-capable provider. Any step that could still escalate is always buffered instead (its full text is needed to evaluate confidence before deciding). In agent mode, the cascade emits the final response as a single chunk rather than streaming it live.

> **Bounds & consistency:** a live-streamed step is **not** subject to the per-step `timeoutMs` / `maxTotalDurationMs` cap — cancelling it mid-stream cannot stop the provider from continuing to emit tokens to the client, so instead it runs under the streaming executor's own internal bound (~120 s) and its result — even if partial at that bound — is the accepted answer. The client therefore never receives tokens for a response that is then replaced. `returnBestAcrossSteps` also never supersedes a step that was streamed live, for the same reason.

## Observability

### Trace

The full per-step trace is stored in conversation memory under `langchain:cascade:trace:<taskId>`. Each entry contains: `step`, `model`, `modelType`, `confidence`, `durationMs`, `tokenUsage` (`inputTokens`/`outputTokens`/`totalTokens`), `costUsd`, and `status` (`accepted`, `escalated`, `timeout`, `error`, `retryable_error`). A step that ended in a failure (`timeout`, `error`, `retryable_error`) also carries `failureClass` — one of `TRANSIENT`, `RATE_LIMITED`, `QUOTA_EXHAUSTED`, `AUTH`, `BAD_REQUEST`, `CONTEXT_TOO_LONG`, `MODEL_NOT_FOUND`, `TIMEOUT`, `UNKNOWN` (see [error classification](langchain.md#error-classification)) — and, when the provider asked callers to wait, `retryAfterMs`. Both are additive; `status` and `error` are unchanged. When `returnBestAcrossSteps` overrides the outcome, the step that would have been accepted is relabeled `superseded_by_best` and the earlier winning step is relabeled `accepted_as_best`, so the trace always agrees with the returned `stepUsed`.

### Response metadata

If `responseMetadataObjectName` is set, the cascade populates it with real token usage plus `cascadeCostUsd`, `cascadeModel` (`provider/model`), `cascadeStep`, and `cascadeConfidence`.

### Turn deadline and request timeouts

Inside an agent's [turn deadline](langchain.md#turn-deadline) the cascade's own budget shrinks to
`min(maxTotalDurationMs, remaining - reserve)`, each step's timeout is clamped to what is left, and a
further step is not started when less than 3 seconds (plus the reserve) remain — the best answer so
far is returned instead.

A step's `timeoutMs` also bounds the provider request itself: the model built for the step has its
`timeout` parameter clamped to the step's `timeoutMs` (rounded up to whole seconds up to 10 s, and to
5-second steps above, so a deadline that shrinks every turn does not create a model per millisecond).
Without that, cancelling the waiting future left the HTTP call running — billed, and holding a thread
— for the model's full `timeout`. A model `timeout` already at or below the step's is left alone.
Setting a model `timeout` longer than its step's `timeoutMs` is reported as a deploy-time warning;
set them equal. `eddi.llm.cancelled{scope=cascade_step}` counts steps cancelled on timeout, and the
trace of a clamped step carries `modelTimeoutClampedMs`.

### Metrics (Micrometer, `/q/metrics`)

`eddi.llm.cascade.executions` (tag `agentMode`), `eddi.llm.cascade.escalations` (tag `reason`), `eddi.llm.cascade.accepted.step` (tag `step`), `eddi.llm.cascade.step.latency` (timer, tag `provider`), `eddi.llm.cascade.confidence` (distribution), `eddi.llm.cascade.step.errors` (tags `provider`, `type`), `eddi.llm.failure` (tags `class` = the failure class, `model` = the step's model name; one count per failed step), `eddi.llm.cascade.tokens` / `eddi.llm.cascade.cost` (tag `provider`), `eddi.llm.cascade.ceiling.exceeded` (tag `kind` = `duration`|`cost`), `eddi.llm.turn.deadline.exceeded` (tag `stage`), `eddi.llm.cancelled` (tag `scope`).

## Audit Trail

When the audit collector is active, the cascade writes:

| Memory Key | Content |
|---|---|
| `audit:model_name` | The **actual** winning model, `provider/model` |
| `audit:cascade_model` | `provider/model (step N)` |
| `audit:confidence` | Confidence of the accepted step, as a `Double` |
| `audit:cost` | Turn-total dollar cost — configured cascade LLM pricing plus tracked tool cost (not cascade-exclusive) |
| `audit:token_usage` | Turn-total `{inputTokens, outputTokens, totalTokens}`, accumulated across every LLM call of the step (not cascade-exclusive) |

## Agent Mode

When `enableInAgentMode` is `true` (default), the cascade also works when tools (built-in, MCP, HTTP calls, A2A) are configured — each step can independently invoke the tool-calling loop. Because the `structured_output` wrapper cannot be injected around the tool loop, agent-mode confidence uses `judge_model` (if configured) or `heuristic`.

**Escalation does not replay tools.** When a step that ran tools escalates, the next step receives those tool calls and their results (`carryToolResultsOnEscalation`, default `true`) and continues from them, so a tool that already ran is not executed again just because a stronger model took over. The returned tool trace covers the calls of every step that *returned*, not only the winning step's, and a tool-approval pause raised by a later step carries those earlier steps' calls in its trace too. **The same holds for a step that times out or fails.** Its answer is lost, but the tools it had already completed are not: the tool loop records each call together with its result as the result arrives, and when the step times out or throws, the next step receives those completed calls (after anything that step was itself handed) and does not run them again. The step's trace entry records how many messages it contributed as `completedToolMessages`; its tool **cost** is counted toward `maxCostPerRun` as before. Its tool **trace** entries are not kept: the returned trace does not list the calls of a timed-out or failed step, even though the next step received them. For an audit of what ran, read `completedToolMessages` on that step's cascade trace entry alongside the returned tool trace. Only complete pairs are carried — if a model asked for two tools and the step died after the first, the next step sees the first call and its result, never an unanswered call. What is still *not* carried: a tool that was still **executing** when the step was cancelled. Its outcome is unknown, so the next step may run it again. A step that failed before any tool ran carries nothing, and a tool-approval pause is not a failure — it pauses the turn as described in [HITL](hitl.md).

**Across providers.** A carried call always has an id — calls from bindings that pass a null id through (Ollama, Gemini) get a synthetic one, paired with its result, because OpenAI and Anthropic reject a tool call without an id. A provider can still refuse another provider's transcript (a Gemini 3 model expects thought signatures other providers never produce, and gateways have their own rules). When a step that was handed a carried exchange has its **first** model request rejected as a bad request (HTTP 400/422, langchain4j `InvalidRequestException`), the cascade retries that step **once from the conversation alone** — the pre-carry behaviour, so its tools may run again — logs a WARN, records `carriedToolExchangeRejected` on the step's trace entry and counts `eddi.llm.cascade.step.errors{type="carried_exchange_rejected"}`. Nothing else triggers that retry: not a failure after the step's first request was answered (its tools may already have run, and a retry could repeat their side effects), not an authentication failure, an unknown model or a content filter (a retry cannot fix those and would hide them), and not a timeout or a transient (429/5xx) failure.

**Data boundary.** A carried exchange sends the earlier steps' tool calls **and their results** to the next step's provider. Without it, that provider would run the same tools and see equivalent results itself, and it already receives the conversation — but if the steps run on providers with different data-handling terms, treat the tool results as data you are handing to every provider in the cascade, and set `carryToolResultsOnEscalation: false` where that is not acceptable (at the cost of the replay described above). If a cross-provider cascade trips this on every turn, set `carryToolResultsOnEscalation: false`.

**HITL:** a tool-approval pause raised inside a cascade step records which step paused, and the resume continues on **that step's** model — not the task's base model.

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
        "parameters": { "modelName": "gpt-4o-mini" },
        "confidenceThreshold": 0.8,
        "timeoutMs": 15000,
        "inputPricePer1M": 0.15,
        "outputPricePer1M": 0.60
      },
      {
        "type": "anthropic",
        "parameters": { "modelName": "claude-sonnet-4-20250514" },
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
