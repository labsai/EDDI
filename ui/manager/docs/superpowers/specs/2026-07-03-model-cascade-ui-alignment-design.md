# Model-Cascade UI Alignment — Design

**Date:** 2026-07-03
**Branches:** `feat/model-cascade-ui` in `EDDI-Manager` (off `origin/main`) and in `eddi-chat-ui` (off `feature/version-6.0.0`)

## Problem

The EDDI backend branch `feat/model-cascade-enterprise-hardening` extended the per-task
`modelCascade` LLM config (cost/time ceilings, cascade + per-step pricing, a configurable
judge model, heuristic tuning, best-of-steps, cross-provider credential guards) and now emits
live cascade SSE events (`cascade_step_start`, `cascade_escalation`). The two frontends are
behind:

- **EDDI-Manager** (admin) has a cascade editor that only exposes `enabled`, `strategy`,
  `evaluationStrategy`, `enableInAgentMode`, and per-step `type` / `model` / `confidenceThreshold`
  / `timeoutMs`. Everything the hardening added is unreachable — and `judge_model` is selectable
  with no way to configure the judge (a dead-end).
- **eddi-chat-ui** (end-user) has no cascade awareness at all.

Goal: bring both UIs back into feature alignment, with great UX.

## Backend contract (authoritative — from `LlmConfiguration.java` + `CascadeConfigValidator.java`)

`modelCascade`: `enabled` (bool, false) · `strategy` (str, "cascade"; also "parallel") ·
`evaluationStrategy` (str, "structured_output"; also "heuristic", "judge_model", "none") ·
`enableInAgentMode` (bool, true) · `steps` (CascadeStep[]) · `judgeModel` ({type, parameters}) ·
`heuristic` (HeuristicConfig) · `maxTotalDurationMs` (long, >0) · `maxCostPerRun` (double, ≥0) ·
`inputPricePer1M` / `outputPricePer1M` (double, ≥0) · `returnBestAcrossSteps` (bool, false).

`CascadeStep`: `type` (str) · `parameters` (Map<str,str>) · `confidenceThreshold` (double, [0,1] or null) ·
`timeoutMs` (long, 30000) · `inputPricePer1M` / `outputPricePer1M` (double, ≥0).

`HeuristicConfig`: `lowConfidencePhrases` (str[]) · `refusalPhrases` (str[]) ·
`shortLengthThreshold` (int) · `shortScore` / `refusalScore` / `hedgingScore` / `defaultScore`
(double; runtime defaults 0.3 / 0.2 / 0.4 / 0.8). All optional; null → built-in English default.

### Validation (mirrored client-side for pre-deploy UX)

- **Errors (backend deploy fails):** `maxTotalDurationMs ≤ 0`, `maxCostPerRun < 0`, any cascade or
  per-step `inputPricePer1M` / `outputPricePer1M < 0`.
- **Warnings (deploy proceeds, degraded at runtime):** enabled with no steps; unknown `strategy`;
  `strategy = parallel` (not implemented); unknown `evaluationStrategy`; `judge_model` without a
  `judgeModel.type`; a step whose provider differs from the task and that has no own `apiKey`
  (cross-provider credential trap); a non-last step with a null `confidenceThreshold` (dead-step
  trap); `confidenceThreshold` outside `[0,1]`; `timeoutMs ≤ 0`.

## Design

### EDDI-Manager — Phase 1: config editor

- **Types** (`src/components/editors/llm/types.ts`): extend `ModelCascadeConfig` and `CascadeStep`;
  add `CascadeJudgeModel` and `CascadeHeuristic`. Re-export from `llm-editor.tsx`.
- **Validation** (`src/components/editors/llm/cascade/cascade-validation.ts`): pure
  `validateCascade(task): CascadeIssue[]` mirroring the rules above. Unit-tested (TDD).
- **UI** — split `task-cascade-section.tsx` into an orchestrator plus focused components under
  `llm/cascade/`, using the existing `EditorSection` / `SecretKeyPicker` / amber-callout patterns.
  Progressive disclosure so only relevant controls show:
  1. Enable + description (existing testids preserved: `cascade-section`, `cascade-enable`).
  2. Strategy + Confidence Evaluation (existing).
  3. Evaluation-specific: `cascade-judge-model` (when `judge_model`), `cascade-heuristic`
     (when `heuristic`), else a contextual info note.
  4. `enableInAgentMode` (existing) + `returnBestAcrossSteps` (new).
  5. `cascade-ceilings`: `maxTotalDurationMs`, `maxCostPerRun`, cascade-default pricing.
  6. Steps (`cascade-step-card`, testids `cascade-step-${i}` / `add-cascade-step` preserved): existing
     controls + per-step pricing + `apiKey` (SecretKeyPicker, highlighted when cross-provider) +
     collapsible advanced `parameters` grid (hides `model`/`apiKey`, SecretKeyPicker for sensitive keys).
  7. Inline validation callouts from `validateCascade` (red = error, amber = warning).
- **i18n**: ~35 new `llmEditor.cascade*` keys in `en.json`, translated into all 10 other locales.

### EDDI-Manager — Phase 2: observability

- `PipelineEvent` (`src/hooks/use-debug-events.ts`) gains `modelName`, `threshold`, `reason`,
  `fromStep`, `toStep`, `totalSteps`, `stepIndex` (keeps `confidence`).
- `handleSSEEvent` (`src/hooks/use-chat.ts`) gains `cascade_step_start` and `cascade_escalation`
  cases → `debug.addEvent(...)`. The existing `default` already ignores unknown events.
- Render escalation in `chat-activity.tsx` (inline card) and `pipeline-trace.tsx` (debug drawer):
  model tier, confidence vs threshold, reason, duration/cost. i18n + tests.

### eddi-chat-ui — Phase 3: tasteful end-user signal

The cascade events ride the same `/agents/{id}/stream` the widget consumes.

- **Robustness (mandatory):** confirmed the parser (`src/api/chat-api.ts`) and
  `handleSSEEvent` (`src/components/ChatWidget.tsx`, `default: return false`) already ignore unknown
  events — verified via test.
- **UX:** add `cascade_step_start` / `cascade_escalation` to `SSEEventType` (`src/types.ts`); add an
  `isEscalating` flag + reducer action (`src/store/chat-store.tsx`); on `cascade_escalation` show a
  generic **"Thinking harder…"** indicator (new variant in `Indicators.tsx` + CSS), reset on the next
  `token`/`done`. **No model names, confidence, or pricing are shown to end users** — that detail is
  admin-only. eddi-chat-ui has no i18n system, so strings are hardcoded English (matching existing).

## Testing

- Manager: `cascade-validation.test.ts` (every rule); cascade-section component tests (conditional
  panels, per-step credentials, warnings); observability tests for the new events. Existing cascade
  tests stay green (testids preserved). Verify `typecheck` + `lint` + `vitest`.
- chat-ui: SSE cascade handling + `isEscalating` reducer tests. Verify `typecheck` + `vitest`.

## Out of scope

- `openapi.json` (not wired to codegen; TS types are hand-maintained).
- The version-cascade `cascade-save.ts` (unrelated to model cascade).
- Backend changes, and `pipeline-railroad.tsx` escalation visuals (optional follow-up).
