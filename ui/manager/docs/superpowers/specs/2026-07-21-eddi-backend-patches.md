# EDDI Backend Patches — spec for a follow-up PR

Companion to the EDDI-Manager `feat/eddi-feature-coverage` branch. These are the changes that require **backend** work (the Java/Quarkus `labsai/EDDI` repo) to reach true 100% coverage. The Manager already ships an honest/robust version of each so it is correct against the current backend; these patches let it become fully-featured. Build/verify in the EDDI repo's own CI (`./mvnw -q -pl . test`), on a branch `feat/manager-coverage-backend`.

## Already resolved as Manager-side fixes (no backend change needed)
- **Undeploy "previous versions" param** — the Manager was sending `undeployAllPreviousVersions`; the real endpoint (`IRestAgentAdministration.undeployAgent`) expects `undeployThisAndAllPreviousAgentVersions`. Fixed in the Manager.
- **Per-secret value "rotate"** — there is no `POST /secretstore/secrets/{tenant}/{key}/rotate` endpoint; a value rotation is just `storeSecret` (upsert). The Manager should call `storeSecret` for a value change (tracked as Manager polish); no backend endpoint required.

## 1. Orphans selective delete  (enables the Manager's per-item selection)
**Why:** `DELETE /administration/orphans` (`IRestOrphanAdmin.purgeOrphans`, `configs/admin/IRestOrphanAdmin.java:47-56`) takes only `includeDeleted` and always purges *every* orphan. The Manager can only offer "purge all" honestly.
**Change:** add an optional selection to the purge:
```java
// IRestOrphanAdmin.purgeOrphans — add a query param (repeatable) of resource URIs to restrict the purge:
OrphanReport purgeOrphans(
    @QueryParam("includeDeleted") @DefaultValue("true") Boolean includeDeleted,
    @QueryParam("resource") List<String> resources);   // NEW: empty/absent = purge all (back-compat)
```
In `RestOrphanAdmin.purgeOrphans` impl: after scanning, if `resources` is non-empty, filter the to-delete set to `orphan.getResourceUri() ∈ resources` before deleting; return the filtered report. Keep the empty-list default = purge-all for backward compatibility.
**Manager wiring after merge:** `orphans.ts purgeOrphans` sends `&resource=<uri>` per checked item; the "Delete N selected" CTA and its confirmation become truthful.

> **Correction — the parameterless `DELETE` is the conservative call.** Until the
> `resource` param exists, the Manager must keep sending the bare
> `DELETE /administration/orphans?includeDeleted=…`. Sending an unrecognized
> `resource` query param to the current backend would be silently ignored by
> JAX-RS and purge **everything** while the UI implied a subset — strictly worse
> than today's behavior, which at least states the truth in its confirmation
> ("your selection is not applied… Purge All"). So: no speculative wiring. Ship
> the backend param first, then wire the Manager.

## 2. `maxAgentsPerTenant` enforcement  (makes the quota real)
**Why:** `TenantQuota.maxAgentsPerTenant` is stored but enforced nowhere; the Manager currently must label it "not enforced".
**Change:** in the deploy path (`IRestAgentAdministration.deployAgent` impl / the deployment service), before accepting a new agent deployment, count the tenant's currently-deployed distinct agents and reject with **429** (or 403) + a `Retry-After`/reason body when it would exceed `quota.maxAgentsPerTenant` (skip when the quota is `-1`/unlimited or the quota store is disabled). Add an integration test in `EDDI-integration-tests`.
**Manager wiring after merge:** drop the "not enforced" badge on the quotas field.

## 3. Monthly-cost metering  (makes the cost gauge real)
**Why:** `TenantUsage.monthlyCostUsd` stays 0 because `recordCost` is never called during execution; the Manager labels the cost gauge "not yet active".
**Change:** in the LLM execution path (`modules/llm` — the streaming/cascade executors, where token usage & `CascadeResult.runCostUsd` are already computed), call the tenancy service's `recordCost(tenantId, costUsd)` once per completed turn (guard against double-counting on retries/cascade escalations — record the final accepted step's cost). Emit `costMonth` on `TenantUsage`.
**Manager wiring after merge:** drop the "metering not active" caption; add `costMonth` to the `TenantUsage` type and show the month on the cost card.

## 4. Schedule timestamp serialization — ✅ LANDED on the backend
**What shipped:** `@JsonFormat(shape = JsonFormat.Shape.STRING)` on the `Instant` fields of `ScheduleFireLog` and `ScheduleConfiguration` (`nextFire`, `lastFired`), so they now emit **ISO-8601 strings** instead of the fractional epoch seconds produced by `quarkus.jackson.write-dates-as-timestamps=true`.

**Correction — this spec was wrong.** It previously called the change "optional cleanliness only… no Manager change is required either way." That was false, and the Schedules dashboard broke on it: the "soonest schedule" comparator did raw arithmetic, `(a.nextFire ?? 0) - (b.nextFire ?? 0)`, which yields `NaN` on ISO strings. A `NaN` comparator is inconsistent, so `sort` left the array untouched and the "Next Fire" card surfaced whichever schedule happened to be first rather than the soonest.

`parseInstant` did handle ISO — but only the code paths that actually called it were safe. The typings were the real hole: `nextFire`/`lastFired` were declared `number`, so TypeScript permitted the arithmetic.

**Fixed in the Manager:** `nextFire`/`lastFired` widened to `string | number` (matching `ScheduleFireLog.fireTime`), and the comparator now sorts on `parseInstant(...).getTime()` with an explicit compare (no subtraction, so no `NaN` when values are unparseable). Regression tests cover both encodings.

**Lesson for the remaining items:** a serialization change is never Manager-neutral while a field is typed as a raw `number` — widen the type first, and let `tsc` find the arithmetic.

## Not required
Client metadata (#597), A2A protocol plumbing, and the MCP-server security posture are backend/runtime concerns with no operator-facing REST surface, so they need no Manager UI and no patch here.
