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
| [October 2026](changelog/2026-10.md) | 93 | 426 KB |
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

## 🔒 fix(ui): clear three npm advisories reported by Scorecard (2026-10-16)

**Repo:** EDDI (`fix/ui-dependency-advisories`)

### What changed and why

OpenSSF Scorecard's Vulnerabilities check flagged three advisories. All three
came from the UI lockfiles, not from the Maven build.

- **GHSA-238p-pmpm-9mq7, KaTeX prototype-pollution trust bypass (`katex` < 0.18.2).**
  The Chat UI's own `katex` was already 0.19.0. `rehype-katex@7.0.1` and
  `micromark-extension-math@3.1.0` each ask for `^0.16`, which gave them a nested
  0.16.47 copy that ships in the chat bundle. Both are on their latest release,
  so an npm `overrides` entry now points both at the root version (`$katex`).
  Build, lint and all 480 chat tests pass, including the `MessageBubble`
  math-rendering tests.
- **GHSA-rj75-hqrm-r3gf, quadratic selector parsing in `postcss-selector-parser`
  (< 7.1.6).** `@tailwindcss/typography@0.5.20`, also on its latest release,
  pins exactly 6.0.10. The Manager now overrides it to `^7.1.6`. This is a
  build-time dev dependency. The Manager's generated CSS is byte-for-byte
  identical under 6.0.10 and 7.1.6, across all three stylesheets and 141
  `.prose` rules.
- **GHSA-vfj7-8cjw-p6xm, `braces` stack exhaustion (all versions through 3.0.3,
  with no fixed release).** It reached the repo only through
  `ui/manager/.ds-sync/package-lock.json`, via `@tailwindcss/cli` →
  `@parcel/watcher` → `micromatch`. `.ds-sync` holds local design-sync tooling
  that is never shipped and is not inventoried in the SBOM. Its lockfile is
  removed and gitignored, and the Dependabot entry for that directory stays to
  keep the manifest's direct dependencies current.

Both lockfiles were regenerated with the pinned Node 24.21.0 (npm 11.19.0), so
the diff holds only the dependency change, without `peer`-flag churn from a
different npm. Each nested entry was deleted by hand before `npm install`,
because npm does not apply a new override to an entry the lockfile has already
resolved.

**Files:** [`ui/chat/package.json`](../ui/chat/package.json),
[`ui/manager/package.json`](../ui/manager/package.json), both
`package-lock.json` files, [`ui/manager/.gitignore`](../ui/manager/.gitignore),
[`.github/dependabot.yml`](../.github/dependabot.yml)

### Next

Drop the two overrides once `rehype-katex`/`micromark-extension-math` accept
KaTeX ≥ 0.18.2 and `@tailwindcss/typography` stops pinning
`postcss-selector-parser` 6.

---

## perf(groups): run the blocking group endpoints on virtual threads (2026-10-15)

**Repo:** EDDI · **Branch:** `feat/group-endpoints-virtual-threads`

### What changed and why

This is the last part of the throughput plan's WS2 (item 5). Six group endpoints run discussion
work inline, and each one held a Quarkus worker thread for an entire multi-agent discussion,
every member turn and LLM call included:

- `discuss`
- `followUpWithMember`
- `continueDiscussion`
- `approveGroupPhase`
- `submitHumanInput`
- `approveGroupPhaseStreaming`, which calls `resumeDiscussion` on the request thread

