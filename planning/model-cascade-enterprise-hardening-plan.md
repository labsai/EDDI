# Multi-Model Cascade — Enterprise Hardening Plan

> Branch: `feat/model-cascade-enterprise-hardening` (off `origin/main`)
> Status: In progress
> Scope: Full enterprise pass over the multi-model cascading feature — fixes every
> item in the code review (broken promises, correctness bugs, enterprise gaps) and
> makes the docs honest.

## Background

The cascade loop (`CascadingModelExecutor` + `ConfidenceEvaluator` + `LlmTask` branch)
is well built: clean escalation, best-response fallback, per-step timeouts via virtual
threads, per-conversation trace. But a review found: two documented capabilities don't
exist end-to-end (SSE events, judge model), the audit trail records the wrong model,
and token/cost/metrics are discarded so the cost-savings pitch is unmeasurable.

This plan implements the full enterprise pass. Design decisions (locked with the user):

| Item | Decision |
|---|---|
| judge_model | **Implement** — real `judgeModel` config block, built via `ChatModelRegistry`. |
| Agent-mode confidence (#6) | **Auto-route** to judge (if configured) else heuristic; never inject the wrapper into the tool loop; warn once at configure time. |
| convertToObject + cascade (#7) | **Support it** — honor native `jsonMode`; force a non-wrapper strategy when `convertToObject=true`. |
| Heuristic i18n | **Both** — config-driven phrases/thresholds *and* language-agnostic fallback defaults. |
| Cost ceiling | **Configurable per-step pricing** (`inputPricePer1M`/`outputPricePer1M`) → dollars from captured token usage; plus wall-clock `maxTotalDurationMs`. |

## Architecture changes

### Config model — `LlmConfiguration`

`ModelCascadeConfig` new fields (all optional, backward compatible):
- `JudgeModelConfig judgeModel` — `{ String type; Map<String,String> parameters; }`
- `HeuristicConfig heuristic` — `{ List<String> lowConfidencePhrases; List<String> refusalPhrases;
  Integer shortLengthThreshold; Double shortScore; Double refusalScore; Double hedgingScore; Double defaultScore; }`
  (nulls fall back to today's English defaults)
- `Long maxTotalDurationMs` — cascade wall-clock ceiling (null = unlimited)
- `Double maxCostPerRun` — dollar ceiling (null = unlimited)
- `Double inputPricePer1M`, `Double outputPricePer1M` — cascade-level default pricing
- `boolean returnBestAcrossSteps = false`

`CascadeStep` new fields:
- `Double inputPricePer1M`, `Double outputPricePer1M` — per-step price (override cascade default)

### `ConfidenceEvaluator`
- **JSON-parse-first**: parse the confidence wrapper with Jackson (already injected), regex
  fallback only. Extract confidence only from an identified wrapper object, not any stray
  `"confidence":` inside answer content.
- **Config-driven heuristic + language-agnostic fallback**: phrases/thresholds from
  `HeuristicConfig` (English defaults); when no configured phrase matches, score via
  language-agnostic signals (length bands + JSON-structure presence) instead of a flat 0.8.
- **judge_model**: now reachable — a real judge model is passed in.

### `CascadingModelExecutor` → `@ApplicationScoped` CDI bean
Converted from static utility to injectable bean so it can hold `MeterRegistry`,
`GlobalVariableResolver`, and `IMemoryItemConverter` (templating). The 3 existing executor
test classes are updated to instantiate with mocks.
- Per-step token capture from `ChatResponse.metadata().tokenUsage()`; per-step `costUsd` from
  configured pricing. `CascadeResult` carries real `modelName`, aggregate `tokenUsage`,
  aggregate `costUsd`. All added to the per-step trace.
- **#8**: resolve step `type` through `GlobalVariableResolver`; apply `${vault:}` + global-var +
  Qute templating to step param values (parity with task params).
- **judge_model**: build the judge once, pass to `ConfidenceEvaluator`.
- **#6**: agent-mode `structured_output` → effective judge/heuristic (no wrapper).
- **#7**: thread `jsonMode`; when `convertToObject=true`, effective strategy is never the wrapper.
- **strategy**: `parallel`/unknown → handled by validation; runtime logs once, runs sequentially.
- **Ceilings**: track cumulative duration + cost; before each step after step 0, stop and return
  best-so-far if a ceiling is exceeded; cap each step timeout by remaining duration budget.
- **returnBestAcrossSteps**: when true, if an earlier escalated step scored strictly higher than
  the accepted final step, return the earlier one.

### `LlmTask`
- **#5 audit**: `audit:model_name` = real winning model under cascade; `audit:cascade_model` =
  `provider/model (step N)`; add `audit:cascade_token_usage` / `audit:cascade_cost`.
- Populate `responseMetadata` from cascade token usage so `responseMetadataObjectName` yields real
  counts (not `{}`).
- Build the base `chatModel` lazily — not when the cascade branch is taken.

### SSE wiring (#1) — three-link chain
1. `IConversationService.StreamingResponseHandler`: add `default` no-op `onCascadeStepStart` /
   `onCascadeEscalation`.
2. `ConversationService.sayStreaming` anonymous `ConversationEventSink`: override both, forward to
   the handler.
3. `RestAgentEngineStreaming`: implement both, emit `cascade_step_start` / `cascade_escalation`
   SSE events with JSON payloads.

### Metrics (Micrometer) — `eddi.llm.cascade.*`
`executions` (tag agentMode), `escalations` (tag reason), `accepted.step` (tag step),
`step.latency` timer (tag provider), `confidence` distribution, `step.errors` (tags provider,type),
`tokens`, `cost`, `ceiling.exceeded` (tag kind=duration|cost).

### Configure-time validation (`LlmTask.configure`)
Fail fast on: empty steps; unknown `evaluationStrategy`; `judge_model` without judge config;
threshold ∉ [0,1]; non-last step with null threshold (dead-step trap); unknown `strategy`
(reject) / `parallel` (warn); negative pricing/ceilings. `convertToObject + structured_output`
→ warn + auto-downgrade (per #7).

### Cancellation safety (#9)
Cooperative cancellation flag checked between orchestrator tool-loop iterations so a timed-out
agent-mode step stops launching new tools; buffer the step's tool trace and merge only on
acceptance to avoid concurrent writes into the shared conversation step. Residual in-flight-tool
risk documented.

### Streaming the final step live
Last step is always accepted → stream it live via the streaming model in legacy mode (earlier
steps stay buffered for confidence eval); also stream step 0 live when `strategy: none`.
Agent-mode cascade keeps today's single-chunk final response (documented).

### Docs
Rewrite `docs/model-cascade.md`: 429 retried-then-escalate, real trace key
(`langchain:cascade:trace:<taskId>`), all new fields, real SSE/audit content, agent-mode +
convertToObject behavior, token/cost/metrics, streaming caveat. Changelog entry same branch.

## Phased delivery (each phase compiles & tests green, own commit)

1. **Audit correctness (#5) + base-model laziness** — `LlmTask`.
2. **Config model + configure-time validation** — `LlmConfiguration`, `LlmTask.configure`.
3. **ConfidenceEvaluator** — JSON-parse-first, config-driven + language-agnostic heuristic.
4. **Executor rework** — CDI bean; token capture; real modelName; #8; metrics; judge; #6; #7;
   ceilings; returnBestAcrossSteps.
5. **LlmTask integration** — responseMetadata, audit real model, wire executor deps.
6. **SSE wiring (#1)**.
7. **Cancellation safety (#9)**.
8. **Streaming final step**.
9. **Docs rewrite + changelog**.
10. **Test suite** — extend existing, add new coverage for every item.
11. **Adversarial verification workflow** — multi-lens review + skeptics + completeness critic; fix confirmed findings.

## Backward compatibility
All new config fields are optional with today's behavior as defaults. Configs without
`modelCascade`, and `enabled:false`, are unaffected. `StreamingResponseHandler` gains
default methods so other implementers don't break. Internal-only APIs, so no external contract.
