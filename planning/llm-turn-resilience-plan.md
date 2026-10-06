# Handoff: LLM turn resilience in EDDI — recover from every failure class, always serve the user

## Status

- **R3 + R14 implemented** (PR for branch `feat/llm-output-parsing-never-throws`): fence stripping and prose recovery of **objects** (a non-empty object extracted from surrounding text), arrays accepted **only as the whole reply**; parsing never throws; outcome recorded under `llm:output:outcome:<taskId>` and `eddi.llm.output{outcome}`. `FaultInjectingChatModel` is test scope only.
- **R2, R4, R5, R6, R7 implemented** on the stacked branches (see `docs/changelog.d/2026-10-06-*.md`). Where the implementation differs from the text below: a corrective message is sent only for invalid JSON and schema mismatches (empty, truncated and filtered replies are re-asked with the original request; context-too-long re-sends with the history window halved); fallback exclusion from the LLM history is driven by the step flag `llm:fallback:<taskId>`, because a `postResponse` fallback has no task-created `OutputItem`.
- Everything else below is still to do.


**Repo:** `labsai/EDDI` (Java backend). Branch from `origin/main` (AGENTS.md §2 rule 3), for example
`feat/llm-turn-resilience`. Copy this file to `planning/llm-turn-resilience-plan.md` on that branch.
Add a changelog fragment under `docs/changelog.d/` (§2 rule 8). Read first:
- `AGENTS.md`;
- `docs/project-philosophy.md`;
- `docs/langchain.md` (Retry Configuration, Structured Output);
- `docs/model-cascade.md`.

This plan is deliberately large. It is split into independent work items (R1–R16) with an order
(§7). Ship them as separate PRs.

---

## 0. Why, and the goal

Agents that serve end users through a backend have one job per turn: **answer within the
caller's time budget**. Today a turn can:
- fail with HTTP 500 (provider error after retries; malformed JSON);
- return an empty answer (plain-text or wrong-shape reply under `convertToObject`);
- overrun the caller's timeout (retry and timeout layers multiply);

and nothing tells an operator that it's happening systemically.

**Goal:**
- every recoverable failure is recovered, by the cheapest means first, inside one deadline;
- every unrecoverable one ends in a configured, user-presentable answer;
- systemic failures are detected, cut short and alerted.

All of it is config-driven (Pillar 1) and backward compatible: defaults keep today's behaviour unless
noted.

## 1. Baseline already deployed with today's config (do not regress)

A production deployment (two agents, Gemini, `convertToObject:"true"`, no tools, synchronous REST,
caller timeout 60 s, measured turns: median 4.6 s, p90 9.9 s, max 19.4 s) now runs:

```json
"parameters": { "timeout": "25000", "convertToObject": "true", "modelName": "${vars:gemini-model}", ... },
"retry": { "maxAttempts": 3, "backoffDelayMs": 1000, "backoffMultiplier": 2.0, "maxBackoffDelayMs": 2000 },
"modelCascade": { "enabled": true, "strategy": "cascade", "evaluationStrategy": "heuristic", "maxTotalDurationMs": 35000,
  "steps": [ { "parameters": { "modelName": "${vars:gemini-model}" }, "confidenceThreshold": 0.1, "timeoutMs": 25000 },
             { "parameters": { "modelName": "gemini-3.7-flash" }, "timeoutMs": 20000 } ] },
"responseValidation": { "enabled": true, "onEmpty": "fallback", "onTruncation": "fallback", "onContentFilter": "fallback",
  "onRefusal": "ignore", "onStreamingTimeout": "warn" },
"postResponse": { "outputBuildInstructions": [ { "outputValue":
  "{properties.aiOutputObject.htmlResponseText ?: 'Sorry, I could not answer that just now. Please try again.'}" } ] }
```

Plus, for the program-content HTTP call: `timeoutInMillis 10000` and
`retryApiCallInstruction.maxRetries 1` on 502/503/504.

**Verified on 2026-10-06:**
- **A forced step-1 failure escalates.** An invalid model id gave a 404, and step 2 answered in 5.5 s
  with valid JSON. The trace is under `langchain:cascade:trace:<type>`; `audit:cascade_model` names
  the step.
