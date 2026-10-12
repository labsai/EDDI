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
| [September 2026](changelog/2026-09.md) | 188 | 1105 KB |
| [August 2026](changelog/2026-08.md) | 212 | 837 KB |
| [July 2026](changelog/2026-07.md) | 147 | 648 KB |
| [June 2026](changelog/2026-06.md) | 26 | 67 KB |
| [May 2026](changelog/2026-05.md) | 34 | 76 KB |
| [April 2026](changelog/2026-04.md) | 104 | 220 KB |
| [March 2026](changelog/2026-03.md) | 59 | 183 KB |

The two running registers — **Decision Log** and **Regression Notes** — live at the
bottom of this file and are never archived.

---

## 📈 fix(metrics): static files no longer exhaust the HTTP metrics URI tags (2026-10-02)

**Repo:** EDDI (`fix/http-metrics-uri-cardinality`)

### The bug

`http.server.requests` tags each request with its URI, and Micrometer registers at most
`quarkus.micrometer.binder.http-server.max-uri-tags` distinct values. After that, every new URI is
denied and gets no request metrics, with a single WARN to show for it. REST endpoints are tagged by
template, but Quarkus tags a static file by its literal path, and the image ships 816 content-hashed
Manager chunks under `/assets` plus 65 fonts. A few minutes of clicking through the Manager on the
6.5.0 release candidate used up the cap of 200. Every API endpoint first called after that went
unmeasured.

The cap was also too small without the assets: the API has 265 distinct paths (the Manager's
OpenAPI snapshot). The value came in with an unrelated commit in 2022 and had never been sized.

### What changed

- [`application.properties`](../src/main/resources/application.properties):
  `match-patterns` folds `/assets/*`, `/fonts/*`, `/scripts/*` and `/img/*` into one tag per
  directory (`/assets/{file}` …). `max-uri-tags` goes from 200 to 500.
- `HttpMetricsUriTagsConfigTest` (new) parses the patterns the way Quarkus 3.40 does (first `=`,
  trimmed, whole-path `matches()`, read from the bytecode of `HttpBinderConfiguration` and
  `RequestMetricInfo`). It checks that representative asset, font, script and image paths fold, that
  REST and UI-shell paths are left alone, and that the cap stays at least 100 above the path count
  in `ui/manager/src/test/mocks/openapi-operations.json`. As the API grows into the headroom, this
  test fails before production does.
- [`metrics.md`](metrics.md) explains the `uri` values and the cap.

### Verified

On the CI image of 1150bc1e1, with the two settings passed as environment variables, I requested
all 881 static files the jar ships and then the UI shells and an API call. That produced 13 distinct
`uri` tags and no cap warning. Without the settings, a handful of Manager pages produced 95.

---

## 🖱️ fix(manager): the Workforce onboarding page, confirm dialogs and the command palette scroll again (2026-10-02)

**Repo:** EDDI (`fix/workforce-onboarding-scroll`)

### What was broken

- **The Workforce landing page could not be scrolled** (reported against 6.5.0). `workforce-main` clips its
  content on purpose: every Workforce page brings its own scroll container (the scroll contract in
  `workforce-scroll-contract.test.tsx`). `WorkforceDashboard` wrapped its populated view in one, but returned the
  empty state, `<OnboardingHero />`, bare. The hero is 1406px tall, so on a 1280×640 window everything below the
  how-it-works cards was out of reach, including the templates a first-time user needs to create a task force.
  The contract test missed it twice: its mock always returns task forces, and an empty render was accepted as
  "cannot overflow". The loading and error states had no scroller either.
- **`AlertDialog`, the shared confirm dialog, had no height cap.** It is fixed and centred with a transform, so
  content taller than the window ran off both edges with nothing able to scroll it. In a 375px landscape-phone
  window the Undeploy prompt measured −40px to 416px: Close above the screen, Cancel and Undeploy cut off at the
  bottom. Every confirm dialog shares the primitive; the ones with extra content (undeploy, agent delete,
  conversations, groups, operator upgrade) hit it first.
- **The command palette's list was a fixed 360px** below a 15% offset. With the search row, footer and borders
  (86px, measured), the palette ran past the bottom of any window under about 525px tall: its footer at 310px in a
  300px window, where scrolling the list cannot reach it.

### What changed

- [`workforce-dashboard.tsx`](../ui/manager/src/pages/workforce/workforce-dashboard.tsx): the empty, loading and
  error states are each a `flex-1 min-h-0 overflow-y-auto` scroller, like the populated view.
- [`alert-dialog.tsx`](../ui/manager/src/components/ui/alert-dialog.tsx): `max-h-[calc(100dvh-2rem)]
  overflow-y-auto`, the same cap `AccessibleDialog` already had.
- [`command-palette.tsx`](../ui/manager/src/components/shared/command-palette.tsx): the list is capped at
  `min(360px, calc(85dvh - 6rem))`. The dialog starts at 15%, so 85dvh remain for all of it, and 6rem covers the
  86px of fixed parts with a 10px margin. Measured in Chromium: the palette fits at 200px (30–189px), 300px
  (45–289px) and 800px (unchanged at 360px). A first attempt at `min(360px, 60dvh)` ignored the footer and still
  overflowed below about 370px; CodeRabbit caught it in review.
- Tests: the scroll contract gains a case that renders the dashboard with **zero** task forces and requires the
  onboarding hero (new `data-testid="workforce-onboarding-hero"`) to sit inside a scroller. A second case
  holds the request pending and requires the same of the loading skeleton (`workforce-dashboard-skeleton`); the
  page table never holds that state still. A new `alert-dialog.test.tsx` pins the dialog's cap and scroller, and
  the palette test pins its list cap. The last two were added at CodeRabbit's suggestion. Each was
  mutation-checked: removing the fix fails it.

### How the rest was ruled out

A detector run in the browser against the 6.5.0 image, over all 48 Manager and Workforce routes at 1280×640,
375×812 and 1280×480, with seeded data (two agents, a group, a conversation). For every visible element below the
fold, it checks whether some scroll container, or the document, can bring it into view. The only page-level failure
was the onboarding hero, and the detector caught it as a positive control (65 unreachable elements under
`#workforce-main`). Dialogs and sheets were checked by reading the overlay primitives: `AccessibleDialog`, the
agent performance sheet, the mobile sidebar and the version diff already cap and scroll. Both fixes were then
confirmed live: the patched Manager ran against the same backend, the hero scrolled to its last template, and the
Undeploy dialog fit the 375px window and scrolled to its buttons.

Not covered: states the seed data does not produce, such as a Workforce board with a long discussion or analytics
with data. Those render inside scrollers the contract test already requires.

---

## 🔖 chore(release): after 6.5.0 (2026-10-01)

**Repo:** EDDI (`chore/post-release-6.5.0`, opened by `post-release.yml` after the release pipeline published the image)

### What

- Release pointers: 6.4.0 → 6.5.0 (Helm chart 2.3.0 → 2.4.0)
- Build version (pom.xml): 6.5.0 → 6.6.0

Generated by `python scripts/bump-version.py post-release 6.5.0`. Files:

- `pom.xml`
- `docs/build-reproducibility.md`
- `docs/developer-quickstart.md`
- `docs/kubernetes.md`
- `docs/redhat-openshift.md`
- `docs/release-signing.md`
- `k8s/base/kustomization.yaml`
- `k8s/quickstart.yaml`
- `helm/eddi/Chart.yaml`
- `src/test/java/ai/labs/eddi/deploy/DeploymentManifestsTest.java`

---

## 🐛 fix(conversations): filtered listings page exactly, and a listing page no longer loads whole conversations (2026-10-01)

**Repo:** EDDI (`fix/conversation-listing-pagination`)

### The bug

`GET /conversationstore/conversations` treated `index` as a page of *descriptors*, filtered the rows
in Java afterwards, and kept adding whole descriptor pages until it had `limit` rows. With any filter
— `agentId`, `agentVersion`, `conversationState`, `viewState`, or the owner filter every non-admin
gets — that went wrong two ways:

- **Overshoot.** The size was checked only after a whole descriptor page had been added, so a page
  could hold up to `2*limit - 1` rows. On a rehearsal of real 5.5.1 data, `agentId=…&limit=100`
  returned 189 rows and `limit=20` returned 39.
- **Overlap.** `index=1` started at descriptor page 1, which `index=0` had often already consumed. 90
  of the 90 rows on `index=1` were on `index=0` too; paging to an empty page saw 279 rows for 189
  conversations. Not a 6.5 regression: 6.4 showed 1,499 rows for 852 conversations.

### The fix

[`RestConversationStore.readConversationDescriptors`](../src/main/java/ai/labs/eddi/engine/memory/rest/RestConversationStore.java):

- **`index` is a page of results.** The listing reads descriptor pages from the start, counts off the
  `index*limit` rows that pass every filter, and stops at `limit` exactly — mid-page if need be.
- **The descriptor-field filters are pushed into the query**, for MongoDB and PostgreSQL alike, so the
  descriptors read are mostly results: the agent (`agentResource` names it, *or names no agent* — a
  descriptor an earlier 6.x rewrote without its agent gets it from the conversation), `viewState`
  (equality), and for a non-admin the owner (`userId` is the caller, *or no owner is recorded* — a
  pre-v5.1.6 conversation). The owner is not pushed when an agent is named, because a reviewer of that
  agent may list conversations they do not own. Each pushed group admits a superset of what the
  per-row check accepts; that check still runs on every row and stays the authority.
- **What stays in Java** is what only the conversation knows: its state (the descriptor's copy is not
  maintained), whether it still exists (orphans, still counted in
  `eddi.conversations.listing.orphaned_descriptors`), and the owner or agent of a legacy descriptor.
- **A ceiling for every caller:** `index * limit` above 10,000 is refused with a 400 before any
  query. A page counts off every match before it, and an admin — or anyone when authorization is
  disabled — never reaches the owner-scan budget, so `index=2000000000` would otherwise have read the
  whole descriptor collection. (Raised in review.) A deep page also reads the rows it counts off in
  batches sized to that work, up to 1,000: each batch makes the database walk past the batches before
  it again, so fixed batches of 100 made a deep page quadratic. The owner budget is checked once per
  batch, never mid-batch: which rows of a batch are counted off — and so exempt from the budget — is
  known only after its summary read, and stopping mid-batch let the next batch start after rows nobody
  had examined (with batches larger than the budget, rows 500–599 of a deep page went missing).
- **The owner-scan budget** (`MAX_OWNER_SCAN`, 500) counts the descriptors a page examines, minus the
  matches it counts off for earlier pages: those are the caller's own conversations, and charging them
  would put a caller's older conversations out of reach. The reviewer exception is unchanged.

### Performance

Every listed row used to be a full conversation-memory load — one round trip per row, deserializing
every step, property and output — to read six small fields; the agent's display name was another read
per row. Counting off earlier pages would have multiplied that.

- New `IConversationMemoryStore.loadListingSummaries(ids)`: one projected query per descriptor page.
  MongoDB: `$in` with a projection that counts the steps server-side (`$size`). PostgreSQL:
  `id = ANY(?)`, the state/agent columns plus three JSONB fields and `jsonb_array_length`. A page now
  costs two small queries per descriptor page and loads no conversation in full. A row whose
  descriptor records another owner is still rejected from the descriptor alone, before any
  conversation read; a legacy descriptor that records no owner is decided from its conversation's
  summary.
- Page 0 reads descriptors in pages of `limit`; a later page, which first counts off the rows of the
  pages before it, reads them in batches of 100 to 1,000 sized to that work, so `index=50&limit=20`
  costs 4 queries (two batches of 1,000, each with its summary read) rather than 102.
- An agent's display name is read once per agent version per listing, and only for returned rows.
- If a page's summary read fails, the page's ids are read one at a time, so a conversation the store
  cannot read costs only its own row (logged, sanitized, and not counted as an orphan) — as a failed
  per-row load did before.

### Store plumbing

- `DescriptorStore.readDescriptorsRestricted(…, List<QueryFilters>)` takes any number of AND-ed filter
  groups; the two-group overload delegates to it.
- `IConversationDescriptorStore.readDescriptors(…, restrictions)` exposes it for conversations.

### Tests

- `RestConversationStoreTest.FilteredPagination`: a filtered page stops at `limit`; consecutive
  filtered pages list every match once, in order; an orphan before the page does not shift it; a state
  filter pages through matching states; a page reads one summary batch per descriptor page and never
  loads a conversation in full; an agent name is read once; the agent/view-state groups and their
  patterns; the state is not pushed down.
- `RestConversationStoreOwnershipTest`: an owner's pages are exact and disjoint with foreign rows
  interleaved; page 5 of 650 own conversations is reachable (the budget is not spent on counted-off
  rows); the owner group is pushed for a non-admin, not for an agent listing, not for an admin.
- `MongoConversationMemoryStoreTest` / `PostgresConversationMemoryStoreTest` (real databases): a
  summary equals the summary of the full load, including step count and a narrow `ENDED` write;
  unknown and malformed ids have no entry.
- `V6RenameMigrationConversationDescriptorsTest` already ran the listing over a real MongoDB and
  covers the agent pushdown, including the "descriptor names no agent" fallback;
  `PostgresResourceStorageContainerTest` now checks the same agent group on a real PostgreSQL.
- An unreadable conversation costs only its row and is not counted as an orphan.
- Mutation-checked: 12 mutants (no stop at `limit`, no counting-off, budget charged for counted-off
  rows, nothing pushed down, owner pushed for agent listings, no agent-name cache, orphans kept, scan
  batch ignored, and four in the two `loadListingSummaries` implementations), all killed.

### Docs

The listing's paging contract is on `IRestConversationStore.readConversationDescriptors`;
[`upgrading-from-5x.md`](upgrading-from-5x.md) says to page until empty and that earlier versions
over-counted.

---

## ⚡ perf(search): descriptor search is a literal substring test, not a `.*text.*` regex (2026-10-01)

**Repo:** EDDI (`fix/conversation-listing-pagination`)

Every descriptor listing's search box — conversations, agents, every config type — sent the escaped
term as the regex `.*<text>.*`, ORed over five fields (`userId`, `name`, `agentName`, `description`,
`resource`). An unanchored regex already matches anywhere, so the wrapping changed no result and only
added backtracking; PostgreSQL then ran it as `~`, which costs more per row than a literal test.

- New filter value `IResourceFilter.Contains(text)`: the field contains the text as a literal,
  case-sensitive substring; an absent field does not match. `DescriptorStore` builds the search from
  it (`StringUtilities.searchText` keeps the old quoted-term handling).
