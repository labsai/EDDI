# Manager-coverage backend patches — design

**Date:** 2026-07-21
**Branch:** `claude/eddi-backend-manager-coverage-0598fe` (branched from `origin/main`)
**Source:** the "EDDI Backend Patches" spec accompanying the EDDI-Manager `feat/eddi-feature-coverage` branch

## Summary

The incoming spec proposed four backend changes so the EDDI-Manager could reach full feature coverage. Verifying each claim against the code found the spec directionally right but materially stale, and surfaced several pre-existing defects more serious than anything it asked for — including one that can permanently delete live configuration.

This document records what was verified, what shipped, and what deliberately did not.

## Corrections to the incoming spec

| Spec claim | Reality |
| --- | --- |
| `TenantUsage.monthlyCostUsd` | The type does not exist. It was split into `TenantUsageCounters` (internal) and `UsageSnapshot` (API). `costMonth` already ships — that action item was already done. |
| Item 4 is "optional cleanliness" | It is a live user-visible bug: the Manager renders every `Instant` as a 1970 date. |
| Item 4 = annotate `nextFire` + `lastFired` | `ScheduleConfiguration` has **six** `Instant` fields, `ScheduleFireLog` three. Annotating two of six leaves one JSON object carrying two ISO strings and four fractional-second numbers — worse than the uniform wrongness it replaces. |
| Add an IT in `EDDI-integration-tests` | No such module. `pom.xml` has no `<modules>`; the 68 `*IT.java` live in `src/test/java/ai/labs/eddi/integration/`. |
| Branch `feat/manager-coverage-backend` | Does not exist locally or on any remote. |
| `./mvnw -q -pl . test` | Single-module build, so `-pl .` is redundant; Windows needs `.\mvnw.cmd`; `-q` suppresses the surefire summary. |
| Manager sent `undeployAllPreviousVersions` | Zero occurrences anywhere in this repo, including the vendored Manager bundles. Backend param is `undeployThisAndAllPreviousAgentVersions`. Unverifiable from here — confirm in the Manager repo. |
| No per-secret `rotate` endpoint | Correct, but `POST /{tenantId}/rotate-dek` and `POST /admin/rotate-kek` do exist for other key layers. Do not let the phrasing invite a redundant endpoint. |

## Shipped

### 1. `fix(admin)` — descriptor page walk and purge safety (`cb9b54ce1`)

`readAllDescriptors` advanced its cursor by `batch.size()`, but `DescriptorStore.readDescriptors` treats that argument as a **page index** (`skip = index * limit`). The second iteration requested `skip = 40 000`, always came back empty, and every store type truncated at 200 rows.

The dangerous half was `buildReferencedUrisSet` — the *referenced* set truncated identically, so above 200 agents or 200 workflows, live in-use resources were classified as orphans, and `purgeOrphans` deletes with `permanent=true` (`deleteAllPermanently`: current document **and** all history).

Separately the reference scan failed **open**: read errors were swallowed (two at `debug`) and the partial set returned as complete. Since that set is what *protects* a resource, every swallowed error made more things look orphaned.

- page walk advances by page, bounded by `MAX_PAGES`; raises rather than truncating silently
- `scanReferencedUris()` returns a `ReferenceScan` record carrying a `complete` flag
- `purgeOrphans` refuses with **409** on an incomplete scan; `scanOrphans` still returns its best-effort read-only report
- `ResourceNotFoundException` is explicitly not incompleteness — a descriptor whose resource is gone is a genuine orphan

### 2. `fix(tenancy)` — at-limit comparison and `costMonth` shape (`7641f60f3`)

`checkCostBudget` denies on `>=` and `InMemoryTenantQuotaStore` matches, but both **production** stores used `>`. At exactly the budget the pre-call gate denied while post-call accounting allowed.

