## ✨ feat(llm): per-model circuit breaker for systemic failures (2026-10-06)

**Repo:** EDDI (`feat/llm-circuit-breaker`, stacked on `feat/llm-recovery-policies`)

### What changed and why

Item R8 of the LLM turn-resilience plan ([`planning/llm-turn-resilience-plan.md`](../../planning/llm-turn-resilience-plan.md)). Retries and re-asks fix an unlucky turn; they do nothing for a model that cannot answer at all (a revoked key, a retired model id, a prompt it always answers in prose). Every turn then spent a full attempt, and the re-asks, to arrive at the same failure, and a cascade kept paying its first step's deadline before reaching a step that works.

New opt-in task field `circuitBreaker: { enabled, window, threshold, coolDownMs }` (defaults `false`, 10, 8, 60000), mirrored in the Manager's `editors/llm/types.ts`. Without it nothing changes.

- **New `LlmCircuitBreakers`** (`modules/llm/impl`, `@ApplicationScoped`): one in-memory breaker per (agent, agent version, provider, model), in a bounded (10,000) Caffeine cache that forgets idle keys after an hour. The model is the name the model was built with, after `${vars:…}`/templating (the cascade's own `resolveModelName`).
- **What counts:** `INVALID_OUTPUT` (a `convertToObject` reply still invalid JSON or off-shape after the re-asks), `BAD_REQUEST`, `MODEL_NOT_FOUND` when `threshold` of the last `window` counted turns share the class; `AUTH` and `QUOTA_EXHAUSTED` at once. Transient, rate-limited, timeout, context-too-long, unknown and uncounted outcomes are not recorded at all, so they neither fill nor drain the window.
- **Open:** the model is not called. `CascadingModelExecutor` skips the step (trace `status: circuit_open`, `eddi.llm.cascade.escalations{reason=circuit_open}`, `cascade_escalation` SSE event) and the next step runs; with no step left it returns the best response so far or throws `LlmCircuitOpenException` (a `LifecycleException` carrying the class that opened the circuit). On the non-cascade path `LlmTask` throws the same exception inside the `onError` guard, so `onError: fallback` serves the fallback with no model call.
- **Half-open:** after the cool-down exactly one probe turn is let through (a concurrent turn is denied); success closes, a counted failure re-opens, an uncounted outcome (a transient error, a HITL pause) releases the probe, and a probe whose result never arrives is abandoned after one more cool-down. A late result from a turn that started before the circuit opened cannot move it.
- **R5 hook:** the `ReaskGate` is now live. A circuit that is open or half-open for invalid output refuses the same-model re-ask (`eddi.llm.recovery{outcome=skipped_breaker}`), so a probe asks once and the cascade moves on. Each cascade step carries its own gate (`StepRecovery.gate`), because the old gate did not know which model it was asked about.
- **Invalid output is looked at directly** (`FormatRetryRunner.classifyJsonReply`) for tasks with no re-ask policy, so a model that never returns valid JSON is seen whatever the `responseValidation` settings are.
- **Alerting:** one ERROR log line per circuit **open** (agent, version, provider, model, class, EDDI's reason; never model output); half-open and closed are INFO. Metrics: `eddi.llm.circuit{state,class}` (transitions), `eddi.llm.circuit.skipped{class}`, gauge `eddi.llm.circuit.open`; two panels on the Full Metrics dashboard and a section in `docs/metrics.md`.

### Design decisions

- **A new class rather than reusing the MCP/A2A breakers.** Both are private fields of their managers: a consecutive-failure counter per server URL, no half-open state, no probe, no per-class accounting. "N of the last M failed the same way" plus a single concurrent probe could not be added without rewriting both, so `LlmCircuitBreakers` is the reusable one; migrating the other two onto it is a separate, behaviour-visible change.
- **A ticket per turn** (`acquire` then `success` / `failure` / `release`, settle-once) instead of a bare "record" call, so a probe is tied to the turn that holds it and late results from other turns are ignored. A disabled breaker hands out a no-op ticket, so callers have no `if (enabled)`.
- **Transition-based, not state-gauge-only, metric,** plus a gauge of circuits not closed: the counter drives alerting on "just opened", the gauge answers "is anything open now".
- **ERROR only for open.** The plan says one ERROR line per state change; a recovery logged at ERROR would page an operator for good news, so half-open and closed are INFO.
- **Registry is injected by an initializer method** on `LlmTask` (the same reason `attachmentForwarder` is a field): the many direct-construction tests are untouched, and a null registry means no guard.
- **"A fallback is configured"** is read as `onError: fallback`. A `responseValidation` `fallbackAction` still applies to a reply that was produced; an open circuit produces none, so it ends in the `onError` path (default `error`: fail fast with the class).

### Not done / follow-ups

- **Webhook / Slack notification.** There is no generic operator-notification hook: the only notifier infrastructure is the HITL approval channel, which is bound to a conversation's pending approval. Alerting is the ERROR log line and the metric; a generic hook would be a separate change.
- The breakers are per node. A shared (NATS/DB) state would make a cluster open at once; not needed for the failure modes here, each node sees the same failures within a window.
- The Platform Operator prompt is unchanged: it points the operator at the `langchain` and `model-cascade` docs for LLM troubleshooting, and both now describe the breaker.

```decision-log
| 2026-10-06 | R8 circuit breaker is a new per-model class with half-open single-probe, not a generalisation of the MCP/A2A breakers | Those are private consecutive-failure counters with no half-open state or per-class window; an LLM model needs N-of-M by class and one concurrent probe | Rewriting both existing breakers onto a shared utility in the same change (behaviour-visible for MCP/A2A) |
```