- **The `?:` fallback works under `strict-rendering=false` + `property-not-found-strategy=NOOP`.** It
  returns the real value when present, and the fallback when the key is missing or the parent is a
  plain string (template preview, `POST /administration/preview/template`).
- **A step's timeout bounds its in-step retries.** `CascadingModelExecutor.executeStepWithTimeout`
  wraps `LegacyChatExecutor`, which wraps `executeWithRetry`.
- **Validation runs on the cascade's final answer** (`LlmTask` ~647), and the finish-reason warning is
  propagated from the cascade.
- **Side-effect HTTP calls already use `fireAndForget`** (tracking, notifications), so they don't add
  turn latency.

**What this baseline still cannot do** is the rest of this plan:
- retry on invalid JSON;
- recover from context-too-long;
- honour the caller's deadline end to end;
- avoid a 500 when everything fails;
- localize the fallback;
- avoid feeding fallback text back into the model's history;
- detect systemic failure.

## 2. Failure taxonomy (complete) and today's coverage

| Class | Examples | Today | Gap → item |
|---|---|---|---|
| **A1 transient provider** | 429 rate, 502/503/504, connect/read timeout, DNS | `executeWithRetry`: **3 attempts by default even with no `retry` block**, then cascade escalation | 500 not in `RETRYABLE_STATUS_CODES` (only retried if langchain4j types it `RetriableException`); `Retry-After` / Gemini `RetryInfo.retryDelay` ignored; library-level retries (langchain4j `maxRetries`, never set by EDDI builders) may multiply EDDI's → **R2** |
| **A2 hang** | the provider accepts and never answers | step timeout cancels; escalates | the in-flight HTTP call may continue after `future.cancel(true)` (cost, threads) → **R10**; without a cascade only the per-request `timeout` bounds it, per attempt → **R1** |
| **B1 permanent request** | 400 invalid argument, 404 unknown/retired model | not retried (correct); cascade escalates on "other errors" | a retired model is systemic → **R8**; error class not exposed → **R9** |
| **B2 credentials / quota** | 401/403; 429 `RESOURCE_EXHAUSTED` for a **daily quota** (not a rate) | auth not retried; a quota 429 **is** retried, which is wasted | quota vs rate distinction → **R2**; open the breaker → **R8** |
| **B3 context too long** | 400 "input token count exceeds" | turn fails | shrink history and retry once → **R5** |
| **C1 empty / safety / truncation** | finishReason SAFETY, MAX_TOKENS, empty text | cascade heuristic escalates on empty (score 0.0); `responseValidation` → `fallback` | no `retry` action; truncation retry with a larger `maxOutputTokens`; the fallback text is fixed English and not JSON-shaped → **R5, R6** |
| **C2 not JSON** | prose, a ```` ```json ```` fence, prefix/suffix text | stored as a **string** with a WARN; the output renders empty (baseline: `?:` text) | detection + local repair + retry → **R3, R5** |
| **C3 malformed JSON** | starts with `{`, truncated or invalid | **`deserialize` throws → turn fails** (`LlmTask` ~667, ~1099) | never throw; classify → **R3** |
| **C4 wrong shape** | valid JSON without `htmlResponseText`, wrong types | renders empty (baseline: `?:` text) | schema validation; native schema → **R4** |
| **C5 empty field** | `htmlResponseText: ""` | blank bubble (`?:` does not trigger on `""`) | schema `minLength` / non-blank rule → **R4** |
| **D pipeline dependency** | program fetch fails or times out; vault reference unresolved | fetch failure is non-fatal (the turn continues with stale content); an unresolved vault reference fails the call closed | fetch budget not deadline-aware → **R1** |
| **E caller timeout** | the backend gives up at 60 s, EDDI still runs; the user retries → `409 IN_PROGRESS`; the late answer is stored but never seen | — | deadline propagation, idempotent replay → **R1, R9** |
| **F systemic** | bad prompt/config → every turn invalid; key revoked; model retired | retried on every turn, every user | circuit breaker + alert → **R8** |
| **G everything failed** | all steps and retries exhausted | **HTTP 500**, conversation `ERROR` (the next message works) | `onError` fallback answer → **R7** |

## 3. Design principles

1. **One deadline per turn.** Every layer spends from it (HTTP calls, retries, cascade steps,
   validation retries), and a reserve is kept for producing the fallback.
2. **Recover in cost order:** local repair → retry the same model (transient) → re-ask **the same
   model** with a corrective message (format) → only then escalate to the next cascade step (which
   gets its own repair and re-ask) → fallback answer. A one-off format slip is usually fixed by the
   model that made it, and escalating first would pay for a different, possibly slower or pricier
   model on every hiccup.
3. **Classify before acting.** Transient means retry; permanent means don't (escalate or fall back);
   systemic means stop doing it for everyone.
4. **Never retry side effects.** In tool mode only the final answer generation may be re-asked; tool
   executions are never replayed by a format retry. Transport retries of a single model call are
   fine (no side effect has happened).
5. **The user always gets an answer; the model never sees our apology.** Fallback text is shown and
   stored, but excluded from the LLM history.
6. **Everything observable:** a metric per failure class and per recovery action and outcome, one
   structured log line per recovery, and audit entries.
7. **Backward compatible:** new fields default to today's behaviour. The two exceptions (R3: malformed
   JSON no longer throws; R2: 500 becomes retriable) are bug fixes. Call them out in the changelog.

## 4. Work items

### R1 — Turn deadline, end to end
- **Config:** agent-level `turnDeadlineMs` (default unset = today), and a per-request override
  (header `X-EDDI-Turn-Deadline-Ms`, also accepted on the managed endpoints), capped by config.
- `Conversation.say` computes `deadline = start + min(config, header)`. Store it on the conversation
  memory as a transient value (not persisted), e.g. `IConversationMemory#getTurnDeadline()`.
- **Consumers:**
  - `RetryConfiguration.executeWithRetry`: don't start an attempt if
    `remaining < minAttemptMs (default 3000) + reserveMs`. Each attempt's timeout is
    `min(configured, remaining − reserve)`; this needs a hook to pass a per-call timeout. Where the
    langchain4j model is cached with a fixed timeout, enforce it with a `Future` timeout as the
    cascade does.
  - `CascadingModelExecutor`: `maxTotalDurationMs = min(config, remaining − reserve)`.
  - `ApiCallExecutor`: per-call timeout and `retryApiCallInstruction` bounded by `remaining`.
  - R5 validation retries: only if the remaining budget allows a full attempt.
- **Reserve:** `turnDeadlineReserveMs` (default 1500) for the fallback path and persistence.
- **Deploy-time warning** (R16) when the static worst case (fetch + cascade + retries) exceeds
  `turnDeadlineMs`.
- **Tests:** a fake clock; attempts are skipped when the budget is short; the cascade budget is
  shrunk by the time already spent in earlier tasks.

### R2 — Error classification and retry ownership
- Add **500** to `RETRYABLE_STATUS_CODES`. Map Gemini statuses: `INTERNAL` and `UNAVAILABLE` are
  retriable; `DEADLINE_EXCEEDED` is retriable; `RESOURCE_EXHAUSTED` needs the next point.
- **Quota vs rate:** Gemini returns `RESOURCE_EXHAUSTED` for both. Parse `error.details[]`
  (`QuotaFailure` violations naming a per-day quota vs per-minute; `RetryInfo.retryDelay`). Per-day
  is non-retriable and systemic (R8); per-minute is retriable with the given delay. Do the same for
  OpenAI (`insufficient_quota` vs `rate_limit_exceeded`) and Anthropic (`overloaded_error` is
  retriable).
- **Honour `Retry-After` / `retryDelay`** when it fits the deadline; otherwise escalate immediately
  instead of sleeping.
- **Single owner of retries:** set the langchain4j client `maxRetries(0)` in every builder that has it
  (`GeminiLanguageModelBuilder` and the others), so `executeWithRetry` is the only retry loop.
  Assert it in the builder tests.
- **Result type:** `FailureClass { TRANSIENT, RATE_LIMITED(delay), QUOTA_EXHAUSTED, AUTH, BAD_REQUEST,
  CONTEXT_TOO_LONG, MODEL_NOT_FOUND, TIMEOUT, UNKNOWN }`, exposed to the cascade (escalation
  reason), metrics, R8 and R9. Keep `isRetryableError` as a wrapper.
- **Tests:** a table-driven test per provider error JSON (fixtures from real error bodies), and
  wrapped-exception chains (keep the existing "outermost wins" regression).

### R3 — Output parsing that never throws
- **One method** used by both the live and the resume path (`LlmTask` ~667 and ~1099):
  `JsonOutcome parseModelOutput(String raw, boolean convertToObject)` returning
  `VALID(Object)`, `INVALID(reason, raw)` or `EMPTY`.
- **Normalisation:**
  - trim;
  - strip one surrounding fence (```` ``` ```` / ```` ```json ````);
  - if it still doesn't parse, extract the outermost balanced `{…}`/`[…]` and parse that (implemented as objects only: an extracted fragment must be a non-empty object; arrays count only as the whole reply), string-
    and escape-aware;
  - no "fixing" of invalid JSON (no trailing-comma repair). Either it parses or it is `INVALID`.
- **`INVALID`/`EMPTY` keep today's observable behaviour** (raw string in `responseObjectName`) unless
  R5 policies say otherwise, **but never throw**.
- Record the outcome on the step: `llm:output:outcome:<taskId>` = `valid|repaired|invalid|empty`.
  Count it: `eddi.llm.output{outcome}`.

### R4 — Shape validation and native schema
- `responseSchema` (already a parameter) gets two uses:
  1. **Validation:** a JSON-Schema subset (`type`, `required`, `properties`, `items`, `enum`,
     `minLength`), in-house unless `pom.xml` already has a validator. A mismatch is
     `INVALID(schema: <path> <rule>)`.
  2. **Native enforcement** where the provider supports it, via `JsonResponseFormatPolicy`:
     (applied per outgoing `ChatRequest`, never baked into a cached model)
     - Gemini: `responseSchema` / `responseJsonSchema` on the langchain4j builder;
     - OpenAI and Azure OpenAI: `response_format: json_schema` with `strict: true`;
     - Mistral, if supported.

     The prompt block stays for providers without it.
- **Optional `nonBlankFields`** (e.g. `["htmlResponseText"]`): a shortcut for the C5 rule without
  writing a schema.
- **Tests:** required missing, wrong type, blank field; the native schema is passed to the Gemini and
  OpenAI builders, and absent for others.

### R5 — Recovery policies (retry where it helps)
Extend `responseValidation`. Today's actions are `ignore|warn|fallback|error`.
- **New action `retry`**, valid for:
  - `onEmpty`;
  - `onTruncation`;
  - `onContentFilter` (a single re-sample; rarely useful, so document it);
  - **new `onInvalidJson`**, covering C2 and C3;
  - **new `onSchemaMismatch`**, covering C4 and C5;
  - **new `onContextTooLong`** (B3; its retry shrinks the history, see below).
- **Mechanics:**
  - `retry` re-asks the **same step's model** with the original messages plus one corrective user
    message. The text is configurable via `correctiveMessage`; the default is "Your previous reply
    could not be used: {reason}. Reply again with only the JSON object described in the
    instructions." `{reason}` is the parser or schema reason, never model output.
  - **Order: same model first, then the next model.** Per cascade step:
    1. the step answers;
    2. R3 local repair;
    3. up to `maxRetries` same-model corrective re-asks;
    4. if it is still invalid, **escalate** to the next step, which runs the same sequence with its
       own `maxRetries`;
    5. after the last step, `fallbackAction`.

    Without a cascade it is steps 1–3 then the fallback. Implement it inside
    `CascadingModelExecutor`'s step loop (a format failure becomes an escalation reason
    `invalid_output`, recorded in the trace like the confidence-based ones), and in the plain
    `LegacyChatExecutor` / tool-loop path for non-cascade tasks.
  - **The re-ask happens within the step's own timeout and the R1 deadline.** If the remaining step
    time can't fit another attempt (`minAttemptMs`), skip the re-ask and escalate at once. Serving
    the user in time beats insisting on the same model.
  - **Systemic short-cut:** if R8 reports that this model has been producing invalid output for most
    recent turns (breaker half-open or open for `invalid_output`), skip the same-model re-ask and
    escalate immediately. Repeating a known-bad pattern only burns the deadline.
  - Truncation retry: once, with `maxOutputTokens × truncationRetryFactor` (default 2, capped).
  - Context-too-long retry: once, with the history window halved (the same machinery as
    `maxContextTokens` / `conversationHistoryLimit`); anchored first steps are kept.