`UsageSnapshot.costMonth` serialized as the array `[2026,7]`: under `write-dates-as-timestamps=true`, Jackson's `YearMonthSerializer` takes its `useTimestamp` branch. Both stores already persist it as an ISO string and never route it through Jackson, so only the REST shape disagreed — making the annotation safe in isolation.

### 3. `fix(engine)` — 429 instead of 500 for api-call quota denials (`e3fbf94c9`)

`say()` resumes through an `AsyncResponse`, so `QuotaExceededException` is caught inside `RestAgentEngine.sayInternal` and never reaches `QuotaExceededExceptionMapper`. It fell into `catch (Exception e)` and surfaced as 500. Only the *conversation-start* quota ever produced a real 429, because that path is synchronous.

Both new behavioural tests were **mutation-checked** — reverting the production change makes each fail — so neither is vacuous.

## Deliberately not shipped

### C3 as designed — repo-wide `Instant` → ISO-8601

Approved as "repo-wide via `SerializationCustomizer`", implemented, then **backed out** when the critique surfaced a blocker that invalidated the basis of the decision.

`SerializationCustomizer`'s mapper is **not only the REST mapper**. `JsonSerialization` `@Inject`s the same CDI `ObjectMapper`, and it backs `DocumentBuilder` for every Mongo resource write, every Postgres JSONB column, the backup/export writer, and the `{json:serialize}` Qute template extension. Changing the shared static therefore changes **on-disk formats**.

Concretely: `GroupConversation.lastModified` is an `Instant` persisted through that mapper, and `GroupConversationStore.listByGroupId`/`listByState` sort **server-side** on it. Mongo ranks all Doubles before all Strings in cross-type ordering; Postgres does `ORDER BY data->>'lastModified'` lexicographically. Old numeric rows and new ISO rows interleave wrongly, silently, with no backfill.

Commit `dc117cddc` ("keep numeric date format; harden schedule Instant parsing") already reverted a broader version of this exact change for breaking `findDueSchedules`.

The empirical part still holds and is worth keeping: a `configOverride(Instant.class)` **does** beat the global flag, old fractional-epoch-second JSON **does** still deserialize, and `java.util.Date` stays epoch millis. The blocker is scope, not mechanism.

**To land it properly:**
1. Decouple persistence from REST — give `JsonSerialization` its own mapper from `configureObjectMapper(new ObjectMapper(), false)` *without* the override, and apply the override only inside `customize(ObjectMapper)`. Moving the line into `customize()` alone is **not** sufficient, because `JsonSerialization` injects the very mapper `customize()` mutates.
2. Or annotate the enumerable REST-facing DTOs instead (`ScheduleConfiguration`, `ScheduleFireLog`, `PendingApprovalSummary`, `SimpleConversationMemorySnapshot.hitlPausedAt`, `UserMemoryEntry`, `GroupConversation`, `Attachment.createdAt`), per-class-complete — the `SecretMetadata` idiom.
3. Either way: restore the fractional-seconds precondition in `MongoScheduleStoreInstantRoundTripTest.setUp` (otherwise that CRITICAL-bug guard silently stops reproducing its scenario), and land the Manager comparator fix — the schedules dashboard sorts `nextFire` arithmetically, which yields `NaN` on ISO strings.

### C1(b) — `includeDeleted` semantics

`includeDeleted` is an equality filter (`Filters.eq("deleted", flag)`), so `scanOrphans` (default `false`) and `purgeOrphans` (default `true`) operate on **disjoint** sets. Redefining it as a true "include" is correct and matches the documented meaning — but with the page walk now complete, doing so *without also flipping the `true` default* turns the default `DELETE /administration/orphans` from "purge ≤200 already-soft-deleted rows" into "permanently wipe every unreferenced resource, unbounded".

Land as its own commit, together with the default flip and/or an explicit `confirm=true`, and a changelog entry naming the behaviour change. Note the `includeDeleted=true` path currently has **zero** test coverage anywhere in the repo.

