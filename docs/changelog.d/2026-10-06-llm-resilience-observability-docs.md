## feat(llm): recovery observability, resume-path parity and the resilience guide (2026-10-06)

**Repo:** EDDI (`feat/llm-resilience-observability-docs`, the capstone of the LLM turn-resilience series, on top of `feat/llm-circuit-breaker` and `feat/turn-idempotency-structured-errors`)

### What changed and why

R11, R12 and R13 of the LLM turn-resilience plan ([`planning/llm-turn-resilience-plan.md`](../../planning/llm-turn-resilience-plan.md)), and the plan's status block. The seven items before it each added a recovery; this one makes them readable and keeps the HITL resume path from being the one place they do not apply.

**R11 - observability**

- **Resolved model names.** `audit:model_name`, the cascade trace's `model` and the circuit-breaker key recorded the literal `${vars:gemini-model}` (the registry resolves global variables late, after the task has recorded its name). `LlmTask.resolveModelName(params, type, resolver)` and the cascade's `resolveVariables` now resolve `${vars:...}` through `GlobalVariableResolver` for everything that records or keys on the model, including the new `eddi.llm.failure` tag. An unresolvable reference (unknown variable, resolver error) keeps the configured text instead of failing the turn over a label. The raw template is not kept in a separate field (not trivial: the audit has one name slot).
- **One structured line per recovery.** New `LlmRecoveryLog` (`modules/llm/impl`) writes `LLM recovery conversationId=... agentId=... class=... action=... outcome=... attempt=... durationMs=...` at INFO, once per repair, same-model re-ask, cascade escalation, skipped (open-circuit) model and served fallback. The call site that performs the action emits it; the failures that triggered it keep their own WARN/ERROR lines. The ad-hoc INFO lines it replaces: the `FormatRetryRunner` "Re-asking the same model" line (now written from `ReaskListener.onReaskDone`, where outcome and duration are known), the cascade's WARN for a skipped circuit and its INFO "escalating: confidence" line (kept at DEBUG, it carries the scores). Values are stripped of whitespace and control characters.
- **Metrics gap-fill** (only what was missing): `eddi.llm.recovery` gains `action=repair` (a reply that parsed only after fence stripping or extraction), `action=circuit_skip`, and `action=escalate` for every escalation reason (`timeout`, `error`, `retryable_error`, `low_confidence`, besides the existing `invalid_output`); `eddi.llm.failure{class,model}` is now also counted for a failed single-model task (a cascade still counts per step, and its overall failure is not counted twice; the circuit's own refusal and control flow such as a HITL pause are not model failures). `eddi.llm.output` and `eddi.llm.turn.deadline.exceeded` already existed. The tag keys of `eddi.llm.recovery` stay `action, outcome, trigger` so Prometheus sees one consistent meter.

**R12 - resume parity.** `LlmTask.executeResume` now applies, to the resumed final answer: the `onError: fallback` guard (the approved tools already ran exactly once through the journal, so only the answer is replaced), `responseValidation` actions (it applied none before), and the R5 same-model re-ask of an empty, invalid-JSON or schema-mismatch reply. The re-ask is one model call over the resumed transcript through `reaskFinalAnswer`; `ToolLoopResumer` records that transcript on a new `ExecutionResult.resumeTranscript` (the loop's final messages without the answer), so no tool can run twice. A task that may re-ask is buffered on resume, as on a live turn. **Not applied on resume, and why:** the circuit breaker (the resume pins the model whose loop paused and settles no ticket), the cascade (the escalation decision was made before the pause), truncation and context-too-long re-asks (the loop reports no finish reason; shrinking after tools ran would replay them), and a turn deadline (a resume starts a turn without one). A resume that recorded no transcript cannot re-ask and goes to `fallbackAction`. Parsing and shape validation (R3, R4) were already shared.

**R13 - docs.** New [`docs/llm-resilience.md`](../llm-resilience.md): failure taxonomy, recovery order, the full configuration surface with defaults (verified against the config classes), a complete `langchain.json` recipe for a JSON agent behind a 60-second caller, how to read step keys, trace, log line and metrics, the resume table and the known limits. [`langchain.md`](../langchain.md), [`model-cascade.md`](../model-cascade.md) (new "Cascade as failover") and [`metrics.md`](../metrics.md) link to it and no longer say the resume path has no recovery. `docs/SUMMARY.md` lists it.

**Platform Operator.** An admin will ask "why did my agent answer 'Sorry...'" and "why is model X skipped". The operator prompt's docs map now names `llm-resilience`, and its how-to-work section says which step keys to read (`llm:fallback`, `llm:error`, `llm:output:outcome|reason`, a trace status `circuit_open`) and that the log carries one `LLM recovery` line per recovery. No allow-list change (it can already read conversations, logs and the audit trail). `operator-revision.json` 2 to 3.

**Plan status.** The status block of the plan now marks R1 to R16 implemented with the branch and PR of each, where the implementation differs from the plan text, and the known gaps below.

### Design decisions

- One helper rather than six `LOGGER.infof` calls, so the line has one shape and tests can assert "exactly one line per recovery" through an observer hook (`LlmRecoveryLog.observer`, null in production).
- The retry line is written by a `ReaskListener` default method (`onReaskDone`), not by `FormatRetryRunner`, because only the caller knows the conversation and agent ids.
- `ExecutionResult` grew a fifth component rather than reusing `toolExchange`: the exchange is "tools only" by contract, the resume transcript is "everything the model saw".
- `circuit_skip` is a fifth `action` value next to the plan's four: it is a recovery (the engine avoided a call) and an operator counting escalations should not have to subtract it.

### Known gaps and follow-ups

- The streaming path is still not bounded by the turn deadline and uses the plain backoff.
- R15's `onInvalidJson: "retry"` with `maxRetries: 0` warning is not implemented, and its `onError` check still reads the field reflectively.
- The R14 WireMock cascade IT was not written.
- No dashboard panel dedicated to `action=repair` / `circuit_skip`; the full-metrics dashboard has pre-existing duplicate panel ids (158/159, 168 to 172) on `main` that this change leaves alone. Ids 174 to 181 (this series) are unique.
- Manager UI has types only for the new task fields.

```decision-log
| 2026-10-06 | One `LlmRecoveryLog` INFO line per recovery action, emitted by the call site that performs it | Six ad-hoc log lines had no shared shape and no conversation id; "one recovery, one line" needs a single place that formats it | Keeping each component's own log message and documenting them (not greppable as one family, no ids) |
| 2026-10-06 | A HITL resume applies onError, responseValidation and the final-answer re-ask, but not the breaker, cascade, truncation/context re-asks or a deadline | The resume pins the paused model and must not replay tools; the breaker and cascade decisions were made before the pause | Re-running the cascade on resume (would re-enter the tool loop and replay approved tools) |
```