- **Budget:** `responseValidation.maxRetries` (default 1, clamp 3) is **per model/step**, shared
  across the policies within that step, and always bounded by the step timeout and the R1 deadline.
  A cascade step may override it (`steps[i].maxFormatRetries`), e.g. 0 for an expensive last-resort
  model. After the last step, `fallbackAction` (default `fallback`).
- **Tool mode:** a format retry re-asks only the final model call with the tool results already in
  the transcript. It never re-runs `ToolLoopRunner` tools (principle 4).
- **Streaming:** for `convertToObject` tasks, don't stream partial tokens of an attempt that might be
  retried. Buffer it, and emit an SSE `llm_retry` event `{reason, attempt}` so a UI can show
  "retrying…".
- **Tests:**
  - invalid then valid on the **same** model gives one corrective message and a valid output, with
    no escalation;
  - step 1 invalid twice gives an escalation to step 2 (trace reason `invalid_output`);
  - step 2 invalid then valid is answered by step 2 after its own re-ask;
  - every step invalid gives the fallback;
  - no time left in the step: escalate without a re-ask;
  - an open breaker for `invalid_output`: escalate without a re-ask;
  - a per-step `maxFormatRetries: 0` is respected;
  - truncation retry doubles max tokens;
  - context retry halves the window;
  - tool mode never re-executes a tool.

