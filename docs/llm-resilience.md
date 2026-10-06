# LLM Turn Resilience

A conversation turn that calls a language model has many ways to go wrong: the provider is slow or down, the
key is revoked, the model answers in prose instead of JSON, the reply is empty, the caller gives up before the
answer arrives. EDDI recovers from each of these in a fixed order, always tries to give the user an answer, and
leaves a trail an operator can read afterwards. This page is the map: what can fail, what the engine does about
it, which settings control it and where to look when a turn did not go as expected.

Everything here is **optional and off by default** unless stated otherwise. An agent with no resilience
settings behaves as it did before, with two exceptions that are bug fixes: a reply that is not valid JSON no
longer fails the turn under `convertToObject`, and an HTTP 500 from a provider is now retried.

> Detail pages: [LLM Integration](langchain.md) (every field), [Model Cascade](model-cascade.md),
> [Metrics](metrics.md), [Conversations](conversations.md) (the caller-facing contract).

## Failure taxonomy

Every failure is classified into one class by `LlmFailureClassifier`, from the HTTP status **and** the provider's
error body (a status alone cannot tell a rate limit from a spent daily quota). The class decides what is done.

| Class | Examples | What the engine does |
| --- | --- | --- |
| Transient provider fault (`TRANSIENT`, `RATE_LIMITED`, `TIMEOUT`) | 429 per-minute rate, 500/502/503/504/529, connect or read timeout, DNS | Retried in the same model call (`retry`), honouring the provider's own delay; then a cascade escalates |
| Hang | the provider accepts the request and never answers | The attempt is bounded by the turn deadline and by the cascade step's `timeoutMs`; the abandoned call is cancelled; the cascade escalates |
| Permanent request fault (`BAD_REQUEST`, `MODEL_NOT_FOUND`) | 400 invalid argument, 404 retired model | Not retried; the cascade escalates; repeated ones open the circuit breaker |
| Credentials / quota (`AUTH`, `QUOTA_EXHAUSTED`) | 401/403, a **per-day** 429 | Not retried (a spent quota cannot succeed); the cascade escalates at once; the circuit opens on the first one |
| Context too long (`CONTEXT_TOO_LONG`) | 400 "input token count exceeds" | With `onContextTooLong: "retry"`, one re-send with the history window halved |
| Empty, truncated or filtered reply | finish reason `SAFETY`, `MAX_TOKENS`, blank text | `responseValidation` action per case; `retry` re-asks (truncation with a larger token cap) |
| Not JSON | prose, a code fence, text around the object | Local repair (free), then `onInvalidJson` |
| Malformed JSON | cut off mid-object, bad syntax | Never throws; the raw string is kept, then `onInvalidJson` |
| Wrong shape | valid JSON without the field the template reads, wrong type, `""` | Outcome `schema_mismatch` (`responseSchema` / `nonBlankFields`), then `onSchemaMismatch` |
| Dependency | an httpcall the turn needs is slow | The call's timeout and its retries are clamped to the remaining turn budget |
| Caller gives up | the caller times out at 60 s while the engine still works | The turn deadline ends the work in time; an `Idempotency-Key` retry gets the answer instead of `409` |
| Systemic | key revoked, model retired, prompt that always yields prose | The per-model circuit breaker stops calling it for everyone for a while |
| Everything failed | all retries, steps and re-asks exhausted | `onError: "fallback"` serves a configured answer with HTTP 200; the default `error` fails the turn with a structured `error` object |

## Recovery order

Recovery always runs from cheapest to most expensive, and each step is skipped when its budget is gone:

```text
model answers
  1. local repair          fence stripping / extracting the object from prose      free
  2. transport retry       retry: same request, provider delay honoured             transient faults only
  3. same-model re-ask     responseValidation "retry" + corrective message          up to maxRetries (default 1)
  4. escalate              next cascade step, which repeats 1-3 with its own re-asks
  5. fallback              fallbackAction / onError: fallback                       the user gets an answer
```

