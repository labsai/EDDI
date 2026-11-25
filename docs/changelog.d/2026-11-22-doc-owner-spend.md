## feat(documents): owner-keyed spend metering, daily document spend ceilings and their erasure participant (2026-11-22)

**Repo:** EDDI (`feat/doc-owner-spend`). Work package WP-4b.1 of the PDF documents plan. It depends on WP-2a.2 (#1502). Nothing user-visible yet: no code path charges document spend until the background summary job (WP-4b.2) calls it.

### What changed and why

Model calls that document work makes **outside a conversation turn** (the large-document summary job, later the modality bridge) need a payer. `SystemSpendMeter` could only charge a group conversation (plan X6); it now has an owner-keyed variant, and two cumulative daily ceilings bound such spend per user and per team.

- **`SpendOwner(ownerKey, userId, teamSpaceId, tenantId)`** (`engine/pricing`) names whom the work is charged to: the owner snapshot taken when the work was requested, not "the system".
- **`SystemSpendMeter.charge(SpendOwner, key, override, usage, detail)`**: one call prices the usage, adds it to the owner's user and team day buckets, charges the owner's tenant (null = the default tenant) and fires a `SpendEvent` of the new kind **`DOCUMENT`** with the user id. It never throws. There is no ledger to charge by replacement, so every call adds its full cost and `key` is only a label for log lines. An unpriced model costs $0, is counted as an unpriced sighting under the new `site` `document`, and its call still counts in the buckets. The group variant is unchanged (`SystemSpendMeterTest` untouched and green).
- **Carried tenant charges are grouped by tenant.** The carry-over queue for charges the tenant counter could not take used to sum the whole batch into one `recordCost` on the default tenant. With owners that name their own tenant, that would have billed one tenant for another's charge. Each tenant is now charged its own entries; a batch of group charges alone (all the default tenant) behaves exactly as before.
- **`DocumentSpendGate`** (`engine/documents/spend`): `admit(owner, estimateUsd)` checks, in this order, the tenant's monthly budget (`TenantQuotaService.checkCostBudget` with the estimate as pending spend), the user's daily ceiling, then the team's; the per-document ceiling stays the caller's. A refusal names which ceiling refused, the spend so far, the ceiling and the estimate, with no user or team id in the message. It fails closed when a budget or counter cannot be read. `record(owner, usd)` is called by the meter; a store failure there is logged, never thrown.
- **`IDocumentSpendStore`** with **MongoDB** (`document_spend_days`, `_id = kind:day:subject`, `$inc` upsert with the duplicate-key retry of `MongoTeamBudgetStore`, indexes built on first use within 10 s, TTL backstop) and **PostgreSQL** (`document_spend_days`, `INSERT … ON CONFLICT DO UPDATE … RETURNING`, schema on first use) implementations, selected in `DataStoreProducers`. One row per (kind `USER`|`TEAM`, subject, UTC epoch day) with dollars and a call count. Every write is one atomic increment.
- **Failure handling.**
  - A failure of the daily counters, including a malformed `eddi.documents.spend.*` value, never costs the tenant charge: the meter guards the bucket update on its own.
  - Admission refuses with `ACCOUNTING_UNAVAILABLE` when the ceilings cannot be read.
  - A failed retention delete after a committed increment is logged and never reported as a lost charge.
  - A MongoDB index pass that fails at first use is retried 5 minutes later, not only after a restart.
- **Retention on write.** Each write deletes the same subject's buckets older than `eddi.documents.spend.retention-days` (35, today included) and, at most every 10 minutes per store instance, every subject's.
- **Erasure ships with the data (I10, B7).** `DocumentSpendGdprParticipant implements IGdprParticipant, UserErasureParticipant`, name `documentSpend`: erases every `USER` bucket of the erased id, exactly as given; team buckets hold no user id and stay; `stopInFlightWork` returns 0. The export carries day, dollars and calls under `days`, capped at 1,000.
- **Operator properties** `eddi.documents.spend.max-per-user-per-day-usd` and `.max-per-team-per-day-usd` (unset = no ceiling; negative = no ceiling; `0` refuses all such work) and `.retention-days` (35), read through `DocumentSettings.spendLimits()` in the WP-4b.1 block.

### Design decisions

- **`SpendOwner` is a top-level record** in `engine/pricing` rather than nested in `SystemSpendMeter`, so the gate and later callers import it by its simple name.
- **The meter takes the gate through a new `@Inject` constructor**; the two-argument constructor stays for the group-path tests, so `SystemSpendMeterTest` is unchanged.
- **Admission is a pre-flight check, not a reservation.** The plan specifies `admit(owner, estimate)` with no release, so concurrent work admitted at the same moment can together pass a ceiling by what it then spends. The per-document ceiling and the per-user job limits bound what is in flight. Documented.
- **Buckets are charged before the tenant and whatever the tenant answers**, and never again from the carry-over replay, so the daily ceilings see every charge exactly once.
- **A call count is kept per bucket** so that WP-4b.2's per-document-per-day call limit can reuse the ledger's day buckets.
- **No new meter.** The plan's metrics list (§3.2) names none for this package; `docs/metrics.md` only gains the `document` site value.
- **Platform Operator, MCP, UI: no change.** No endpoint, tool or designer key is added, and nothing charges the counters yet, so there is nothing for an admin to act on through the operator. `operator-revision.json` is not bumped.

### Docs

`docs/langchain.md` (new "Daily document spend ceilings"), `docs/model-pricing.md` (document spend, `DOCUMENT` kind), `docs/configuration-reference.md`, `docs/gdpr-compliance.md` (category 18, `documentSpend`), `docs/metrics.md` (unpriced `site`), `application.properties`.

### Tests

`DocumentSpendStoreContract` (accumulation, unpriced calls, 8×25 concurrent increments adding up exactly, retention on write, sweep, ordering, erasure keeping team buckets) runs against MongoDB (`MongoDocumentSpendStoreIT`), PostgreSQL (`PostgresDocumentSpendStoreIT`) and the in-memory double. Plus `DocumentSpendGateTest` (ceilings with both numbers, admission order, fail closed, UTC rollover, retention, owner's tenant), `SystemSpendMeterOwnerTest` (one call does everything, carried charge counted once in the buckets, carried charges kept per tenant, never throws), `DocumentSpendGdprParticipantTest`, `DocumentSpendStoreFailureTest` (database down, Mongo duplicate-key retry) and a WP-4b.1 block in `DocumentSettingsDefaultsTest`. The contract writes days around 2100-01-01, so MongoDB's TTL monitor never deletes a test's rows mid-run. 24 mutations were run (skipped ceilings and tenant check, fail-open paths, local day instead of UTC, a single tenant for carried charges, replace-instead-of-increment on both databases, erasure of the wrong kind, the review fixes) and all were caught.

### Follow-ups

- **For WP-4.3a / 4b.2.** A job that keeps running after its user is erased could write a new bucket for the erased id, so the job participant must stop those jobs first (stated in `gdpr-compliance.md`).
- **The id 4b.2 must use.** `SpendOwner.userId` has to be the raw user id of the conversation, because that is the id erasure uses.

### What's next

WP-4b.2 (`SUMMARY` stage and `summarizeDocument`) admits through `DocumentSpendGate.admit` and charges through `SystemSpendMeter.charge(SpendOwner, …)`.
