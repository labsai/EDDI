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

## 2. `maxAgentsPerTenant` enforcement  (makes the quota real)
**Why:** `TenantQuota.maxAgentsPerTenant` is stored but enforced nowhere; the Manager currently must label it "not enforced".
**Change:** in the deploy path (`IRestAgentAdministration.deployAgent` impl / the deployment service), before accepting a new agent deployment, count the tenant's currently-deployed distinct agents and reject with **429** (or 403) + a `Retry-After`/reason body when it would exceed `quota.maxAgentsPerTenant` (skip when the quota is `-1`/unlimited or the quota store is disabled). Add an integration test in `EDDI-integration-tests`.
**Manager wiring after merge:** drop the "not enforced" badge on the quotas field.

## 3. Monthly-cost metering  (makes the cost gauge real)
**Why:** `TenantUsage.monthlyCostUsd` stays 0 because `recordCost` is never called during execution; the Manager labels the cost gauge "not yet active".
**Change:** in the LLM execution path (`modules/llm` — the streaming/cascade executors, where token usage & `CascadeResult.runCostUsd` are already computed), call the tenancy service's `recordCost(tenantId, costUsd)` once per completed turn (guard against double-counting on retries/cascade escalations — record the final accepted step's cost). Emit `costMonth` on `TenantUsage`.
**Manager wiring after merge:** drop the "metering not active" caption; add `costMonth` to the `TenantUsage` type and show the month on the cost card.

## 4. (Optional) Schedule timestamp serialization — cleanup
**Why:** Quarkus is configured with `quarkus.jackson.write-dates-as-timestamps=true` (`application.properties:174`), so `java.time.Instant` fields serialize as **fractional epoch seconds** (e.g. `1719964800.123`). The Manager now parses this robustly (`schedules.ts parseInstant`), so this is optional cleanliness only.
**Change (nice-to-have):** add `@JsonFormat(shape = JsonFormat.Shape.STRING)` to the `Instant` fields of `ScheduleFireLog` (`engine/schedule/model/ScheduleFireLog.java`) and `ScheduleConfiguration` (`nextFire`, `lastFired`) — matching what `SecretMetadata.java` already does — so they emit ISO-8601 strings. `parseInstant` already handles ISO too, so no Manager change is required either way.

## Not required
Client metadata (#597), A2A protocol plumbing, and the MCP-server security posture are backend/runtime concerns with no operator-facing REST surface, so they need no Manager UI and no patch here.