### R6 — Configurable, JSON-aware fallback; never fed back to the model
- **New fields:**
  - `responseValidation.fallbackMessage`: a Qute template (e.g. a `{#switch properties.language}`
    block, or `{snippets.fallback_text}`), default = today's sentence;
  - `fallbackField`: under `convertToObject`, the fallback becomes `{"<fallbackField>": "<message>"}`
    so existing output templates render it unchanged;
  - `fallbackQuickReplies`: optional list, e.g. `[{"value":"Try again","expressions":"retry_last"}]`.
- **Mark the fallback.** The step gets `llm:fallback:<taskId>=true` and the output item a
  `"fallback": true` flag. `ConversationHistoryBuilder` **skips outputs flagged as fallback** when
  building the LLM's message history, so the model never learns from "Sorry, I could not answer". The
  conversation log and UI still show it. This needs a small change where conversationOutputs become
  ChatMessages.
- **Tests:** a localized fallback renders; the JSON-wrapped fallback goes through
  `postResponse.outputBuildInstructions`; the next turn's LLM history has no fallback message.

### R7 — `onError` for the whole LLM task
- **Task-level** `onError: { "action": "fallback" | "error" }`, default `error` (today).
- With `fallback`, any `LifecycleException` from the model phase (after R2/R5/cascade/R8) produces
  the R6 fallback output; the turn completes with **200**, and the conversation is **not** set to
  `ERROR`.