The order is deliberate: a one-off format slip is usually fixed by the model that made it, and escalating
first would pay a different, possibly slower or dearer model for every hiccup. A model that keeps failing the
same way is taken out of rotation altogether by the [circuit breaker](#circuit-breaker), which also switches off
step 3 for it.

Two rules hold at every step:

- **Tools are never re-run.** Only the final model call is re-asked, over a transcript that already holds the
  tool results. A tool request inside a re-asked answer is ignored.
- **The model never sees the fallback.** The fallback text is shown and stored, but left out of the history
  the next turn sends to the model.

## Configuration surface

### Agent level

| Setting | Meaning | Default |
| --- | --- | --- |
| `turnDeadlineMs` | Wall-clock budget of one turn, counted from the request's arrival. Every layer spends from it | unset (no deadline) |
| `turnDeadlineReserveMs` | Kept back for producing the fallback and persisting the turn | `1500` |

### LLM task level (`langchain.json`)

| Setting | Meaning | Default |
| --- | --- | --- |
| `retry.maxAttempts` | Total attempts of one model call, including the first. Clamped to 1..10. **An LLM task with no `retry` block still retries**; `1` turns it off | `3` |
| `retry.backoffDelayMs` / `backoffMultiplier` / `maxBackoffDelayMs` | Exponential backoff between attempts | `1000` / `2.0` / `10000` |
| `retry.honorRetryAfter` | Sleep the delay the provider asked for instead of the backoff | `true` |
| `retry.maxRetryAfterMs` | Longest provider-requested wait accepted; a longer one stops retrying **without sleeping** so a cascade escalates at once (engine ceiling 30000) | `10000` |
| `responseValidation.enabled` | Master switch of the `responseValidation` policies below (`retry` and the validation `fallback` need it) | `false` |
| `responseValidation.onEmpty` / `onTruncation` / `onContentFilter` | `ignore`, `warn`, `fallback`, `error` or `retry` | `warn` |
| `responseValidation.onInvalidJson` / `onSchemaMismatch` | As above, for a `convertToObject` reply that is not JSON / breaks the shape | `ignore` |
| `responseValidation.onContextTooLong` | `retry` re-sends once with the history window halved; anything else lets the failure propagate | `error` |
| `responseValidation.onRefusal` / `onStreamingTimeout` | `retry` is **not** supported here (treated as `warn`) | `ignore` / `warn` |
| `responseValidation.maxRetries` | Same-model re-asks per model (per cascade step), shared by every `retry` policy. Clamped to 0..3. A cascade step overrides it with `maxFormatRetries` | `1` |
| `responseValidation.truncationRetryFactor` | Output-token cap multiplier for the one truncation re-ask (1..4) | `2` |
| `responseValidation.correctiveMessage` | The user message of an invalid-JSON re-ask; `{reason}` is replaced with EDDI's own reason. Not a template | built-in sentence |
| `responseValidation.maxRetryCostUsd` | Dollar cap on what the re-asks of one model may cost; needs `inputPricePer1M` / `outputPricePer1M` | none |
| `responseValidation.minAttemptMs` | A re-ask only starts if at least this much time remains for the model | `3000` |
| `responseValidation.fallbackAction` | What happens when re-asks and cascade steps did not help: `fallback`, `error` or `warn` | `fallback` |
| `responseValidation.fallbackMessage` | The fallback text; a Qute template over the task's template data | "I'm sorry, I wasn't able to generate a complete response. Please try again." |
| `responseValidation.fallbackField` | Under `convertToObject`, store the fallback as `{"<field>": "<message>"}` so output templates render it unchanged | none |
| `responseValidation.fallbackQuickReplies` | Quick replies added next to the fallback | none |
| `onError.action` | `fallback` absorbs a failed model phase and serves the fallback (HTTP 200, conversation not `ERROR`); `error` fails the turn | `error` |
| `circuitBreaker.enabled` / `window` / `threshold` / `coolDownMs` | Per-model circuit breaker | `false` / `10` / `8` / `60000` |
| `nonBlankFields` | Fields of the parsed reply that must hold a non-blank string (`"htmlResponseText"`, `"answer.text"`) | none |
| `parameters.responseSchema` | A JSON Schema the reply must match; also sent natively to providers that support it | none |
| `jsonResponseFormat` | `auto`, `on` or `off`: whether the request carries the provider's JSON mode | `auto` |
| `modelCascade.steps[i].maxFormatRetries` | Per-step override of `maxRetries` | task value |
| `modelCascade.steps[i].timeoutMs` | Per-step timeout; also caps the step model's request `timeout` | `30000` |

### Per request

| Header | Meaning |
| --- | --- |
| `X-EDDI-Turn-Deadline-Ms` | How long the caller will wait. With an agent `turnDeadlineMs` it can only **shorten** it; with none it enables a deadline by itself (capped at 10 minutes). A malformed or non-positive value is ignored |
| `Idempotency-Key` (or `X-EDDI-Request-Id`; the former wins) | Makes a retried `say` safe: the same key while the turn runs waits for it, after it completed within `eddi.turns.idempotency.ttl-seconds` (default `600`, `0` = off) returns the stored answer. Keys are per conversation, 1 to 128 printable ASCII characters, else `400`. A failed turn is not remembered |

Both headers are read on `say`, the managed-conversation message and the streaming `say`; the deadline header
also on the OpenAI-compatible `/v1` chat endpoint. A failed turn answers with a structured `error`
(`code`, `retryable`, `retryAfterMs`, `message`) and a `Retry-After` header when the provider gave a hint;
see [Conversations](conversations.md#failed-turns-the-error-object).

## Recipe: a JSON agent behind a 60-second caller

The caller gives up at 60 seconds. The agent answers in JSON that an output template renders, so a missing or
blank `htmlResponseText` would show an empty bubble.

```text
turnDeadlineMs 55000 (reserve 1500)
cascade          step 1: primary model, threshold 0.1, timeout 25 s
                 step 2: a second model (other version or provider), the remainder
retry            3 attempts in-step for transient faults, provider delay honoured
validation       invalid JSON / shape -> repair -> re-ask the same model once
                 -> step 2 (repair, re-ask once) -> fallback
onError          fallback (HTTP 200, localized text, a "Try again" quick reply)
breaker          8 of 10 -> skip the model for 60 s; auth / quota -> at once
```

The agent configuration (`agent.json`) carries the deadline:

```json
{
  "turnDeadlineMs": 55000,
  "turnDeadlineReserveMs": 1500
}
```

and the LLM task (`langchain.json`):

```json
{
  "tasks": [
    {
      "id": "answerer",
      "type": "gemini",
      "actions": ["answer"],
      "retry": {
        "maxAttempts": 3,
        "backoffDelayMs": 1000,
        "backoffMultiplier": 2.0,
        "maxBackoffDelayMs": 4000,
        "honorRetryAfter": true,
        "maxRetryAfterMs": 8000
      },
      "nonBlankFields": ["htmlResponseText"],
      "responseObjectName": "aiOutputObject",
      "parameters": {
        "apiKey": "${vault:gemini-key}",
        "modelName": "${vars:primary-model}",
        "systemMessage": "You are a support assistant. Reply with a JSON object with the fields htmlResponseText (string, never empty) and quickReplies (array of strings).",
        "convertToObject": "true",
        "addToOutput": "false",
        "responseSchema": "{\"type\":\"object\",\"required\":[\"htmlResponseText\"],\"properties\":{\"htmlResponseText\":{\"type\":\"string\",\"minLength\":1},\"quickReplies\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}}}",
        "timeout": "25000"
      },
      "modelCascade": {
        "enabled": true,
        "strategy": "cascade",
        "evaluationStrategy": "heuristic",
        "maxTotalDurationMs": 50000,
        "steps": [
          {
            "type": "gemini",
            "parameters": { "modelName": "${vars:primary-model}" },
            "confidenceThreshold": 0.1,
            "timeoutMs": 25000
          },
          {
            "type": "gemini",
            "parameters": { "modelName": "${vars:fallback-model}" },
            "timeoutMs": 25000
          }
        ]
      },
      "responseValidation": {
        "enabled": true,
        "onEmpty": "retry",
        "onTruncation": "retry",
        "onContentFilter": "fallback",
        "onInvalidJson": "retry",
        "onSchemaMismatch": "retry",
        "onContextTooLong": "retry",
        "maxRetries": 1,
        "fallbackAction": "fallback",
        "fallbackMessage": "Sorry, I could not answer that just now. Please try again.",
        "fallbackField": "htmlResponseText",
        "fallbackQuickReplies": [{ "value": "Try again", "expressions": "retry_last" }]
      },
      "onError": { "action": "fallback" },
      "circuitBreaker": { "enabled": true, "window": 10, "threshold": 8, "coolDownMs": 60000 },
      "postResponse": {
        "outputBuildInstructions": [
          { "outputType": "text", "outputValue": "{properties.aiOutputObject.htmlResponseText}" }
        ]
      }
    }
  ]
}
```

Notes on the choices:

- `heuristic` with a threshold of `0.1` makes the cascade a **failover**: the heuristic scores an empty reply `0.0`
  and anything else higher, so step 1 is accepted unless it is empty. (`evaluationStrategy: "none"` always
  accepts step 1 and only escalates on errors and unusable replies.) With a real quality gate, use
  `structured_output` or `judge_model` and a higher threshold.
- Step timeouts of 25 s plus `maxTotalDurationMs` and `turnDeadlineMs` all apply; the smallest wins. The step
  model's own request `timeout` is clamped to the step's `timeoutMs` automatically.
- The fallback is JSON-shaped (`fallbackField`), so the `postResponse` output template renders it with no
  special case. Without `fallbackField` the stored value is a plain string and
  `{properties.aiOutputObject.htmlResponseText}` would be empty.
- A model that cannot be built (`${vars:...}` unknown, key revoked) fails inside the `onError` guard and serves
  the fallback with HTTP 200.
- Pair it with `Idempotency-Key` and `X-EDDI-Turn-Deadline-Ms` on the caller's side so a retried request returns
  the answer the engine already produced.

## Reading what happened

### In the conversation step

These keys are written to the turn's step. They are **not** part of the public conversation snapshot (the
`llm:` prefix keeps them out); `returnDetailed=true` shows them, and so does the conversation log.

| Key | Meaning |
| --- | --- |
| `llm:output:outcome:<taskId>` | How a `convertToObject` reply parsed: `valid`, `repaired` (needed fence stripping or extraction), `invalid`, `empty`, `schema_mismatch` |
| `llm:output:reason:<taskId>` | EDDI's own reason for `invalid` / `schema_mismatch` (`truncated JSON`, `schema: $.htmlResponseText required`, `nonBlank: $.a.b`). Never model output |
| `llm:retry:<taskId>` | `{reasks, unresolved}`: how many same-model re-asks were sent and which problem was still unresolved afterwards |
| `llm:fallback:<taskId>` | `true` when the engine served its own fallback instead of a model answer |
| `llm:error:<taskId>` | `{class, message}` of the failure `onError: fallback` absorbed (redacted, cut at 200 characters) |
| `llm:validation:<type>:<taskId>` | The note a `warn` or `fallback` validation action left (`empty_response`, `truncated_response`, `content_filter`, `invalid_json`, `schema_mismatch`, ...) |
| `langchain:cascade:trace:<taskId>` | The per-step cascade trace (below) |
| `audit:model_name` | The model that answered, as `provider/model` under a cascade. A `${vars:...}` reference is shown **resolved** |

### In the cascade trace

Each entry has `step`, `model` (resolved), `modelType`, `status`, `durationMs`, cost and token fields. Resilience-related values:

| Field | Values |
| --- | --- |
| `status` | `accepted`, `escalated`, `timeout`, `error`, `retryable_error`, `circuit_open`, `superseded_by_best`, `accepted_as_best` |
| `reason` (on an `escalated` step) | `invalid_output`: the reply stayed unusable after the step's re-asks. Also seen as the `reason` of the `cascade_escalation` SSE event |
| `invalidOutput`, `formatRetries` | Which problem was unresolved and how many re-asks the step spent |
| `failureClass`, `retryAfterMs` | The class of a failed step, and the provider's wait hint |
| `circuit_open` | The step was skipped without a call because its model's circuit is open (`failureClass` says what opened it) |

### In the log

Each recovery action leaves **one** INFO line from `LlmRecoveryLog`, the same shape for all of them:

```text
LLM recovery conversationId=<id> agentId=<id> class=<class> action=<action> outcome=<outcome> attempt=<n> durationMs=<ms>
```

| `action` | `class` | `outcome` | `attempt` |
| --- | --- | --- | --- |
| `repair` | `invalid_json` | `recovered` | `1` |
| `retry` (a same-model re-ask) | the reply problem (`empty`, `truncated`, `content_filter`, `invalid_json`, `schema_mismatch`, `context_too_long`) | `recovered`, `still_invalid`, `failed` | the re-ask number |
| `escalate` (a cascade step handed over) | the failure class, or the reply problem, or `low_confidence` | the escalation reason: `invalid_output`, `timeout`, `error`, `retryable_error`, `low_confidence` | the 1-based step that gave up |
| `circuit_skip` | the class that opened the circuit | `skipped` | `1` (the step number under a cascade) |
| `fallback` | the absorbed failure's class, or the validation type | `served` | `1` |

The failures that triggered a recovery keep their own WARN or ERROR lines; this line says what the engine did
about them. `grep "LLM recovery"` with a conversation id reconstructs the order of events of one turn.

### In the metrics

All counters are Micrometer meters on `/q/metrics` (names below as Prometheus renders them; see
[Metrics](metrics.md) for the full list and the dashboard panels).

| Metric | Tags | Meaning |
| --- | --- | --- |
| `eddi_llm_failure_total` | `class`, `model` | A failed model call by failure class: every failed cascade step, and every failed single-model task |
| `eddi_llm_recovery_total` | `action`, `outcome`, `trigger` | One count per recovery action. `action` is `repair`, `retry`, `escalate`, `circuit_skip` or `fallback` |
| `eddi_llm_output_total` | `outcome` | `convertToObject` replies by parse outcome (`valid`, `repaired`, `invalid`, `empty`, `schema_mismatch`) |
| `eddi_llm_turn_deadline_exceeded_total` | `stage` | A layer stopped because the turn deadline left no room (`attempt`, `sleep`, `attempt_timeout`, `cascade`, `tool`, `httpcall`, `httpcall_retry`) |
| `eddi_llm_cancelled_total` | `scope` | An attempt or cascade step abandoned on timeout |
| `eddi_llm_circuit_total`, `eddi_llm_circuit_skipped_total`, `eddi_llm_circuit_open` | | Circuit breaker transitions, skipped turns and the gauge of open circuits |

A rising `invalid` or `schema_mismatch` share for one agent points at a prompt, schema or model-version
problem; a rising `repaired` share means the model wraps its JSON and the local repair is carrying it.

## Circuit breaker

`circuitBreaker` takes a model that cannot answer out of rotation for `coolDownMs`: a cascade goes straight to
its next step, a single-model task takes its `onError` path with no model call. It counts only failures that
would fail the same way again (`INVALID_OUTPUT`, `BAD_REQUEST`, `MODEL_NOT_FOUND` when `threshold` of the last
`window` counted turns share a class; `AUTH` and `QUOTA_EXHAUSTED` at once). Transient errors, rate limits and
timeouts never count. After the cool-down one probe turn is let through. Circuits live in memory on each node,
keyed by agent, agent version, provider and the **resolved** model name. Every open is one ERROR log line
(`LLM circuit OPEN ...`); alert on that or on `eddi_llm_circuit_total{state="open"}`. Full description:
[LLM Integration](langchain.md#circuit-breaker-skip-a-model-that-keeps-failing).

## After a human approval (resume)

A turn that paused for a human tool approval continues on the same recovery policies, with these exceptions:

| Policy | On resume |
| --- | --- |
| Parsing, shape validation (`responseSchema`, `nonBlankFields`) | Applies (the same code as a live turn) |
| `responseValidation` actions (`fallback`, `error`, `warn`) | Applies to the final answer |
| `onError: fallback` | Applies to the resumed model phase. The approved tool calls already ran exactly once (the write-ahead journal), so only the final answer is replaced |
| Same-model re-ask (`retry`) of empty, invalid JSON or schema-mismatch replies | Applies to the **final answer** only, over the resumed transcript. Not available when the resume recorded no transcript (the answer then goes to `fallbackAction`) |
| Truncation and context-too-long re-asks | Not available: no finish reason is reported by the loop, and shrinking the prompt after tools ran would mean replaying them |
| Circuit breaker | Not consulted and not settled: the resume pins the model whose tool loop paused and counts neither a success nor a failure |
| Cascade | Not applied: the resume continues on the cascade step that paused (the escalation decision was made before the pause) |
| Turn deadline | None: a resume starts a turn without one |

## Known limits

- Streaming (SSE) calls keep their own timeout and are not bounded by the turn deadline, and their retry loop
  uses the plain backoff, not the provider's delay. A task that may re-ask is buffered rather than streamed.
- An HTTP `Retry-After` header is not available to the engine: only the provider's error body is read.
- Circuit breaker state is per node.
- A duplicate request that lands on another node (NATS coordinator) while a turn runs still meets `409` until
  the turn completes; after that the stored answer is served everywhere.

## See also

- [LLM Integration](langchain.md): every field, the provider matrix, structured output
- [Model Cascade](model-cascade.md): confidence strategies, escalation, trace
- [Conversations](conversations.md): the `error` object, `Idempotency-Key`, the deadline header
- [Metrics](metrics.md): meters and dashboard
- [Human-in-the-loop](hitl.md): approval gates and resume
