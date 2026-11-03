# Tenant Quotas

EDDI meters usage per tenant and can refuse work once a tenant passes its limits. The limits
themselves are stored per tenant and are editable at runtime through the admin API below — you do
not need a restart to raise one tenant's ceiling.

The deployment-wide defaults that seed a new tenant live in
[configuration-reference.md](configuration-reference.md) under `eddi.tenant.quota.*`. A tenant row
is created from those defaults the first time the tenant is seen; editing the properties afterwards
does not rewrite rows that already exist, and a mismatch between a stored row and the configured
default is reported in a startup WARN.

## What is metered

| Limit | Meaning | Unlimited |
|---|---|---|
| `maxConversationsPerDay` | New conversations started in the current calendar day | `-1` |
| `maxAgentsPerTenant` | Agents the tenant may own | `-1` |
| `maxApiCallsPerMinute` | API calls in the current calendar minute | `-1` |
| `maxMonthlyCostUsd` | Dollar spend in the current calendar month | `-1` |
| `enabled` | Whether enforcement is active for this tenant at all | — |

Windows are **calendar-aligned, not sliding**. The daily counter resets at the start of the day in
the store's clock, the per-minute counter at the start of the minute, and the cost counter at the
start of the month.

## The monthly cost budget

`maxMonthlyCostUsd` is enforced. It works in two halves:

- **Metering (after the spend).** Every settled conversation turn charges its priced cost to the
  tenant's monthly counter — on a normal or streamed `say`, a rerun, the `CONVERSATION_START` turn
  and a HITL resume. Group member turns and sub-agent conversations are conversations of their own
  and are metered the same way, once. A turn's cost is the step's `audit:cost`: every LLM call of
  the turn priced at its configured prices plus tracked tool cost. Each step remembers what it has
  already been charged (`audit:metered_cost`), so a turn paused for human approval and resumed is
  charged once in total. Dream memory consolidation runs outside conversations and charges its
  priced calls directly.
