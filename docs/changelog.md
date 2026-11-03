# EDDI Ecosystem — Working Changelog

> **Purpose:** Living document tracking all changes, decisions, and reasoning during
> implementation. Updated as work progresses, newest first.

## How to Read This Document

Each entry records:

- **Date** — what changed and why
- **Repo** — which repository and branch was modified
- **Decision** — key design decisions and their reasoning
- **Files** — the files touched

## Where to Add an Entry

**Not here.** Write your entry as a new file in
[`changelog.d/`](changelog.d/README.md) — `YYYY-MM-DD-<slug>.md`, with the slug
unique to your branch — and leave this file alone. The same goes for the two
running registers at the bottom: their rows ride along in the fragment, in a
fenced `decision-log` or `regression-note` block.

Entries used to be inserted at the top of this file, and the registers appended
to at the bottom. Both are a fixed point in a shared file, which git cannot
merge: with several PRs open, every one of them conflicted with every other over
a document that had nothing to do with the code under review. A fragment is a new
file under a name no other branch picks, so the same two PRs merge without
touching each other.

`.github/workflows/changelog-collate.yml` runs nightly, merges the fragments in
here **by date** — a PR that stayed open for weeks lands among its
contemporaries rather than on top — trims this file back under its rotation
target, and opens a PR. Until that PR merges, `changelog.d/` holds the newest
history, so read it alongside the top of this file. To do it by hand:

```bash
python scripts/collate-changelog.py   # fragments -> this file
python scripts/rotate-changelog.py    # this file -> docs/changelog/<YYYY-MM>.md
```

This file holds only recent work and is capped at **250 KB** —
`ChangelogRotationTest` fails the build if it grows past that. Rotation runs at a
lower threshold than the cap, trimming back to **200 KB** whenever the file is
over that, so the session whose entry tips it over is not the one made to rotate
it. Rotation moves the oldest entries into `docs/changelog/<YYYY-MM>.md` by date,
adds one `../` to the relative links it moves (an archive sits a directory deeper
than this file) without touching the ones inside code spans, and regenerates both
the Archive table below and the changelog list in [`SUMMARY.md`](SUMMARY.md) from
what is on disk. Do not raise the cap.

The single file this replaced had reached 1.9 MB — roughly half a million tokens —
which neither a reader nor an agent's context window could usefully hold.

## Archive

| Period | Entries | Size |
|---|---|---|
| [October 2026](changelog/2026-10.md) | 257 | 1175 KB |
| [September 2026](changelog/2026-09.md) | 223 | 1216 KB |
| [August 2026](changelog/2026-08.md) | 213 | 845 KB |
| [July 2026](changelog/2026-07.md) | 147 | 648 KB |
| [June 2026](changelog/2026-06.md) | 26 | 67 KB |
| [May 2026](changelog/2026-05.md) | 34 | 76 KB |
| [April 2026](changelog/2026-04.md) | 104 | 220 KB |
| [March 2026](changelog/2026-03.md) | 59 | 183 KB |

The two running registers — **Decision Log** and **Regression Notes** — live at the
bottom of this file and are never archived.

---

## ✨ feat(llm): `toolQuotas` — per-subject, per-window caps on tool calls (2026-11-02)

**Repo:** EDDI (`feat/tool-quotas`)

### What changed and why

An agent designer can now cap how often one **subject** — normally the end user — may make a tool run within a
time window, counted **across conversations and across nodes**: "at most 100 content improvements per user per
day". Until now EDDI had a per-tool-per-minute rate limit per conversation (`toolRateLimits`), a per-conversation
budget that a new conversation resets (`maxBudgetPerConversation`) and per-tenant quotas — none of them is per
user per day.

```json
"toolQuotas": {
  "subject": "{context.personId}",
  "tools": {
    "updateAction":        { "limit": 100, "window": "P1D", "pool": "improvements" },
    "updateSession":       { "limit": 100, "window": "P1D", "pool": "improvements" },
    "generateActionImage": { "limit": 30,  "window": "P1D" }
  }
}
```

- **Config** — `LlmConfiguration.Task.toolQuotas` ([`ToolQuotaConfig`](../src/main/java/ai/labs/eddi/modules/llm/model/ToolQuotaConfig.java)).
  Keys are tool names with the same matching as `toolRateLimits` (dispatch name wins over slug) and work for every
  tool source, HTTP-call tools included. Tools of one `pool` share one counter. Validated at save time
  ([`ToolQuotaConfigValidator`](../src/main/java/ai/labs/eddi/modules/llm/tools/quota/ToolQuotaConfigValidator.java),
  wired into `LlmStore` like the `toolApprovals` check, so it is a 400): `limit` a positive integer, `window` an
  ISO-8601 duration from `PT1M` to `P31D`, every rule of a pool states the same limit and window. Windows are fixed
  and aligned to the UTC epoch (`P1D` resets at 00:00 UTC).
- **Subject** — an optional template rendered with the turn's template data; default is the conversation's user id.
  Managed conversations use composite user ids (`<user>-<company>-<program>`), so the default counts per program and
  the template is how a deployment counts per person. A blank render falls back to the user id, a caller with no user
  id is capped per conversation, a subject over 256 characters becomes a prefix plus a digest, and the subject is only
  ever logged as a short digest.
- **Counts real executions only.** The reservation is made in one place, `ToolExecutionService.executeToolWrapped`,
  after the rate limit and the cache and immediately before the tool runs (`ToolQuotaGate.reserve`). Cache hits,
  rate-limited calls, calls refused by the conversation or tenant budget, rejected approvals and calls that never ran
  are never counted. A failed call gives its reservation back (`ToolQuotaGate.settle`): an exception, a tool-reported
  error, an HTTP non-2xx or transport failure — judged by the same `ToolOutcomeClassifier` the repeat guard uses. A
  **timed-out** call keeps its count, because the abandoned worker cannot be stopped and may still finish the side
  effect.
- **Atomic, no overshoot, multi-node.** A new counter store, `IToolQuotaStore`, with a MongoDB implementation
  (`findOneAndUpdate` with a `count < limit` filter and upsert; the duplicate-key race on the window's first insert is
  retried as a conditional update; TTL index on `expiresAt`) and a PostgreSQL one (`INSERT … ON CONFLICT DO UPDATE …
  WHERE used < ? RETURNING used`; an hourly sweep removes expired rows). Selected in `DataStoreProducers` like the other
  stores. With three calls left, a batch of five parallel calls runs exactly three.
- **Refusal** is a tool result the model sees, a final `NOT_EXECUTED` envelope with the limit and the reset time
  ("Not run: the daily limit for this tool (100) is reached; it resets at 2026-11-03T00:00:00Z …"), not a thrown error;
  the turn carries on and the model is not invited to retry.
- **Human approval.** Approved calls run on the resume path through the same `ToolLoopRunner.executeToolCall` →
  `executeToolWrapped` pipeline as live calls, so the quota is applied (and counted) at execution time there too. A
  gated call proposed while its quota is already exhausted is refused immediately by a read-only pre-check
  (`ToolLoopRunner.refuseExhaustedGatedCalls`, before the pause is built), so a human is never asked to approve what
  will then be refused; pausing counts nothing.
- **Fails closed.** If the store cannot answer, a stored rule cannot be applied or the subject template fails to
  render, the call is not run and the model is told the limit could not be checked. One WARN a minute with agent,
  conversation and a digest of the user id; every failure is counted.
- **Metrics** — `eddi.tool.quota.decisions{tool, outcome}` (`allowed`, `refused`, `precheck_refused`, `unavailable`,
  `released`) and `eddi.tool.quota.store.failures{operation}`; documented in `docs/metrics.md` and charted on the Full
  Metrics Reference dashboard.
- **GDPR** — counters are keyed by a user identifier, so
  [`ToolQuotaGdprParticipant`](../src/main/java/ai/labs/eddi/modules/llm/tools/quota/ToolQuotaGdprParticipant.java)
  joins the erasure cascade (delete by subject) and the export (the user's counters). If a deployment's subject
  template derives an identifier other than the conversation's user id, erasure deletes the counters whose subject
  equals the erased user id; counters hold counts only and expire an hour after their window ends.

### Design decisions

- The reservation sits inside `executeToolWrapped`, not in `ToolLoopRunner` around the supplier: only there is it after
  the cache check, and a refusal returned from inside the supplier would have been cached and charged as a result.
- A rule keyed by a slug is one counter for all of that tool's operations (unlike the per-dispatch-name rate-limit
  bucket), because "30 web searches a day" is naturally per tool; `pool` is the explicit way to share across tools.
- Pre-check only refuses an *already exhausted* quota; it does not reserve slots for pending approvals.
- A call with no quota rule takes exactly the old 10-argument `executeToolWrapped` path.

### What's next / not done

- The Manager has no form for `toolQuotas` yet; its LLM task editor spreads the task object on save, so a configured `toolQuotas` is preserved.

**Files:** [`ToolExecutionService.java`](../src/main/java/ai/labs/eddi/modules/llm/tools/ToolExecutionService.java),
[`ToolLoopRunner.java`](../src/main/java/ai/labs/eddi/modules/llm/impl/ToolLoopRunner.java),
[`ToolQuotaService.java`](../src/main/java/ai/labs/eddi/modules/llm/tools/quota/ToolQuotaService.java),
[`MongoToolQuotaStore.java`](../src/main/java/ai/labs/eddi/modules/llm/tools/quota/MongoToolQuotaStore.java),
[`PostgresToolQuotaStore.java`](../src/main/java/ai/labs/eddi/modules/llm/tools/quota/PostgresToolQuotaStore.java),
[`docs/langchain.md`](langchain.md)

---

## 🐛 fix(test): `toolQuotas` tests follow `executeToolCall` and `truncateIfNeeded` (2026-11-02)

