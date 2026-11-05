## 💰 feat(cost): per-run tool quotas and an opt-in hard tenant ceiling (2026-11-03)

**Repo:** EDDI (`feat/cost-hardening`, branched from the local `feat/operator-mcp-integration` because the price resolver
and catalog PRs were still open)

### What changed and why

Two gaps in cost control, found while planning to run EDDI on EDDI inside a fixed monthly budget.

**1. `toolQuotas` `window: "RUN"`.** Tool quotas only had fixed UTC windows (`PT1M`..`P31D`), so "20 searches per scout
per run" had to be approximated with a daily cap. A rule may now say `"window": "RUN"`: the counter belongs to one run
and the next run starts at zero. The run is a scheduled fire (new context key `scheduleFireId`, set by
`ScheduleFireExecutor` on the startup turn and on every fire turn, so a persistent conversation counts per fire), else a
group conversation (`groupConversationId`, already set on every member turn), else the conversation. Mechanics:
`ToolQuotaRules.Resolved.perRun()`, the run id hashed into the counter name by `ToolQuotaService.keyFor` with window
start `0` (no schema change in either store), rows expire 31 days after the first call. Validated at save time
(`ToolQuotaConfigValidator`): `RUN` is case-insensitive, a pool cannot mix `RUN` with a time window. The refusal names
the per-run limit and no reset time. `docs/langchain.md` gets a "Per-run caps" section.

**2. `eddi.tenant.quota.cost-reservation.enabled` (default `false`).** `maxMonthlyCostUsd` was only charged after each
settled turn, so the crossing turn finished and overshot, and concurrent turns all read the same remaining budget. With
the mode on, an LLM task's model phase and each priced tool call *reserve their maximum cost* against the tenant counter
before running, and are refused when it does not fit.

- `ITenantQuotaStore.tryReserveCost` / `releaseCost`: one atomic conditional write per backend (MongoDB: `$expr` filter
  plus pipeline update on `tenant_usage`; PostgreSQL: one `UPDATE ... WHERE cost + held + amount <= limit`, two new
  columns added with `ADD COLUMN IF NOT EXISTS`; in-memory under the tenant lock). Unreleased holds (dead node) are
  dropped by an epoch (`reservedSince`) older than `eddi.tenant.quota.cost-reservation.ttl` (`PT10M`); a release names
  its epoch, so a late release cannot cancel somebody else's hold.
- `TenantQuotaService.reserveCost` / `holdForCall` / `shrinkHolds` / `releaseHolds`; fails closed (a store that cannot
  answer refuses, counted on `eddi_tenant_quota_unavailable_total{type="cost"}`). Metrics
  `eddi_tenant_cost_reservation_total{outcome}` and `eddi_tenant_cost_reservation_usd_total{direction}`.
- `ModelCallBound` sizes the model phase: prompt (three characters per token) x input price + output-token cap x output
  price, over `1 + maxToolIterations` rounds for an agent-mode task (later rounds carry the tool-context cap) and every
  step of a cascade. Unpriced models reserve $0, like metering.
- Holds live on the turn (`IConversationMemory.getTenantCostHolds`), are shrunk to the actual cost when each LLM task
  finishes (`LlmSpendGate.settleHolds`) and are released in `TurnCostMeter.meterSettledStep` *after* the charge, so spend
  is always counted as spent before it stops being counted as held.
- Wired into `LlmTask` (before the model phase, and when a paused tool loop resumes) and `ToolLoopRunner` (priced tool calls, price from the new
  `ToolCostTracker.priceOf`).

**3. Manager UI.** The LLM task editor has a new "Tool quotas" section (`task-tool-quotas.tsx`): subject template,
per-tool rules with limit, window (per run, hour, day, 7 days, 31 days, or a stored custom duration kept as-is) and pool,
plus the note that a per-run cap is not an abuse limit. Nothing is written until a rule is added. The Quotas page shows the
budget running turns hold (`reservedUsd`, new on `UsageSnapshot` and filled by all three stores) as a second segment on
the monthly-cost bar, and explains the opt-in startup property next to the cost limit. 16 new i18n keys in all 11 locales.
A Grafana panel (`Tenant cost reservations (hard ceiling)`) charts the new meters.

### Design decisions

- A deployment property, not a per-tenant field: it needs no change to the tenant quota record, REST shape or its two
  stores' document layout, and a cluster agrees on it by construction.
- A reservation is held until the turn is charged, not until the call ends: the actual cost only reaches the counter at
  settle, so releasing on call end would leave a window where the spend is counted nowhere.
- The small paid side calls (re-ask, judge, guardrail classifier, summaries, Dream) are **not** reserved; they keep the
  read-only gate and are charged at settle. The mode is a hard ceiling for the calls that carry the cost, not bit-exact
  (documented in `docs/tenant-quotas.md`).
- The `RUN` id comes from runtime context, so it bounds an autonomous run, not a hostile end user (documented); fixed
  windows remain the tool for per-user fair use.
- Platform Operator: system prompt teaches both (revision 54, fingerprint `00f0946c7e085db5`; 55 after the review follow-ups); the allow-list is
  unchanged (the property is startup configuration, the `RUN` rule is part of the agent document).

### Not done / next

- Anthropic Batch API support was explicitly out of scope.
- Store ITs (`TenantQuotaStoreParityTest`, Mongo and PostgreSQL) are written; they need Docker.
- Not reserved: embeddings (unpriced anyway), the side calls above and the HITL REVISE model call, which keep the
  read-only gate. A resumed tool loop does reserve (`ModelCallBound.resumeMaxUsd`).

### Review follow-ups (2026-11-05)

- Hold expiry is by the **newest** hold, not the shared epoch: a busy tenant's overlapping turns kept the held total
  above zero past the TTL and the next reservation dropped holds of calls still running. New `reservedLastAt` /
  `reserved_last_at` (added by `ALTER ... IF NOT EXISTS`) in all three stores; a dead node's hold on a continuously busy
  tenant now waits for a quiet TTL (fails closed). Parity test with a stepping clock. Docs say the snapshot's
  `reservedUsd` can include such a hold, and that a model hold is an estimate.
- `window: "RUN"` on a scheduled fire is keyed by `scheduleId@lastFired` (`ScheduleFireExecutor.runIdOf`), so the retries
  of one due fire share one run instead of each getting a fresh counter.
- The resumed LLM task settles its holds when it finishes (as `executeTask` does).
- `maxToolContextTokens <= 0` (guard off) sizes a hold with the default tool-context cap instead of zero.
- Manager: no subject-only `toolQuotas` (subject disabled until a rule exists; removing the last rule drops it); a rename
  onto another rule's name is held as a draft and flagged instead of silently dropping that rule; the budget status
  subtracts holds from what is left; the cost-ceiling note and the operator prompt no longer promise an un-overshootable
  budget. 2 new i18n keys and one amended, in all 11 locales. Grafana: the USD series has its own unit and axis.
- Platform Operator: revision 55 (fingerprint `a5a444f4385d0702`) for the prompt change.