- **Gating (before the next spend).** Once the month's total reaches the limit, every LLM task
  skips its model call and serves its configured fallback answer (`responseValidation.fallbackMessage`,
  or the default sentence), recording `llm:spend_refused:<taskId>` on the step; a task already
  running sends no further re-ask, cascade escalation or guardrail reprompt, and a `classifier`
  guardrail does not call its model (reason `spend_refused`); tool calls are
  refused with an error result; and Dream stops consolidating. Nothing already running is cut
  short, so the turn that crosses the limit finishes and the total can end a little above it —
  unless you turn on the [hard ceiling](#hard-ceiling-cost-reservation-opt-in), which refuses the
  call that would cross the limit instead.

A turn that crosses the limit is never failed over it, and neither is a turn whose cost could not
be recorded because the quota store was down — the spend already happened. Its cost is not lost:
the turn's step records it as `audit:pending_cost`, and the conversation's next settle charges it
together with its own cost (the pending amount carries forward until a charge succeeds). It is
charged to the month that settle runs in: a charge lost to an outage across a month boundary lands
in the new month, because the closed month's counter has already been reset and there is no
window left to add it to. Undo and redo do not lose it either: each copies the pending amount of
the step it moves aside onto the step that becomes current, so a rerun or a next `say` after an
undo charges what is really owed — not an amount already recovered, and not an older value. Dream consolidation has no conversation to carry
it on, so a Dream charge lost to an outage is held in memory on the node and added to its next
priced consolidation charge — a restart before the store recovers loses it. A store
outage at the *gate* refuses the model call (fail closed), as below.

### Only priced spend counts

A model call is metered at its price — the config's `inputPricePer1M` / `outputPricePer1M`
(LLM task, cascade step, cascade judge, guardrail, group summary, Dream), completed by the
deployment's [price catalog](model-pricing.md). EDDI ships no active prices: with an empty
catalog, a model with no price on its config meters **$0** in token cost, so its calls alone
never reach the budget — but the priced tools a turn calls are charged to the same counter and
can. Price every model you want the budget to bound, in the config or the catalog; unpriced
calls are reported at deploy time, per step and as `eddi.llm.calls.unpriced`, and the opt-in
[strict mode](model-pricing.md#strict-mode) refuses them while a budget applies. Anthropic and
Bedrock cache tokens and Gemini thinking tokens are counted — see
[What the providers report](model-pricing.md#what-the-providers-report).

Group transcript window summaries and stance summaries are charged to the tenant too, and are
skipped (plain truncation, an extracted stance) once the budget is spent.

These calls are **not** metered today, priced or not:

| Call | Why |
|---|---|
| Embeddings (RAG retrieval, knowledge-base ingestion) | Embedding calls are not priced anywhere |
| A turn stopped by **lease loss** in cluster mode | Nothing of the turn is stored; the node that took the conversation over runs it again and meters that run |

A replayed idempotent turn (`Idempotency-Key`) is answered from the stored outcome and is not
metered again. A turn whose model phase **failed** is metered for what it spent before the failure
(the answered tool-loop rounds, the tools that ran, the cascade steps that were tried); the rolling
summary and `toolResponseLimits` summarization are metered at the task's prices when they run on
the task's own model, and at the catalog price of their own model otherwise.

To bound one conversation rather than the whole tenant, set the agent-level
`maxCostPerConversationUsd` — see [LLM Configuration → Per-conversation LLM cost
ceiling](langchain.md#per-conversation-llm-cost-ceiling).

## Failing closed

If the quota store cannot be read — the database is down, a query fails — the gate **denies** the
request rather than letting it through. Both the MongoDB and the PostgreSQL store answer with the
same message, `Quota accounting unavailable — denying request for safety`, surfaced as `503`, so a
caller cannot tell which backend refused and cannot get a different answer by retrying against
another node.

The agent gate (`maxAgentsPerTenant`) also counts the deployed agents, from the deployment records and every node's runtime registry (any `READY` version, not only the newest — a failed newer version does not hide a live older one). When that count cannot be read the deploy is refused with the same `503`, counted on `eddi_tenant_quota_unavailable_total{type="agent"}` — but only while an agent limit is in force; with no quota, a disabled one or `maxAgentsPerTenant: -1` the outage blocks nothing.

The monthly cost gate is the exception to the `503`, because it runs inside a turn: when the
month's spend cannot be read, an LLM task skips its model call and serves its fallback answer
(`llm:spend_refused:<taskId>` with `reason: tenant_accounting_unavailable`), a tool call gets an
error result, and Dream stops consolidating. The turn itself completes. Each refusal also counts on
`eddi_tenant_quota_unavailable_total{type="cost"}`, the same on both backends.

If the budget check itself throws instead of answering — a defect, not an outage — both gates
still refuse (the model call serves its fallback answer, the tool call gets an error result), but
under `reason: tenant_check_error`, counted on `eddi_llm_spend_refused_total{reason="tenant_check_error"}`
and **not** on `eddi_tenant_quota_unavailable_total`. It is logged at ERROR, with the stack trace
at most once a minute.

## Hard ceiling: cost reservation (opt-in)

By default the budget is charged **after** each settled turn, so the turn that crosses the limit
finishes and the month can end above it — and several conversations running at the same moment (on
one node or on many) all read the same remaining budget and can all pass. With a small monthly
budget that overshoot is the whole margin. The reservation mode closes it:

```properties
eddi.tenant.quota.cost-reservation.enabled=true
```

It is **off unless you set it**; with it off, nothing in this section applies and no extra store
operation runs. It applies to a tenant whose quota is `enabled` with a `maxMonthlyCostUsd` of `0` or
more (`-1` has nothing to hold against).

**How it works.** Before a call that costs money, EDDI *reserves its maximum cost* against the
tenant's monthly counter, in one atomic store operation: the call is granted only if
`cost so far + everything already reserved + this call's maximum <= limit`. Two nodes asking for the
last dollar cannot both get it. When the reservation does not fit, the call is **refused** — an LLM
task serves its fallback answer (`llm:spend_refused:<taskId>`, reason `tenant_budget`), a tool call
gets an error result — and nothing is spent.

| Call | Reserved | When |
|---|---|---|
| An LLM task's model phase | prompt tokens x input price + the model's output-token cap x output price, for every round the phase can run (an agent-mode task: `1 + maxToolIterations` rounds, each later round carrying up to `maxToolContextTokens` of tool output; a cascade: every step) | Once, before the first model request; a tool loop resumed after human approval reserves the rounds it has left |
| A priced tool call (`toolPricing`, or a built-in's default price) | The tool's price | Right before the tool runs |

The prompt is sized from the messages about to be sent at three characters per token, which errs
high. A model without a price (in the config or the [catalog](model-pricing.md)) reserves $0, exactly
as it meters $0 — only priced spend is bounded. A model with no output-token cap of its own is sized
with `eddi.tenant.quota.cost-reservation.default-max-output-tokens` (default `4096`); set
`maxTokens` on the model and the reservation shrinks with it.

**Settling.** A reservation is *not* released when the call ends: the actual cost is charged to the
counter only when the turn settles, and releasing earlier would leave that spend counted nowhere.
So: when each LLM task finishes, its hold is **shrunk to the actual cost** (the difference goes back
at once); when the turn settles, the actual cost is **charged first** and then every hold of the turn
is released — the money is always counted as spent before it stops being counted as held. A turn
that pauses for human approval releases its holds at the pause and takes new ones on resume.

**Fails closed.** If the quota store cannot answer, the reservation is refused (reason
`tenant_accounting_unavailable`, counted on `eddi_tenant_quota_unavailable_total{type="cost"}`), the
same way the read-only gate refuses. A hold that is never released — the node died mid-turn — does
not pin the budget forever: the held total is dropped once it is older than
`eddi.tenant.quota.cost-reservation.ttl` (default `PT10M`; set it above your longest turn). If a
release fails the hold just waits out that TTL, which errs on the safe side.

**What is *not* reserved.** The mode bounds the model phase of each LLM task and priced tool calls.
The smaller paid side calls — a same-model re-ask, a cascade judge, a guardrail classifier, a rolling
summary, group transcript summaries, Dream — still go through the ordinary read-only gate and are
charged at settle, so they can add a little on top of the reservation; the reserved amount of the
task they belong to normally already covers them. Embeddings are not priced. The limit is therefore a
hard ceiling for the calls that carry the cost, not a bit-exact one.

| Setting | Default | Meaning |
|---|---|---|
| `eddi.tenant.quota.cost-reservation.enabled` | `false` | Turn the mode on |
| `eddi.tenant.quota.cost-reservation.ttl` | `PT10M` | How long an unreleased hold may pin the budget |
| `eddi.tenant.quota.cost-reservation.default-max-output-tokens` | `4096` | Output cap assumed for a model that sets none |

**Operating it.** The price of the guarantee is that near the limit the budget is held back by what
running turns *might* spend: with $3 left and a turn that can cost $2.50, a second turn that can cost
$1 is refused until the first settles. Keep `maxTokens` and `maxToolIterations` realistic. Metrics:
`eddi_tenant_cost_reservation_total{outcome}` (`reserved`, `refused`, `unavailable`, `release_failed`)
and `eddi_tenant_cost_reservation_usd_total{direction}` (`held`, `released`) — see
[Metrics](metrics.md). A rise in `refused` while `eddi_tenant_usage_cost_total` is still well below
the limit means holds are bigger than real turns: lower the output caps or the tool-loop depth.

Storage: the held total lives on the same per-tenant usage record as the month's cost (MongoDB:
`reservedUsd` / `reservedSince` on `tenant_usage`; PostgreSQL: the same columns, added to an
existing table on first start), updated with a single conditional write on both backends.

## Admin API

All five operations sit under `/administration/quotas` and require the `eddi-admin` role.

| Method | Path | Description |
|---|---|---|
| `GET` | `/administration/quotas` | List every configured tenant quota |
| `GET` | `/administration/quotas/{tenantId}` | Read one tenant's quota |
| `PUT` | `/administration/quotas/{tenantId}` | Create or replace one tenant's quota |
| `GET` | `/administration/quotas/{tenantId}/usage` | Read the tenant's current counters |
| `POST` | `/administration/quotas/{tenantId}/usage/reset` | Zero the tenant's counters |

### Quota shape

```json
{
  "tenantId": "acme",
  "maxConversationsPerDay": 5000,
  "maxAgentsPerTenant": 50,
  "maxApiCallsPerMinute": 600,
  "maxMonthlyCostUsd": -1,
  "enabled": true
}
```

`-1` means unlimited for any of the four numeric limits. A `PUT` rejects a negative value other
than `-1` with a `400`.

### Raising one tenant's ceiling

```bash
curl -X PUT "http://localhost:7070/administration/quotas/acme" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{
    "tenantId": "acme",
    "maxConversationsPerDay": 20000,
    "maxAgentsPerTenant": 50,
    "maxApiCallsPerMinute": 600,
    "maxMonthlyCostUsd": -1,
    "enabled": true
  }'
```

The write takes effect immediately: the service caches quotas but drops the tenant's entry on
every write, so the next check reads the new row.

### Usage shape

```json
{
  "tenantId": "acme",
  "conversationsToday": 143,
  "apiCallsThisMinute": 7,
  "monthlyCostUsd": 0.0,
  "minuteWindowStart": "2026-09-07T14:32:00Z",
  "dayStart": "2026-09-07T00:00:00Z",
  "costMonth": "2026-09"
}
```

Counters from an expired window read as zero rather than as their stale value, so a `conversationsToday`
of `0` on a tenant that was busy yesterday is correct, not a lost write.

Resetting is an operator escape hatch — it zeroes the stored counters for the current windows and
does not change the limits.

## Not to be confused with `toolQuotas`

A tenant quota is shared by everyone in the tenant. To cap what **one user** may do with a **tool**
within a time window — "100 content improvements per user per day", across conversations and nodes — use
the agent designer's [`toolQuotas`](langchain.md#per-subject-tool-quotas-toolquotas), which is configured
per LLM task and counted per subject. The two stack: a call must pass both.

To give each **team** (a group) its own monthly share of the tenant's budget, use a group's
[`teamBudget`](team-budgets.md). Both apply: a team stops at its own cap or when the tenant's runs
out, whichever comes first.

## See also

- [Configuration Reference](configuration-reference.md) — the `eddi.tenant.quota.*` defaults
- [Metrics & Monitoring](metrics.md) — the quota counters exposed at `/q/metrics`
