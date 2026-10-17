## 💰 feat(tenancy): enforce the tenant monthly cost budget — meter settled turns and Dream runs, gate model calls (2026-10-15)

**Repo:** EDDI (`feat/tenancy-cost-metering`)

### What changed and why

`maxMonthlyCostUsd` was stored, validated, shown in the Manager quotas page and documented as
**not enforced**: `TenantQuotaService.recordCost` had no production caller, so the monthly counter
stayed at zero and the pre-call gate never tripped. It is now real.

- **Metering at the settle points.** New [`TurnCostMeter`](../../src/main/java/ai/labs/eddi/engine/tenancy/TurnCostMeter.java)
  charges the current step's `audit:cost` to the default tenant from the pipeline's completion
  callback — `ConversationService.admitTurn` (say, streamed say, rerun; group member turns and
  sub-agents go through it too), the inline `CONVERSATION_START` turn in `startConversation`, and
  `ConversationHitlService.resumeConversation` (now always passes a completion callback, also for an
  internally driven resume). Only the not-yet-metered part is charged: the step keeps
  `audit:metered_cost`, persisted with the turn, so a turn paused for approval and resumed is charged
  once in total. Skipped for a turn stopped by lease loss (nothing is stored; the new owner re-runs
  and meters it); a replayed idempotent turn never reaches the callback. A denied or unavailable
  `recordCost` never fails the turn — logged and counted on `eddi.tenant.cost.metering{outcome}`;
  a charge the store could not record is carried forward as `audit:pending_cost` on the newest step
  and charged by the conversation's next settle (review finding: an ordinary say turn starts a new
  step, and say turns are persisted as appends, so leaving it on the old step would have lost it).
- **The marker key is `audit:metered_cost`, not `audit:cost:metered`.** Step lookups are prefix
  matches, so a key starting with `audit:cost` would be read back as the step's cost by
  `getLatestData(AUDIT_COST)` (`LlmTask.accumulateCost`, `LifecycleManager`).
- **`audit:cost` no longer depends on the audit ledger.** `LlmTask` only wrote it with
  `eddi.audit.enabled=true`; token usage and cost are now recorded either way
  (`accumulateUsageAndCost`), since the tenant budget charges them.
- **Pre-model-call gate.** New [`LlmSpendGate`](../../src/main/java/ai/labs/eddi/modules/llm/impl/LlmSpendGate.java),
  asked once per LLM task before any model request (plain, tool loop, cascade, streaming). The
  existing `checkCostBudget` gate ran only before tool calls, so an agent without tools never
  stopped, and one with tools kept calling its model to be refused tool after tool. A refusal fails
  soft like the tool-call gate: the model is not called, the task's fallback answer
  (`responseValidation.fallbackMessage`, or the default sentence) is served regardless of `onError`,
  the step records `llm:spend_refused:<taskId>`, and `eddi.llm.spend.refused{reason}` counts it.
  Cheap: with quotas off it is a cache hit; on, one monthly-cost read per LLM task. The circuit
  breaker ticket is not acquired for a refused call, and the rolling summary is not updated.
- **Dream (A2).** `DreamService` charges each consolidation call at the dream config's own prices
  (`pricedCost` — never the upper-bound estimate it uses for `maxCostPerRun`, so an unpriced config
  meters $0) and stops consolidating once the tenant budget is spent (not a failed cycle).
- **Caveats removed.** The WARN in `RestTenantQuota`, the "not enforced" notes in `TenantQuota`,
  `checkCostBudget` (which also wrongly said `AgentOrchestrator` called it per LLM turn) and
  `recordCost`, and the note in [`tenant-quotas.md`](../tenant-quotas.md), replaced with an
  accurate section listing the call classes that are **not** metered: rolling summary,
  tool-response summarization, group window/stance summaries, embeddings, the pre-pause segment of
  a tool-call HITL pause, lease-lost turns. The `MemberTurnExecutor` comment calling `AUDIT_COST` a
  running total is corrected (it is per step).
- **Docs/Operator.** `tenant-quotas.md`, `langchain.md` (prices drive the budget),
  `metrics.md`, `configuration-reference.md`; the Platform Operator prompt says the cap is enforced
  and priced-only (operator revision bumped; 18 at merge time).

