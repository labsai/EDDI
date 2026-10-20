# Throughput Optimization — Implementation Plan

> **Status:** ready to implement. Written 2026-10-05, re-grounded on `origin/main` after an adversarial review, **re-checked 2026-10-14 against `origin/main` @ `d9da29477`** (Quarkus 3.40.1). Self-contained: an agent should be able to execute it without the conversation that produced it.
> **Evidence quality:** from code review, not measurement. **WS0 (measurement) comes first**, and every later workstream must report before/after numbers. Anchors are `origin/main` @ `d9da29477` line numbers — re-locate by symbol (`git fetch` first; this area changes often).
> **Landed since the first draft (don't rebuild):** queued turns reload current memory (`reloadIfSuperseded`); an end-to-end, opt-in **turn deadline** (`configs/shared/TurnDeadline`, #996); **idempotent turns** via `Idempotency-Key` + structured turn errors (#1000); executor saturation gauges `eddi.runtime.executor.{queued,active,max_threads}{pool=managed|nested}`, Mongo connection-pool metrics, audit queue gauges `eddi_audit_queue_{depth,capacity}`, shipped alert rules (#951); per-model LLM circuit breaker (#1001); memory-safe idle sweep / bulk end of inactive conversations (#990).
> **Progress:** WS0 harness landed in [`scripts/loadtest/`](../scripts/loadtest/README.md) — one Python driver (`loadtest.py`) instead of `setup.sh`/`collect.sh`; baseline in [`results/baseline-2026-10-15.md`](../scripts/loadtest/results/baseline-2026-10-15.md) (ceiling confirmed: ~61 turns/s at 500 conversations, managed pool pinned at 199, no 503s; ~23 Mongo ops/turn).
> **Related plans:** `http-client-consolidation-plan.md` (not on `main` yet) (shared LLM connection pools — WS8.6), `pqc-readiness-plan.md` (not on `main` yet) (PQ-5a includes the signing negative cache = WS8.1 — implement once), [`memory-architecture-plan.md`](memory-architecture-plan.md) (WS6 long term).
> Paths are relative to `src/main/java/ai/labs/eddi/` unless they start with `src/`, `docs/`, `scripts/`, `helm/`, `k8s/`.

---

## 0. Working agreement (read before touching code)

1. Read `AGENTS.md` (repo root) fully and follow it:
   - Branch from `origin/main`; **never** a `claude/*` branch name; never commit to `main`; stage files individually.
   - `./mvnw compile` (Windows `.\mvnw.cmd`) before every commit — Checkstyle + `formatter:validate` (fix with `./mvnw formatter:format`).
   - Changelog fragment per PR: `docs/changelog.d/YYYY-MM-DD-<branch-slug>.md` (AGENTS.md §2 rule 8; `ChangelogFragmentTest` enforces the format). Never edit `docs/changelog.md`.
   - **No AI attribution** in commits/PRs. **Ask the human before every `git push`.** Never force-push.
2. **The conversation lifecycle is a hot spot with a long history of subtle concurrency fixes.** Comments tagged `C3`, `C10`, `C11`, `C13` … in `engine/runtime/BaseRuntime.java`, `engine/internal/ConversationStepRunner.java`, `engine/internal/ConversationService.java` and both coordinators document invariants (one-shot callbacks, abandonment tokens, no retry after start, dead-lettering, `ProcessingTurn` one-shot release). Read them before changing anything; keep their tests green.
3. Guard tests to include in targeted runs (all exist): `ImportStyleTest`, `BuildQualityGatesTest`, `StrictBoundaryShippedConfigsTest`, `RuleSetStoreShippedRulesetsTest`, `DocumentationLinksTest`, `DocumentedRestPathsTest`, `ConfigurationReferenceCoverageTest` (every new `eddi.*` property in both `application.properties` and `docs/configuration-reference.md`), `MetricsDashboardCoverageTest` (new meters → Grafana dashboard).
4. Testing traps: `-Dtest=A,B` silently drops missing classes; `Class#method` on `@Nested` runs 0 tests — count surefire reports. Piping `mvnw` hides failures. Type-signature refactors need `./mvnw clean compile`. Baseline the unit suite on `main` first. `WorkflowTraversal` has a **static** cache: clear it in tests via the package-private static `clearCache()` (`modules/llm/impl/WorkflowTraversal.java:76`). Use 24-char hex ids (Mockito `anyString()` doesn't match `null`; short/non-hex ids make `extractResourceId` return null → vacuous `verify(never())`). **Mutation-check** every behavioural/perf-guard test. ITs need Docker (`./mvnw verify -DskipITs=false`); `ContainerBaseIT` patterns exist for multi-container tests.
5. **Mocks of `IRuntime`:** ~25 test files stub `IRuntime.submitCallable` (7 coordinator tests). A *new* `IRuntime` method would return `null` from those mocks and turns would silently never run — prefer routing changes *inside* `BaseRuntime`.

---

## 1. How a turn executes today (`origin/main` @ `d9da29477`)

1. `RestAgentEngine.sayInternal` (`engine/internal/RestAgentEngine.java:260`, `AsyncResponse` timeout `agentTimeout` set at :263; optional `X-EDDI-Turn-Deadline-Ms` header read via `TurnDeadlineHeaderReader` ~:267) runs on a **Quarkus worker thread**. Errors: structured `TurnError`s (#1000); `RejectedExecutionException` → **503 `capacity_exceeded` + `Retry-After`** (~:394; clients fail over on 503, incl. during shutdown drain).
2. `ConversationService.say(conversationId, …)` (`engine/internal/ConversationService.java:1295`; streaming twin `sayStreaming` :1410) calls `requireSnapshot` (:1301 / :1416, definition :1267, **full load**), runs idempotency admission (`turnIdempotency.admitOrServe` ~:1318 — a duplicate `Idempotency-Key` is answered from the recorded result without running a turn), then `say(env, …)` (:755) loads **again** (`loadConversationMemory`, :903). It then resolves the agent (incl. compatible-version adoption `adoptResolvedAgentVersion` :982/:1972, #905), applies the turn deadline (`applyTurnDeadline` ~:712/:983), GDPR/quota/state checks, registers a `ProcessingTurn` (class :362; gauge `eddi_processing_conversation_count` :336; one-shot release on every exit path — C11) and calls `conversationCoordinator.submitInOrder` (:1045).
3. Coordinator (`engine/runtime/internal/InMemoryConversationCoordinator.java`, or `NatsConversationCoordinator.java` in the `nats` profile): per-conversation `LinkedTransferQueue`, gauges `eddi.coordinator.queue_depth` / `eddi.coordinator.active_conversations`, counters `eddi.coordinator.dead_lettered`, admission cap `eddi.coordinator.max-active-conversations=10000` (`application.properties:1148`) → `RejectedExecutionException`.
4. `BaseRuntime.submitCallable` (`engine/runtime/BaseRuntime.java`, selection ~:240-243): nested submissions (ThreadLocal `EXECUTING_SUBMITTED_CALLABLE`) → virtual-thread executor (`nestedExecutorService`, :110); otherwise the bounded **`ManagedExecutor`** (no `quarkus.thread-pool.*` override → `max(200, 8×cores)`). Load is visible via `eddi.runtime.executor.queued|active{pool}` and `eddi.runtime.executor.max_threads{pool=managed}` (:120-145) — **`queued{pool=managed} > 0` for any length of time is the saturation signal.**
5. The outer callable (`ConversationStepRunner`) reads persisted state (:235), reloads memory if superseded (`reloadIfSuperseded` :258/:805), submits the pipeline (:441 → nested VT executor) and **blocks on `future.get(agentTimeout)`** (:671) for the whole pipeline, LLM included.
6. Inside the pipeline callable, `Conversation` renders output in a `finally` → the completion lambda in `ConversationService` (~:998-1009): convert for the response, record turn outcome, cache state, **`conversationDescriptorStore.updateTimeStamp`** (:1004), record version move, metrics, `processingTurn.release()`, `listener.completed(...)`. **The client gets its response before persistence**, which happens afterwards in BaseRuntime's `onComplete` callback in `ConversationStepRunner` (full conversions at :850/:879; append-only `$push` + revision CAS + merge-on-conflict in `ConversationMemoryStore`).
7. Timer `eddi_conversation_processing_duration` measures processing; `eddi.turn.idempotency` counts idempotency outcomes.
8. **Turn deadline (#996):** `TurnDeadline` (`configs/shared/TurnDeadline.java`) is an absolute, arrival-based budget with a reserve (default 1500 ms) for the fallback answer + persist. It exists **only if** the agent sets `turnDeadlineMs` (`AgentConfiguration` ~:587) or the caller sends `X-EDDI-Turn-Deadline-Ms`. Consumers: `RetryConfiguration` (model retries), `ApiCallExecutor` (per-call timeout + retry fit, `eddi.llm.turn.deadline.exceeded{stage}`), `ToolLoopRunner`. The watchdog `future.get(agentTimeout)` and the `AsyncResponse` timeout do **not** use it.

## 2. Workstreams

| WS | Topic | Size | Depends on |
|---|---|---|---|
| WS0 | Load-test harness (+ one missing metric) | M | — |
| WS1 | Cache immutable config resources | S | WS0 |
| WS2 | Remove the platform-thread ceiling; reconcile admission control | M | WS0 |
| WS3 | Per-turn database work | M–L | WS0 |
| WS4 | Overload behaviour (deadlines, per-conversation queue cap) | S–M | WS2 |
| WS5 | Multi-node correctness | L | design decision |
| WS6 | 16 MB document guard (+ long-term design) | S + design | — |
| WS7 | PostgreSQL adapter | S | — |
| WS8 | Smaller items | S each | — |

---

### WS0 — Measurement first (≈2–3 days)

**Goal:** reproducible local load test reporting turns/s, latency percentiles, saturation and Mongo ops per turn.

1. `scripts/loadtest/` (new):
   - `docker-compose.loadtest.yml`: `mongo:7`, the EDDI image (build per `README.md`), `wiremock/wiremock` as a mock OpenAI-compatible LLM, `grafana/k6`. **Copy the auth/boot env vars from the repo's `docker-compose.yml`** (`EDDI_SECURITY_ALLOW_UNAUTHENTICATED`, `EDDI_MCP_ALLOW_UNAUTHENTICATED`, `EDDI_SECRETSTORE_ALLOW_UNAUTHENTICATED`, `QUARKUS_OIDC_TENANT_ENABLED=false`, …) — EDDI's boot guards refuse to start otherwise. Use a non-default host port.
   - WireMock mappings: reuse the OpenAI stubs from `LlmAgentEngineIT` (chat completions JSON). Set latency at run time via `PUT /__admin/settings {"fixedDelay": <ms>}` (WireMock can't read env vars into delays). Skip streaming scenarios (k6 has no built-in SSE; would need xk6-sse) unless you build a custom k6 binary.
   - `setup.sh`: create + deploy an LLM agent with `POST /administration/agents/setup` (`SetupAgentRequest`: `provider`, `baseUrl` = `http://wiremock:8080/v1`, `apiKey` = dummy, `deploy` = true). Don't document v5 paths (`DocumentedRestPathsTest`).
   - `k6/turns.js`: `steady` (N VUs, one conversation each, M turns, think time), `burst` (ramp), `same-conversation` (several VUs on one conversation), `long-conversation` (200 turns, to expose O(n) costs). Report p50/p95/p99, status breakdown (503/429/408/5xx), turns/s.
   - `collect.sh`: before/after snapshots of `/q/metrics` and Mongo `db.serverStatus().opcounters` → Mongo ops per turn; also audit metrics (`eddi_audit_entries_dropped_total` etc.) — at high turn rates the 100k audit queue (`eddi.audit.max-queue-size`) can overflow; measure it.
   - `README.md`.
2. **Metrics:** almost everything exists — reuse `eddi_processing_conversation_count`, `eddi_conversation_processing_duration`, `eddi.coordinator.queue_depth` / `active_conversations`, **`eddi.runtime.executor.queued|active|max_threads{pool}`** (the thread-ceiling signal), Mongo pool gauges (`MongoMetricsConnectionPoolListener`, `PersistenceModule`), `eddi_audit_queue_depth|capacity`, `eddi.turn.idempotency`, `eddi.llm.circuit*`. Shipped Grafana dashboards and alert rules (`k8s/overlays/monitoring/`, `helm/eddi/files/eddi-alerts.yml`) can be loaded into the harness instead of building panels. Add only `eddi.turn.queue_wait` (timer: `submitInOrder` → outer callable start) if the executor gauges aren't enough to explain latency. New meters → Grafana dashboard (`MetricsDashboardCoverageTest`).
3. Note: the OpenAI-compatible `/v1` adapter is off by default (`eddi.openai-compat.enabled=false`) — enable it in the compose file only for a `/v1` scenario.

**Acceptance:** `scripts/loadtest/results/baseline-YYYY-MM-DD.md` with turns/s and p95 at 3 s LLM delay for 50/200/500 concurrent conversations, error breakdown, Mongo ops/turn, peak active worker threads, audit queue behaviour. Expect a ceiling near ~200 concurrent in-flight turns (WS2) — confirm or refute.

---

### WS1 — Cache immutable config resources (≈1–2 days)

**Problem:** `modules/llm/impl/WorkflowTraversal.java` caches traversals for **2 s** (`CACHE_TTL_MILLIS` :59, cap 512 with clear-all on overflow :62). A miss reads `agentStore.read` (:167), `workflowStore.read` per workflow (:213), `resourceClientLibrary.getResource` per matching step (:221) — direct DB reads + Jackson; stores don't cache. Turn-path callers: `LlmTask` (:2395), `HttpCallToolsProvider` (:218), `McpToolsProvider` (:140), `RagContextProvider` (:102) (`LlmTask` and `HttpCallToolsProvider` share the httpcalls key, so ~3 traversals per turn). New since the first draft: `TurnBudgetValidator` (:62, :71) calls it for deploy-time budget warnings — not per turn, but it must see the same (invalidated) data. Human turns are >2 s apart ⇒ reads on practically every LLM turn.

**Verified:** stored `(id, version)` config resources are immutable — the only in-place writers are `ModifiableHistorizedResourceStore` (descriptors only) and startup migrations.

**Changes**
1. Replace the TTL with size-bounded caching of **complete** traversals (keep "only complete traversals are cached"), keyed as today (`agentId|agentVersion|stepTypeUri|configClass`). Use Caffeine with `maximumSize` (e.g. 1,000) instead of clear-all-on-overflow.
2. **Agent version adoption (#905):** a turn may run on an adopted compatible version (`adoptResolvedAgentVersion`). Confirm the key uses the version the turn actually runs (check what `memory.getAgentVersion()` returns after adoption); if not, key by the resolved version.
3. Invalidation: the cache is static in a non-CDI class → add a static `invalidateAgent(agentId)` and call it from deployment lifecycle hooks (deploy/undeploy/redeploy and version moves — see `engine/runtime/internal/AgentDeploymentManagement.java`, `IDeploymentListener`). Startup migrations run before traffic; clear on startup completion to be safe.
4. Cached config objects are shared across conversations — verify no caller mutates them (grep the four callers for setters on the returned configs); if any does, return defensive copies or fix the caller.
5. Keep the `nowMillis` overload (:131) used by tests.

**Tests:** two traversals 10 s apart (via `nowMillis`) → stores read once; invalidation → re-read; incomplete traversal (store throws) not cached; adopted version uses its own entry. Mutation-check (restore the TTL → the test fails).
**Acceptance:** WS0 shows config reads/turn ≈ 0 in steady state.

---

### WS2 — Remove the platform-thread ceiling; reconcile admission control (≈3–4 days)

**Problem:** each in-flight turn holds a `ManagedExecutor` platform thread blocked in `future.get` (`ConversationStepRunner.java:490`). The pool (~200) is shared with blocking REST endpoints and other users; its queue is unbounded, so saturation = latency for everything, not a clean rejection. The only cap today, `eddi.coordinator.max-active-conversations=10000`, is far above the thread ceiling.

Amplifiers: the OpenAI-compatible bridge (`integrations/openai/OpenAiConversationBridge.java`) blocks a **second** worker per request (`future.get` :686 and, for streaming, `finished.get` :942; adapter cap `eddi.openai-compat.max-concurrent-requests=64`, `application.properties:484`); group member turns run on virtual threads (`GroupConversationService.java:314`) but call `conversationService.say` (`MemberTurnExecutor.java:406`) without the nested marker → each member's outer callable takes a `ManagedExecutor` thread.

**Who really uses the `ManagedExecutor` today:** the two coordinators (turns), `RestAgentAdministration` (deploy/undeploy), `RestConversationStore` (retention sweeps), `RestSemanticParser`. `ConversationHitlService`'s pipeline submission and `ApiCallExecutor`'s batch sends run inside a turn and are **already nested** (virtual threads); HITL resume's outer task goes through `submitInOrder` and is covered by this change automatically.

**Changes**
1. **Run coordinator turn tasks on virtual threads, routed inside `BaseRuntime`** (not via a new `IRuntime` method — see §0.5). Options: wrap turn callables in a marker type (e.g. `TurnCallable implements Callable<T>`) that `BaseRuntime` recognises, or have the coordinators pass a flag through an existing overload. Keep **all** current semantics: `threadBindings` / `ThreadContext.setResources`, `EXECUTING_SUBMITTED_CALLABLE` set inside the body (so the pipeline submission stays nested), cleared before callbacks, one-shot `callbackFired`, the `abandoned` token, completion dispatch outside the guarded region. `submitNext` from completion callbacks must also produce turn tasks. Update the `BaseRuntime` Javadoc that explains the half-pool cliff.
2. Check nothing on the outer path relies on `ManagedExecutor` context propagation (request context, `SecurityIdentity`): identity travels via `CallerIdentityContext.withIdentity` (`ConversationStepRunner` ~:92-110); grep for `@RequestScoped`/`SecurityIdentity` reachable from the outer callable.
3. **Admission control:** add a global in-flight limit `eddi.conversation.max-in-flight-turns` (default e.g. 1000; document sizing vs heap and Mongo pool). Acquire at admission in `ConversationService` **after** idempotency admission (`turnIdempotency.admitOrServe` — a duplicate served from the recorded result must not take a permit) and **release from `ProcessingTurn.release()`** (it already fires exactly once on every exit path). Reject with the **existing 503 `capacity_exceeded` + `Retry-After` convention** (throw `RejectedExecutionException` so every surface — REST, SSE, `/v1`, groups, MCP, Slack — keeps its current mapping). Decide with the human whether `max-active-conversations` stays as a separate queue-count cap (recommended: keep both; document the difference). Metric: reuse existing gauges + `eddi.turn.rejected{reason}`.
4. **OpenAI-compatible adapter:** stop blocking a REST worker in both the sync (:686) and streaming (:942) paths — make the endpoints async (`AsyncResponse`/`Uni` completed from the turn's completion) or `@RunOnVirtualThread` (verify no request-scoped dependency on a platform thread).
5. Group discussions: with (1), member outer callables already move to virtual threads. Check `RestGroupConversation` endpoints for synchronous waits holding a worker and convert similarly.
6. Optionally set `quarkus.thread-pool.max-threads` explicitly with a comment.

**Tests**
- Deliberately update `BaseRuntimeConcurrencyTest.submissionFromCompletionCallbackIsNotTreatedAsNested` and `boundedPoolIsSaturatedByWaitingOuterTasks` to the new model (and keep their intent: callbacks still schedule follow-up turns correctly; outer turn tasks no longer saturate the pool).
- With a fake LLM blocked on a latch: more concurrent turns than the `ManagedExecutor` size are all in flight; per-conversation ordering preserved; one-shot callback / abandonment tests green.
- Admission: limit 2 → third turn gets 503 `capacity_exceeded`; permits released on success, failure, abandonment and pre-submission throw (mutation-check each).
**Acceptance:** WS0 at 3 s LLM delay sustains ≥ 1000 in-flight turns on one node; REST reads stay fast during the run; overload yields 503s, not timeouts.

---

### WS3 — Per-turn database work (≈4–6 days; split into PRs)

**3a. One conversation load per turn.** The only metadata-only `requireSnapshot` callers are `say(conversationId, …)` (:1301) and `sayStreaming(conversationId, …)` (:1416); the others (:671, :692, :1234, :1392 and the undo/redo checks ~:1610-1633) legitimately need the full snapshot. Note that the env-less `say` now also runs idempotency admission (`admitOrServe`) between the load and `say(env, …)` — keep that order. Simplest fix: in those two env-less overloads, load the memory once and **pass it down** into `say(env, …)` / `sayStreaming(env, …)` (add an internal overload taking the loaded memory), instead of a projection API. Don't freeze `agentVersion` before #905's adoption logic runs.

**3b. Convert only what the response needs.** `convertSimpleConversationMemorySnapshot(IConversationMemory, …)` (`engine/memory/ConversationMemoryUtilities.java:893-901`) does a full `convertConversationMemory` and then trims to the last step when `returnCurrentStepOnly=true` (default). Add a current-step-only conversion that produces identical output — **including the `SecretRedactionFilter` pass** applied in the conversion path. Since persistence converts the same memory again (`ConversationStepRunner` :850/:879), consider sharing one full conversion between response and persist when both need it. Golden-file tests for `returnCurrentStepOnly` × `returnDetailed`.

**3c. `updateTimeStamp` (3 reads + 1 replace on the response path).** `ConversationDescriptorStore.updateTimeStamp` wraps the generic `datastore/DescriptorStore` (9 indexed fields, via `IResourceStorageFactory`; `datastore/mongo/DescriptorStore` appears unused — verify): `getCurrentResourceId` + `readDescriptor` + `ModifiableHistorizedResourceStore.set` (reads again) + full store.
- Add a single-update method to the resource-storage SPI (`IResourceStorage`, e.g. `touchLastModified(id, version, Date)`) implemented in **both** Mongo and Postgres storages (one `updateOne $set` / one `UPDATE`), and use it.
- **`lastModifiedOn` is correctness-relevant:** retention deletes conversations by it (`engine/memory/rest/RestConversationStore.java` ~:942, ~:989, ~:1052, ~:1165), and it is called inside the completion lambda before `listener.completed` (`ConversationService.java:1004`, plus the streaming twin). Moving it off the response path is fine only if it stays reliable: run it after `onComplete` on the same turn thread (still inside the turn), retry once on failure, log at ERROR, count `eddi.conversation.touch_failed`. Never fire-and-forget without retry.

**3d. Read preference.** `datastore/bootstrap/PersistenceModule.java:61` uses `ReadPreference.nearest()` for all reads → on a replica set a turn can read a stale secondary (revision conflicts, stale state checks, and `reloadIfSuperseded`'s `getRevision` could compare against a stale revision). Add `eddi.mongodb.read-preference` (default **`primary`**, parsed with `ReadPreference.valueOf`). Changelog: behaviour change for replica-set deployments.

**3e. Pool sizing docs.** Mongo pool = driver default (100) unless set in the connection string; Postgres Agroal default max 20 (WS7). Document sizing vs `eddi.conversation.max-in-flight-turns` in `docs/configuration-reference.md`.

**3f. Pre-checks (measure first).** Ownership (`RestAgentEngine.validateConversationOwnership`, descriptor-based), GDPR restriction read (uncached by design — leave it unless the human agrees), state read (:224), `getRevision` (from `reloadIfSuperseded`). Only fold reads where freshness for security checks is preserved.

**Acceptance:** WS0 Mongo ops/turn reduced (target: 1 full conversation read + ≤ 3 small reads + ≤ 2 writes) and lower p95 in the `long-conversation` scenario.

---

### WS4 — Overload behaviour (≈2 days)

The correctness part of the original WS4 ("queued turns run on stale memory") **already landed** (`reloadIfSuperseded`, 56965553f2). Remaining:

**4a. Close the remaining deadline gaps (build on `TurnDeadline`, #996 — don't add a second mechanism).** Today the deadline is opt-in and only bounds retries/HTTP calls/tool loops (§1 item 8). Gaps:
- **No default for client-facing turns.** Without `turnDeadlineMs` or the header, a REST/SSE/`/v1` turn has no deadline, while the client's `AsyncResponse` gives up after `agentTimeout`. Add `eddi.conversation.default-client-turn-deadline=agent-timeout` (values: `agent-timeout` | `none` | a duration) that creates a `TurnDeadline` from arrival time for client-facing entry points only (REST `sayInternal`, SSE, `/v1`) when neither agent config nor header sets one. **Group-member, scheduled, HITL-resume and A2A-server turns keep today's behaviour** (no default deadline).
- **No skip when the deadline expired while queued.** At outer-callable start (`ConversationStepRunner`, before `reloadIfSuperseded`), if a deadline exists and `isExpired()`, end the turn as a structured `TurnError` (reuse #1000's error model; check which code an expired deadline already produces in the `RetryConfiguration`/fallback paths and match it) without running the pipeline; count `eddi.llm.turn.deadline.exceeded{stage="queued"}` (existing counter, new stage tag). Today such a turn still runs the pipeline and produces a fallback answer nobody receives.
- **Watchdog ignores the deadline.** When a deadline exists, wait `min(agentTimeout, remainingMs)` in `future.get` (:671) instead of the full `agentTimeout`.
Preserve the no-retry-after-start and dead-letter invariants; document the new property in the LLM resilience guide added by #1002 (find it under `docs/`) and the configuration reference.

**4b. Per-conversation queue cap.** Add `eddi.conversation.max-queued-turns-per-conversation` (default 5) in both coordinators → `RejectedExecutionException` → existing 503 mapping (or a distinct `conversation_busy` code if the human prefers; keep one convention).

**4c. (Optional) memory per queued turn.** Each queued turn still holds the memory loaded at REST time until it runs (then possibly reloads). If WS0 shows memory pressure in `same-conversation`, defer the load into the callable. Not required for correctness any more.

**Tests:** queued turn whose deadline expired never runs the pipeline (mutation-check); a REST turn without agent/header deadline gets the default; scheduled/group/HITL turns get none; watchdog uses the remaining budget; queue cap rejects the 6th queued turn.

---

### WS5 — Multi-node correctness (design decision + ≈1–2 weeks)

> **Status 2026-10-17: done, superseded by cluster mode.** The facts below describe `main` before the NATS cluster work (#956, #957, #958, #970): the build-profile coordinator is gone, and `eddi.messaging.type=nats` gives per-conversation leases with fencing in a NATS KV bucket (a variant of Option A, with the lease in NATS rather than the database), cluster-wide nonces, rate limits, cost totals and audit positions, event-driven cache invalidation, deployment propagation and shared dead letters — see [`docs/clustering.md`](../docs/clustering.md). Idempotency keys are honoured across nodes by a re-check under the lease (`fix/cluster-resilience`). The per-model LLM circuit breaker stays per node, as intended. Read this section as history.

**Facts (verified on `origin/main`)**
- `NatsConversationCoordinator` publishes the conversation id (:360) and then **executes locally** (:381). The stream is `RetentionPolicy.WorkQueue` with file storage (:239) and a 24 h max age; the only subscriber is the dead-letter one (:611). Net: one synchronous JetStream write per turn that sits on disk for 24 h, and **no cross-node ordering**.
- Two nodes can run the same conversation concurrently. The append path deliberately **merges** on revision conflict ("both turns survive", `ConversationMemoryStore` :289+), so a concurrent/zombie turn merges instead of failing.
- Per-node state: audit chain sequences (`AuditLedgerService`; replicas without affinity grade chains BROKEN), cost budgets (`modules/llm/tools/ToolCostTracker.java`), tool rate limiter (`ToolRateLimiter`), nonce replay cache (`configs/agents/crypto/NonceCacheService.java` — replay possible across nodes), group cancel tokens (`GroupConversationService`), the `conversationState` cache (`engine/caching/CacheFactory.java`, 1,000 entries, no TTL), agent deployment propagation by polling (`AgentDeploymentManagement`), retention `@Scheduled` sweeps on every node.
- Also per node since the first draft: **idempotency keys** (`engine/internal/TurnIdempotencyService.java`, Caffeine `inFlight`/`recent` caches ~:148-150 — a retried request routed to another node runs the turn again) and the **per-model LLM circuit breaker** (#1001, `eddi.llm.circuit*` — each node trips independently; acceptable, document it).
- `helm/eddi/values.yaml` (~:38-44), `k8s/base/eddi-deployment.yaml` (~:19-26), `k8s/overlays/production/eddi-hpa.yaml` already say **replicas: 1, scale vertically** — but their comments still claim "replaceOne … SILENTLY LOST" (re-checked 2026-10-14), which is stale now that appends merge.

**Step 1 — Fix the docs (≈½ day, do now).** Update those comments; keep **single replica as the supported position** until Step 2 ships. State in deployment docs that NATS does not provide cross-node ordering and that URI-hash sticky routing is **not** sufficient (the `/v1` adapter takes the conversation from a chat id/header; Slack, MCP tools, group discussions and schedules reach conversations without the id in the path).

**Step 2 — Decide (ask the human):**
- **Option A (recommended): per-conversation DB lease with fencing.** A separate `conversation_leases` collection/table (`conversationId` unique, `owner`, `epoch`, `expiresAt`, TTL index). Claim before the turn (atomic upsert where absent or expired, incrementing `epoch`); on failure reject with the existing 503 convention (or wait-and-retry briefly). **Fencing:** pass the lease `epoch` into the persist — add it to `appendPreconditions` and to `storeIfState` filters (or re-check lease ownership atomically before persisting) so a zombie turn whose lease expired fails instead of merging. Release on completion/failure/abandon. **Do not reuse `IN_PROGRESS`** in the conversation state: the say path deliberately never persists it, and `HitlCrashRecoveryObserver`, the cancel CAS and `storeIfState(preTurnPersistedState)` depend on that.
- **Option B:** make NATS a real work queue (consumers execute; responses routed back to the node holding the HTTP/SSE connection). Much larger.
- Either way: **remove or deprecate the current NATS publish** (it costs a disk write per turn and orders nothing). Keep NATS only where it's used for something else (grep for other usages; `NatsHealthCheck`, `NatsMetrics`).

**Step 3 — Per-node state (prioritised; each its own PR):**
1. Nonce replay cache → DB-backed (unique index + TTL index; duplicate ⇒ replay). Security-relevant — first.
2. Cost budgets → per-conversation DB counters with a conditional `$inc`, or document per-node enforcement.
3. Audit sequences → derived from the DB under the lease.
4. Group cancel tokens → DB flag checked between phases.
5. `conversationState` cache → TTL or removal (and note: above 1,000 conversations it churns today).
6. Retention sweeps → single-node via the cluster-safe claim/lease used by `SchedulePollerService`, or a DB lock document.
7. Tool rate limiter → document as per-node unless the human wants a shared store (re-check `ToolRateLimiter`/`ToolCostTracker` after #946 "cache scoping, call caps").
8. Idempotency keys → a DB-backed record (unique `conversationId+key`, TTL index) so `Idempotency-Key` holds across nodes; until then document it as per-node.

**Tests:** two-instance IT (Testcontainers, `ContainerBaseIT` patterns): turns for one conversation sent to both nodes are serialised; a zombie turn after lease expiry fails to persist; nonce replay rejected across instances.

---

### WS6 — 16 MB document guard (≈1–2 days) + long-term design

No guard exists for conversation documents (only `engine/memory/model/PendingToolCallBatch.java` and ingestion handle 16 MB).
1. **Guard without a persisted counter** (full-document replace paths — pause, rerun, undo — would erase an `$inc` field): measure the raw BSON size when the document is loaded (the codec has the bytes) and add the encoded size of the tail being appended (`encodeWithTailOnly` in `ConversationMemoryStore`). Thresholds: `eddi.conversation.size-warn-bytes` (8 MB → WARN + `eddi.conversation.size.near_limit`), `eddi.conversation.size-max-bytes` (15 MB → the turn fails with a clear user-facing error and the conversation ends with a reason, instead of an opaque Mongo write error). Postgres: same logic on the JSONB text size.
2. **Long-term (design only):** steps as separate documents or offloading large `IData` payloads to the attachment store — write it into `planning/memory-architecture-plan.md`, honouring AGENTS.md §4.7 ("Full data is never deleted by optimization").

---

### WS7 — PostgreSQL adapter (≈1 day)

`datastore/postgres/PostgresConversationMemoryStore.java`: the append rewrites the full JSONB value (TOAST/MVCC churn — real fix is WS6 long-term); `data->>'userId'` queries (:748, :886, :904) have no index (existing indexes only ~:72-73); no `quarkus.datasource.jdbc.max-size` set (Agroal default 20).
1. Idempotent expression index `CREATE INDEX IF NOT EXISTS … ((data->>'userId'))` in the store's schema init.
2. Document/raise the Agroal pool (`application.properties`, commented for the Postgres profile; configuration reference).
3. Implement WS3c's `touchLastModified` for Postgres storage.

---

### WS8 — Smaller items

1. **Signing negative cache** (= PQC plan PQ-5a step 7 — do once): `AgentSigningService.loadPrivateKey` (`configs/agents/AgentSigningService.java` ~:399) caches only successes, so with an active vault every audit entry of a keyless agent re-reads the vault. Cache "absent" for 60 s.
2. **GDPR index:** `getConversationIdsByUserId` (`ConversationMemoryStore.java:917`) — conversation indexes (:120-124) still have none leading with `userId` (only `(state, userId)`) → full scan on export/erasure. Add one. `getEndedConversationIds` loads all ended ids → page it. (Re-checked 2026-10-14: still open; #990's new `loadOpenConversationActivity` also filters by `userId`.)
3. **MCP initialize inside `computeIfAbsent`** (`McpToolProviderManager.java:879`): initialise outside the map bin (per-key future or Caffeine async loading).
4. **Sequential tool calls** (`ToolLoopRunner`): add an agent-level opt-in (e.g. `parallelToolExecution: false` in the LLM task config) to run independent tool calls of one LLM response concurrently on virtual threads, preserving result order, `ToolExecutionService.executeToolWrapped` guarantees (check thread safety of rate limiter/cache/cost tracker per conversation) and HITL tool-approval semantics (`docs/hitl.md`).
5. **`new ObjectMapper()` per `discover_tools` result** (`ToolLoopRunner.java:1235`) → shared mapper.
6. **Per-model HTTP clients** (pool + selector thread per cached model) → HTTP plan Phase 3.
7. **Tenant quota hot document** (only when quotas enabled, `engine/tenancy/MongoTenantQuotaStore.java`) — document only.

## 3. Checked and fine

- PBKDF2 (600k iterations) runs at startup/rotation, not per turn; resolved secrets cached; Ed25519 keys cached.
- Memory writes are append-only `$push` with revision CAS + merge retry (correct single-node design; see WS5 for multi-node).
- Queued turns reload current memory (`reloadIfSuperseded`).
- Half-pool deadlock fixed (inner work on virtual threads); coordinator locking per conversation and short.
- Agents/workflows resolved at deploy (except `WorkflowTraversal`, WS1); chat models cached; Qute templates compiled once; `MemoryItemConverter.convert` cheap.
- Schedule polling cluster-safe (atomic claim + lease).
- REST responses default to current-step-only output; OpenTelemetry off by default.
- Native SSE streaming holds no REST worker after submission (the `/v1` streaming path does — WS2.4).
- User memories load once at init (indexed); writes diffed against the turn-start baseline.
- **Audit ledger** is async (bounded queue + batch flush) — but verify its queue under WS0 load rather than assume.

## 4. Risks

- WS2 changes the core execution model; unbounded virtual threads are safe only with the admission limit; memory per in-flight turn × limit must fit the heap.
- WS3c touches the storage SPI on two backends and a retention-relevant field.
- WS3d shifts reads to the primary on replica sets.
- WS5 adds a lease write per turn; measure.
- Every change touches code with many prior concurrency fixes — read the `C*` comments, keep their tests green, mutation-check new ones.

## 5. Definition of done

- WS0 harness committed with a baseline and a results file per workstream.
- WS1–WS4 merged with measured improvements (thread ceiling removed, Mongo ops/turn reduced, consistent 503s under overload, no wasted LLM calls after client timeouts).
- WS5: docs corrected; decision recorded; nonce replay cache cluster-safe.
- WS6 guard live; long-term storage design written.
- Every new property in `application.properties` + configuration reference; new meters on the dashboard.