- The step records `llm:error:<taskId>` = `{class, message}` (no secrets) and the audit entry. Use the
  Commit Flags mechanism so the failed task's partial data stays uncommitted.
- **Scope:** the LLM task only. Other tasks keep their own error semantics; a generic
  "pipeline-level onError" is out of scope.
- **Tests:** a provider 500 after retries gives HTTP 200 and the fallback; state `READY`; failure
  metadata stored; a metric increments.

### R8 — Circuit breaker and alerting for systemic failures
- **Key:** (agentId, agentVersion, provider, model). In-memory, per node. Reuse the existing MCP
  circuit-breaker utility if it fits; don't build a second one.
- **Trips on:**
  - N of the last M turns failing with the same non-transient class (`INVALID` output after
    retries, `BAD_REQUEST`, `MODEL_NOT_FOUND`), defaults 8 of 10;
  - `AUTH` or `QUOTA_EXHAUSTED` immediately.
- **When open:** skip that model, and go straight to the next cascade step, or to R6/R7 when there
  is none, for a cool-down (default 60 s; half-open: one probe turn). It saves the deadline for the
  users.
- **Alert:**
  - one ERROR log per state change (agent, version, model, class, sample reason);
  - metric `eddi.llm.circuit{state}`;
  - optional webhook via the existing notification/Slack infrastructure, if a generic hook exists;
    otherwise list it as a follow-up.