**Tests:** `TurnCostMeterTest` (delta, pause→persist→resume once, prefix trap, outage retry,
exception swallowed, over-budget marked, in-memory end to end), `ConversationServiceCostMeteringTest`
(settle point through the real say path, unpriced, idempotent replay, lease loss),
`LlmTaskSpendGateTest`, `DreamServiceTest` additions, `ConversationServiceResumeTest` (resume
callback meters), `RestTenantQuotaTest` (no "NOT enforced" warning), `LlmTaskAuditLedgerTest` (cost now bypasses the collector gate),
`TenantCostMeteringIT` (Mongo + Postgres Testcontainers: two priced turns cross a $0.01 budget and
the gate then refuses; and a priced turn that pauses, is persisted to and reloaded from the real
conversation store, and resumes with further cost is charged exactly the step total, with
`audit:metered_cost` on the persisted step).

### Known gap / follow-up

`GroupCostLedger` reads the member conversation's **last step's** `audit:cost` and replaces the
member's entry with it, so a member that spoke in several turns is attributed only its latest turn
— the group's `totalCost` (and `maxCostPerDiscussion`) under-counts. Not changed here (tenant
metering does not use it).

```decision-log
| 2026-10-15 | Tenant cost is metered per settled step from the completion callback, with an `audit:metered_cost` marker; LLM tasks get a soft pre-call gate (fallback answer) | `recordCost` had no caller; the tool-call gate alone never stopped a tool-less agent | Metering per LLM task (double counts — `audit:cost` is already a per-step total); metering in `GroupCostLedger` (member turns already settle through `say`); failing the turn on refusal (the tool gate is soft; a deterministic reply is friendlier) |
```

## 💰 feat(llm): per-conversation LLM cost ceiling `maxCostPerConversationUsd` (2026-10-15)

**Repo:** EDDI (`feat/tenancy-cost-metering`)

### What changed and why

A new optional **agent-level** field, `maxCostPerConversationUsd` on
[`AgentConfiguration`](../../src/main/java/ai/labs/eddi/configs/agents/model/AgentConfiguration.java),
bounds the priced spend of one conversation — the sum of every step's `audit:cost`. Absent (every
existing agent) means unlimited and behaviour is unchanged; `0` allows no priced spend; a negative
value is unlimited. The task-level, tool-only `maxBudgetPerConversation` is untouched.

- **Carrier.** `AgentStoreClientLibrary` copies it onto the deployed `Agent`, which puts it on the
  memory in `startConversation` and in `continueConversation` (every say, rerun and HITL resume
  passes there, so the running version's value always wins and a version without one clears it).
  The memory carrier is transient and typed `OptionalDouble`, not a nullable `Double`: a mocked
  memory hands back `0.0` for a `Double`, which here means "no spend allowed".
- **Checks.** `LlmSpendGate` compares the conversation's spend with the ceiling before the tenant
  budget, once per LLM task before any model call (plain, streaming, tool loop, cascade); a refusal
  serves the fallback answer and records `llm:spend_refused:<taskId>` with
  `reason: conversation_ceiling`. Because that gate only sees a tool loop's cost when the loop ends,
  `ToolLoopRunner` repeats the check between rounds (prior spend + the loop's tokens at the task's
  prices + its tool cost) and ends the loop with a fixed answer saying the completed calls took
  effect. Counted on `eddi.llm.spend.refused{reason="conversation_ceiling"}`.
- **Docs.** [`langchain.md`](../langchain.md#per-conversation-llm-cost-ceiling) (new section),
  `tenant-quotas.md`, `metrics.md`.

**Tests:** `ConversationCostCeilingTest` (old JSON without the field, round trip, carrier set and
cleared, spend summed over steps without the metering marker, gate order and edge values),
`LlmTaskSpendGateTest` (ceiling refuses without asking the tenant budget; no ceiling sums nothing),
`ToolLoopRunnerCostCeilingTest` (stops at the ceiling, prior spend counts, no ceiling unchanged),
`AgentStoreClientLibraryMemoryToolsTest` (deployed agent carries the value).

### Not done

No Manager field: the agent editor has no cost/limits section and no nullable-number input, and
`turnDeadlineMs` — the nearest agent-level setting — is not editable there either. The value can be
set through the agent JSON. Follow-up: a "Limits" section for both.