- **MongoDB** renders it as a bare escaped regex — no `.*` wrapping.
- **PostgreSQL** renders it as `LIKE '%…%' ESCAPE '\'` with `%`, `_` and `\` escaped. `LIKE` rather
  than `strpos()` (which measures the same) because a `pg_trgm` GIN index serves `LIKE` and never
  `strpos()`.

Measured on 300k descriptors (250k conversations) in throwaway `mongo:7` and `postgres:16-alpine`
containers, back to back on an otherwise idle machine, medians of 5 (MongoDB) and 7 (PostgreSQL)
runs:

| Query | MongoDB before → after | PostgreSQL before → after |
|---|---|---|
| selective search (hit) | 276 → 121 ms (2.3×) | 443 → 295 ms, cached plan 453 → 349 ms |
| search with no hit | 405 → 411 ms (no change) | 491 → 407 ms, cached plan 1,253 → 914 ms |

The PostgreSQL figures are for one prepared statement executed repeatedly, as the JDBC driver runs it:
the first five executions are planned for their value, later ones use PostgreSQL's cached generic
plan. A search that matches nothing still examines every descriptor on both databases, and on a
long-running PostgreSQL that cached plan makes it take about a second — before and after this change.

Results are unchanged: same rows, same case sensitivity, same quoted-term handling. Real-database
tests on both backends (`MongoContainsFilterTest`, `PostgresResourceStorageContainerTest`) assert the
same rows for literal metacharacters (`+`, `(`, `%`, `_`, `\`, `'`), case, an absent field and an
empty search.

PostgreSQL search is indexed too — see the next entry. MongoDB has no index that serves a substring
search; a text index would change matching to whole words.

### The "nothing matched, list everything" fallback keeps its meaning

A search that matches no conversation lists everything instead. That was decided from the first
unfiltered descriptor page; with the listing's filters now pushed into the query, an empty first page
no longer means the search matched nothing — "Billing" within agent A comes back empty when it matches
agent B's conversations. A one-row probe without the pushed-down filters now decides it, so that search
lists nothing (as before) instead of falling back to all of agent A.

### Verified against a running EDDI on both databases

The packaged jar, run against fresh `mongo:7` and `postgres:16-alpine` containers and driven only
through its REST API (two database edits aside: orphaning three conversations and marking four
`SEEN`). 67 conversations on two agents, some ended:

| Run | MongoDB | PostgreSQL |
|---|---|---|
| Paging at limits 3/7/10/20 for every filter (none, agent, agent+version, state, agent+state, view state), row content, orphans, search (by id, metacharacters, `%`/`_`, quoted, case, inside a filter), the orphan counter | 293/293 | 293/293 |
| With Keycloak: two `eddi-user`s and an admin, 50 conversations, one legacy descriptor without an owner — each user pages through exactly their own (the legacy one by its conversation's owner), the admin through all, searches never leak or fall back across owners | 121/121 | 121/121 |

The same unauthenticated checks against `labsai/eddi:latest` (6.4.0, before this fix) fail 98 times —
agent A's 45 conversations came back as 88 rows — so they do detect the bug.

Mutation-checked: nine more mutants in the search and fallback code (LIKE wildcards unescaped, ILIKE,
MongoDB text unescaped, back to a regex string, quotes kept, fallback always / never, a failed batch
dropping the page, an unreadable row counted as an orphan), all killed.

---

## ⚡ perf(postgres): index the descriptor search with pg_trgm, and plan it per execution (2026-10-01)

**Repo:** EDDI (`fix/conversation-listing-pagination`)

A search on PostgreSQL scanned every descriptor. On a long-running instance it was slower still: the
JDBC driver server-prepares a statement it sees repeatedly, PostgreSQL then reuses one generic plan,
and a search that matched nothing took about a second on 300k descriptors.

- **`PostgresSubstringSearchIndexes`**: GIN `gin_trgm_ops` indexes on the five searched fields,
  partial to the `descriptors` collection. `DescriptorStore` asks for them through the new optional
  `ISubstringSearchIndexing`, which only the PostgreSQL storage implements. The indexes are a fixed
  catalogue: every index name and `CREATE`/`DROP` statement is a compile-time constant, so no runtime
  string reaches the DDL (which takes no bind parameters); a field or collection outside the catalogue
  is not indexed. (CodeQL flagged the first version, which built the DDL from validated names.)
- **Built in the background**, 15 s after boot, with `CREATE INDEX CONCURRENTLY`: a large deployment
  neither waits at startup nor stops writing (300k descriptors took ~8 s for all five). One builder
  across replicas via an advisory lock. An index an interrupted build left INVALID — which
  `IF NOT EXISTS` would skip for ever while it still costs every write — is dropped and rebuilt.
- **Retries transient failures.** The first live boot deadlocked: the concurrent build ran into the
  other storages' schema setup and PostgreSQL aborted it, leaving an index INVALID. The build now waits
  for boot to settle and retries a deadlock, serialization failure, lock timeout or cancellation
  (after 30 s, 2 min, 10 min); a refused privilege is reported once and not retried.
- **Planned per execution, once the indexes are ready.** The search statement is sent with a prepare
  threshold of 0, so PostgreSQL plans it for its actual pattern and can use the indexes. Only that
  statement, and only when every index is valid: without the indexes, per-execution planning made a
  common term ~170× slower than the cached plan, which happens to stop early on the date index.
  Until they are ready, the search asks the catalogue at most once a minute (one caller, atomically),
  on the connection it already holds — a second checkout per search could stall concurrent searches in
  an exhausted pool (raised in review).
- **`eddi.datastore.postgres.substring-search-index`** (default `true`) turns it off. If
  `CREATE EXTENSION pg_trgm` is refused, a warning is logged and search runs unindexed, as before.

Measured live: the packaged jar on PostgreSQL 16 with 300k conversation descriptors and memories,
through `GET /conversationstore/conversations?filter=…`, 120 requests per term, median of the last 60
(after the connection pool's plan caching settled), identical rows with the feature off and on:

| Search | off (as before) | on |
|---|---|---|
| matches a few conversations | 393 ms | 39 ms |
| matches nothing | 375 ms | 23 ms |
| matches many (common term) | 14 ms | 18 ms |

Cost: each descriptor write maintains five GIN indexes — measured at the database, a descriptor
rewrite (one per conversation turn) went from ~60 µs to 130–200 µs — and ~50 MB per 300k descriptors.

Verified live on that boot: the build waited, repaired the INVALID index the deadlocked run had left,
built the rest and reported ready 29 s after start, with EDDI serving from 4 s. The 293-check listing
script passed on PostgreSQL with the feature on (indexes ready) and on MongoDB, where nothing changes.

Tests: `PostgresSubstringSearchIndexesTest` (real PostgreSQL: the index definitions, a planned search
uses the index, an INVALID index is rebuilt, another builder's lock is respected),
`PostgresSubstringSearchIndexesRetryTest` (a deadlock is retried to success, a privilege failure is
not, retries are bounded, the SQLState classification), and `PostgresResourceStorageTest` (planned per
execution only for a substring search with ready indexes; the build starts only when enabled).
Mutation-checked: ten mutants — never planned per execution, planned before ready, nothing
transient, no repair, lock ignored, privilege retried, a field outside the catalogue indexed, any
collection indexed, readiness checked on a second connection, no re-check throttle — all killed.

---

## 🐛 fix: conversation listings leave out descriptors whose conversation is gone (2026-10-01)

**Repo:** EDDI (`fix/6.5-release-readiness`)

A rehearsal on real 5.5.1 data found that 1,106 of 1,301 conversation descriptors had no
conversation memory left. They were already orphans on 5.x, never marked deleted. Once the v6
rename gave them an `agentResource`, `GET /conversationstore/conversations?agentId=` returned them
as conversations that cannot be opened: 852 entries for an agent with 189 conversations. It also
logged one WARN per orphan per page, about 51,000 lines for seven listings.

`RestConversationStore`:
- `populateDataToDescriptor` now reports a missing snapshot, and the listing skips that
  descriptor instead of returning it.
- The per-orphan message is DEBUG. A listing that skipped any counts them once in the new metric
  `eddi.conversations.listing.orphaned_descriptors`.
- A snapshot that exists but names an agent whose descriptor is gone is still listed, as before.
- The counter is in [`metrics.md`](metrics.md) and charted as a second series on the Full Metrics
  Reference panel for listings, beside `owner_scan_exhausted`.

The test that pinned the old behaviour ("descriptor with null snapshot should still be added") is
replaced by a by-agent case: an orphan and a live conversation for the same agent, where only the
live one is listed. Mutation-checked: listing the orphan anyway, or not counting it, fails it.

[`upgrading-from-5x.md`](upgrading-from-5x.md) §6 says to compare the by-agent listing with
`conversationmemories`, and names the metric.

The idle sweep's message now reads "has been idle for N days, longer than the maximum idle time of
M days". Before, it said the conversation was "N days older than" the limit, which gave the idle
time as if it were the excess over the limit.

---

## 📝 docs: upgrade guides cover the 6.5 fixes; stale Open WebUI and RAG claims; two names the reference guard wrongly refused (2026-10-01)

**Repo:** EDDI (`fix/6.5-release-readiness`)

### Upgrade guides

[`upgrading-from-5x.md`](upgrading-from-5x.md):
- a configuration row and a section on the idle sweep, which first runs five minutes after boot. From
  6.5 a limit below 1 disables idle-ending; before 6.5, `-1` ended every conversation;
- the conversation-descriptor rename;
- the new reporting of unconvertible templates (one ERROR, then one WARN per boot);
- the by-agent listing as a check;
- a precise description of which legacy properties are held back.

[`upgrading-from-6.4.md`](upgrading-from-6.4.md):
- the two one-time catch-ups a 6.4-migrated database gets (step shape, descriptor fields and their
  backfill), with count queries to confirm them;
- the idle-limit `-1` change;
- the NEGOTIATION fix, which changes the outcome of stored groups: a real verdict instead of a
  summary recorded as one.

### Stale claims corrected

- [`open-webui-integration.md`](open-webui-integration.md) and `docker-compose.openwebui.yml` said
  the `/v1` adapter "is not in any published image yet". It has shipped since 6.4.0; the demo builds
  from the working tree so it runs the checkout's adapter.
- [`rag.md`](rag.md) called `web` "the only type implemented", above its own `upload` section.
- A Manager comment in `ui/manager/src/lib/hitl-config.ts` said the backend runs the generic synthesis
  prompt for a NEGOTIATION group missing its arbitration prompt. Since 6.5 the backend restores the
  prompt itself, with the same matching rule.

### `ConfigurationReferenceCoverageTest`

The guard refused two real settings in any document:
- `EDDI_BIND`, the Compose variable that sets the address EDDI's ports are published on. It's now in
  the non-property list beside `EDDI_PORT`.
- `EDDI_CHAT_FRAME_ANCESTORS`. Its property is read through a `${...}` expression rather than by Java,
  so the guard's scan of the code couldn't see it. The environment names of the properties in
  `DECLARED_BUT_UNREAD` now count as valid.

The README now names `EDDI_BIND` directly, where it had to describe the variable's location instead.
The 6.4 guide names `EDDI_CHAT_FRAME_ANCESTORS`. Both changes were mutation-checked: removing either
one fails `documentedEnvironmentVariablesMapToRealProperties` on those docs.

---

## 🧹 chore(ci): time limits on the E2E jobs and their browser install (2026-09-30)

**Repo:** EDDI (`chore/ci-playwright-timeouts`)

### Why

On PR 890 the Auth E2E (Keycloak) job sat in "Install Playwright browsers" for
over an hour and a half; the tests never started. Other runs that day passed, so
it was a stalled download on one runner, not a code problem. Nothing in
[`ci.yml`](../.github/workflows/ci.yml) bounded it, so a stall like that runs
until GitHub's 6-hour default while the PR shows the check as pending.

### What changed

- `timeout-minutes: 10` on each of the three `npx playwright install --with-deps
  chromium` steps. A normal install takes 20 seconds to 5 minutes.
- Job limits: `UI Manager E2E (MSW)` 20 minutes, `Backend E2E` 20, and
  `Auth E2E (Keycloak)` 15. Recent green runs took about 5, 2 to 6, and 2 minutes.

A timed-out step fails the job, and re-running it is the fix. No automatic retry
was added: interrupting `--with-deps` part-way can leave apt holding its lock, so
a second attempt could fail for a different reason.

---

## fix(migration): identifiers are no longer held back as credentials by the legacy-properties migration (2026-09-30)

**Repo:** EDDI (`fix/650-credential-chatui`)

### Why

On a 5.5.1 database, `PropertiesMigrationService` held back five `createdProgram` properties whose
value is `{courseId: <a 17-character alphanumeric id>}` and logged them as credentials. The rule
that fired is the entropy check in `SecretScrubber.scrubTextValue` (check 3): a key-like string of
at least 14 characters scoring over 3.5 bits per character is redacted unless its field is one of
the structural names. A random 17-character id scores about 4.1, and `courseId` is not structural.
The values stayed in `properties_migrated_v6`, but the users lost those memories.

### What changed

- [`PropertiesMigrationService`](../src/main/java/ai/labs/eddi/configs/properties/mongo/PropertiesMigrationService.java):
  the copy of the value handed to the scrubber (which already replaced BSON ObjectIds) now also
  replaces an **identifier-shaped** string (`[A-Za-z0-9_-]{1,128}`) under an **identifier-named**
  field — `id`, or a name whose last word is `id`/`ids` (`courseId`, `course_id`, `courseID`,
  `courseIds`) — with a placeholder. Names qualified by a credential word (`sessionId`,
  `accessKeyId`, `tokenId`, `apiId`) get no exemption.
- A new **known-format** check holds back any key whose value contains, at any depth, a JWT, a
  pasted `Bearer`/`Basic` value, or a provider key prefix (`sk-`, Stripe, Slack, GitHub, GitLab,
  AWS, Google, Hugging Face). It closes a gap the entropy rule never covered: `"Bearer …"` has a
  space, so the scrubber's whole-value pattern did not match it. An identifier-named field gets no
  exemption for these.
- The credential-name rules are unchanged, and the scrubber itself is untouched: the narrowing
  applies to this migration only, so export scrubbing is exactly as strict as before.

### Tests

`PropertiesMigrationServiceTest$IdentifiersAreNotCredentials`: the id migrates (with a
precondition test proving the scrubber alone still flags it); name variants migrate; `token`,
`apiKey`, a JWT, a `Bearer` value, an `sk-` key, a JWT under `courseId`, a high-entropy value under
a neutral key and under `sessionId` are all held back and logged by key name only; `userInfo` is
still skipped. Name- and format-rule cases use zero-entropy values so entropy cannot catch them
for the wrong reason. Mutation-checked.

### Not changed

The export scrubber has the same false positive: any identifier of 14+ random characters under a
non-structural field name is redacted on export. Loosening it globally is not shown to be safe
everywhere, so it is left as it is.

---

## fix(chat): a user holding only eddi-user sees the agent's name (2026-09-30)

**Repo:** EDDI (`fix/650-credential-chatui`)

### Why

The Chat UI read the agent's name from `GET /descriptorstore/descriptors/{id}/simple`, which is
`@RolesAllowed({"eddi-admin", "eddi-editor"})`. A chat user holding only `eddi-user` got a 403;
the failure was swallowed, and the header showed only the logo — never the name of the agent they
were talking to.

### What changed

- **The conversation read carries the name.** `SimpleConversationMemorySnapshot` gains a nullable
  `agentName`, which
  [`RestAgentEngine.readConversation`](../src/main/java/ai/labs/eddi/engine/internal/RestAgentEngine.java)
  sets after its ownership check, through the new
  [`AgentDisplayNameResolver`](../src/main/java/ai/labs/eddi/engine/internal/AgentDisplayNameResolver.java).
  The managed-conversation load goes through the same method. No endpoint was added and no role
  was widened.
- **Only the name, only for a user of the agent.** The resolver checks the caller's `USE` access
  to the agent (the gate `POST /agents/{agentId}/start` applies) before reading the descriptor of
  the conversation's agent version, and returns the name alone — not the description, owner,
  grants or configuration. Any failure answers `null`; the read itself never fails over a name.
- **Chat UI** ([`ChatWidget.tsx`](../ui/chat/src/components/ChatWidget.tsx)) takes the name from
  the snapshot and no longer calls the descriptor store; `fetchAgentDescriptor` is removed. With no
  name the header shows the logo, titled with the configured `title`.

### Tests

`AgentDisplayNameResolverTest` (name present for a `USE` caller; absent and no descriptor read
without `USE`; absent on a store failure or blank name; description never serialised; `eddi-user`
admitted by `readConversation` and by neither `IRestDocumentDescriptorStore` nor
`IRestAgentStore`). Chat UI: name from the snapshot with the descriptor store answering 403,
fallback to logo and title, blank name ignored. Mutation-checked.

---

## ⬆️ build(deps): langchain4j 1.20.2 and the safe half of the minor-and-patch group, for 6.5.0 (2026-09-30)

**Repo:** EDDI (`fix/6.5-release-readiness`)

### What changed

- **Quarkus** `3.39.5` → `3.40.1`. 3.40 is the new LTS (12 months of support) and continues 3.39: its
  migration guide is empty, and 3.40.0 and 3.40.1 contain only bug fixes (none in OIDC, REST, MongoDB, Qute
  or Micrometer). `quarkus-mcp-server` stays on `2.0.1`, the version the 3.40 platform itself ships. Verified
  with a clean compile, the full unit suite and the 5.x first-boot rehearsal on the 3.40.1 build.
- **langchain4j** `1.20.0` → `1.20.2`, and the beta line `1.20.0-beta30` → `1.20.2-beta30`. Every
  module EDDI pins was checked on Maven Central at the new version before the bump.
- **One exception:** `langchain4j-community-oci-genai` (Oracle GenAI) has no `1.20.2-beta30`; its
  newest release is `1.20.0-beta30`. It now pins its own property, `langchain4j-community-oci.version`.
  `BuildQualityGatesTest` allowed only the two shared properties, because a third can drift and fail at
  runtime with `NoSuchMethodError`. It now names this artifact as the single sanctioned exception,
  bounded twice by a new test:
  - only a patch-level lag on the core's major.minor line is allowed;
  - the exception **expires**: once the property equals the beta line (which Dependabot's langchain4j
    group will propose when a newer OCI release appears), the build fails until the artifact rejoins
    `${langchain4j-beta.version}` and the entry and property are removed.

  All four ways of breaking it were mutation-checked, each caught by a named test:
  1. the property caught up with the beta line;
  2. a minor-line drift;
  3. the exception entry removed;
  4. another artifact using the OCI property.

  The builder tests pass on the mixed versions (45/45 `LanguageModelBuildersTest`).
- From Dependabot's minor-and-patch group (#913), everything except reactor-netty:
  - Jackson `2.22.2` → `2.22.3` (core, databind, dataformat-csv, dataformat-xml);
  - commons-lang3 `3.20.0` → `3.21.0`;
  - maven-compiler-plugin `3.15.0` → `3.16.0`;
  - maven-resources-plugin `3.3.1` → `3.5.0`;
  - maven-clean-plugin `3.2.0` → `3.5.0`.
- **reactor-netty-http stays on 1.2.18.** #913 moved it to 1.3.7, which is built against Netty 4.2
  while Quarkus manages 4.1. `ReactorNettyNettyCompatibilityTest` failed on it with the
  `NoClassDefFoundError` the pin's comment predicts, and since it shared a group PR with everything
  else, nothing in that group could merge. `.github/dependabot.yml` now ignores reactor-netty minor
  and major updates, with the same reason, until Quarkus moves to Netty 4.2.

Quarkus 3.39.5 (#881) was already on `main`.

### Verified

On the new dependencies: `GeminiThoughtSignatureTest`, `LanguageModelBuildersTest`,
`ReactorNettyNettyCompatibilityTest`, the OpenAI-compatible builder and provider tests, and the
tool-loop tests. `LanguageModelBuildersTest` errored inside the sandbox with "Unable to establish
loopback connection" (an environment limit on starting a Java HTTP client), and passed 45/45 outside
it.

---

## 🐛 fix(llm): a timed-out or failed cascade step hands its completed tools to the next step (2026-09-30)

**Repo:** EDDI (`fix/cascade-partial-tool-exchange`)

Closes the follow-up the 2026-09-26 tool-loop entry left open ("Not carried (follow-up)"): a
cascade step carried its tool exchange into the next step only when it *returned*. A step whose
future timed out or threw lost the exchange with its result, so the next step started from the
conversation alone and could execute the same side-effecting tools again.

### What changed

- **`ToolExchangeRecorder`** (new, `modules/llm/impl`) — a per-run, thread-safe record of the
  tool loop's exchange, appended as it happens. It holds only complete pairs: a call is recorded in
  the same step as its result, and an assistant message that asked for several tools is reported
  with the calls that have a result and nothing else. A run cancelled between two tools of one
  batch therefore never leaves a dangling call. Its output goes through
  `ToolLoopRunner.toolExchange`, so null-id calls get the same synthetic, paired ids a returned
  exchange gets.
- **`ToolLoopRunner`** — `runToolCallLoop` and a new `executeWithTools` overload take the
  recorder; every place the loop appends a tool result (executed, self-conversation refusal,
  pause-cap denial) now goes through one `addResult` helper that appends and records together.
  The old `executeWithTools` signature passes `null` (records nothing); the resume path passes
  `null` too.
- **`IAgentOrchestrator` / `AgentOrchestrator`** — a new `executeIfToolsEnabled` overload with the
  recorder. The interface default ignores it and delegates to the existing method, so another
  implementation keeps the pre-change behaviour (nothing to carry) rather than failing.
- **`CascadingModelExecutor`** — each step attempt gets a recorder (a fresh one for the
  carried-exchange-rejected retry). In the `TimeoutException` and generic `Exception` paths, after
  the future is cancelled, `carryCompletedExchange` applies the success path's rule: the carried
  exchange becomes what the step started from plus what it completed. The step's trace entry
  records `completedToolMessages`. The `ToolApprovalRequiredException` branch is untouched; it
  rethrows before the recorder is read.

### Design decisions

- **A step that completed nothing leaves the carried exchange alone** rather than replacing it
  with an empty one. There is nothing new to hand on, and dropping an exchange the next provider
  might accept would only invite a replay.
- **A tool still executing when the step is cancelled is not carried.** Its outcome is unknown.
  `cancel(true)` does not wait for the virtual thread, and waiting for it would defeat the step
  timeout. This is the one replay the change cannot prevent, and `docs/model-cascade.md` says so.
- **A failed step's tool trace entries are still not kept.** The next step receives its completed
  calls, but the returned tool trace lists only the calls of steps that returned;
  `completedToolMessages` on the failed step's cascade trace entry is the record that they ran.
  `docs/model-cascade.md` now says so rather than claiming the trace covers every step (review of
  #916). Merging them is a follow-up.
- **A recorder passed in, not state on the runner.** `ToolLoopRunner` is shared by every
  conversation and stays stateless; the recorder lives exactly as long as one step attempt.

### Tests

- `CascadingModelExecutorPartialExchangeTest` (new) runs the real `ToolLoopRunner` under the
  cascade, with a tool that counts its executions. A step that completes `placeOrder` and then
  hangs (timeout) or throws on the next model request hands the call and its result to step 1, and
  the order is placed once. A step that fails before any tool (`FailedBeforeToolsException`) carries
  nothing. With `carryToolResultsOnEscalation: false` nothing is carried and the tool runs again,
  as documented.
- `ToolExchangeRecorderTest` (new) covers complete batches, a batch cut between two tools, round
  ordering and null-id pairing.
- Existing cascade and `LlmTask` cascade tests stub the new overload, because the cascade now calls
  it.

Docs: [`model-cascade.md`](model-cascade.md) replaces the known-limitation sentence with the
new behaviour and the one remaining case.

---

## 🐛 fix(manager): model suggestions that vendors retired, renamed or never had (2026-09-30)

**Repo:** EDDI (`fix/manager-model-suggestions`)

### Why

While building the model catalog for eddi.technology, every id the Manager suggests was checked
against the vendors' own documentation (2026-09-30). Several could not load: an agent created with one
saves and deploys cleanly, then fails on its first message.

### What changed

- **`ui/manager/src/lib/model-suggestions.ts`**
  - Gemini: dropped the bare `gemini-3.1-pro` (Gemini API and Vertex lists). Gemini 3.1 Pro exists only as
    `gemini-3.1-pro-preview` / `-customtools`, which stay.
  - OpenAI: added `gpt-6.1-sol` (2026-09-29), which supersedes `gpt-6-sol`; the older id is still served
    and stays.
  - Ollama: `phi4:mini` → `phi4-mini` (the `phi4` library only has 14b tags).
  - Hugging Face: `THUDM/GLM-5.1` → `zai-org/GLM-5.1`.
  - Mistral: removed `devstral-latest`, `devstral-small-latest` (Devstral retired by 2026-07-31) and the
    `magistral-*-latest` aliases (their targets were retired 2026-07-31; reasoning is now
    `reasoning_effort` on Small and Medium). `mistral-small-4` → the documented `mistral-small-2603`.
    Added the documented dated ids `mistral-large-2512`, `mistral-medium-3-5`, `ministral-14b-2512` and
    `codestral-2508` beside the `-latest` aliases.
  - Bedrock: Llama 4 Maverick and Scout now suggest the cross-region profiles
    `us.meta.llama4-…-instruct-v1:0`. Bedrock has no in-region on-demand support for Llama 4, so the
    bare id fails.
  - Oracle GenAI: `cohere.command-latest` and `cohere.command-plus-latest` never existed on OCI, and
    Command R / R+ are retired there. Replaced with `cohere.command-a-03-2025`, `-reasoning` and
    `-vision`. The Llama 4 entry used the Hugging Face repo name; it is now OCI's
    `meta.llama-4-maverick-17b-128e-instruct-fp8`. The unverified Scout entry was dropped.
- **`ui/manager/src/lib/api/agent-setup.ts`**: the Oracle GenAI default model `cohere.command-r-plus-v2`
  → `cohere.command-a-03-2025`.
- **`docs/langchain.md`**: the Oracle GenAI example and provider line name Command A.
- **Platform Operator revision 1 → 2** (`operator-revision.json`): the Operator's system prompt carries a model catalogue built from these suggestions, so existing Operators are told to upgrade and stop recommending the retired ids.
- **`pom.xml`: Jackson overrides 2.22.2 → 2.22.3** (core, databind, dataformat-csv, dataformat-xml).
  CVE-2026-91776 and CVE-2026-91777 in jackson-databind 2.22.2 were published on 2026-09-30 and
  turned `Trivy Filesystem Scan` red on every PR. Dependabot's #913 carries the same bump inside a
  larger group whose build is failing, so the security fix is taken here on its own.
- **Test**: `model-suggestions.test.ts` lists the retired ids with the reason for each and fails if any
  provider suggests one or defaults to one.

### Not changed

- `claude-haiku-4-5` stays: it is active, though Anthropic only guarantees it until 2026-10-15, so check
  the deprecation page after that date.
- `deepseek-v4-flash` stays in the vision-token list: the alias still routes to V4.1-Flash.
- The legacy rule-based reference agent under `docs/agent-configs/rule-based-reference/` still offers
  older model ids as quick replies; it is a historical sample and was left as it is.
- The `openai/gpt-oss-*` ids under Oracle GenAI were not verified against OCI's naming and were left.

---

## 🐛 fix(operator): knowledge-base diagnosis, a working screen context, and an upgrade path for existing operators (2026-09-30)

**Repo:** EDDI (`fix/operator-rag-knowledge`)

### Why

A review of the Platform Operator against what shipped since its knowledge-base section (2026-09-17) found four gaps. The largest was not about RAG at all: **no prompt improvement ever reached an operator that already existed.** Activation stores the instructions on the config, and the form re-seeded every Reconfigure from that stored copy (`initial.promptBody || default`). So every operator activated before 09-17 still had no knowledge-base section, and none had the 5.5 model catalogue.

### What changed

**What the operator knows about knowledge bases** ([`system-prompt.ts`](../ui/manager/src/lib/operator/system-prompt.ts), [`tool-scopes.ts`](../ui/manager/src/lib/operator/tool-scopes.ts))

- **The three-part binding.** A KB reaches an agent only through the KB document, an `eddi://ai.labs.rag` workflow step, and the LLM task's `knowledgeBases` / `enableWorkflowRag`. Any missing piece fails silently. The prompt stated only the step. It now walks the three in order, as [`rag.md`](rag.md#troubleshooting)'s table does.
- **Names match on the document's own `name` field.** That is not the descriptor name the prompt told the operator to search by, and the two can differ. `httpCallRag` and `maxRagContextChars` are named as well.
- **`in-memory` is flagged.** It is the default `storeType`, and it empties itself on restart, after 30 idle minutes, and on any secret change.
- **"Did the documents land?" is now answerable.** New read grants `GET /ragstore/rags/{id}/sources/{sourceId}/runs` and `…/files`, each with its own predicate and prompt line. The existing ingestion-status read needs an id that only a direct `POST …/ingest` returns, so the operator could never use it; its line now says so. A KB with no `sources` is stated as not checkable. Run, preview, upload, file delete and purge stay excluded, and a test pins that.
- **Connecting an existing KB** (read-write only) is described when the operator holds the KB reads and both `PUT /workflowstore/workflows/{id}` and `PUT /llmstore/llms/{id}` (`grantsKnowledgeBaseBinding`). The prompt also says that a deploy can be refused by vault-grant enforcement, which the operator cannot fix, and that binding makes the KB's documents answerable to the agent's users.

**Screen context** ([`use-current-screen-context.ts`](../ui/manager/src/hooks/use-current-screen-context.ts))

- The viewed group was sent as `groupId`, an engine-reserved key that `ClientContextGuard` strips since the 09-26 hardening, so "(group …)" never rendered. It is now `viewedGroupId`.
- `resourceType`/`resourceId`, `conversationId` and `channelId` were sent all along but never read by the template. They are now rendered. On a knowledge base's own page, the operator now knows which KB is meant.
- A test fails if the template ever reads a reserved key again.

**Operator upgrades** ([`operator-revision.ts`](../ui/manager/src/lib/operator/operator-revision.ts), [`operator-upgrade.tsx`](../ui/manager/src/components/operator/operator-upgrade.tsx), [`OperatorRevisionCheck.java`](../src/main/java/ai/labs/eddi/engine/api/OperatorRevisionCheck.java))

- A provisioning revision lives in [`operator-revision.json`](../ui/manager/src/lib/operator/operator-revision.json), starting at 1; configs from before this are read as 0.
- Activation stamps three fields into the config: `provisionedRevision`, `provisionedEndpoints` and `promptBodyIsDefault`. An upgrade also carries the model endpoint `llmBaseUrl`, which named OpenAI-compatible providers (#904) started storing.
- `operator-revision.test.ts` hashes everything provisioning derives (both scopes' endpoints, preambles and default bodies, plus the gate). It fails when that changes without a revision bump, and prints the new fingerprint.
- **Shown in the Manager:**
  - an *Upgrade* banner on the operator page, listing the tools added and removed;
  - a hint and a dot on the docked drawer;
  - a dashboard line;
  - one `console.warn` per page load.
- **Shown in the server log:** a startup `WARN` from `OperatorRevisionCheck`. It reads the same JSON, which `pom.xml` now copies onto the classpath on every build, `-DskipUi` included.
- **Upgrade is one click.** It is a Reconfigure with every setting carried over, so it runs through every activation check before the old operator is retired. Instructions the admin never edited get the new default. Edited ones are kept unless the admin picks the new default. Legacy configs whose text differs from today's default ask, with *keep* preselected: that text is usually an old default, but it may be an edit, and a click-through must not discard it.
- An operator with a plaintext key (never stored) or an unrecorded model-server address opens the prefilled form instead.
- The form now seeds today's default when the stored text was a default, and offers *Reset to default* whenever the text differs.

**Process** ([`AGENTS.md`](../AGENTS.md) §4.6, [`ui/manager/AGENTS.md`](../ui/manager/AGENTS.md))

- New rule: when functionality is added, changed or removed, check the operator's prompt and allow-list alongside the docs. Any change to them is a revision bump.

### Decisions

- **Upgrade replaces rather than versions the agent in place.** `setup-api` only creates. An in-place new version would need a backend update mode that regenerates the `apicalls` tools from the spec. Replacement reuses the one path that already proves a new operator safe. Cost: the operator chat ends, and old operator conversations leave its history. The confirmation dialog says both.
- **A fingerprint test, not a computed-at-runtime hash.** The backend needs a plain number it can compare. The test keeps that number honest without a build-time code generator.
- **No Sidebar badge.** `Sidebar` is part of the synced design system, and a data hook there would break its preview bundle. The drawer launcher, present on every page of both shells, carries the dot instead.
- **Paused operators are not flagged.** Activating one again always provisions the current revision.

### Next

- Existing deployments will see the upgrade notice on first start of the release carrying this. Nothing is migrated automatically.

---

## 🧪 test(manager): the operator fallback test expects the new default Anthropic model (2026-09-30)

**Repo:** EDDI (`fix/operator-test-default-model`)

### Why

`main` has failed `UI Manager Checks` since #903 merged. #896 made `claude-sonnet-5-5` the first
Anthropic model suggestion, and the operator setup falls back to a provider's first suggestion
when its stored provider is no longer offered. `operator-activation.test.tsx` still expected
`claude-sonnet-5`. #896's own CI ran before that test existed on its base, so neither PR saw the
combination.

### What changed

- `ui/manager/src/components/operator/__tests__/operator-activation.test.tsx`: the fallback case
  expects `claude-sonnet-5-5`. No production code changes.

---

## 🐛 fix(runtime, groups): an idle limit of -1 no longer ends every conversation; stored NEGOTIATION groups arbitrate again (2026-09-30)

**Repo:** EDDI (`fix/650-idle-negotiation`)

### The idle sweep's `-1`

`AgentDeploymentManagement.manageAgentDeployments` runs daily, first five minutes after boot. It ends
conversations idle longer than `eddi.conversations.maximumLifeTimeOfIdleConversationsInDays` and
undeploys old agent versions nothing active uses. The idle check is
`DAYS.between(lastInteraction, today) >= limit`, so a limit of `-1` or `0` counted **every**
conversation as idle and ended all of them. `-1` is how the two neighbouring retention settings say
"never", so an operator copying that idiom, for instance to keep a 5.x database's conversations open
through the upgrade, closed every conversation instead.

- A limit below 1 now **disables** idle-ending (the same threshold as the retention sweep). The sweep
  loads and ends no conversation for idleness, and startup logs once that it is off.
- **Undeploying is unchanged.** The sweep still deploys the latest version of each agent, still
  retires an old version whose conversations can move to a newer compatible one, and still undeploys an
  old version with **no** active conversation. None of that ends a conversation: an idle conversation is
  still open, so it counts as active and keeps its version deployed. Stopping those undeploys too would
  leave superseded versions holding memory for ever, which needs a setting of its own. The reasoning is
  in `idleEndingEnabled()`'s Javadoc.
- Documented in [`configuration-reference.md`](configuration-reference.md), `application.properties`
  and [`gdpr-compliance.md`](gdpr-compliance.md).

### NEGOTIATION groups stored with a prompt-less Arbitration phase

A NEGOTIATION group can be stored with its phases materialized and the Arbitration phase's
`inputTemplate` null. The Manager used to save exactly that (enabling an approval point materializes the
phases), and the REST and MCP APIs and ZIP import accept it as given. There was no error at run time:
`GroupContextBuilder` fell back to the generic SYNTHESIS prompt ("synthesize a balanced conclusion"), so
when bargaining failed the moderator summarised the deadlock instead of deciding it, and
`GroupConversationService` still recorded that summary as the arbitrated VERDICT. Only the Manager
repaired such a group, on its next save.

- `DiscussionStylePresets.withNegotiationArbitrationRepaired` puts `TEMPLATE_ARBITRATION` back on that
  one phase. The test is the Manager's `repairNegotiationArbitration`: NEGOTIATION style, named
  `Arbitration`, a MODERATOR SYNTHESIS skipped on `AGREEMENT_REACHED`, no template. Any other phase,
  or one with a template, is left as written.
- **Run time:** `GroupConversationService.resolvePhases` and `effectivePhases` (a persisted runtime
  phase list) apply it, so groups already stored this way arbitrate without being re-saved.
- **Save time:** `AgentGroupStore` create and update store the repaired phase, whichever client sent
  it. It is filled in rather than rejected with a 400, because the Manager itself saved this shape and
  such groups must stay saveable through the API that stored them.
- The Manager's own repair is kept; it and the backend now agree.

**Files:** [`AgentDeploymentManagement.java`](../src/main/java/ai/labs/eddi/engine/runtime/internal/AgentDeploymentManagement.java),
[`DiscussionStylePresets.java`](../src/main/java/ai/labs/eddi/configs/groups/model/DiscussionStylePresets.java),
[`GroupConversationService.java`](../src/main/java/ai/labs/eddi/engine/internal/GroupConversationService.java),
[`AgentGroupStore.java`](../src/main/java/ai/labs/eddi/configs/groups/mongo/AgentGroupStore.java);
tests `AgentDeploymentManagementIdleSweepTest`, `NegotiationArbitrationRepairTest`,
`AgentGroupStoreNegotiationArbitrationTest`.

---

## 🐛 fix(migration): conversation descriptors and steps reach the v6 shape on databases an earlier 6.x migrated; an unconvertible template is an ERROR once (2026-09-30)

**Repo:** EDDI (`fix/650-migration-descriptors`)

Findings from the second 5.x → 6.5 first-boot rehearsal and its docs review.

### 1. Per-agent conversation listings were empty after a 5.x upgrade

EDDI 5 stored a conversation descriptor's agent as `botResource` and `botName`. The rename
migration rewrote the URI inside `botResource` but never the field's name, and the 6.x
`ConversationDescriptor` reads `agentResource`. So `GET /conversationstore/conversations?agentId=…`
returned none of the conversations EDDI 5 created, with or without `agentVersion`, and the text
filter on the agent's name (`agentName` is one of the indexed search fields) missed them too.

Worse, on a database an earlier 6.x migrated: 6.x read such a descriptor without its agent and
every write of a descriptor is a whole replace, so each turn, and each conversation the idle sweep
ended, stored the descriptor with **neither** name.

- **`V6RenameMigration`** — a new server-side pass renames `botResource` → `agentResource` and
  `botName` → `agentName` in `descriptors` and `descriptors.history` (one `updateMany` with a
  pipeline; a document holding both names of a pair is left alone, counted and reported, as for
  conversations). A second pass gives an `agentResource` back to the conversation descriptors
  that lost both names, from the `agentId`/`agentVersion` of the conversation with the same id,
  in batches; a descriptor whose conversation is gone is counted and reported, not failed. Both
  run as step 7 of a first migration, and as a **catch-up** on a database whose rename migration
  an earlier 6.x recorded complete (the #908 pattern for triggers), until it succeeds once:
  it records `v6-rename-descriptor-fields-complete` in `migrationlog`.
- **`ConversationDescriptor`** — reads `botResource` and `botName` through private write-only
  setters, so an unmigrated descriptor reads with its agent and is written back with the v6 names.
- **`RestConversationStore`** — the listing falls back to the conversation's own agent when a
  descriptor names none, so a descriptor stripped after the catch-up ran (by a replica still on
  an earlier 6.x) is still listed.

### 2. A database 6.4 migrated kept the v5 step shape of every EDDI 5 conversation

The step rename (`conversationSteps[].packages` → `workflows`, #907/#909) runs only as part of
a first migration. On a database whose `v6-rename-migration-complete` an earlier 6.x recorded,
it never ran, and those conversations loaded only through `@JsonAlias("packages")` on the
snapshot — while a comment said nothing depended on that alias.

- **`V6RenameMigration`** — the same server-side pipeline now also runs as a catch-up on such a
  database (idempotent, counted, `_rev`/`_histRev` bumped as #909's passes do), recorded as
  `v6-rename-step-shape-complete`. Both catch-ups go through one `catchUp(key, …)` helper; a first
  migration records both keys with its completion.
- **`ConversationMemorySnapshot`** / `migrateConversationStepShape` Javadoc — the alias is a safety
  net that has to stay (a database awaiting its catch-up, a v5 step written after the pass), and
  now say so.

### 3. An unconvertible Thymeleaf template was logged at ERROR on every boot

A template `V6QuteMigration` refuses (one that builds template syntax) keeps the migration
incomplete, so it re-ran and re-reported the same document on every boot, for as long as the
legacy config existed.

- **`V6QuteMigration`** — the first boot that finds a refused document logs an ERROR with the
  collection, id (with the version, for a history row), field paths and the remedy. Later boots
  list the documents already reported in **one WARN**. The reported set is kept in `migrationlog`
  under `v6-qute-migration-unconvertible` (an `entries` list); a document fixed since is converted
  and dropped from it, a new refused one gets its own ERROR, and the record is removed when none
  is left. The end-of-run summary is an ERROR only for real failures (an unreadable collection, a
  failed write); refusals alone end with an INFO. A cursor failing part-way through a collection is
  now counted as a failure instead of escaping the pass.
- **`IMigrationLogStore`** — `readMigrationEntries` / `writeMigrationEntries`, implemented by the
  MongoDB store on a plain document. The defaults keep nothing, so a store without them reports as
  on a first boot every time (the PostgreSQL store; the Qute migration reads MongoDB anyway).
- **Docs** — [`configuration-reference.md`](configuration-reference.md) (`eddi.migration.v6-qute.enabled`)
  and [`output-templating.md`](output-templating.md).

### Design decisions

- **The Qute migration stays incomplete while any document is refused, reported or not.**
  Completion means no stored template is still Thymeleaf, and it is also what stops the scan: once
  complete, a refused document fixed later would never be converted, and a refused one restored
  later would never be reported. Re-checking only the recorded ones would be a second path through
  the same four config collections; the full scan is cheap, and it is what an incomplete migration
  already cost.
- **The descriptor passes bump no revision**, unlike the conversation passes of #909. A descriptor
  has no `_rev`; its `_version` is part of the conversation's URI (`?version=`) and must not move,
  and descriptor writes are unconditional replaces that a bump would not guard anyway. A replace
  racing the pass is safe from 6.5 on, because the model reads the v5 names.
- **Not `@JsonAlias`.** With an alias, a document holding both names reads as whichever key comes
  last; the setters let the v6 name win in either order.
- **No alias for `ConversationMemorySnapshot.botId`/`botVersion`.** Conversations are migrated
  server-side before anything loads them, and a snapshot save `$set`s fields rather than replacing
  the document: an alias would leave `botId` beside the new `agentId`, which the migration then
  reports as ambiguous forever.

### Tests

- `V6RenameMigrationConversationDescriptorsTest` (Testcontainers): first boot, current and history,
  listed through the real `RestConversationStore` filter by agent and by agent + version; the
  catch-up on a database an earlier 6.x migrated; runs once; idempotent; ambiguous left alone;
  the backfill; reading the v5 names and writing back the v6 ones; v6 wins in either key order; the
  listing's fallback to the conversation.
- `V6RenameMigrationFirstBootTest` (Testcontainers): the step-shape catch-up on a database an
  earlier 6.x migrated — renamed, revision bumped, loads with the same item counts, a second run
  changes nothing — and it does not run once recorded.
- `V6QuteMigrationReportingTest` (Testcontainers): ERROR per document on the first boot; one WARN and
  no ERROR on the second; a fixed document converted and dropped, completion once none is left; a
  new refused document gets its own ERROR; a history row named with its version.
- `LogCaptureSupport.captureRecordsOf` — captures the level and the formatted message.
- Every behavioural test above was mutation-checked: 18 mutants (each fix reverted in turn), all
  killed by the test written for it.

### Open

- A refused template that is fixed through the API leaves its old version in the `.history`
  collection, where it is still refused: the migration completes only once that row is corrected
  or removed by hand. That is deliberate — an agent pinned to the old version still reads it — and
  the ERROR says so.

---

## 📝 docs(readme): bring the README up to what 6.5.0 ships (2026-09-30)

**Repo:** EDDI (`docs/650-readme`)

### Why

The README was last refreshed around 6.4.0. Since then the RAG ingestion pipeline (web crawler,
file uploads, scheduled sources, the Manager panel), agent version following, LLM telemetry, MCP
OAuth sign-in, editable vault grants and a round of fail-closed security changes landed, and
several existing claims had drifted from the code.

### What changed

- **Added** (each checked against the code or the page it links): discussion overview, agent
  version following, the OpenAI Chat Completions row in the standards table, MCP OAuth sign-in and
  the MCP client, connections, caller identity, secret context values, editable vault grants,
  workspaces and sharing, the restricted template engine, memory guardrails, the `secret` property
  scope, RAG crawler / upload / scheduled sources, LLM telemetry meters and span, global variables,
  the Manager approvals page, the Workforce workspace and the `/manage` / `/workforce` / `/chat`
  entry points, the loopback-by-default compose bind, the
  `ollama-nvidia`, `mcp-sidecar` and `openwebui` compose files, `mise.toml`, an upgrade callout
  under *Updating*, and ten rows in the documentation table.
- **Corrected**: embedding providers 7 → 8 (Gemini was missing; `EmbeddingModelFactory`),
  memory visibility `global`/`agent`/`group` → `self`/`group`/`global` (`Property.Visibility`),
  prompt-snippet syntax `{{snippets.x}}` → `{snippets.x}` (Qute), GDPR erasure "across 6 stores"
  → every store (the cascade now has well over a dozen steps), MCP circuit breaker "60s cooldown"
  → "3 failures within 60 s" (`McpToolProviderManager`), `install.sh --full` selects PostgreSQL.
- **Removed**: the ZAP DAST line from the CI security gates. `ci.yml` removed that job; its
  comment says not to cite DAST until it is rebuilt.

### Decisions

- No concrete image tag is written, so the README stays out of the release-pointer sweep until the
  6.5.0 image exists.
- The compose bind-address variable for EDDI is not named in the README: `ConfigurationReferenceCoverageTest` treats every documented `EDDI_*` name as a property unless its allow-list of compose variables lists it, and that list does not include this one yet. The upgrade guide words it the same way.
- Screenshots are unchanged: none of the new surfaces has one yet, and a caption must not
  describe a picture that does not show it.

---

## 🔒 fix(schedules): a configuration PUT no longer re-enables a schedule someone else disabled (2026-09-30)

**Repo:** EDDI (`fix/schedule-put-preserves-enabled`)

### What changed and why

`PUT /schedulestore/schedules/{id}` wrote every field, including `enabled`, from the
request body. It had no version or expected-state check. The Manager's schedule editor
sent `enabled` from the copy it had read. If another operator disabled the schedule after
that read, the editor's Save switched it back on, and the schedule fired again. Leaving
`enabled` out of the body would not have helped: the Java model defaults it to `true`, so
an omitted value read as "enable". CodeRabbit raised this on labsai/EDDI#854 (review
5357550681) as a server-contract gap outside that UI PR.

The fix makes `enabled` a switch that only `/enable` and `/disable` flip. That is option 1
of the two considered.

- **Both stores' `updateSchedule`** leave `enabled` out of the write: the Mongo `$set`
  list and the Postgres `UPDATE … SET` list. The stored value is kept inside the same
  atomic write, so no read-then-write window exists. This is the same treatment
  `fireStatus` and `failCount` already get. The Postgres parameter indexes after
  `max_cost_per_fire` shift down by one.
- **`RestScheduleStore.updateSchedule`** copies the stored `enabled` onto the body in
  `carryOverNonEditableFields`. This keeps the in-memory body consistent with the row;
  the store is what guarantees the behaviour. A PUT with `"enabled": false` does not
  disable either. The OpenAPI description of the PUT says so.
- **Manager editor** (`ui/manager/src/pages/schedules.tsx`): an edit no longer sends
  `enabled`. A create still sends `true`. The row toggle already used `/enable` and
  `/disable`.
- **Docs:** [`scheduling.md`](scheduling.md#enabling-and-disabling) has a new "Enabling
  and disabling" section, plus notes on the field and PUT rows.
  [`import-export-an-agent.md`](import-export-an-agent.md#what-happens-to-schedules-on-import)
  notes the effect on imports.

### Behaviour changes to know

- Editing a one-shot that has already fired, and so disabled itself, no longer re-arms
  it. Save the new time, then call `/enable`.
- An import with `strategy=merge` that updates an existing schedule now keeps the
  target's `enabled` state instead of taking the archive's. This matches how a merge
  already keeps the schedule's owner. Rollback of a failed import needs no change,
  because the import never alters `enabled` on an existing schedule.

### Tests

- `RestScheduleStoreTest`: a stale `"enabled": true` body, or one that omits `enabled`,
  keeps a stored `enabled=false` and never calls `setScheduleEnabled`. A body with
  `"enabled": false` does not disable.
- `MongoScheduleStoreTest` (unit) and `PostgresScheduleStoreUnitTest`: the update never
  names `enabled`. The shifted Postgres parameter indexes are re-asserted.
- `datastore/mongo/MongoScheduleStoreTest` and `PostgresScheduleStoreTest`
  (Testcontainers; CI runs them) cover the race: read, then `setScheduleEnabled(false)`,
  then `updateSchedule(staleCopy)`. The edit lands and the schedule stays disabled. The
  Postgres IT that expected a PUT to disable now asserts the opposite.
- Manager `schedules-regressions.test.tsx`: an edit's PUT body has no `enabled`, even
  when the snapshot the dialog opened on is stale, and Save never calls `/enable`.

---

## 📝 docs(upgrade): an upgrade guide from 6.4 to 6.5 (2026-09-30)

**Repo:** EDDI (`docs/650-upgrade-guide`)

### Why

An operator upgrading an existing 6.4.x deployment had no single place that says what breaks and
what to change. Several 6.5 changes take a working 6.4 deployment down without one: a Keycloak
realm imported from the 6.1–6.4 realm file answers `401` on every request, an IdP that delivers
roles outside `realm_access/roles` answers `403`, `helm upgrade` with the in-chart MongoDB refuses
to render, and a weak vault master key stops a production boot.

### What changed

- New [`upgrading-from-6.4.md`](upgrading-from-6.4.md), in the shape of
  [`upgrading-from-5x.md`](upgrading-from-5x.md): before you start (backup, no mixed versions,
  no rollback without the backup), the changes that break a working deployment, then
  authentication, refusals, outbound HTTP, Helm/Kubernetes/Compose/image, client-visible REST, MCP
  and SSE changes, automatic database changes, the extra checks for a database that 6.4 migrated
  from 5.x, and how to check the result. Each item says what changed, who is affected and what to
  do, and links the detailed page instead of repeating it.
- Linked from the README documentation table and `docs/SUMMARY.md`, next to the 5.x guide.

### Decisions

- Every item was checked against the code on this branch (property, `@ConfigProperty` default,
  model field, refusing code path, role annotation, chart template), not taken from changelog
  entries. An earlier draft said HTTP calls resolve `${vault:…}` in URL, body and query "for the
  first time"; 6.4.0 already did, so the guide describes what is actually new: the rule that a
  credential reference must come from the configured template, and the fail-closed handling of
  an unresolvable one.
- The guide warns that two boot-time steps delete duplicate documents (`usermemories` identities
  and deployment rows), which is why the backup comes first.
- No concrete image tag is written: the release pointers still name 6.4.0 until the post-release
  update, so the guide tells Helm users to set `eddi.image.tag` explicitly.

---

## 📝 docs(upgrading): say that stored conversations and config history keep 5.x credentials (2026-09-30)

**Repo:** EDDI (`fix/6.5-release-readiness`)

### Why

[`upgrading-from-5x.md`](upgrading-from-5x.md) §7, "What is not migrated automatically", listed
plaintext credentials in agent configs, but not the copies EDDI 5 left elsewhere. EDDI 5 stored
whatever a turn carried, and no migration rewrites stored conversations. A rehearsal against a
production 5.5.1 database found credential-named fields in every one of its 195 conversations:
- properties copied from plaintext configs;
- a user token sent as context, in 174 of them;
- recorded `Authorization` request headers, in 93.

The config `.history` collections kept the plaintext as well. An operator following the guide would
have believed the upgrade left no credentials outside the vault and the named backups.

### What changed

- §7 now names both leftovers, stored conversations and the config history, and says why rotating
  every credential a 5.x deployment used is the only complete fix.
- A `mongosh` script counts credential-named fields per collection and prints counts and field names,
  never values. It walks every nested object and array, so it doesn't depend on the stored shape, and
  it also catches property instructions that name the credential in a value (`{name: "…",
  valueString: …}`), which is how plaintext keys sit in property-setter configs. Verified against the
  5.5.1 dump: it found every location found by hand during the staging migration (the conversations,
  API-call and LLM configs and their history, the property setters and their history).
- §8 points back to it.

---

## 🔖 chore(release): main builds 6.5.0 (2026-09-30)

**Repo:** EDDI (`fix/6.5-release-readiness`)

### Why

6.4.0 shipped on 2026-09-15, before the automatic post-release bump existed
([release-versioning.md](release-versioning.md), "After a GA Release"). Nothing moved the build
version afterwards, so `main` kept publishing `6.4.0-b<N>` snapshots for two weeks after 6.4.0 was
released. A pre-release suffix sorts before the release, so each of them looked older than the version
it came after.

### What changed

- `pom.xml` `6.4.0` → `6.5.0`, via `python scripts/bump-version.py next 6.5.0`. Everything that derives
  the build version (`application.properties`, the OpenAPI document, the image label, the Manager's
  sidebar) follows it.
- The two illustrative version examples that aren't release pointers now say 6.5.0: the Red Hat
  certification workflow's input description, and three help and comment lines in `install.sh`. The
  lines about realms imported from 6.1.0–6.4.0 are history and stay.

### Deliberately not changed

The release pointers (Helm `appVersion`, the k8s base and quickstart, the docs' copy-pasteable
commands) stay on 6.4.0. They name the version a reader should deploy, and no 6.5.0 image exists yet.
The post-release PR that `ci.yml` opens after the `6.5.0` tag's image is published moves them.

---

## 🔧 chore(ci): PR #846 reconciled with main — main's chat-ui ESLint kept, the CI hardening ported (2026-09-29)

**Repo:** EDDI (`chore/ci-hardening`, PR #846)

`main` moved while this branch was open: #856 / `4fdf4bedd` added ESLint to ui/chat and a `Lint` step to the `UI Chat` job, #870 slimmed AGENTS.md, #873 split the Slack notification into two sections, and #869 added release tooling (`release-pointers`, `post-release`). Merged `origin/main` and cut this branch to what `main` still lacks.

- **Kept main's:** `ui/chat/eslint.config.js`, `ui/chat/package.json`, `ui/chat/package-lock.json`, `ChatInput.tsx`, `ChatWidget.tsx` and `ui/chat/AGENTS.md` are exactly `main`'s. This branch's parallel ESLint setup (the `react-refresh` plugin, a `toBeGreaterThan(-1)` rule) and its lint fixes are dropped; `main`'s lint already passes on `main`'s sources. The duplicate `Lint` step in `UI Chat` is removed, leaving `main`'s (after `Type check`). `ui/chat` no longer differs from `main` at all.
- **Ported unchanged**, because `main` has none of it: `docker` needing `e2e-auth` + `e2e-gate`; `NODE_VERSION` for every `setup-node` and the verified Node archive in Build Image; the changelog guard's `--check` step, fence-aware heading count and collation-branch check; `.gitignore` anchoring and its `backend` filter entry; the `vitest-security` Dependabot groups; the Manager NUL escapes and `source-control-chars.test.ts`; the Jlama dev-mode flag in the docs; the realm checks in `DeploymentManifestsTest`; best-effort Slack delivery.
- **Merged by hand:** `BuildQualityGatesTest` keeps both this branch's tests and `main`'s `slackSectionsStayWithinTheFieldCap`. That test's Javadoc and the ci.yml comment beside the payload now say `curl -sf` was the old behaviour. `slackNotificationIsBestEffort` now skips shell comments, since that comment names `curl -sf` and the test was grading it as a command. In AGENTS.md, `main`'s text stays and only the Jlama flag and the changelog-guard description are applied on top.

**Files:** [`ci.yml`](../.github/workflows/ci.yml), [`BuildQualityGatesTest.java`](../src/test/java/ai/labs/eddi/BuildQualityGatesTest.java), [`AGENTS.md`](../AGENTS.md)

---

## 🔀 fix(deploy): deployment defaults reconciled with main's infra hardening (2026-09-29)

**Repo:** EDDI (`fix/deployment-defaults`) — merge of `origin/main` after
PR #865 (`fix/security-infra`) and the frontend CSP batch landed overlapping fixes.

### Reconciled with main (main's version kept, this branch's duplicate dropped)

- **Helm MongoDB authentication.** Main requires `mongodb.rootPassword`, guards
  pre-auth upgrades with `mongodb.authMigrated` and URL-encodes the credential in
  the projected Secret. This branch's `mongodb.auth.*`, `eddi.enabledUnlessFalse`,
  the mounted `mongodb-secrets.properties`, the chart 3.0.0 bump and the matching
  `manifest-lint` cases are gone; the chart stays at main's 2.2.0.
- **Helm datastore NetworkPolicies.** Main's `templates/networkpolicy.yaml` isolates
  MongoDB, PostgreSQL, NATS and Keycloak behind `networkPolicy.enabled`;
  `datastore-networkpolicy.yaml` and `networkPolicy.datastores` are dropped.
- **Kustomize MongoDB NetworkPolicy:** main's `mongodb-networkpolicy.yaml` kept;
  the PostgreSQL policy this branch adds is renamed `postgres-networkpolicy.yaml`
  to match.
- **Loopback binds** in every compose overlay, `EDDI_BIND` and Keycloak's
  `KEYCLOAK_BIND` (this branch's `KEYCLOAK_BIND_ADDRESS` renamed to it).
- **`/chat` framing split** (`csp-chat`, per-filter `X-Frame-Options`,
  `form-action`/`base-uri`/`object-src`) and main's `CspPolicyTest` cases.
- **Compose admin credentials.** Main's semantics: `docker-compose.auth.yml` reads
  `KC_BOOTSTRAP_ADMIN_USERNAME`/`_PASSWORD` and `docker-compose.monitoring.yml`
  `GRAFANA_ADMIN_USER`/`_PASSWORD`, each defaulting to `admin` — a loopback-only
  dev default, so a plain `docker compose up` starts without any setting. This
  branch's required `KEYCLOAK_ADMIN_PASSWORD` / `GRAFANA_ADMIN_PASSWORD` (`:?`),
  the `.env.example` block, the Manager's `docker-compose.keycloak.yml`
  requirement and the Linux `eddi update` pre-flight for them are gone.
  `ComposeStackTest` now pins the overridable-with-default form instead.
- **GCP provisioner:** main's scoped firewall, VM-side Keycloak admin password
  (`/root/.eddi-keycloak-admin`, exported as `KC_BOOTSTRAP_ADMIN_*`) and
  SSH-tunnel banner; `install.sh` keeps an exported value rather than generating
  one, and records it in `.env`.
- `ci.yml`, `README.md` and the Helm install snippets in
  [`getting-started.md`](getting-started.md) and
  [`kubernetes.md`](kubernetes.md) take main's text (`mongodb.rootPassword`,
  the OIDC-off opt-ins).

### What this branch still adds on top of main

No realm account ships a password and `eddi-frontend` refuses the password grant
(all three realms, the E2E realm generator, the installers' repair of existing
realms); both installers generate the Keycloak and Grafana admin passwords and
record them in `.env` under main's names (`KC_BOOTSTRAP_ADMIN_*`,
`GRAFANA_ADMIN_USER`/`_PASSWORD`), move a running service still on `admin`/`admin`
to them, and warn (rather than stop) when a running service rejects a stored or
operator-supplied password, while a Grafana password generated by the same run
that Grafana rejects stops the install before the success banner; Kustomize MongoDB authentication and the
PostgreSQL NetworkPolicy; `postgres-secret.yaml.example`; Grafana's `grafana-admin`
Secret and the namespaced Prometheus Role; no service-account token in any pod
(Helm `serviceAccount.automountToken`); CSP `img-src blob:` and
`eddi.csp.extra-connect-sources`; the explicit `/q/metrics` policy; Helm values
for `eddi.metrics.httpPolicy`, `eddi.chat.frameAncestors` and
`eddi.oidc.resourceMetadata.resource`; the `AuthStartupGuard` message fix; and
`Dockerfile.demo` copying `ui/`.

### Known gap

The Helm datastore NetworkPolicies stay main's opt-in (`networkPolicy.enabled`,
default `false`). On a default Helm install MongoDB and PostgreSQL are protected by
their passwords only, and the in-chart NATS is reachable from every pod in the
cluster with no authentication.

---

## 🔒 chore(engine): PR #831 reconciled with #859/#860 — main's implementation kept where both fixed the same finding (2026-09-29)

**Repo:** EDDI (`fix/reserved-context-keys`)

`main` merged an independent fix for the same review (#859: `ClientContextGuard`; #860: converse ownership checks; #871: `ConversationGroups`). Merged `origin/main`, every conflict resolved to `main`'s side, then cut the branch to its delta:

- **Kept from `main`:** `ClientContextGuard` and its operator knob `eddi.conversation.client-context.permitted-reserved-keys` as the only reserved-key boundary (REST start/say, streaming, MCP trigger start); `converse_with_agent`'s ownership check and its refusal of unbound or ownerless continuations (C6); the fail-closed group-policy carry-over; the removed `groupId` property fallback and the shared `ConversationGroups` resolver (C3c's first half).
- **Dropped from this branch:** `ReservedContextKeys` and the strip in `ConversationService`'s `conversationId` overloads (Slack and `/v1` send no client context, so `main`'s boundary loses nothing), its duplicate boundary tests, and the started-conversation set (`dynamic:delegated_conversation_ids`) that let `converse_with_agent` continue only conversations it had started.
- **Ported onto `main`'s classes:** the exact-key reads and the prefix drop (into `ClientContextGuard`, respecting the permitted-keys knob), the earlier-step `groupId` verification (into `ConversationGroups`, now covering the write boundary too), and the delegation USE check — extended to continuations, which the started set used to cover, since `main`'s ownership rule admits any conversation of the same user.
- **Unchanged from this branch:** C3b creator marker, M-A1 recruitment check and admin flag, M-T2 retained-set seeding.
- `main`'s `CreateSubAgentTool` prompt-escaping tests (#858) now stub the origin-stamping `setupAgent` overload.

---

## 🔒 fix(secrets): secret-scope branch reconciled with main (2026-09-29)

**Repo:** EDDI (`fix/secret-scope-vault`)

Main fixed the shared auto-vault slot (C2) while this branch was open, with per-write slots in `AutoVaultedSecrets`, and landed the secret-input scrub of #856. Where both sides fixed the same thing, main's implementation is kept and this branch's copy is dropped. What this branch still adds was ported onto main's code.

### Dropped (main covers it)

- The per-conversation key `<agentId>.<conversationId>.<property>` (`AutoVaultReference`), the resolver-cache invalidation and the ignored `tenantId` property — main's slot is per write, so nothing is overwritten, and main keeps reading the tenant from the property.
- The conversation id allocated before the first turn: `newConversationId`, the `unpersisted` flag, `IAgent.startConversation(conversationId, …)`. Main's slot name does not need it.
- `deleteConversationSecrets`, the orphan sweep and `conversationExists` — main deletes slots with the conversation, in the retention sweep and on GDPR erasure.
- The refusal of legacy `<agentId>.<property>` references — main still accepts them in older conversations.
- The move of `ConfigReferenceGuard` to `ai.labs.eddi.secrets`. It stays in `modules.apicalls.impl`, now public, with `requireConfiguredParameters` added.
- Map-key replacement for short secrets. Main never renames a key for a short secret, so the new `SecretValueScrubber` exact mode replaces values only.

### Kept, on main's code

- [`SecretPropertyVault`](../src/main/java/ai/labs/eddi/modules/properties/impl/SecretPropertyVault.java) holds main's `autoVaultSecret` and step scrub, unchanged. `PropertySetterTask` and `PrePostUtils` both call it:
  - a `fromObjectPath` value, which is not rendered again, as main decided;
  - a refusal of typed values;
  - the httpcall, MCP and LLM pre-request and post-response instructions;
  - a check of the name at run time;
  - `SecretScopeValidation` when a configuration is saved.
- The parameter guard for LLM tasks, cascade steps and judges. It runs on the HITL-resume path too, through `resolveStepModel`.
- The `ApiCallExecutor` echo and exception redaction, and `RequestRedactor.safeResponseLog`.
- Exact-match scrubbing of short secret context values. It is a new `EXACT` mode of `SecretValueScrubber` beside main's whole-token mode, and it reaches `Conversation` and `TurnAuditBuffer`.
- The `AgentSetupService` fail-closed vaulting and the vaulting of `apiAuth`.


### CodeRabbit review of `d44edc478`

- **Scrubbed longTerm properties are written back.** `Conversation` kept the *same* `Property` objects in its longTerm baseline. The turn-end secret scrub changes a property in place, so a loaded longTerm property that it scrubbed still equalled its baseline and was never written: the user-memory store kept the secret. This was main's baseline code; the ≥ 8-character scrub hit it already, and the exact match for short values made it reachable for PINs too. The baseline now holds independent copies.
- **`apiAuth` references are recognised, not guessed.** `AgentSetupService.vaultApiAuth` used to treat any value containing `${` as a reference and store it as given. Now:
  - A value that is exactly a supported reference (`vault`, `eddivault`, `connection`, `vars` or `caller`), optionally after a scheme, is kept.
  - A value that mixes a reference with other text is refused.
  - Anything else is vaulted, so a literal such as `Bearer test-token${` no longer reaches the httpcalls in plaintext.
- **Post-response output cannot render a token its own instructions vaulted.** `PrePostUtils.runPostResponse` scrubs the vaulted plaintexts from the template data before it builds the output and quick replies. `{tokenResponse.access_token}` in an output template no longer reaches the conversation output.
- **An oversize JSON body is redacted before it is cut.** `ApiCallExecutor` parses and scrubs the whole application/json body first, then serializes and truncates it. Cutting first made the body invalid JSON, and the text fallback that followed misses secrets under 8 characters. A body that is genuinely invalid JSON still falls back to text redaction.

### CodeRabbit review of `cd0515dc1`

- **Non-string secret values from a response are refused.** In an httpcall, MCP or LLM instruction with `scope: "secret"`, a `fromObjectPath` value that is a native object, array, number or boolean now fails the turn. It used to be replaced by an empty string, which the secret branch then skipped, so the secret was dropped silently.
- **Vault references survive the template-data scrub.** The scrub after a post-response instruction now leaves `${vault:…}` / `${eddivault:…}` references intact and never renames map keys: `SecretValueScrubber.scrubDeepKeepingReferences`. Otherwise a plaintext that occurs inside the property name broke two things: the property's own reference, because the slot name ends in the name, and its key in `properties`. The references are found by a linear scan, not a regex, so text full of unterminated `${vault:` openings cannot make the scrub quadratic (CodeQL `java/polynomial-redos` on the first version).
- **Setup errors no longer carry the vault provider's message.** The detail is logged on the server; the caller gets the guidance only.

---

## 🔒 fix(slack): reconcile the Slack HITL binding branch with main's channel-identity fix (2026-09-29)

**Repo:** EDDI (`fix/slack-hitl-binding`)

`main` landed `fix/security-channel-identity` (#861) while this branch was open, fixing the same Slack findings a different way: persisted approval-card records with a per-card id (`ISlackApprovalRecordStore`), event signatures bound to the channel's owner, DMs routed to the signing integration, mandatory `slack:<team>:<user>` ids with the workspace taken only from a declared `platformConfig.teamId`, and unique integration names. Wherever both sides fixed the same thing, **main's implementation is kept** and this branch's is dropped:

- Dropped: the `channelIntegrationId` start context and group origin mapping (main's approval records bind a decision to the card its integration posted); the `<integration>|<subject>|<pauseId>` button value and "out of date" / no-buttons card variants (main's card id is stronger); `SlackEventEnvelope` and `SlackSignatureVerifier.matchingSecret`; the optional `platformConfig.appId` pin; the routed-only name uniqueness check; the opt-in `eddi.slack.namespace-user-ids` / `eddi.slack.legacy-team-id` settings with the bare-id memory copy, and `SlackIdentityStartupCheck`; team-scoped (`T…:U…`) approver entries (a declared `teamId` covers the case, below).

What this branch still adds on top of main's Slack code:

- **A Slack decision carries the pause its card was checked against.** `SlackInteractivityHandler` matched the clicked card to the subject's current pause and then resumed in a second step, so a resume and re-pause in between went undetected (main documented this as a residual). The decision now carries `HitlDecision.pauseId`, which the engine re-checks under the resume CAS and in the group resume; a mismatch marks the card "already resolved".
- **Routes found by a sender-chosen timestamp are bound to the signing app.** The webhook binds a signature to the channel's owner, but a DM thread lock and a group-discussion follow-up are looked up by a timestamp in the body. `EventOrigin` now carries the verified signing secret, and every route the handler takes must belong to an integration or legacy connector holding it (`SlackEventHandler.routeMatchesSigner`). A DM thread reply takes its credentials only from an app that holds the secret *and* serves the locked target (`ChannelTargetRouter.threadCredentialsForDm`) — previously another app's secret could continue it, and the reply had no bot token at all. A follow-up must be in the discussion's own channel and come from the app that started it, and is answered with that route's token. A DM signed by a legacy connector goes to the legacy connector holding its secret (`resolveLegacyDefaultForDm`) instead of the first new-style integration.
- **An approver click from another workspace is refused when the integration declares its `teamId`.** A bare Slack user id is unique within one workspace only, so in a shared (Slack Connect) approval channel a user of another workspace could carry a listed approver's id; `SlackInteractivityHandler` now compares the clicker's `user.team_id` with the declared workspace.
- **A channel integration name may not contain `|`**, which separates the name from the subject and card id in a Slack button value; such a name made every decision on its cards unresolvable. An integration stored earlier with such a name still loads and routes, but its approval cards (conversation and group) are posted without buttons, with a WARN naming the rule (`SlackHitlSupport.isBindableIntegrationName`) — stored configs are not renamed automatically (CodeRabbit review).
- **Group approve answers a stale `pauseId` as a pause change.** `RestGroupConversation.approveGroupPhase` and `approveGroupPhaseStreaming` caught `GroupPauseMismatchException` only as its parent `GroupDiscussionException` and answered "not awaiting approval"; they now answer `409` (streaming: a `group_error`) telling the reviewer to re-read approval-status and decide again, like the conversation endpoint (CodeRabbit review).
- **`PAUSE_CHANGED` is in the documented MCP `errorCode` lists** of `hitl.md` and `mcp-server.md` (the latter also gains `CONFLICT` and `INTERNAL`, which the tools already return) (CodeRabbit review).

**Files:** [`SlackInteractivityHandler.java`](../src/main/java/ai/labs/eddi/integrations/slack/SlackInteractivityHandler.java), [`SlackEventHandler.java`](../src/main/java/ai/labs/eddi/integrations/slack/SlackEventHandler.java), [`RestSlackWebhook.java`](../src/main/java/ai/labs/eddi/integrations/slack/rest/RestSlackWebhook.java), [`ChannelTargetRouter.java`](../src/main/java/ai/labs/eddi/integrations/channels/ChannelTargetRouter.java), [`RestChannelIntegrationStore.java`](../src/main/java/ai/labs/eddi/configs/channels/rest/RestChannelIntegrationStore.java), [`SlackHitlSupport.java`](../src/main/java/ai/labs/eddi/integrations/slack/SlackHitlSupport.java), [`SlackGroupDiscussionListener.java`](../src/main/java/ai/labs/eddi/integrations/slack/SlackGroupDiscussionListener.java), [`RestGroupConversation.java`](../src/main/java/ai/labs/eddi/engine/internal/RestGroupConversation.java); docs [`slack-integration.md`](slack-integration.md), [`hitl.md`](hitl.md), [`mcp-server.md`](mcp-server.md).

---

## 🔐 fix(vault): reconcile key safety with main's secrets-crypto hardening (2026-09-29)

**Repo:** EDDI (`fix/vault-key-safety`, merge of `origin/main`)

`main` landed a parallel pass over the same code (the 2026-09-26 "security(secrets): vault master-key, checksum, crypto and reference hardening" entry).
Where both sides fixed the same thing, main's implementation is kept and this branch's copy is dropped;
what main still lacked is ported onto main's code.

**Kept from main (this branch's version dropped):**

- **Secret AAD (L-S1).** Main's `tenantId|keyName|dekId` associated data with the no-AAD fallback and no
  prefix. The `a1:` marker, the `String` AAD overloads of `EnvelopeCrypto` and the tenant/key binding are
  gone — one on-disk format. The DEK-wrapping AAD is dropped too: main does not bind DEK wrappings, and a
  second wrapping encoding is not worth a low-value binding (a swapped wrapping already fails at the
  secret's own AAD).
- **Insert-if-absent metadata.** `ISecretPersistence.setMetaValueIfAbsent` (null = no metadata store)
  replaces `putMetaValueIfAbsent`; the salt, the KEK check value and system values use it. Main's Mongo and
  Postgres implementations and tests are kept.
- **KEK rotation rollback.** Main's commit-failure rollback, weak-new-key refusal and checksum-key re-wrap
  stay. On top of them: the pending salt is persisted first, verify accepts DEKs already under the new KEK,
  the KEK check value is announced before the first re-wrap, re-wraps are guarded (`updateDekWrapping`,
  never an upsert that could resurrect a reset tenant's DEK) with a catch-up sweep, and the checksum key
  is re-read and re-wrapped last. A failed commit rolls the DEKs back and restores the check value; only if
  that rollback fails too does the announcement stay, and a re-run with the same keys completes it.

**Ported onto main's code:** salt race and fail-closed salt read (H6a), first-DEK insert (H6b),
KEK check value / stale-replica refusal and take-back (H6c, m2), audit keyring and v5 (H6d, M1), keyed
pseudonyms (L-S2), tenant-reset discard and system-tenant guard (L-S3, m4), value rotation keeps the grant
(S1), grant precondition (S6), impact-analysis `UNKNOWN` (S7), `adopt-master-key` (B1).

**Adapted to main's additions:**

- **Checksum key.** Main's KEK-wrapped checksum key is covered by the stale-replica guard (not created
  under a retired KEK), opens with the pending-salt KEK after an interrupted legacy migration, and is
  discarded by `adopt-master-key` — reported as `checksumKeyReset` — and by the empty-vault adoption at
  startup when the adopted key cannot unwrap it. Otherwise every `store()` failed for good after a lost key.
- **Late audit entries.** Main's `markUserErased` rewrite now writes the keyed v5 pseudonym whenever the
  ledger signs, so an entry flushed after an erasure does not carry the unkeyed hash L-S2 removes.
- Main's tests are kept; four needed adapting: the checksum-key write failure targets only that key (the
  announcement precedes it), the racing-creator hide applies to the checksum-key read, the wrong-old-key
  refusal message starts with `KEK rotation failed` and keeps the unwrap failure as its cause, and the
  erased-user signature check verifies through the ledger (entries are v5 now).

**Files:** [`VaultSecretProvider.java`](../src/main/java/ai/labs/eddi/secrets/impl/VaultSecretProvider.java),
[`VaultSaltManager.java`](../src/main/java/ai/labs/eddi/secrets/crypto/VaultSaltManager.java),
[`ISecretPersistence.java`](../src/main/java/ai/labs/eddi/secrets/persistence/ISecretPersistence.java),
[`AuditLedgerService.java`](../src/main/java/ai/labs/eddi/engine/audit/AuditLedgerService.java),
[`secrets-vault.md`](secrets-vault.md), [`gdpr-compliance.md`](gdpr-compliance.md).

---

## 🔀 Reconciled with main (2026-09-29)

**Repo:** EDDI (`fix/workspace-authz-scoping`), merge of `origin/main` at `9cf30b8d8`.

Main landed parallel fixes for part of this batch while the PR was open (`8d24ae335` schedule and
trigger ownership, `566631b94` parser role gate, `37a9b8336` owner-scoped schedule paging, `2eded8789`
review follow-ups, `74233c293` dream schedules). Where both sides fixed the same thing, main's
implementation was kept and this branch's copy dropped:

- **H2f parser**: main's `@RolesAllowed` + VIEW check, with main's tests. This branch adds only the
  sentence in `semantic-parser.md`.
- **H2a owner scoping**: main's `ScheduleOwnerScope`, `canAccessScheduleOwner` rule and
  `requireMutableSchedule` guard. `IScheduleStore.ListingScope` is gone; a refused direct read
  answers main's `403`, not the `404` this branch used. `requireOwnUserId` keeps main's exact
  `system:scheduler` exemption (the "any `system:` identity" widening is dropped).
- **H2b triggers**: main gates update/delete on **USE** of the stored targets
  (`requireUseOnStoredReferencedAgents`); this branch's EDIT gate and its decision-log row are dropped.

What main still lacked is ported onto main's types (`ScheduleOwnerScope`, `RestScheduleStore`):

- `ScheduleOwnerScope` gained two refinements, pushed into both queries:
  `sharedOnlyIfCreatedByCaller()` (workspaces enforced: shared rows only when `createdBy` is the
  caller, unless listing by an agent the caller may EDIT) and `withTeamCadences()` (workspaces off:
  every cadence, whoever created it). The two-argument constructor and `visibleTo` keep main's meaning.
- `RestScheduleStore`: `mayRead`/`mayAccess` replace `canAccessScheduleOwner` (HITL reads hidden
  from non-admins, team cadences reachable through their group, shared schedules owned by creator or
  by the team of what they drive under enforcement); fire logs and `/admin/failed` scoped to it;
  forged cadence bodies refused on create and on update unless the update echoes the same cadence's
  own markers (main's `guardManagedSchedule` lets group editors change a cadence's cron by PUT);
  `fireNow` needs EDIT on a cadence's group and rethrows `ForbiddenException` instead of a `500`.
- Kept from this branch unchanged (main has no equivalent): H1, H2c, H2d, H2e's `scheduleRef` check,
  H2g, H2h, H3, A1, A3, E5, the trigger listing/read filter with `TriggerNotVisibleException`, and
  the export's schedule filter (now on `ScheduleOwnerScope.isShared`).
- AGENTS.md takes main's text; the E5 self-URL sentence moved with its section to
  [`agent-config-authoring.md`](agent-config-authoring.md).

---

## 🐛 fix(conversations): review findings for version following (2026-09-29)

**Repo:** EDDI (`feat/agent-version-following`) — found by reviewing Phases 0–3 against the code
paths around them.

- **The daily deployment sweep ended conversations that could have moved.** It retires old versions
  by ending their idle conversations, then undeploying once none are left. For an old version with a
  newer compatible version ready, it now undeploys at once and ends nothing
  (`AgentDeploymentManagement.retireIfConversationsCanMove`): those conversations continue on the
  newer version whenever they return.
- **A group member's private conversation that ended mid-discussion failed the member for every
  remaining turn** (`MemberTurnExecutor`). It now continues in a fresh conversation, once; a second
  end in a row is an ordinary member failure.
- **The move was recorded before the turn was admitted.** The descriptor update and the switch
  counter ran when the version was resolved, before the quota check, so a refused turn left the
  descriptor naming a version the conversation never ran on. Both now run when the turn completes.
- **A queued turn rebuilt over a reloaded memory ran on the stored version's memory.** Main's H13a
  rebuilds a superseded queued turn over the current document, which holds the version it was
  stored on. The rebuilt memory now adopts the version resolved for the turn within its own
  generation (`ConversationService.adoptResolvedAgentVersion`), and the move is recorded against
  the version that document was stored on.
- **A failed descriptor update was never retried.** The next turn is already on the new version,
  so it saw no move and the listings named the old version for good. The memory now keeps
  `staleDescriptorAgentVersion` (persisted, absent otherwise) and every turn retries until the
  descriptor is current; the switch counter still counts the move once.
- **Undo and redo persisted properties by the wrong version's memory policy.** They read
  `userMemoryConfig` from the conversation's current version, and fell back to any version of its
  generation when that was gone. Compatible versions may default visibility differently, so they
  now read the version recorded on the step (`agent:version`); when it is not deployed the config
  stays unset and the sync falls back to never widening a scope, as it always did.
- **A failed merge import's rollback stays breaking**, deliberately: reusing the original
  generation would leave the latest version below the aborted import's, and the next breaking save
  would share a generation with that aborted version. Commented in `RestImportService`.
- **Docs**: [`docs/deployment-management-of-agents.md`](deployment-management-of-agents.md#running-conversations-and-new-agent-versions)
  has the full model; `hitl.md`, `architecture.md`, `scheduling.md`, `slack-integration.md`,
  `mcp-server.md` and `metrics.md` link to it, and the full-metrics dashboard charts
  `eddi_conversation_agent_version_switch_count`.

---

## ✨ feat(deployment): undeploy and deploy understand compatible versions (2026-09-29)

**Repo:** EDDI (`feat/agent-version-following`) — Phase 3 of
[`planning/agent-version-following-plan.md`](../planning/agent-version-following-plan.md)

### What changed

- **Undeploy** (`RestAgentAdministration.undeployAgent`): open conversations on a version no
  longer block it with 409, and are not ended by `endAllActiveConversations`, when another
  deployed version in the same environment has the same compatibility generation — they move
  there on their next turn. "Deployed" is the deployment records plus this node's registry (which
  also holds `autoDeploy=false` deployments). A version undeployed by the same call never counts:
  `undeployThisAndAllPreviousAgentVersions` runs its undeploys asynchronously, and counting them
  would strand the conversations. Everything else — no generation, a breaking successor, a record
  that cannot be read — behaves exactly as before.
- **Conversations ended by an undeploy record `agent-version-retired`**, via a new optional
  `endReason` on `POST /conversationstore/conversations/end`. Only known reasons are accepted
  (400 otherwise): the reason is shown to users, so it must never be caller-supplied text.
- **Schedules survive retiring an old version.** Undeploy disabled every schedule of the agent on
  any undeploy, so "deploy v6, undeploy v5" — the normal rollout — silently switched off every
  heartbeat. They are now disabled only when the call leaves no version of the agent deployed in
  that environment.
- **`GET /administration/{env}/deploymentimpact/{agentId}?version=N`**: for every other deployed
  version, its open conversations and whether they `FOLLOW` version N (same generation, older) or
  `STAY` (another generation, none, or newer). Requires EDIT on the agent, like undeploy.

---

## ✨ feat(conversations): conversations follow compatible versions of their agent (2026-09-29)

**Repo:** EDDI (`feat/agent-version-following`) — Phase 2 of
[`planning/agent-version-following-plan.md`](../planning/agent-version-following-plan.md)

### What changed

- **Per-turn resolution** (`ConversationService.resolveConversationAgent`, used by `say` and
  `sayStreaming`): a conversation with a compatibility generation runs on the highest `READY`
  version of that generation on this node, moving to it if it is elsewhere — forward when a
  compatible version is deployed, back when the newer one is undeployed (a rollback) or has not
  reached this node yet. A conversation without a generation, a paused one, and one whose
  generation has nothing ready here take the old path unchanged (`getAgent`, including its
  on-demand deploy).
- **Memory**: `compatibilityGeneration` on the conversation (set from the version it starts on,
  persisted on the snapshot); `agentVersion` is no longer final. `IAgentFactory` gains
  `getLatestReadyAgentOfGeneration`.
- **Every step records its version** as step data `agent:version`, and the first step after a
  move records `agent:switch = {from, to}`. The marker is not `agent:version…` because step
  lookups match keys by prefix — the first name tried shadowed the version key in the tests.
  Both are written at the **end** of the step: detailed snapshots list a step's data in
  insertion order and clients read it by position, so writing them first shifted every entry
  (`AgentEngineIT` caught it).
- A move updates the conversation descriptor's agent URI (best-effort; listings filter on it),
  logs at INFO and increments `eddi_conversation_agent_version_switch_count`. Undo/redo read the
  memory config from any version of the generation when the conversation's own is gone.

---

## ✨ feat(agents): each agent version records whether it is compatible with the previous one (2026-09-29)

**Repo:** EDDI (`feat/agent-version-following`) — Phase 1 of
[`planning/agent-version-following-plan.md`](../planning/agent-version-following-plan.md). No
behaviour changes yet; Phase 2 makes conversations act on it.

### What changed

- **`AgentConfiguration.compatibilityGeneration`**, assigned by `AgentStore` on every write and
  never taken from the body: `create` starts at 1; `update(id, version, config, compatible)` keeps
  the previous generation when `compatible` is true and advances it otherwise; the plain `update`
  is breaking. A previous version without a generation (stored before this existed) starts a new
  chain either way, so its conversations stay pinned. Only the current version can be updated,
  so the previous version always holds the highest generation and no other version is read.
- **REST**: `PUT /agentstore/agents/{id}?version=N&compatible=true` and the same flag on
  `PUT /agentstore/agents/{id}/updateResourceUri`. Absent means `false`.
- **MCP**: `apply_agent_changes` takes `compatible` (default `false`) and reports
  `compatibleWithPreviousVersion` when it wrote a new agent version. It is the only MCP tool that
  creates an agent version from an existing one.
- **Import and sync** (`RestImportService`, `UpgradeExecutor`) always write a breaking version: the
  configuration comes from elsewhere and nobody has judged it against the conversations running
  here. `StructuralMatcher` leaves `compatibilityGeneration` out of the agent comparison — each
  instance numbers its own, and comparing them made an unchanged agent UPDATE on every sync.
- **`IAgent.getCompatibilityGeneration()`**, set when a version is deployed.

---

## 🐛 fix(conversations): an ended conversation no longer strands a Slack thread or a heartbeat (2026-09-29)

**Repo:** EDDI (`feat/agent-version-following`) — Phase 0 of
[`planning/agent-version-following-plan.md`](../planning/agent-version-following-plan.md)

### Why

The idle sweep and `undeploy?endAllActiveConversations=true` both end conversations, and two
callers never recovered from that: a Slack thread kept sending to its ended conversation and was
refused on every further message, and a `conversationStrategy=persistent` schedule kept firing into
its ended conversation until the FAILED fires dead-lettered it.

### What changed

- **`ConversationService.say` / `sayStreaming`** refuse an ENDED conversation before looking its
  agent up (`rejectIfEnded`). The ended check used to come after the lookup, so an ended
  conversation whose version had been undeployed — the normal state after `endAll` — answered
  "agent not ready", which no client recovers from.
- **Slack** (`SlackEventHandler.openThreadConversation` / `sendInThread`): a mapped conversation
  that has ended or vanished is replaced by a fresh one, and a conversation that ends between
  opening and sending (including a queued turn dropped as ENDED) is replaced once and the message
  resent. When the old conversation ended because its agent version was retired, the thread is told
  first. A state that cannot be read keeps the conversation — a store hiccup never costs a thread.
- **`IUserConversationStore.deleteUserConversationIfMatches`** (Mongo + Postgres): the mapping is
  removed only while it still names the ended conversation, so two messages racing on one ended
  thread converge on one new conversation. Both stores now also report a raced duplicate insert as
  `ResourceAlreadyExistsException` (Mongo `E11000`, Postgres `23505`) instead of a raw driver
  error, which is what the existing create-race recovery listens for.
- **`ScheduleFireExecutor.resolveOrCreatePersistent`** treats an ENDED conversation like an
  unreadable one and creates a fresh conversation.
- **End reason**: `IConversationService.endConversation(id, endedBy, endReason)` records why a
  conversation ended (`endReason` on the stored and client-facing snapshots, a narrow field update
  on both stores). `END_REASON_AGENT_VERSION_RETIRED` is the first value; the undeploy path sets it
  in Phase 3.

---

## 📝 docs(planning): conversations follow compatible agent versions (2026-09-29)

**Repo:** EDDI (`docs/agent-version-following`)

### Why

A conversation is pinned to the agent version it started on for its whole life, so fixes never
reach long-running conversations and old versions cannot be undeployed without ending
conversations that could have carried on.

### What changed

- New plan: [`planning/agent-version-following-plan.md`](../planning/agent-version-following-plan.md).
  No code changes.

### Design decisions

- **Breaking is the default.** Every save produces a breaking version unless the save request
  explicitly passes `compatible=true`. Existing agent versions carry no compatibility value and
  stay pinned, so upgrading EDDI changes nothing.
- **A server-owned compatibility generation** (semver-major equivalent) decides which versions a
  conversation may move between. It is assigned by the store and never read from the config body,
  so copies, exports and agent sync cannot carry a "compatible" forward by accident.
- **The version is resolved on every turn** (highest ready version of the conversation's
  generation on this node), which covers the 10 s cluster rollout window and makes undeploying a
  faulty version an automatic rollback. Compatibility is therefore bidirectional by definition.
- **No new end-conversation policy**: keeping or ending conversations on an older generation uses
  the existing undeploy options, with a new `system:agent-version-retired` reason.
- **Undeploy counts only conversations that cannot move**, so compatible conversations neither
  block undeploy nor get ended by `endAllActiveConversations`.

- **The generation is a store-owned `AgentConfiguration` field.** Only the latest version can be
  updated (`HistorizedResourceStore.update`), so the previous version always holds the highest
  generation and the assignment needs no other lookup.
- **Two existing gaps found and scheduled as Phase 0**: a Slack thread whose conversation has ended
  fails on every further message, and a `persistent` schedule whose conversation has ended is
  retried until it dead-letters. Both will start a fresh conversation instead. Both already happen
  today (the idle sweep and `endAllActiveConversations` end conversations).

### Next

Phase 0 of the plan (ended-conversation recovery for Slack and persistent schedules), then
Phase 1 (generation stored and exposed, no behaviour change).

---

## ✨ feat(manager, chat): compatible saves, deployment impact and version markers in the UIs (2026-09-29)

**Repo:** EDDI (`feat/agent-version-following-ui`) — the Manager and Chat UI half of agent version
following; the backend ships in `feat/agent-version-following`. Against a backend without it, the
`compatible` parameter is ignored (every save stays breaking, as today) and the impact preview
hides itself when its request fails.

- **Manager — saving**: a "Compatible with the previous version" checkbox, unticked by default and
  reset after every save, on each save path that writes an agent version and has room for a choice:
  the post-save cascade dialog, the resource editor in cascade mode, the Studio editor panel, the
  workflow editor's Save & Test, and the Workforce agent editor sheet. Ticked on top of a version
  that predates generations, it says that conversations already on that version stay on it.
  The tick also resets when the page's agent context changes underneath it: the resource editor,
  the workflow editor and the Studio editor panel (agents sharing a workflow) stay mounted when only their query string changes, so a tick given for
  one agent could otherwise have written another agent's version as compatible. The workflow
  editor now also takes the agent version its next Save & Test replaces from the new context.
  `updateAgent` sends `compatible=true` only for an explicit `true`. Silent inline saves (agent
  section toggles, adding or removing workflows on the agent page) stay breaking.
- **Manager — deploying**: the agent page's Environments card shows, per environment, what the
  displayed version does to the conversations on the other deployed versions (`FOLLOW` / `STAY`
  with the reason), from `GET /administration/{env}/deploymentimpact/{agentId}`. The undeploy
  dialog notes that conversations a compatible deployed version can continue are moved rather than
  ended. A badge next to the version shows its compatibility generation.
- **Manager — conversations**: the conversation view and the debugger's memory inspector show the
  version each step ran on (`agent:version`), an "Agent moved from vN to vM" notice on the step
  after a move (`agent:switch`), and "Ended: the agent version was retired" for such an end.
  16 new keys in all 11 locales. The OpenAPI contract test exempts the new endpoint until the next
  snapshot refresh.
- **Chat UI**: a message refused because the conversation ended (410) re-reads it; when it ended as
  `agent-version-retired`, the footer says "This assistant was updated. Start a new conversation to
  continue." next to the existing new-conversation button.
- **Not built** (tracked in the plan): a compatible/breaking marker on every entry of the version
  list, and a keep/end choice inside the impact preview.

---

## 🔐 feat(secrets): atomic create-if-absent in the vault SPI (2026-09-29)

**Repo:** EDDI (`feat/secrets-store-if-absent`) · closes #700

`ISecretProvider.store` is an upsert for every caller, so a read-then-write over a secret
was a TOCTOU: two callers could both see a key as absent and both write, and the later
write silently replaced the earlier. `AgentSetupService.useNamedVaultKey` narrowed that
with a read-back after the write (#699) but could not close it.

### What changed

- **`ISecretPersistence.insertSecretIfAbsent(EncryptedSecret) → boolean`** — `false` when
  a row already existed. PostgreSQL: `INSERT … ON CONFLICT (tenant_id, key_name) DO
  NOTHING`, answering from the affected-row count. MongoDB: `insertOne`, treating a
  duplicate-key error as "already there". Neither needs a migration — both backends
  already enforce a unique `(tenant, key)` (`idx_secret_tenant_key`, and the table's
  `UNIQUE (tenant_id, key_name)`).
- **`ISecretProvider.storeIfAbsent(...) → boolean`** exposes it; `VaultSecretProvider`
  seals the value exactly as `store` does (the shared step is now one private `seal`
  method) but does not read the row first — the insert is the existence check. `store`
  is unchanged and remains the explicit upsert for rotation and `RestSecretStore`. The
  SPI method has no default implementation on purpose: a default read-then-write would
  reintroduce the race under a name that promises otherwise.
- **`AgentSetupService.useNamedVaultKey`** creates through `storeIfAbsent`. Losing the
  race no longer overwrites anything: the setup reuses the winner's entry when it holds
  the same value and fails, before creating anything, when it holds a different one. The
  best-effort `verifyStoredValue` read-back is gone. `docs/secrets-vault.md` is updated.

### Not done

Callers that intentionally upsert (`AgentSigningService` key generation,
`PropertySetterTask.autoVaultSecret`, `RestSecretStore`) are unchanged — moving them
changes what a conflict means for each and is a separate decision. The checksum
reservation for `findReusableSecret` (item 4 in the issue, marked lower value) is also
left: that path self-corrects.

### Tests

`MongoSecretPersistenceTest` and `PostgresSecretPersistenceTest` (Testcontainers) gain an
`insertSecretIfAbsent` group: insert-when-absent, an existing row left byte-for-byte
alone, tenants independent, `upsertSecret` still replacing, and eight writers racing for
one key over 25 rounds with exactly one winner whose value is the stored one. With
`DO NOTHING` swapped for `DO UPDATE`, the race and the leave-alone tests fail; without
the unique index, the Mongo ones do. Both ITs now rebuild the Mongo persistence per test,
since the collection drop between tests takes the index with it.
`VaultSecretProviderGrantTest` pins that `storeIfAbsent` never reads or upserts, and
`AgentSetupVaultKeyReuseTest` covers winning, losing to the same value and losing to a
different one.

---

## 🔒 fix(memory): claim a global memory key atomically, so a racing agent cannot overwrite it (2026-09-29)

**Repo:** EDDI (`fix/user-memory-global-owner-cas`)

### What changed and why

When `allowGlobalKeyOverwrite` is off, the `rememberFact` tool must not overwrite a
`global` memory owned by another agent. It checked ownership with a read and then
wrote with a plain upsert. Those were two steps. Two agents of the same user, both
allowed `global` visibility, could each read the key as free and each write it. The
store kept the first agent as owner but applied the second agent's value, so one
agent silently overwrote a memory the other owned (CWE-367). This was a CodeRabbit
finding on #860 and was deferred there.

- **`IUserMemoryStore.upsertIfOwnedBy(entry, agentId)`** writes a global entry only
  if its key is free or already owned by `agentId`, and returns whether it applied.
  The store makes that decision inside the write itself. An existing entry with no
  recorded owner (null or blank) counts as not owned.
- **PostgreSQL:** one `INSERT … ON CONFLICT (user_id, key) WHERE visibility =
  'global' DO UPDATE … WHERE usermemories.source_agent_id = EXCLUDED.source_agent_id
  RETURNING id`. A conflicting insert waits on the unique `idx_um_upsert_global`
  index and then evaluates the owner condition against the committed row. No
  returned row means the write was refused.
- **MongoDB:** the store now builds a **unique partial index on `(userId, key)` for
  global entries** (`idx_um_upsert_global`). Mongo had no such index before, so
  racing upserts could also create two global entries for one key.
  - `upsertIfOwnedBy` is an upsert filtered on the writer as owner. If another
    agent holds the key, the upsert attempts a second insert and the index rejects
    it with a duplicate-key error. That error is reported as a refusal.
  - A plain upsert that loses the same insert race retries once as an update.
  - `insertIfAbsent` that loses the race now returns `null` ("already there"), not a
    duplicate-key error.
  - If the index cannot be built, because a deployment already holds duplicate
    global keys, the store logs a WARN and falls back to a non-atomic check.
- **`UserMemoryTool.rememberFact`** uses the conditional write for global writes
  when `allowGlobalKeyOverwrite` is off. It returns the existing "owned by another
  agent" refusal when the write did not apply, and a refused write does not count
  against `maxWritesPerTurn`.
  - The early ownership read stays in place, only so that a doomed write does not
    trigger capacity eviction.
  - A global write with no agent id is now refused, since there is no owner to
    write as.
- **Unchanged:** REST, MCP, admin, property and `allowGlobalKeyOverwrite=true`
  writes keep the plain `upsert` semantics.

### Tests

- `UserMemoryToolTest`:
  - the conditional write is used for owned global writes and not for self writes
    or overwrite-allowed writes;
  - a lost race is refused;
  - a refused write leaves the turn budget intact;
  - a global write with no agent id is refused.
- `PostgresUserMemoryStoreUnitTest` and `configs/properties/mongo/MongoUserMemoryStoreTest`
  (mocked):
  - the conditional SQL and filter shape;
  - no returned row, or a duplicate key, is a refusal;
  - other write errors propagate;
  - the index options;
  - the no-index fallback;
  - the plain upsert's duplicate-key retry.
- `datastore/postgres/PostgresUserMemoryStoreTest` and `datastore/mongo/MongoUserMemoryStoreTest`
  (Testcontainers):
  - free, own, other-agent, ownerless and blank-owner keys;
  - 8 agents racing for a fresh key over 10 rounds, with exactly one winner whose
    value and ownership stick.
- The Mongo IT also checks that the index exists and that 8 concurrent plain
  upserts leave one entry.

**Files:** [`IUserMemoryStore.java`](../src/main/java/ai/labs/eddi/configs/properties/IUserMemoryStore.java),
[`PostgresUserMemoryStore.java`](../src/main/java/ai/labs/eddi/datastore/postgres/PostgresUserMemoryStore.java),
[`MongoUserMemoryStore.java`](../src/main/java/ai/labs/eddi/configs/properties/mongo/MongoUserMemoryStore.java),
[`UserMemoryTool.java`](../src/main/java/ai/labs/eddi/modules/llm/tools/UserMemoryTool.java)

---

## 🐛 fix(memory): MongoDB `usermemories` enforces its upsert identities (2026-09-29)

**Repo:** EDDI (`fix/usermemories-unique-identity`) · closes #892

`MongoUserMemoryStore` keyed its upserts on two identities — one shared entry per
`(userId, key)` among `global` entries, one per `(userId, key, sourceAgentId)` among the
non-global ones — but no index enforced either. Two first writes racing for the same
identity both missed the filter and both inserted, and every later upsert then updated
whichever copy the server found first. That held for ordinary `upsert` and
`mergeProperties` writes and for `insertIfAbsent`, which the legacy
`PropertiesMigrationService` uses: #871 stopped it replacing newer values, but two nodes
migrating at once could still insert the same entry twice. PostgreSQL has enforced the
same identities since the store was written (`idx_um_upsert_global`,
`idx_um_upsert_agent`).

### What changed

Since this branch was opened, #893 landed the same global index
(`idx_um_upsert_global`) and a duplicate-key retry for its owner-checked writes, but
left two gaps: it could not be built over data that already holds duplicates (the store
then fell back to a non-atomic ownership check), and nothing enforced the per-agent
identity. This change keeps #893's write path as it is and closes both.

- **`UserMemoryIdentityIndexes`** (new) runs at startup, before the store's own global
  index build. While either index is missing it merges duplicates, then installs both
  partial unique indexes: `(userId, key)` filtered to `visibility: global` — the same
  name and spec #893 builds, so the two never conflict — and
  `(userId, key, sourceAgentId)` (`idx_um_upsert_agent`) filtered to
  `visibility: {$in: [self, group]}`; a partial filter accepts `$in` but not `$ne`.
- **Merge.** Per duplicated identity the entry with the newest `updatedAt` survives
  (compared as parsed instants: `Instant.toString()` has a variable-length fraction, so
  string order is wrong) and takes the summed `accessCount`. Once both indexes exist the
  scan is skipped. A duplicate that another node inserts between the merge and the build
  triggers one more pass. Any failure is logged and startup continues, so #893's
  `globalKeyUnique` fallback still applies exactly as before. With the merge in place
  that fallback becomes the exception rather than the rule for upgraded deployments.
- **No new write-path code.** The duplicate-key retry and the `insertIfAbsent`
  "already present" answer are #893's; this branch originally carried its own and
  dropped it in favour of that one on merge.

### Tests

`datastore/mongo/MongoUserMemoryStoreTest` (Testcontainers, `mongo:6.0`) gains a
`Unique upsert identities` group: eight writers racing for one identity, 25 rounds each,
for global upserts, per-agent upserts (self and group mixed), `mergeProperties` and
`insertIfAbsent`; the startup merge; and the indexes refusing a duplicate written around
the store. With the startup call removed, six of the seven fail. The class now rebuilds the store after each
collection drop, so all its tests run under the new indexes. The two mock-based store
tests stub the index listing (`IdentityIndexStubs`).

---

## 🐛 fix(migration): a 5.x database now boots on 6.5 with its agents deployed, its conversations intact and no tokens in memory (2026-09-29)

**Repo:** EDDI (`fix/v6-first-boot-blockers`)

### Why

We rehearsed the upgrade against a restored production 5.5.1 database with both v6 migration flags on. Every deployed agent ended in ERROR, every old conversation loaded with empty steps, and the properties migration copied 159 learners' platform bearer tokens into `global` long-term memory. There were four separate causes.

### What changed

**1. A unique index from before 6.3 no longer stops the descriptor store from starting.** Databases created before 6.3 hold `descriptors.resource_1` with `unique: true`. 6.x asks for the same key non-unique, and MongoDB refuses with `IndexKeySpecsConflict` (86). The exception escaped the store's constructor, so no agent could deploy and `/descriptorstore` answered 500. [`MongoResourceStorage.ensureIndex`](../src/main/java/ai/labs/eddi/datastore/mongo/MongoResourceStorage.java) now reads the collection's indexes, following the same approach as the deployment store (#781):
- An index on the same key with another specification (a different `unique` flag, or partial, sparse, hidden, or with a collation other than the collection's default — none of which serves this store's queries) is dropped and rebuilt to the current specification. That includes one under another name: the server accepts a non-unique index beside it without any error, and the unique one would go on refusing writes.
- An equivalent index under another name (85) is kept.
- An index that holds the generated name on a *different* key is never dropped; that case is logged, the stale index on our key is still replaced, and ours is built as `<name>_eddi`.
- A conflict that can't be resolved is logged at ERROR, and the store starts anyway.

The index is rebuilt non-unique on purpose. Since 6.3, neither backend enforces uniqueness on `resource`: PostgreSQL's expression index isn't unique, and a MongoDB created by 6.3 never had it.

**2. The LLM workflow step type is renamed.** A v5 workflow names its LLM step `eddi://ai.labs.langchain`. The rename migration's URI rewrites end in `/` so that they match config URIs only, so the bare step type never matched, and every agent with an LLM step failed with `Extension 'ai.labs.langchain' not found`. The slash can't simply be dropped: `ai.labs.behavior` and `ai.labs.httpcalls` are still the registered step ids, even though their config URIs moved. [`V6RenameMigration.STEP_TYPE_REWRITES`](../src/main/java/ai/labs/eddi/configs/migration/V6RenameMigration.java) is an exact-match table for step `type` fields in `workflows` and `workflows.history`. Its only entry is `langchain → llm`, the one v5 step type (of seven in the database) that 6.x doesn't register. `V6RenameMigrationStepTypeTest` builds the real extension registry by running every bootstrap module, and checks both sides of the table against it. [`LlmModule`](../src/main/java/ai/labs/eddi/modules/llm/bootstrap/LlmModule.java) also registers `ai.labs.langchain` as an alias of the LLM task, as `ai.labs.behavior`/`ai.labs.rules` already are. The migration rewrites step types only on the boot that runs it: a database migrated by 6.0–6.4 recorded the migration complete with the old type still in place, and a v5 ZIP imports it verbatim. The test requires any v5 type that stays registered to be an alias of the same task.

**3. Old conversations keep their history.** EDDI 5 stored a step's runs as `conversationSteps[].packages`. The 6.x snapshot serialises them as `workflows` and ignored the unknown key, so a v5 conversation loaded 200/READY with no data in any step, and the next save wrote the empty steps back. There are two fixes:
- The migration renames the stored shape server-side: one `updateMany` with an aggregation pipeline, because `$rename` can't reach array elements. It covers `conversationSteps` and `redoCache`, and also renames `originPackageId` to `originWorkflowId` inside each result. It matches per array element — a step that holds `packages` and whose `workflows` is absent, null or empty — so a second run is a no-op. A step that holds both keys with a non-empty `workflows`, or neither key, is left unchanged and counted in a WARN.
- [`ConversationMemorySnapshot`](../src/main/java/ai/labs/eddi/engine/memory/model/ConversationMemorySnapshot.java) accepts both old keys on read (`@JsonAlias`), as a safety net for documents the migration hasn't reached.

**4. Credentials are not migrated into long-term memory.** [`PropertiesMigrationService`](../src/main/java/ai/labs/eddi/configs/properties/mongo/PropertiesMigrationService.java) now leaves two kinds of key behind:
- the keys in the new `eddi.migration.properties.skip-keys` (default `userInfo`, the 5.x per-request identity: a token next to names and ids);
- any key whose value [`SecretScrubber.containsCredential`](../src/main/java/ai/labs/eddi/secrets/sanitize/SecretScrubber.java) flags. That check uses the export redaction rules unchanged, and doesn't modify anything. Every field name at any depth, lists inside lists included, is also checked on its own, since the scrubber judges `apiKey: {value: "…"}` by the name `value`. BSON `ObjectId` values are identifiers by type and are left out of it; as extended JSON their hex string would trip the entropy rule.

Skipped keys are logged by name and count, never by value, and they aren't failures: the values stay in `properties_migrated_v6`. [`user-memory.md`](user-memory.md) documents the cleanup query for a database that already ran the old migration.

### Decisions

- `resource_1` is rebuilt **non-unique** rather than kept unique; see 1.
- The scrubber's entropy rule is kept for the properties migration. On the rehearsal database it also skips five 17-character `createdProgram.courseId` values. That is accepted: they stay in the backup collection, and weakening the check for `*Id` fields would weaken it for session ids too.
- The step-type table lists only the types that no longer resolve. A type that is still registered is never rewritten, even when its config URI moved.

### Tests

`MongoResourceStorageIndexConflictTest`, `V6RenameMigrationStepTypeTest`, `V6RenameMigrationFirstBootTest` (Testcontainers MongoDB, read back through the production Jackson codec, including a save round trip), and new `PropertiesMigrationServiceTest` cases that use zero-entropy fake credentials. Each was mutation-checked: with the fix reverted, the tests fail, and with each half of the conversation fix removed on its own, the other half still keeps the load working.

### Next

PR B covers readiness with agents in ERROR plus ERROR retry, the nested Thymeleaf concat, and migrating triggers and user conversations. PR C covers the retention sweep on first boot, the startup probe, the documentation fixes, a 5.x → 6.x upgrade guide, and moving the conversation environment pass server-side.

---

## Decision Log

_For recording decisions that come up during implementation that aren't in the plan._

| Date       | Decision                                                              | Context                               | Alternative Considered                                      |
| ---------- | --------------------------------------------------------------------- | ------------------------------------- | ----------------------------------------------------------- |
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