- **Tests:** trips after N of M; auth trips immediately; half-open closes on success; per-model
  isolation (step 2 is still used while step 1 is open).

### R9 — Caller contract: structured errors, idempotent turns
- When `onError=error` (or a non-LLM failure), the REST body is
  `{ "error": { "code": "<FailureClass>", "retryable": bool, "retryAfterMs": n } }` instead of a bare
  500 message, with `Retry-After` set when known. Keep the HTTP status as today (500/503).
- **Idempotency:** accept `Idempotency-Key` (or `X-EDDI-Request-Id`) on say/managed endpoints.
  - **Same key while the turn is IN_PROGRESS:** wait for the in-flight turn up to the caller's
    deadline and return its result, instead of `409`.
  - **Same key after completion** (within a short TTL, default 10 min): return the stored result
    without re-running.

  This turns "backend timed out and retried" from a lost answer plus a 409 into the answer. Store
  the key on the step (persisted), and keep an in-memory map of in-flight keys. It must work with
  the `ConversationCoordinator`.
- **Tests:** a duplicate during the turn returns the same output; a duplicate after it returns
  stored; a different key runs a new turn.

### R10 — Cancellation hygiene
- When a step or attempt deadline fires, ensure the provider HTTP request is aborted, not merely the
  waiting future. Options: per-request timeout ≤ step timeout (enforced automatically: clamp the
  model `timeout` to the step timeout when building the step's model), plus closing the client call
  if langchain4j exposes it.
- Metric `eddi.llm.cancelled`. A deploy-time warning when the configured `timeout` > step `timeoutMs`
  (it was a latent multiplier in the baseline until set equal by hand).

### R11 — Observability
- **Metrics:**
  - `eddi.llm.failure{class,model}`;
  - `eddi.llm.recovery{action=repair|retry|escalate|fallback,outcome}`;
  - `eddi.llm.output{outcome}`;
  - `eddi.llm.turn.deadline.exceeded`;
  - existing cascade metrics kept.
- **Resolve `${vars:…}` in audit and trace model names.** Today `audit:model_name` and the cascade
  trace say `${vars:gemini-model}`, which is useless when a variable changes.
- **One structured INFO line per recovery:** conversation, agent, class, action, attempt, duration.

### R12 — Resume (HITL) path parity
The resume continuation (`LlmTask` ~1052–1110) duplicates parsing and skips the cascade by design.
Use R3 and R5 there, and document what does not apply on resume.

### R13 — Docs
- `docs/langchain.md`: the `retry` default is **3 attempts**, not "(none)"; the new
  `responseValidation` fields; `onError`; `turnDeadlineMs`.
- New `docs/llm-resilience.md`: the taxonomy (§2), the recovery order, a recipe for a JSON agent
  behind a 60 s caller (§6), and how to read the trace, metrics and audit entries.
- `docs/model-cascade.md`: "cascade as failover" (`heuristic` + threshold 0.1, or `none`), and the
  escalate-before-re-ask order.

### R14 — Fault injection for tests
A test-scope chat model (`type: "fault-injection"`, test classpath only, or a WireMock Gemini stub)
scripted per call. It returns:
- HTTP status N;
- an empty reply;
- a finishReason (SAFETY, MAX_TOKENS);
- prose;
- truncated JSON;
- the wrong shape;
- a delay.

Every R-item's tests use it. Also add one IT that runs a two-step cascade against WireMock with a
real `LlmTask` pipeline.

### R15 — Deploy-time validation (warnings, never blocking)
- the worst case (fetch timeouts + retries + cascade budget) > `turnDeadlineMs`;
- a model `timeout` > step `timeoutMs`;
- `convertToObject` with no fallback path (no `responseValidation`, no `onError: fallback`, and an
  output template without `?:` on the response field);
- `onInvalidJson: retry` with `maxRetries: 0`.

### R16 — Security and cost guards
- Corrective messages never echo model output or user input. Only the parser or schema reason is
  used, which is EDDI-generated.
- Retries count toward `ToolCostTracker` / cost tracking. Add an optional
  `responseValidation.maxRetryCostUsd` (dollar-based, per AGENTS.md cost guidance).
- No new LLM-reachable tool. Nothing here is controllable by the model.

## 5. Config surface summary (all optional)

```json
"turnDeadlineMs": 55000,
"retry": { "maxAttempts": 3, "backoffDelayMs": 1000, "backoffMultiplier": 2.0, "maxBackoffDelayMs": 2000, "honorRetryAfter": true },
"responseValidation": {
  "enabled": true,
  "onEmpty": "retry", "onTruncation": "retry", "onContentFilter": "fallback",
  "onInvalidJson": "retry", "onSchemaMismatch": "retry", "onContextTooLong": "retry",
  "maxRetries": 1, "truncationRetryFactor": 2,
  "correctiveMessage": "Your previous reply could not be used: {reason}. Reply again with only the JSON object.",
  "fallbackMessage": "{snippets.fallback_text ?: 'Sorry, I could not answer that just now. Please try again.'}",
  "fallbackField": "htmlResponseText",
  "fallbackQuickReplies": [ { "value": "Try again", "expressions": "retry_last" } ],
  "nonBlankFields": ["htmlResponseText"]
},
"onError": { "action": "fallback" },
"circuitBreaker": { "enabled": true, "window": 10, "threshold": 8, "coolDownMs": 60000 }
```

`turnDeadlineMs` is agent-level; the rest are LLM task level.

## 6. Recipe after implementation (JSON agent behind a 60 s caller)

```text
turnDeadlineMs 55000 (reserve 1500)
program fetch    timeout ≤10 s, 1 retry, deadline-bounded
cascade          step 1 primary model, threshold 0.1, timeout 25 s
                 step 2 fallback model (other version or provider), the remainder
retry            3 attempts in-step for TRANSIENT and RATE_LIMITED (honouring Retry-After)
validation       invalid JSON / schema → repair → re-ask the same model once → step 2 (repair, re-ask once) → fallback
onError          fallback (200, localized text, "Try again" quick reply)
breaker          8 of 10 → skip the model 60 s; auth/quota → immediately; alert
```

## 7. Order (each item is one PR)

1. **R3** (no more throw on malformed JSON) + **R14** (fault-injection harness). Small, a bug fix,
   unblocks testing.
2. **R2** (classification, 500, quota vs rate, single retry owner).
3. **R6 + R7** (fallback answer, `onError`, history exclusion). Ends 500s and blank bubbles.
4. **R5** (retry actions incl. invalid JSON, truncation, context-too-long).
5. **R1** (deadline end to end) + **R10** (cancellation) + **R15** (warnings).
6. **R4** (schema validation + native schema).
7. **R8** (breaker + alerting), **R9** (structured errors + idempotency).
8. **R11, R12, R13, R16** alongside the items they touch; docs complete at the end.

## 8. Acceptance criteria

1. A scripted provider returning 503, 503, 200 answers within one turn; 3 attempts are logged.
2. A model that 404s or times out escalates to step 2. A model returning prose and then valid JSON
   is answered **by the same model** after one corrective re-ask, with no escalation. Only a model
   that stays invalid after its re-ask hands over to step 2, which gets its own re-ask before the
   fallback.
3. Truncated JSON never throws. It is retried with more output tokens, then falls back.
4. With every model failing, the caller gets HTTP 200 with the configured, localized fallback and a
   "Try again" quick reply. The conversation stays `READY`, and the next turn's LLM history does not
   contain the fallback text.
5. With `turnDeadlineMs` 55 s and every dependency slow, the turn answers (fallback) before 55 s.
6. 8 consecutive invalid outputs open the breaker. The next turn skips the bad model and logs one
   ERROR; an auth failure opens it immediately.
7. A duplicate request with the same idempotency key during a running turn returns the same answer,
   not 409.
8. Without any new config, existing agents behave as before, except for the two documented bug fixes
   (R3 no throw; R2 500 retriable).

Ask before pushing; never force-push; no AI attribution in commits or PRs (AGENTS.md §2).