### C2 — selective orphan purge

Blocked on C1(b): until scan and purge see the same set, a selective purge deletes exactly zero of the user's selections. Design that survived critique:

- `@QueryParam("resource") List<String>`, null-guarded, no `@DefaultValue`; absent/empty = purge all
- scan runs first and unconditionally; `resources` only **intersects** its result — never a branch that skips the scan, or it becomes a delete-arbitrary-resource primitive on an endpoint whose `@RolesAllowed` is unenforced when `quarkus.oidc.tenant-enabled=false`
- open question the critique raised and this design has not answered: normalising to `host + "/" + id` drops the version, and `extractResourceId` returns a null id for short/non-hex input, so several distinct inputs can collapse to one key. Decide between `type:id` pairs and full-URI matching before implementing.

### C4 — `maxAgentsPerTenant` enforcement

No blockers, several confirmed majors:

- **`autoDeploy=false` bypasses it entirely** — the `deployed` row is only written when `autoDeploy` is true, yet the agent is live in `AgentFactory`. `AgentDeploymentComponentIT` exercises that path.
- The count must be read on the request thread and **outside** the existing `try`, or `catch (Exception) → InternalServerErrorException` converts the 429 into a 500.
- Read-only gate (`checkAgentQuota(tenantId, count)`) modelled on `checkCostBudget`, not an atomic counter — the 10 s re-deploy sweep, the 24 h old-version undeploy, `TeardownAgentTool` and `GroupConversationService` all mutate deployments without passing any acquire point.
- Keep the 1-arg `QuotaExceededException(String)` defaulting to 60 so `TenancyModelsTest` and `RestTenantQuotaTest` keep passing; add a 2-arg overload for capacity denials.
- `RestAgentAdministrationTest` and `RestAgentAdministrationExtendedTest` construct the bean positionally and must be updated in the same commit.

### C5 — cost metering

**Blocked on a blocker, not just majors.** The design accumulated per-turn USD from token usage, but agent mode and the HITL resume path *discard* `tokenUsage` — so the accumulator would read ~0 on exactly the paths the change targets. `ObservableChatModel` is the correct interception point, not `LlmTask`.

Additional confirmed problems:
- `inputPricePer1M`/`outputPricePer1M` already exist on `CascadeStep` **and** `ModelCascadeConfig`. A third copy on `Task` needs a stated precedence rule or it double-counts cascade turns against `runCostUsd`, which `LlmTask` already surfaces as `cascadeCostUsd`.
- Five other LLM call sites in the same turn would remain unmetered.
- Wiring `recordCost` makes a dormant **Mongo E11000** live: `tryAddCost` upserts against a filter pinning `costMonth` while `tenant_usage` has a unique index on `tenantId`, so the first cost of each new UTC month attempts a duplicate insert. `MongoTenantQuotaStoreTest:369` pins the current two-call sequence and will need rewriting.
- Tool cost is structurally $0 — `ToolCostTracker`'s price map is keyed `"websearch"`/`"weather"` while the names passed in are `searchWeb`/`getCurrentWeather`, so every lookup misses.

Redesign around `ObservableChatModel` before implementing.

## Verification notes

- Local `mvnw test` is red out of the box — roughly 8 failures / 288 errors on a pristine tree, all environmental (loopback sockets, network egress, model downloads) in `WebSearchTool`, `WebScraperTool`, `WeatherTool`, `PdfReaderTool`, `SafeHttpClient`, `SlackWebApiClient`, `A2AToolProviderManager*`, `EmbeddingModelFactory*`, `LanguageModelBuilders`. Baseline before attributing failures to a change.
- `-Dtest=Class#method` runs **0 tests and exits green** when the method sits in a `@Nested` class. Filter by whole class.
- Fixtures need 24-char hex ids — `RestUtilities.extractResourceId` returns a null id otherwise, and assertions pass vacuously.
