## SERVICE convergence judge charges the tenant budget (2026-11-15)

**Repo:** EDDI · **Branch:** `fix/service-judge-tenant-charge`

**Gap.** `ServiceConvergenceJudge` booked its model-call cost on the group ledger only
(`GroupCostLedger.recordSystemCost`). Unlike the window and stance summaries it never went through
`SystemSpendMeter`, so `judge: SERVICE` spend was neither counted against nor gated by the tenant's
monthly ceiling (`maxMonthlyCostUsd`), and never reached the spend sinks.

**What changed**

- `ServiceConvergenceJudge` passes the summaries' pre-call gate — `SystemSpendMeter.admit()` (fails closed)
  and `UnpricedCallPolicy.check(...)` (site `group_judge`). A refusal records `outcome=error` and returns
  "not converged"; the discussion is never failed.
- After the call it charges through `SystemSpendMeter.charge` under the existing key
  `system:judge:<round>:<phaseIndex>:<repeat>` (also the spend event's detail). The meter keeps the group-ledger
  booking (by replacement), charges the tenant the increase, and carries a charge the quota store could not take
  (`accountingUnavailable`) to the next group system charge.
- `JudgeCallPricing.usage(...)` exposes the usage map `cost(...)` prices, so the tenant is charged the same
  amount the ledger used to book.
- `SystemSpendMeter.install` is public (a test seam for callers' tests in other packages).
- Docs: [group-service-judge.md](../group-service-judge.md), [tenant-quotas.md](../tenant-quotas.md).

**Design decisions.** No hard-ceiling `CostReservation` for the judge: the group summaries do not take one either
(they use the read-only gate and are charged at settle, as `tenant-quotas.md` documents for side calls), so the
judge follows the same path and is listed among the unreserved side calls.

**Tests.** `ServiceConvergenceJudgeTest$TenantCharge` — tenant charged with cost and attribution, re-run idempotence,
spent budget refuses, unreadable budget fails closed, unavailable accounting carried.