**Repo:** EDDI (`fix/review-infra`, folded into #1185 so it lands with a reviewed PR)

### What changed and why

#1210 (`toolQuotas`) and #1192 (spend recorded on every model-phase exit) each passed CI on their own, but
together `main` no longer compiled its tests: #1192 added a `DoubleSupplier runSpend` parameter to
`ToolLoopRunner.executeToolCall` and a seventh parameter to `ToolResponseTruncator.truncateIfNeeded`, and
`ToolLoopRunnerQuotaTest` still called the old shapes. The two direct calls now pass
`ToolLoopRunner.NO_RUN_SPEND`, and the truncator stub matches the seven-argument overload the runner uses (the
old stub was never hit, so the mock returned `null` and five tests failed with an NPE on `governed()`).
`ToolQuotaHitlTest` had the same two stale stubs: `checkCostBudget(any())` (now `checkCostBudget(any(), anyDouble())`, the
two-argument form the runner calls) and the six-argument `truncateIfNeeded`; its three resume tests failed with an NPE
in CI. Test-only change.

---

## Group HITL transitions are bound to their pause with a multi-field CAS (2026-11-01)

Repo: EDDI, branch `fix/groups-pause-cas` (stacked on #1188).

**Problem:** the group approve, reject, cancel and human-turn timeout-skip writes compared only `state` in their compare-and-set. A resume followed by a re-pause between the read and the write (ABA) left the state `AWAITING_APPROVAL` / `AWAITING_HUMAN_INPUT` again, so a stale decision could act on a newer pause it never saw. The in-memory pause-id checks narrowed the window but could not close it.

**Change:**
- `IResourceStorage.storeIfFieldsEqual(resource, Map)` — an atomic conjunctive conditional write (a `null` expected value means "absent"), implemented in `MongoResourceStorage` (one `replaceOne` filter) and `PostgresResourceStorage` (one `UPDATE ... WHERE a = ? AND b = ?`), with the same 404/409 zero-match distinction as the single-field CAS.
- `GroupConversation.pauseId` is persisted next to `pausedAt` and kept in lockstep by `setPausedAt`, so no pause or resume site can forget it. `IGroupConversationStore.updateIfStateAndPause` writes with state AND pauseId; `GroupHitlCoordinator` uses it for approve, reject, `cancelStored` (so `cancelDiscussion` and `cancelPausedDiscussion`) and the human-turn timeout skip and submit, each bound to the pause it read.

**Design decisions:** a separate string field rather than comparing `pausedAt`, because an `Instant` is stored differently by the two backends while a string compares identically on both. Documents written before the field existed are matched as absent (still state-bound, still atomic) so pauses in flight at upgrade remain decidable; the next write stamps the id. The pause→cancel conversion inside a running leg is unchanged: it acts on the pause that same leg just committed.

**Tests:** coordinator tests per transition (the CAS names the pause that was read and a stale refusal surfaces), store unit tests, and Testcontainers tests on MongoDB and PostgreSQL showing that a decision read before resume + re-pause is refused while the newer pause is untouched.

---

## fix(hitl,alerts): self-heal the journal TTL index and release digests held by stale rules (2026-11-01)

**Repo:** EDDI (`fix/hitl-alerts-followups`, stacked on `fix/review-engine-hitl-alerts`)

Closes the two items deferred in the HITL/alerts review fixes (`2026-10-31-review-engine-hitl-alerts`).

### What changed and why

- **Journal TTL index self-heals.** During a rolling update an old pod could recreate the unfiltered `idx_journal_ttl_claimed` after a new pod dropped it, silently expiring `EXECUTING` claims again. `IHitlToolJournalStore.reconcileRetention()` (MongoDB: lists the indexes, drops any unfiltered TTL on `claimedAt` or a legacy name with a `WARN`, re-ensures the partial `idx_journal_ttl_completed`) is called by the new `HitlToolJournalMaintenance` scheduled job every `eddi.hitl.tool.journal-reconcile-interval` (`15m`); the constructor still drops the known names at startup. PostgreSQL has no such index (default no-op).
- **Stale-cache digest events are released.** `IAlertEventStore.findDigestPendingRuleIds` (Mongo `distinct`, Postgres `SELECT DISTINCT`) lets `AlertMaintenanceService` release `DIGEST_PENDING` events whose rule is now deleted, disabled or has no digest, so an event created by a replica that still cached the old rule stays in the inbox instead of being held until retention. `AlertMaintenanceService.Result` gained `released`.

### Design decisions

- The reconciliation is a periodic pass over the existing alert maintenance job and a small `@Scheduled` bean for the journal, not a new scheduler or a migration marker: both repairs are idempotent, so every replica may run them.
- The journal job lives in its own bean because the Mongo store is created lazily only on MongoDB; a `@Scheduled` method on the store would instantiate it on PostgreSQL.

---

## chore(build): Node 26 LTS, cache/paths-filter actions, hold blocked majors (2026-11-01)

**Repo:** EDDI (`chore/node-26-lts`)

### What changed and why

- **Node 24.22.0 to 26.13.0** (Active LTS "Lithium" since 2026-10-28). Moved together: `pom.xml` `node.version` and its comment, `ci.yml` `NODE_VERSION` and `NODE_SHA256_LINUX_X64` (taken from the official `SHASUMS256.txt` for `node-v26.13.0-linux-x64.tar.gz`), `docs-site.yml` `NODE_VERSION`, `docs-site/.nvmrc`, `mise.toml`, and the current-state mentions in `AGENTS.md`, `README.md`, `docs-site/README.md`, `ui/chat/README.md` and `ui/manager/README.md`. `@types/node` is untouched; separate Dependabot PRs own it.
- **GitHub Actions**: `actions/cache/restore` and `actions/cache/save` v4 to v6.1.0 in `docker-pull-notify.yml`; `dorny/paths-filter` v3 to v4.0.3 in `ci.yml` and `docs-site.yml`. SHA pins kept, comments now carry the exact version. Supersedes Dependabot PRs #823, #824 and #744.
- **`dependabot.yml` ignore rules** (semver-major only, so minor/patch and security updates still arrive): `de.undercouch:bson4jackson` and `com.networknt:json-schema-validator` (3.x pull Jackson 3 / `tools.jackson`, banned by the `ban-jackson3` enforcer rule while Quarkus manages Jackson 2); `io.github.classgraph:classgraph` (a dependencyManagement pin for a transitive dependency, and ClassGraph 5/6 is not binary-compatible with 4.x); `typescript` in `ui/manager` and `ui/chat` (TS 7 is the Go-native compiler and typescript-eslint peers `typescript <6.1.0`, so `npm ci` fails with ERESOLVE).

### Design decisions

- The held majors are closed rather than left open: each would fail the build or `npm ci`, and an ignore rule stops Dependabot re-proposing them every week. Each rule carries its reason and the condition for lifting it.

### Verification

`BuildQualityGatesTest` keeps `pom.xml`, `ci.yml` and `mise.toml` Node pins equal. UI builds on Node 26 are verified by CI.

---

## fix(runtime): persist late agent-test charges and fence dead letters behind GDPR erasure (2026-11-01)

**Repo:** EDDI (`fix/runtime-late-charges-erasure`, stacked on `fix/review-engine-runtime`)

The two items the engine-runtime review PR deferred. Each has unit tests that fail without the fix.

### What changed and why

- **Late charges of an abandoned agent-test turn** (`AgentTestRunner`): the terminal record was built from the run's cost at the moment `run()` returned, so a turn that completed afterwards updated only the in-memory total and the reported spend stayed too low. A `LateChargeLedger` now tracks what the terminal record accounted for. Once that record is stored, every further difference (cost, newly seen models) is handed out exactly once and applied to the stored run as an adjustment, and an `AGENT_TEST_LATE_CHARGE` entry carrying just the added cost goes to the audit ledger. Charges that land between building the record and storing it are collected right after the write. A failed adjustment is handed out again with the next charge.
- **Dead letters vs GDPR erasure across nodes** (`ClusterConversationCoordinator`, new `ErasureTombstones`): a node that reconnects between the erasure's shared purge and the deletion of the conversation's snapshot still found the conversation existing and republished its erased dead letter. `purgeDeadLetters(conversationId)` now writes a marker to a shared `ERASED` bucket first; forwarding reads it before publishing (erased: discard), and again after the publish (erased meanwhile: purge the published copy). An unreadable marker defers the entry, as an unreadable conversation store already did.

### Design decisions

- The late-charge adjustment is a compare-and-swap loop on the terminal record's `heartbeatAt`, which no longer means anything once the run is terminal, so it doubles as the version counter. No new store method was needed. The alternative, holding finalisation for abandoned turns, would delay every timed-out run by up to the turn timeout plus the write-back wait.
- The adjustment does not recompute status, cases or `costCeilingReached`: it corrects the figure, not the verdict.
- The marker lives in the coordinator's purge, not in `GdprComplianceService`, so every caller of the purge gets it; if it cannot be written, the shared stream is purged anyway and the failure is reported to the cascade (step `deadLetters`).
- The marker's lifetime is configurable (`eddi.cluster.erasure-marker-ttl`, default `P7D`). It holds only the conversation id as key. Past it, the existing existence check takes over.

### Decision Log

---

## Scheduler keeps claiming due schedules while long fires run (2026-11-01)

**Repo:** EDDI, branch `fix/schedule-claim-cycle` (stacked on #1197)

- `SchedulePollerService.pollDueSchedules()` waited for its whole batch of fires, so with `concurrentExecution = SKIP` one fire running for minutes suppressed every 15-second poll on that instance; schedules that fell due meanwhile (HITL approval deadlines included) waited for the long fire. The poll is now a short claim cycle: it claims due schedules and hands each to a bounded fire executor, then returns.
- New `eddi.schedule.max-concurrent-fires` (default 100) caps fires in flight per instance. A poll takes a slot before claiming, so nothing is claimed that cannot start; the rest stay due.
- Each fire is bounded by its own watchdog (`fire-timeout` plus grace) instead of a shared batch deadline; a timed-out fire is interrupted, counted in `eddi.schedule.fire.timedout`, and gives its slot back even if it ignores the interrupt. The lease invariant (lease > fire-timeout + grace), dead-lettering, `failCount`, expired-claim-as-failed-attempt and the manual-fire path are unchanged.
- Graceful shutdown (`@PreDestroy`): stop claiming, let in-flight fires finish for 5 s, interrupt the rest.
- Tests: a long fire does not block the next poll from claiming another schedule; the in-flight cap holds and slots are returned (also after a lost claim); shutdown drains and cancels; the per-fire timeout frees its slot.
- Docs: `docs/scheduling.md`, `docs/configuration-reference.md`.

---

## 🔧 ci: warm one complete Maven cache and retry Central rate limits (2026-10-31)

**Repo:** EDDI (`chore/ci-maven-cache`)

### What changed and why

A pull request's Build & Test job failed with an HTTP 429 from Maven Central while resolving
`surefire-junit-platform`, before a single test ran. Every Java job used `setup-java` with `cache: maven`, which keys
one shared cache on the `pom.xml` hash: whichever job finished first saved it and nothing ever updated it. A
compile-only job (CodeQL) could therefore save a repository without the surefire/failsafe providers or the other
test-time plugins, and every later job fetched those from Central, concurrently with every other open PR.

- **New `Maven Cache` job** (`.github/workflows/ci.yml`) is the only writer of `~/.m2/repository`. It resolves the
  dependencies and plugins, runs `package` once with tests skipped (formatter, Checkstyle, JaCoCo, Quarkus and its
  `*-deployment` jars), forces the surefire and failsafe JUnit providers, and fetches the SBOM and license plugins,
  then saves under `maven-v1-<os>-<hash of every pom.xml and maven-wrapper.properties>`. When that exact key already
  exists it does a lookup-only check and stops, so a docs-only PR pays seconds. A miss starts from the nearest older
  cache. It is `continue-on-error`, so a failed warm-up never blocks the required checks.
- **Build & Test, Integration Tests, CodeQL Analysis, Generate SBOM and Build Image** `need` it and only
  `actions/cache/restore` the same key (with the prefix as fallback); none saves. `cache: maven` is gone from them. Job
  names are unchanged.
- **`.mvn/maven.config`** sets Maven Resolver's HTTP retry: `aether.transport.http.retryHandler.count=8`,
  `.interval=10000`, `.intervalMax=300000`, `.serviceUnavailable=429,503`. A 429 or 503 is retried, the server's
  `Retry-After` is obeyed when sent, and a wait above five minutes fails the request instead of stalling the build.

- Review fix: the warm-up checks out before it computes its cache key (the key hashed files that were not on disk yet, so it never matched and every run rebuilt the cache), and it also stores the checksum-verified Node archive in the cache so Build Image downloads nothing.

### Design decisions

- The warm-up is ungated: CodeQL runs on every PR (Scorecard), so a gated warm-up would leave it uncached. The exact-key
  lookup keeps that cheap.
- Caches written on `main` are visible to every PR, caches written on a PR only to that PR, which is why the fallback
  prefix exists.
- The Node archive pin in Build Image is unchanged; it now rides in the same restored repository.
- `codeql.yml` and `loadtest-db-ops.yml` still use `cache: maven` (separate workflows, not in this change).

---

## 🐛 fix(ci): ratings races in the Chat UI and the integration-test stall on main (2026-10-31)

**Repo:** EDDI (`fix/main-ci-ratings-it-hang`)

### What changed and why

Three CI failures present on `main` (and so on every PR) at 01c3250.

- **Chat UI ratings restore clobbered the person's own action.** `useRatings` applied the
  server's restore read verbatim, so a thumb tapped while the read was in flight was undone
  (flaky `ChatWidget.ratings.test.tsx`, and a real bug). Changes made since the read left are
  now kept over the server's older view. The restore effect also closed the rating card
  whenever it re-ran, including when the profile merely arrived after paint, so a fast click
  on "Rate this conversation" opened and immediately closed it (the Playwright
  `ratings-feedback.fullstack` timeout waiting for `rating-star-1`). It now closes only on a
  switch to another conversation. Both have unit tests in `useRatings.test.tsx`.
- **`RagWorkflowExtensionIT` stalled the failsafe fork.** It was the one Mongo-backed
  `@QuarkusTest` without `@TestProfile(IntegrationTestProfile.class)`, so Quarkus restarted the
  application without the Mongo dev-service connection string. The app then pointed at the
  unresolvable `mongodb:27017` and every Mongo-touching bean waited out the driver's 30 s server
  selection: 250 s on the last green run, ~580 s on main since more stores were added. The
  900 s fork timeout then killed whichever class was running (usually `PostgresGroupVerdictIT`,
  which is innocent). The test now carries the profile.

- **The rating card never opened for an agent that sends no greeting** (`ChatWidget.tsx`). With no messages the transcript renders its empty state, and the card was only rendered in the non-empty branch, so "Rate this conversation" set the state and nothing appeared. The Backend E2E agent has an empty workflow, so its ratings test waited for a star that was never rendered. The card now renders in both branches; a ChatWidget test covers the empty transcript.

- **Rating state across the restore read** (`useRatings`, `RatingCard`), from the review of this PR: an edit is now tracked per rating (in flight, confirmed since the read began, revision). A failed write rolls back to the server's value (from the read or the last confirmed write), never to empty, and only when no newer edit has started; a failed write before the read answers lets the read fill the value in. The per-conversation state is no longer cleared when the profile merely reloads for the same conversation, and the protection is retired when the next read begins. A card opened before the read finishes picks up the restored rating until the person has touched it.

### Design decisions

- No timeout was raised and nothing was skipped; the stall's cause was removed.

---

## 🔒 fix(backup): review findings on export, import and upgrade (2026-10-31)

**Repo:** EDDI (`fix/review-backup`)

### What changed and why

Verified findings from the CodeRabbit security review and the October code review, all on
the backup / import / sync path. Several authorization findings only matter with
`eddi.workspaces.enabled=true`; they are fixed regardless.

**Export**

- The agent and workflow documents are scrubbed like every other archived document — a
  Dream job's model `apiKey` and legacy `channels[].config` tokens used to leave verbatim.
- The export scrubber now redacts query parameters whose *name* marks a credential
  (`key`, `sig`, `signature`, `accessKey`, … — `ConfigSecretRedactor`'s rule) in relative
  paths and absolute URLs, so a signed-URL signature an editor's GET hides no longer
  leaves in the archive. Form bodies are not affected.
- A URI found anywhere in a workflow other than its step references (which the authoring
  check vetted) needs `VIEW` before export or preview reads it; previously a URI in a free
  field of a step's `config` pulled another space's configuration into the archive.
- Snippets are exported only with `VIEW` (a `USE`-only listing no longer suffices).
- Connections go through `ConnectionReadRedactor` before the generic scrubber, so a
  pre-validation literal `passwordRef` is not exported.

**Import**

- A workflow written straight to the store runs `RestWorkflowStore`'s reference check
  (now reused via `WorkflowReferenceAccess`, walking `RestWorkflowStore.referencedResources`).
- A workflow directory without a workflow document is a 400, not a kept foreign reference.
- Repeated workflow URIs in an agent are imported once (no orphan copies).
- `updateParser` now goes through `updateTracked`, so a failed merge rolls it back too.
- Connections are created only for an administrator, as over REST; an editor's import
  skips (and counts) them.

**Upgrade / sync**

- A source resource two workflows share is matched per workflow: extension diffs carry
  their workflow's `workflowIndex`, and the executor looks diffs up per workflow (#8).
- A 409 is retried against the store's version only when the descriptor lags the store
  (a lost descriptor write) or when the newer version is one this run wrote itself (a
  target resource two workflows share is written once per workflow); any other version
  whose descriptor moved is somebody's edit and is reported, not overwritten.
- `createExtension` decodes the JSON before `requireMayAuthor`, so a JSON-escaped
  `${caller:token}` no longer passes the check; a created RAG config gets
  `KnowledgeBaseStorageGuard.prepareNew`.
- A newly created workflow runs the reference check before any of its resources is
  written.
- `ScrubbedSecrets` restores a *literal* target credential only when the object's
  destination (`…Url`, `uri`, `endpoint`, `host`, absolute `path`) is unchanged, compared
  by origin with scheme and host case-folded and a default port equal to an omitted one;
  vault references still come from the target. The identity lookup for array elements is
  indexed once per list instead of a scan per element.
- Source readers refuse more than 256 workflow references; the remote source fetches each
  document once per sync.

**Archive extraction:** a ZIP directory entry carrying data, or an entry nested more than
32 levels deep, is refused.

**Dead code:** removed `StructuralMatcher.readLatestVersionOrDefault` and
`UpgradeExecutor.readLatestVersion`; moved the stacked Javadoc in `StructuralMatcher` onto
`readLatestVersion`.

### Design decisions

- Export keeps "VIEW on the agent covers the graph beneath it" for the structural step
  references (a non-cascading share still exports); only references the authoring check
  never saw need their own `VIEW`. That includes the dictionaries a *parser document*
  names (the parser store does not vet them on write), so a recipient of a non-cascading
  share whose agent uses parser-document dictionaries they cannot see now gets a 403
  instead of an archive.
- Minimal edits outside `backup/`: `RestWorkflowStore.referencedResources` and
  `ConnectionReadRedactor.redactLegacyLiterals` made public for reuse;
  `SecretScrubber` gained the credential-query-name rule.

### Not done

- Consolidating `RestImportService`'s per-type create/update methods with
  `UpgradeExecutor.resolveExtensionOps` into one table: the import side carries
  per-type behaviour (RAG preparation, storage namespace, v5 LLM defaults) that makes it
  a larger refactor than this pass.

- Second review round: an upgrade write that meets a 409 for a version this run did not write is now always reported
  as "changed on this instance" instead of being retried when the descriptor lags the store (a lost descriptor bump and a
  save whose descriptor has not moved yet cannot be told apart, so the sync fails safe; preview and sync again). The
  workflow-reference rule now lives once, in `RestWorkflowStore.requireAccessToReferences`, shared with the backup paths.

**Files:** [`RestExportService.java`](../src/main/java/ai/labs/eddi/backup/impl/RestExportService.java),
[`RestImportService.java`](../src/main/java/ai/labs/eddi/backup/impl/RestImportService.java),
[`UpgradeExecutor.java`](../src/main/java/ai/labs/eddi/backup/impl/UpgradeExecutor.java),
[`StructuralMatcher.java`](../src/main/java/ai/labs/eddi/backup/impl/StructuralMatcher.java),
[`ScrubbedSecrets.java`](../src/main/java/ai/labs/eddi/backup/impl/ScrubbedSecrets.java),
[`WorkflowReferenceAccess.java`](../src/main/java/ai/labs/eddi/backup/impl/WorkflowReferenceAccess.java),
[`ZipArchive.java`](../src/main/java/ai/labs/eddi/backup/impl/ZipArchive.java),
[`SecretScrubber.java`](../src/main/java/ai/labs/eddi/secrets/sanitize/SecretScrubber.java),
[`import-export-an-agent.md`](import-export-an-agent.md), [`agent-sync-guide.md`](agent-sync-guide.md)

---

## fix(configs,datastore): review findings in the configuration stores and the datastore (2026-10-31)

**Repo:** EDDI (`fix/review-configs`)

Verified CodeRabbit findings and code-review leads (§4 Persistence, §5 dead code) for `configs/**` and `datastore/**`.

### What changed and why

**Configuration stores**

- **Reverse-reference lookups no longer loop on a caller-chosen version.** `AgentStore.getAgentDescriptorsContainingWorkflow` and `WorkflowStore.getWorkflowDescriptorsContainingResource` issued two to four queries per integer version from the `?version=` the caller sent down to 1, so `?version=2000000000&includePreviousVersions=true` kept a worker busy for billions of queries. The walk now starts at the referenced resource's LIVE version, read from its own store (`ResourceUtilities.clampToLiveVersion`; the workflow store for an agent lookup, `IResourceClientLibrary` for a workflow lookup), never from its descriptor: descriptors lag every in-process update, and clamping to a stale descriptor version made the cascade-delete and orphan-purge reference checks skip referrers of the live version. When the live version is unknown a walk above 1000 versions is refused (both delete paths read that as "still referenced") instead of being cut short. Only walks are clamped; an exact lookup (`includePreviousVersions=false`) queries the version it was asked about.
- **JSON Patch cannot relocate a credential the caller cannot read.** `ConfigJsonPatcher` applied the patch to the unredacted document, so an editor could `copy`/`move` a plaintext key into `description` and read it back. For anyone but an eddi-admin the patch now runs against a view where every credential `ConfigSecretRedactor` would redact is an opaque random token; a token is restored wherever it is a whole value, but one that ends up at another path (an `add`/`remove` earlier in an array shifts the indexes after it) is kept only if the redacted read still hides it there; a credential relocated into a readable field, or embedded in a larger string, is refused with a 400, and a `test` against a credential never sees its value.
- **JSON Patch output is bounded.** Operations are applied one by one and each `copy` is followed by a size check (nodes plus string length, counted with an early stop) that refuses growth beyond twice the stored document plus 1 MB, before anything is serialised. Shared JSON-P structure let a few copies build an exponentially large result.
- **Workflow references with a non-canonical scheme are checked.** `RestWorkflowStore.addReference` skipped any `config.uri` not starting with `eddi://`, but the engine resolves by host and id, so `Eddi://` or `x://ai.labs.llm/…` ran a configuration the access check never saw. Every URI with a scheme, host and id is now checked.
- **Parser documents check their dictionary references.** `RestParserStore` create, update and duplicate run the workflow store's reference check on the parser's extensions (VIEW on each newly introduced dictionary), so a private dictionary can no longer be reached through the standalone parse endpoint or an export.
- **Agent tests:** `maxCostPerRun` NaN/Infinity and a non-finite `expect.maxCostUsd` are refused (NaN passed the range check and disabled the budget).
- **Artifact JSON-schema validators** evaluate `pattern`/`patternProperties` through a deadline-bound regex factory sharing one 500 ms budget per validation, like the `REGEX` validator; a runaway pattern refuses the write. The budget is checked before every match as well as during one, so many short matches (every `patternProperties` pattern against every key) stop at it too.
- **Template migration is linear.** `TemplateSyntaxMigrator.unconvertibleReason` continues after the closer instead of re-scanning from every nested opener, and a text whose openers-times-length exceeds 50M is reported unconvertible and left alone instead of costing quadratic CPU at import or startup. Only anchors a conversion step can scan from count (`[[${`, `[(${`, `[#`, `#strings.`), so nested JSON arrays (`[[1,2]]`) no longer push a resource past the bound, and an import that the converter refuses as a whole text is reported in the log like a refused value.
- **Global variables:** the assembled-reference check scans in one linear pass instead of a regex retried at every unterminated opener. `ApiCallExecutor` refuses a full-form `${vars:tenant/key}` that only conversation data supplied (the configured template did not write it): it would read another workspace's variable.
- **Knowledge bases:** a 6.5.0 name-layout knowledge base that drops its explicit location moves to the id layout, as a rename already did (rename-keeping-location then drop-location reached another name's default store unchecked); an Elasticsearch `indexName` must name one concrete index (no wildcard, list or exclusion, and no surrounding whitespace, since the store receives it untrimmed); and the location collision scan fails closed when it runs past its 20,000-descriptor budget.
- **Channel integrations:** `duplicateChannel` runs the target USE check a create runs; the uniqueness checks and the write are serialised on the node, and a new integration's descriptor (which those checks enumerate) is written before the lock is released; a create whose descriptor cannot be written removes the integration again and fails with a 500 instead of leaving it invisible to the next check. A `channelId` a deployed agent's legacy Slack connector serves is refused instead of silently taking the route over. That check asks `ChannelTargetRouter.isServedByLegacyConnector`, which reads the live deployments and deployed agent configurations rather than the router's one-minute cache (a connector deployed a moment ago is not in it), and the save answers 503 when they cannot be read. The router is constructor-injected.
- **Group configurations:** `protocol.maxTurns` and a phase's `repeats` are capped at 1000, and `artifactConfig.maxArtifactsPerDiscussion` at 1000 (the quota counts a bounded listing).
- **Orphan purge:** the scan also walks the exact workflow version every current (not only deployed) agent pins, so a dependency a newer workflow version dropped is not purged from under an undeployed agent. It reads each agent and workflow at its live version rather than the version its (possibly stale) descriptor names, and the per-candidate re-check before a delete also asks whether a current agent pins a superseded workflow version that uses the extension (`IWorkflowStore.getWorkflowVersionsContainingResource`), failing closed.
- **Properties:** `PropertyValues.toProperty` and `copyOf` copy nested maps and lists, so a path assignment into a property built from a cached configuration value cannot write into that shared configuration.
- **`PropertiesMigrationService`** skips only on `postgres`, like every other datastore switch, instead of running only on the literal `mongodb`.

**Datastore**

- `PostgresUserConversationStore.deleteAllForUser` throws instead of returning 0 on a SQL error, so a failed GDPR erasure step is reported as failed.
- `DescriptorStore.findByOriginId` returns every match instead of the first ten, which was a cap applied before the caller's workspace/EDIT filter and could make a merge import create a duplicate.
- Sorted PostgreSQL `findResources` pages break ties on `id`, so offset paging over equal sort values neither repeats nor skips rows (the field index still supplies the leading key; the tie is ordered by an incremental sort). MongoDB deliberately keeps the single-key sort: its field indexes are single-field, and `{field: -1, _id: -1}` would turn every sorted page into a blocking in-memory sort over all matches.
- `PostgresAttachmentStore.grantAccess` throws `AttachmentNotFoundException` for a missing blob, as the MongoDB store does.
- Attachments that record no owner fail closed for **reads**: the MongoDB store no longer lets any conversation read them unless that conversation was explicitly granted access, matching PostgreSQL. **Deletes are unchanged** on both backends: an ownerless blob can still be deleted by its `storageRef`, because deleting discloses nothing and refusing it would strand personal data beyond erasure. `deleteByConversation` (conversation delete, purge, GDPR erasure) is untouched and matches on the owning conversation exactly as before. Docs: [Attachments guide](attachments-guide.md).
- `V6RenameMigration.saveDocument` addresses a string `_id` as stored (it queried `new ObjectId(string)`, which never matched) and counts a document only when the replace matched.
- Dead code removed: `datastore/model/ResourceId` and its test; the unreachable null-weight check in `AgentGroupStore.validateVotePhases` (`VoteConfig` copies weights with `Map.copyOf`, which refuses null values).

Docs updated: [RAG](rag.md), [Group conversations](group-conversations.md), [Attachments guide](attachments-guide.md).

### Design decisions

- **Patch protection by token, not by path rules.** Deciding which patch operations touch credentials by their pointers breaks on array shifts and nested copies; masking the document with per-request random tokens, and admitting a token at a new path only where the redaction rules still hide it, covers both and reuses the read redaction's own rules. Over-masking (no skip paths) only restricts copy/move for non-admins.
- **Reverse-lookup clamp uses the resource's live version from its own store**, injected lazily (`Provider`) because the workflow store and the resource client library reach back to the agent and workflow stores through the REST facades. The descriptor's version was tried first and is wrong: it lags in-process updates. A store-level prefix query would avoid the per-version walk entirely but needs new queries on both backends.
- **Ownerless attachments fail closed for reads only**: the PostgreSQL store already refused those reads, and an owner nobody can establish is not an owner. Deletes stay allowed, since a delete that refuses data it cannot attribute would make it impossible to erase.

### Deferred

- Descriptor PATCH vs. sharing race (full-snapshot write conditioned only on the resource version): needs a descriptor revision or field-level update on both backends.
- Slack approval records keyed by integration name: needs an immutable integration id on approval records (integrations area).
- Knowledge-base collision check against locations of older, still-pinned versions: needs a location index across versions.

---

## 🐛 fix(engine): conversation engine, HITL and memory findings from the external reviews (2026-10-31)

**Repo:** EDDI (`fix/review-engine-conv`)

Verified findings from the CodeRabbit security review and the full code review against `01c3250`, for the conversation engine (`engine/internal`), the HITL services and conversation memory (`engine/memory`).

### What changed and why

**Conversation state can no longer be resurrected or overwritten blindly**

- The agent-timeout watchdog and the turn error path wrote `EXECUTION_INTERRUPTED` / `ERROR` with an unconditional store write. An end only raises the cooperative-cancel flag, which a hung model call never sees, so ending a stuck conversation was undone by the watchdog — and `EXECUTION_INTERRUPTED` auto-recovers on the next say, so the ended conversation was drivable again and invisible to the ENDED-only retention sweep. Both are now a compare-and-set from the state they read, never over `ENDED` (`ConversationStepRunner.transitionUnlessEnded`; both stores already had `compareAndSetState`).
- The admin `PATCH /agents/{conversationId}/state` read the state and then wrote READY: `IConversationService.resetConversationState` now takes the expected state and is a compare-and-set; a lost race answers `409`.
- A cancelled say ends its pipeline through the `STOP_CONVERSATION` path, so the caller was told `ENDED` while the store recorded `EXECUTION_INTERRUPTED`. It is now reported as what the store holds (`ConversationService.reportCancelledTurnAsInterrupted`).

**HITL**

- An `ABORT` timeout (and the pending-approval retention sweep in `HitlCrashRecoveryObserver`) used the full cancel and was not bound to its pause: a fire claimed just before a resume stopped the resumed turn, or aborted a newer pause. Both now use the paused-only cancel bound to the pause id (`IConversationService.cancelPausedConversation(id, by, pauseId)`), which re-checks the pause after its compare-and-set and puts back a pause it did not mean. An `ABORT` fire that meets a resume of its pause still in flight (`IN_PROGRESS`) stays armed and is retried rather than completed, since that resume may still fail and restore the pause. The group surface is left to the groups work.
- Resume did not apply the GDPR Art. 18 processing restriction that start and say apply; a restricted owner could drive a resumed turn (including a rejection-note revision). It now refuses with the pause restored and its timeout still armed.
- A tool pause's timeout policy came from the governing rule of the *matched* calls only, so an `AUTO_APPROVE` rule for one endpoint auto-approved an unmatched gated call bundled into the same model message. A call that no policy-stating rule covers now contributes the policy it would inherit, strictest wins.
- `getAgent` keeps deploying a conversation's pinned version on demand even when its deployment record says `undeployed`: the old-version cleanup undeploys a previous version whose only open conversations are paused (and retirement does the same for movable ones), so refusing would strand those approvals at the next deploy. `docs/hitl.md` claimed a resume after an undeploy returned `409`; it now describes this.
- The approver projection stripped the revision candidate (frozen prompt + tool results) from active steps but not from the redo cache an undo fills.

**Memory, retention and reads**

- The raw read `GET /conversationstore/conversations/{id}` and `approval-status?detail=full` served `audit:compiled_prompt` — the agent's rendered system prompt — to an owner who may only USE the agent. Both now strip `audit:*` (keeping `audit:token_usage`) unless the caller has VIEW on the agent, as the detailed view and MCP `read_audit_trail` already did (`ConversationMemoryUtilities.stripAuditData`).
- The retention sweep deleted the snapshot without the memory checkpoints (copies of the properties) and the HITL tool journal, removing the only link a later user erasure had to them. It now runs the same dependent cascade as a permanent delete; and both paths keep the snapshot and descriptors when any dependent delete fails (`ConversationPurgeService.deleteDependentData`), or when the vault is unavailable while the conversation references auto-vaulted secrets, instead of orphaning the copies.
- The conversation listing's "search matches nothing → list everything" fallback decided by searching the whole store, an existence oracle over other users' conversations. It is now decided within the caller's own records.
- Mid-turn memory checkpoints copied properties before the turn-end secret-context scrub, persisting a secret context value a property instruction had copied. The checkpoint copy is now scrubbed with the same thresholds (`MemorySnapshotService.withoutSecretContextValues`).
- `SecretValueScrubber` decoded serialized JSON only in exact mode; in substring and whole-token mode a secret with a JSON-escaped character survived inside tool-call arguments. All modes now decode embedded JSON.
- Attachment uploads to a legacy conversation used the descriptor's (null) owner as the quota identity, exempting them from the per-user quota; they now use the owner the access guard resolved.
- An `Idempotency-Key` retry was served a turn's answer from the grace cache even when the turn's persist then failed; a failed persist now drops it (`TurnIdempotencyService.forgetUnpersisted`).

**Administration**

- `undeployThisAndAllPreviousAgentVersions` counted down from any requested integer (one DB read and one undeploy task per number, plus an "undeployed" record for versions that never existed). The starting version must now be one the agent has (`400` above its current version).
- The agent quota counted only each agent's newest runtime version, so a failed newer version hid a live older one; it now counts any READY version. A deployment-count outage failed open; it now refuses with `503` while an agent limit is in force (`TenantQuotaService.agentCountUnavailable`).
- The live log stream (`/administration/logs/stream`) kept delivering admin-only records after the opening token expired; it now closes at the token's expiry, as the cluster activity stream does.

**Dead code and duplication**

- Removed `SecretValueScrubber.scrubContext`, `ITurnAbandonment` with `ConversationService.abandonInFlightTurn` (only a test called it), and the unused `MemoryKeys.SYSTEM_MESSAGE` / `PROMPT`.
- `ConversationAccessGuard`'s four near-identical owner checks now share one helper.

### Design decisions

- Pause-bound cancels re-verify the pause *after* the state compare-and-set and restore it on a mismatch, before any side effect — the same pattern resume uses — rather than adding a pause-id predicate to both stores' CAS.
- The agent quota fails closed only where a limit applies, so a deployment-store outage never blocks deploys on deployments without an agent quota.
- Whether an admin's explicit undeploy should block resuming the version's pending approvals is open: it needs an undeploy record that tells an explicit undeploy from the automatic cleanup, which writes the same `undeployed` status today.

- Second review round: a bulk undeploy no longer answers 404 when the deployment records could not be read (500 instead); the log stream stops its initial batch when the token expires mid-batch; `ConversationPurgeService.purge` ends the conversation before the first irreversible cleanup step, so a delete that fails part-way leaves it unable to take a turn until the retry finishes (and when that write itself fails, the delete stops before any cleanup and is retried); the stale-turn tests also assert that no compare-and-set to `ERROR` happens.

### Deferred

- **A persistent pending-deletion state** (a dedicated state that blocks every read path, not only turns) is not added: a partly purged conversation is ENDED, findable for the retry, the retention sweep and erasure, but still readable through the history endpoints.

- API-call quota spent on turns refused after admission (duplicate, skipped, ended): needs a refund operation in both quota stores or moving acquisition into the queued turn — a product decision on whether skipped turns count.
- `ConversationService`'s thin private delegators kept for reflective tests (§7): reducing them means rewriting the characterization suites that reach them via `getDeclaredMethod`; not done here.

**Files:** [`ConversationStepRunner.java`](../src/main/java/ai/labs/eddi/engine/internal/ConversationStepRunner.java), [`ConversationService.java`](../src/main/java/ai/labs/eddi/engine/internal/ConversationService.java), [`ConversationHitlService.java`](../src/main/java/ai/labs/eddi/engine/internal/ConversationHitlService.java), [`HitlTimeoutHandler.java`](../src/main/java/ai/labs/eddi/engine/internal/HitlTimeoutHandler.java), [`HitlCrashRecoveryObserver.java`](../src/main/java/ai/labs/eddi/engine/hitl/HitlCrashRecoveryObserver.java), [`RestAgentAdministration.java`](../src/main/java/ai/labs/eddi/engine/internal/RestAgentAdministration.java), [`RestAgentEngine.java`](../src/main/java/ai/labs/eddi/engine/internal/RestAgentEngine.java), [`RestLogAdmin.java`](../src/main/java/ai/labs/eddi/engine/internal/RestLogAdmin.java), [`TurnIdempotencyService.java`](../src/main/java/ai/labs/eddi/engine/internal/TurnIdempotencyService.java), [`RestConversationStore.java`](../src/main/java/ai/labs/eddi/engine/memory/rest/RestConversationStore.java), [`RestAttachmentUpload.java`](../src/main/java/ai/labs/eddi/engine/memory/rest/RestAttachmentUpload.java), [`ConversationPurgeService.java`](../src/main/java/ai/labs/eddi/engine/memory/ConversationPurgeService.java), [`ConversationMemoryUtilities.java`](../src/main/java/ai/labs/eddi/engine/memory/ConversationMemoryUtilities.java), [`MemorySnapshotService.java`](../src/main/java/ai/labs/eddi/engine/memory/MemorySnapshotService.java), [`SecretValueScrubber.java`](../src/main/java/ai/labs/eddi/engine/memory/SecretValueScrubber.java), [`IConversationService.java`](../src/main/java/ai/labs/eddi/engine/api/IConversationService.java), [`TenantQuotaService.java`](../src/main/java/ai/labs/eddi/engine/tenancy/TenantQuotaService.java), [`ConversationAccessGuard.java`](../src/main/java/ai/labs/eddi/engine/security/ConversationAccessGuard.java); docs [`hitl.md`](hitl.md), [`gdpr-compliance.md`](gdpr-compliance.md), [`tenant-quotas.md`](tenant-quotas.md), [`deployment-management-of-agents.md`](deployment-management-of-agents.md), [`rest-api-reference.md`](rest-api-reference.md).

---

## fix(engine): review findings — HITL, GDPR, Dream, alerts, audit, mail (2026-10-31)

**Repo:** EDDI (`fix/review-engine-hitl-alerts`)

Fixes from the CodeRabbit review and the full code review (against `01c3250`) for the engine runtime and the packages around it. Each fix has a unit test that fails without it.

### What changed and why

**HITL**
- A reissue of an approved call that has already executed is gated again (`ToolApprovalGate.REPEAT_REASON`) instead of running a second time on one approval.
- Approval patterns are matched by a linear glob matcher (`ToolApprovalPatterns.matches`), not one backtracking `.*` per wildcard.
- `TaskToolApprovalsResolver` (strict) pins a task rule with no `timeoutPolicy` to `WAIT_INDEFINITELY` when it would inherit the agent's `AUTO_APPROVE`, so it cannot hide a stricter agent rule.
- `SelfUngatingGuard` compares an LLM-store write against the stored configuration: a whole-document `PUT` that omits a stored task gate, or a patch on its ancestor, is refused (lookup wired through `ActingAgentResources`).
- The tool journal's TTL expires `EXECUTED` entries only; a crash-orphaned `EXECUTING` claim is kept until its conversation is deleted, so a late approval cannot run the call again.

**GDPR, tenancy, ratings, Dream**
- Erasure keeps conversation snapshots when attachment deletion failed, keeps mapping rows when their cache keys could not be read, and reports nodes that did not confirm the cluster stop (`IClusterRpc.expectedPeers`). `restrictProcessing` removes a legacy agent-owned row under the reserved key first, which otherwise made the restriction unenforced.
- `PostgresTenantQuotaStore.setQuota` propagates a write failure (503) instead of acknowledging a quota that was never stored.
- Withdrawing a rating blanks the comment excerpts alerts copied from it first; erasure could not find them afterwards.
- Dream partitions consolidation by group readership (no union of group ids across groups), merges duplicate keys in linear time with the cap checked per append, and refuses a cycle whose pinned agent version names an ungranted vault secret.

**Alerts and audit**
- Webhook targets, signing-secret references and multi-agent rules need the `eddi-admin` role (`AlertAccess.hasAdminRole`), not merely unrestricted visibility, which every editor has while workspaces are off.
- Digests go out per originating agent; deleting/disabling a rule or setting its digest to `NONE` releases its `DIGEST_PENDING` events (`IAlertEventStore.releaseDigestPending`). An output value older than the erasure fence's lifetime is not stored. `AlertEngine` lost its test-only constructors and the duplicated 24-argument event rebuilds (`AlertEvent.withMatch`), and uses `AlertMatchers.applies`.
- The audit chain check finds holes between sequenced rows in a window that also holds unsequenced rows (was `UNAVAILABLE`), and with signing on ignores positions claimed by v1/v2-signed rows, whose signature does not cover the sequence.

**Mail**
- `DkimVerifier` refuses a header block with a bare CR or LF (the MIME parser splits a header there, the verifier did not). `QuoteStripper`'s label patterns are possessive (quadratic backtracking on long blank runs).

Docs: [Alerts](alerts.md), [Audit Ledger](audit-ledger.md), [HITL](hitl.md), [Secrets Vault](secrets-vault.md).

- Second review round: a digest is skipped when its rule was disabled or deleted after the pass began, and withdrawn (not delivered) when every held event was released first; `NatsClusterRpc.expectedPeers` reads presence uncached, fails closed when it cannot, and excludes this node by id; erasure keeps conversation snapshots when the conversation-id lookup failed; a failed tool-journal delete keeps the conversation (`ConversationPurgeService.purge` throws) so a retry can still reach the rows naming the approver; Dream `warn` mode logs ungranted vault references; agent-trigger cache misses re-check inside the stripe lock; `AgentSetupService` registers API-call locations before the creation loop so rollback sees them; two docs/Javadoc corrections.

### Design decisions

- **A repeat of an executed approved call goes back to a human** rather than replaying the journaled result: replay needs a new result path through `ToolLoopRunner`; re-gating is safe with the code this group owns.

### Not done (deferred)

- **Stale cached alert rules** can, for up to the rule cache TTL (60 s) on another replica, still create a `DIGEST_PENDING` event after the rule was disabled; such an event stays visible in the inbox until retention. Reconciling them needs a store query for held events of rules that are no longer digest rules.
- **Mongo journal TTL migration during a rolling update:** an old pod can recreate the unfiltered `idx_journal_ttl_claimed` after a new pod dropped it, until it is stopped. Finish the upgrade (no old pods) before relying on the EXECUTING-claim retention; a versioned migration marker is not part of this change.

- **Audit tail / whole-trail deletion** stays undetectable: it needs an anchored chain head stored outside the trail (or a predecessor digest in a new canonical form), which is a format change.
- **Interim narration of a tool-approval pause** is not screened by the task's output guardrails: the narration is built in `modules/llm` (`ToolApprovalGateSupport`), where the guardrails live.
- **Alert webhook HMAC keys** have no grant model of their own; with webhooks now reserved to the administrator role (who controls grants anyway) the remaining oracle is administrator-only.

---

## fix(engine): review findings — runtime, scheduling, cluster (2026-10-31)

**Repo:** EDDI (`fix/review-engine-runtime`)

Fixes from the CodeRabbit review and the full code review (against `01c3250`) for the engine runtime and the packages around it. Each fix has a unit test that fails without it.

### What changed and why

**Runtime and REST**
- `CronParser.parseField` checks a range's endpoints against the field's domain *before* enumerating it; `0-2000000000` used to allocate billions of boxed integers first (reachable from group cadences and schedules). The stepped loop no longer wraps on a huge step.
- `DocumentDescriptorFilter` takes the deleted resource's id from the request path and the `version` parameter only; `?x=/<other id>` made it mark another resource's descriptor deleted.
- `LegacyPathRewriteFilter` rewrites a v5 store prefix only at the start of the path, and `restricted`/`unrestricted` only in `/administration/{environment}/…`. A substring replace turned `DELETE /admin/gdpr/restricted` into an erasure of user `production`.
- `WorkflowStoreClientLibrary` reads secret instructions from the executable `PropertySetter` the task configures to; the check for `PropertySetterConfiguration` never matched, so failed turns of secret-collecting agents kept their input in dead letters.
- `RestInterfaceFactory` addresses the HTTP listener EDDI is bound to (`SelfUrlResolver`'s logic), not always `127.0.0.1`, and forwards no caller token when that address cannot be resolved.

**Scheduling and cluster**
- A schedule whose claim expired without an outcome (its node died or the fire wedged) counts as a failed attempt instead of being re-fired at once with `failCount` untouched — a node-killing fire could never dead-letter. The poll runs with `concurrentExecution = SKIP`. `tryClaim` re-checks `enabled` (MongoDB and PostgreSQL). `POST /{id}/fire` and the MCP `fire_schedule_now` tool are bounded by the fire timeout like a polled fire, so a manual Dream or ingestion run cannot outlast its lease and start twice. A disabled schedule can no longer be fired manually.
- A scheduled fire that starts a conversation runs the agent's startup workflow under `maxCostPerFire`, and the turn gets the remainder.
- A dead letter a node kept locally while cut off from NATS is discarded, not forwarded, when its conversation no longer exists (erased in the meantime).
- NATS TLS verifies the broker host name (`eddi.nats.tls.verify-hostname`, default `true`); jnats sets no endpoint identification, so any trusted certificate for any host was accepted. **Upgrade note:** the broker certificate must now carry a SAN for every host in `eddi.nats.url` (an IP SAN for an IP address); see [Clustering](clustering.md).
- `CacheFactory.evictLocal` remembers key hashes instead of SHA-256-ing every cached key on every eviction event. `BoundedLogStore`'s DB queue is capped (50,000; drops are counted and logged at flush). `BoundedFanOut` claims "not started" by compare-and-set, so an item reported as not sent can no longer go on and send. `AgentTestRunner` charges an abandoned (timed-out) turn's cost when it completes.

**Cleanup:** unused `ClusterConfig.withDegradedNonces/withDegradedRateLimits/withHitlRecovery` removed; misplaced Javadoc in `ScheduleFireExecutor` and `SchedulePollerService` moved to its methods; the nine fire-log blocks in `ScheduleFireExecutor` share `recordFire`.

Docs: [Clustering](clustering.md), [Scheduling](scheduling.md), [Configuration Reference](configuration-reference.md).

- Second review round: a manual fire cut off at its deadline (REST and MCP) answers a timeout (504 / an error result) instead of an empty 200, and leaves the schedule claimed until its lease expires rather than releasing it while the worker may still run (`FireAbandonedException`); a local dead letter whose conversation cannot be checked stays on the node instead of being published, and a legacy conversation without a stored state is no longer mistaken for a missing one; an unreadable startup spend leaves the turn no allowance instead of the full `maxCostPerFire`; `BoundedFanOut` no longer reports a gate-refused item as started; `BoundedLogStore` hands the database fixed-size batches; two docs/test corrections.

### Design decisions

- **An expired claim is a failure, not a retry-now.** The lease already outlasts the fire timeout by a margin, so a claim that expires means the fire is gone.
- **Hostname verification is on by default** and can be turned off; an IP address in `eddi.nats.url` then needs an IP SAN.

### Not done (deferred)

- **Late charges of an abandoned agent-test turn** (`AgentTestRunner`) are still not persisted into the already-finalised run record; that needs an atomic adjustment of the terminal record or a hold on finalisation until the turn's persistence completes.
- **Local dead-letter forwarding vs GDPR erasure** has no shared erasure marker: a node that reconnects between the shared purge and the snapshot deletion can still publish an erased turn (the existence check now closes the common case).
- **The `SKIP` poll cycle** still waits for its batch's fires, so one long fire delays claiming other schedules on that instance; splitting the short claim cycle from the bounded fire wait is a larger change to the poller.

- **One fire-timeout mechanism** instead of the executor's turn latch plus the poller's deadline: both now use `eddi.schedule.fire-timeout`, and the manual path is bounded; merging them is a refactor.

---

## 🔒 fix(engine-surface): review findings on channels, Slack, MCP, A2A, sharing and outbound HTTP (2026-10-31)

**Repo:** EDDI (`fix/review-engine-surface`)

Fixes from the CodeRabbit review and the full code review (revision 01c3250) for
the external surfaces: `integrations/**`, `engine/mcp`, `engine/security`,
`engine/a2a`, `engine/httpclient`.

### Channels and Slack

- **Only credentials are vault-resolved.** `ChannelTargetRouter` used to expand
  `${vault:...}` in every `platformConfig` value, so an editor could put a
  restricted reference into a webhook's `inputTemplate` and read the plaintext in
  the resulting conversation. Only `botToken` and `signingSecret` are resolved now;
  everything else stays literal.
- **An unresolved credential is dropped, for every channel type.** The resolver
  passes an unresolvable reference through unchanged, and Slack used that public
  text as its HMAC key. The router now drops it (as it already did for webhooks),
  and `SlackSignatureVerifier` refuses a reference-shaped key outright.
- **A Slack bot token honours its secret's `allowedHosts`** (checked against
  `https://slack.com/api/` via `VaultHostAllowlist`).
- **The approval card no longer borrows another integration's bot token** via the
  editor-chosen approval channel; resume outcomes are posted only for
  conversations owned by a Slack principal (`SlackUserIdentity.isSlackPrincipal`),
  so a hand-made `channel:slack:...` trigger intent cannot post with another
  integration's identity. Only the reserved `slack:` form counts: a raw
  `U123ABC`-shaped owner is just a shape an OIDC principal can share, so a legacy
  thread mapping gets resume posts again once its next message re-keys it.
- **Event user ids are validated** (`[UW][A-Z0-9]+`) before they become
  `slack:<team>:<user>`, so a teamless signer cannot alias a workspace-qualified
  user by sending `user: "T1:U2"`.
- **Approval-card text is mrkdwn-escaped** (tool previews, reason, agent label).
- **`/v1` matrix parameters** (`v1;x/chat/completions`) no longer skip
  `OpenAiAuthFilter`'s key check.

### MCP

- `chat_managed` re-checks USE on the retained conversation's own agent, and a
  refusal there is the neutral "you may not continue this conversation" (the
  owner does own it), not "you do not own this managed conversation".
- `submit_group_human_input` (and REST `POST .../human-input`) return an
  acknowledgement unless the caller is the owner or an admin. The Manager's
  `submitHumanInput` resolves to `GroupConversation | HumanInputAcknowledgement`
  (narrow with `isHumanInputAcknowledgement`).
- `McpAdminTools` no longer echoes raw exception text: caller-fixable failures are
  described, everything else returns a log reference (`toolFailure` overload).
  Two refusals stay caller errors rather than `INTERNAL`: a guard's Quarkus
  `ForbiddenException` (`ResourceAccessGuard`, `OwnershipValidator`) answers
  errorCode `FORBIDDEN` with its reason, and malformed JSON in a `config` /
  `resourceMappings` argument answers "config is not valid JSON (line L, column
  C)" (`McpToolUtils.readCallerJson`) — resources, channels and triggers alike.
  `list_agent_resources` reads each distinct workflow once.
- `McpApiToolBuilder`: spec parameter names that collide with template namespaces
  are renamed (`properties_2`); suffix allocation is linear; a spec server URL is
  literal (declared variables take their defaults, other `{...}` is refused).
- `OpenApiSpecFetcher` repeats the target-policy check after connecting (DNS
  rebinding) and discards the response if it now fails.

### Security, A2A, outbound HTTP

- With authentication off, `GET /agents/managed/...` (creates a conversation)
  gets the same cross-site check as a `POST`.
- Access requests answer a non-configuration id exactly like a missing one, after
  counting the attempt; a cascade skips `HUMAN` group members, budgets its
  seeding, and omits the name of a skipped resource the caller cannot view.
- A2A caps caller-chosen ids at 256 characters and refuses non-scalar JSON-RPC ids.
  Every JSON-RPC response now carries the `id` member, `"id": null` for an
  unreadable request, as JSON-RPC 2.0 requires (`JsonRpcResponse` was NON_NULL).
- httpcalls: a cross-origin redirect hop keeps only content-negotiation headers
  (a credential can sit in any header name); with SSRF protection off a redirect
  hop's *connected* peer is held to the previous hop's address class.
- Constant-time compares in the area now use `HmacSignatures.constantTimeEquals`.

### Deferred

- Review eligibility pinned to the conversation's start version
  (`ConversationAccessGuard.requireConversationRead`) needs a descriptor field
  written by conversation setup (engine/internal) — left for that owner.
- Former team members' share notifications need the share subject stored on the
  notification (store schema change) for a cheap read-time check.
- Control-character scrubbers and domain normalisers (§6) live outside this area.

**Files:** [`ChannelTargetRouter.java`](../src/main/java/ai/labs/eddi/integrations/channels/ChannelTargetRouter.java),
[`SlackSignatureVerifier.java`](../src/main/java/ai/labs/eddi/integrations/slack/SlackSignatureVerifier.java),
[`SlackEventHandler.java`](../src/main/java/ai/labs/eddi/integrations/slack/SlackEventHandler.java),
[`SlackHitlResumeObserver.java`](../src/main/java/ai/labs/eddi/integrations/slack/SlackHitlResumeObserver.java),
[`SlackUserIdentity.java`](../src/main/java/ai/labs/eddi/integrations/slack/SlackUserIdentity.java),
[`SlackHitlSupport.java`](../src/main/java/ai/labs/eddi/integrations/slack/SlackHitlSupport.java),
[`OpenAiAuthFilter.java`](../src/main/java/ai/labs/eddi/integrations/openai/OpenAiAuthFilter.java),
[`McpConversationTools.java`](../src/main/java/ai/labs/eddi/engine/mcp/McpConversationTools.java),
[`McpHitlTools.java`](../src/main/java/ai/labs/eddi/engine/mcp/McpHitlTools.java),
[`McpAdminTools.java`](../src/main/java/ai/labs/eddi/engine/mcp/McpAdminTools.java),
[`McpApiToolBuilder.java`](../src/main/java/ai/labs/eddi/engine/mcp/McpApiToolBuilder.java),
[`OpenApiSpecFetcher.java`](../src/main/java/ai/labs/eddi/engine/mcp/OpenApiSpecFetcher.java),
[`UnauthenticatedRequestGuard.java`](../src/main/java/ai/labs/eddi/engine/security/UnauthenticatedRequestGuard.java),
[`ResourceSharingService.java`](../src/main/java/ai/labs/eddi/engine/security/spaces/ResourceSharingService.java),
[`ConfigGraphResolver.java`](../src/main/java/ai/labs/eddi/engine/security/spaces/ConfigGraphResolver.java),
[`WorkspaceNotifications.java`](../src/main/java/ai/labs/eddi/engine/security/spaces/notifications/WorkspaceNotifications.java),
[`A2AWireFormat.java`](../src/main/java/ai/labs/eddi/engine/a2a/A2AWireFormat.java),
[`A2AModels.java`](../src/main/java/ai/labs/eddi/engine/a2a/A2AModels.java),
[`McpToolUtils.java`](../src/main/java/ai/labs/eddi/engine/mcp/McpToolUtils.java),
[`HttpClientModule.java`](../src/main/java/ai/labs/eddi/engine/httpclient/bootstrap/HttpClientModule.java);
docs: [`slack-integration.md`](slack-integration.md), [`webhook-triggers.md`](webhook-triggers.md),
[`httpcalls.md`](httpcalls.md), [`security.md`](security.md), [`a2a-protocol.md`](a2a-protocol.md),
[`workspaces.md`](workspaces.md), [`group-conversations.md`](group-conversations.md), [`architecture.md`](architecture.md).

---

## 🐛 fix(groups): review findings — HITL scope, follow-up budget, task-force results, negotiation, JSON extraction (2026-10-31)

**Repo:** EDDI (`fix/review-groups`)

Fixes from the external code review and the CodeRabbit pass against `01c3250`, group-conversation area.

### What changed and why

**HITL authority and read scope**

- **Approvers can no longer cancel another user's running discussion.** The group cancel on REST and on the MCP
  `cancel_group_discussion` tool now uses `HitlAccessGuard.mayCancelExecuting`, like the 1:1 surface: an
  approver-only caller gets a paused-only cancel (`cancelPausedDiscussion`, one compare-and-set on
  `AWAITING_APPROVAL` / `AWAITING_HUMAN_INPUT`) and `403` / `FORBIDDEN` on a running discussion. The Javadoc that
  claimed both surfaces already did this is now true.
- **Responses no longer hand the transcript to callers who may not read it.** The JSON approve, the human-input
  submission and the approver cancel return the conversation only to its owner or an admin; an approver or a
  pending HUMAN member gets `{"state": …}`. MCP `approve_group_phase` and `submit_group_human_input` match.
- **A human-turn resume no longer runs as the submitter.** `resolveHumanTurn` bound the submitting member's
  identity to the rest of the owner's discussion, so later `${caller:token}` calls went out with their token. It now
  uses `resumeCallerFor`, the rule the approval resume already followed.
- **Group HITL timeouts are bound to their pause.** Approval `ABORT`, human-turn `ABORT` and `SKIP_TURN` pass the
  schedule's `pauseId`; a late fire no longer skips a newer human turn or cancels a discussion that has resumed.
  Stale `skipHumanTurnOnTimeout` Javadoc ("never throws") corrected.

**Discussion engine**

- **Follow-ups respect the cost ceiling and are accounted.** `followUpWithMember` refuses once the effective
  `maxCostPerDiscussion` is spent (`409`), harvests the member's cost into the ledger, and propagates
  dynamic-agent tracking so sub-agents created in a follow-up are cleaned up with the discussion.
- **A skipped or errored task-force member fails its task.** It used to complete the task with a `null` result,
  unblocking dependents that then ran without its output. `SharedTaskList.completeTask` / `submitForApproval` now
  refuse a `null` result.
- **Negotiation cannot record a stale agreement.** Accepting a proposal withdraws the signer's signature from every
  other open proposal. An agreement keeps the dissents a dissent round recorded, and an arbitration verdict now
  replaces a dissent-only `NONE` record instead of being dropped.
- **One balanced JSON extractor for every group engine** (`EmbeddedJson`): facilitator, retro, vote, negotiation,
  bid, convergence and debate parsers, and the VERIFY phase. The greedy first-`{`-to-last-`}` slice dropped a
  ballot followed by a brace in prose; the first-`[`-to-last-`]` slice made VERIFY fail open on any bracketed
  aside. Each engine accepts only an object carrying a key it reads (`vote`/`votes`/`statement`, `bids`, `move`,
  `lessons`, `accept`/`proposal`/`concessions`, `passed`, `agreementScore`, `winner`), so an unrelated object in
  the prose (an example, a quoted config, `{}`) is neither taken for the reply nor a rival to it. A reply carrying
  two *well-shaped* values is ambiguous and rejected — unchanged for the greedy engines (two objects never parsed
  as one), but a behaviour change for bids and bargain moves, which used to take the first. The documented
  fail-open on a truly unparseable verdict is unchanged.
- **`groupTranscript` is scoped** by the phase's `contextScope`, like the rendered prompt, and a member
  conversation's startup step now receives the group's dynamic-agent policy instead of the permissive standalone
  default.
- **Stance extraction is linear.** `leadSentence` rescanned the prefix at every terminator.

**Dead code:** `GroupConversationService.DEFAULT_ENV`, `PhaseOutcome.endDiscussion` / `END_DISCUSSION` and the
unreachable `endDiscussionEarly` handling, and the `findMember` / `findMemberIncludingDynamic` reflective
delegators (covered directly by `TaskForceEngineTest`).

### Not done

- Retention revocation (`unretainAgent`) still does not propagate to the group registry, and the
  discussion-wide creation cap is still checked against a per-member copy. Both need a change in
  `modules/llm` (`DynamicAgentToolsProvider` / `CreateSubAgentTool`) to record revocations and reserve slots.
- The approval/reject CAS still compares only `state`, not the pause id; that needs a multi-field conditional
  write in the storage layer.
- The remaining ~24 private delegators in `GroupConversationService` stay until their reflective tests are ported.

- Second review round: the follow-up cost-ceiling lookup propagates store failures instead of meaning "unlimited"; an
  exhausted `EmbeddedJson` candidate budget is ambiguous, not a unique match; an ABORT no-result task turn stops the
  following waves; a blank task result is no result; a verification array is accepted only when an element carries a
  `subject`.

**Files:** [`GroupHitlCoordinator.java`](../src/main/java/ai/labs/eddi/engine/internal/groups/GroupHitlCoordinator.java),
[`RestGroupConversation.java`](../src/main/java/ai/labs/eddi/engine/internal/RestGroupConversation.java),
[`McpHitlTools.java`](../src/main/java/ai/labs/eddi/engine/mcp/McpHitlTools.java),
[`HitlAccessGuard.java`](../src/main/java/ai/labs/eddi/engine/hitl/HitlAccessGuard.java),
[`HitlTimeoutHandler.java`](../src/main/java/ai/labs/eddi/engine/internal/HitlTimeoutHandler.java),
[`GroupLifecycleOps.java`](../src/main/java/ai/labs/eddi/engine/internal/groups/GroupLifecycleOps.java),
[`TaskForceEngine.java`](../src/main/java/ai/labs/eddi/engine/internal/groups/TaskForceEngine.java),
[`NegotiationEngine.java`](../src/main/java/ai/labs/eddi/engine/internal/groups/NegotiationEngine.java),
[`EmbeddedJson.java`](../src/main/java/ai/labs/eddi/engine/internal/groups/EmbeddedJson.java),
[`hitl.md`](hitl.md), [`group-conversations.md`](group-conversations.md),
[`passing-context-information.md`](passing-context-information.md)

---

## Deployment and script hardening from the code review (2026-10-31)

Repo: EDDI, branch `fix/review-infra`. Low-severity findings in operator scripts and manifests.

- `install.sh`: every Keycloak admin call now sends its bearer token through a 0600 curl config file (`kc_authed_curl`) instead of `-H` on the command line, so it no longer shows in `/proc/<pid>/cmdline`. The already-running repair path verifies the downloaded `keycloak/eddi-realm.json` against the release `SHA256SUMS` before posting scope definitions to Keycloak, and no longer sends stored Keycloak/Grafana admin passwords to a localhost port unless Docker reports a running container of this installation's compose project, for that service, publishing that port; when Docker cannot be queried the credentials are withheld. The check sits in `kc_password_token`, so every password grant is covered. A bearer token is accepted into the curl config only if it consists of token characters, and a failed realm download is truncated so no unverified partial file reaches Keycloak.
- `gcp/provision-vm.sh`: `--vault-key` is removed. The key was appended to the installer flags, uploaded as startup-script instance metadata and echoed into `/var/log/eddi-install.log`; the VM now generates its own master key (in `/root/.eddi/.env` on the VM), like the Keycloak admin password. Passing the flag is an error that says why.
- `docker-compose.yml`, `docker-compose.cluster.yml`, `docker-compose.postgres-only.yml`: the bind guard fails closed. Only `false`/`0`/`no`/`off` (any case) count as "authentication on"; `TRUE`, `1`, `yes`... no longer slip past the refusal.
- `docker-compose.auth.yml`: `keycloak-admin-rotate` writes the `.eddi-admin-verified` marker only after a successful login with the configured credentials. When `KC_BOOTSTRAP_ADMIN_USERNAME` names another user, the original `admin` account is also tried with `admin` and rotated to the configured password if it still accepts it, so a renamed admin user can no longer certify a volume whose `admin/admin` remains. kcadm reads the configured password from `KC_CLI_PASSWORD` (supported by kcadm 26.x) instead of `--password`, so it stays out of the process arguments; stdin is not an option because kcadm's prompt needs a console.
- Helm: `nats.externalUrl` containing `@` is refused (it lands in a ConfigMap); new `nats.externalUsername` / `nats.externalPassword` go to the chart Secret. A password without a username, these values alongside `eddi.existingSecret` (which would make them inert), and these credentials with a non-`tls://`/`wss://` server are all refused at render time; `nats.externalAllowPlaintext=true` is the opt-out for an encrypted transport underneath EDDI. Chart `2.12.0` → `2.13.0`. `docs/kubernetes.md` updated.
- Kustomize: warnings in the ingress component (plain HTTP as shipped; the redirect annotation is named as ingress-nginx-only) and in the auth overlay (remove the localhost redirect URIs outside dev). `k8s/create-secrets.ps1` writes the temporary key file into a private directory made before any cluster change (`mktemp -d`, mode 0700 from creation, on Linux/macOS; a per-user temp subdirectory on Windows), so it works on every PowerShell 7.x and a failure there cannot follow the `-Force` delete.
- `docs-site/scripts/check-live.mjs`: redirects are followed only to the configured origins (parsed origin comparison, not string prefix).
- `scripts/cluster-demo/mock_llm.py`: 10 s socket timeout, 1 MiB body cap, and loopback by default (`demo.py` binds it to the docker bridge gateway with Docker Engine on Linux via `MOCK_LLM_BIND`, keeps loopback on Docker Desktop, including Docker Desktop for Linux, never all interfaces, and aborts when the mock does not come up listening; the README's manual `--external-mock` example sets the same binding). `scripts/loadtest/loadtest.py`: startup state moved from the world-writable `results/` to `scripts/loadtest/.state/` (0700), written atomically through a fresh `mkstemp` file per save (no shared `.tmp` between overlapping runs), a symlinked state directory refused, symlink ignored on read. `src/main/docker/seed-demo-agent.sh`: the provider key goes to curl on stdin, not `-d`, and a key or allowed host with anything but printable ASCII (a trailing newline included) is refused instead of being altered or producing invalid JSON — the curl image has no JSON encoder.

Decision: the shipped k8s base (unauthenticated quickstart), the Prometheus overlay and the other BY_DESIGN findings are unchanged.

---

## Decision Log

_For recording decisions that come up during implementation that aren't in the plan._

| Date       | Decision                                                              | Context                               | Alternative Considered                                      |
| ---------- | --------------------------------------------------------------------- | ------------------------------------- | ----------------------------------------------------------- |
| 2026-11-02 | `toolQuotas` reserve inside `ToolExecutionService.executeToolWrapped` (after cache, before execution), release on failure, keep on timeout | A quota that counts cache hits or refused calls, or that cannot give a failed call back, charges users for nothing; a timed-out call may still have completed its side effect | Reserving in `ToolLoopRunner` around the supplier (cannot see the cache; the refusal would be cached and charged); counting at result time (overshoots under concurrency) |
| 2026-11-01 | Late agent-test charges adjust the stored terminal record (compare-and-swap on `heartbeatAt`) | A timed-out run would otherwise wait up to the turn timeout plus the write-back wait before it could report | Holding finalisation for abandoned turns |
| 2026-11-01 | Dead-letter forwarding is fenced by a shared erasure tombstone, checked before and after publishing | The snapshot outlives the shared purge, so "does the conversation exist" cannot see an erasure under way, and one check still races the purge | Relying on the existence check alone |
| 2026-10-27 | docs.labs.ai redirects to docs.eddi.technology with an explicit 301 (not Vercel's default 308), on every path, kept forever | Domain decision for the GitBook replacement; released images print docs.labs.ai links indefinitely | `permanent: true` (308) — equally permanent, but not the decided code |
| 2026-10-27 | The prompt and response are removed from the stored step once the ledger accepted the turn's entries; tool calls, usage, cost, model name and guardrail records stay | The ledger is the system of record and holds the full text; the copy was a fifth of the document. Agent tests, cost metering and history replay read the small keys back from the stored step | A config switch (no consumer needs the text in memory); a hash/reference in place of the text (the ledger is addressable by conversation id and step index already); stripping `audit:tool_calls` too (agent tests read it from the stored step) |
| 2026-10-27 | Current-step and approval-status reads load a server-side projection (last step/output + computed totals) instead of the whole conversation document | Whole-document reads ran at ~100 KB/s on a bandwidth-limited database link; 0.5–3 MB documents made these reads take 11–29 s | Caching the snapshot (stale across nodes, does not help the first read); a `$slice` find projection without totals (undoAvailable, output/step pairing and conversationCost need the full lengths and every step's spend) |
| 2026-10-26 | Due resubmission rows of a deleted integration are left to ledger retention, not refused by the sweeper | Once the query is scoped to one integration they block nobody; a sweeper pass over all RECEIVED rows would again read one global batch | Refusing them in the inbound sweep; deleting an integration's ledger rows with it (the rows are GDPR-reachable history) |
| 2026-10-25 | A conversation-mode reply's recipient is decided in one function, from the ledger row cross-checked with the mail's step in conversation memory and the conversation's identity, on every attempt | Authentication is a fact of the inbound mail; the processor forces it on, and the reply path checks it again from two independent records, so a reply to an unauthenticated or anonymous sender cannot be produced even for a turn that should not have run | An authenticated flag on the ledger row (a new column on both stores for a fact the step already holds); trusting the processor's check alone |
| 2026-10-25 | Replies are rebuilt from the conversation on retry; group replies, whose discussion keeps no copy of the mail, are retried only by the node that holds them | The ledger stores no body or subject (plan §12); a group conversation has no field for the answered mail's subject | Storing the subject in the ledger; an extra copy of the mail in the group conversation |
| 2026-10-25 | A mail whose conversation was started but whose turn never ran is found again in the mailbox by Message-ID and run once | The raw mail is not kept, and the mailbox still has it (flagged or moved); the hand-back is a conditional transition and the turn carries the mail's ledger key | Keeping the rendered input (a body in storage); marking it lost (a mail silently not processed) |
| 2026-10-25 | The email suppression list stores a SHA-256 of the address per integration, and GDPR erasure deletes the entry | Enough to answer "suppressed?" and to clear it for someone who knows the address; conversation mode only answers mail the person sends, so an erased person is written to again only if they write first | Plain addresses (a list of correspondents); keeping a hash after erasure (plan §23 Q4 — keeps the identifier the request asked to remove) |
| 2026-10-25 | Suppressions are cleared through two channel REST endpoints, not an MCP tool or an operator grant | An administrator needs a way to undo a bounce without database access; the operator holds no channel endpoints by design | Store-only clearing (database access); an MCP tool (one more admin surface to secure) |
| 2026-10-25 | Email trigger mode never runs a mail twice once a side effect may have happened; a claim that did nothing is given back and the mail, still unmarked in the mailbox, runs again | The CLAIMED state separates "nothing left EDDI" from the point of no return; only a crash while starting the conversation is ambiguous, and that one is REFUSED with an owner notice | Refusing every stranded claim (lost mail that had run nothing); re-running submitted turns (double side effects); a sweep that opens its own IMAP session (two nodes on one mailbox) |
| 2026-10-25 | The thread mapping key of a correlated email conversation includes the sender's address | Message-IDs travel in every forward; under identityMode integration all senders share a user id, so a thread-only key lets anyone join any thread | An explicit sender field checked after lookup (one more stored fact and a check that can be forgotten); correlating by user id only (unsafe in integration mode) |
| 2026-10-25 | Unauthenticated email senders get a one-off email-anon id with no user-memory store and no memory tool, enforced in ConversationService and ContextualToolsProvider | Their From is a claim anyone can make; memory written under it would be read by the next forger | A shared anonymous id (one sender's injection persists for all); relying on the random id never recurring (still writes orphaned memories) |
| 2026-10-25 | Mail-server outages back off inside the poller and are COMPLETED fires with the error in the fire log | The schedule retry ladder dead-letters after a few minutes, which would silently stop a mailbox after a short outage | FAILED fires (dead-letter); SKIPPED fires (no error recorded) |
| 2026-10-25 | Polling state lives in a new IEmailMailboxStateStore (Mongo + Postgres), not the ledger or the cluster KV | The cursor is one value per mailbox that must outlive every ledger row's 30-day retention; the KV buckets are node-local and volatile in single-node mode | A cursor row in the ledger (expires, mixes per-message and per-mailbox facts); NATS KV (lost on restart) |
| 2026-10-25 | Save-time warnings travel as stable codes in an X-Eddi-Warnings response header | The channel save responses carry no body to extend, and codes let the Manager translate them in 11 locales | A response body (changes the create/update contract); log-only warnings (invisible to the author) |
| 2026-10-25 | Mail in the folder before an integration's first poll is never processed | Connecting an integration to a full mailbox must not make an agent work through years of mail | Processing everything unread (cost and side effects nobody expected); a startFrom key (more surface, deferred until asked for) |
| 2026-10-25 | A validly signed message with a second From, or a duplicated Subject/Date/Message-ID/In-Reply-To/References, is FAIL, not ALIGNED_PASS | Bottom-up selection hashes only one instance; the other is unsigned attacker text that clients may display | Trusting the signature alone; downgrading to PASS_UNALIGNED (would hide that the message is malformed) |
| 2026-10-25 | DKIM l= is refused as FAIL rather than honoured | It lets anyone append unsigned text that EDDI would read as the sender's | Honouring l= and ignoring the extra body (EDDI's parser would still read it) |
| 2026-10-25 | Signatures under a t=y key make a message NONE, not FAIL | RFC 6376 §3.6.1: testing-mode mail must be treated as unsigned | FAIL (would misreport a sender who is merely testing) |
| 2026-10-25 | A temporary DMARC lookup failure assumes strict alignment and flags the result temporary | Strict still authenticates an exact-domain signature, and never over-authenticates a subdomain one | Relaxed (could authenticate what the domain's policy forbids); FAIL (punishes the common exact-domain case for a DNS blip) |
| 2026-10-25 | dnsjava, not JNDI's DNS provider | JNDI gives no TTLs, no rcode, no control of the server per lookup and no TCP fallback control; dnsjava is BSD-3-Clause with one runtime dependency | JNDI (com.sun.jndi.dns); a hand-written DNS client |
| 2026-10-24 | Self-continuation is delivered as a one-shot schedule on the cluster-aware schedule machinery, not through the conversation coordinator's queue | E8 needs a continuation that survives a node crash, runs once under the conversation lease, and lands in dead letters when lost | Coordinator queue (in memory, lost on a crash); a custom scheduler (AGENTS.md forbids it) |
| 2026-10-24 | `maxContinuations` counts per conversation (steps carrying the engine-written `continuation:number`), not per chain | A counter the author or a property setter can reset is no bound; a job that needs more starts a new conversation | A conversation property as counter (writable by config); per-chain counts reset by user input |
| 2026-10-26 | Docs move off GitBook to an Astro + Starlight build of `docs/` on Vercel at docs.eddi.technology; docs.labs.ai redirects permanently; flat URLs from file names | GitBook URLs carry the SUMMARY section and break when a page moves; the backend's flat docs links 404 there; the marketing site already moved to eddi.technology on Vercel | Keeping docs.labs.ai canonical (docs stay on the old brand domain); folding the docs into the website repo (plain Astro with 11 locales, and docs PRs could not be validated before merge); GitHub Pages (no real redirects) |
| 2026-10-26 | DNS rebinding is closed per client: Vert.x checks the connected peer before sending, OutboundHttp re-checks after the exchange, MCP re-validates per request | Found by a repository security scan; previously an accepted risk | A JVM-wide InetAddressResolverProvider (affects every lookup in the process, loaded before app classes in Quarkus); IP-literal URLs with a Host header (breaks SNI and certificate checks) |
| 2026-10-25 | HITL notification recipients are bounded by an operator domain allowlist, defaulting to the sender's own domain | Agent authors choose recipients but the mail goes out under the operator's sender and relay, with author- and user-influenced text; an unbounded list makes the relay an open mailer | Unbounded recipients (open mailer); a per-agent allowlist (set by the same author it is meant to bound) |
| 2026-10-24 | Proposed (not approved): make → judge → repair extends the shipped output guardrails with a `judge` type (closed-vocabulary issue codes) and `onExhausted: deliver-best`, rather than a new loop (planning/evaluated-generation-plan.md) | Analysis of hosting a multi-stage video-generation pipeline found seven existing loops; #1064/#1112 made output guardrails the natural home | Checks in responseValidation, a classifier issues mode, free-text judge feedback, loops in LifecycleManager, a refine ILifecycleTask, groups for the per-item loop, a workflow engine |
| 2026-10-24 | HITL email notifications go to configured recipients only, not to "every approver" | EDDI's approvers are role holders in the identity provider, which EDDI cannot enumerate; a list built from past sign-ins would keep mailing a revoked approver indefinitely | Recording roles in the user directory at sign-in (stale and unrevokable); mailing the conversation owner (usually the end user, not an approver) |
| 2026-10-24 | The audit entry of a notification records the recipient as SHA-256 + domain, not the address | The audit ledger is write-once and erasure only pseudonymises its userId, so a plain address would outlive an erasure; the hash still answers "was this address mailed?" for someone who has it | Plain address (unerasable personal data); no recipient at all (unauditable) |
| 2026-10-24 | Operator-configured relays get their own socket policy instead of a bypass | An on-premises relay on a private address or port 25 is a normal deployment, but the pinning, metadata refusal and TLS verification are still worth having; a bypass would lose them | Allowing private addresses through eddi.email.allowed-hosts (would open them to channel authors too); opening a raw socket outside SafeSocketConnector |
| 2026-10-24 | Hidden-content pass in two modes: conservative for pages (comments, 1px, transparent, explicitly same-coloured text), strict for mail | Pages hide accordions, tabs and "read more" content with display:none/hidden/opacity until script reveals it — content RAG wants; mail runs no script, so anything hidden there is only for a machine | One strict rule set for every caller (dropped FAQ answers and tab panels from crawled pages); opt-in only for mail (leaves RAG without the white-on-white and zero-size defences) |
| 2026-10-24 | Style-block hiding rules over-approximate instead of being capped | A rule cap is a bypass: a display:none rule placed after the cap would leave the hidden text in | Truncating the style text or the rule count; exact selector matching per rule (one document search per rule, quadratic) |
| 2026-10-24 | A forward (Fwd:/FW:/WG:/TR: subject, or a forward separator) is never quote-stripped | Outlook writes the same From/Sent block for forwards as for replies, and a forwarded invoice is the content of a trigger-mode mail | Stripping by header block alone (deleted the forwarded mail) |
| 2026-10-24 | httpcall `forEach` is a new field, not `batchRequests` made to collect | Old configs with `batchRequests` on a waiting call send one request; reusing the field would silently send N | Extending `batchRequests`; dropping the fire-and-forget restriction |
| 2026-10-24 | `forEach.maxConcurrency` defaults to 1 | Sequential sends the same load as the calls written one after another; parallel is opt-in for APIs that allow it | A small default such as 4 |
| 2026-10-24 | A `forEach` item that threw (timeout) is never retried | The request may have been processed and billed | Retrying timeouts with the response retry policy |
| 2026-10-24 | Single-agent revise is `hitlConfig.onRejection: REVISE`, RULE pauses only, the frozen request kept as step data | Plan decision 9.5; a rejected tool call already reaches the model with the note | A new verdict value on the approval API; rebuilding the request from memory at resume (loses retrieved context and tool results) |
| 2026-10-24 | `maxReworks` runs the rework inside the VERIFY phase | The phase list stays linear and resume bookmarks untouched | Jumping the phase loop back to EXECUTE |
| 2026-10-24 | A guardrail-refused message is withheld from every later model-facing history by a step flag written at block time (`llm:input_withheld:<taskId>`), not by re-screening | The classifier does not re-screen history, so a message it blocked reached the model on the next turn | Making the classifier screen history (cost multiplies by the window); storing the redacted text in memory (size, and a classifier redaction is the block message anyway) |
| 2026-10-24 | `postResponse` runs after an input block, like every other fallback | An input-blocked `convertToObject` + `addToOutput: false` task answered nothing | Correcting the docs instead — that left the silent empty answer |
| 2026-10-24 | Webhook dedup refuses (503) per integration (own budget, fair share from half capacity, hard stop at 90%) rather than evicting; shared via the DEDUP bucket in a cluster | The dedup cache is the only replay protection of the github / generic-hmac schemes, and it sat on the 1,000-entry default shared by all integrations | A configurable size property (could be set below the traffic); fail-closed on any eviction (Caffeine cannot report one) |
| 2026-10-26 | A rejection note from a person asks the model to revise; automated `system:` decisions keep the "declined" text | The note is the user's way to say "not like this"; the model should come back with a new proposal to approve. Automated notes are explanations, and the group auto-reject would otherwise be retried in a loop. | Matching on the note text; a new verdict type that means "revise" |
| 2026-10-19 | The REST turn timeout counts from the request's arrival (first route of the router), and a turn still queued when its caller gave up is dropped unrun | Under a burst the worker-pool wait before the timeout was armed reached 79 s, and turns nobody waited for spent the model and the database | A default `turnDeadlineMs` for every agent; arming the timeout on a Vert.x timer at arrival, which races the resource method's own response |
| 2026-10-19 | `say`/`rerun` run on virtual threads, conversation starts stay on the worker pool | Starts on virtual threads collapsed a 2-CPU node (event loops starved); the pool is their only concurrency bound | All agent endpoints on virtual threads; a new start-concurrency limit (follow-up) |
| 2026-10-19 | `/q/metrics` is served by an EDDI route on two dedicated threads, ahead of Quarkus' blocking handler | The blocking route shares the worker pool and went blind for two minutes in every overloaded run | The Quarkus management interface, whose blocking routes use the same pool |
| 2026-10-15 | Webhook channel starts the conversation and admits the turn on the request thread | 202 must carry the conversation id and quota refusals must be a 429 | A separate executor like Slack's (429 unreportable), a non-consuming quota pre-check (none exists; racy) |
| 2026-10-15 | Self-hosted OpenAI-compatible servers get their own type, `openai-compatible`, with author-declared capability flags (jsonMode, jsonModeWithTools, vision; all off by default) instead of catalog rows | `type: openai` + `baseUrl` inherited OpenAI's JSON-with-tools and vision rules; the catalog validator requires a fixed https URL and model | Adding self-hosted rows to `openai-compatible-providers.json`; a new `extraBody` key next to `customParameters` |
| 2026-10-17 | GDPR database-log pseudonym is keyed (ledger key, else a persisted per-deployment salt); old unsalted pseudonyms are not migrated, and ledger step 7b keeps the unsalted token | L9: unsalted SHA-256 of an e-mail id is dictionary-reversible | Re-keying old rows (raw id is gone); keying step 7b too (breaks v3/v4 signatures) |
| 2026-10-17 | A secret create under grant-enforcement=enforce requires allowedHosts; ["*"] is the explicit opt-out, [] is refused | allowedAgents limits which agent references a secret, not where it is sent; every editor can choose the destination (security review 2026-10-17, H1) | A [SECURITY] warning only, which keeps scripts working but leaves the default open; requiring hosts in every mode, which overrides the warn/off opt-out |
| 2026-10-15 | LLM input/output guardrails use an EDDI-native `ContentGuardrail` SPI, not `dev.langchain4j.guardrail` | WS4/R0 implementation against langchain4j 1.22.0 | lc4j `InputGuardrail`/`OutputGuardrail` + `EddiChatExecutor` (mandatory executor = unbudgeted model-call path; sealed results; mandatory builder fields) |
| 2026-10-15 | Tenant cost is metered per settled step from the completion callback, with an `audit:metered_cost` marker; LLM tasks get a soft pre-call gate (fallback answer) | `recordCost` had no caller; the tool-call gate alone never stopped a tool-less agent | Metering per LLM task (double counts — `audit:cost` is already a per-step total); metering in `GroupCostLedger` (member turns already settle through `say`); failing the turn on refusal (the tool gate is soft; a deterministic reply is friendlier) |
| 2026-10-15 | A listing above the page ceiling is refused with 400, not clamped | A clamp returns a short page a client mistakes for the whole; a refusal tells it to page. The ceiling (1000) is the Manager's largest page — it resolves agent names and dashboard counts from one page of 1000, and 200 broke both | Silent clamping, as the conversation listing did at 100; a 200 ceiling with the Manager paging |
| 2026-10-15 | An agent with any `scope: secret` instruction never has its turn input dead-lettered | The capture runs before the pipeline, so the scrub cannot help, and a client cannot be relied on to flag every secret turn; losing replayability for those agents' failed turns is the lesser cost | Per-turn detection of secret-shaped input (the redaction filter still runs on everything captured) |
| 2026-10-15 | An approver-only caller may cancel only an `AWAITING_HUMAN` conversation | The role exists to decide pauses; a cancel of an executing turn is the owner's or an admin's to make | A separate cancel permission |
| 2026-10-15 | The Chat UI's `?token=` is an operator opt-in, `eddi.chat.url-token.enabled`, default off; served to the widget as a script global before the bundle | Security review M6: a token in the URL is login CSRF and is logged by the first request | Keeping it on and documenting the risk; a fetched JSON config, which would race the `?token=` read |
| 2026-10-15 | All three CSP filters use negative Vert.x orders so they run before the `/q` sub-router mount | Security review L11: at order 20 the Swagger filter never ran, and Quarkus passes the order to Vert.x unchanged, ascending | Moving Swagger UI off the sub-router, or a Java `Filters` observer — both heavier than three numbers |
| 2026-10-15 | `quarkus.oidc.resource-metadata.force-https-scheme` defaults to `false`; the advertised MCP resource scheme follows the request | Security review L3: a plain-http instance with auth on advertised an https identifier nothing served | Keeping `true` (wrong on every plain-http instance); deriving it from `quarkus.http.proxy.*` (no config signal separates plain http from a TLS proxy without forwarded headers) |
| 2026-10-15 | `k8s/quickstart.yaml` keeps all three `*_ALLOW_UNAUTHENTICATED` flags `true` behind a loud banner | Review L4 asked for the MCP/secret-store opt-outs to be `false` like Helm | Setting them `false` with OIDC off: `HighValueSurfaceGuard` refuses to boot, so the quickstart would crash-loop; Helm's `false` is paired with a render-time refusal, which a static manifest cannot express |
| 2026-10-15 | `docker-compose.yml` enforces "no non-loopback bind while unauthenticated" with a one-shot `bind-guard` service on the `mongo` image | Review L4; the contract was only a comment | A busybox/alpine init image (extra pull, breaks offline); an entrypoint wrapper on `eddi` (intrusive); installer-only check (does not cover `docker compose up` by hand) |
| 2026-10-15 | `mongodb.existingSecret` requires `eddi.existingSecret` | The chart builds `mongodb.connectionString` from `mongodb.rootPassword` | Reading the password back out of the operator's Secret (`lookup` is empty under `helm template` and dry runs, so the render would differ from the install) |
| 2026-10-15 | Tool cost ceilings enforced by default (`eddi.tools.budget.enforce-by-default=true`); `enforceBudget: false` is the named opt-out | Security review M8: every stored ceiling was advisory because the opt-in key was undeclared, and the $0.00 built-in pricing that justified the opt-in was fixed releases ago | Keep the opt-in and only document it |
| 2026-10-15 | Remote MCP/A2A tool parameter schemas are governed (text redaction, per-tool and per-server byte caps; descriptions stripped before a tool is dropped) | Security review M7: schemas are prompt content authored by the remote party, and a partial schema would make the model call the tool wrongly | Govern descriptions only; truncate the serialised schema mid-document |
| 2026-10-15 | `eddi.self.base-url` is the one exemption from `eddi.security.ssrf-protection.enabled` for configured endpoints (LLM `baseUrl`, embedding endpoints, spec locations) | It is deployment configuration set by the operator, not agent configuration, and an agent that talks to EDDI's own `/v1` API is a supported arrangement; without it, turning the switch on would break that on every deployment, since the self address is loopback or private by construction. Bare database hosts get no exemption: EDDI's listener is never a database. |
| 2026-10-15 | Templated httpCall hosts are refused while eddi.security.ssrf-protection.enabled=false; allowed-hosts list added | A conversation-controlled host reached loopback and private APIs | Defaulting protection to true (breaks the localhost reference agent and every configured internal API) |
| 2026-10-15 | Only eddi.caller-identity.author-roles (default eddi-admin) may store a config containing ${caller:token} | An editor-authored call spent the chatting admin's token on EDDI's admin API | Disabling self-release by default (breaks the Platform Operator); HITL-gating every caller-token call (a prompt per tool call) |
| 2026-10-13 | Model-version rules live in a data catalog consulted when a model is built, rejected parameters are dropped with a WARN (switchable) | Exposing 30 more parameters per provider without knowing which model accepts them would make agents easier to break; dropping a certain-4xx parameter keeps the agent answering | Fail the build of the model; only document the rules |
| 2026-10-13 | No catalog rule without an official source; unknown models fail open | A wrong rule silently removes a working setting, a missing rule only costs a visible vendor error | Infer rules from model-name prefixes |
| 2026-10-12 | A2A streaming uses the agent-qualified `sayStreaming`, like A2A's non-streaming `say()` | #1008 fixed the #949/#953 compile break by routing A2A streaming through the conversation-id overload and its ownership check | Keeping #1008's routing (A2A streaming and non-streaming would apply different access rules) |
| 2026-10-12 | In-memory vector stores live in their own never-evicting registry keyed by knowledge base; an ingestion run re-embeds everything when the store's epoch differs from the one recorded with the source's state | In-memory KBs emptied after 30 idle minutes or any secret rotation, and unchanged content hashes stopped scheduled runs from refilling them | Keeping them in the client cache with a longer TTL; a per-document epoch column (wider schema change for the same effect); evicting the largest KB when a memory cap is hit |
| 2026-10-07 | Bind `POST /agentstore/agents/{id}/cascade` for the operator while `PUT /agentstore/agents/{id}` stays excluded | Multi-step edits cost one approval per step (five for a knowledge-base binding); dependent steps cannot be pre-approved because approval binds to the resolved request | Plan-level / template approval (explicitly out of scope); a looser same-id-only rule on mappings (would forbid swapping to a different config of the same type); admin-only on the REST endpoint (stricter than every write it composes, and would 403 an editor-operator that holds every other write) |
| 2026-10-07 | Operator edits LLM, apicall and mcpcall configs with RFC 6902 PATCH; gate-guard reads patch operations and refuses copy/move | The operator's llmstore PUT could never work for a config holding a ${vault:…} reference: ConfigReferenceGuard refuses the echo, and an allowed echo would have stored the plaintext key | Relaxing ConfigReferenceGuard with an "unchanged from the stored document" exemption (the guard does not know the target, and the read-back is conversation data); a server-side toolApprovals restriction (cannot distinguish the operator from an admin) |
| 2026-10-07 | Operations whose required input the generator cannot send are skipped and reported, not generated half-working | An approved `updateResourceUri` call could only ever fail, and the model could not say why | Generating them and warning (the tool still exists for the model to call); failing the whole setup (one unusable endpoint would block every other tool) |
| 2026-10-07 | A gated call whose request the credential-reference guard refuses is refused before the pause, not approved unpinned | An admin approved a setupAgent call that was refused the instant they approved it | Adding an "allowed if unchanged from the stored document" exemption to the guard; weakening the guard; an approval-card warning flag (dead on every call the operator can make) |
| 2026-10-06 | A deploy refused by the vault-grant gate returns 200 + `failure`, not 409 | Handoff preferred 409 so callers notice a grant refusal | 409: every other failed waited deploy is a 200 with the outcome in the body; one outcome would get two shapes across 4 in-process callers, the Manager and quarkus-eddi |
| 2026-10-06 | One-agent grant append is a compare-and-set loop over the existing guarded write, not a new persistence method | Two admins granting two new agents at once lost one grant with the replacing PUT | A new atomic `$addToSet` per store (Mongo + Postgres twins) for the same guarantee |
| 2026-10-04 | Cluster console: read-only insights for eddi-viewer, content and actions for eddi-admin | Ids, counts and timings help a read-only on-call role; a dead letter's input is what a user typed, and every action changes the live cluster |
| 2026-10-04 | No purge-all, no stream/bucket delete, no lease hand-over in the console | Each could lose data or bypass fencing; per-item discard and the scripted DELETE endpoint remain |
| 2026-10-04 | Activity timeline in its own JetStream stream rather than node-local rings | Behind a round-robin load balancer a node-local timeline changes on every refresh |
| 2026-10-04 | Operator gets cluster reads but no recovery action | Actions act on the live cluster, not on a reviewable document; not in operator-write-scope-plan's write set |
| 2026-10-13 | The Kubernetes copy of the Full Metrics Reference ships as two linked dashboards | Keeps plain client-side `kubectl apply -k` working; the source dashboard is unchanged | Requiring `kubectl apply --server-side` (changes every operator's apply command); raising the test's budget (removes the headroom that caught this) |
| 2026-10-06 | Agent detail edits a draft saved by an explicit Save, with Save & leave on the app's one unsaved-changes prompt | Every inline edit created an agent version and could not be undone | Keeping auto-save with an undo stack; a second navigation guard beside the router's single blocker |
| 2026-10-06 | One `LlmRecoveryLog` INFO line per recovery action, emitted by the call site that performs it | Six ad-hoc log lines had no shared shape and no conversation id; "one recovery, one line" needs a single place that formats it | Keeping each component's own log message and documenting them (not greppable as one family, no ids) |
| 2026-10-06 | A HITL resume applies onError, responseValidation and the final-answer re-ask, but not the breaker, cascade, truncation/context re-asks or a deadline | The resume pins the paused model and must not replay tools; the breaker and cascade decisions were made before the pause | Re-running the cascade on resume (would re-enter the tool loop and replay approved tools) |
| 2026-10-03 | HTTP latency panels chart mean and peak; http.server.requests publishes no histogram | The P50/P95/P99 panels queried _bucket series nobody publishes | A percentile histogram on every uri-tagged series (cardinality x ~70), and SLO buckets (still x ~10) |
| 2026-10-03 | eddi_vault_errors_count_total no longer counts "no such key" | Agent signing probes a missing key every turn, so the counter could not be alerted on | Tagging the counter by kind (a second tag shape on an existing meter is dropped by the registry) |
| 2026-10-06 | R8 circuit breaker is a new per-model class with half-open single-probe, not a generalisation of the MCP/A2A breakers | Those are private consecutive-failure counters with no half-open state or per-class window; an LLM model needs N-of-M by class and one concurrent probe | Rewriting both existing breakers onto a shared utility in the same change (behaviour-visible for MCP/A2A) |
| 2026-10-06 | R5 recovery order: repair, same-model re-ask, then escalate | A format slip is usually fixed by the model that made it; escalating first pays a pricier model per hiccup | Escalating to the next cascade step first (pays a different, pricier model for every one-off format slip) |
| 2026-10-04 | Manager imports send `X-EDDI-Space` | 6.6 scopes merge-import matching to the importing space; the raw-fetch import calls never sent the selected space | Leaving placement to the default space (merge would silently target a different copy than the one on screen) |
| 2026-10-04 | Operator allow-list not extended for `/auditstore/admin-actions` in the UI PR | `findMissingEndpoints` refuses activation when the spec lacks an allow-listed endpoint | Duplicating #952's allow-list line (would break activation on main until #952 merged) |
| 2026-10-04 | Manager uses a data router with one catch-all route wrapping `<App />` | `useBlocker` needs a data router; converting every route would churn the table and every `MemoryRouter` test | Route objects per page |
| 2026-10-03 | A2A pinned to 1.0 (spec v1.0.1), with 0.3 and pre-0.2 tasks/send still served on the same endpoint; dialect chosen by method name | The pre-0.2 dialect was unreadable to every current SDK, and 1.0 renamed every method and enum | Serving only 1.0 (breaks 0.3 peers and every older EDDI client); a separate endpoint per version (a card can name one URL per interface, and the method names never collide) |
| 2026-10-03 | Server-issued A2A task ids embed the conversation id; turns carry the task id in the input context | tasks/get had to survive a restart without redesigning the task store, which the NATS cluster work owns | Storing tasks in a new collection (a parallel store P11 would have to replace); trusting the node-local cache (loses every task on restart) |
| 2026-10-03 | /chat img-src appends eddi.chat.img-sources, default https: | Designer image output items on other hosts were blocked by the widget's own CSP | Default empty (feature broken out of the box); Manager-wide img-src widening |
| 2026-10-03 | Chat token hand-off: ready/token-request/token protocol with a 401 retry hook; allow-list stays ?tokenOrigin= (exact origins) under the operator's frame-ancestors | The widget's first request beat the host's token, was refused and never retried | A backend-served origin list (new endpoint for a value frame-ancestors already enforces); delaying start on a fixed timer |
| 2026-10-03 | GDPR erasure redacts audit content and re-signs only rows that verified; rows and sequences are kept | Erasure left verbatim PII in audit input/output/llmDetail/toolCalls and reported complete:true | A v6 canonical form over content digests (redactable without the key); deleting rows (breaks the chain); pseudonymising only (the old behaviour, kept as eddi.audit.erasure-mode=pseudonymize) |
| 2026-10-03 | Administrative REST actions are audited by excluding the data plane, not by listing admin paths | "Every API call is audited" was false; no actor was on record for config, deploy, vault, backup or GDPR actions | An allow-list of admin paths, which silently misses the next endpoint; auditing reads (volume, and the proxy access log already has them) |
| 2026-10-03 | Dream deletes/overwrites are compare-and-set on updatedAt; on a miss the user's newer write wins | Dream deleted or overwrote facts written during its LLM call | A version counter field (schema change on both backends for the same guarantee) |
| 2026-10-03 | A group member may hold one seat; repeats are refused at save time and dropped on read for stored configs | Duplicate members shared one member conversation (review top issue #12) | One conversation per seat (needs a seat id through every agentId-keyed group structure) |
| 2026-10-03 | Generic sealed data is AAD-bound to a caller context with an unbound-read fallback; a DEK rotation re-seals bound | `seal()` bound nothing, so a grant token copied between rows opened | Forced re-encryption migration; binding without fallback (would strand every existing grant) |
| 2026-10-03 | /v1 requests without a chat id are keyed by a hash of their first system + first user message (`chat-key-fallback=history`), with `shared` as the opt-back | One `:default` conversation per user/agent shared context across unrelated chats and grew without bound | Always-new conversation per request (breaks history-less clients and memory); inferring restarts from message counts; honouring a caller-supplied EDDI conversation id (needs an ownership check, deferred) |
| 2026-10-02 | EDDI's Deployment upgrades with `Recreate`; Helm `eddi.updateStrategy` can opt back into `RollingUpdate` | `maxSurge: 1` ran two EDDI JVMs against one database during every rollout, and the engine's turn lock, nonces and caches are node-local | Keep `RollingUpdate` and only document the overlap (rejected: the default would stay unsafe) |
| 2026-10-02 | Installers fetch the compose files for `latest` from the newest release tag (GitHub releases API), falling back to `main` with a warning | The `latest` image is the newest release while `main` can be ahead of it | Pin the image to the resolved tag as well (rejected: changes what `eddi update` pulls) |
| 2026-10-02 | The metrics job keeps aggregate counts only; the stargazer roster is no longer collected | A per-person record kept forever, for a question the star count answers | Keep the roster and document a retention rule (rejected: data minimisation) |
| 2026-10-02 | The two "first agent" tutorials are folded into one page (creating-your-first-agent/README.md) and the sub-pages are deleted | Both repeated the same steps with different mistakes; one tested page is cheaper to keep correct than two. The anchors other pages link to are directory links and still resolve | Correcting both pages in place (two copies of the same steps to keep in sync) |
| 2026-10-02 | Doc examples keep `labsai/eddi:6.4.0` / `EDDI_VERSION=6.4.0` although 6.5.0 is tagged | ReleaseVersionSourceTest ties every pointer to helm/eddi/Chart.yaml's appVersion, still 6.4.0 until the post-release PR lands; bump-version.py moves them all together. Superseded on 2026-10-12: the post-release PR moved the chart to 6.5.0, so the examples were bumped with it when main was merged | Writing 6.5.0 now (fails the build until the chart moves) |
| 2026-10-02 | Conversations with no owner anywhere are admin-only; the snapshot owner is the fallback | F1: a stripped descriptor opened a conversation to every token | Keeping "unowned = open" for legacy data — the pre-v5.1.6 legacy case records its owner in the snapshot, so the open rule protected nothing real |
| 2026-10-02 | The generic descriptor/sharing API answers for configuration types derived from the IRestVersionInfo stores | F1 | A deny-list of non-config types (breaks silently on the next new type); moving conversation descriptors to their own collection (a data migration on both backends for the same protection) |
| 2026-10-02 | Triggers need EDIT on their targets for create, update and delete | F2 | USE (any chat recipient could re-point); OWN (excludes team editors who legitimately maintain the routing) |
| 2026-10-02 | Metrics default to a role (eddi-admin, eddi-metrics) rather than any token | F7 | Keeping `authenticated` (a role-less token read the exposition); shipping the eddi-metrics role in the realm files (needs three realm copies and a chart bump for an opt-in scrape account) |
| 2026-10-02 | Parameter-conversion 404s become 400 with a JSON body; other 404s unchanged | JAX-RS §3.2 maps a bad query/path parameter to 404, indistinguishable from a missing resource | A global ParamConverter (does not cover built-in types) |
| 2026-10-02 | A timed-out turn stays in inFlightConversations until its body returns | the registry was the only handle /cancel and GDPR stop had on it | Interrupt-only abandonment (swallowable) |
| 2026-10-02 | HITL timeouts whose decision cannot be applied yet are re-armed at a fixed interval and never dead-lettered | A COMPLETED-and-disabled one-shot silently turned a finite policy into wait-forever | Retrying on the normal backoff ladder (dead-letters after ~21 min) |
| 2026-10-02 | Schedule fires re-check USE against a creator access snapshot (teams + admin flag) taken at creation | principalMayUse(id, principal) refuses team shares; server has no team membership without a token | Re-checking the bare principal; skipping the check |
| 2026-10-02 | Scheduler lease is raised at startup to outlast fire-timeout + grace instead of failing startup | A misconfigured scheduler should not take the platform down | Failing startup; heartbeat-renewed leases (more store surface) |
| 2026-10-02 | Conversation-bound tools are excluded from the tool cache entirely, not narrowed to the conversation scope | Their state also changes within a conversation (new uploads, peer artifacts) and several have side effects; the classification is derived from the tool class (non-CDI-bean = per-conversation) | Forcing the conversation scope (still stale within a conversation); extending the hand-kept name list |
| 2026-10-02 | EDDI's RetryConfiguration is the single LLM retry layer; provider SDK retries are set to 0 | 3 × 3 = 9 upstream calls per failure; EDDI's layer is configurable, budgeted and skips non-retryable 4xx | Disabling EDDI's layer and keeping the SDK retries (not configurable per task, no shared backoff budget) |
| 2026-10-02 | Manager deletes default to soft; hard delete is an explicit unticked option in every dialog | Review #7: "Delete Group Only" sent permanent=true and also deleted the workspace | Keeping permanent defaults with stronger wording; removing hard delete from the UI entirely |
| 2026-10-02 | Data router with one catch-all route around the existing <Routes> to get useBlocker | No in-app unsaved-changes guard was possible under <BrowserRouter> | Rewriting the route table as route objects; intercepting link clicks by hand |
| 2026-10-02 | Role-aware UI fails open when the token carries no EDDI role | A deployment may map roles from another claim; hiding admin screens from a real admin is worse than noise | Hiding everything not positively allowed |
| 2026-10-02 | MCP role tiers mirror REST `@RolesAllowed` per tool; `eddi-viewer` is kept as an MCP-only read-and-converse role; audit trail and conversation logs stay admin + viewer (REST: admin); schedules and memory writes stay admin-only over MCP | Review §2 #17: MCP setup open to editors, deploy closed to them, config reads open to viewers, and no hierarchy for reads | A real role hierarchy in `requireRole` (would also widen every admin-only check implicitly); dropping `eddi-viewer` from MCP entirely (breaks every viewer-based MCP client) |
| 2026-10-02 | MCP tool failures become `isError: true` through an interceptor that rethrows `errorJson` results as `ToolCallException` | Every tool returns a `String`, and the unit tests compare those strings | Changing every tool's return type to `ToolResponse` |
| 2026-10-02 | Store Long as valueLong and Double/BigDecimal as valueDouble (new Property fields); a Long that fits an int is stored as valueInt | PropertySetterTask/PrePostUtils dropped or blanked them | Widening valueInt/valueFloat (breaks stored documents); storing numbers as strings (loses type in templates and conditions) |
| 2026-10-02 | A postResponse fromObjectPath that resolves to nothing writes nothing | The two property engines disagreed (postResponse stored "", property.json skipped) | Keeping "" (overwrote a good value with an empty one whenever an API omitted a field) |
| 2026-10-02 | Failed preRequest/postResponse property instructions are recorded under step key propertyInstructions:errors and do not fail the turn (scope secret excepted) | The failure was a log line only | Failing the turn (the response has already been received; one bad instruction would discard the others) |
| 2026-10-02 | The parser builds next-turn quick-reply dictionaries from the previous step's quickReplies:* data, which client-context quick replies never get; postResponse-built ones are marked server-generated on their Context | N3: client context quick replies minted expressions/actions | Stripping only the expressions (a value still generates an expression); a new persisted provenance field on IData |
| 2026-10-02 | A parser step's config.uri document is merged with the inline step: step config keys win, step extensions are appended after the document's | N1: the uri was ignored | Document-only or inline-only (either breaks existing workflows that carry both) |
| 2026-10-02 | Setup-created agents no longer get expressionsAsActions=true | Not needed by their inputmatcher-* rule; amplified N3 | Keeping it (documented risk) |
| 2026-10-02 | Unknown, foreign and malformed ids are "not found" (404) on both datastores, checked before any statement is sent | PostgreSQL answered 500, MongoDB 400 on a GET and 500 on a POST, for the same request; archives and URLs from one backend carry the other's id format | 400 for a malformed id on both (MongoDB had it on some paths) — rejected: a UUID is not malformed, only foreign, and most callers cannot tell the backends apart |
| 2026-10-02 | PostgreSQL sorts `DESC NULLS LAST`, and its field indexes are rebuilt in that order (`idx_resources_fdesc_*`, replacing `idx_resources_field_*`) | MongoDB sorts a missing field last, PostgreSQL first | `NULLS LAST` on the old ascending index — rejected: the planner can no longer read a sorted page off it and sorts the whole collection per listing page |
| 2026-10-02 | On an upgraded database the `idx_resources_fdesc_*` replacements are built `CONCURRENTLY` in the background, under a per-index advisory lock, and the old index is dropped only once the new one is valid | A blocking `CREATE INDEX` at boot locks the shared `resources` table (a descriptor per conversation) against writes cluster-wide for the whole build | `CREATE INDEX CONCURRENTLY` at boot — rejected: it waits for every open transaction, so boot could stall, and two booting instances race on one name; keeping the ascending index and adding the new one beside it — rejected: two indexes per field cost every write |
| 2026-10-02 | The EDDI 5 migrations are inert when `eddi.datastore.type=postgres` | `holdsRetention()` dereferenced the lazy `MongoDatabase` proxy on PostgreSQL and held retention for ever | Injecting `Instance<MongoDatabase>` — rejected: the proxy is already lazy; the defect was calling it, and EDDI 5 never ran on PostgreSQL |
| 2026-10-02 | RAG stores are addressed by the config id; a `storeNamespace` field keeps 6.5.0 KBs on their name-derived store until renamed or switched | Cross-KB poisoning via `?kbId=` and same-name KBs sharing a store | A startup migration moving vectors (EDDI does not own external stores); filtering legacy KBs (would hide all 6.5.0 chunks) |
| 2026-09-30 | An idle limit below 1 disables idle-ending; old versions with no active conversation are still undeployed | `-1` ended every conversation, while the retention settings read `-1` as "never" | Treating `-1` literally; also stopping undeploys (a separate decision needing its own setting) |
| 2026-09-30 | A NEGOTIATION group's prompt-less Arbitration phase is repaired in the backend at run time and on save, not rejected | Only the Manager repaired it, so behaviour depended on which client saved the group | A 400 at save (the Manager saved this shape itself, so its groups would become unsaveable) |
| 2026-09-30 | Schedule `enabled` changes only through `/enable`/`/disable`; a configuration PUT keeps the stored value inside the same atomic write on both stores | CodeRabbit on labsai/EDDI#854: the Manager editor's save re-enabled a schedule another operator had disabled after the dialog read it | Optimistic concurrency on `updatedAt` with 409 (needs a client protocol change, and the other lifecycle fields already follow the keep-stored rule); simply omitting `enabled` from the PUT (the model defaults it to `true`) |
| 2026-09-29 | Merge with main: a captured conversation property is kept at turn end; a secret input shorter than 4 characters is removed as a whole token, in values only | The wizard pattern hands a captured key to a later turn; a short PIN echoed into a reply must not persist, but digits inside other numbers and field names must | Main's property scrub; no search below 4 characters; renaming map keys in token mode |
| 2026-09-29 | One wall-clock deadline, max(3 × connect timeout, request timeout), covers every SafeHttpClient send path including buffered body reads; redirect bodies go to a 64 KiB discarding subscriber | H16/R8 after #862: the per-request timeout stops at the headers, and 3xx bodies reached the caller's handler (an unclosed stream per hop on the bounded paths) | Leaving buffered paths to per-caller bounds; discarding redirect bodies without a cap (downloads a huge courtesy page in full) |
| 2026-09-29 | Reconciled fix/outbound-http-hardening with #862 by keeping main's implementation wherever both fixed the same finding | Two parallel fixes; main's shipped first and is what other code builds on | Keeping the PR's handler-level BoundedBodyHandlers alongside main's sendBounded (two mechanisms for one job) |
| 2026-09-29 | Block the NAT64 local-use prefix 64:ff9b:1::/48 whole instead of unwrapping its trailing 32 bits | RFC 8215 allows any RFC 6052 prefix length inside it, so the IPv4 address need not be at the end | Unwrapping per prefix length (the length is a per-network choice EDDI cannot see) |
| 2026-09-29 | PR #831 keeps #859's `ClientContextGuard` and #860's converse ownership check, drops its own `ReservedContextKeys` and started-conversation set, and applies the delegation USE check to continuations | Two implementations of one boundary drift apart; the USE check on continuations closes the one case the started set covered that ownership does not | Keeping the started set alongside the ownership check (two continuation rules for one tool); stripping in `ConversationService` as well (a second boundary for the same keys) |
| 2026-09-29 | Reconciled with main: main's per-write auto-vault slots (AutoVaultedSecrets) replace this branch's per-conversation key; only the deltas main lacked are kept | Both sides fixed C2; one implementation, main's | Keeping both; keeping the per-conversation key and pre-allocated conversation id |
| 2026-09-29 | Where this branch and #861 fixed the same Slack finding, keep #861's implementation (approval records + card ids, owner-bound signatures, mandatory namespaced ids) | Two parallel bindings of one decision are harder to reason about than one | Keeping both bindings; keeping this branch's opt-in user-id namespacing |
| 2026-09-29 | Every Slack route the event handler takes must belong to an app holding the secret that verified the event | Thread locks and group follow-ups are found by a sender-chosen timestamp, outside the webhook's channel-owner check | Checking the channel only (a DM is owned by nobody) |
| 2026-09-29 | KEK rotation keeps main's rollback and adds re-runnability only for what a rollback cannot undo | Both branches fixed "a half-way rotation strands DEKs"; one mechanism, main's, with the pending salt and either-KEK verify for the unrecoverable cases | Forward-only re-run (this branch's original); rollback without the pending salt (loses DEKs when the salt write fails) |
| 2026-09-29 | Libraries that come from Quarkus or integrate with it take the version the Quarkus platform ships, never a newer one | Quarkus validates its platform as a set; running ahead of it invites classpath and behaviour mismatches nobody tested | Taking the newest stable release of every library independently |
| 2026-09-29 | langchain4j stays at 1.20.0 (1.20.2 reverted) | The Quarkus 3.39.5 platform ships langchain4j 1.19.3 via quarkus-langchain4j-bom | 1.20.2 with langchain4j-community-oci-genai pinned at 1.20.0-beta30 |
| 2026-09-29 | Global-key ownership for the rememberFact tool is decided by an owner-conditional write in the store (ON CONFLICT … WHERE on PostgreSQL; an owner-filtered upsert against a new unique partial index on MongoDB) | Read-then-upsert let two agents both see a key as free and the later value win (CodeRabbit on #860, CWE-367) | Serialising writes per user in the tool (single-node only); a transaction around read and write (not available on standalone Mongo) |
| 2026-09-29 | OpenAI-compatible vendors are catalog entries, not Java classes | One JSON file feeds the backend registry and (through a parity test) the Manager, so a new vendor is a data change. | A Java builder class per vendor; a REST catalog endpoint. |
| 2026-09-28 | json-schema-validator 3.x, victools jsonschema-generator 5.x and bson4jackson 3.x not taken | All three require Jackson 3 (tools.jackson.*) in their public API; the ban-jackson3 enforcer rule forbids it and the Quarkus BOM ships Jackson 2 | Lifting the Jackson 3 ban for three libraries as part of a dependency bump |
| 2026-09-28 | UI majors: take every stable major whose peers allow it; keep TypeScript 5.9 in the Manager, hold jsdom at 30.0.x, and pin react-hooks to its two classic rules | typescript-eslint 8.71 caps TypeScript below 6.1; Vitest 5.0.2 cannot unwrap jsdom 30.1 Blobs; react-hooks 7 `recommended` adds 119 React Compiler findings | Forcing TS 7 with overrides / --legacy-peer-deps; jsdom 30.1 with a Blob shim in test setup; fixing 119 compiler findings inside a dependency bump |
| 2026-09-28 | A vault reference in synced content yields to the target's own value at the same place; it travels only where the target has none | Which vault entry an environment uses belongs to that environment; a promotion that repointed production at staging's entry broke every call it made | Transferring references as-is (the defect); stripping them entirely (a first promotion would lose the only hint of which entry to create) |
| 2026-09-28 | Local edits are detected by a `syncedVersion` recorded on the descriptor at every sync or import write, and a resource changed on both sides is written only when named | The descriptor already travels forward with every version through `DocumentDescriptorFilter`, so a version past the recorded one is a local edit without any new store; overwriting a hotfix by default is the one outcome a promotion must not produce | Content hashes in a separate collection (a second store to keep in step); warning but writing anyway (the hotfix is still lost) |
| 2026-09-28 | A resource for a step the source added is created only when the workflow adoption that places it is decided to succeed, before anything is written | The previous refusal made adding a step impossible to promote; creating unconditionally left an orphan whenever the adoption was refused afterwards | Refusing (unusable); creating then deleting on refusal (a second failure path mid-sync) |
| 2026-09-28 | Without `targetAgentId`, a sync targets the agent promoted from that source (by `originId`), with `createNew` to force a copy and 409 when ambiguous | The guide already said the originId "is what lets the next sync match it", but nothing matched on it, so every call without a target made another full copy | Keeping "no target means create" (not idempotent for scripts and CI); picking the most recent of several (a guess) |
| 2026-09-28 | A parser document's dictionaries are matched as resources keyed under the document, and the parser repointed by source id — never paired by position | Pairing a document's references by position with the target's copy made a dictionary *replaced* by another at the same place compare equal, so the preview said SKIP and nothing was synced; keying them lets the ordinary machinery diff, create and update them | Positional pairing against the target's copy (the first version of this change — silent on a replacement); pairing by the target descriptor's originId (only covers resources an import created, and still leaves new dictionaries uncreated) |
| 2026-09-28 | An archive without a parser document imports its parser step unchanged, instead of pruning the step as a `create` does for any other missing config | Every archive written before parser documents travelled lacks the file; pruning would leave the agent unable to parse input, and a `merge` would fail with no local copy to answer the reference. The pipeline never loads the document, so the old behaviour was harmless | Treating parser like every other type (breaks every existing archive); failing with a message (the operator cannot act on it — the product wrote the archive) |
| 2026-09-28 | A sync recreates a resource the target's step names but the target no longer has — only when the store confirms it is gone | Every agent promoted before parser documents travelled names a parser its instance never had; refusing that CREATE would fail every later sync of it | Reporting it as a failure (permanent 207 for every previously promoted agent); recreating on any read failure (a timeout would orphan a live resource) |
| 2026-09-28 | Shares resolve names and emails through a user directory; the principal stays the storage key | Sharing with an email stored `user:<email>`, which matched no principal and reached nobody while answering 200 | Keying grants on email (mutable; unverified emails let anyone collect shares), or requiring raw principals from users |
| 2026-09-28 | Space secrets and variables use hashed (64-bit) per-space tenants, authorized by membership and checked again at deploy; a reference assembled from variable pieces is refused at resolution | Editors could not store their own keys, and any editor could overwrite a global variable other teams relied on | Per-secret ACLs; letting EDIT on an agent imply use of the owner's team secrets |
| 2026-09-28 | Conversation review is opt-in per agent version and read-only, with a notice shown before the first message | Owners of shared agents could not see how the agent was used, and silent access to other people's chats is not acceptable | Owners always reading conversations; an opt-in that applies retroactively to versions that did not announce it |
| 2026-09-27 | A client-flagged secret input is searched for from 4 characters, not 8 and not 1 | A PIN or short password is what a password field carries; below 4 the replace-everywhere search shreds the turn's reply and map keys for a guessable value. Same floor in the turn-end scrub, read-time masking and audit buffer | The 8-character context-value floor; every nonempty form |
| 2026-09-26 | Chat UI: KaTeX and highlight.js are loaded on first use, not bundled | Wiring the advertised features statically tripled the widget bundle (645 → 1086 kB) | Static imports; dropping the features from the README |
| 2026-09-26 | A client-flagged secret input is scrubbed when its turn ends, and the turn output's `input` is the masked display copy on the wire | The parser overwrote the placeholder with the normalized plaintext, so exposing `input` alone would have leaked a copy | Exposing `input` without the engine fix; scrubbing properties the designer captured the input into (breaks the wizard pattern) |
| 2026-09-26 | Older stored secret turns are masked on read, not migrated | Every secret turn carries `context.secretInput`; masking on read closes the exposure for history at no migration cost | A data migration; leaving history exposed |
| 2026-09-26 | Chat UI math is `$$…$$` only | Single-dollar math turned dollar amounts into formulas | remark-math defaults |
| 2026-09-26 | A queued turn whose snapshot was superseded is rebuilt over a reloaded memory (revision probe first) | H13a/E1: queued turns ran on request-time memory | Always reloading (doubles every turn's load); refreshing the memory in place (the Conversation's longTerm baseline would stay stale) |
| 2026-09-26 | Persistent schedules may roll over (opt-in, idle only, conversation properties carried) at a step limit instead of trimming | 16 MB document limit | Trimming steps (destroys history, violates "full data is never deleted by optimization"); on by default (silently resets a stateful agent's history) |
| 2026-09-26 | The in-chart and Kustomize MongoDB authenticate by default, and Helm refuses to render without a password | C5c: any pod could read and rewrite all EDDI data | NetworkPolicy only (inert without an enforcing CNI); auth opt-in (leaves the default open) |
| 2026-09-26 | The /chat framing allow-list is an operator property (default 'none'), in its own CSP filter | M-F3: the global DENY blocked the documented embed | Relax frame-ancestors for every path; XFO ALLOW-FROM (unsupported) |
| 2026-09-26 | The wizard's spec-by-URL needs eddi.csp.extra-connect-sources; connect-src stays strict by default | M-F4: the browser blocked the fetch | Allow https: in connect-src by default (opens exfiltration for any injected script) |
| 2026-09-26 | A failed merge import writes each updated resource's pre-import content back as a new version (and restores its descriptor) | M-P4: rollback only deleted created resources, leaving the target half-promoted | Deleting the imported version / restoring history in place (history is append-only everywhere else) |
| 2026-09-26 | `IResourceStorage.storeHistoryAndRemove` takes the expected version and has no default implementation | M-P5: a delete racing an update erased the committed version and lost its tombstone | Keeping an unconditional default for non-transactional backends |
| 2026-09-26 | Tool-loop retry wraps one model request, not the loop | H11a: whole-loop retry re-executed tools | Keeping whole-loop retry with a dedupe journal on the live path (more state, same result) |
| 2026-09-26 | Cascade escalation carries the executed tool exchange to the next step (default on, `carryToolResultsOnEscalation`) | H11b: each escalation re-ran side-effecting tools | Refusing to escalate after any tool ran (defeats agent-mode cascades) |
| 2026-09-26 | The carried-exchange fallback retries only a rejected FIRST request of a step, and only for a 400/422-class rejection | A failure after tools ran would re-run them; auth/model errors cannot be fixed by dropping the exchange | Retrying on any non-retryable failure (replays side effects, masks auth errors) |
| 2026-09-26 | HTTP/MCP/A2A tool results are cached only when named in `toolCacheScopes` | A cache hit skips a write | A new `cacheableToolSources` list (extra field, same effect) |
| 2026-09-26 | Manager approval decisions carry the shown pause (pauseId when reported, else a fresh re-read compared on kind/start/call ids) | A queue row up to 10 s old approved whatever the conversation was paused on, including an unreviewed tool-call batch | Sending toolDecisions only (does not stop a rule-row approval landing on a tool pause); waiting for the backend pauseId alone (leaves current main unprotected) |
| 2026-09-26 | Audit screen verdict comes from GET /auditstore/verify, green only when verified | hmac presence was shown as a verified shield | Verifying client-side (no key in the browser); keeping the count banner with softer wording |
| 2026-09-26 | Fixed-time crons (no `*` in minute or hour) fire once per local time across DST: skipped time at the transition, repeated time at its first occurrence; wildcard crons keep real-time cadence. Accepted Vixie consequences: `0 2,3` collapses into one fire on spring-forward, fixed hour ranges do not repeat the doubled hour | M-Q1: `30 2 * * *` double-fired / was skipped in DST zones | Shifting a skipped time by the gap length (ZonedDateTime.of): `30 2,3` would collapse into one fire |
| 2026-09-26 | An oversized fire-and-forget batch is refused whole, not truncated; `maxBatchSize` default 100, ceiling 1000 (both operator-configurable) | M-Q3: uncapped fan-out from upstream/LLM data | Silent truncation (reports success while dropping requests) |
| 2026-09-26 | A schedule PUT keeps `metadata`/`tenantId`/`allowSelfScheduling` only when the body omits them; naming the field (even `null` or `{}`) sets it | UI High 5: edited system schedules became chat schedules | Treating metadata as never editable over REST: an operator could not deliberately clear a marker |
| 2026-09-26 | Dismissing a dead letter is a store operation conditional on DEAD_LETTERED, 409 otherwise | Unfenced `markCompleted` reset live claims (double fire) | Fencing on fireId: a dead-lettered row holds no claim to fence on |
| 2026-09-26 | A schedule PUT is judged on the STORED row for managed schedules: RAG ingestion refused outright (409), team cadence requires EDIT on the group | Review MAJOR 1: carrying metadata over let USE+VIEW re-cron a knowledge base's crawl | Requiring KB EDIT for ingestion edits (as fireNow does): the knowledge-base save re-creates the row from its source cron, so an edit here would be silently undone anyway |
| 2026-09-26 | Batch cap default and ceiling are operator properties (`eddi.httpcalls.batch.*`); a `maxBatchSize` above the ceiling is refused at save time | Review MINOR 2: hard-coded cap was a regression with no escape hatch | Silent runtime clamping only |
| 2026-09-26 | `RestAgentStore.updateAgent` keeps the stored `dynamicOrigin`; duplicate and new-agent ZIP import drop it; create still accepts it | The marker authorizes permanent deletion, so only the engine may write it; setup writes it through the loopback REST create | Stripping on create (breaks `AgentSetupService`); a separate engine-only store field (larger change, same outcome) |
| 2026-09-26 | Reserved context keys are read with exact-key lookups (`getData`, new `getExactDataPerStep`) **and** `ClientContextGuard` also drops keys that start with a reserved name | `getLatestData`/`getAllLatestData` match by prefix, so `groupIdSuffix` and friends passed the exact-name guard and were read as the reserved key (PR #831, CWE-863) | Only the exact lookups (the next prefix read reopens it); only the prefix drop (leaves every reader relying on a distant boundary); changing `getLatestData` to exact matching (many callers rely on the prefix, e.g. `httpCalls:*`, `output:*`) |
| 2026-09-26 | `teardown_agent` and end-of-discussion cleanup require a `dynamicOrigin` marker on the target agent in addition to the created-ids list | The list alone decided and could be forged; deletion is permanent | Trusting the list once the context channel is closed (one leak away from deleting a person's agent) |
| 2026-09-26 | Short secret context values (4–7 chars) are exact-match scrubbed, top-level string entries only | S4: a PIN copied into a property survived; object leaves would blank unrelated data | Substring replacement; matching object leaves |
| 2026-09-26 | Success bodies: text redaction for secrets ≥ 8 chars, JSON scrubbed as a tree | Echo redaction must not corrupt the data a call fetches | Redacting every length by substring; redacting error bodies only |
| 2026-09-26 | HITL decisions may carry a pauseId (epoch-ms of the pause start), checked before and after the resume CAS | A stale REST/MCP decision could approve a later, different pause | A stored random pause UUID (needs a schema field and migration for RULE pauses) |
| 2026-09-26 | A non-owner approver's identity is bound only around the tool calls they approved; the rest of the resumed turn has no caller | ${caller:token} calls after an approval ran with the approver's token, including un-previewed calls after a RULE pause | Keeping the whole-turn binding (privilege escalation); dropping it entirely (breaks approved caller-bound calls) |
| 2026-09-26 | Audit HMAC key pinned in the vault (sealed under a reserved system tenant's DEK) and keyed by id in v5 signatures | KEK rotation silently changed the audit key and invalidated the whole ledger | Requiring operators to configure a separate key (kept as an option, eddi.audit.hmac-key); a KEK-wrapped meta value (would need its own re-wrap step in rotateKek) |
| 2026-09-26 | Lost master key is resolved by an explicit admin call (adopt-master-key), not automatically | A stale replica and a lost key look identical from a node; guessing either way loses data | Auto-adopting on mismatch (lets a stale replica strand tenants); manual DB edit (undocumented) |
| 2026-09-26 | UNKNOWN_KEY only for key ids recorded as sealed system values | The key id in a row is attacker-writable text | Rewording docs only |
| 2026-09-26 | Unreadable vault salt fails startup | Legacy-salt fallback derives the wrong KEK on random-salt deployments | Falling back and warning; marking the vault unavailable |
| 2026-09-26 | Schedules that run as a real user are that user's for every operation, workspaces on or off | H2a: editors could list/disable/delete other users' dream schedules | Workspace-only scoping (left the per-user leak open with workspaces off) |
| 2026-09-21 | Default the per-tool timeout to 120000ms and leave it ON, rather than shipping it disabled | A bound nobody enables does not fix a hang; and the field is null on every agent already stored, so the engine-side fallback is what actually decides the default | Shipping `-1` by default (inert for everyone), and a 30s default (would cut into legitimate nested-agent and delegation calls, and duplicate the MCP/A2A transport timeouts) |
| 2026-09-21 | Return an error string on expiry instead of throwing | `RetryConfiguration.isRetryableError` treats `TimeoutException` as retryable, so a thrown timeout becomes a retry loop over a hang; and the rate-limit branch already established "tell the model, keep the turn" | Throwing a `LifecycleException` (fails the turn), throwing `TimeoutException` (retried), a custom checked exception (every caller would have to translate it back into a string anyway) |
| 2026-09-21 | Run the bounded call on a shared virtual-thread executor, inline when unbounded | A timeout needs a second thread by construction; virtual threads make the abandoned worker of a hung tool nearly free, and running inline when disabled keeps the untouched path byte-identical | A fixed platform-thread pool (an abandoned worker costs a whole thread and stack, and a pool of N hung tools deadlocks the N+1st), a pool per call (leak by construction) |
| 2026-09-21 | Add `int timeoutMs` as a tenth parameter rather than a field on `ToolInvocation` | The brief asked for the `rateLimit` shape end to end, and `rateLimit` is a parameter; it also breaks stale Mockito stubs at COMPILE time instead of silently mismatching at runtime | Putting it on the `ToolInvocation` record (would have kept every `any(ToolInvocation.class)` stub compiling while no longer describing the call), a separate 10-arg overload (same silent-mismatch problem) |
| 2026-09-21 | Count abandoned workers on a gauge, but put no admission bound on the timeout executor | A leak an operator cannot see is the dangerous half of the trade-off, and counting is unambiguously right; refusing healthy tool calls because unrelated ones are stuck trades a per-tool failure for a conversation-wide one, which is a product call rather than a review fix | A bounded-queue or semaphore executor with a model-visible "too many tools running" error (Copilot's suggestion, left open for a human), a per-tool `Semaphore` (same blast radius, more bookkeeping), doing nothing and leaving the accumulation undetectable |
| 2026-09-21 | Leave the `String` overload unbounded | Its only caller is `McpCallsTask`, which has no `LlmConfiguration.Task` and already bounds its tools with `McpCallsConfiguration.timeoutMs` | Giving it the same default (would apply an LLM-task setting to a workflow step that cannot configure it) |
| 2026-09-21 | Walk `docs/` rather than list it in `ComposeStackTest`, and assert the sweep's shape | A guard that reads only the top level would have failed over a nested page that *does* document a stack; a false failure is how a guard gets deleted rather than fixed | Listing the top level only (the reviewed version), naming the nested directories explicitly (a third place to forget when one is added) |
| 2026-09-29 | Each Slack approval card carries a random card id in its buttons, recorded with the card; a click must present the id recorded for the current pause | Subject + pause matching let an old card of the same subject approve a newer pause | Storing the posted message `ts` on the record after `chat.postMessage` (a post-then-update window, and a failed update leaves a live card unusable) |
| 2026-09-28 | The Slack workspace comes only from a declared `platformConfig.teamId`, for owned channels too; without one the identity is team-less | A signing secret authenticates the integration, not the workspace in the payload | Trusting an owned channel's payload team_id; scoping team-less ids by integration name (names are mutable) |
| 2026-09-28 | Adoption of raw-id `/v1` chat mappings is opt-in (`adopt-legacy-header-mappings`, default false) | A raw mapping may have been written for an OIDC principal under `http-policy=authenticated`; its origin is unrecorded | Adopting by default (reopens the cross-namespace reach); heuristics on the id's shape |
| 2026-09-28 | `/v1` refuses an OIDC principal carrying the reserved `openwebui:` prefix | Such a principal equals a header-derived identity | Namespacing OIDC principals too |
| 2026-09-27 | Removed the standalone bare-id memory MOVE for both OpenAI-compat and Slack; kept adopt/rekey only | The move read the shared bare-id namespace on an attacker-chosen id and could relocate+erase an OIDC user's memories (review Finding A/B) | A mapping-gated move (still leaks on a bare-id string collision with an OIDC principal) |
| 2026-09-27 | HITL owner binding refuses an ambiguous integration name and enforces global per-type name uniqueness | Integration display names were not unique, so a copied name could bind a decision to the attacker's config (review Finding C) | Keying everything on a unique resource id (larger blast radius on a security branch) |
| 2026-09-27 | Slack webhook rejects an event whose claimed workspace disagrees with the signing integration's declared teamId; a teamId-less unowned (DM) event is bound team-less, not to the payload team | Payload team_id was attacker-controllable in a validly-signed DM event → cross-tenant memory read (review residual #1) | Trusting the payload team when the integration declares none |
| 2026-09-26 | Conversation guard resolves the owner from the archived descriptor; no descriptor at all = 404 for non-admins | A soft delete left the snapshot readable and drivable by every authenticated caller | Admin-only `/active` + `/end` (editors undeploy and already end them all); deleting audit entries with the conversation (append-only ledger) |
| 2026-09-26 | GDPR erasure stops in-flight work through a CDI SPI (`UserErasureParticipant`) called before the cascade, not a direct call | The services that own turns and discussions depend on GdprComplianceService, so the dependency cannot point the other way; same pattern as SealedDataRotationParticipant | Injecting ConversationService/GroupConversationService directly (dependency cycle); a CDI event (no failure reporting into failedSteps) |
| 2026-09-26 | `_gdpr_` memory keys are refused at the store (`upsert`/`mergeProperties`) and written only via `upsertReserved` | A caller-side check misses the next write path; a category check is forgeable because REST/MCP callers choose the category | Validating in each caller only; honouring the key when category == gdpr |
| 2026-09-26 | Manager treats a deployment at an older version as "live" (flagged `deployedVersion`) instead of "Not deployed" | Every save bumps the version and the per-version status endpoint answers only for the exact version | A backend "any version" query parameter (needs a contract change); asking the listing for every card (N requests) |
| 2026-09-26 | Workforce Stop cancels on the server before closing the stream; a 409 closes it without claiming CANCELLED | Stop only aborted the SSE connection, leaving the run spending | Aborting first and cancelling after (a failed cancel would then leave a running discussion with nothing following it) |
| 2026-09-26 | A stream that ends without a terminal event is "interrupted", and the UI follows the stored conversation | A dropped connection froze the board or showed FAILED for a still-running discussion | Closing placeholders as SKIPPED on interruption (those members may still be answering) |
| 2026-09-26 | returnDetailed hardened by redaction + denylist rather than an owner gate | The detailed conversion is a central chokepoint reached by several endpoints and the owner is not cheaply available at that layer; raw full-fidelity data stays on the owner/admin-gated raw endpoint | Restricting returnDetailed to admin/editor/owner at every call site |
| 2026-09-26 | UserMemoryTool default allowedVisibilities = [self] | Secure-by-default: a prompt-injected model must not broadcast group/global memories unless an operator opts in; the configured defaultVisibility is always unioned in so it is never self-blocking | Leaving visibility fully model-chosen |
| 2026-09-26 | Slack HITL decisions require a persisted record of the card the owning integration posted, matched to the subject's current pause | Signature and approver list bound the integration, not the subject | In-memory marker (lost on restart); trusting the button value |
| 2026-09-26 | Slack users are `slack:<team_id>:<user_id>` in EDDI; raw-id mappings are re-keyed only | Raw Slack ids shared the OIDC principal namespace | Keeping raw ids for existing users (leaves the collision open); a one-shot bulk migration |
| 2026-09-26 | OpenAI-compat header users are `openwebui:<id>` in EDDI; raw-id mappings are re-keyed only (opt-in since 2026-09-28) | `X-OpenWebUI-User-Id` shared the OIDC principal namespace, so a shared-key holder reached OIDC users | Namespacing OIDC principals too (they are already canonical); a one-shot bulk migration |
| 2026-09-26 | Mask secret input by scrubbing input:initial post-pipeline in Conversation's finally, not at store time | The plaintext must reach parser/property tasks (vaulting) during the turn but never persist; the finally runs on error/pause paths too and precedes the audit redaction | Storing a placeholder up front (would break scope=secret vaulting and normalization) |
| 2026-09-26 | Chat markdown images render as links (keep img in sanitize schema + component override) rather than dropping img from the schema | The "renders as a link" behaviour needs the node to reach the renderer; the override prevents any fetch, preserving the URL as a click-through | Dropping img from the sanitize schema (loses the URL entirely and cannot render a link) |
| 2026-09-26 | Deep links preselect the agent only; starting a conversation stays an explicit user action | Auto-starting as the admin from a URL param is a CSRF-style side effect | Keeping the auto-start behind a confirmation step |
| 2026-09-26 | Bind EDDI to 127.0.0.1 by default in compose, opt-in `EDDI_BIND` to expose | An unauthenticated default must not be network-reachable out of the box; MongoDB was already loopback-bound, EDDI was not |
| 2026-09-26 | Helm: require explicit opt-in for /mcp and /secretstore when OIDC is off | Deriving the opt-outs from oidc.enabled silently opened the two highest-value surfaces, defeating HighValueSurfaceGuard |
| 2026-09-26 | CI: sign the image digest and require the release tag to be an ancestor of main | A tag on any commit could publish and sign :latest, bypassing review; a digest signature cannot be moved by a later tag push |
| 2026-09-26 | Bound outbound response reads with a shared BoundedBodyReader (cap + deadline) rather than per-tool ad-hoc checks | One implementation across attachment/web/pdf/A2A paths, mirroring the crawler's tested readBounded; size is enforced while streaming, not after buffering | Keep per-tool post-buffer size checks (the status quo that let unbounded bodies into memory first) |
| 2026-09-26 | Drop the shared WebClientSession cookie jar for httpcalls in favour of a plain WebClient | The single app-scoped session replayed one user's Set-Cookie on another user's call to the same host; no httpcalls feature needs cookies | Scope a WebClientSession per conversation/principal (more machinery for a capability nothing uses) |
| 2026-09-26 | Strip `SafeHttpClient.SENSITIVE_HEADERS` on every cross-origin redirect hop in the shared httpcalls client (`HttpClientModule`), and additionally disable redirect-following for a request whose header carries a resolved (reference) credential | Vert.x's redirect handler strips none of these headers cross-origin, so a literal credential written in the config leaked too; the hop-level strip covers every credential source, and the per-request disable is the stronger measure for resolved secrets | Disable redirects only for reference credentials (the first cut: missed literal credentials); strip only the three RFC headers (Vert.x does not even do that) |
| 2026-09-26 | Under enforced workspaces a render gets only snippets from the agent's space, its owner's own snippets (personal-space agents only) and legacy snippets; granted/published snippets from other spaces are never auto-injected | C4c + pre-push review: snippets are injected by name, so a grant or publish is a push into other tenants' prompts (incl. the counterweight preset names) | Ranking grants/published below closer tiers (they remain the only candidates for unclaimed names such as the presets); scoping by the chatting user |
| 2026-09-26 | Caller-supplied and already-rendered output is marked verbatim per data entry, persisted in ResultSnapshot, and never templated again | C4a + review: context output was rendered as Qute; a HITL resume or a second templating pass re-rendered data | Key-prefix convention (collides with an action named "context", suffix is client-chosen); a transient flag (lost on tool-call resume) |
| 2026-09-26 | Strip engine-reserved context keys at the client entry points (REST, streaming, MCP trigger context) with an opt-in config list, rather than verifying them downstream | The engine trusted client-supplied values for group memory scope, dynamic-agent policy, created-agent tracking and delegation depth | Marking trusted entries with a flag on `Context` (does not survive the store round-trip, so reloaded steps would lose the policy); stripping inside `ConversationService` (internal group/delegation callers use the same methods) |
| 2026-09-26 | Runtime templates render with an EDDI-built Qute engine assembled from the Quarkus engine's resolvers through allow-lists | The injected engine exposes config:, inject:/cdi:, str:eval and unrestricted reflection to agent-authored templates | Keeping the Quarkus engine and scrubbing template text (unbounded syntax surface); hand-writing all extension resolvers (duplicates Quarkus's generated ones) |
| 2026-09-26 | Values read via fromObjectPath are stored verbatim, with no opt-out | They are user/upstream data; no legitimate configuration needs them evaluated, and valueString covers composing text | A config flag to restore rendering (would re-open the path per agent) |
| 2026-09-22 | `StanceSummaryConfig` carries no `enabled` flag | Stances always exist (extraction needs no config), so the flag could only mean "may this spend?" — already said by naming a provider/model. A flag could contradict them. | EDDI |
| 2026-09-22 | `cost_updated` carries cumulative cost, never a delta | The ledger records by replacement, so duplicates are idempotent; a delta frame replayed after a reconnect would double-count, and PARALLEL turns interleave. | EDDI |
| 2026-09-22 | `memberStances` rides schema v4 without a bump | It is a display projection — reproducible from the transcript, read by no resume path. The bump rule is scoped to resume-consumed fields. | EDDI |
| 2026-09-22 | Per-style difference is band ORDERING, not a renderer per style | Every discussion reduces to phases × members × entries; a style table means an unknown style still renders, and adding one is a row rather than a component. | EDDI Manager |
| 2026-09-22 | One `useDiscussionDigest` adapter for all three transcript surfaces | Two data shapes × three renderers is exactly how a DISSENT once rendered as an opinion on two of them; one adapter makes the drift structurally impossible. | EDDI Manager |
| 2026-09-22 | `split` restacks instead of being width-gated | A gated mode would discard the half the user picked and silently rewrite their stored preference. | EDDI Manager |
| 2026-09-22 | Round boundaries are recovered from QUESTION entries but validated against the stored index | Only the current round's start is persisted. Trusting the QUESTION invariant blindly split a discussion on a stray marker; validating and falling back narrows the switcher instead of slicing the view wrongly. | EDDI Manager |
| 2026-09-22 | The interaction band is a list, not a graph | A force-directed diagram of five nodes is decoration and of twenty is unreadable; a list answers "who did this member take on" and "who went unchallenged" at any size with no layout engine. | EDDI Manager |
| 2026-09-22 | DELPHI gets no interaction band | Naming who answered whom would undo the anonymity the method rests on — the reason its later rounds run ANONYMOUS. | EDDI Manager |
| 2026-09-22 | A first-time live sync fetches the source's export archive and imports it, rather than creating resources itself | `executeUpgrade` has no create path, and writing one meant a second implementation of everything `RestImportService` already does for a ZIP — schedules, connections, capability registration, rollback | Writing native creates in `UpgradeExecutor` (would quietly do less than a ZIP import and drift from it); materialising the archive locally from `IResourceSource` (duplicates the export's layout rules) |
| 2026-09-22 | The sync source policy is three independent settings, all defaulting to the strict behaviour, with an exact-origin allow-list as the preferred one | A compiled-in refusal of every private address made the feature unusable for self-hosted deployments, but relaxing it globally by default would let any caller of the endpoint probe hosts behind the deployment | Reusing `eddi.security.ssrf-protection.enabled` (it governs agent-config-driven calls, a different trust question, and defaults the other way); a single "allow internal" switch (cannot express "this one staging host") |
| 2026-09-22 | `includeFirstAgentMessage` drops a message only when it is the agent's, and the flag is deprecated | The unconditional `removeFirst()` emptied the history of any agent with no `ai.labs.output` step, and Anthropic rejected the call; the Anthropic rule the flag exists for no longer applies | REMOVING it — agent behaviour lives in stored JSON, and silently ignoring a deliberate setting would start sending a greeting the author chose to withhold, with no diagnostic |
| 2026-09-22 | A HITL rejection gets its own `REJECTED` state rather than reusing `FAILED` | The Manager rendered a recorded human decision as a red "Failed" badge | Re-labelling `FAILED` in the UI only — the backend distinction is what audit and API consumers need |
| 2026-09-22 | Pre-`REJECTED` documents keep `FAILED`; no migration | Nothing stored distinguishes a rejection from a failure, so a migration could only guess | Backfilling from the audit ledger — it is not guaranteed enabled |
| 2026-09-22 | The debate-verdict note is INFO, not a save-time rejection | For a real DEBATE group the verdict path is the intended behaviour; rejecting would break every existing debate config | A hard error, and a WARN (which would cry wolf on every correct debate) |
| 2026-09-22 | The expanded group question is height-bounded and scrolls itself | Unbounded expansion pushed its own "Show less" out of an overflow-hidden pane, so it could not be undone without a reload | Making the whole header scroll — it would move the state badge and cost pane height a transcript needs |
| 2026-09-22 | "New Discussion" is guarded by an explicit-clear ref, not a one-shot restore | Preserves today's auto-select-after-delete behaviour, which a one-shot ref would drop | The Workforce board's one-shot `restoredRef`, which is right for its "restore an ongoing discussion" semantics but not for this page's "select the newest" one |
| 2026-09-22 | The session log stream is lazy and refcounted rather than removed | The Logs page genuinely needs a live tail; what was wrong was holding it on every page | Keeping the boot connection and raising the tab budget — the six-per-origin cap is Chrome's, not ours |
| 2026-09-22 | A plain Save toasts "not yet live" with a Deploy action rather than deploying itself | Deploying on every Save would make an ordinary edit a production change; the gap was that nothing said the change was inert | Auto-deploying, and leaving it to documentation |
| 2026-09-21 | Document the three-sided KB binding instead of making the workflow step optional | Retrieval discovers knowledge bases from the workflow document, which is what makes a KB an agent-level capability rather than a per-task one; inferring a binding from `knowledgeBases[].name` alone would let any task reach any KB in the deployment. The requirement is correct — it was undocumented. |
| 2026-09-21 | Fix CWE-117 in `NatsConversationCoordinator` at the log call, and also widen `sanitizeSubject` to strip CR, LF and tab | The log call and the subject token answer different questions, so both are fixed. Widening was first rejected as moving the subject namespace; that was wrong — NATS refuses a subject containing those characters outright, so nothing was published under one, and the rejection is an unchecked `IllegalArgumentException` that escapes the publish's `IOException \| JetStreamApiException` handler | Leave CR/LF in `sanitizeSubject` and rely on the log call alone — keeps a latent unchecked-exception path for no gain |
| 2026-09-21 | Arm an ingestion schedule in its creator, not in `createSchedule` | A cron ingestion source was stored enabled with a null `nextFire`, which no `findDueSchedules` can ever match, so it never ran | Computing `nextFire` inside the stores: `MongoScheduleStore` does not cover `PostgresScheduleStore` (no shared base), and it would make a second copy of the arming policy that already lives once in `RestScheduleStore.computeRearmNextFire` |
| 2026-09-21 | Repair already-stored unarmed ingestion schedules with a repeatable startup sweep, rather than a migration script or leaving it to the next save | Rows written before the fix are dead for ever and nothing tells the operator to re-save the knowledge base | A one-off migration (needs running, and is skipped on upgrades); re-syncing every knowledge base at startup (delete-then-create races between nodes); a generic sweep over all schedules (wider blast radius than the defect) |
| 2026-09-21 | Arm a legacy row in the zone the poller will use, rather than in UTC or by widening the store's re-arm to carry one | `armIfUnarmed` writes only `nextFire`, so a legacy row's `timeZone` stays null and the poller re-arms it in the deployment default; arming the first fire in UTC regardless would make exactly one interval the wrong length | Adding a zone to the store's re-arm on both backends (CodeRabbit's suggestion — it would also normalise legacy rows to UTC, at the cost of a third field in a predicate that exists to do one thing), leaving the fixed-UTC arm and the drift with it |
| 2026-09-21 | Close the two-node repair race with a conditional `armIfUnarmed` on both stores rather than a re-read | A re-read narrows the window to one store round-trip and still reads a snapshot; the predicate is the only place two nodes meet, and ~20 lines per backend is a small price for a write that cannot skip a fire | A re-read before writing (narrows, does not close), a distributed lock for a startup sweep, leaving the inaccurate idempotency claim in place |
| 2026-09-21 | Changelog entries are per-branch fragment files, collated nightly on main | Every PR inserted at the same point in one file, so every open PR conflicted with every other over a document unrelated to its code | Keep one file and resolve by hand (the conflict returns at the next merge); collate on every push to main (a bot commit per merge, and races between them); let the merge tool own it (no merge driver makes two insertions at one point orderable) |
| 2026-09-21 | The nightly job opens a PR, and skips entirely while one is open | main requires a PR; and a second PR proposing the already-claimed fragments would conflict with the first — the very failure being fixed | Push to main directly (a hole in the PR requirement); force-push the bot branch (banned by §2 rule 4); a new branch per night (two PRs carrying the same entries) |
| 2026-09-21 | Fragments live in docs/changelog.d/, one directory below the live file | Puts all changelog material in one place, and makes a fragment exactly as deep as an archive, so the two link transforms are inverses | A repo-root newsfragments/ (avoids the SUMMARY.md carve-out, splits changelog material across two trees); a subdirectory of docs/changelog/ (collides with the archive naming rule) |
| 2026-09-21 | CI fails a PR that ADDS an entry heading or a dated register row to docs/changelog.md | AGENTS.md prose is what a session follows, but it is not a guard, and 29 PRs were open under the old rule | Detect any change to the file (blocks legitimate header edits and typo fixes in past entries); rely on review to catch it (it is one line at the top of a file nobody reads in a diff) |
| 2026-09-21 | The collation PR prefers a CHANGELOG_BOT_TOKEN, and says so in the PR body when it has none | GitHub does not fire pull_request workflows for GITHUB_TOKEN events, so the PR is unmergeable against required checks until a human reopens it | Use GITHUB_TOKEN and say nothing (a nightly PR that silently cannot merge); require the secret (the job would not run at all until someone provisions it) |
| 2026-09-18 | Keep the v5→v6 conversation rewrite a client-side pass; no skip-if-clean pre-check | Staging: 20 of 24 startup minutes in `migrateEnvironments` over 80 MB | A pre-check was built and removed: a nested legacy URI has no filter form, so proving a collection clean costs the same read. Server-side `updateMany` is the real fix, left as follow-up |
| 2026-09-18 | Default Gemini's `returnThinking`/`sendThinking` to true rather than requiring agent designers to set them | Without both, no Gemini 3.x model can use tools at all — a 400 with no config workaround, not a preference | Leave them opt-in and document it (every Gemini 3.x agent breaks until its author reads the docs); pin them on with no override (removes configurability for no gain) |
| 2026-09-18 | Leave Anthropic and Bedrock `returnThinking` alone; enable it when extended thinking becomes configurable | Both have the identical signed-thinking echo-back requirement and langchain4j models it, but no config key turns the mode on, so the provider returns no signed blocks and the flag is untestable | Set it now anyway — ships a line no test can reach and implies the mode works |
| 2026-09-18 | Warn instead of fixing `gemini-vertex` for Gemini 3.x | Neither `langchain4j-vertex-ai-gemini:1.20.0-beta30` nor the `Part` protobuf (`proto-google-cloud-vertexai-v1:1.27.0`) has a `thought_signature` field — an upstream change plus a dependency bump, not an EDDI fix | Hard-fail the model build (breaks Gemini 3.x agents that use no tools and work today); say nothing (operators meet a bare 400 from inside the provider) |
| 2026-03-05 | Use Astro (not Expo) for website                                      | Static site on GitHub Pages           | Expo would add unnecessary abstraction for a marketing site |
| 2026-03-05 | Use AI complexity scale (🟢/🟡/🔴/⚫) instead of human time estimates | AI will do all implementation work    | Human hours are meaningless for AI execution                |
| 2026-03-05 | Docs already published at docs.labs.ai                                | Third-party tool reads `docs/` folder | Could migrate to Astro Content Collections later            |
| 2026-09-17 | Keep `deny-licenses` in dependency-review, broadened to GPL-2.0, LGPL-2.0/2.1/3.0, SSPL-1.0, BUSL-1.1 and Elastic-2.0 | An allow-list would fail today on the `LicenseRef-bad-non-standard` values GitHub reports for jsoup and classgraph, and would gate nothing extra — unknown licences are informational in both modes | Migrate to `allow-licenses` (needs two permanent per-package exclusions to work around GitHub's normalisation); leave the list at GPL-3.0/AGPL-3.0 (misses the source-available relicensing hazard that actually threatens a project depending on MongoDB and Elasticsearch clients) |
| 2026-09-17 | Mark secret context on the value (`"secret": true`), scrub every copy when the turn ends | A per-user credential sent as context was stored, echoed and copied into properties; `scope: secret` holds one vault slot per agent | A list of secret keys in the agent configuration — couples every agent to one client's field names |
| 2026-09-14 | Connection deployment settings are runtime-writable; a set property pins its value (409 on change) | Properties-only meant a restart per change and protected nothing from `eddi-admin`, who already writes the vault and can send any `${vault:}` value anywhere via an httpcall | Keep properties only (restart, no real protection); store without pinning (removes the operator/admin split for deployments that have one); seed the store from properties (a removed property would be silently replaced by its copy) |
| 2026-09-13 | Block the cloud metadata service on every outbound path, even with `eddi.security.ssrf-protection.enabled=false` | E2E: a config-authored httpcall reached `169.254.169.254` | Flip SSRF protection on by default — breaks every configured internal API |
| 2026-09-13 | Buffer a turn's audit entries and flush them after the pipeline, redacting a vaulted input | E2E: parser/rules entries carried a `scope: secret` plaintext into the append-only ledger | Redact after submission — impossible, entries are signed and immutable |
| 2026-09-13 | Exclude stateful tools from the tool cache by reflecting over their `@Tool` classes | E2E: group members share a user, so `listArtifacts()` was served stale | Make caching opt-in per tool — changes every existing cached tool |
| 2026-09-13 | New group save-time checks (member agentId, negative limits, preset roles, nesting cycles) are hard errors | E2E: all saved fine and failed at run time | Warn only — the invalid configs cannot run as written, and shipped templates pass |
| 2026-09-20 | Escape record boundaries in the throwable's MESSAGE before the trace is rendered, not in the rendered `%s%e` output | `%e` prints `toString()` as the trace's first line, so a CR/LF in an exception message forged a record past every call-site `sanitize(...)` | Scan the rendered trace and keep the breaks that begin `\tat ` / `Caused by:` / `\t... N more` — an attacker can write all three into a message, so the scan has to guess; or drop the throwable at the ~415 call sites — the stack trace is often the only diagnostic left |

---

## Regression Notes

_Track any regressions introduced during implementation for quick debugging._

| Date | Regression | Cause | Fix | Commit |
| ---- | ---------- | ----- | --- | ------ |
| 2026-10-20 | Every HITL resume that re-supplied secret context answered 400, so an approved call acting as the user could never run | Both conversation stores rebuilt context entries without the `secret` flag when loading a conversation; the resume check and the injection read it from that memory | Both loaders keep the flag; store round-trip tests for MongoDB and PostgreSQL | (this branch) |
| 2026-10-15 | An embedding that relied on `?token=` stops authenticating | The parameter is now ignored unless `eddi.chat.url-token.enabled=true` (M6); the widget says so in the browser console | Set the property, or move to the `postMessage` hand-off | a136e09 |
| 2026-10-15 | A deployment behind a TLS-terminating proxy with neither `quarkus.http.proxy.proxy-address-forwarding` nor an absolute `quarkus.oidc.resource-metadata.resource` advertises an `http` MCP resource identifier | `force-https-scheme` now defaults to `false` (L3) | Set one of those two, or `force-https-scheme=true` | a136e09 |
| 2026-10-15 | `ExpressionProvider` recursed without bound (StackOverflowError at depth 20,000, ~10 s CPU for 3 MB); the Error escaped task-failure accounting; `saveResponse` without `responseObjectName` failed every turn | No length/depth cap and per-level `substring`; `LifecycleManager` caught only `RuntimeException`; no save-time validation | `ExpressionLimits` + index-based parser, `StackOverflowError` handled as a task failure, `ApiCallsConfigurationValidator` | fix/parser-bounds |
| 2026-10-03 | Dashboards queried series that do not exist (`eddi_nats_dead_letter_count`, `http_server_requests_seconds_bucket`, a `skill` label, `jvm_info`) and read "No data" forever | The coverage test only substring-matched the Full dashboard against registered meters; nothing checked queries against what a scrape contains | Every dashboard parsed; every query's series checked against registered meters plus a verified built-in list | (this branch) |
| 2026-10-03 | GDPR erasure kept SSNs in 6/6 audit rows and reported complete:true; fast erasures reported auditEntriesPseudonymized: 0 | Erasure only rewrote userId, store-side, while entries were still in the write queue | AuditLedgerService.eraseUser flushes, redacts and re-seals; IAuditStore.redactEntry | fix/gdpr-audit-memory |
| 2026-10-03 | `maxToolCallsPerTurn` restarted from zero after a HITL resume | The resumed loop counted only its own trace; the pre-pause trace was merged in afterwards | `ToolLoopResumer` seeds the loop trace with `batch.getTraceSoFar()`; `AgentOrchestratorResumeToolLoopTest.perTurnCapCountsCallsMadeBeforeThePause` | `fix/llm-tool-execution` |
| 2026-09-30 | `maximumLifeTimeOfIdleConversationsInDays=-1` ended every conversation five minutes after boot | `DAYS.between(date, today) >= -1` is always true | A limit below 1 disables idle-ending | fix/650-idle-negotiation |
| 2026-09-29 | EDDI failed to start on this branch (caught by CI E2E before merge) | `LlmModule.configure()` runs twice under CDI and the provider collision guard checked the map it had just filled | Guard against a fixed built-in type set; a test calls `configure()` twice | feat/openai-compatible-providers |
| 2026-09-28 | Manager RAG upload tests failed under Node 24 | undici 7 brand-checks File/Blob; jsdom's global File loses its bytes in FormData and breaks request.formData() | Assert on the multipart part's name and content type, parsed from the raw body | d09d1b120a |
| 2026-09-28 | A live sync after the first one showed the source's plaintext credentials in the preview and wrote them, and the source's vault references, over the target's | `RemoteApiResourceSource` read store documents unscrubbed, and `ScrubbedSecrets` restored only placeholders | Scrub every document the remote source reads; treat vault references as target-bound in the restore | `fix/agent-sync-hardening` |
| 2026-09-28 | Syncing an agent after a workflow was added on the source answered 201 and left an agent version that could not be deployed | `createNewWorkflow` stored the source's workflow document unchanged — its steps named source-only ids — and created none of its resources | Create the workflow's resources first and repoint its steps at them | `fix/agent-sync-hardening` |
| 2026-09-26 | A delete racing an update erased the new version; `set()` on a history version silently did nothing and on the current version could roll back a newer write | Delete removed by id alone; `set()` used insert-if-absent for history and an id-only replace for the current row | Version predicate on delete (tombstone upsert + revert on conflict), CAS for current `set()`, `replaceHistory` for history rows | fix/import-persistence |
| 2026-09-28 | Weekly Slack digest posted (0) for every metric | Actions cache eviction reseeded the week baseline to "now" 8 min before the delayed Monday run | Digests computed row-to-row from the `metrics` branch, sent by any run once due, deduplicated by a claimed marker | fix/weekly-digest-durable-baseline |
| 2026-09-27 | Updating either of two pre-existing duplicate-named channel integrations of the same type now fails validation until one is renamed | New global per-type name uniqueness check on create/update (Finding C) — benign migration: rename one integration | Rename one of the clashing integrations, then update | fix/security-channel-identity |
| 2026-09-26 | Any caller could read, drive and delete a soft-deleted conversation, and read the audit trail of a deleted one | `ConversationAccessGuard` treated a missing live descriptor as "allowed" | Resolve the owner via the archived descriptor, 404 for non-admins without one, soft delete ends the conversation | fix/conversation-access-guard |
| 2026-09-26 | Saving a second Studio stage 409'd; group/workforce edits "reverted"; a quick second agent-section edit vanished | Pages kept the version they were opened with instead of the one in the save's `Location` | Pages move onto the saved version; cascade checks references before writing and reports partial progress for retry | (this branch) |
| 2026-09-26 | Secret 🔒 input persisted as public input:initial and echoed on reload | Only conversationOutput["input"] was masked; input:initial kept the raw text and is always included in the simple snapshot | Scrub input:initial/normalized to the placeholder before persist in Conversation.executeConversationStep's finally | fix/security-frontend |
| 2026-09-26 | Secret input still persisted verbatim inside parser expressions (unknown(<token>)) | scrubSecretUserInput scrubbed only input:initial/normalized; the parser's expressions:parsed/matches/intents keep the normalized secret and a free-text secret matches no dictionary | Also drop the derived parsed forms in scrubSecretUserInput (dropParsedSecretForms), mirroring PropertySetterTask.dropParsedForms | fix/security-frontend |
| 2026-09-26 | Chat ?apiServer= deny-list bypassed by a leading control char, leaking the bearer token off-origin | %09/space prefix escaped the scheme/// /\ tests but the URL parser strips it, resolving ${base}${path} to the attacker origin | Replace with an allow-list requiring a clean single-leading-/ path | fix/security-frontend |
| 2026-09-26 | scope:secret checksum was a brute-forceable unsalted SHA-256 exposed over REST | plain digest of low-entropy user secrets | keyed tenant-bound HMAC checksum, never returned over REST | fix/security-secrets-crypto |
| 2026-09-24 | A task with an empty `knowledgeBases` and no `enableWorkflowRag: true` also retrieves nothing and says nothing — `RagContextProvider.retrieveContext` returns before workflow discovery even runs, so unlike the missing-step cause there is no `DEBUG` line either. Check the task's own RAG settings before checking the workflow binding. |
| 2026-09-22 | Matrix cells: a phase not yet reached must read `pending`, not `absent` | `absent` means "the selector excluded them"; using it for "not yet" told readers a debate's PRO side had gone quiet during a CON-only phase. Guarded by `use-discussion-digest.test.ts` "distinguishes a member excluded from a phase from one still expected". | EDDI Manager |
| 2026-09-22 | Stance coverage must count a member's OWN contributions, not transcript length | Keyed to the transcript, any member speaking invalidated every member's stance: a six-member discussion re-summarised all six at every boundary. Guarded by `StanceSummaryEngineTest` "a member is NOT re-summarized because somebody else spoke". | EDDI |
| 2026-09-22 | A continuation round restarts phaseIndex at 0 — slice at roundStartTranscriptIndex | Without the slice, round 1's turns render in round 2's cells and a round-1 failure marks a round-2 cell failed. Guarded by `use-discussion-digest.test.ts` "does not merge a previous round's turns into this round's phases". | EDDI Manager |
| 2026-09-22 | The live cost map overlays the persisted one, never replaces it | A stream carries only the keys it announced this session; swapping dropped earlier rounds and unspoken members, so Continue on a $4.10 discussion showed $0.02. Guarded by "keeps persisted keys the live stream has not re-announced". | EDDI Manager |
| 2026-09-22 | A LIVE continuation keeps every round in one transcript — slice at the stream's own roundStartIndex | `continueStream` preserves `s.transcript` and `group_start` appends; assuming the live transcript was already round-scoped re-created the cross-round contamination the persisted slice prevents. Guarded by "slices a LIVE continuation at the stream's own boundary". | EDDI Manager |
| 2026-09-22 | The I1 ceiling must be re-checked per member, not once per boundary | Each stance call adds to the ledger, so one decision up front let every member after the first spend past an exhausted budget. | EDDI |
| 2026-09-22 | Overview mode unmounts the transcript, so anything rendered only inside it is GONE | The task board and the synthesised answer were both invisible in Overview until moved into the `extras`/`outcome` bands. Anything added to a transcript renderer in future needs the same question asked. | EDDI Manager |
| 2026-09-22 | A repeating phase must render its repeat count | ROUND_TABLE and DELPHI are built on `repeats`; rendering the phase once made a four-pass deliberation indistinguishable from a single one, in the two styles most groups use. | EDDI Manager |
| 2026-09-22 | A live sync worked once per target agent, then failed permanently with "the store did not accept the update"; the version it did write could not be deployed | `readDescriptor(id, null)` always throws on a historized store, so version resolution fell back to 1 — and nothing moved the `DocumentDescriptor` onto the version each write produced | Resolve through `readCurrentDescriptor`, and bump the descriptor after every write, reporting a resource whose descriptor could not be moved as a failure | `fix/agent-sync-promotion` |
| 2026-09-22 | `invalid_request_error: messages: Field required` on any Anthropic agent with no `ai.labs.output` step | `includeFirstAgentMessage: false` removed message zero whatever its role, emptying a one-turn history | Removal is role-aware; only an `assistant` first message is dropped | (this branch) |
| 2026-09-22 | Every `@RolesAllowed` endpoint 403'd, with an empty body and no log line, for users in a Keycloak group | `quarkus.oidc.roles.role-claim-path` absent from the 6.4.0 image; quarkus-oidc fell back to the `groups` claim | Startup ERROR naming `QUARKUS_OIDC_ROLES_ROLE_CLAIM_PATH`, plus a test pinning the property in the source | (this branch) |
| 2026-09-22 | `GET /manage/` answered 200 with an empty body | The empty path normalized to the resource base, whose directory entry is a non-null empty stream | A path that normalizes to nothing serves the SPA shell | (this branch) |
| 2026-09-22 | "Show more" on a long group question could not be undone without reloading | The expanded text was unbounded inside a shrink-0 header, pushing its own toggle out of an overflow-hidden pane | Bound the expanded question and let it scroll in place | (this branch) |
| 2026-09-22 | A group that had held any discussion could never accept an uploaded file again | "New Discussion" cleared the selection and the auto-select effect restored it within a tick, so the attachment control never rendered | An explicit-clear ref the auto-select effect honours | (this branch) |
| 2026-09-22 | Manager pages hung on skeleton loaders while the backend was healthy | A boot-time SSE log stream per tab saturated Chrome's six-connections-per-origin cap | The stream is opened lazily by its consumers and refcounted | (this branch) |
| 2026-09-22 | A saved config edit silently did not take effect | A plain Save cascades resource → workflow → agent but never deploys, and reported plain success | The toast says "not yet live" and offers a Deploy action | (this branch) |
| 2026-09-21 | A RAG setup with a correct KB config and a correct `knowledgeBases` reference but no `eddi://ai.labs.rag` workflow step retrieves nothing, and says nothing: no context, no `rag:trace:*`, no error, and the only log is `DEBUG` "No RAG steps found in workflow". Check the workflow step first, and `GET /extensionstore/extensions` before that on older builds. |
| 2026-09-21 | `POST /ragstore/rags/{id}/ingest` accepts a `kbId` that overrides the embedding-store key, but retrieval always keys on the KB's `name` and cannot be redirected. A `kbId` that is not exactly the `name` ingests into a store nothing reads, reporting `202` then `completed` the whole way. Leave `kbId` unset. Ingestion sources are unaffected. |
| 2026-09-21 | A RAG ingestion source with a cron was stored looking enabled and never fired; a ZIP could store a cron the REST API refuses; run reports named a null source; `docs/rag.md` overstated defaults; a Manager test asserted nothing | All five were raised in review on PR #790 and the PR was **merged with those threads unresolved** — the review caught them, the merge did not wait for them | `nextFire` computed in `buildSchedule` plus a repeatable startup repair for existing rows; cron validation shared between the REST and import paths; `effectiveId()` at all six report sites; docs corrected against the code; the Manager test rewritten to serve a saved-disabled source so no dirty-state guard can mask it | (this branch) |
