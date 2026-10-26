## ✨ feat(llm): spend checks that see every attempt, and per-conversation reservations (2026-10-24)

**Repo:** EDDI · **Branch:** `feat/llm-spend-checks` · Plan item E4 of `planning/evaluated-generation-plan.md`

### What changed and why

The agent's `maxCostPerConversationUsd` and the tenant's monthly budget were checked once per LLM task,
before its first model call, and between tool-loop rounds. Every further paid call of the task ran
unchecked — and, since a task writes its cost into `audit:cost` only once it has finished, even a
check would have missed the attempts before it:

- a same-model re-ask (`FormatRetryRunner`) was bounded only by `maxRetryCostUsd`, priced from per-1M
  prices, and never consulted the conversation's spend;
- a cascade escalation to its next step was bounded only by the cascade's own `maxCostPerRun`;
- an output guardrail `reprompt` and a `classifier` guardrail's model call (input and output) ran
  whatever the ceiling or the tenant budget said;
- nothing reserved the cost of a call in flight, so calls running at the same time would all pass
  the same check.

Now every one of these is admitted by `LlmSpendGate.admit` before it starts, with what the task has
already spent and not yet recorded (earlier attempts at their prices; the primary answer's cost before
a reprompt; earlier cascade steps before an escalation; the tool loop's tool cost before its final
re-ask). A refused re-ask is skipped (`eddi.llm.recovery{outcome="skipped_spend"}`) and the reply is
handled as unresolved; a refused escalation returns the cascade's best answer so far
(`eddi.llm.cascade.ceiling.exceeded{kind="spend"}`) — or, when no earlier step produced one, raises the
spend refusal so `LlmTask` serves the task's fallback answer, as for a refused primary call; a refused reprompt degrades to the existing
"no reprompt could start" block; a refused classifier call is "could not decide" with the new audit
reason `spend_refused`, so its `failMode` applies. Every refusal is counted by the existing
`eddi.llm.spend.refused{reason}` with the existing reasons — no new meter.

**Reservations.** `SpendReservations` (one per loaded `ConversationMemory`, transient) holds the
expected cost of each admitted call until it is closed; `LlmSpendGate.check` and `admit` both count
what is held. It is per conversation and per node — exact for the ceiling, because a conversation's
turns are serialized (coordinator; conversation lease in a cluster) and the ceiling is computed from the
conversation's own steps, not from the cluster `COSTS` KV. The tenant budget is read, not reserved —
but its check now adds the turn's unsettled spend (the current step's `audit:cost` not yet metered,
plus the running task's unrecorded spend), through a new `TenantQuotaService.checkCostBudget(tenantId,
pendingUsd)`: the monthly counter is charged only when a turn settles, so without it every paid call of
a turn was compared with the counter as it stood before the turn.
`LlmSpendGate.admit(memory, pendingUsd, expectedUsd)` and `TaskSpend` (a `PaidCallGate`) are the
entry points for E5 (`forEach`) and E7 (media).

**Behaviour kept, now documented precisely.** A call is admitted while the spend so far — recorded,
unrecorded and in flight — is *below* the ceiling; its own price is not compared, so the call that
crosses the ceiling completes and the next is refused. No configuration field was added; an agent
without a ceiling and with quotas off behaves exactly as before.

### Design decisions

- One gate, one rule: re-asks, escalations, reprompts and classifier calls use the same `admit` the
  primary call's `check` is built on, and the same reasons and metric.
- The reprompt check sits in `GuardrailService.runOutput`, behind `GuardrailContext.spend()`, so every
  execution branch (plain, tool loop, cascade, HITL resume) gets it; `Reprompter.Attempt` now carries
  the reprompt's priced cost.
- A phase's unrecorded spend lives in a `TaskSpend` that is discarded once the phase's cost is
  recorded, so no amount is counted both there and in `audit:cost`.
- Expected costs are cheap estimates (previous attempt's price; the classifier prompt at its input
  price). They only matter for concurrent calls, of which there are none inside one task today.

### Files

[`SpendReservations.java`](../../src/main/java/ai/labs/eddi/engine/memory/SpendReservations.java),
[`LlmSpendGate.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/LlmSpendGate.java),
[`TaskSpend.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/TaskSpend.java),
[`PaidCallGate.java`](../../src/main/java/ai/labs/eddi/modules/llm/guardrails/PaidCallGate.java),
[`FormatRetryRunner.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/FormatRetryRunner.java),
[`CascadingModelExecutor.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/CascadingModelExecutor.java),
[`GuardrailService.java`](../../src/main/java/ai/labs/eddi/modules/llm/guardrails/GuardrailService.java),
[`ClassifierGuardrail.java`](../../src/main/java/ai/labs/eddi/modules/llm/guardrails/impl/ClassifierGuardrail.java),
[`LlmTask.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/LlmTask.java); docs in
[`langchain.md`](../langchain.md#per-conversation-llm-cost-ceiling), [`llm-resilience.md`](../llm-resilience.md),
[`tenant-quotas.md`](../tenant-quotas.md), [`metrics.md`](../metrics.md); Platform Operator prompt and
revision 37.

### End-to-end tests

- [`LlmSpendChecksIT`](../../src/test/java/ai/labs/eddi/integration/LlmSpendChecksIT.java) and its
  PostgreSQL twin [`PostgresLlmSpendChecksIT`](../../src/test/java/ai/labs/eddi/integration/postgres/PostgresLlmSpendChecksIT.java)
  drive a deployed agent against a WireMock OpenAI endpoint whose `usage` blocks are priced by the task:
  the ceiling refusing the turn after the one that crosses it; a re-ask, a guardrail reprompt, a cascade
  escalation and an output `classifier` call each refused on the task's unrecorded spend (each with a
  control that makes the call when nothing stops it); the tenant budget charged by one conversation and
  refusing the next, and refusing a re-ask on the same turn's unsettled spend; and an under-budget turn
  whose `audit:cost` and tenant usage equal the reported usage at the configured prices. Each asserts the
  client's answer, the stored conversation and the exact number of provider requests.

### Not done / next

- The cascade's `judge_model` confidence call is still unchecked (it is priced through the cascade's run
  cost and the next step's check sees it).
- Tenant-level reservations would need the quota store: a turn's own spend is now counted, but another
  conversation's unsettled turn is not, so concurrent conversations can each complete one call past the
  tenant budget.
- Operator revision 37 will collide with other open branches that bump it; re-bump at merge.