The worker pool has about 200 threads and blocking REST endpoints share it, so a handful of
long discussions could starve unrelated requests. Conversation turns already left that pool
(PR #1045), and the member turns inside a discussion already run on virtual threads.

[`RestGroupConversation`](../src/main/java/ai/labs/eddi/engine/internal/RestGroupConversation.java)
now marks those six methods `@RunOnVirtualThread`. The other streaming endpoints were already
asynchronous and are unchanged: `discussStreaming` goes through `startAndDiscussAsync`, and
`continueDiscussionStreaming` uses its own executor.

### Design decisions

- **The annotation is on the implementing methods, not on `IRestGroupConversation`.** The JAX-RS
  contract stays free of execution-model detail. Whether Quarkus REST honours the annotation
  there was the open question; `GroupEndpointsVirtualThreadIT` settles it over HTTP.
- **The proof is an IT, not a unit test.** The execution model is chosen when Quarkus dispatches
  the request, so calling the bean directly cannot observe it. The IT installs a Mockito
  stand-in for `GroupConversationService` with `QuarkusMock` (Mockito, not a JDK proxy, because
  `QuarkusMock` needs an instance of the bean class). The stand-in records
  `Thread.isVirtual()` for each service call and completes the streaming listener.

### Verification

- `GroupEndpointsVirtualThreadIT`: 6 tests, one per endpoint. With every annotation removed,
  all 6 fail with "a worker thread".
- `GroupConversationIT` and `GroupHitlIT`, which drive real discussions, approvals and human
  input through these endpoints, pass (12 tests).
- 1,106 group unit tests pass, along with `ImportStyleTest`, `BuildQualityGatesTest` and
  `ChangelogRotationTest`.

---

## Outbound HTTP callers moved onto the facade; `SafeHttpClient` retired — consolidation phase 2 (2026-10-15)

**Repo:** EDDI · **Branch:** `feat/outbound-http-callers` (stacked on `feat/outbound-http-facade`, labsai/EDDI#1042) · **Plan:** `planning/http-client-consolidation-plan.md`, Phase 2

**What changed**

Every EDDI-owned caller now sends through `OutboundHttp`, with its existing caps, timeouts and redirect rules unchanged:

| Caller | SSRF / redirects | Body |
|---|---|---|
| `WeatherTool`, `WebSearchTool` | `HOPS_ONLY` (fixed hosts), JDK-safe redirects | `bytes(1 MiB / 2 MiB, TRUNCATE)`; a truncated answer is refused as before |
| `PdfReaderTool`, `WebScraperTool` | the tool's own URL check, then `HOPS_ONLY` | `bytes(eddi.tools.*, TRUNCATE)` |
| `AttachmentForwarder` | `STRICT` | `bytes(per-file cap, TRUNCATE)` |
| `SafeHttpPageFetcher` (crawler) | `STRICT` | `bytes(cap, TRUNCATE)`; its hand-written bounded read and watchdog are gone (identical in the facade), and so is its `IRuntime` dependency |
| `OAuthTokenClient` | `NONE` (the credential-endpoint allowlist is the gate), `NEVER` | `string(1 MiB, FAIL)`. **New cap**: a token answer was unbounded before; anything over 1 MiB is now a transient `TOKEN_ENDPOINT_UNAVAILABLE` |
| `SlackWebApiClient` | `NONE` (fixed host), `NEVER` | `string(1 MiB, FAIL)`; its private `HttpClient` is gone. **Now bounded in time** — it had no request timeout at all, and gets the facade's 15 s per hop |
| `A2AToolProviderManager` | `STRICT` or `METADATA_ONLY` per `eddi.security.ssrf-protection.enabled`, `NEVER` | `bytes(1 MiB, TRUNCATE)`; its lazy private client and its `a2a-body-watchdog` thread are gone |
| `OpenApiSpecFetcher` (MCP/httpcall OpenAPI discovery, added on `main` while this PR was open) | its own redirect loop with `OutboundTargetPolicy` on every hop, each hop one `NEVER` send with `SsrfPolicy.NONE` (the policy is the gate) | `stream(16 MiB, 4 × 30 s)`; its private `openapi-spec-body-watchdog` thread is gone. Its "did not arrive within" message now names the real budget (120 s), not the request timeout |
| `RemoteApiResourceSource` (+ the two static helpers) | new profile `INTERNAL_PEER`: `NONE` (`SourceUrlValidator` stays the gate), `NEVER` | JSON `string(eddi.backup.sync.max-response-bytes, FAIL)` with the old refusal message; archives `bytes(256 MiB, FAIL)` |

- **`SafeHttpClient` is deleted** (internal API, no callers left), with `CappedStringBodyHandler` and the per-source client lifecycle (`close()`, `configure()`, the owned-client flag): sources share the application's facade. `BoundedBodyReader` moved into `engine/httpclient/outbound` as package-private.
- `RestImportService` gets the facade by setter injection, lazily falling back to a standalone one outside CDI, as `A2AToolProviderManager` does.
- AGENTS.md (§4.2 table, §4.4 Tool Security and its sample, Key Files), README, `architecture.md` (SSRF layers and sample), `connections.md`, `code-review-standards.md`, `security-review.md`, `a2a-protocol.md`, `configuration-reference.md` and `metrics.md` now name `OutboundHttp`.
- The `// codeql[java/ssrf]` suppressions moved with the sinks: they now sit on the `http.send(...)` calls in `RemoteApiResourceSource` (and its `sendJson` helper).

**Tests**

- The 22 server-backed `SafeHttpClient` tests were ported to `OutboundHttpServerTest` against the facade on the real JDK engine (a no-op `hopValidator` replaces the Mockito spy that let a redirect reach loopback). Their subjects, and a new cross-origin `Authorization` case, are all still covered over real sockets.
- New test helpers in `engine/httpclient/outbound` (test sources): `TestOutbound` (standalone facade, facade over any engine, canned responses), `ScriptedEngine` (records hops, replays answers), and `JdkClientEngine` (routes facade hops into a mocked JDK `HttpClient`). The last one let roughly 200 remote-sync test stubs stay as they were while the code under test runs through the real facade.
- The crawler, OAuth, A2A wiring and new Slack tests run the real facade over a scripted engine rather than mocking it. New: a loopback crawl seed is refused before anything is sent; an A2A peer's 307 and a Slack 302 are never followed; a sync source declaring a body over `eddi.backup.sync.max-response-bytes` is refused before it is read (the old size tests only simulated the refusal in a stub, so lifting the cap broke none of them).
- Mutation-checked: dropping `NEVER` from OAuth, A2A, Slack or remote sync, weakening the crawler to `HOPS_ONLY`, or lifting the remote-sync size cap each fails a test.

**Design decisions**

- `internal-peer` has its own connection pool but no configuration keys yet. Remote sync had nothing tunable, so it uses the `default` settings, and the configuration reference says so.
- Pass-through, not rewrite: every caller keeps its old checks (the tools' own `validateUrl`, A2A's pre-credential check, `SourceUrlValidator`). The facade's policy is added on top.

**Next:** Phase 3, the langchain4j adapter: shared LLM provider pools, plus `llm-provider` keys.

---

## Outbound HTTP facade — Phase 1 of the client consolidation (2026-10-15)

**Repo:** EDDI · **Branch:** `feat/outbound-http-facade` · **Plan:** `planning/http-client-consolidation-plan.md`, Phase 1

**What changed**

- New package `ai.labs.eddi.engine.httpclient.outbound`. `OutboundHttp` is the one place outbound policy lives: the SSRF policy per request (`STRICT`, `HOPS_ONLY`, `METADATA_ONLY`, `NONE`, `FOLLOW_GLOBAL_FLAG`); redirects followed hop by hop with a `MethodRule` (`JDK_SAFE`, the RFC 9110 rule `SafeHttpClient` always used, and `VERTX_COMPAT`, which matches what the `httpcalls` Vert.x client does); cross-origin stripping of the fixed `SENSITIVE_HEADERS` **plus any header the request marks caller-derived** (a `${caller:token}` can sit in any header name); one whole-exchange deadline; redirect bodies dropped after 64 KiB; body caps enforced while reading (`BodyMode.string/bytes/file/stream/discard`, `OnOverflow.TRUNCATE|FAIL`); refusal on a Vert.x event-loop thread; four meters (`eddi.http.outbound.requests|duration|blocked|bytes`) on a new **Outbound HTTP** dashboard row.
- `OutboundEngine` is a one-hop SPI; `JdkOutboundEngine` keeps one `java.net.http` client per `Profile` (`default`, `llm-provider`, `apicalls`, `internal-peer`), with an optional forward proxy (vault-resolvable credentials, answered to proxy challenges only, failing closed when unresolved) and an optional named `quarkus.tls` configuration.
- `SafeHttpClient` is now a `@Deprecated` adapter over the facade. Its API and behaviour are unchanged: all 22 server-backed `SafeHttpClientTest`/`SafeHttpClientBodyBoundsTest` cases pass as they were, apart from one import (`CallerFrames` moved). Its unit tests of moved internals were ported: the method rule to `MethodRuleTest`, the budget and redirect-body discard to `OutboundHttpTest`, and the `withDefaultTimeout` rebuild to a request-conversion test.
- New settings, documented in [configuration-reference.md](configuration-reference.md#outbound-http-client): `eddi.http.outbound.engine`, `eddi.http.outbound.default.{connect-timeout,total-timeout,protocol,tls.configuration-name}`, `eddi.http.outbound.proxy.{host,port,non-proxy-hosts,credentials}`. The connect timeout falls back to `httpClient.connectTimeoutInMillis`, so a tuned deployment keeps its value.

**Design decisions**

- Only the `default` profile has keys. The others get theirs in the phase that moves their callers, so no documented setting is a silent no-op in the meantime.
- **`tls.named-groups` was dropped from this phase.** `java.net.http.HttpClient` copies the SSL parameters it is given and discards `namedGroups`: `client.sslParameters().getNamedGroups()` comes back `null`. A key would therefore be a no-op. PQC plan PQ-3 needs another route: an `SSLContext` wrapper that sets groups per engine, the global `jdk.tls.namedGroups`, or the Vert.x engine.
- `max-connections-per-host` was deferred to the Vert.x engine (Phase 4), because the JDK client has no per-host pool limit.
- `OutboundBlockedException` extends `IllegalArgumentException` and `OutboundTimeoutException` extends `HttpTimeoutException`, so every existing `catch` keeps working.
- The plan's table maps `send*` to `NONE`, but what `send` actually did was trust the initial URL and fully validate every redirect hop. That is `SsrfPolicy.HOPS_ONLY`, a policy the plan did not list.
- The event-loop guard was enabled after an audit: the only `Uni`/`@NonBlocking` code in `src/main/java` is `NamelessPrincipalAugmentor`, which makes no outbound call.
- Mutation-checked: removing caller-derived stripping, the `VERTX_COMPAT` RETURN, the redirect-hop check, the FAIL-mode cap probe and the redirect-body cap each fails a test.

**Next:** Phase 2 moves the callers (`OAuthTokenClient`, `SafeHttpPageFetcher`, `AttachmentForwarder`, the tools, `SlackWebApiClient`, `A2AToolProviderManager`, `RemoteApiResourceSource`) onto `OutboundHttp` and retires `SafeHttpClient`.

---

## docs(security): cryptography inventory and post-quantum readiness, guarded by a test (2026-10-15)

**Repo:** EDDI · **Branch:** `feat/pqc-crypto-inventory`

First step (PQ-1) of the post-quantum readiness work. It changes no runtime behaviour.

### What changed and why

- **New page [`docs/cryptography.md`](cryptography.md)** lists every algorithm EDDI uses (vault AES-256-GCM and PBKDF2, the HMAC-SHA256 audit ledger and vault checksums, Ed25519 agent signing, SHA-256 hashing, and TLS), what each one protects, and whether it is post-quantum safe. It also covers the threat model (harvest-now-decrypt-later for TLS, future forgery of Ed25519 signatures) and the roadmap. The page is linked from [`security.md`](security.md) (TLS section and See Also) and from `SUMMARY.md`.
- **Verified on JDK 25.0.1:** JSSE's default named groups are classical only (`x25519, secp256r1, secp384r1, secp521r1, x448, ffdhe*`), while `ML-DSA-65` and `ML-KEM-768` are available through the standard JCA (an ML-DSA-65 signature is 3309 B and its public key 1974 B). The page states only these verified facts about TLS.
- **New `CryptoInventoryTest`** scans `src/main/java` for the JCA engine `getInstance(...)` calls and `new SecretKeySpec(...)`. It resolves each algorithm from a literal or a same-file `static final String`, and fails the build in four cases:
  - the algorithm is not on its allowlist;
  - the argument cannot be resolved, so moving the string into a variable cannot bypass the check;
  - the call pins a JCA provider;
  - an allowlist entry is no longer used, or is not named in `docs/cryptography.md`.

### Design decisions

- Rather than scan only the per-class constants listed in the plan, the test resolves each call's argument, so a new call site cannot slip through by using an unfamiliar constant name.
- `TrustManagerFactory`, `KeyManagerFactory` and `KeyStore` are left out of the scan: they name PKIX and trust-store formats rather than primitives.
- The allowlist is two-way (unused entries fail as well) so the doc cannot drift from the code.

### Next

PQ-5a: make the existing agent signatures on audit entries versioned and verifiable (see `planning/pqc-readiness-plan.md`).

---

## ✨ feat(rag): live run progress, a knowledge-base inspector and schedule status for ingestion (2026-10-15)

**Repo:** EDDI (`feat/rag-ingestion-ux`)

### What changed and why

Ingestion ran blind: a run row was written as `RUNNING` with zeroes and only got its numbers when it
ended, nothing said what a knowledge base actually held, and a source's schedule was a cron string
with no word on whether it was armed, firing or failing. The backend now answers all three.

**Run visibility**

- `IIngestionStateStore.reportProgress(run)` writes a running run's counters onto its row, fenced on
  `status = RUNNING` in both stores, so a late write can neither reopen nor overwrite a finished or
  reaped run. `IngestionPipeline`'s collector calls it at most every two seconds (forced once at the
  start, so the trigger is on the row before the first document); a failed write is a stale number,
  never a failed run.
- `IngestionRun` gains `documentsSkipped`, `stopReason` and `trigger` (`MANUAL` / `SCHEDULED`), with a
  13-argument constructor kept for the existing callers. Postgres gains the columns through
  `ADD COLUMN IF NOT EXISTS`; Mongo needs nothing. The skipped count and stop reason were already in
  `IngestionReport` and reached only the preview response and the log.
- `RagSourceIngestionService.runAsync` takes the trigger; `processScheduledFire` passes `SCHEDULED`.
  A run cancelled at shutdown is now closed with the counters its last progress write recorded rather
  than zeroes.
- `POST …/sources/{sourceId}/run` returns the `runId` it reserved.

**Inspector** — `KnowledgeBaseInspector`, read-only:

- `GET …/sources/{sourceId}/documents` (VIEW) — the source's document states, most recently embedded
  first, filterable and paged, labelled by file name for uploads; the orphan-sweep marker row is hidden.
- `GET …/sources/{sourceId}/chunks?documentId=` (EDIT) — one document's chunks in document order.
- `POST /ragstore/rags/{id}/search` (EDIT) — a test retrieval with the knowledge base's own
  `maxResults`/`minScore` as defaults, optionally narrowed to one source.
- The vector stores have no listing, so chunks are reached by a similarity search filtered on the
  metadata ingestion writes, and always ANDed with `EmbeddingStoreFactory.retrievalFilter` — the
  inspector sees exactly what retrieval would, and a per-id knowledge base in a shared table sees only
  its own chunks. A store that cannot filter answers 501; a failing provider or store 502 with the cause.

**Schedules**

- `IScheduleStore.readScheduleByName` (Mongo + Postgres), and `GET …/sources/{sourceId}/schedule`
  (VIEW) returning next/last fire, fire status, fail count, disable reason and the last five fires.
- `GET /ragstore/rags/cron/preview` applies the save-time checks — parseable, matches a real date, not
  more frequent than `eddi.schedule.min-interval-seconds` — and returns the next fire times in UTC.

### Design decisions

- **Chunks and search need EDIT, the document list VIEW.** A published knowledge base grants VIEW to
  everyone; the chunk text is the knowledge base's content and every call costs an embedding.
- **Progress is throttled in the pipeline, not in the store.** One small update per source every two
  seconds; the interval is an instance field only so a test can make every document report.
- **Search goes through the retrieval isolation filter unconditionally.** An inspector that could see
  another knowledge base's chunks in a shared table would be a cross-tenant read.

### Tests

`IngestionStateStoreContract` (three new cases, run against Mongo, Postgres and the in-memory double),
`IngestionPipelineTest` (trigger + stop reason recorded; counters visible on the running row while it
embeds), `RagSourceIngestionServiceTest` (shutdown keeps progress), `KnowledgeBaseInspectorTest`
(real `InMemoryEmbeddingStore`: ordering, isolation, source narrowing, min score, file labels),
`RestRagIngestionInspectionTest` (access levels, clamping, 400/404/501/502), `RagSourceScheduleViewTest`,
and `readScheduleByName` in both schedule store container tests.

**Files:** [`IIngestionStateStore.java`](../src/main/java/ai/labs/eddi/modules/ingestion/IIngestionStateStore.java),
[`IngestionPipeline.java`](../src/main/java/ai/labs/eddi/modules/ingestion/IngestionPipeline.java),
[`KnowledgeBaseInspector.java`](../src/main/java/ai/labs/eddi/modules/ingestion/KnowledgeBaseInspector.java),
[`RagSourceIngestionService.java`](../src/main/java/ai/labs/eddi/modules/ingestion/RagSourceIngestionService.java),
[`IRestRagIngestion.java`](../src/main/java/ai/labs/eddi/configs/rag/IRestRagIngestion.java),
[`RestRagIngestion.java`](../src/main/java/ai/labs/eddi/configs/rag/rest/RestRagIngestion.java),
[`IScheduleStore.java`](../src/main/java/ai/labs/eddi/engine/schedule/IScheduleStore.java), both state
and schedule stores, [`docs/rag.md`](rag.md).

### What's next

The cost controls planned in [`rag-ingestion-source-types-plan.md`](../planning/rag-ingestion-source-types-plan.md)
(§3.13) and run outcome/duration metrics. A cancel button for a running run would need a
cancellation signal the worker polls; `isCancelled` is the natural hook.

---

## ✨ feat(manager): see ingestion while and after it runs — progress, run details, schedule, contents, test retrieval (2026-10-15)

**Repo:** EDDI (`feat/rag-ingestion-ux`)

### What changed and why

The knowledge-base editor showed a spinner during a run, "N ingested · M unchanged" after it, a bare
cron string for the schedule, and nothing at all about what the knowledge base held. It now uses the
endpoints added in the backend entry of the same date.

- **Live progress** (`ingestion-run-history.tsx` → `RunProgress`): documents looked at against what the
  last complete run saw (or the file count, for a Files source), every counter as it moves, the trigger,
  and elapsed time against the source's time budget. The run history polls every 2 s while a run is in
  flight (was 3 s).
- **Run history** opens each run to all it recorded — skipped, failed, removed, chunks, duration,
  estimated cost and how it ended, the stop reason said in words. A completed run that lost documents
  gets an amber icon instead of the green tick, and its summary says how many failed.
- **Errors say what the server said.** Run, Preview and Purge failures append the server's message to
  the generic line ("…A run may already be in flight." was shown for a disabled source and for a
  database fault alike).
- **Schedule** (`ingestion-schedule.tsx`): a preset picker (hand only, hourly, daily, weekly, monthly —
  UTC) or a custom cron checked against the server's rules while it is typed, with its next fire times
  in local time. Once saved, `ScheduleStatus` shows the next run, the last fire, recent fires with their
  errors, and says in words when a saved cron produced no schedule, when fires are failing, when the
  scheduler disabled it and when it was dead-lettered. The card header shows the preset's name with the
  raw cron on hover.
- **Ingested documents** (`ingestion-source-contents.tsx`): the source's documents, filterable and
  paged, each Live, Missed by the last N runs or Removed; opening one shows its chunks. Collapsed by
  default — each opened document is an embedding call on the server, so chunks are fetched only on
  demand, never retried, and kept for five minutes.
- **Test Retrieval** (`retrieval-tester.tsx`): a new editor section that searches the whole knowledge
  base or one source exactly as an agent would and shows each chunk with its score and origin.
- After a run ends the document list and schedule refetch (`useRefreshAfterRun`), keyed on the newest
  run's id and status rather than a timer.
- The legacy **Document Ingestion** section now says that pasted documents cannot be listed, updated
  or removed later, and points to a Files source.
- **Schedules page:** an ingestion schedule links to the knowledge base it is changed on.

### Design decisions

- **No `count` interpolation** in the new strings, so none of them needs per-locale plural categories;
  the numbers read naturally with neutral wording ("Missed by the last {{runs}} run(s)").
- **The source card observes the files query only while a run is in flight**, for the progress total.
  Observing it always refetched the list every time a card was opened — caught by the existing
  "reloads the file list" test.
- The run id `POST …/run` now returns is not used: `ApiClient` discards every 202 body, and the
  `RUNNING` row in the history identifies the run.

### Tests

`resource-detail-rag-insight.test.tsx` (9 cases: progress estimate and bar, run details and stop
reason, server error text, schedule status / missing schedule, live cron check, documents → filter →
chunks, retrieval request and result, retrieval failure), one case in `schedules-regressions.test.tsx`;
the cron assertion in `resource-detail-rag-sources.test.tsx` now checks the preset label and the raw
cron in its title. 87 new keys in all 11 locales; `npm run i18n:check`, `lint` and `typecheck` pass.
The five new operations are added to `openapi-operations.json` (refreshed from a Maven-built spec), which the MSW contract test checks every handler against. Checked in the browser against the mock API.

---

## feat(loadtest): turn load-test harness and the throughput baseline (2026-10-15)

**Repo:** EDDI · **Branch:** `feat/throughput-loadtest-harness`

### What changed and why

WS0 of the [throughput plan](../planning/throughput-optimization-plan.md): every later workstream
must show before/after numbers, and until now there was nothing to measure with.

- **`scripts/loadtest/`** (new). A compose stack (MongoDB, a WireMock OpenAI-compatible LLM, EDDI,
  k6) with everything bound to `127.0.0.1`, plus `loadtest.py`, which uses only the standard library.
  `up` mounts this checkout's `target/quarkus-app` into the published image, the way
  `scripts/cluster-demo` does, and creates one deployed LLM agent through
  `POST /administration/agents/setup`. `run` and `matrix` set the mock's latency through WireMock's
  admin API, run k6 (scenarios `steady`, `burst`, `same-conversation`, `long-conversation`), sample the
  saturation gauges every second, and write turns/s, latency percentiles, errors by status, peak
  executor, coordinator, audit and pool gauges, and MongoDB operations per turn (`opcounters` and `top`
  per collection).
- **`scripts/loadtest/results/baseline-2026-10-15.md`**. Measured on `aa811c838` at 3 s LLM delay:
  50 and 200 concurrent conversations run at the LLM's pace. At 500, throughput flattens at about
  61 turns/s with the managed pool pinned at 199 active and up to 198 queued, and p50 rises from 3.1 s
  to 7.5 s. No request is rejected: overload shows up as latency. Each turn costs about 23 MongoDB
  operations, independent of load.

### Design decisions

- **One Python driver instead of the plan's `setup.sh` / `collect.sh`.** It matches the cluster demo
  and runs the same way from PowerShell and Git Bash.
- **No new meter.** The existing executor gauges explain the latency on their own, so the plan's
  optional `eddi.turn.queue_wait` timer is not added.
- **WireMock with async responses** and no request journal, so the mock is not the bottleneck at
  hundreds of concurrent delayed calls.

### Next

WS1 (configuration cache): re-measure with `--think-ms 3000`, because back-to-back turns hide the 2 s
TTL. WS2 (thread ceiling and admission). Unexplained so far: about 3 vault reads per turn.

---

## perf(runtime): conversation turns on virtual threads, bounded by an in-flight limit (2026-10-15)

**Repo:** EDDI · **Branch:** `feat/throughput-virtual-thread-turns`

### What changed and why

WS2 of the throughput plan. Each in-flight turn held one `ManagedExecutor` platform thread,
blocked on the pipeline's future for as long as the LLM took. The pool (~200 threads), which
blocking REST endpoints share, was therefore the node's turn ceiling. Past it, turns queued
without bound in front of the pool, and overload showed up as latency for everything rather
than as a rejection.

- **Turns run on virtual threads.** `IRuntime.ITurnExecution` is a marker sub-interface of the
  completion callback. Both coordinators (`AbstractQueuedConversationCoordinator.execute`, and
  `ClusterConversationCoordinator.runUnderLease`, which `submitNext` also goes through) now pass
  one, and [`BaseRuntime`](../src/main/java/ai/labs/eddi/engine/runtime/BaseRuntime.java) runs
  those submissions on a dedicated virtual-thread executor (`eddi-turn-*`). The body is
  unchanged: thread bindings, the nested marker (so the pipeline still goes to the nested
  executor), the one-shot callback and the abandonment token. Deploys, retention sweeps and the
  parser endpoint stay on the pool. New gauge series:
  `eddi_runtime_executor_{active,queued}{pool="turn"}`.
- **Admission control: `eddi.conversation.max-in-flight-turns` (default `1000`).** The C11
  `ProcessingTurn` token carries the permit. It is taken with one CAS against a permit counter,
  so concurrent admissions cannot overshoot, and given back exactly once by `finish()`. Only the
  turn's own exits call `finish()`: the turn task's `finally`, its discard hook, and the
  pre-submission rollback. The gauge's existing `release()` still fires as soon as the client
  has its answer, but persistence runs after that on the same thread, so the permit stays held
  until then. Releasing it with the gauge let the node run past its limit (1029 active at 1000
  in the load test below). The permit is admitted after idempotency admission (a duplicate key
  answered from the stored result takes no permit) and **before the tenant quota slot**, so a
  capacity refusal does not spend quota. `startConversation` admits its CONVERSATION_START turn
  the same way, because that turn runs inline and can call an LLM. A turn the watchdog abandons
  while its body keeps running (a swallowed interrupt) keeps its permit until that body really
  stops: the in-flight registration that already tracks such zombies returns it on `settle()`.
  A zombie that never ends therefore holds its permit, which `eddi_conversation_abandoned_running`
  shows. The refusal,
  `TurnCapacityExceededException`, extends `RejectedExecutionException`, so every surface
  answers it with the existing 503 `capacity_exceeded` + `Retry-After`. It is counted in
  `eddi_turn_rejected_total{reason="in_flight_limit"}`.
- **`/v1/chat/completions` runs on a virtual thread** (`@RunOnVirtualThread` instead of
  `@Blocking`). Both the sync wait and the streaming body held a second worker thread per
  request next to the turn's own.
- **Monitoring.** `EddiExecutorSaturated` no longer describes turns. The new
  `EddiTurnCapacityReached` alert fires when turns are refused at the limit for 5 minutes; it
  has a runbook entry and a promtool case. The full-metrics dashboard's
  "Coordinator throughput" panel charts the rejection rate. `docs/metrics.md` and the
  configuration reference cover the new series and property.

### Measured (load-test harness of the WS0 PR, 3 s mock-LLM delay, 10 turns per conversation)

| Concurrent conversations | Before: turns/s · p50 · p95 | After: turns/s · p50 · p95 |
|---|---|---|
| 200 | 52–60 · 3.1–3.3 s · 3.5–4.4 s | 60 · 3.1 s · 3.6 s |
| 500 | 61 · 7.5 s · 9.8–10.1 s | 131 · 3.3 s · 4.2 s |
| 1000 | (not run: past the ceiling) | 269 · 3.2 s · 4.6 s |
| 1500 (limit 1000) | — | 182 · 3.6 s · 5.5 s; 8161 × 503, no timeout, no other 5xx |

Before, at 500 conversations, the managed pool was pinned at 199 active with up to 198 queued.
After, it stays at 0: turns peaked at 1029 active on `pool="turn"` with 326 live JVM threads.
That build still released the permit with the client's answer. The overshoot past 1000 is the
persistence tail that review moved inside the permit, and the numbers were not re-measured
after that change.
MongoDB operations per successful turn are unchanged (≈ 22.7). A refused turn still costs its
conversation loads, because admission sits after them. That is WS3a's single-load work.

### `/v1` answers capacity refusals as 503

Review found that the OpenAI-compatible bridge turned any `RejectedExecutionException` into a 500:
the new in-flight limit, coordinator saturation and the shutdown drain alike. That applied to the sync
response, the streaming error event and conversation start. It now answers 503 `capacity_exceeded` with
`Retry-After: 5`, like every other surface. A quota-store outage gets 503
`quota_accounting_unavailable` (`OpenAiApiException.capacityExceeded`,
`OpenAiConversationBridge.rejectionAsApiException`, and a `Retry-After` header from
`OpenAiExceptionMapper`).

### `max-in-flight-turns=auto` (the default)

A fixed 1000 fitted only one container size. The Helm chart's 1 GiB limit gives a 512 MB heap at the
image's default 50 %, so a busy node would run out of memory long before 1000 turns. `auto`
([`InFlightTurnLimit`](../src/main/java/ai/labs/eddi/engine/internal/InFlightTurnLimit.java)) derives the
limit from the maximum heap: (max heap − 256 MB) ÷ `eddi.conversation.in-flight-turn-memory-mb`
(new, default 2 MB per turn, twice the ~1 MB measured). That gives 128 at 512 MB, ~1400 at 3 GB and
~2900 at 6 GB.
- A number still sets the limit, and `0` still disables it.
- An invalid setting fails the boot (`@PostConstruct`), and the node logs the value it resolved.
- The value is exported as the gauge `eddi_conversation_max_in_flight_turns`, charted next to the
  in-flight count on the full-metrics dashboard. That panel's legend is now on.

### Sizing the limit

A later comparison run (the load-test harness, MongoDB and PostgreSQL, two rounds each) found that
with exactly as many clients as the limit (1000 against 1000), 5–12 % of turns were refused. A turn keeps
its place until it is persisted, which happens after its caller already has the answer, so a client's next
turn can arrive first. The configuration reference and `application.properties` now say to leave roughly
10–20 % headroom above the expected number of concurrently active conversations. They also give the
measured heap per in-flight turn (~1 MB for short conversations) and PostgreSQL's pool default (50).

### Design decisions

- **A callback marker, not a new `IRuntime` method or a wrapped callable.** About 25 tests mock
  `IRuntime`, and a new method would return `null` there. The coordinator tests also assert
  callable identity (`eq(task)`) and `isNull()` bindings, which a wrapper would break. The
  coordinators already pass a callback; marking it changes nothing else.
- **`max-active-conversations` stays.** It caps how many conversations hold a turn queue and
  only ever refuses a *new* conversation; the new limit caps turns. Both are documented side by side.
- **Rejections log at DEBUG in `ConversationService`.** `RestAgentEngine` already logs one WARN
  per 503, and under overload a second line per refused turn only doubles the flood.

### Next

- `RestGroupConversation.discuss` and the other synchronous group endpoints still hold a worker
  for a whole discussion. Converting them is a JAX-RS-interface change; it is a separate piece.
- WS1 (configuration cache), then WS3a (one conversation load per turn, which also makes refused
  turns cheap).

---

## 🔒 fix(security): close the M9, M10, L6, L9, L10 and L12 findings of the 2026-10-15 review (2026-10-15)

**Repo:** EDDI (`fix/authz-lifecycle`)

### What changed and why

Six residual findings of the security review of `main` at `d49e855`, each a gap that
the live RBAC/IDOR tests did not reach. One commit per finding; the code comments
name the finding so a later reader can find this entry.

- **M9 — A2A JSON-RPC skipped the per-agent USE check.** `POST /a2a/agents/{agentId}`
  required a role, but not the `USE` grant that `POST /agents/{id}/start` and `/v1`
  apply under enforced workspaces, so any `eddi-user` could converse with any agent.
  `RestA2AEndpoint.handleJsonRpc` now calls `ResourceAccessGuard.requireAgentUseAccess`
  before dispatching `SEND` and `STREAM` — outside the method's catch-all, so the
  refusal is a 403 and not a JSON-RPC "internal error". `tasks/get` and `tasks/cancel`
  stay principal-scoped: they reach only a task this peer created after passing the
  gate. `A2aEndpointPermissionsTest` and `RestA2AEndpointTest` cover every send and
  stream method name in all three dialects.
- **M10 — GDPR erasure and export did not reach the A2A task store.** A peer's tasks
  hold the message it sent and the reply, keyed by its principal, and outlived an
  erasure until the cache expired. `IA2ATaskStore` gains `tasksOf` and
  `eraseForPrincipal` (the length-prefixed key makes a principal's prefix exact), and
  `A2ATaskGdprParticipant` registers both with `GdprComplianceService` as step
  `a2aTasks`. It also joins the cascade's stop phase: `beginErasure` closes the store
  to the principal's writes for five minutes, so a peer request admitted while the
  cascade runs cannot leave a task behind the sweep; on one node the check and the
  write are serialised against the sweep by a read/write lock, and in cluster mode the
  marker lives in a shared `A2A_ERASING` bucket so every node refuses the writes, while a
  shared per-principal `A2A_INDEX` lets a sweep or export on one node reach the records
  other nodes wrote (bucket keys are hashed, so nothing else can enumerate them). The index
  is kept with a compare-and-set loop — `SharedKvCache.replace(key, expected, next)` is now
  a revision-checked update on the bucket, not get-then-put — and caps a principal at
  10,000 tasks: an id that falls off the end takes its record with it. The one narrow
  cross-node residual is in [`clustering.md`](clustering.md#residual-limitations). [`gdpr-compliance.md`](gdpr-compliance.md) lists it and now has a
  section naming what erasure does **not** reach — RAG knowledge bases, application
  log files, telemetry exports and LLM-provider retention — with why, and what bounds
  each.
- **L6 — dead letters kept the raw turn input for seven days.** `describeTurn` runs
  before the pipeline, so the `scope: secret` scrub could not help, and only a client
  that set `context.secretInput` was spared. The captured input and its string
  context values now pass through `SecretRedactionFilter` — string leaves inside
  object values included, with credential-named keys masked — an entry the client
  marked `secret` or whose key names a credential is dropped, and a turn addressed to an
  agent whose workflows carry any `scope: secret` property instruction is recorded as
  `secretInput: true` without input or context, like a client-flagged one. The fact is
  computed once at deployment: `PropertySetterConfiguration.hasSecretInstructions()`,
  carried by `IExecutableWorkflow` and aggregated on `IAgent`, and read from the agent
  `say` already resolved for the turn — no second lookup on the hot path. A turn with
  no resolved agent is treated as one that expects secrets.
- **L9 — three ownership gaps on the internal REST surface.**
  `RestAgentManagement` never bound the `{userId}` path segment to the caller (its
  only check asked whether the caller was anonymous), and `initUserConversation`
  deleted the ended-conversation mapping before the engine's transitive 403; every
  method now runs `OwnershipValidator.validateUserAccess` first. The group write paths
  `followUp`, `continue` and `continue/stream` used `requireOwnerOrAdmin`, which admits
  any authenticated caller to a legacy record with no owner; they use
  `requireOwnerOrAdminStrict`, as delete and close already did. And an `eddi-approver`
  could cancel any user's *executing* turn through `POST /agents/{id}/cancel`: a caller
  who is only an approver now goes through `cancelPausedConversation`, a single
  compare-and-set on `AWAITING_HUMAN` that never signals an executing turn, so the
  entitlement check and the cancel are one atomic operation (any other state is a 403;
  documented in [`hitl.md`](hitl.md#who-may-decide)); the MCP `cancel_conversation` tool
  applies the same rule through `HitlAccessGuard.mayCancelExecuting`; owner and admin are
  unchanged.
- **L10 — unbounded listings and unbounded per-user attachments.**
  `?limit=2147483647` returned every descriptor in one response (verified live on
  `/agentstore/agents/descriptors`). `PageBounds` applies one ceiling —
  `eddi.rest.listing.max-limit`, default 1000, the Manager's largest descriptor page — to every
  config store's descriptor listing, the cross-resource `/descriptors`, the
  conversation listing (which used to clamp silently at 100), the visible user
  memories and both `/administration/logs` listings; a limit above it, or a negative
  index, is a 400 with a JSON `{"error": …}` body. `eddi.attachments.max-per-user` and
  `eddi.attachments.max-total-bytes-per-user` default to 500 files and 1 GiB instead of
  off ([`attachments-guide.md`](attachments-guide.md#quotas)).
- **L12 (remaining) — legacy salt and request logging.** A boot that adopts the
  legacy fixed KEK salt (DEKs present, no per-deployment salt) records the fact in the
  vault metadata (`vault-kek-legacy-salt-seen`); every later boot still on it logs an
  **ERROR** naming `POST /secretstore/secrets/admin/rotate-kek` and the date the salt
  was first adopted, where before there was one WARN per boot. A persisted or migrated
  salt clears the record. `logRequests` on the LLM decorators wrote 200 characters of
  the last message — the user's own text — at INFO; the line is now DEBUG and both it
  and the `logResponses` line run through `LogRecordRedactor` (before truncation, so a
  key straddling the cutoff cannot leave its first characters behind), which is public
  for that.

- **Flaky test fixed at its cause.** `ClusterConversationCoordinatorTest.localDeadLettersPage`
  failed CI on this branch (and passes or fails locally by chance): the local dead-letter id
  was drawn outside the lock that appends the entry, so concurrent failures could append out
  of id order, and the cursor paging over local entries — which skips by id number — then
  dropped or repeated one. The id is now taken under the lock;
  `InMemoryConversationCoordinatorTest.concurrentDeadLettersAreRecordedInIdOrder` pins it.

**Files:** [`RestA2AEndpoint.java`](../src/main/java/ai/labs/eddi/engine/a2a/RestA2AEndpoint.java),
[`A2ATaskGdprParticipant.java`](../src/main/java/ai/labs/eddi/engine/a2a/A2ATaskGdprParticipant.java),
[`CachedA2ATaskStore.java`](../src/main/java/ai/labs/eddi/engine/a2a/CachedA2ATaskStore.java),
[`ConversationService.java`](../src/main/java/ai/labs/eddi/engine/internal/ConversationService.java),
[`WorkflowStoreClientLibrary.java`](../src/main/java/ai/labs/eddi/engine/runtime/client/workflows/WorkflowStoreClientLibrary.java),
[`RestAgentManagement.java`](../src/main/java/ai/labs/eddi/engine/internal/RestAgentManagement.java),
[`RestGroupConversation.java`](../src/main/java/ai/labs/eddi/engine/internal/RestGroupConversation.java),
[`RestAgentEngine.java`](../src/main/java/ai/labs/eddi/engine/internal/RestAgentEngine.java),
[`PageBounds.java`](../src/main/java/ai/labs/eddi/utils/PageBounds.java),
[`VaultSaltManager.java`](../src/main/java/ai/labs/eddi/secrets/crypto/VaultSaltManager.java),
[`ObservableChatModel.java`](../src/main/java/ai/labs/eddi/modules/llm/impl/ObservableChatModel.java),
[`application.properties`](../src/main/resources/application.properties),
[`configuration-reference.md`](configuration-reference.md), [`gdpr-compliance.md`](gdpr-compliance.md),
[`hitl.md`](hitl.md), [`secrets-vault.md`](secrets-vault.md), [`langchain.md`](langchain.md).

---

## 🔒 fix(security): `?token=` opt-in for the Chat UI, Swagger UI gets its CSP, MCP discovery advertises the request's scheme (2026-10-15)

**Repo:** EDDI (`fix/chat-token-headers`)

Three findings of the 2026-10-15 security review of `main` at `d49e855`, verified
live against the packaged 6.6.0 jar: M6, L11 and L3.

### What changed and why

**M6 — the Chat UI took a bearer token from `?token=` unconditionally.** A crafted
link signs the widget in as whoever minted the token (login CSRF), and the first
request writes the token into the access log before the widget has stripped it
from the address bar. It is now an operator opt-in: `eddi.chat.url-token.enabled`
(default `false`) reaches the widget as `window.__EDDI_CHAT__` from a new
`GET /chat/__config__.js` on `RestHtmlChatResource`, loaded by `chat.html`
before the widget — the way the Manager reads `/manage/__auth_config__.js`.
Off, `readUrlToken` (new `ui/chat/src/api/chat-config.ts`) ignores the parameter
and logs a console warning naming the property; the parameter is stripped from
the address bar either way, and only a literal `true` switches it on. The
`postMessage` hand-off (`?tokenOrigin=`) and `ChatConfig.authToken` are
unchanged. `parseAllowedTokenOrigins` also warns about a plain-`http`
`tokenOrigin` that is not loopback: the token the host posts is only as private
as that page's transport. The Vite dev proxy forwards the config script so
`npm run dev` sees the real value with a backend up.

**L11 — `/q/swagger-ui/` was served with no CSP and no X-Frame-Options.** The
`csp-swagger` filter was configured but never ran. Quarkus hands a filter's
`order` to Vert.x `Route.order()` unchanged, and Vert.x runs routes in
*ascending* order (the Quarkus doc's "higher priority takes precedence" reads
the other way); the `/q` sub-router — health, metrics, Swagger UI — is mounted
on the same router as a route whose order is its creation sequence number.
Live, that number sat between 10 and 20: `/q/health/ready` got `csp-default`
(order 10) and `/q/swagger-ui/` got nothing from `csp-swagger` (order 20),
because the sub-router had already answered. All three CSP filters now use
negative orders (`-30`/`-25`/`-20`), which run before every sequence-ordered
route; verified live both ways in dev mode. `CspPolicyTest` pins the orders
negative, adds the Swagger paths and the other `/q` routes, and feeds the path
tricks through Vert.x's own `HttpUtils.normalizePath` (what the regexes are
matched against): `/chat/../manage`, `/%2Fchat`, `/CHAT`, `/chat;x=1/…` keep
the strict default policy; `//chat` (folded to `/chat`) and `/chat/..;/manage`
(not a dot segment; the chat catch-all serves `chat.html`) get the chat policy
and nothing else, so the browser never enforces an intersection.

**L3 — with OIDC on, a plain-http instance advertised
`https://localhost:7070/mcp`.** `quarkus.oidc.resource-metadata.force-https-scheme`
was pinned `true` for the TLS-terminating-proxy case, so every plain-http
deployment with authentication on had to remember to override it, and one that
did not handed MCP clients an identifier nothing served. It is now `false`: the
scheme follows the request — `http` on a plain-http instance, `https` over TLS,
and behind a proxy that forwards it (`quarkus.http.proxy.proxy-address-forwarding`)
the public one. A proxy that terminates TLS without forwarding the scheme gets
every other absolute URL EDDI builds wrong too; the fix there is the proxy
headers or the absolute `quarkus.oidc.resource-metadata.resource`, both already
documented. `McpOAuthDiscoveryConfigTest` replaces the pinned-`true` assertion
with one that drives quarkus-oidc's own `ResourceMetadataHandler` with the
values from `application.properties` and proves an http request yields an http
identifier and an https request an https one. The compose auth stacks, the k8s
auth overlay and the Manager's Keycloak E2E compose keep their explicit `false`
(now the default); only their comments changed. The one shipped stack that relied
on the forced `https` was the GCP VM (`gcp/provision-vm.sh`): its nginx terminates
TLS and proxies to EDDI over plain http, so its generated compose override now
sets `QUARKUS_OIDC_RESOURCE_METADATA_RESOURCE` to the public `https://<domain>/mcp`,
the absolute identifier [mcp-server.md](mcp-server.md) asks of every production
deployment (CodeRabbit, outside-diff finding). [upgrading-from-6.4.md](upgrading-from-6.4.md)
no longer tells plain-http deployments to set `force-https-scheme=false`.

**Files:** [`application.properties`](../src/main/resources/application.properties),
`src/main/java/ai/labs/eddi/ui/{IRestHtmlChatResource,RestHtmlChatResource}.java`,
`ui/chat/chat.html`, `ui/chat/vite.config.ts`, `ui/chat/src/api/chat-config.ts` (new),
`ui/chat/src/api/embed-auth.ts`, `ui/chat/src/components/ChatWidget.tsx`,
`CspPolicyTest`, `McpOAuthDiscoveryConfigTest`, `RestHtmlChatResourceTest`,
the Chat UI tests, [`chat-ui-embedding.md`](chat-ui-embedding.md),
[`security.md`](security.md), [`mcp-server.md`](mcp-server.md),
[`configuration-reference.md`](configuration-reference.md),
`docker-compose.auth.yml`, `k8s/overlays/auth/kustomization.yaml`,
`ui/manager/docker-compose.integration-keycloak.yml`.

### Design decisions

- **A script global, not a fetch.** The widget needs the flag before its first
  request, synchronously, at mount — a fetched JSON would race the `?token=`
  read. The Manager already solves this with `/manage/__auth_config__.js`.
- **Strictly `true`.** A missing, malformed or string-valued global reads as
  off: a bundle served without the backend, or a stale cached script, must not
  turn the vector back on.
- **Scheme from the request rather than a smarter default.** There is no config
  signal that distinguishes "plain http" from "TLS terminated upstream without
  forwarded headers"; the request's scheme is right in every configured
  deployment and wrong only in the misconfigured one, where it is already wrong
  everywhere else.

---

## 🔒 fix(deploy): close the CI/CD, Helm, compose and installer findings of the 2026-10-15 security review (2026-10-15)

**Repo:** EDDI (`fix/cicd-deploy-hardening`)

Findings M4, M5, L4 and L7 of the full security review run against `origin/main`
d49e855 on 2026-10-15. Config, workflows, manifests, installers and docs only; the
one Java change is to the two tests that read those files.

### What changed and why

**M4 — `auto-approve-copilot.yml` stood in for a human reviewer.**
`COLLABORATOR` is no longer a trusted author association (only `OWNER` and
`MEMBER`): a collaborator is anyone granted repository access, including an
outside account invited for one task. `PROTECTED_GLOBS` now also covers the
security-critical code — `engine/security/**`, `secrets/**`,
`engine/httpclient/**`, `connections/**`, `application.properties` and every
`*Guard*.java` — because a change there stays green on every check and can
still turn a control off. The workflow header states the assumption it runs
under: branch protection on `main` still requires one *human* approval, and the
bot only ever satisfies the second of two. That count is a repository setting
the workflow cannot read, so [`code-review-standards.md`](code-review-standards.md)
now says so under *Merge Policy*. `AutoApproveCopilotPolicyTest` moved
`application.properties` from its "ordinary" list to the protected one and
added the new paths. The review also named `.github/workflows/**`, `helm/**`,
`k8s/**`, `docker-compose*.yml` and the installers; those were already covered
by `.github/**`, `helm/**`, `k8s/**`, `**/docker-compose*.yml`, `install.sh`
and `install.ps1`.

**M5 — Helm put the Keycloak bootstrap admin password in a plain pod env value.**
[`keycloak.yaml`](../helm/eddi/templates/keycloak.yaml) renders it into a
`<release>-eddi-keycloak` Secret (key `password`, the kustomize overlay's shape)
and the StatefulSet reads it through `secretKeyRef` without `optional`;
`required` moved to the Secret, so the chart still ships no default. Every
credential the chart renders can now come from a Secret the operator manages:
`eddi.existingSecret`, `mongodb.existingSecret`, `postgres.existingSecret` and
`keycloak.existingSecret` each skip the chart's own Secret and point the
consumer at the named one (the keys each must carry are in
[`values.yaml`](../helm/eddi/values.yaml) and
[`kubernetes.md`](kubernetes.md#bringing-your-own-secrets-helm)).
`mongodb.existingSecret` requires `eddi.existingSecret` alongside, because the
chart cannot build EDDI's connection string without the root password — it
refuses to render otherwise rather than booting EDDI against a database it
cannot authenticate to. The chart also refuses `keycloak.enabled=true` together
with `ingress.enabled=true` unless `keycloak.devModeAcknowledged=true`, since
the in-chart Keycloak runs `start-dev`. NOTES.txt describes all of it, names
the Secret the password landed in, and carries the `start-dev` warning.
`DeploymentManifestsTest` asserts the new shape (secretKeyRef, no `value:`, no
`optional`, `required` on the Secret). Chart version 2.7.0 → 2.8.0.

**L4 — defaults that were safe only on a laptop.**
[`docker-compose.postgres-only.yml`](../docker-compose.postgres-only.yml)
requires `POSTGRES_PASSWORD` (it hard-coded `eddi`/`eddi`);
[`docker-compose.cluster.yml`](../docker-compose.cluster.yml) requires
`MONGO_PASSWORD` the way it already required `NATS_PASSWORD` (it defaulted to
`changeme`). Both installers generate `POSTGRES_PASSWORD` into `.env` for
`--db=postgres` and carry a stored value forward, like the Keycloak password,
because the postgres image reads it at initdb only. [`docker-compose.yml`](../docker-compose.yml)
keeps its laptop defaults but gains a one-shot `bind-guard` service that `eddi`
depends on: it exits 1 when `EDDI_BIND` is not loopback while
`EDDI_SECURITY_ALLOW_UNAUTHENTICATED` is `true`, unless
`EDDI_I_UNDERSTAND_PUBLIC=true`. It runs on the `mongo` image the stack pulls
anyway, so it costs no extra image and works offline and with the local build
overlay; `install.sh` performs the same check before pulling images, and the GCP
provisioner passes the acknowledgement for `--i-understand-public`. Documented in
[`docker.md`](docker.md#publishing-eddi-on-the-network). `k8s/quickstart.yaml`
carries the same loud banner the vault key has. **Its three
`*_ALLOW_UNAUTHENTICATED` flags stay `"true"`, deliberately:** the review asked
for the MCP and secret-store opt-outs to be `false` "like Helm does", but with
OIDC off `HighValueSurfaceGuard` refuses to boot unless both are opted in — Helm
renders `false` *and then refuses to render that release*. `false` in the
quickstart is not a safer manifest, it is a CrashLoopBackOff; the banner says
so and tells the reader that turning OIDC on is the only way to close them.

**L7 — CI and docs.** In [`ci.yml`](../.github/workflows/ci.yml) the `:latest`
Docker alias and the GitHub release's `make_latest` follow `is-stable`, and a
pre-release tag is published with `prerelease: true`; a `6.4.0-RC1` tag used to
move both. [`base-image-check.yml`](../.github/workflows/base-image-check.yml)
opens its PR with `BASE_IMAGE_BOT_TOKEN || CHANGELOG_BOT_TOKEN || GITHUB_TOKEN`
and carries the sibling workflows' PR-body note, because a PR opened with the
default token never runs `Build & Test` — the one check a base-image bump needs.
[`httpcalls.md`](httpcalls.md#running-behind-a-reverse-proxy) adds
`quarkus.http.proxy.trusted-proxies` to the proxy guidance: without it every
peer may send forwarded headers, which is the origin check `${caller:token}`
relies on. With an Ingress and OIDC on, the chart defaults
`QUARKUS_OIDC_RESOURCE_METADATA_RESOURCE` to `<scheme>://<ingress.hosts[0].host>/mcp`
instead of trusting each request's `Host`. Setting `keycloak.publicOrigin`
strips the `http(s)://localhost:*` redirect URIs and the `+` wildcard web origin
from the realm's `eddi-frontend` client (the realm is parsed and re-serialised
rather than string-edited; the `eddi-mcp` client keeps its loopback redirects).
`install.ps1`'s `Protect-SensitiveFile` fails with the `icacls` fix instead of
warning when the `.env` ACL cannot be set. `SECURITY.md`'s cosign regex uses the
strict tag pattern CI and `release-signing.md` use.

### Verification

`helm lint` and `helm template` (dockerised `alpine/helm:3.17.3`, helm is not on
PATH here) for the default, the CI shape with PostgreSQL and Keycloak, Keycloak
behind an Ingress with and without the acknowledgement, every `existingSecret` combination and the
`mongodb.existingSecret`-alone refusal; `docker compose config` for every edited
compose file plus the auth and local overlays, and the bind guard run under each
environment combination; `python -c "import yaml"` over every edited workflow
and manifest; `bash -n` on the shell installers and a parser check on
`install.ps1`; the deploy and docs unit tests via `./mvnw test`.

---

## test(coordinator): deterministic regression test for local dead-letter cursor paging (2026-10-15)

**Repo:** EDDI · **Branch:** `fix/dead-letter-cursor-paging`

### What changed and why

- **Background.** `ClusterConversationCoordinatorTest.localDeadLettersPage` failed CI
  twice on #1046 (`only the entry after the cursor: [] ==> expected: <1> but was: <0>`).
  The cause was in production: `recordLocalDeadLetter` took the local id before entering
  `deadLetterLock` but appended inside it, so two concurrent failures could land in the
  ring out of id order (`local-2` ahead of `local-1`). The cluster cursor skips local
  entries by number, so a page ending on `local-2` silently dropped `local-1` from the
  next page. The fix — taking the id under the lock — landed on `main` in #1040
  (`fix(coordinator): record local dead letters in id order`) while this branch was
  open; this branch carried the same fix and now contributes only the test.
- **Why another test.** #1040's `InMemoryConversationCoordinatorTest.concurrentDeadLettersAreRecordedInIdOrder`
  races eight writers and checks ring order, so it catches a regression only when the
  scheduler happens to interleave them, and it never pages.
  `ClusterConversationCoordinatorTest.concurrentLocalDeadLettersPageInIdOrder` forces the
  interleaving with latches — one failure is held inside `describe()` while a second one
  is recorded to completion — and then pages one entry at a time through
  `getDeadLetters(limit, after)`, the path operators use. Mutation-checked: with the id
  taken outside the lock it fails every run with the CI message (ring
  `[local-2, local-1]`).

---

## fix(ci): unblock main — shaded jsoup CVE in jinjava, gitleaks false positives, a time-of-day schedules test flake (2026-10-15)

**Repo:** EDDI · **Branch:** `fix/main-ci-jsoup-gitleaks`

### What failed

- **Docker Build & Push** on main (`f3d0fa596`, `37f8292fd`): the Trivy image scan
  reported one HIGH, CVE-2026-75140 (GHSA-65r4-943x-97jj, quadratic memory in jsoup's
  `XmlTreeBuilder`, fixed in 1.23.2) in `org.jsoup:jsoup` 1.15.3 inside
  `com.hubspot.jinjava.jinjava-2.8.4.jar`.
- **Secret Scanning** on main (`775edae28`, the squash of PR #956): gitleaks 8.30.1
  reported three `generic-api-key` findings.
- **UI Manager Checks** on main (`775edae28`): `schedules.test.tsx` "shows schedule
  names from mock data" threw "Found multiple elements" for `Daily Health Check`.

### What changed and why

- **`.trivyignore`** — the jsoup copy is *shaded*: jinjava's `maven-shade-plugin`
  bundles jsoup 1.15.3 into its own jar and relocates it to `jinjava.org.jsoup`, so a
  managed `org.jsoup:jsoup` version cannot replace it, and 2.8.4 is still the newest
  jinjava on Maven Central. jinjava is on the classpath only because `jlama-core`
  renders model chat templates with it, so it cannot be excluded without breaking the
  `jlama` provider. The vulnerable code is not reachable: jinjava's only jsoup users are
  the `closehtml`, `striptags` and `truncatehtml` filters, which use the HTML parser,
  and no jinjava or jlama class references the XML parser. EDDI's own direct jsoup is
  already 1.23.2. The entry carries `exp:2027-01-15` so it lapses and forces a re-check
  rather than silently outliving a jinjava fix.
- **`.gitleaksignore`** — the three findings are rows of the Manager's MSW cluster mock
  (`ui/manager/src/test/mocks/cluster-handlers.ts`, lines 83–85), whose `key` field is a
  NATS KV lease key made of `c.` and a made-up conversation id. Fingerprints for the
  squash commit `775edae28` were taken from a local run of gitleaks 8.30.1 (checksum
  verified against the release) over `37f8292fd..775edae28`, the range main's push scan
  checked. The `scripts/cluster-demo/demo.py` demo values were not flagged.
- **`ui/manager/src/pages/__tests__/schedules.test.tsx`** — a time-of-day flake, not a
  regression. The MSW mock gives Heartbeat Monitor `nextFire = now + 30 min` and Daily
  Health Check the next weekday 09:00 UTC, so from 08:30 to 09:00 UTC on a weekday
  Daily Health Check is the soonest schedule and its name renders a second time, in the
  Next Fire card. The test now uses `findAllByText`, like the Heartbeat Monitor line
  below it already did. The other tests naming either schedule were already safe
  (`schedules-features.test.tsx:171` reads an input inside the edit dialog; the Next
  Fire card test matches either name).

### Verification

- Trivy 0.70.0 (the version the pinned `trivy-action` installs) over the jinjava jar:
  1 HIGH without the entry, exit 0 with it.
- gitleaks 8.30.1 over `37f8292fd..775edae28`: 3 leaks before, none after.
- `schedules.test.tsx` with the clock faked to a Wednesday 08:45 UTC: the old assertion
  fails with "Found multiple elements", the new one passes (50/50); it also failed
  unfaked at 08:57 UTC on the day of the fix, and both schedules test files pass with
  the fix (65/65). `npm run typecheck` and `npm run lint` are clean.

### Decision

The `.trivyignore` entry is the last resort, taken because there is no fixed jinjava,
no override can reach a relocated copy, and dropping jinjava would remove the `jlama`
provider. Remove the entry when a jinjava release shades jsoup >= 1.23.2.

**Node-local dead letters kept in id order.** `ClusterConversationCoordinatorTest.localDeadLettersPage` failed once in CI on this branch, and the cause was real. `AbstractQueuedConversationCoordinator.recordLocalDeadLetter` numbered an entry before taking the ring's lock. Two conversations failing at once could therefore add `local-3` before `local-2`, and the cluster coordinator's paging, which skips every local entry numbered at or below the cursor, then dropped `local-2` from the next page. The number is now taken inside the lock. `InMemoryConversationCoordinatorTest.localDeadLettersAreHeldInIdOrderUnderConcurrentFailures` forces that interleaving and fails without the fix.

---

## 🔒 fix(mcp): govern remote tool schemas, pin the MCP transport to no redirects, enforce tool budgets by default, give the HITL MCP tools a role tier, widen the self-ungating guard (2026-10-15)

**Repo:** EDDI (`fix/mcp-governance`)

Findings M7, M2 (MCP transport), M8, L5 and L13 of the 2026-10-15 security review.

### What changed and why

**M7 — remote tool parameter schemas were not governed.** `McpToolProviderManager.governDescription` redacted and capped a remote tool's *description* and rebuilt the spec with `parameters(spec.parameters())` verbatim, so every property description, title and enum value in the server's `inputSchema` reached the model unredacted and unbounded; `A2AToolProviderManager` had the same shape. New `modules/llm/governance/ToolSchemaGovernor` walks a langchain4j `JsonSchemaElement` tree — objects, arrays, enums, `anyOf`, `$defs`, raw JSON-Schema fragments — and governs every text with `RemoteTextGovernor`, bounds the serialised schema per tool (`eddi.mcp.tool-schema.max-bytes`, default 32 KB: descriptions are stripped first, a schema still over the cap drops its tool) and per server (`eddi.mcp.tool-schema.max-bytes-per-server`, default 512 KB), refuses nesting deeper than 32 levels, and reports `redacted` / `truncated` / `dropped` / `dropped_server_cap` in `eddi_mcp_tool_schema_governed_total`. The A2A manager applies the same walk with `eddi.a2a.tool-schema.max-bytes` / `…-per-agent` and `eddi_a2a_tool_schema_governed_total` — its schema is built locally today, so this is symmetry rather than a live hole, but a card-sourced `inputSchema` is now governed from day one.

**M2 (MCP part) — a 3xx from a validated MCP URL could be followed.** Only the configured URL was validated and langchain4j's `StreamableHttpMcpTransport` built its `HttpClient` with `Redirect.NORMAL` in earlier releases, so a `307 Location: http://10.0.0.5/…` carried the session and every tool call's `Authorization` header to a host nobody checked. The transport is now built with `followRedirects(false)` explicitly — langchain4j-mcp 1.22 happens to default to that, but the builder flag exists precisely so the caller decides, and `McpTransportRedirectTest` holds the line against an embedded redirecting server on the loopback interface: the handshake fails on the 307 and the `Location` is never requested. `docs/security.md`'s SSRF note no longer says the MCP client follows redirects while protection is off.

**M8 — tool cost ceilings were recorded, not enforced.** `maxBudgetPerConversation` only refused calls with `enforceBudget: true`, and the deployment-wide `eddi.tools.budget.enforce-by-default` was never declared in `application.properties`, so every stored ceiling was advisory. The reason for the opt-in — built-in tools priced at $0.00 until their pricing was repaired, so a default-on gate would have bound for the first time on upgrade — has passed. The key is now declared with `true`, `AgentOrchestrator.BUDGET_ENFORCE_DEFAULT` falls back to `true`, `warnAboutUnenforcedBudgets` names the *opt-out* (`"enforceBudget": false` on the task, or the deployment key set to `false`) once per task instead of the absence of an opt-in, and the MCP `get_agent` and `deploy_agent` outputs carry `toolBudgetEnforcedByDefault` so an operator reading a stored ceiling sees whether it refuses. `AgentOrchestratorToolCostTest` and `AgentOrchestratorCoverageTest` now pin the opt-out contract (an unflagged ceiling refuses the third call; an opted-out one is warned about; an enforced one is silent). Note: `docs/llm-parameters.md` is generated from `model-parameters.json` and carries provider parameters only, so the budget text lives in `langchain.md`, `security.md` and `configuration-reference.md`.

**L5 — the MCP HITL tools had no role tier.** `McpHitlTools` relied on `HitlAccessGuard` alone, so over MCP an `eddi-viewer` — a role every REST HITL endpoint refuses at `@RolesAllowed` — could decide its own gated tool calls. New `McpRoles.HITL` (`eddi-admin`, `eddi-editor`, `eddi-user`, `eddi-approver`) is checked first on all ten tools, mutations and reads alike; the per-conversation guard still runs after it. `McpRoleTierParityTest` now measures the HITL tools against that tier (the no-role caller passes nothing any more, `eddi-viewer` is refused by every HITL tool) and asserts the tier equals the method-level `@RolesAllowed` of the nine REST HITL endpoints on `IRestAgentEngine` and `IRestGroupConversation`; `McpHitlToolsTest` adds the viewer refusal and the auth-off no-op.

**L13 — the self-ungating guard knew only the agent id; the pagination store was process-global.** `SelfUngatingGuard` now takes a `Protection` (acting agent, the ids of its workflows and of every extension resource those workflows reference, whether that resolution failed, and a stored-gate lookup). New `modules/llm/impl/ActingAgentResources` resolves the ids from the agent and workflow stores once per resumed batch in `ToolLoopResumer`; a write to any of them is refused (`OWN_RESOURCE_REASON`), and if the stores cannot be read every configuration-store write in the batch is refused (`UNRESOLVED_RESOURCES_REASON`) rather than guessed at. Agent-store writes to *other* agents are compared against the stored `hitlConfig.toolApprovals`: a full `PUT` that drops, loosens or adds it, and a `PATCH` naming `hitlConfig` or `toolApprovals`, are refused (`GATE_REMOVAL_REASON`); a faithful copy runs; without a lookup a body touching either key is refused. `ApprovedCallScope` carries the `Protection` on its thread-local, so the rule applies to the request as finally sent. Both id patterns accept a 24-hex ObjectId and a canonical UUID, so PostgreSQL deployments are covered, and a parser step's `extensions.dictionaries[]`/`corrections[]` resource URIs are protected like `config.uri`. Review also added to `approve_group_phase` the group USE gate REST's approve endpoint has (`requireResumeUseAccess`: admins and approvers decide by role, an owner whose group access was revoked cannot resume the run). `PaginatedResponseStore` is keyed by `(conversationId, responseId)`; `FetchToolResponsePageTool` is handed to each turn `boundTo` its conversation by `BuiltinToolsProvider` (the unbound catalog bean refuses every fetch), and `ToolResponseTruncator.truncateIfNeeded` takes the conversation — without one, `paginate` falls back to truncation and stores nothing.

### Files

- `modules/llm/governance/ToolSchemaGovernor.java` (new), `McpToolProviderManager.java`, `A2AToolProviderManager.java` — M7
- `McpToolProviderManager.java` (`createTransport`), `McpTransportRedirectTest.java` (new) — M2
- `AgentOrchestrator.java`, `ToolLoopRunner.java`, `LlmConfiguration.java`, `ToolBudgetEnforcement.java` (new), `McpConversationTools.java`, `McpAdminTools.java`, `application.properties` — M8
- `McpRoles.java`, `McpHitlTools.java`, `McpRoleTierParityTest.java`, `McpHitlToolsTest.java`, `McpHitlToolsCoverageTest.java` — L5
- `engine/hitl/tools/SelfUngatingGuard.java`, `ApprovedCallScope.java`, `modules/llm/impl/ActingAgentResources.java` (new), `ToolLoopResumer.java`, `AgentOrchestrator.java`, `PaginatedResponseStore.java`, `FetchToolResponsePageTool.java`, `BuiltinToolsProvider.java`, `ToolResponseTruncator.java` — L13
- Docs: `configuration-reference.md`, `mcp-client.md`, `metrics.md`, `security.md`, `langchain.md`, `mcp-server.md`, `hitl.md`, `clustering.md`

### Design decisions

- A schema over the byte cap loses its descriptions before its tool is dropped: the prose is the untrusted bulk, the structure is what the model needs to call the tool at all, and a partial structure would make it call with the wrong arguments.
- `followRedirects(false)` is pinned in EDDI rather than trusted to the library default, which changed between langchain4j releases.
- Budget enforcement is an opt-out named in a WARN, not an opt-in named in a WARN: a ceiling someone wrote down should refuse unless someone else says otherwise.
- The HITL role tier is checked before the per-conversation guard so a role refusal is a role error, as on every other MCP tool, and the mocked guard in the unit tests is never consulted for a viewer.
- A resolution failure in `ActingAgentResources` fails closed for configuration-store writes only, so an approved call to an external API still runs when the config stores are briefly unavailable.

---

## 🔒 fix(security): bound the expression parser, dictionary regexes and template string ops; refuse `saveResponse` without a name (2026-10-15)

**Repo:** EDDI (`fix/parser-bounds`) — findings M3, L8 and L2 of the 2026-10-15 security review

### What changed and why

**M3 — the expression parser was unbounded and reachable by any chatting user.** `ExpressionProvider` recursed once per nesting level with no cap and copied the remainder of the string at every level. A `context` entry of type `expressions` reaches it through `PropertySetterTask` and every `contextmatcher` rule; verified live, 20,000 nested parentheses threw a `StackOverflowError` that escaped `LifecycleManager`'s task-failure accounting, and a 3 MB payload cost ~10 s of CPU per request.

- New [`ExpressionLimits`](../src/main/java/ai/labs/eddi/modules/nlp/expressions/utilities/ExpressionLimits.java) bean: `eddi.expressions.max-length` (default 32,768 characters) and `eddi.expressions.max-depth` (default 32), checked in one linear scan before parsing; `0` disables a bound. Exceeding one throws `ExpressionLimitExceededException` (an `IllegalArgumentException`) with the measured value and the bound.
- [`ExpressionProvider`](../src/main/java/ai/labs/eddi/modules/nlp/expressions/utilities/ExpressionProvider.java) now parses on index ranges of the one input string — no per-level `substring` — and re-checks the depth where it descends, so no entry point bypasses the scan. The grammar is unchanged, quirks included (`ExpressionProviderBoundsTest` pins them). The limit exception is explicitly not swallowed by the parser's own error logging.
- REST boundary: `ConversationService.requireInputWithinLimit` — the gate every conversationId entry point, A2A and the OpenAI adapter already run — now also checks `context` entries of type `expressions` and refuses them with the existing `413 input_too_large` (`InputTooLargeException` gained a message constructor), so every adapter answers it without new plumbing. Inside a turn the exception is a task failure.
- [`LifecycleManager`](../src/main/java/ai/labs/eddi/engine/lifecycle/internal/LifecycleManager.java) catches `StackOverflowError` alongside `RuntimeException`, wraps it in an `IllegalStateException` and runs the unchanged failure accounting: error counter (`error.type=stack_overflow`), SSE `task_failed`, audit entry, strict-write rollback, `taskErrors` to the caller.

**L8 — editor-authored CPU cost.**

- Dictionary regexes: [`RegEx.match`](../src/main/java/ai/labs/eddi/modules/nlp/model/RegEx.java) runs through a deadline-checking `CharSequence` (new [`RegexDeadline`](../src/main/java/ai/labs/eddi/utils/RegexDeadline.java), the pattern `ArtifactValidators` already used) with a 200 ms budget that `RegularDictionary.lookupTerm` shares across all the patterns of one lookup (`IRegEx.match(lookup, deadlineNanos)`), so N slow patterns cost one budget per token, not N; a timed-out match is a miss, logged at WARN. With the parser's `maxInputTokens` (default 200) that bounds a turn's dictionary matching at 200 × 200 ms in the pathological worst case. [`RestDictionaryStore`](../src/main/java/ai/labs/eddi/configs/dictionary/rest/RestDictionaryStore.java) compiles every `regEx` on create, update, patch and duplicate and refuses an invalid one — or an entry with no `regEx` at all, which the provider used to drop silently — with `400` naming the problem, instead of failing the agent's deployment later.
- Template string ops: `str.replace` and `str.concat` in [`StringTemplateExtensions`](../src/main/java/ai/labs/eddi/modules/templating/impl/extensions/StringTemplateExtensions.java) compute the size they would produce and refuse it against `eddi.templating.max-output-chars` *before* allocating (for `replace`, whenever the replacement grows or the source is already over the bound) (new [`TemplateOutputLimit`](../src/main/java/ai/labs/eddi/modules/templating/impl/extensions/TemplateOutputLimit.java), a process-wide value the production `TemplatingEngine` publishes, since Qute invokes extensions statically). `TemplatingEngine.processTemplate` catches `OutOfMemoryError` with the exceptions and reports it — and a `TemplateLimitExceededException` anywhere in the cause chain — as a `TemplateEngineException` naming the bound.
- [`PathNavigator.evaluateExpression`](../src/main/java/ai/labs/eddi/utils/PathNavigator.java) caps an expression at 64 `+`/`-` operands (`MAX_OPERANDS`); `tokenize` stops at the cap rather than materialising every operand first, and the expression resolves to nothing. The vendored copy under `.clusterfuzzlite/` is synced. The longest-prefix search is quadratic in the operand count.

**L2 — `saveResponse: true` without `responseObjectName` was accepted and then failed every turn** with "Null key for a Map not allowed in JSON", leaving the conversation in ERROR. New [`ApiCallsConfigurationValidator`](../src/main/java/ai/labs/eddi/configs/apicalls/ApiCallsConfigurationValidator.java) refuses it at save time (`400`, naming the call); it is wired with one call from `RestApiCallsStore.validateBatchLimits`, which create, update, patch and duplicate all run. `ApiCallExecutor` derives one `saveResponse` flag per call that is `false` when the name is missing (logged once per call at WARN), so a config stored before the check skips the save instead of failing the turn.

### Design decisions

- **413, not 400, for an over-long or over-deep `expressions` context.** It is the same class of refusal as a too-long input and the same exception, so the seven entry points that already map `InputTooLargeException` answer it with no new plumbing. The message names the bound and the property.
- **The expression bounds are deployment properties, not agent config**: they are a safety floor against a client, not agent behaviour.
- **The regex budget and the operand cap are constants**, documented in [security.md](security.md#parser-and-matcher-bounds): nothing a dictionary legitimately matches takes 200 ms, and nobody writes 64 operands by hand.
- **The template pre-check is process-wide.** Qute calls extension methods statically, so the bound cannot travel with the engine instance; the production constructor publishes its configured value, tests get the shipped default. `BoundedResolvers.check` still enforces the per-engine value afterwards.

### Tests

`ExpressionProviderBoundsTest` (live payloads, both bounds at their boundary, every entry point, grammar parity), `LifecycleManagerStackOverflowTest`, `ConversationServiceTest` (expressions context), `RegExTest`, `RestDictionaryStoreRegExValidationTest`, `StringTemplateExtensionsOutputLimitTest`, `TemplatingEngineOutOfMemoryTest`, `PathNavigatorOperandCapTest`, `ApiCallsConfigurationValidatorTest`, `RestApiCallsStoreSaveResponseTest`, `ApiCallExecutorSaveResponseGuardTest`. Each bound was mutation-checked: with the fix reverted, its test fails. The one exception is the `OutOfMemoryError` catch clause itself: Qute hands the Error over wrapped in an exception, so the clause cannot be exercised from a unit test; the reporting it feeds is.

**Docs:** [configuration-reference.md](configuration-reference.md) (two new properties), [security.md](security.md#parser-and-matcher-bounds), [rest-api-reference.md](rest-api-reference.md), [httpcalls.md](httpcalls.md).

---

## 🔒 fix(security): `eddi.security.ssrf-protection.enabled` now covers LLM, embedding, vector-store and OpenAPI-discovery targets (2026-10-15)

**Repo:** EDDI (`fix/ssrf-coverage`; backend, docs)

Finding M2 of the 2026-10-15 security review.

### What changed and why

The SSRF switch governed httpcalls, MCP and A2A targets only. Every other endpoint an editor can write into a config — an LLM `baseUrl`, an Azure `endpoint`, an embedding endpoint, a pgvector `host`, a MongoDB `connectionString`, an OpenAPI spec location — was checked against the cloud instance-metadata service alone, protection on or off. With the switch on, an editor who could save an LLM config could still point it at any internal host; a knowledge base could open a raw connection to any host:port, EDDI's own MongoDB or PostgreSQL included, and pgvector would `CREATE TABLE` there. And `POST /apicallstore/apicalls/discover-endpoints` fetched the spec through swagger-parser's `RemoteUrl`, which follows up to five redirects with no check on where they lead.

- **One policy, `OutboundTargetPolicy`** ([`engine/httpclient/OutboundTargetPolicy.java`](../src/main/java/ai/labs/eddi/engine/httpclient/OutboundTargetPolicy.java)): the metadata service is refused always, as before; with the switch on, `UrlValidationUtils.validateUrl` runs too — http(s) only, no loopback/private/link-local address — with one escape hatch: a URL on this deployment's own origin (`eddi.self.base-url`, or the derived loopback address, via `SelfUrlResolver`) passes, because it is operator configuration and an agent calling EDDI's own `/v1` API is a supported arrangement. `validateHost` is the same rule for a bare `host[:port]` opened as a raw connection (no self escape hatch: EDDI's listener is never a database); an unresolvable host counts as private. Hosts on `eddi.security.ssrf-protection.allowed-hosts` (the httpcalls allow-list from #1033, parsed the same way) skip the private-address check on both paths, never the metadata check.
- **Every LLM builder with an endpoint** — OpenAI (and the eight OpenAI-compatible providers through it), Anthropic, Ollama, Mistral, Azure OpenAI, Gemini, Hugging Face, Vertex Gemini — field-injects the policy and runs its `baseUrl`/`endpoint`/`apiEndpoint` through it. A builder constructed with `new` (tests) behaves as with protection off.
- **`EmbeddingModelFactory`**: OpenAI, Azure, Ollama, Gemini, Mistral and Vertex endpoints. **`EmbeddingStoreFactory`**: Elasticsearch `serverUrl` and Chroma `baseUrl` as URLs; pgvector and Qdrant `host`, and every host a MongoDB connection string names (parsed by the driver's own `ConnectionString`, `mongodb+srv` seed names included), as bare hosts. With the switch on, the default `localhost` of pgvector, Qdrant, Elasticsearch, Chroma and Ollama is refused too: such a deployment must name a public address.
- **OpenAPI spec fetch** ([`engine/mcp/OpenApiSpecFetcher.java`](../src/main/java/ai/labs/eddi/engine/mcp/OpenApiSpecFetcher.java)): `McpApiToolBuilder.parseSpec` no longer calls swagger-parser's `readLocation`. The document is fetched by EDDI through `SafeHttpClient.sendNoRedirect`, redirects followed by hand (at most five, GET only, no credentials) with every hop held to the policy, the body read through `BoundedBodyReader` and refused above 16 MB while streaming. The text is handed to `readContents` with the location as its base, so a multi-file spec's relative `$ref`s resolve as before. `RestApiCallsStore` and `AgentSetupService` inject the fetcher; the fetcher-less `parseSpec`/`parseAndBuild` overloads remain for inline specs and tests and fetch with protection off.
- **`AgentSetupService.createApiAgent`**'s `llmBaseUrl` goes through the same policy, so a URL the model build would later refuse fails at the wizard, before any resource exists.
- **Docs**: [`security.md`](security.md) SSRF section lists the covered paths and the residual; [`langchain.md`](langchain.md) OpenAI-compatible `baseUrl` note; [`configuration-reference.md`](configuration-reference.md) row for the switch.

### Verified, no change needed

`EmbeddingStoreFactory.physicalName` cannot be coerced into another location at build time: a `${...}` reference in the raw `table`/`collectionName`/`indexName` is refused outright, a pgvector name must be a plain (optionally schema-qualified) identifier because the store puts it into SQL unquoted, and the `eddi_kb` prefix is refused at save time (`KnowledgeBaseStorageGuard`). A literal name equal to one of EDDI's own collections or tables is not refused — there is no central registry of those names to check against — but it only matters when the connection string names EDDI's own database, which is a private host and therefore refused when the switch is on. With the switch off, reaching internal databases is the documented choice.

### Residual

`$ref`s inside a fetched spec are still resolved by swagger-parser's own resolver (`setSafelyResolveURL(true)`), which checks each reference's initial URL but follows that URL's redirects itself. Disabling resolution would break multi-file specs; the spec document itself — the editor-controlled location — is now fetched safely.

---

## fix(security): refuse templated hosts without SSRF protection, and only admins may write `${caller:token}` (2026-10-15)

**Repo:** EDDI (`fix/ssrf-default-caller-token`; backend, docs)

### What changed and why

The two High findings of the 2026-10-15 security review.

**H1 — templated httpCall hosts with SSRF protection off.** `eddi.security.ssrf-protection.enabled` defaults to `false`, and an httpCall's authority may be a Qute template whose rendered value keeps dots and a port. A property is routinely captured from user input, so `https://{properties.apiHost}` let the chatting user steer a call to `127.0.0.1:7070` (EDDI's own admin API) or any RFC1918 address; the review reproduced the loopback reach live.

- **A templated scheme, host or port is refused while protection is off.** `ApiCallExecutor.rejectTemplatedAuthority` checks the URL before rendering (a `{` in the authority that is not part of a `${vars:…}`/`${vault:…}` reference); `ApiCallsTask.configure` calls it for `targetServerUrl` and every full-URL call path, so the agent fails to deploy with a `WorkflowConfigurationException` naming the flag, and `buildRequest` applies the same check per turn, which also covers httpcall tools. With protection on, templated hosts work as before and the rendered host is validated.
- **`eddi.security.ssrf-protection.allowed-hosts`.** Comma-separated exact `host` or `host:port` entries (case-insensitive, no wildcards, a bare host matches any port). With protection on, a listed host skips the private-address check but never the cloud-metadata check, and redirects stay off. Lets a self-hoster turn protection on and keep named internal APIs. httpCalls only; MCP and A2A are unchanged (review finding M2 covers them).
- **Default kept `false`, with a production WARN.** The rule-based reference agent and the integration tests target `http://localhost:7070`, so defaulting to `true` would break them. `AuthStartupGuard.ssrfDiagnostic()` logs a `[SECURITY]` WARN at production startup while protection is off, naming the flag and the allow-list.

**H2 — `${caller:token}` as a confused deputy.** `CallerIdentityResolver` releases the bearer token of whoever is chatting to the caller's origin or EDDI's own address, without regard to who authored the config. Editors may save `${caller:token}` and deploy, so an editor-authored call to `/administration/…` or `/secretstore/…` ran with the privileges of the next admin who chatted with the agent.

- **New `CallerTokenAuthorPolicy`.** With `authorization.enabled=true`, storing a document that contains `${caller:token}` (or `{caller:token}`) requires one of `eddi.caller-identity.author-roles` (default `eddi-admin`); anyone else gets a 403 with a JSON `error` naming the property. It scans the serialized document, so it covers httpcall headers, MCP `apiKey` and anything later added, without tracking fields. `${caller:userId}` stays open to editors.
- **Released only from the configuration as written.** `ApiCallExecutor` refuses a header whose `${caller:token}` arrives through a `${vars:…}` or `${vault:…}` value (`GlobalVariableResolver` allows a variable whose whole value is a reference). The save-time scan cannot see through a variable, and a variable can be edited after the config was saved, so this is enforced at execution. The MCP `apiKey` path never resolves a caller reference produced by a variable, so it needed no change.
- **Applied at every write.** `RestApiCallsStore` and `RestLlmStore` create/update/patch/duplicate, `RestMcpCallsStore` create/update/patch/duplicate, `RestImportService.createResourceDirect` (ZIP create/merge), and `UpgradeExecutor.createExtension` (upgrade and instance sync); merge and upgrade updates go through the REST stores. The check throws rather than returning a response, because the import treats a non-200 update as "create it directly instead". An import refusal fails the whole import with the 403 and rolls it back.
- **Docs.** [`security.md`](security.md#ssrf-protection--urlvalidationutils), [`configuration-reference.md`](configuration-reference.md), [`httpcalls.md`](httpcalls.md#who-may-write-callertoken) ("Who may write `${caller:token}`"), [`upgrading-from-6.5.md`](upgrading-from-6.5.md).

### Design decisions

- **Refuse the templated authority, not the flag default.** Defaulting protection on breaks every configured internal target at once; refusing only the template closes the path the end user controls and leaves editor-written literals, which the WARN now surfaces.
- **Check at write time, not at release time.** The resolver knows the chatting user, not the author; the write is the only point where the author is known.
- **Whole-document text scan.** One rule for every config type, no field list to keep in step. A literal `{caller:token}` in, say, a prompt is refused for editors too; acceptable for a credential reference.
- **Platform Operator unchanged.** Its prompt does not discuss caller identity or SSRF, and it is provisioned by an admin, who passes the check.

### Known limitations

- A merge import by a non-author is refused before its first write when it would replace a stored version containing `${caller:token}` (`CallerTokenAuthorPolicy.requireMayRestore`): its rollback restores through the same stores, which would refuse that version and leave the import's content in place.
- An upgrade or instance sync reports a refusal as that resource failing rather than a 403, like every other per-resource failure there: an upgrade is not transactional, and aborting would leave earlier resources written.
- With protection off, editor-written literal internal targets remain reachable by design; turn protection on and use the allow-list to close that.

---

## 🔒 fix(vault): no default grant on create, per-secret `allowedHosts`, vault-backed `${vars:}` credentials, wider scrubbing (2026-10-15)

**Repo:** EDDI (`fix/vault-grants`)

Findings M1, L1 and L12 (partial) of the 2026-10-15 security review.

### M1 — vault secrets were granted to every agent by default, and `${vault:…}` had no destination allow-list

- **A create needs a grant.** `PUT /secretstore/secrets/{tenantId}/{keyName}` used to pass a missing `allowedAgents` straight through, and `VaultSecretProvider.storeIfAbsent` / the persistence layer turned it into `["*"]`. Under `eddi.vault.grant-enforcement=enforce` (the shipped default) a create without the list is now a **400** that tells the operator to pass the agent ids or `["*"]` explicitly — the rule `PUT …/grant` already had. `warn`/`off` keep the convenience, written out as `["*"]` at the REST layer instead of left to a default. A rotation may still omit the list and keeps the stored grant. The space-scoped store (`RestSpaceResources.storeSecret`) applies the same rule through the shared [`VaultGrantRequests`](../src/main/java/ai/labs/eddi/secrets/VaultGrantRequests.java). `VaultSecretProvider.store` (on an insert) and `storeIfAbsent` now throw `IllegalArgumentException` on a null grant in every mode, so no code path reaches the persistence default: the setup wizard already passed `List.of("*")` with its reason next to it, `SecretPropertyVault` passes the agent's id, `AgentSigningService` the agent's id.
- **Per-secret `allowedHosts`.** New optional field on `SecretMetadata`/`EncryptedSecret`, stored by Mongo (`allowedHosts`) and Postgres (`allowed_hosts JSONB`, added with `ALTER TABLE … ADD COLUMN IF NOT EXISTS`); null or empty is "any destination", which every existing row has. Set with the value on `PUT /{tenantId}/{keyName}` or without it on `PUT …/grant` (`ISecretProvider.updateAllowedHosts`, `ISecretPersistence.updateSecretAllowedHosts`). Entries are exact `host` or `host:port`, validated and lower-cased by `VaultHostAllowlist.validatedHosts`, at most 100. Enforced by the new [`VaultHostAllowlist`](../src/main/java/ai/labs/eddi/secrets/VaultHostAllowlist.java) at the two places a reference is resolved for an outbound destination: `ApiCallExecutor` collects every `${vault:…}` reference the request carried (path, body, headers, query) in a `ResolvedSecrets` accumulator and checks them against the fully resolved target URI just before `BuiltRequest` is returned — a field-injected hook, so the constructor the SSRF/caller-token session is editing is untouched — and `ChatModelRegistry` checks the builder parameters against the configured `baseUrl`/`endpoint`/`url`/`serverUrl`/`host` before resolving the vault references, on both the sync and the streaming path. A refusal names the reference, the destination and the list (never a value) and the grant call that fixes it; a list that cannot be read fails closed. Not covered: a configuration without an endpoint parameter (the vendor default, which an editor cannot choose), and the embedding/vector-store, MCP and A2A resolution paths — stated in the docs.
- The Manager's two create flows now send the grant explicitly: the Secrets page sends `["*"]` where it used to omit the list for "all agents", and the secret picker's inline create grants the agent it was opened for (`agentId`) or `["*"]`. The Manager does not edit `allowedHosts` yet.

### L1 — `${vars:…}` accepted wherever a vault reference is required

`ConnectionConfiguration` accepts `${vars:x}` for `oauth.clientSecret` and `staticAuth.passwordRef`, and `/variablestore/variables` is editor-readable, so an OAuth client secret could live in plaintext global variables. A variable in a credential field may now only hold a `${vault:…}` reference: `CredentialReferenceResolver.resolveVaultBacked` (used by `OAuthTokenService`, `RestConnectionAuthorization` and the BASIC password path of `ConnectionResolver`) refuses at use with `INVALID_CONFIGURATION` when the expansion is not exactly one vault reference, and `resolveRequired` (the `valueTemplate` path) refuses when a variable expands to anything `ConnectionConfiguration.isAcceptableValueTemplate` would not accept — scaffolding like `Bearer ` is fine, a key is not. `RestConnectionStore` runs the same checks at save through `requireVaultBacked` and, for `staticAuth.valueTemplate`, `requireAcceptableTemplate` (a variable that does not exist yet is let through; the use-time check closes that). No message quotes the expansion.

### L12 (partial) — scrubber field names and LLM config read-back

- `SecretScrubber` now also treats `pwd`, `pass`, `passphrase`, `pin`, `otp`, `pem`, `privateKeyPem` as credential field names, and `pwd`, `passphrase`, `privateKey`/`private_key` as credential suffixes; `pin`/`otp`/`pem` are deliberately whole-name only (`spin`, `item`, `poem` stay). Tests use zero-entropy values so the name rule is what they exercise.
- `RestLlmStore.readLlm` returns a non-administrator a copy with plaintext credential parameters replaced by a marker, as `RestConnectionStore.readConnection` does; an administrator still reads the document as stored so the literal can be found and vaulted.

### Design decisions

- `allowedHosts` is enforced at resolution time, unlike `allowedAgents` (deploy time), because the destination is only known then. The ApiCallExecutor hook is field-injected and null-guarded only for direct construction in tests; under CDI it is always present.
- The grant endpoint writes the host list as a separate, unconditional write — it is independent of the agent grant, so `expectedAllowedAgents` does not cover it — and writes it **first**: if the grant write then fails, the row is left in its tighter state (narrowed hosts without the widened grant) rather than the reverse.
- `ChatModelRegistry` checks every endpoint key that is set (`baseUrl`, `endpoint`, `url`, `serverUrl`, `host`), not only the first: which one a builder reads depends on the provider.
- `RestLlmStore`'s read redaction treats a value as a reference or template only when it has that shape (`${scheme:…}` or `{name…}`), so a literal credential containing a brace is still redacted.
- The Platform Operator cannot create secrets (no `secretstore` PUT in its allow-list) and its prompt does not describe the grant default, so neither the prompt nor the revision changes.

**Tests:** `VaultHostAllowlistTest`, `RestSecretStoreGrantOnCreateTest`, `VaultSecretProviderAllowedHostsTest`, `ApiCallExecutorAllowedHostsTest`, `ChatModelRegistryAllowedHostsTest`, `CredentialReferenceResolverVaultBackedTest`, `RestConnectionStoreVaultBackedVarsTest`, `SecretScrubberFieldNamesTest`, `RestLlmStoreReadRedactionTest`; existing tests updated for the five-argument `store` and the explicit grant. The grant-on-create and scrubber tests were mutation-checked by reverting the fix.

**Docs:** [`secrets-vault.md`](secrets-vault.md) (grant on create, new *Destination allow-list* section, endpoint table), [`connections.md`](connections.md) (vault-backed `${vars:}`).

---

## 🔒 fix(vault): enforce `allowedHosts` for embedding, vector-store, MCP and A2A references; edit it in the Manager (2026-10-15)

**Repo:** EDDI (`fix/vault-allowed-hosts-coverage`)

Follow-up to M1 of the 2026-10-15 security review. `fix/vault-grants` added a per-secret `allowedHosts` list but enforced it only where `ApiCallExecutor` and `ChatModelRegistry` resolve `${vault:…}`; [`secrets-vault.md`](secrets-vault.md#destination-allow-list-allowedhosts) listed the other resolution paths, and the Manager, as gaps. This closes them.

### Backend

- **`EmbeddingModelFactory`** checks the embedding parameters against **every** endpoint key present (`baseUrl`, `endpoint`) before the references are resolved — checking only the first would let an Azure config carry an allowed `baseUrl` while the builder reads an off-list `endpoint` (CodeRabbit on #1043; #1037 made the same correction in `ChatModelRegistry`). No endpoint parameter means the vendor's own endpoint and is not checked.
- **`EmbeddingStoreFactory.resolveParams`** collects the references, resolves them, and checks every destination the store builder will connect to — `destinationsOf(storeType, params)`: pgvector and Qdrant `host:port` with the builders' defaults, the Elasticsearch `serverUrl`, the Chroma `baseUrl`, and each host of a MongoDB Atlas connection string. For `mongodb+srv://` that host is the SRV name: the driver only accepts SRV-resolved hosts under that name's parent domain, so listing the SRV name trusts whoever controls that DNS zone — stated in the docs rather than refusing SRV strings, which is how Atlas is normally addressed. *After* resolution on purpose: the connection string is commonly the secret itself, so its hosts are unknown until it is decrypted, and resolving is local — the check still runs before anything connects. An unparseable connection string yields a destination no list matches.
- **`McpToolProviderManager`**: new `validateSecretDestination`, run with the other pre-contact checks in `discoverTools` and `resourceBridgeTools`, so a refusal is reported as `INVALID_CONFIGURATION` and kept away from the circuit breaker (it rethrows `HostNotAllowedException` as `IllegalArgumentException`); `createTransport` checks again immediately before resolving, since a transport is also built for a tool call. Caller- and connection-bound keys are not vault references and are skipped.
- **`A2AToolProviderManager.applyCredential`** checks against the request's own `agentUrl` — for a task call that is the endpoint the peer's agent card announced, which need not be the configured host. Discovery already treats an `IllegalStateException` as a credential problem, not a breaker failure.
- All four field-inject `VaultHostAllowlist` (null only in tests that construct them directly) rather than adding a constructor parameter: `fix/ssrf-coverage` changes the embedding factories' constructors, and the MCP/A2A ones have several back-compat overloads.
- **`RestSecretStore.updateGrant`** now calls `secretResolver.invalidateCache(ref)` when `allowedHosts` is written. Models, embedding models/stores and MCP clients check the list when they are built and are then cached; without the eviction their invalidation listeners perform, a narrowed list would not reach a client already built. The agent-grant-only edit still does not invalidate (unchanged reasoning).

### Manager

- `SecretMetadata`, `SecretGrantRequest` and `SecretGrantResponse` carry an optional `allowedHosts`; `updateSecretGrant` and `useUpdateSecretGrant` pass it through only when given. `validateAllowedHost` mirrors the backend's shape check (no scheme, path or wildcard; `host` or `host:port`).
- The Secrets page shows a restricted secret's hosts under its agent chips (`allowed-hosts-<key>`); an unrestricted one shows nothing.
- The Access dialog (`EditGrantDialog`) edits the list with the shared `ChipInput`: entries are lower-cased, a URL is refused as it is typed, text still in the box is folded in on Save, and the field is sent only when it changed — removing every host sends `[]`, which lifts the restriction.
- **Narrowing warns.** Adding a first host or dropping a stored one can stop a deployed configuration at its next call, which the agent-impact dry run cannot see, so the dialog shows a warning and holds Save until the operator ticks it — keyed on the list it was given for, as the agent acknowledgement is. Widening or lifting the list needs no tick. The dialog's "only who may use it changes" line now also says where it may be sent.
- MSW: `google-gemini-key` is seeded with `allowedHosts`, and the grant handler stores and echoes it like the backend. Eight new keys under `secrets.*` and the revised `secrets.grantValueUntouched`, in all 11 locales.
- No operator change: the operator cannot create or edit secrets, and a refusal message names the grant call that fixes it.

### Tests

`VaultAllowedHostsCoverageTest` (13, one `@Nested` class per path, real `VaultHostAllowlist` over a mocked provider) and two assertions in `RestSecretStoreGrantOnCreateTest` (invalidation on a hosts write, none on a grant-only edit). Mutation check: with the five new calls disabled, 7 tests fail — every path's refusal test and the invalidation assertion. `secrets-grants.test.tsx` gains eight Manager tests. `VaultAllowedHostsCoverageTest.everyEndpointKeyChecked` fails with the first-key `return` put back.

---

## ✨ feat(operator): the operator can look up the running EDDI version and git commit (2026-10-13)

**Repo:** EDDI (`feat/operator-build-version`)

### What

An operator should always know which EDDI build it runs on, including after the
deployment is upgraded. The Platform Operator's system prompt is written at activation and
its tools are generated from the OpenAPI spec, so nothing it was given can say what is
running *now*. It now has a live lookup:

- `BuildInfo` (`engine/runtime`) is the single source: version from
  `systemRuntime.projectVersion`, commit from `eddi.build.commit` (env `EDDI_BUILD_COMMIT`).
  Only a hex string is accepted as a commit; anything else reports `unknown`, because the
  value reaches an LLM through the tool.
- `GET /administration/version` (`IRestBuildInfo`, `eddi-admin`) returns
  `{version, commit, commitShort}`.
- `get_eddi_version` MCP tool (`McpVersionTools`, `eddi-admin`), added to the `McpToolFilter`
  whitelist and `McpToolFilterCoverageTest`.
- An `EDDI <version> (commit <sha>)` startup log line. (A Micrometer info gauge was left out:
  `MetricsDashboardCoverageTest` requires every meter on the generated dashboard and in
  `docs/metrics.md`, which is more than this change warrants.)
- Dockerfile: `ARG EDDI_COMMIT` -> `ENV EDDI_BUILD_COMMIT` and the
  `org.opencontainers.image.revision` label. `ci.yml` passes `--build-arg EDDI_COMMIT=${{ github.sha }}`.
- Manager: `GET /administration/version` added to the operator's `READ_ENDPOINTS`, and one line
  in its system prompt telling it to call the tool instead of answering from memory.

### Design decisions

- **Commit via Docker build arg, not a Maven git plugin.** The jar stays free of per-commit
  stamps (see `docs/build-reproducibility.md`) and no new build dependency is introduced.
  The cost: a jar run outside the image reports `commit: unknown` unless `EDDI_BUILD_COMMIT` is set.
- **Live endpoint rather than text in the operator's prompt.** An upgrade replaces the image;
  it does not rewrite the stored operator agent, so anything baked in at activation goes stale.
- Raising the Manager's backend floor: operator activation now requires an EDDI that serves
  `/administration/version`. Manager and backend ship in one jar, so this only matters for a
  Manager pointed at an older backend.

### Not done

- Manager vitest was not run locally (no `node_modules` in this checkout); CI runs it. Backend:
  `BuildInfoTest`, `McpToolFilterCoverageTest`, `ImportStyleTest` pass.

### Also in this change

- `ui/manager/src/test/mocks/openapi-operations.json` refreshed from the `OpenAPI Snapshot` job's
  artifact, and `operator-revision.json` bumped to revision 9 (the operator's prompt and allow-list
  changed, which is what makes an operator update reach existing deployments).

---

## Decision Log

_For recording decisions that come up during implementation that aren't in the plan._

| Date       | Decision                                                              | Context                               | Alternative Considered                                      |
| ---------- | --------------------------------------------------------------------- | ------------------------------------- | ----------------------------------------------------------- |
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
