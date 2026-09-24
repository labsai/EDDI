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
| [September 2026](changelog/2026-09.md) | 53 | 214 KB |
| [August 2026](changelog/2026-08.md) | 212 | 837 KB |
| [July 2026](changelog/2026-07.md) | 147 | 648 KB |
| [June 2026](changelog/2026-06.md) | 26 | 67 KB |
| [May 2026](changelog/2026-05.md) | 34 | 76 KB |
| [April 2026](changelog/2026-04.md) | 104 | 220 KB |
| [March 2026](changelog/2026-03.md) | 59 | 183 KB |

The two running registers — **Decision Log** and **Regression Notes** — live at the
bottom of this file and are never archived.

---

## 🧩 chore(changelog): per-branch fragments instead of one shared file (2026-09-21)

**Repo:** EDDI (`chore/changelog-fragments`)

### The problem

[`AGENTS.md`](../AGENTS.md) §2 rule 8 requires every branch to add a changelog entry, and it
said to add it **directly below the `---` that closes the header** of [`changelog.md`](changelog.md).
Every open PR therefore inserted text at the same point in the same file. Git has no way to merge two
insertions at one point, so with a dozen PRs in flight every one of them conflicted with every other —
over a document that had nothing to do with the code under review. Resolving it rebased the branch,
which re-ran the identical collision at the next merge. The two running registers at the bottom of the
file (`## Decision Log`, `## Regression Notes`) behaved the same way: a fixed append point in a shared
file.

The rule was right and the storage was wrong. Nothing about "every session records what it did"
requires every session to write into the same twenty lines.

### The shape of the fix

An entry is now a **new file** in [`docs/changelog.d/`](changelog.d/README.md), named `YYYY-MM-DD-<slug>.md` with
the slug unique to the branch. Two branches adding an entry add two files, and git takes both without
asking. The entries are merged **once, afterwards, on `main`**, by
[`changelog-collate.yml`](../.github/workflows/changelog-collate.yml) — where there is no competing
branch to conflict with. The changelog gains a third depth:

```
docs/changelog.d/<date>-<slug>.md   pending, written by a PR
        |  collate-changelog.py  (nightly)
        v
docs/changelog.md                   the live file, newest first
        |  rotate-changelog.py   (nightly, when over the 200 KB target)
        v
docs/changelog/<YYYY-MM>.md         monthly archive
```

**The fragment format is what used to be pasted into the live file**, so collation is a move rather
than a translation — the entry that lands in `changelog.md` is byte-for-byte the one that was reviewed
on the PR that wrote it, apart from link depth. That mattered more than a tidier format would have: a
collator that reformats is a collator whose output has to be re-reviewed.

### Decisions

**Links change depth, and the transform refuses rather than guesses.** A fragment sits one directory
below `changelog.md`, exactly like an archive sits one below it in the other direction, so collation
removes one `../` and rotation adds one back. Both live in the new
[`changelog_common.py`](../scripts/changelog_common.py) rather than being written twice, masking
code spans and fenced blocks out of the substitution — some of the text that moves is documentation
*of* link syntax, and re-depthing an example corrupts it. `undepth()` **exits** on a link with no
`../` to remove instead of passing it through: that case is a fragment linking to a sibling, which
resolves where it is written (so `DocumentationLinksTest` passes it) and breaks the moment the entry
moves up — in the bot's commit, days later, blamed on the bot.

**Register rows ride along in fenced blocks.** ` ```decision-log ` and ` ```regression-note ` blocks
anywhere in a fragment are lifted out and inserted at the top of the matching table. A heading would
have been prettier to write, but `split_sections()` keys on the heading text: a bare `## Decision Log`
is classified as the register itself, so the section is filed below the bottom rule instead of being
collated, and a dated `## Decision Log (2026-09-22)` collates normally and is then *reclassified on
the next run* — moving an entry that has already been reviewed and merged, with no commit to explain
it. `REGISTER` is anchored to the exact heading and `ChangelogFragmentTest` rejects both spellings in
a fragment, so neither half depends on the other being right.

**Every scan is fence-aware.** A changelog entry routinely quotes the markdown it describes, and the
first draft read a `## ` inside a fenced block as a heading and a ` ```decision-log ` inside a
` ````markdown ` example as a real register block — splitting an entry at a code line, and filing an
example row into the live Decision Log while gutting the fence around it. `fence_mask()` closes a
fence only on a run of the **same character**, at least as long as the one that opened it, and the
heading scan, the register scan and both link transforms all go through it.

CommonMark allows `~~~` as well as ``` ``` ```, and the first version of that mask knew only about
backticks — so a `~~~markdown` example was not a fence at all, and everything inside it was read as
structure. Both are recognised now, in the scripts and in the test. The test had the mirror-image
bug: `registerRowsCarryADate` opened only on a register fence, so it graded the placeholder row in
the README's own nested example as real and was *stricter* than the collator, against a class
Javadoc promising it enforces exactly what the collator enforces.

**The nightly job opens a PR and skips while one is open.** `main` requires a PR, and a bot pushing
straight to it would be a hole in that requirement even where the token allows it. The skip matters
more than it looks: a still-open collation PR has already claimed those fragments — deleted on its
branch, still present on `main` — so collating again would produce a second PR proposing the same
entries, and whichever merged second would conflict. That would reintroduce, inside the fix, the exact
failure the fix exists to remove. For the same reason the open-PR lookup **fails the job** rather than
reporting "none" when the API call errors: the next step deletes the branch, so a lookup that returns
an empty string on failure would close the PR it exists to protect.

**The PR needs a non-default token to be mergeable, and says so when it does not have one.** GitHub
does not fire `pull_request` workflows for events created with the default `GITHUB_TOKEN`, so a PR it
opens sits at *Expected — waiting for status* against the required `Build & Test` and
`CodeQL Analysis` checks forever. `base-image-check.yml` has the same shape and has never actually
been exercised — the repository has no PR authored by `github-actions`. The job therefore prefers a
`CHANGELOG_BOT_TOKEN` secret and, without one, still opens the PR but states in the run summary and
the PR body that the checks need a close/reopen. A silently unmergeable nightly PR would stall the
whole mechanism on day one.

**Adding an entry in place now fails CI.** The `Changelog Discipline` job rejects a PR that *adds*
an entry heading or a dated register row to `docs/changelog.md`. Prose in AGENTS.md is what every
session follows, but it is not a guard — and twenty-nine PRs were open against the old rule when
this was written.

Two things the first draft of that job got wrong, both found by review. It required a closing `)`
after the date while `DATE` deliberately does not, so the fourteen entries headed
`(2026-07-02, session 2)` or `(2026-04-08 cont.)` — the house style — walked straight past a guard
that the collator would still have treated as entries. And it counted additions only, so correcting
a typo in a past entry's heading failed, with a summary telling the author to move their correction
into a new fragment. It now compares added against **removed**: an edit is one `+` and one `-`, nets
to zero, and is allowed; rotation removes entries and nets negative; only a genuine insertion raises
the count. All ten cases are exercised. It is deliberately not a required check — it reports rather
than blocks, which matters while those twenty-nine PRs are still open.

**Rotation now maintains `SUMMARY.md` itself.** A new archive that is not listed there fails
`DocumentationLinksTest` — the page exists and nothing navigates to it. That used to be a printed
reminder at the end of the script, which is a step a tired human skips and an automated rotation
cannot perform at all. It is regenerated from the files on disk, like the Archive table beside it.

### Keeping the guards pointed at the right files

`docs/changelog.md`, `docs/changelog.d/**` and `docs/changelog/**` were added to `ci.yml`'s
`operator_docs` path filter. The `code` and `backend` filters both exclude `docs/**`, and the nightly
collation PR touches nothing else — so the one PR that rewrites the entire changelog was the one PR
that would have skipped `build-and-test` entirely, and a skipped required check still satisfies branch
protection.

Two existing guards needed the new directory carved out, each with a compensating assertion:

- `DocumentationLinksTest` requires every page under `docs/` to be reachable from `SUMMARY.md`.
  Fragments are pending entries, not pages, and live about a day. The exemption is kept narrow by
  `ChangelogFragmentTest`, which allows nothing but a `README.md` and dated fragments in that
  directory — so it cannot become somewhere to park a real page.
- `DocumentationAccuracyTest` already skips `changelog.md` and its archives, because they record what
  was true at the time. A fragment is the same historical record one step earlier; without the
  exclusion its assertions would fire on an entry and stop firing on the identical text the next
  morning. `DocumentedRestPathsTest` and `ConfigurationReferenceCoverageTest` carry the same pair of
  exemptions and needed the same third one, for the same reason. `changelog.d/README.md` is **not**
  exempted from the accuracy sweep: it is a current page that documents the format, not a record.

`ChangelogFragmentTest` also asserts that `AGENTS.md` still sends sessions to `changelog.d/`. Every
session follows the instruction it is given, and a convention nobody is told about fixes nothing.

### Entries merge by date, and the separator stops multiplying

Two bugs a sandbox caught that reading would not have. The first draft **stacked** the collated block
above everything, so a PR that sat open for three weeks pushed a three-week-old entry above last
night's — in a file whose whole contract is newest-first. Entries are now woven into the existing
list by date, ties placing the new one first.

The second: `split_sections()` hands back the last entry's body *including* the `---` that closes it,
and the writer appended another one. Every collation added a rule, permanently — 48, 49, 50, 51 over
three nightly runs in the sandbox — and rotation only reset the count on the runs that happened to
move the last entry. `register_separator()` now emits one only when the body does not already carry it.

### Also fixed in passing

- The **Regression Notes** table had a header row and no `|---|` separator, so it had never rendered
  as a table. The collator writes into it, so it needed one.
- Dates are checked against the calendar, not just bounded by a regex. `MONTH` and `DAY` stop
  `2026-99-99` (which used to sort lexically into the live file and then crash rotation inside
  `pretty_month()`, weeks later, in the nightly job), but they still admit `2026-02-30` — so both
  the scripts and the test now parse the date as well.
- `--check` validates the live file it would write into, not only the fragments. Returning early
  meant a `changelog.md` missing a register section, or a table missing its `|---|` row, passed
  "validate, change nothing" and then failed the real run — on main, with nobody's change to blame.
- A UTF-8 BOM no longer reports a fragment as having no `## ` heading when the heading is plainly
  there; `read()` uses `utf-8-sig` and the test strips it. The BOM constant in the test is written
  numerically on purpose — the project formatter rewrites a `\uXXXX` escape into the raw character,
  which would have put an invisible BOM into the source file.
- Register rows are sorted newest-first before insertion. They arrive in fragment-filename order —
  oldest first — and were inserted as one block at the top, so a night that collated several days'
  fragments would have put 09-20 above 09-21 inside a table whose whole ordering is newest-first.
- A row bound for a register must lead with a real date, because that date is what places it. The
  three legacy `2026-03-05` rows at the top of the **Decision Log** were left where they are: moving
  rows reads as adding them, and the `Changelog Discipline` job below would have rejected this PR
  over a purely cosmetic reorder.
- `update_summary()` regenerates the month list in `SUMMARY.md` between the anchor and the first line
  that is not an archive row. Putting the *Pending entries* link directly under the anchor — the
  natural place for it — would have had the regenerated months inserted above it and the old rows
  kept below, silently doubling the list; every duplicated link still resolves, so nothing would have
  caught it. It now consumes every child of the anchor and re-emits the non-month ones after.

### Migrating the PRs already open

Twenty-nine open PRs carry an entry in `docs/changelog.md` under the old rule, and seven of them also
carry a branch-local rotation of `docs/changelog/2026-09.md`. Each needs its entry cut into a fragment
— the steps are in [`docs/changelog.d/README.md`](changelog.d/README.md) — which also removes the
conflict that branch currently has with every other open PR. [`.github/PULL_REQUEST_TEMPLATE.md`](../.github/PULL_REQUEST_TEMPLATE.md)
and [`ui/chat/AGENTS.md`](../ui/chat/AGENTS.md) were updated to point at the new location; the
`planning/*.md` documents that mention editing the changelog are historical plans and were left as
written.

**Files:** [`scripts/changelog_common.py`](../scripts/changelog_common.py),
[`scripts/collate-changelog.py`](../scripts/collate-changelog.py),
[`scripts/rotate-changelog.py`](../scripts/rotate-changelog.py),
[`.github/workflows/changelog-collate.yml`](../.github/workflows/changelog-collate.yml),
[`.github/workflows/ci.yml`](../.github/workflows/ci.yml),
[`ChangelogFragmentTest.java`](../src/test/java/ai/labs/eddi/docs/ChangelogFragmentTest.java),
[`DocumentationLinksTest.java`](../src/test/java/ai/labs/eddi/docs/DocumentationLinksTest.java),
[`DocumentationAccuracyTest.java`](../src/test/java/ai/labs/eddi/docs/DocumentationAccuracyTest.java),
[`DocumentedRestPathsTest.java`](../src/test/java/ai/labs/eddi/docs/DocumentedRestPathsTest.java),
[`ConfigurationReferenceCoverageTest.java`](../src/test/java/ai/labs/eddi/docs/ConfigurationReferenceCoverageTest.java),
[`AGENTS.md`](../AGENTS.md), [`docs/changelog.md`](changelog.md),
[`docs/changelog.d/README.md`](changelog.d/README.md), [`docs/SUMMARY.md`](SUMMARY.md),
[`.github/PULL_REQUEST_TEMPLATE.md`](../.github/PULL_REQUEST_TEMPLATE.md),
[`ui/chat/AGENTS.md`](../ui/chat/AGENTS.md)

## 🚧 fix(ingestion): a reaped run can no longer write over the run that replaced it (2026-09-21)

**Repo:** EDDI (`feat/ingestion-state-store`)

### Why

Review of PR #785 found a write-after-reap hole in the ingestion state store, and left it
open because no fix fitted in that PR's shape. Worker A holds the `RUNNING` run row for a
source, stalls past the stale threshold, and `reapStaleRuns` fails its run. Worker B claims
a replacement run. A then wakes up and calls `recordIngested` / `recordSeen` /
`recordUnreachable` with a run id that is dead, mutating document rows that now belong to B.

The severity was originally judged bounded — a late `recordIngested` sets `missedRuns = 0`
and `tombstoned = false`, so it preserves a document rather than losing one. Writing the
contract cases turned up two worse paths that the "it only keeps data" reading misses:

- **Silent, permanent loss of a page.** Tombstoning deletes a document's vectors. A stale
  `recordIngested` lifts the tombstone *and* restores the hash those deleted vectors
  matched, so `hasChanged` reports "unchanged" on every later run and the page is never
  embedded again. It is gone from retrieval for good, with nothing in any log.
- **Vector deletion of a live page.** `recordUnreachable` writes only the run marker, which
  looks harmless until you follow it: stamping that marker is exactly how a document escapes
  the owning run's miss count. A zombie overwriting it hands a page the live run has just
  seen back to `tombstoneMissing`.

### What changed

A fencing token both backends apply **in the same statement as the document write**, because
the alternatives re-introduce backend divergence: MongoDB cannot join collections in an
update, and multi-document transactions need a replica set while EDDI supports standalone
MongoDB (`docker-compose.yml` ships `mongo:7` standalone). A PostgreSQL-only
`AND EXISTS (SELECT 1 FROM rag_ingestion_runs …)` fence would leave the two stores behaving
differently — the exact drift `IngestionStateStoreContract` exists to prevent.

Ownership is denormalized onto the document row instead:

- `fencing_run_id` / `fencingRunId` — the run that owns the row. `startRun` takes ownership
  of the source's rows when it claims a run; `reapStaleRuns` releases it (to `NULL`) when it
  actually reaps something, so a reaped worker is fenced from that moment rather than only
  once a replacement claims.
- `fencing_generation` / `fencingGeneration`, paired with a new `generation` on the run row —
  the claim's sequence number, which orders the ownership stamps themselves. Without it, a
  stamp delayed past its own run's reaping could land after the replacement's and take the
  source back.
- Every `record*` and `tombstoneMissing` call puts its `runId` into the update's own filter.
  **No signature changed and the caller makes no extra round trip** — the `runId` the pipeline
  already passes *is* the fencing token. PostgreSQL uses `ON CONFLICT … DO UPDATE … WHERE
  fencing_run_id = EXCLUDED.fencing_run_id`; MongoDB puts the field in the upsert filter and
  reads the resulting `(sourceId, documentId)` duplicate-key error as the same answer.

A fenced write is a no-op logged at DEBUG, not an exception: the reaper already decided the
run is dead and `finishRun` logs once that its result was discarded, so failing every document
of a doomed crawl would only add noise to a result that is thrown away.

**What the fence does not cover,** stated in the interface Javadoc rather than glossed: a
document the superseded run is the first ever to see has no row to own, so its insert still
lands. That is the benign direction, and the owning run's `tombstoneMissing` reconciles it
away over the following runs.

### Design decisions

- **Ownership stamped at claim, not propagated lazily.** Comparing a per-document generation
  on the write alone only fences rows the *replacement* run has already touched — a zombie
  would still be free to write every row the live run had not reached yet, which is most of
  them early in a crawl. Taking ownership of the source's rows at claim time costs one bulk
  update per run and is the only shape that fences the whole source.
- **`fencing_run_id` is separate from `last_run_id`.** They mean different things:
  `last_run_id` is the run that last *wrote* the row and drives the miss count; ownership is
  about who is *allowed* to write. Overloading one field would have made `recordUnreachable`
  grant itself the write permission it is being checked for.
- **`ALTER TABLE … ADD COLUMN IF NOT EXISTS` alongside the `CREATE TABLE`.** `CREATE TABLE IF
  NOT EXISTS` is a no-op against a database an earlier build of this branch already created,
  which would have left the fence silently un-enforceable there.

### Verified

`MongoIngestionStateStoreTest` and `PostgresIngestionStateStoreTest` — 38 cases each, both
green. Non-vacuity checked by reverting `src/main` and re-running: **6 of the 7 new cases fail
identically on both backends** (the seventh, `fencingIsScopedPerSource`, is the guard against
over-fencing and must pass either way).

### Follow-up for the downstream stack

`InMemoryIngestionStateStore` (test double, added in #787) implements the same contract, so it
will need the same fence when this branch merges forward into #787 / #789 / #790. Nothing in
`IngestionPipeline` needs to change — preview mode never calls `record*`, and a reserved run id
comes from `startRun` like any other.

### Files

- `src/main/java/ai/labs/eddi/modules/ingestion/IIngestionStateStore.java`
- `src/main/java/ai/labs/eddi/modules/ingestion/mongo/MongoIngestionStateStore.java`
- `src/main/java/ai/labs/eddi/datastore/postgres/PostgresIngestionStateStore.java`
- `src/test/java/ai/labs/eddi/modules/ingestion/IngestionStateStoreContract.java`

---

### Review follow-ups (2026-09-21)

- **The reaper released ownership for the whole source, not for the runs it reaped.**
  `reapStaleRuns` fails the stale runs and clears `fencing_run_id` in two writes, not one. Once the
  first commits, the partial unique index on the source is free: a replacement run can claim the
  source and stamp every document with its own id before the second one runs, and a source-wide
  release then wipes the live run's fence. Every `recordSeen`, `recordIngested`,
  `recordUnreachable` and `tombstoneMissing` of that run silently matches nothing while it carries
  on crawling and embedding, so it finishes looking healthy having recorded no document at all, and
  the whole source is re-fetched and re-embedded on the next run. PostgreSQL now reaps with
  `UPDATE ... RETURNING run_id` and releases `WHERE fencing_run_id = ANY (?)`; MongoDB claims each
  stale run with `findOneAndUpdate` — the ids have to come back *with* the write, not from a read
  after it — and releases with `in(fencingRunId, reapedIds)`.
- **`startRun`'s insert was the one operation still outside `translating(...)`.** It handled
  `MongoWriteException` and let everything else out, so a connection failure, a timeout or a
  step-down during the insert escaped as a raw `MongoException` while PostgreSQL answered the same
  outage with `IngestionStateStoreException` — the backend-dependent exception contract the helper
  was added to remove. The duplicate-key case is now handled inside the supplier, so it still
  returns `Optional.empty()`, and everything else falls through to the helper.

The contract gained `reapingDoesNotReleaseAnotherRunsOwnership` and one hook,
`forceDocumentOwner`, implemented per backend. The hook is needed because the interleave cannot be
produced through the public interface: the partial unique index means a stale `RUNNING` run and its
replacement can never both exist, so there is no sequence of `startRun` calls that leaves a document
owned by a run other than the one about to be reaped.

### Still open

Review also found that `recordIngested` inserts a newly discovered document with a
`fencing_run_id` but no `fencing_generation`, and `takeOwnership` matches
`fencing_generation IS NULL` (the arm that exists for rows predating the fencing columns). A
`takeOwnership` delayed past its own run's reaping can therefore land on a row the replacement run
inserted and take that one document back. The scoped release above does not close it. Every fix
needs the claiming run's generation at insert time, which `recordIngested` is not given: a scalar
subquery works on PostgreSQL and has no MongoDB equivalent, a per-call lookup is symmetric but adds
a read per ingested document, and carrying the generation through `startRun`'s return type is the
cleanest but changes `IIngestionStateStore`, which two further open PRs are built on. Left for that
decision rather than picked unilaterally here.

### Merging `main`: two designs for the same concern

`main` reworked tombstoning while this branch was open, and the two changes met
head-on. This branch made `tombstoneMissing` atomic -- `UPDATE ... RETURNING` on
PostgreSQL, `findOneAndUpdate` in a loop on MongoDB -- so two runs finishing together
could not both report the same document as newly gone. `main` split it instead, into
`bumpAndFindMissing` + `markTombstoned`, so a caller can delete the vectors *before*
marking: marking first is durable in the wrong order, because a crash between the two
leaves a document flagged gone while its chunks stay retrievable, and a tombstoned
document is never reported again.

**`main`'s split is kept, and this branch's fencing is grafted onto it.** The two
rationales are not symmetric: the durability ordering is a property nothing else
provides, whereas the atomicity the `RETURNING` clause bought is already delivered by
the fence. Only the owning run satisfies `fencing_run_id = ?`, so two runs cannot both
reach the same document to report it in the first place. Keeping both would have meant
choosing the weaker guarantee and losing the stronger one.

Three consequences, each of which is now pinned by a test:

- **The search is fenced, not just the bump.** Fencing the miss counter alone leaves a
  hole: the counter is shared, so a document another run has already bumped to the
  threshold still satisfies an unfenced search. A superseded run would hand its caller
  a list of documents to delete, and the caller deletes the vectors before anyone
  checks who owned them. Both statements now carry the predicate, in both backends.
  This was not covered -- removing the predicate from the PostgreSQL search left all 39
  contract cases green -- so `supersededRunReportsNothingMissing` was added to the
  shared contract, where all three implementations run it.
- **`tombstoneMissing` reports the transition it just performed.** `bumpAndFindMissing`
  reports its candidates *before* marking them and `DocumentState` is immutable, so the
  convenience default was handing back a list that still said `tombstoned=false`
  although marking had succeeded. A caller that believed it would re-report the same
  documents on the next run. `DocumentState.asTombstoned()` restamps them.
- **The in-memory double was fenced too.** `main` added
  `InMemoryIngestionStateStore` with no fencing, which would have left the double
  behaving differently from both backends on exactly the property this branch exists to
  add -- the failure the shared contract was written to prevent. It now takes
  ownership on `startRun`, fences all four document writes, and releases only the
  ownership a reap actually took. All three implementations run the same 39 cases.

The double deliberately reproduces one thing it could have quietly fixed: a document
row inserted for the first time lands with an owner and **no** generation, because that
is what both backends do. Stamping the claiming generation there is the subject of an
open review thread and is a sequencing decision, not a merge one -- it changes
`IIngestionStateStore`, which two further PRs are built on.

---

## 📝 docs(mcp): how to reach an authenticated `/mcp`, and the plan to stop needing this (2026-09-20)

**Repo:** EDDI (`docs/mcp-oauth-plan`)

A local MCP client — Claude Desktop, Claude Code, Cursor, LM Studio — cannot practically
manage an EDDI instance that has OIDC enabled. `/mcp` carries an `authenticated` policy
(its own `quarkus.http.auth.permission.mcp` rule), EDDI is bearer-only (`application-type=service`), and it
advertises no OAuth metadata, so a client that would log in by itself gets a bare 401 with
nothing to discover. The only way in is a hand-pasted token that the shipped realm lets
expire after Keycloak's default five minutes, and there is no long-lived key for `/mcp`
(the only api-key surface is the `/v1` adapter).

The Quick Start in `docs/mcp-server.md` only ever showed the unauthenticated
`localhost:7070` case, so nothing said any of that.

### What changed

- **`docs/mcp-server.md`** — new *Connecting to an authenticated instance* section under
  Authentication & Authorization: get a token from the public `eddi-frontend` client, pass
  it either as a header on a Streamable-HTTP client or through `mcp-remote` (whose argument
  splitting means the value belongs in an env var), and four caveats in the order they
  bite — expiry, no api key, roles decide which tools work, and `/mcp` cannot be opened
  selectively. The Quick Start now points at it.
- **`docs/mcp-server.md`** — the Configuration block documented `quarkus.mcp-server.http.root-path`.
  That hyphenated form is not a key the extension knows; `application.properties`
  already says so. Corrected to `quarkus.mcp.server.http.root-path` with the warning kept.
- **`planning/mcp-oauth-protected-resource-plan.md`** (new) — the fix: advertise `/mcp` as an
  RFC 9728 protected resource so the client runs the OAuth flow and refreshes its own token,
  removing the shared long-lived credential rather than automating its rotation.

### Decisions

- **Rotation is the wrong problem to solve.** The instinct is to reuse **Connections**, which
  already does lazy OAuth refresh with a single-flight claim. It cannot apply: a connection
  resolves to a header on a request *EDDI originates*, and here EDDI is the callee. Connections
  exists because EDDI holds a credential it must refresh; inbound, the client holds it.
- **Serving the metadata is configuration, not code.** Quarkus OIDC 3.39.3 already ships
  `ResourceMetadataHandler` and appends `resource_metadata="…"` to the 401 challenge. The
  plan's Increment 1 is four properties, a permit rule and a Keycloak client.
- **A permit rule is mandatory, not a precaution.** That handler registers as
  `FilterBuildItem(handler, 50)`, and `SecurityHandlerPriorities.AUTHORIZATION` is 100 — it
  runs *after* authorization, so the catch-all at `/*` would 401 the discovery document and
  the flow could never start.
- **Pre-registered client over dynamic registration.** The realm defines no `roles` client
  scope; `eddi-frontend` gets `realm_access.roles` only from its own protocol mapper. A
  dynamically registered client cannot carry mappers, so its tokens authenticate and then
  fail every tool with "requires role" — the worst failure shape available.
- **Review follow-up (2026-09-21).** The §3.2 configuration block quoted a hardcoded `/mcp`
  in both `resource-metadata.resource` and the permit rule's second path. What ships derives
  both from `${quarkus.mcp.server.http.root-path}`, so an operator who moves the MCP root
  moves the metadata document and its permit rule with it; a hardcoded permit path would
  leave the relocated document behind the `authenticated` policy and 401 the discovery
  request before it starts. The snippet now matches `application.properties`.

- **The EDDI → client direction is deliberately out of scope** and recorded as such in the
  plan, so it is not re-derived: it needs Claude Code channels rather than MCP, and two
  design answers first — attribution (nothing reads the token's `azp`, so a model answering
  a HUMAN member's turn is recorded as the person) and keeping HITL decisions out of an
  AI client's reach.

### Files

- `docs/mcp-server.md`
- `planning/mcp-oauth-protected-resource-plan.md` (new)

## 🔑 feat(keycloak): ship an `eddi-mcp` client for MCP clients to log in through (2026-09-20)

**Repo:** EDDI (`feat/keycloak-mcp-client`, stacked on `feat/mcp-oauth-discovery`)

Increment 1 of [`planning/mcp-oauth-protected-resource-plan.md`](../planning/mcp-oauth-protected-resource-plan.md),
second half. The previous entry made EDDI tell a client *where* to authenticate; this gives
it something to authenticate as.

### What changed

- **All three realm copies** (`keycloak/`, `helm/eddi/files/`, `k8s/overlays/auth/`) gain
  `eddi-mcp`: public, authorization code + PKCE `S256` required, direct access grant / implicit
  / service accounts all off, redirect URIs `http://localhost:*` and `http://127.0.0.1:*`, no
  web origins, and the `realm-roles`, `eddi-backend-audience` and `groups` protocol mappers
  copied from `eddi-frontend`.
- **`DeploymentManifestsTest`** — a case per realm copy asserting the flow settings, the PKCE
  requirement, the mappers (by claim name and by *access* token, not just id token), that no
  redirect is `*` or a remote http URL, that `webOrigins` is empty, and that neither `name` nor
  `description` exceeds 255 characters: Keycloak stores them in `VARCHAR(255)` and an over-long
  value does not truncate — **the realm import fails and Keycloak exits 1**, which is how the
  first draft of this client took down every stack that imports the realm. Found by running the
  import, not by reading the file.
- **`helm/eddi/templates/NOTES.txt`**, **`k8s/overlays/auth/kustomization.yaml`**,
  **`docs/security.md`** — every place that told an operator to grant an account "those two
  roles" now names all three. Following the old instruction built an administrator that logs in
  and is refused every MCP read tool, which is the trap the realm change exists to close.
- **`.github/workflows/ci.yml`** — `keycloak/**` added to the `code` and `backend` path
  filters. `k8s/` and `helm/` were already there, so the compose realm was the one copy whose
  change ran no CI — including the audience mapper every accepted token depends on.
- **`docs/mcp-server.md`**, **`docs/security.md`** — the client, how to point a client at it,
  why dynamic registration is not an option here, and what to do on an **existing** realm:
  `--import-realm` never re-imports into a realm that already exists and both auth stacks keep
  Keycloak's database in a named volume, so an upgrade leaves the client absent and the flow
  ends in `invalid_client`. The manual steps are listed, `realm-roles` first.

- **All three realm copies** — the seeded `eddi` administrator gains `eddi-viewer` alongside
  `eddi-admin`/`eddi-editor`. There is no role hierarchy, so without it the account an operator
  points their first MCP client at completes the login and is then refused all 27 viewer-gated tools.
  A test pins it. `scripts/make-test-realm.mjs` guards that fixture set against the realm and
  fails the auth E2E run when the two drift, so `ROLE_FIXTURES` and `e2e/auth/auth-helpers.ts`
  move with it — which is how CI caught this change the first time it ran.

### Decisions

- **Pre-registered client, not dynamic registration.** Not a preference: this realm supplies its
  own `clientScopes` and defines no `roles` scope, so `realm_access.roles` comes only from a
  client's own protocol mapper. RFC 7591 registration carries no mappers, so a self-registered
  client would mint tokens that authenticate and then fail every tool with "requires role" —
  login succeeded, everything forbidden. Keycloak's default registration policies would also
  have to be loosened in at least three places to get there.
- **Loopback redirects only; `https://claude.ai/api/mcp/auth_callback` is not shipped.** Claude
  Desktop connectors redirect to that remote callback, so the authorization response for a
  self-hosted EDDI would pass through a third party. That is an operator's decision, documented
  in `docs/mcp-server.md`, rather than a default inherited from us.
- **No `webOrigins`, not even `+`.** These clients are native processes; `eddi-frontend` needs
  browser origins and this one never makes a browser request.
- **The redirect list is the one `[ext]` assumption in the plan.** Which loopback path each
  client uses is documented client behaviour rather than something verified here, so the entries
  are the broad `localhost` / `127.0.0.1` wildcards the realm already uses for the SPA, and the
  docs say to add anything else in the admin console.

### Files

- `keycloak/eddi-realm.json`, `helm/eddi/files/eddi-realm.json`, `k8s/overlays/auth/eddi-realm.json`
- `src/test/java/ai/labs/eddi/deploy/DeploymentManifestsTest.java`
- `docs/mcp-server.md`, `docs/security.md`

## 🔐 feat(mcp): advertise `/mcp` as an OAuth protected resource, so clients sign themselves in (2026-09-20)

**Repo:** EDDI (`feat/mcp-oauth-discovery`, stacked on `docs/mcp-oauth-plan`)

Increment 1 of [`planning/mcp-oauth-protected-resource-plan.md`](../planning/mcp-oauth-protected-resource-plan.md).
An MCP client now discovers where to authenticate and holds its own token, instead of an
operator pasting a bearer that expires in five minutes.

Quarkus OIDC 3.39.3 already serves the RFC 9728 document and appends `resource_metadata="…"`
to the 401 challenge, so there is no new EDDI code — five properties and one permit rule.

### What changed

- **`application.properties`** — `quarkus.oidc.resource-metadata.*`: `enabled` tracks
  `tenant-enabled` (an instance with auth off has no authorization server to name, and the
  handler is not installed for a disabled tenant), `resource=/mcp`, `force-https-scheme=true`,
  `scopes=openid`, and `authorization-server` preferring `token.issuer` over `auth-server-url`.
- **`application.properties`** — a `permit` rule for the two exact metadata paths (the bare
  form and the path-inserted document), `GET,HEAD` only. Exact rather than a `/*` under the
  prefix, which would anonymously expose any future handler beneath it.
- **`helm/eddi`** — `eddi.oidc.resourceMetadata.{forceHttpsScheme,authorizationServer}`, because
  neither is safely inferable: `publicUrl` describes Keycloak, not EDDI, so an https IdP in
  front of a plain-http port-forward would advertise a resource nothing serves. The scheme now
  follows EDDI's own `ingress.tls` unless set. Chart version bumped per Chart.yaml's rule.
- **`application.properties`** — the MCP security banner said 33 tools (there are 84) and
  described a two-role model (there are four, with no hierarchy).
- **`McpOAuthDiscoveryConfigTest`** (new, 8 cases) — the config *is* the feature, so it is what
  gets asserted: the enabled expression, the resource matching the MCP root path, the issuer
  preference, `openid` while `user-info-required` is on, the permit rule's policy/methods/paths,
  what those paths match and do not match, and `/mcp` still being `authenticated`.
- **`ui/manager/e2e/auth/auth.spec.ts`** — two cases in the Keycloak tier: the document is
  readable with no token and names the issuer a real token carries; an unauthenticated `/mcp`
  POST answers 401 with a challenge pointing at it.
- **Every shipped stack that serves plain http with authentication on** overrides
  `force-https-scheme`, because that is the one shape a forced https identifier is wrong for:
  `docker-compose.auth.yml`, the auth E2E tier, `k8s/overlays/auth` (its documented flow is
  `kubectl port-forward`), and the helm chart whenever the Keycloak URL it is given is itself
  plain http. Without it those deployments advertise `https://…/mcp` with nothing serving TLS,
  and discovery dies before it starts.
- **`ui/manager/docker-compose.integration-keycloak.yml`** — the tier now pins the
  browser-reachable issuer the way `docker-compose.auth.yml` does, so the discovery document it
  publishes is the one a real deployment publishes. The E2E case can therefore assert the
  advertised authorization server is reachable **from outside the compose network** — with the
  old in-cluster hostname that assertion could not have failed.
- **`docs/mcp-server.md`**, **`docs/security.md`** — the discovery path as the preferred way in,
  with the hand-pasted token demoted to a fallback; the new permit row, and why `@PermitAll`
  alone does not make a path public.

### Decisions

- **The permit rule is mandatory, not defence in depth.** quarkus-oidc registers its handler as
  `FilterBuildItem(handler, 50)` and `SecurityHandlerPriorities.AUTHORIZATION` is 100 — higher
  runs first, so authorization would answer 401 before the document could be read, and
  discovery could never start. Verified by `javap` on `OidcBuildStep`, and asserted over HTTP
  in the auth E2E tier because no properties file can prove an ordering.
- **Exact paths, never `/.well-known/*`.** A wildcard there would pre-permit whatever lands
  under that prefix later. A test asserts what the patterns match *and* what they must not.
- **`authorization-server` defaults to `token.issuer`.** `auth-server-url` is the
  cluster-internal Keycloak address in both shipped deployments, so the default would have
  advertised a host no client outside the cluster can resolve. RFC 8414 wants the advertised
  server to equal the issuer regardless, and the E2E assertion compares it against the `iss`
  claim of an accepted token rather than a hardcoded URL.
- **`openid` is advertised deliberately.** `user-info-required=true` makes EDDI call userinfo
  on every request, and Keycloak refuses userinfo for a token minted without that scope;
  clients copy `scopes_supported` into the authorize request. A test pins the pair together so
  removing one surfaces the other.
- **https is forced by default.** `quarkus.http.proxy.*` is unset, so behind a TLS-terminating
  ingress the identifier would be advertised as `http://`.
- **The permit path interpolates the MCP root path (review round 4).** It read
  `/.well-known/oauth-protected-resource/mcp`, a literal, while the advertised resource was
  already derived from `${quarkus.mcp.server.http.root-path}`. An operator who moved the MCP
  root would have moved the document with it and left the permit rule behind: the metadata
  request then meets the catch-all `authenticated` policy and answers 401, and the 401
  challenge that is supposed to bootstrap discovery points at a path that also answers 401.
  Both halves now interpolate the same property. `McpOAuthDiscoveryConfigTest` asserts the
  raw expression — asserting the resolved form would pass either way — and resolves it
  against the root path for its match checks; reverting the property to the literal fails
  that test. `A2aEndpointPermissionsTest` builds its matcher from this file, so it grew a
  small expander for `${…}` inside a permission path and now probes the path-inserted
  document through it; unexpanded, that path entered the matcher as a literal and the model
  reported `authenticated` for a document Quarkus permits.

### Files

- `src/main/resources/application.properties`
- `src/test/java/ai/labs/eddi/configs/McpOAuthDiscoveryConfigTest.java` (new)
- `ui/manager/e2e/auth/auth.spec.ts`, `ui/manager/docker-compose.integration-keycloak.yml`
- `docs/mcp-server.md`, `docs/security.md`
- `src/test/java/ai/labs/eddi/engine/a2a/A2aEndpointPermissionsTest.java`

## 🛡️ fix(security): validate the token audience, and stop calling userinfo on every request (2026-09-20)

**Repo:** EDDI (`feat/oidc-audience-validation`, stacked on `feat/keycloak-mcp-client`)

Increment 2 of [`planning/mcp-oauth-protected-resource-plan.md`](../planning/mcp-oauth-protected-resource-plan.md),
kept separate because it changes which tokens are accepted — every token, not only MCP ones.

Quarkus verifies `aud` on an **access** token only when `quarkus.oidc.token.audience` is set
(`OidcIdentityProvider` passes `enforceAudienceVerification = idToken`, and with the property
unset `OidcProvider` calls `setSkipDefaultAudienceValidation()`). It was unset, so EDDI accepted
any token the realm issued for any client in it — and roles come from `realm_access/roles`,
which is client-independent, so such a token arrived carrying the user's full rights. The
`eddi-backend-audience` mapper that has been on `eddi-frontend` all along is evidence someone
intended this check and never switched it on. It matters more now that a second client exists.

### What changed

- **`application.properties`** — `quarkus.oidc.token.audience=eddi-backend`.
- **`application.properties`** — `quarkus.oidc.token-cache.{max-size=1000,time-to-live=3M,clean-up-timer-interval=5M}`.
  `user-info-required=true` means one Keycloak round trip per request, which an MCP client makes
  many of. Signature, expiry and audience are still checked per request, before the cache is
  consulted at all. What it defers is the userinfo call, which doubles as a session-revocation
  check (Keycloak refuses userinfo for a logged-out session) — so a killed session keeps working
  for up to the TTL. That, not token expiry, is why the TTL is short.
- **`DeploymentManifestsTest`** — every client that can mint a token (standard, direct-grant,
  implicit or service-account flow) must mint the audience the property requires, in all three realm copies, compared
  against the property rather than a spelling repeated in the test.
- **`ui/manager/e2e/auth/mcp-oauth.spec.ts`** (new) — the middle of the feature, which the
  discovery and 401 cases do not reach: authorization code + PKCE against `eddi-mcp`, the
  token exchange, then `/mcp` `initialize` and a real `list_agents` call. It asserts the
  token carries `aud=eddi-backend` and realm roles, so the two silent failures — a token the
  backend refuses, and one that authenticates and is then refused by every tool — surface as
  themselves rather than as a generic 401.
- **`docs/security.md`**, **`docs/open-webui-integration.md`** — both properties in the table, what
  a hand-built realm has to do, and the `/v1` adapter's 401-under-OIDC entry, which now also means
  "and carrying `aud=eddi-backend`".

### Compatibility

**This rejects tokens that were accepted before.** A deployment whose users authenticate through
a client *without* an audience mapper starts answering 401. The shipped realm is unaffected
(both login clients carry the mapper). The fix for a custom realm is to add the mapper; the
escape hatch is `QUARKUS_OIDC_TOKEN_AUDIENCE=any` — quarkus-oidc's own sentinel for skipping
audience validation (`OidcProvider.ANY_AUDIENCE`), which restores the old behaviour.

### Files

- `src/main/resources/application.properties`
- `src/test/java/ai/labs/eddi/deploy/DeploymentManifestsTest.java`
- `docs/security.md`, `docs/open-webui-integration.md`

## 🔒 fix(security): sanitize the agent-deployment log lines CodeQL flagged for CWE-117 (2026-09-20)

**Repo:** EDDI (`fix/log-injection-agent-deployment-logs`)

GitHub code scanning had 14 open `java/log-injection` alerts against `refs/heads/main` in two files —
9 in `RestAgentAdministration` (#90–#92, #97–#102) and 5 in `AgentFactory` (#116–#120). They are not
new: they surfaced while triaging community PRs #558 and #561, which only rename logger fields and
neither introduce nor fix any of them. `agentId` is a path parameter on every endpoint involved, so a
CR/LF in one closes the real log record and lets the remainder read as a second line the server wrote
itself — a forged `[SCHEDULE] Auto-enabled` line for an agent nobody deployed, for instance.

### What changed

- **`AgentFactory`** — `waitForDeploymentCompletion` quoted the raw `AgentId` on five lines (two
  "did not complete successfully" ERRORs, the still-deploying DEBUG, the timeout WARN and the
  interrupted WARN). It now derives one `var safeAgentId = sanitize(agentIdObj.toString())` after the
  listener lookup and every line quotes that — one call site instead of five, and the rendering is
  byte-identical to the old `%s` on the object for a benign id. `logAgentDeployment`'s two INFO lines
  (alerts #119/#120, the only two still current at `798c6e84`) now `sanitize(agentId)`. The file
  already sanitized its three `debugf` calls; these were simply missed.
- **`RestAgentAdministration`** — all 9 flagged sinks: the deploy-wait timeout WARN, the deploy-failed
  WARN (both the id *and* the cause's message), the successful-undeploy INFO, `throwError` and
  `throwErrorForbidden`, and the four `[SCHEDULE]` auto-enable/auto-disable lines, which also quote
  `schedule.getName()` and `schedule.getId()` verbatim. Log levels and message wording are unchanged
  throughout.
- **`RestAgentAdministration` now static-imports `sanitize`** rather than calling
  `LogSanitizer.sanitize(...)` on two pre-existing sites, matching `RestAgentStore` and
  `RestWorkflowStore` and AGENTS.md's ban on inline qualification. The class import is gone, so
  Checkstyle's `UnusedImports` stays green.

### Tests

Two new classes in the established shape — `captureLogsOf(<Class>.class, …)` plus
`assertNoForgedRecordBoundary(...)` against `LogCaptureSupport.FORGED_RECORD`, attaching by logger
category rather than by field name (so PR #558's renames cannot break them):

- `AgentFactoryLogInjectionTest` — 6 tests. Reaching `waitForDeploymentCompletion` needs a published
  `IN_PROGRESS` placeholder, so each test parks a deployment inside its store lookup on a virtual
  thread (the idiom `AgentFactoryUndeployVersionTest` already uses) and calls `getAgent` from the test
  thread.
- `RestAgentAdministrationLogInjectionTest` — 9 tests, one per alert.

Every one of the 17 arguments newly wrapped in `sanitize(...)` was mutation-checked one at a time:
revert exactly that call, run the class, require the named test to fail. 17 mutations, 17 killed,
0 survived. Driver lived in the session scratchpad and is not committed.

### Decisions

- **Which alerts are real.** Three of the five `AgentFactory` alerts (#116–#118) were last seen at
  `d5294a60` and point at lines that carry `sanitize(agentId)` on today's `main` — stale instances
  that should close on the next scan of the branch. The genuinely unsanitized sinks in that file were
  the two `logAgentDeployment` INFOs plus the five `waitForDeploymentCompletion` calls, which CodeQL's
  stale line refs no longer name. Fixing by *call site* rather than by *reported line* is the only way
  to end up with the file actually clean.
- **`throwErrorForbidden`'s sanitize also reaches the client.** Its `message` is both logged and put
  into the `WebApplicationException` body, so sanitizing the id narrows a response-splitting surface
  as well. That is a strict improvement and the wording is untouched, so it stays on one call.
- **The auto-enable/auto-disable failure WARNs pin the id only.** Both pass the cause as the record's
  *throwable*, not as a format parameter, so `LogCaptureSupport` never sees its message — those tests
  deliberately use a benign exception message rather than implying coverage they do not have.
- **The `CompletableFuture` in the timeout/interrupt tests is hand-written, not mocked.** The default
  mock maker does not intercept `CompletableFuture.get(long, TimeUnit)`: a stubbed mock silently ran
  the real 60-second wait, and Mockito reported it as `UnfinishedStubbing` from inside the JDK. A
  four-line anonymous subclass whose `get` throws is deterministic and needs no mock maker at all.
- **Each capture window holds exactly one line under test.** The parked deployment's own
  `logAgentDeployment` INFO fires *before* the store lookup and the endpoint calls run outside the
  window, with only the captured Callable inside it. Without that ordering a test could be satisfied
  by whichever line leaked first, and the per-site mutation check would not localise.

### Still open

`java/log-injection` has ~40 further open alerts on `main` in `GroupHitlCoordinator`,
`GroupConversationService`, `MemberTurnExecutor`, `ConversationHitlService`, `PhaseExecutionEngine`,
`AuditLedgerService` and others. Out of scope here; same one-line fix and same test shape apply.

## 🔒 fix(apicalls): auto-vaulted properties carry a provenance marker; the guard requires it (2026-09-20)

**Repo:** EDDI (`fix/vault-references-in-templates`)

### Why

`ConfigReferenceGuard.autoVaultReferences` decided whether a conversation property named by an
HTTP-call template may resolve a vault secret by **looking at the value**: it accepted the property
when its value was character-for-character what `PropertySetterTask.autoVaultSecret` would have
written for that property, under this conversation's agent and tenant. Two PR reviewers (Copilot,
CodeRabbit) asked for a provenance check instead, and the previous entry recorded why it was not
done then: `Property` carried no marker. A `scope: "secret"` instruction stores its vault reference
with `scope: conversation`, indistinguishable on disk from a property a template wrote from user
input, a model reply or an API response — so `${vault:<agentId>.apiKey}` was a string an attacker
could simply produce, and the shape test accepted it.

What that bought an attacker was bounded (the key is derived from the agent and the property name
the template reads, and the request goes to the endpoint the configuration names), which is why it
shipped. It is still a value the configuration never wrote being resolved into an outgoing request.

### What changed

- **`Property.autoVaulted`** (new, `Boolean`) — the provenance marker, set by
  `PropertySetterTask.autoVaultSecret` and by **nothing else**. No property-instruction field maps to
  it (`convertPropertyInstructions` reads a fixed key set), and no REST endpoint takes a `Property` as
  a request body, so "marked" means "this process vaulted it" rather than "this value looks vaulted".
- **`ConfigReferenceGuard`** requires the marker before it will allow a reference read through
  `{properties.x}`. The agent/property/tenant comparison is kept behind it — redundant by
  construction, since `autoVaultSecret` derives all three itself, and kept as the bound that still
  holds if a marked `Property` ever reaches memory from somewhere other than that method.
- **`ApiCallExecutor`** passes the live `Map<String, Property>` from `IConversationMemory` into
  `buildRequest` → `resolveGuardedVariables` → the guard. `ConversationProperties.toMap()` — what
  templates and, until now, the guard see — flattens each `Property` to its raw value and loses the
  marker, so it needs a channel of its own. Read from memory at build time, not captured earlier: a
  pre-request property instruction writes through to the same map between `execute` being called and
  the request being built. Threaded through `execute`, `resolve` and `executeFireAndForgetCalls`.
- **`MemoryCheckpoint.copyProperties`** carries the marker across the deep copy. It clones through the
  all-args constructor, which does not take the new field — a rollback that dropped it would turn
  every later API call using that secret into a refusal.
- `docs/secrets-vault.md`: the auto-vaulted-property case now rests on provenance, and what an
  unmarked property means.

### Decision: unmarked is refused, not grandfathered

`null` covers two cases that cannot be told apart — a property written from conversation data, and
one written into a conversation document before the field existed. Accepting the pair for
compatibility would leave the hole open permanently, because the attacker's property is unmarked
too; the fix would be decorative. So unmarked fails closed.

The cost is a conversation that auto-vaulted a secret under an earlier release and makes the API
call after the upgrade: the call is refused with the error that names the field and the reference,
and re-running the `scope: "secret"` instruction (the user supplies the secret again, or a new
conversation starts) marks it. Bounded — it needs the vault enabled, which is not the shipped
default — and recoverable. A permanent fail-open is neither.

Deserialization stays backward compatible in the mechanical sense: the field is absent from every
document already in MongoDB and reads back as `null` rather than failing, and EDDI's global
`NON_NULL` inclusion means an unmarked property does not gain the field on write either.

### Verification

- `ConfigReferenceGuardTest` 10 → 13: the three existing auto-vault cases kept (`autoVaultProperty`,
  `autoVaultTenantIsPinned`, `autoVaultOwnTenant`, now stating the marker explicitly — tenant pinning
  still refuses a *marked* property under a foreign tenant, so provenance is necessary and not
  sufficient), plus an unmarked property holding the exact reference, an explicit `FALSE`, and no
  properties at all.
- `ApiCallExecutorConfigReferenceTest` +1: the request `autoVaultedPropertyHeader` sends, refused
  byte-for-byte when the same property is unmarked — the vault is never asked and nothing is sent.
- `PropertySetterTaskSecretScrubTest` +2: a `scope: "secret"` write is marked; an ordinary write of
  the identical string is not.
- `PropertyTest` +5 (JSON round-trip, an unmarked property omits the field, a pre-marker document
  reads back unmarked), `MemoryCheckpointTest` +1 (the marker survives the deep copy).
- Mutation-checked one at a time: the guard ignoring the marker fails 3 tests, `autoVaultSecret` not
  writing it fails 1, the checkpoint clone dropping it fails 1.
- apicalls, properties, memory and secrets suites plus the repo-wide guards: 6479 tests green.

## 🔒 fix(hitl): MCP `get_approval_status` detail=full no longer serves raw tool arguments (2026-09-19)

**Repo:** EDDI (`fix/mcp-approval-detail-redaction`)

The REST `GET /agents/{id}/approval-status?detail=full` returns the snapshot through
`ConversationMemoryUtilities.sanitizePendingToolCallsForApprover`, which removes the pending batch's
`argumentsRaw`, `chatTranscriptJson` and `traceSoFar`, masks the request fingerprint and re-redacts the
served arguments and preview. The MCP mirror, `McpHitlTools.getApprovalStatus`, called only
`stripRequestFingerprintsForRead` — one step of that method — so every MCP caller the read gate
admitted received the raw arguments of each gated tool call (a clear-text API key has been observed
there) plus the full serialized LLM transcript. On a deployment without OIDC,
`eddi.mcp.allow-unauthenticated=true` makes that surface reachable from the network.

### What changed

- **`McpHitlTools.getApprovalStatus`** — `detail=full` now serializes
  `sanitizePendingToolCallsForApprover(snapshot)`, the same call the REST surface makes.
- **`ConversationMemoryUtilities.stripRequestFingerprintsForRead` is now `private`.** The divergence
  existed because there were two public projections to choose from; now there is one, and the compiler
  refuses the old call. Its Javadoc and the sanitizer's say both surfaces must use the sanitizer.
- **`McpHitlToolsTest.getApprovalStatus_detailFull_neverServesRawArgumentsOrTranscript`** — builds a
  paused snapshot with a canary in `argumentsRaw`, `chatTranscriptJson` and `traceSoFar`, serializes
  through the real `JsonSerialization` (a mocked serializer hides which fields ride along), and asserts
  the canary is absent while `argumentsRedacted` is still served. It fails on the previous code.
- **`docs/hitl.md`** — the approver read-scope paragraph states the sanitization applies to REST and MCP.

### Decisions

- **Group variant: no change.** `get_group_approval_status?detail=full` (MCP) and its REST twin both
  return the `GroupConversation` unmodified. That document carries no `PendingToolCallBatch`, no
  `argumentsRaw` and no LLM transcript JSON — its `transcript` is the group discussion itself, which is
  the documented content of the full view, and `hitlLastPauseFingerprint` digests task state, not a
  request. Member tool-call pauses inside group turns (`inGroupTurns: INBOX`) are still reserved, so
  nothing gated per call is stored on the group document. Both surfaces already serve the same thing.
- **Readers swept, already safe:** REST `approval-status` (both views — summary builds `pauseDetails`
  from `argumentsRedacted`, re-redacted); `RestConversationStore` raw log (`redactRawPendingToolCallsForRead`,
  names only) and simple log (`convertSimpleConversationMemory`, names only); `ConversationService.readConversation`
  and every say/resume response (`convertSimpleConversationMemorySnapshot`, names only) and so MCP
  `read_conversation`, the OpenAI-compatible bridge, `ConverseWithAgentTool` / `CreateSubAgentTool`
  (tool names only); Slack approval cards (`SlackHitlSupport` reads `argumentsRedacted`, re-redacted);
  GDPR Art. 15 export (conversation outputs only); the HITL audit entry (`argsDigest`, a SHA-256 — not
  the arguments); `RestToolHistory` (step traces, owner-only, not the pending batch);
  `RestTemplatePreview` (`MemoryItemConverter` exposes no HITL fields).
- **The concurrent change landed first, and this branch absorbed it.** `fix/gemini-thought-signatures`
  (#794) added `PendingToolCallBatch.gatingAssistantMessageJson`, which embeds every gated call's raw
  arguments. It dropped that field inside `stripRequestFingerprintsForRead`, with the reasoning that
  the method was "the one method every full-detail read calls — including the MCP approval-status
  tool, which calls nothing else". That premise is exactly what this branch removes, and its own
  sanitizer already nulls the field, so the merge keeps the strip there and drops the duplicate: one
  projection, one place a new raw-argument field has to be listed. The test that asserted the partial
  strip drops the field now asserts that the partial strip is private — the invariant that kept the
  two doors from drifting again.

---

## 🐛 fix(operator): review follow-ups on the self-URL fix — stricter origin, later retirement, factual tool errors (2026-09-19)

**Repo:** EDDI (`fix/operator-self-url`) — backend and `ui/manager/`, follow-up to the entry below (PR #795 review)

**What changed**

- **`eddi.self.base-url` must be a bare origin.** `SelfUrlResolver` now rejects a value
  with a path, query, fragment or userinfo (falls back to loopback, logged at ERROR).
  Every consumer appends an API path verbatim, so `https://eddi.internal/base` silently
  retargeted every call and `http://eddi:7070?tenant=x` turned every path into query
  content. The Manager's activation form applies the same rule by parsing with `URL`
  (`isOriginOnlyBaseUrl` in `lib/api/operator.ts`) instead of a character class that
  still admitted `?` and `user:pass@`.
- **Plain-HTTP, non-loopback self URL is warned about, not refused.** `${caller:token}`
  is released to that address, so the resolver logs a WARN at startup that the token will
  cross the network unencrypted unless a mesh protects it. Refusing it would break the
  in-cluster service-name case the override exists for.
- **The superseded operator is retired only after the replacement passes verification.**
  `useActivateOperator` used to undeploy and delete the predecessor before
  `verifyGateInstalled` / `enforceGateDryRun`; when either rolled the replacement back,
  the deployment had no operator at all. Retirement now runs last, and the failure path
  (`handBackToPredecessor`) never retires the predecessor. It asks the agent store
  whether the replacement still exists — via `GET /agentstore/agents/{id}/currentversion`
  (200 present, 404 absent; new `getAgentCurrentVersion` in `lib/api/agents.ts`), NOT the
  version-less `GET /agentstore/agents/{id}`, which a live 6.4 answers with 400 for an
  existing agent and an unknown id alike — rather than inferring it from the config
  variable (`resetOperator` deletes the agent before clearing the variable, so a failed
  clear leaves a config naming a deleted agent): gone means the predecessor's config is
  written back; still present — including a predecessor with no recorded version — means
  both are left and the error names both. Tests in `use-operator-supersede.test.tsx`,
  mutation-checked against the old ordering and the first version of the hand-back. Their
  agentstore mocks mirror the measured 6.4 behaviour (version-less GET → 400) and assert
  the exact URL the presence check calls: the first cut of this check used the
  version-less GET, and a mock that answered it with the document let it pass while it
  could only ever return "unknown" against a real backend.
- **Tool failure messages state facts, not reporting policy.** `HttpCallToolsProvider`
  no longer tells the model "report this to the administrator" or that a refused
  connection is "NOT a fault in the service" — a stopped listener refuses too. The
  connect-class message now says the request failed before any response and lists what
  to check (base URL reachable from the server, network path, listener); the SSRF
  message says no request was sent and that the base URL must pass the full SSRF policy.
- **Docs:** the SSRF guidance now says the self URL must pass the *whole* target policy
  (private and link-local are refused too, so an in-cluster name usually stays blocked);
  the `unresolved` self-URL case is documented in `httpcalls.md`, `hitl.md`, `AGENTS.md`
  and `ui/manager/AGENTS.md`; `eddi.self.base-url` and
  `eddi.caller-identity.self-release.enabled` are added to
  `configuration-reference.md` (CI's `ConfigurationReferenceCoverageTest` was red on
  their absence).
- **i18n:** corrected misspelled terms in the new `hi`, `ja`, `ko` and `th` strings.

**Design decisions:** the transport-security finding is answered with a startup warning
rather than a refusal, for the reason above; the tool message keeps its diagnostics in
the engine (every agent needs them) but drops the imperatives, which belong in an agent's
prompt.

---

## 🧹 chore: replace a customer name used in examples and tests with a generic one (2026-09-18)

**Repo:** EDDI (`chore/genericise-customer-examples`); mirrored on the archived EDDI-Manager repo's
branch of the same name

A worked connection example, a planning section, two changelog entries, one Javadoc and the
`CALLER_SUPPLIED` test fixtures named a real integrating customer. They now use generic values
throughout: connection `acme`, host `https://api.example.com` (RFC 2606), header `X-Acme-Key`, and
prose that describes the requirement ("an integration that hands EDDI the end user's own API key")
rather than who had it.

- **Docs:** `docs/connections.md`, `planning/saas-connectors-plan.md` §5.5, one line of this file,
  and the `CALLER_SUPPLIED` entry in the `docs/changelog/2026-08.md` archive (wording only, structure
  untouched).
- **Code:** the `RequestRedactor` Javadoc only.
- **Tests:** `ConnectionConfigurationValidationTest`, `RestConnectionStoreWriteGuardTest`,
  `ConnectionResolverTest`, `ConnectionStartupGuardTest`, `CallerIdentityContextTest`,
  `ApiCallExecutorConnectionHeaderTest`, and under `ui/manager/` the connection tests, the MSW
  fixture and `HANDOFF.md`. The replacement is used consistently in setup and assertion; no behaviour
  changed, and the same tests pass before and after.

The removal is from the tree only. Git history is not rewritten — that would need a force-push to
`main`.

---

  connection on `X-Amp-Id` or a `CALLER_SUPPLIED` one on `X-Acme-Key` matched none, so the live

## 🖥️ feat(manager): ingestion sources panel for knowledge bases (2026-09-18)

**Repo:** EDDI (`feat/manager-ingestion-sources`, stacked on `feat/rag-ingestion-rest`)

### What it adds

A section inside the RAG editor listing a knowledge base's ingestion sources, with add, edit and remove,
and — once the knowledge base is saved — run, preview, purge and recent run history.

### Why a rewrite rather than a port

The stale `labsai/EDDI-Manager#95` (now archived with that repository) targeted standalone
`/ragstore/ingestion-sources` resources. Sources now live on the knowledge base as `sources[]`, so
creating or editing one is an ordinary save of the RAG config, and only the four runtime verbs have
endpoints of their own. The resulting component is considerably smaller than the 846-line original.

### Decisions

- **A new source cannot be run.** It has no id for the endpoints to address until the knowledge base is
  saved; the panel says so instead of offering a button that 404s.
- **Preview is headed "nothing was embedded".** A preview that looked like a run would be worse than none.
- **Purge goes through `AlertDialog`**, since the next run re-embeds everything the source had ingested.
- **Run history polls only while a run is `RUNNING`.** A crawl takes minutes and the start endpoint
  answers 202; an idle screen should not tick forever.

### Contract snapshot

`ui/manager/src/test/mocks/openapi-operations.json` was regenerated from the OpenAPI document Maven
produces. `openapi-contract.test.ts` failed until it was, because the new MSW handlers mocked endpoints the
old snapshot did not know — and the regenerated diff was exactly the four new endpoints, nothing else.

### Tests

16 cases in `resource-detail-rag-sources.test.tsx` (9 when this entry was written, 7 added by later review rounds); 6558 passing across 413 files. `lint`, `typecheck`,
`i18n:check` and `build` all pass, with translations for all 11 locales in the same commit.

---

## 🔧 fix(rag): review findings on the ingestion salvage (2026-09-18)

**Repo:** EDDI (`feat/rag-ingestion-rest` and the branches below it)

A critical review of the six salvage branches returned ten must-fix findings. All are fixed, each with a
test that fails when the fix is reverted. The headline four fixes the salvage was built around held up
under mutation; every finding below was in a seam those tests did not reach.

### Would not have started at all

`PostgresIngestionStateStore` was `@ApplicationScoped` without `@DefaultBean` while `DataStoreProducers`
also produces `IIngestionStateStore` — two non-default candidates, so ArC fails augmentation and nothing
boots. Every sibling Postgres store carries `@DefaultBean` for exactly this reason. No unit test can see
this; the review caught it by reading the pattern.

### Silent data loss

- **A tombstoned page that came back unchanged was never re-embedded.** Its vectors had been deleted, so
  comparing hashes alone made it "unchanged" forever and permanently unretrievable. Two routes, both
  closed: `hasChanged` is now true for a tombstoned document, and it is no longer revalidated with its
  stored ETag (a 304 never re-embeds).
- **Schedules were orphaned.** `syncSchedules` walked only the new document's sources, so a source
  deleted from `sources[]` kept its schedule and went on crawling a third party on a cron with nothing
  left in the configuration to switch it off. The previous version's source ids are now diffed.
- **Deleting an old version killed the live version's schedules.** Removal now happens only when no
  readable version remains.
- **A duplicated knowledge base shared the original's ingestion identity.** Vector stores are keyed by
  name, which a duplicate shares, so the copy's runs replaced and tombstoned the original's chunks while
  the original's state still said "unchanged". A copy now gets fresh source ids and no cron.
- **Vector removal ran after the tombstone was recorded**, and a tombstoned document is never reported
  again — so a failed removal orphaned those vectors permanently. A failure now un-tombstones for retry.

### Runs that wedged their own source

- Only `crawler.crawl` was guarded and only `RuntimeException` caught, so a state-store failure or an
  `Error` left the run `RUNNING` forever: every later manual run a 409, every scheduled fire a failure.
  Everything after the claim is now guarded by `Throwable`, and `reapStaleRuns` is finally called before
  claiming — nothing called it, although the interface said otherwise.
- `ALREADY_RUNNING` and `SKIPPED` were mapped to `FireStatus.FAILED`. The schedule lease is five minutes
  and a crawl's default budget is ten, so the schedule is legitimately re-claimed while the first run is
  still going; the second fire lost the single-in-flight race and was recorded as a failure, dead-lettering
  the schedule within days.
- The converter's recursive walk had no depth cap. jsoup builds the full DOM — 60,000 levels if the page
  says so — and a `StackOverflowError` is an `Error` that sails past every `catch (Exception)`.
- Body reads had no time bound: `HttpRequest.timeout` covers headers only, so a server trickling one byte
  per second held a virtual thread indefinitely.

### Crawler correctness

- Only successful pages entered the visited set, so a dead link in a site-wide footer was fetched once per
  referring page — N errors, and a fetch budget so exhausted that the crawl never reported full coverage,
  which silently disabled deletion reconciliation for that site on every run.
- `isHtml()` matched any content type containing `xml`, so a linked sitemap or RSS feed became a
  knowledge-base document of concatenated `<loc>` URLs.
- robots.txt was fetched for the seed host and applied to every host reached.

### Divergent backends

The Postgres store swallowed every `SQLException` and returned a plausible answer — `lookup` said "never
ingested" (re-embedding the page every run), `startRun` said "already running" (a 409 for a database
fault), `finishRun` left the run `RUNNING`. Mongo threw. Both now fail identically through
`IngestionStateStoreException`, which is the divergence the shared contract exists to prevent.

### Also

Preview was synchronous, unguarded and uncapped — up to a day on a request thread per click; it now runs
with a two-minute budget. `runAsync` leaked an `ExecutorService` per call and swallowed `Error`s. Third-party
titles are capped before being copied onto every segment. `ref`/`referrer` are no longer stripped as
tracking parameters (they select content on plenty of sites). `RobotsPolicy` matched `User-agent` by
substring, so `User-agent: a` captured every crawler.

### Tests

398 across the stack, up from 354: 20 new cases, one per finding, each mutation-checked. The repo's own
`ImportStyleTest` caught two inline FQNs I had introduced.

## 🐛 fix(llm): Gemini 3.x could not use tools at all — thought signatures were dropped (2026-09-18)

**Repo:** EDDI (`fix/gemini-thought-signatures`, branched from `origin/main` @ 798c6e84d)

Every Gemini 3.x model was unusable with tools. On a production agent using function calling on
`gemini-3.8-flash`, the first tool call failed with `400 INVALID_ARGUMENT — Function call is missing
a thought_signature in functionCall parts`. Gemini 3.x attaches an opaque `thoughtSignature` to
`functionCall` parts and requires it echoed back when that model turn is replayed on the follow-up
request carrying the `functionResponse`. `gemini-3.8-flash` and `gemini-3.5-flash` reject the replay
without it, `gemini-2.5-flash` tolerates it, and `thinkingBudget: 0` does not help — measured table
in [`langchain.md`](langchain.md).

### Where the fix landed, and why

langchain4j 1.20.0 **already models the field**, so this is neither an upgrade nor an adapter. It
gates both halves behind builder flags that default off: `PartsAndContentsMapper` captures the
signature into `AiMessage.attributes()["thinking_signature"]` only when `returnThinking == TRUE`,
and re-sends it only when `sendThinking == true`. EDDI set neither. Four places dropped the field:

1. **`GeminiLanguageModelBuilder`** — both flags now default **true** on `build` and
   `buildStreaming`, overridable as `recognisedParameters`. A default rather than opt-in: this is
   protocol correctness, not something an agent designer should learn from a 400.
   `ModelParameterValues.booleanValue(params, key, default)` reads it, so a typo falls back to the
   default instead of `Boolean.parseBoolean`'s silent `false`.
2. **`ToolApprovalGateSupport.normalizeToolCallIds`** rebuilt the message with `AiMessage.from(...)`,
   which carries only text and requests, so enabling the tool-approval gate alone broke Gemini 3.x
   again. Now `toBuilder()`. Blank text still collapses to null: the message is replayed, the Gemini
   mapper sends whitespace-only text as its own part, and Anthropic rejects such blocks.
3. **`gatingAssistantMessageOf`** read `getLast()`, but in a mixed batch the ungated calls execute
   and append their results *before* the pause is snapshotted, so it found nothing. It now walks
   back over this batch's tool results, stopping at anything else so it cannot borrow an earlier
   turn's message. `interimTextOf` had the same pre-existing blind spot — approvers lost the model's
   narration on mixed batches — and shares the walk.
4. **`ToolLoopResumer`, degraded resume** (transcript over its byte cap) replayed a bare
   `AiMessage.from(requests)`. `PendingToolCallBatch.gatingAssistantMessageJson` now keeps the
   gating message — written only when the transcript was omitted (a kept transcript already
   carries it, and a codec change would break both copies alike), capped at 64 KB on its own —
   never by the transcript's cap, since a small transcript cap is what triggers this path — shedding text
   then thinking before its attributes. `gatingExchange` replays it **unchanged**, original parts in
   original order, answering each ungated call with `HANDLED_BEFORE_PAUSE` — handled, not "ran",
   since an ungated call may have been refused or failed, and the outcome is what this path lost.

**Why replay the original parts** rather than rebuild from the gated calls: Gemini signs part 0 of
a parallel batch. Measured once against the live Gemini API (3.8 and 3.5 Flash), moving that
signature onto a different call was accepted — as were the original parts in order; only an
unsigned replay was rejected. So the rebuild works today, on undocumented leniency that would fail
on the rarely exercised degraded path if Google tightened it. Replaying what the model emitted
stays valid, matches the shape the primary resume path already produces, and records the ungated
call.

**Security.** The new field embeds the gated calls' raw arguments — the content
`sanitizePendingToolCallsForApprover` strips `argumentsRaw` for. The first cut excluded it from the
names-only projection but not from the approver `detail=full` surface; it is now dropped in
`stripRequestFingerprintsForRead`, which every full-detail read calls (REST through the approver
sanitizer, the MCP approval-status tool directly), and named in the sanitizer too. Canaries pin all
three projections. *Pre-existing and out of scope:* the MCP `detail=full` path applies only the
fingerprint strip, so it already serves `chatTranscriptJson` and `argumentsRaw` today; only the new
field is closed there.

**Multi-turn.** The HITL tool pause is the only place a tool-carrying model turn crosses a request
boundary and a MongoDB write; `AiMessage.attributes()` round-trips langchain4j's codec. Cross-turn
replay needs no signature: `ConversationHistoryBuilder` rebuilds prior assistant turns as text only.

**Precondition, recorded where the flags are set.** The mapper joins every part's signature into
one attribute and re-sends it on the first `functionCall`. Measured once, every turn shape carried
exactly one signed part (a narrating text part is unsigned). Thought parts could add more — they
appear only with `thinkingConfig`, which EDDI does not expose; re-check before exposing it.

### Tests

`GeminiThoughtSignatureTest` swaps in langchain4j's own `HttpClient` through a package-private
builder seam, so the real model, mapper and codec run with only the socket replaced. The stub
**enforces** Gemini's rule (an unsigned `functionCall` gets the real 400) and an opt-out test proves
the check is live. Streaming is driven over two SSE frames, so attributes merge across frames.
Mutations, each failing the intended test: both flags removed; `sendThinking` only (the verbatim
400); `sendThinking` in `buildStreaming` only; `normalizeToolCallIds` back to `AiMessage.from`;
`gatingAssistantMessageOf` back to `getLast()`; the bare degraded rebuild; the field written with
a kept transcript; either leak strip removed; the Vertex warning call removed from `build()`.

**Live run of the patched build** (`5be8d02f1`, real Gemini API, `gemini-3.5-flash`, an agent with the
calculator and datetime built-in tools and a system prompt forcing tool use; the same driver script run
against both builds):

| Scenario | Patched `5be8d02f1` | Unpatched 6.4.0 release image |
| --- | --- | --- |
| Non-streaming turn 1 (tool call) | correct, READY | ERROR |
| Non-streaming turn 2 (history holds a tool turn) | correct, READY | ERROR |
| Non-streaming turn 3 (two tool calls in one turn) | correct, READY | ERROR |
| Streaming SSE tool turn | correct, READY (`task_start`, `tool_call`, `token`, `task_complete`, `done`) | ERROR (`task_failed`) |
| HITL `requireApproval: ["builtin:*"]` — pause before the tool runs | AWAITING_HUMAN | AWAITING_HUMAN |
| HITL — resume with APPROVED | correct, READY | ERROR |
| **Total** | **6/6** | **1/6** |

The unpatched container logged 33 Gemini 400s, "Function call is missing a thought_signature in
functionCall parts". Not covered live: the degraded resume path (transcript over its cap), which is
exercised only by the unit tests.

### Provider survey — the same defect class elsewhere

| Provider | Verdict |
| --- | --- |
| `gemini` | was live — fixed here |
| `gemini-vertex` | **live, not fixable in EDDI.** `langchain4j-vertex-ai-gemini:1.20.0-beta30` has no `thought`/`thinking`/`signature` anywhere, and the `Part` protobuf it uses (`proto-google-cloud-vertexai-v1:1.27.0`, via `google-cloud-vertexai`) has no `thought_signature` field. Needs upstream changes; `build()` now warns for Gemini 3.x ids, bare or fully qualified, naming `gemini` |
| Anthropic, Bedrock | **latent.** Signed thinking blocks, modelled by langchain4j under the same `thinking_signature` key, but no config key enables extended thinking, so no signed block is ever returned. Set `returnThinking(true)` when that is exposed; fixes 2–4 already apply |
| OpenAI, Azure OpenAI | fine — Chat Completions has no opaque reasoning token; EDDI never uses the Responses API |
| Mistral, Ollama | fine — plaintext thinking, no signature |
| HuggingFace, Jlama, Oracle GenAI | fine — no reasoning concept in the modules |

### Files

`GeminiLanguageModelBuilder`, `ModelParameterValues`, `VertexGeminiLanguageModelBuilder`,
`ToolApprovalGateSupport`, `ToolLoopResumer` (`gatingExchange`), `ChatTranscriptCodec`,
`PendingToolCallBatch`, `ConversationMemoryUtilities` (the strip and the sanitizer);
`docs/langchain.md`, `docs/hitl.md`; tests `GeminiThoughtSignatureTest`,
`ToolApprovalGateSupportNormalizeTest`, `ToolLoopResumerGatingMessageTest`,
`VertexGeminiVersionWarningTest`, plus additions to `ModelParameterValuesTest` and
`ConversationMemoryUtilitiesHitlTest`. `AgentOrchestratorCoverageTest`'s mixed-batch fixture was
given the runtime message order for accuracy; it guards nothing new.

`LanguageModelBuildersTest` shows 13 sandbox-only errors (`Unable to establish loopback connection`
from `JdkHttpClient`) — identical on a clean tree.

## 🐛 fix(operator): the Platform Operator's self-URL, its replacement, and its error message (2026-09-18)

**Repo:** EDDI (`fix/operator-self-url`) — backend and `ui/manager/`, in one branch so the two halves are tested together

The Platform Operator was dead on arrival on a customer deployment. The agent
deployed, reported "Gate verified", and then failed **every** tool call — while telling
the admin that "the documentation service is currently unavailable" and that the refused
connection "indicates a problem with the platform's internal services". EDDI's health was
fine the whole time.

All 22 of the operator's api-call resources carried
`targetServerUrl: http://localhost:7080` — the origin the **browser** had used, over an
SSH tunnel, in front of a container listening on `:7070`. That address means
nothing inside the container. (`:7070` is confirmed by the container's own health check,
`curl -f http://localhost:7070/q/health`, which passes from inside.) Three separate
defects, each of which made the other two harder to find.

### Defect 1 — the self-URL came from the browser

`provisionOperator` sent `apiBaseUrl: window.location.origin`. That is right only when
nothing sits between the browser and EDDI; any tunnel, published-port remap, container
port remap or reverse proxy on another port produces a dead operator, silently.

**Where the fix belongs: the backend has to supply the address, and it did not.** The
browser cannot know it, and neither can any other client. Three mechanisms were weighed:

| Option | Verdict |
| --- | --- |
| Manager offers an explicit, pre-filled override | Necessary but **not sufficient alone** — pre-filled from what? |
| The operator's calls use a placeholder resolved server-side (`${self:baseUrl}`) | Rejected: a new resolver in the hot path of every api call, and it hides the value that the incident was diagnosed by *reading* |
| **EDDI exposes its own base URL; the Manager asks and pre-fills an editable field** | **Chosen** |

So: **`SelfUrlResolver`** (new, `ai.labs.eddi.engine.security`) answers
`eddi.self.base-url` when a deployment sets it, otherwise
`http://127.0.0.1:${quarkus.http.port}` — the port read from config, not assumed, and the
same address `RestInterfaceFactory` has always used for EDDI's internal loopback hop.
Loopback is correct behind a reverse proxy and on a remapped port *because* it ignores
both: a process reaches itself without going back out through whatever is in front of it.
The override exists for the cases where loopback genuinely is wrong — in-process TLS, a
service name a mesh requires. It is served by **`GET /administration/operator/self-url`**
(`IRestOperatorMetrics`, `eddi-admin`), a GET with no arguments so the answer cannot
depend on a `Host` header or an `X-Forwarded-*` chain a proxy rewrites.

**The part that would have turned one failure into another.** On an OIDC-protected
deployment the operator's tools authenticate with `${caller:token}`, and
`CallerIdentityResolver` releases that token **same-origin only**. Pointing the tools at
EDDI's own address makes them cross-origin by that rule — so the fix for defect 1 would
have produced a 401 on every call instead of a connect failure. `CallerIdentityResolver`
now also releases the token when the target is *this very process*
(`SelfUrlResolver.isSelf`). That is the same argument `LoopbackCallerAuthFilter` already
makes for the internal hop: the token is handed back to the process that issued the
request it came from. It is a **narrower** release than same-origin, not a wider one —
`SelfUrlResolver`'s value comes from deployment configuration only, never from an agent
config, a conversation or a request header, so no config can nominate itself. Counted
under its own `resolved_self` outcome tag rather than folded into `resolved`.

Manager side: `OperatorConfig` gains `apiBaseUrl` (optional — a config blob written before
this field has no key at all), `resolveOperatorApiBaseUrl` decides it (explicit value →
backend answer → browser origin *with a warning*, only on a backend that 404s the new
endpoint), and `provisionOperator` now **throws** rather than falling back: the silent
fallback is the defect. The value is persisted, not just sent, so the operator screen can
show the address the live tools call — the field the incident turned on was, until now,
nowhere on screen.

### Defect 2 — Reconfigure left the old operator deployed

Changing only the model produced **two** operators on staging, both `READY`, with the UI
silently talking to the new one. That cost real debugging time: the first repair was
applied to the agent that was no longer in use, and the symptom did not move.

Not a platform constraint — the two bots on that instance are versioned in place, and
`setup-api` creating a new agent id is a Manager consequence, not an EDDI one. The Manager
already *tried* to retire the predecessor; it failed for two reasons, both fixed:

1. `removeSupersededAgent` undeployed **without** `endAllActiveConversations`. The backend
   answers 409 while an agent still has active conversations, and the superseded
   operator's active conversation is almost always the admin's own operator chat — on the
   very screen the Reconfigure button lives on. So having *used* the operator was enough
   to make its replacement leave it deployed. `deactivateOperator` and `resetOperator`
   already pass the flag for exactly this reason.
2. The caller wrapped the whole retirement in a bare `catch {}`. A failed retirement now
   travels back as `ActivationOutcome.supersededWarning`, naming **both** agent ids, and
   the operator page shows it as a persistent destructive banner — not a toast, because
   the admin needs to still be able to read it when they start wondering why the operator
   is behaving oddly. (`activationError` was no use: it renders inside the activation form,
   which is already closed by then.)

The replacement is also explicit now rather than implied: the status panel shows the agent
id and the base URL it calls, and the pre-save warning says the current agent is
undeployed and deleted, names it, and says this screen will address the new one from then on.

### Defect 3 — the failure message pointed at the wrong thing

A transport failure surfaced to the model as the bare exception message —
`"Connection refused"` and nothing else. The model has no way to tell an unreachable
target from a broken dependency, so it guessed, and its guess sent the admin to check
EDDI's health.

`HttpCallToolsProvider.describeToolFailure` now recognises a connect-class failure
(`ConnectException`, `UnknownHostException`, `UnresolvedAddressException`,
`NoRouteToHostException`, connect timeouts — matched by type through the whole cause
chain, with a message-text fallback for clients that flatten it), names the method and the
address that was tried, states that this is a network failure reaching that address and
**not** a fault in the service behind it, and says the configured base URL must be one the
EDDI server can reach rather than one a browser uses. Every other failure keeps its own
message: telling the model to suspect the base URL on a 400 would misdirect in the other
direction.

**What may travel in that string.** It reaches the model and so, in paraphrase, the chat
surface. The URL goes in — it is the whole diagnostic value and it is configuration an
admin can already read. Headers do not. Neither does anything vault-resolved, which is why
the address is built from `targetServerUrl` plus the **configured** path rather than the
fully-resolved request URI: `ApiCallExecutor` resolves `${vault:…}` and global-variable
references into that URI, so it can legitimately hold a secret. The result is passed
through `SecretRedactionFilter` as a belt-and-braces measure against a base URL that
embeds credentials.

The Manager's activation canary gained the matching diagnosis: a connect-shaped tool
result now reports the configured base URL and says outright that this is not an EDDI
outage. It is checked *after* the auth check and allowed to win — an unreachable address is
the more actionable of the two, and a 401 cannot have happened if nothing connected.

### Files

**Backend:** `engine/security/SelfUrlResolver.java` (new),
`engine/api/model/OperatorSelfUrl.java` (new), `engine/api/IRestOperatorMetrics.java`,
`engine/rest/RestOperatorMetrics.java`, `engine/security/CallerIdentityResolver.java`,
`modules/llm/impl/HttpCallToolsProvider.java`, `resources/application.properties`
(documents `eddi.self.base-url`). Tests: `SelfUrlResolverTest`,
`CallerIdentitySelfOriginTest`, `HttpCallToolsProviderFailureMessageTest`,
`RestOperatorMetricsTest`.

**Manager (`ui/manager/src/`):** `lib/api/operator.ts`, `hooks/use-operator.ts`,
`components/operator/operator-activation.tsx`, `components/operator/operator-status.tsx`,
`pages/operator.tsx`, `test/mocks/handlers.ts` (one handler for the new endpoint),
`test/mocks/openapi-operations.json` (regenerated from this branch's own spec with
`OPENAPI_FILE=../../target/openapi/openapi.json npm run openapi:refresh` — one line
added, `GET /administration/operator/self-url`; no contract-test exemption needed), all
11 locales, plus tests in `lib/api/__tests__/operator.test.ts`,
`hooks/__tests__/use-operator-supersede.test.tsx` (new),
`components/operator/__tests__/operator-activation.test.tsx`,
`pages/__tests__/operator.test.tsx`, `pages/__tests__/operator-superseded.test.tsx` (new).

**How the halves line up.** The Manager calls `GET /administration/operator/self-url`
and reads `{ baseUrl, source }` — the `OperatorSelfUrl` record exactly, with `baseUrl`
typed nullable for the `unresolved` case. A 404 (a backend older than the endpoint)
reads as "cannot tell" and falls back, with a warning; every other error, a 401/403
included, propagates rather than being guessed past. The MSW handler answers the
loopback shape the backend produces by default, and the snapshot the contract test
checks it against was generated from this branch, so a drift in either direction fails.

### Mutation checks

Every fix was reverted and the pinning test confirmed red.

| Mutation | Test that failed |
| --- | --- |
| `provisionOperator` back to `currentOrigin()` | "targets the address EDDI can reach ITSELF at, not the browser's origin" |
| `resolveOperatorApiBaseUrl` prefers the browser origin | the five `resolveOperatorApiBaseUrl` cases |
| `requireApiBaseUrl` falls back to the origin | "refuses to provision without a resolved base URL" |
| `CallerIdentityResolver`'s `isSelf` branch removed | `resolvesForSelfWhenCallerOriginDiffers` |
| `SelfUrlResolver` hardcodes 7070 | `followsNonDefaultPort` |
| `SelfUrlResolver` ignores the configured override | the `eddi.self.base-url` cases |
| `selfUrl()` answers a constant | `selfUrlAnswersTheDeploymentsOwnAddress` |
| `describeToolFailure` back to `e.getMessage()` | `namesTheAddressThroughTheExecutor` |
| retirement drops `endAllActiveConversations` | "ends the superseded operator's conversations so its undeploy cannot 409" |
| `supersededWarning` forced to null | "reports a failed retirement instead of swallowing it" |
| canary drops the connection diagnosis | the three connect-diagnosis canary cases |

One of those mutations initially **survived**, and the fix for that is worth carrying
forward: the first cut of `HttpCallToolsProviderFailureMessageTest` only called
`describeToolFailure` directly, so reverting the *catch clause* to `e.getMessage()` left
the helper intact and all 11 cases green. The test now also drives the real executor
lambda `discover` builds (`Wiring`, with a mocked `IApiCallExecutor` that throws), which is
what actually pins what a failing tool call hands back to the model. A helper-level test
proves the helper; only the call site proves the behaviour.

One process note, because it cost half an hour twice: a mutation check must **not** be
undone with `git checkout -- <file>` while the fix is unstaged — that discards the fix
along with the mutation. Copy the file aside and copy it back. And copy it back with
`shutil.copy` rather than `copy2`: `copy2` preserves the backup's mtime, so the restored
source looks *older* than the `.class` Maven compiled from the mutated one, incremental
compilation skips it, and every later run keeps testing the mutation. That reads exactly
like a real regression.

### Independent review, and what changed because of it

A fresh reviewer that had not seen this work went through both halves adversarially.
Its security pass on the `CallerIdentityResolver` change found the value provenance
sound — `SelfUrlResolver` reads only `eddi.self.base-url` and `quarkus.http.port` at
construction; nothing writes config at runtime; no agent config, global variable, vault
reference, `Host` or `X-Forwarded-*` header reaches it — and the origin check sound
against look-alikes: `OriginMatcher` compares parsed `scheme://host:port` with no DNS, so
`https://`, `localhost`, `127.1`, `0.0.0.0`, `[::1]`, `[::ffff:127.0.0.1]`, another port
and `http://127.0.0.1:7070@evil.example` are all refused, and the check runs on the final
URI after template, global-variable and vault resolution, so nothing can alter the target
after it. Every finding was addressed:

| # | Finding | Resolution |
| --- | --- | --- |
| 1 | `describeToolFailure` claimed to redact URL credentials but `SecretRedactionFilter` only knows secret *shapes*; a plain `admin:hunter2@` went through, and the test passed only because it used an `sk-ant-` password | `stripUserInfo` removes `user:pass@` structurally from every URL in the message; new test with a plain password, mutation-checked |
| 2 | `AGENTS.md`, `docs/httpcalls.md`, `docs/mcp-server.md` still stated same-origin as absolute | All three updated |
| 3 | "Narrower than same-origin" was wrong: the self address bypasses the reverse proxy, so the reachable endpoints are what EDDI authorizes, not what the proxy also permits | Claim corrected in code and docs; new `eddi.caller-identity.self-release.enabled` (default true) for a deployment that relies on proxy rules too |
| 4 | With SSRF protection on, loopback is refused and the model got a raw "internal/local addresses" message | Not exempted — that would weaken SSRF protection for every agent. Documented instead, and the tool result now names SSRF protection and the remedy (`eddi.self.base-url` to a non-loopback address) |
| 5 | An identity with no captured origin became releasable to self (fail-closed to fail-open) | Kept fail-closed: `origin == null` never gets the self release; test added |
| 6 | The page banner for a failed retirement was untested | `operator-superseded.test.tsx` drives the page's own wiring |
| 7 | A stored address wins over the server's on reconfigure, and the "derived from HTTP port" note could sit under a different value | The form now says when the field differs from the server's current answer; the loopback note shows only when they match |
| 8 | An admin-typed trailing slash became `//path` in every tool | `normalizeBaseUrl` strips it before provisioning |
| 9 | Netty's connect timeout (a `ConnectException` subclass) read as "refused"; `SocketTimeoutException` also covers READ timeouts, where the service *is* at fault; the "unresolved" text fallback was too broad | Netty timeout matched first by name; `SocketTimeoutException` dropped; fallback narrowed to "unresolved address"; tests for both |
| 10 | A predecessor with no recorded version was skipped silently | Reported through `supersededWarning` like any other failed retirement |
| 11 | `quarkus.http.port=0` (random) answered a confident `:7070` | Now `source: unresolved`, `baseUrl: null`, `isSelf` false for everything; the Manager treats it as "cannot tell" |
| 12 | Some operator strings are not i18n keys | Declined: matches every existing `toolError` string in the file; `i18n:check` is green |
| 13 | `headersOnlyStillHolds` tested code this change never touched; the canary's "agent description" test used text none of the regexes matched | The first removed; the canary match is now anchored to the `{"error": …}` failure shape and the test uses a description containing the exact trigger phrases |

Every review fix was mutation-checked the same way: R1–R7 in the backend (null
origin, the switch, userinfo, the SSRF branch, Netty timeout, read timeout, random port)
and U1–U9 in `ui/manager` — each reverted, each failing its named test.

A second fresh review of the final branch, with the Manager in `ui/manager/`, found the
security pass sound again (provenance, look-alikes, the URI checked being the URI sent,
null-origin and opt-out wiring, no open redirect that would carry the header) and nine
smaller findings, all addressed: `docs/hitl.md` still carried the retracted "narrower"
claim; the form promised a browser-origin fallback on a 403/500 that activation would not
perform (now a distinct notice, and `retry: false` so it appears at once); an `unresolved`
answer fell back to the browser origin — the original defect's value — and now
refuses with a clear message, the fallback reserved for a genuine 404; `stripUserInfo`
stopped at the first `@`, leaking the tail of an un-encoded `p@ss` password; client
validation accepted a path or trailing text; the regression test resolved through the
helper's preset address instead of the backend (now `apiBaseUrl: null`); a note on
`quarkus.http.test-port`; the notice icon; and an `sk-ant-` test fixture replaced with
`sk-test-`. Mutation-checked: V1–V4, each failing its named test.

### Noted, not fixed

- **`HttpCallToolsProvider`'s sibling path.** `ApiCallsTask` (rule-based httpcalls, no LLM)
  has the same bare-message behaviour. Left alone: there is no model there to misdiagnose
  for a human, and the diagnostic belongs where a model paraphrases the error.
- **`activationError` is unreachable after a successful activation.** It renders only
  inside the activation form, which the success handler closes — so the existing
  write-probe failure path (`setActivationError(message)` in `pages/operator.tsx`) is
  visible only as its toast. Worked around here with a dedicated banner rather than
  fixed for both.
- **`secret-key-picker-reference-only.test.tsx` flakes under full-suite load** — it
  passed in isolation and on a clean tree, and passed on the full suite the second time.
  Timing, not a regression from this work.

## 🔗 feat(rag): knowledge-base sources and the ingestion pipeline (2026-09-17)

**Repo:** EDDI (`feat/rag-ingestion-pipeline`)

### Why sources live on the knowledge base

`RagConfiguration` gains `sources[]` rather than ingestion sources becoming a 13th resource type. The
vector store is keyed by the knowledge base, so a source that could exist independently of one has to
name its target by string — and that is exactly how the draft in PR #529 came to key ingestion on the
**source's** name (`kbId = sourceConfig.name()`) while `RagContextProvider` keys retrieval on the
**knowledge base's**. Crawled content went into one pgvector table and every query read another. The run
reported success; the agent retrieved nothing. No test caught it because none performed a retrieval
after an ingest. Ownership removes the possibility rather than documenting it.

### The pipeline

`IngestionPipeline` runs crawl → convert → compare → embed per document, then reconciles deletions:

- **Re-ingesting replaces.** A document's chunks are removed by `documentId` metadata before its new
  ones are added. The draft called `EmbeddingStoreIngestor.ingest`, which only appends and never
  removed anything, so a page edited weekly left a year of stale versions retrievable beside the current
  one. Where a store's driver cannot delete by metadata, the run says so (`replaceUnsupported`) instead
  of quietly accumulating.
- **A document is recorded only after its vectors are stored.** The draft committed the content hash
  while *deciding* whether to ingest, with embedding afterwards inside a `catch` that only logged — so a
  single 429 marked a page done forever.
- **Only a crawl that covered the source may conclude anything is gone.** A run stopped by its page cap,
  time budget or segment budget sets `tombstoningSkipped` and deletes nothing.
- **Tombstoned documents lose their vectors.** In the draft, "stale detection" flipped a flag in a side
  table nothing consulted at retrieval time, so a deleted page kept answering questions forever.
- Segments carry `documentId`, `url`, `title`, `sourceName`, `runId` and `ingestedAt`, so an answer can
  cite its source. Counts are the segments actually written, not `markdown.length() / chunkSize`.
- `maxSegmentsPerRun` is the cost ceiling — exact without a pricing table; set
  `costPerThousandSegments` to have runs report dollars too. `PREVIEW` mode crawls and reports what
  would change without embedding or recording anything.

### Tests

**25 pipeline tests, 23 more for the in-memory state store.** The test double implements the same
`IngestionStateStoreContract` as MongoDB and PostgreSQL, so it cannot quietly behave differently from
production — the failure mode that let the draft's two stores drift apart.

Mutation-checked against all four headline defects: keying the store on the source, appending instead of
replacing, recording the hash before embedding, and tombstoning after a partial crawl each fail between
one and seven tests.

### Note on the branch

This branch is stacked: it contains the converter, state store and crawler commits because the pipeline
needs all three. Merge those three first, or review this as a stack.

## 🧩 fix(rag): register `ai.labs.rag` so RAG workflow steps can be deployed (2026-09-17)

**Repo:** EDDI (`feat/rag-workflow-extension`)

### Why

A workflow step of type `eddi://ai.labs.rag` could not be deployed at all. `WorkflowStoreClientLibrary`
resolves a step by `URI.getHost()` against the `@LifecycleExtensions` map and throws
`UnrecognizedExtensionException` when the key is absent — and no module ever registered `ai.labs.rag`.
The nine registered types were parser, behavior/rules, property, httpcalls/apicalls, output, llm,
mcpcalls and templating.

So the two knowledge-base options documented in [`rag.md`](rag.md) were undeployable; only `httpCallRag`
worked end to end. `RestWorkflowStepStore` builds the Manager's step chooser from the same map, so the
real backend never offered the step either — the Manager's MSW fixture hard-codes it, which is why its
UI suite stayed green.

Nothing caught this: `RagContextProvider` discovers RAG steps by reading the workflow document directly
(`WorkflowTraversal`), and its tests build `WorkflowConfiguration` objects by hand, so no test ever
traversed the deploy path.

### What changed

- **`modules/rag/RagTask.java`** (new) — the config-carrier task. `execute` is a deliberate no-op:
  retrieval stays in `RagContextProvider` inside the LLM task (the Phase 8c decision stands), because
  that is where the user's query is known. `configure` resolves the referenced `RagConfiguration`, so a
  broken knowledge-base binding fails when the workflow is deployed instead of silently returning no
  context on the first conversation.
- **`modules/rag/bootstrap/RagModule.java`** (new) — registers the task under `ai.labs.rag`, matching the
  host of the documented step URI.

`getType()` returns the stage name `"rag"`, not the `eddi://` URI. Per the `ILifecycleTask` contract the
type is a lifecycle stage identifier used for ordering and partial-execution filters; the URI form
matches no filter. (The draft this was salvaged from returned the URI — see Decision Log.)

### Tests

- **`RagTaskTest`** (10) — id/type contract, `configure` happy path, missing/blank/null/malformed URI,
  `ServiceException` wrapping, no-op `execute`, descriptor shape.
- **`RagWorkflowDeploymentTest`** (6) — deploys a real workflow containing a RAG step through
  `WorkflowStoreClientLibrary`, including the negative case that pins the bug: without the registration
  the whole workflow is rejected.
- **`RagModuleTest`** (3) — registration key equals `URI.create("eddi://ai.labs.rag").getHost()`.
- **`integration/RagWorkflowExtensionIT`** — asserts against live CDI wiring via `RestWorkflowStepStore`
  (the bean that feeds the Manager's chooser) that the RAG step is offered, and that every documented
  workflow step type resolves. This is the test that would have caught the original bug; it is an `IT`
  because the repo runs every `@QuarkusTest` in the integration job.

Mutation-checked: reverting `getType()` to the URI and the registration key to a wrong value fails 4 of
the tests.

### Notes

This is the first of six PRs salvaging the scheduled RAG ingestion work from PR #529, which has been
stale and conflicting since 2026-07-02. This piece is independent of that feature — main needs it either
way.

## 🛠️ fix(migration): four first-boot defects found upgrading a real 5.5.1 database (2026-09-17)

**Repo:** EDDI (`fix/first-boot-migration-order`)

Found by rehearsing an upgrade of a customer deployment's **staging** EDDI 5.5.1 database (MongoDB Atlas, 3012
documents, 7 agents, 195 conversations) to 6.4.0 against a verified restore of the production-like
dump. Four defects fire on the first boot against a 5.x database; two of them destroy data. All four
are fixed here with tests, including three that drive a real MongoDB through Testcontainers.

### What changed

- **`TemplateSyntaxMigrator.migrateStringConcat` crashed on a `+` inside a string literal.** It split
  the concat expression with `split("\\s*\\+\\s*")`, which cuts literals apart: a literal `'+'`
  became two lone quote characters, a lone quote both starts and ends with a quote so it was taken for
  a quoted literal, and stripping its delimiters was `substring(1, 0)` —
  `StringIndexOutOfBoundsException: Range [1, 0) out of bounds for length 1`. A new
  `splitOnConcatOperator` splits only outside quotes, and `isStringLiteral` requires length ≥ 2 and
  matching delimiters. The real trigger on staging was a single `httpcalls` config holding a template
  whose three concatenated literals render as another template expression.
- **`V6QuteMigration.migrateCollection` had no per-document isolation**, so that one malformed template
  aborted the Thymeleaf→Qute conversion for *every* config in the database, logging only "will retry
  on next startup" — where it threw again. Each document now migrates in its own try/catch, failures
  are logged with collection and id, and the migration is **not** marked complete while any document
  failed, so it retries once the data is fixed. `migrateCollection` returns a
  `CollectionResult(migrated, failed)`.
- **`MongoDeploymentStorage`'s unique `(environment, agentId, agentVersion)` index destroyed deployment
  rows on a pre-rename database.** EDDI 5 wrote `botId`/`botVersion`; Mongo indexes the absent
  `agentId` as null, so an unrestricted unique index read all 113 staging rows as duplicates of one
  another, `createIndex` failed with E11000, and the recovery path `removeDuplicateDeploymentRows()`
  kept one row for the whole collection and deleted 112. The index is now partial on
  `agentId`/`agentVersion` existing, and the dedupe pipeline `$match`es only rows that carry the key.
- **The `@Scheduled(every = "10s", delayed = "10s")` `checkDeployments()` sweep ran before the rename
  migration and deleted deployments.** On a first boot against a 5.x database the agent configs are
  still in `bots`; `agents` does not exist until `V6RenameMigration` creates it, so
  `isAgentConfigMissing` returned true for every deployed agent and the sweep called
  `deleteDeploymentInfo` on each. Observed live: the deployment rows of both deployed agents deleted. The
  sweep now returns early while `V6RenameMigration.isPending()`.

### Design decisions

- **The sweep gate asks the migration, it does not track a flag.** The first cut set a
  `volatile boolean startupMigrationsAttempted` at the end of `autoDeployAgents()`. Two problems:
  nothing set it if anything above it threw (parking the sweep, and with it all deployment, forever),
  and it read "migrations attempted" as "collections renamed" — so a rename migration that *failed*
  released the sweep to delete the rows anyway. `V6RenameMigration.isPending()` is the actual
  precondition: `enabled && no completion entry in the migration log`, latched once complete so a
  ten-second schedule does not re-read the log forever, and fail-safe (an unreadable log counts as
  pending). Disabled is deliberately *not* pending — the property defaults to false, so "no completion
  entry" is the permanent state of every installation that never needed the migration, and reading that
  as pending would park the sweep on every normal EDDI 6 database. It also made the fix testable
  without rewriting the ~25 existing `checkDeployments()` tests, which call it directly on a freshly
  constructed object.
- **A conflicting index is dropped and rebuilt.** Mongo does not re-shape an existing index: adding
  `partialFilterExpression` to a key pattern that already carries the non-partial unique index is
  refused, not a no-op. Every installation already running 6.x would otherwise have kept the destructive
  index while logging something that reads like a warning about duplicate rows. On either conflict code
  the index actually sitting on the deployment key is looked up and dropped **by name**; if none does,
  the conflict is with someone else's index and is left alone. E11000 still goes to the
  dedupe-and-retry path, because there the *rows* are wrong and dropping the index would throw the
  constraint away instead of fixing them.
- **The partial filter uses `$exists`, not a null check**, so a row that legitimately carries a null
  `agentVersion` stays inside the uniqueness constraint. Only rows missing the field entirely — i.e.
  pre-rename rows — fall out of the index.
- **Not marking the Qute migration complete on a failure re-scans on every boot.** That is accepted:
  `TEMPLATE_COLLECTIONS` is four config collections plus their `.history` counterparts, the scan is
  cheap, and a migrated document contains no Thymeleaf syntax so nothing is rewritten twice. Shipping a
  half-migrated database silently is the worse trade. A collection that cannot be counted is a
  failure too, with one exception: `NamespaceNotFound` (26). Only some of these names exist on any given
  database and some driver versions answer `estimatedDocumentCount` on a missing namespace with that
  error rather than zero, so counting it would leave the migration permanently incomplete on a
  database with nothing to migrate; any other count failure means a collection nobody has read.
- **An empty part of a concat expression is now skipped rather than rendered as `{}`.** An empty
  operand only arises from a leading, trailing or doubled `+`, i.e. from an expression that was already
  malformed; `{}` is a broken Qute expression where nothing at all is a dropped empty operand.

### Review follow-up (PR #781)

Copilot found a real gap in the first version of the splitter: it left quote mode at the *first*
matching quote character, escaped or not, so a valid OGNL literal such as `'it\'s + here'` ended at
the escaped apostrophe and the `+` after it was read as an operator — cutting the literal in half
again, just for a rarer input. A backslash now escapes the next character while inside a literal.

Stripping the delimiters also reduces `\'`, `\"` and `\\` to the character they stood for, because
the conversion inlines the literal's text verbatim and Thymeleaf renders `'it\'s'` as `it's` — leaving
the backslash in would put it on the screen. The other OGNL escapes (`\t`, `\n`, …) are deliberately
left exactly as they are: a Windows path in a config is the likelier intent than a control character,
and guessing wrong there rewrites config content rather than merely failing to tidy it.

CodeRabbit then found that the `catch` around `estimatedDocumentCount()` was half-right in the other
direction: keeping the missing-collection case out of the failure count also swallowed authorization
errors, timeouts and server errors, so `runIfNeeded()` saw zero failures and recorded completion over
a collection it had never read — the same silent half-migration the per-document guard exists to
prevent. Only `NamespaceNotFound` (26) now counts as "nothing to migrate here"; anything else counts
as a failure and keeps the migration incomplete. A pre-existing test
(`runIfNeeded_collectionsNotExist`) asserted the old behaviour on a false premise — `getCollection`
does not contact the server, so it never fails merely because a collection is absent — and now
asserts the corrected contract under the name `runIfNeeded_collectionAccessFailureBlocksCompletion`.

A final independent review found five more things; all are fixed here.

- **A v5 database with two deployment rows that become one v6 key never finished migrating, and so
  never deployed an agent again.** `ENVIRONMENT_REWRITES` maps both `unrestricted` and `restricted` to
  `production`, and v5's own check-then-act upsert wrote same-environment duplicates. The unique index
  `MongoDeploymentStorage` builds at construction already exists when the migration runs, so the second
  row's write failed E11000 on every boot — the first row already rewritten, the second never could be
  — and with the sweep waiting on the migration, nothing deployed. Before this PR the dedupe deleted
  rows but boot completed; the PR had turned lossy-but-booting into never-deploying. A collision is now
  resolved with the store's own rule: one row per key, the newest `_id` kept, so every node picks the
  same survivor. `migrateEnvironments` also isolates documents: one that cannot be written is logged,
  the rest still go through, and the migration is left incomplete rather than aborted. Three
  Testcontainers tests cover both collision shapes and the no-collision control. The staging rehearsal
  could not have caught this: it held no such pair.
- **A pre-check on `migrateEnvironments` was removed.** An earlier commit on this branch added one to
  skip a clean collection, motivated by the staging measurement: the startup migrations took 24
  minutes, ~20 of them this pass on `conversationmemories` (195 documents averaging 410 KB; a read-only
  `mongodump` of the collection took 14 minutes on the same cluster). It could not help. Two of its
  three conditions were server-side counts, but the third — a legacy URI nested at arbitrary depth —
  has no filter form, and a sampled version was rejected because a miss is permanent once the migration
  records completion; an exhaustive one reads the whole collection, which is the entire cost. Clean
  collection: one read either way; dirty: two counts plus the same pass. It was net zero at best and
  the PR described it as a speedup. Removing the 20 minutes needs the rewrite moved server-side
  (`updateMany` with `$rename`/`$set`); that is follow-up work, not in this PR.
- **The index-conflict handling had the error codes wrong.** The review suggested handling 85 alone,
  and the real-server test showed why that is also wrong: an old non-partial index on the same key under
  the same auto-generated name comes back as `IndexKeySpecsConflict` (86) on current servers. Handling 85
  only passed every mocked test and left the destructive index in place. Now either code triggers a
  lookup of the index on the deployment key, dropped by name; a same-named index on another key is not
  touched.
- **`/q/health/ready` reported ready with nothing deployed.** With the rename migration pending the
  sweep is parked, yet `autoDeployAgents()` still set readiness. It now stays not-ready, with an ERROR
  saying why; the migration only runs at startup, so that lasts until a restart after the cause is fixed.
  The "sweep parked" warning is logged once instead of every ten seconds.
- **This entry contradicted itself** on whether an unreadable collection counts as a failure; corrected
  above to match the code.


### Files

- `src/main/java/ai/labs/eddi/configs/migration/TemplateSyntaxMigrator.java`
- `src/main/java/ai/labs/eddi/configs/migration/V6QuteMigration.java`
- `src/main/java/ai/labs/eddi/configs/migration/V6RenameMigration.java` — new `isPending()`
- `src/main/java/ai/labs/eddi/configs/deployment/mongo/MongoDeploymentStorage.java`
- `src/main/java/ai/labs/eddi/engine/runtime/internal/AgentDeploymentManagement.java`
- `src/test/java/ai/labs/eddi/configs/migration/TemplateSyntaxMigratorTest.java`,
  `V6QuteMigrationTest.java`, `V6RenameMigrationTest.java` — the concat fixture fails with the
  original `StringIndexOutOfBoundsException` against the pre-fix splitter
- `src/test/java/ai/labs/eddi/configs/deployment/mongo/MongoDeploymentStorageTest.java` (mocked) and
  `src/test/java/ai/labs/eddi/datastore/mongo/MongoDeploymentStorageTest.java` (Testcontainers —
  pre-rename rows survive construction, a non-partial index is rebuilt as partial, and the dedupe
  spares pre-rename rows on a half-migrated collection)
- `src/test/java/ai/labs/eddi/datastore/mongo/V6RenameMigrationDeploymentsTest.java` (Testcontainers —
  deployment rows that collapse onto one v6 key)
- `src/test/java/ai/labs/eddi/engine/runtime/internal/AgentDeploymentManagementTest.java`,
  `AgentDeploymentManagementBranchTest.java`

### Verification

201 tests green in the selection (`TemplateSyntaxMigratorTest`, `V6QuteMigrationTest`, both
`MongoDeploymentStorageTest`s, `V6RenameMigrationTest`, `V6RenameMigrationBranchTest`,
`AgentDeploymentManagementTest`, `AgentDeploymentManagementBranchTest`) plus the repo-wide guards
(`ImportStyleTest`, `DocumentationLinksTest`, `StrictBoundaryShippedConfigsTest`,
`RuleSetStoreShippedRulesetsTest`, `BuildQualityGatesTest`, `ChangelogRotationTest`). Mutation-checked:
reverting the literal-aware split, the partial filter, the dedupe `$match`, the index-conflict rebuild,
the sweep gate or the "do not mark complete when a document failed" behaviour each makes a test fail.

### Review round: five findings, all in code that runs against a production database (2026-09-21)

- **A pass that could not read a collection counted as a pass with nothing to do.**
  `migrateAgentFields`, `migrateCollection`, `migrateDescriptors` and `migrateEnvironments` each
  opened with a bare `catch (Exception e) { return 0; }`, so an authorization error, a timeout or a
  step-down read exactly like "this collection does not exist": `runIfNeeded()` saw a clean total and
  wrote the completion log over a collection nobody had read, and because the migration runs once,
  those documents stayed in their v5 shape for good. Only `MongoCommandException` code 26
  (`NamespaceNotFound`) is a skip now — the rule `V6QuteMigration` already applied — and everything
  else is counted, so the migration runs again on the next start. The four passes now return a shared
  `(migrated, failed)` result and `runIfNeeded()` aggregates `failed` across all of them, not only
  across the environment pass.
- **`saveDocument` swallowed every write failure and the callers counted the document anyway.** It
  returned `void` after catching everything, and it also declined silently to write an `_id` shape it
  cannot address. Either way the caller incremented `migrated`. It now reports whether the write
  happened and the callers count accordingly.
- **"Newest `_id` wins" was not sound across processes.** An ObjectId is
  `[4 bytes timestamp][5 bytes process-unique][3 bytes counter]` and `compareTo` compares them in
  that order, so for two ids created in the same second by different instances the larger id is as
  likely to be the older row — and this branch deletes the loser, which is a deployment status gone
  with nothing to recover it from. `strictlyNewer` now answers only where insertion order is
  established: a different second, or the same second and the same process, where the counter means
  what it looks like it means. Same second, different process is "cannot tell", and the collision is
  left unresolved — logged with both ids, counted as a failure, migration incomplete. A migration
  that stops and names two rows to reconcile is recoverable; a deleted row is not.
- **Index recovery could drop the wrong index, or the only good one.** `indexOnDeploymentKey()`
  returned the *first* index whose key pattern matched, and MongoDB allows two indexes on one key
  pattern when their names and options differ — the shape an installation lands in if the partial
  index was ever built beside the old unrestricted one. Every index on the deployment key is dropped
  now, then one partial index is rebuilt. And before anything is dropped, the index holding the name
  this one would be given is checked: if that name belongs to an index on a *different* key, nothing
  is dropped and the conflict is reported, because dropping ours would remove a working constraint
  and still not get past the name. The generated name is derived from `DEPLOYMENT_KEY_PATTERN`, not
  written out, so it cannot drift from the key the index is built on. Neither error code decides
  anything: splitting on 85 versus 86 was tried in an earlier round and broke against a real server,
  which reports the same key under the same name as 86.
- **A transient migration-log read failure left the instance not-ready for ever.**
  `setAgentsReadiness(true)` had exactly one call site, inside the startup path that runs once a
  second after boot. `isPending()` is deliberately fail-safe — a read that fails answers "pending",
  because answering "not pending" would let the sweep read every agent config as deleted and retire
  its deployment row — so one failed read in that second left readiness false for the life of the
  process while `checkDeployments()` deployed the agents ten seconds later and served them correctly.
  Readiness is now deferred rather than abandoned, and the first scheduled sweep that completes with
  the migration no longer pending grants it, exactly once. `E.D.D.I is ready!` moved to that same
  point: it used to be logged from a second `isPending()` call after the lambda, so a read that
  failed in one and succeeded in the other logged "ready" against an instance whose readiness flag
  was false.
- **A `}` inside a string literal defeated the Thymeleaf-to-Qute scan entirely.** `CONCAT_PATTERN`
  used `[^}]*?`, so `[[${a + '}' + b}]]` matched nowhere: the quote-aware splitter this PR added was
  never reached, the output patterns failed on it for the same reason, and the template was left in
  Thymeleaf syntax by a migration that runs once and then records itself complete. The expression is
  now located by a scan that tracks quote state and backslash escapes — the same state machine as
  the splitter, at the delimiter instead of at the operator. An unterminated expression is left
  exactly as it is rather than rewritten on a guess.

**Tests.** `V6RenameMigrationTest` gained `CollectionAccessTests` (NamespaceNotFound completes; an
authorization failure and a timeout each keep the migration incomplete) and `StrictlyNewerTests`,
whose third case asserts both that two processes inside one second are unordered *and* that full
`ObjectId` ordering calls the lower-counter row the newer one — the defect, stated.
`MongoDeploymentStorageTest` (mocked) gained "every index on the deployment key is dropped, not the
first one listed" and "nothing is dropped when a different key holds the name ours would be given";
the Testcontainers test against a real MongoDB stays as it is, and is what proved an earlier
85-only fix wrong. `TemplateSyntaxMigratorTest` gained exact-output cases for a quoted `}`, a quoted
`{`, an escaped quote before a brace, an unterminated expression and two expressions on one line.
`AgentDeploymentManagementBranchTest` gained four readiness cases: granted by the sweep after a
transient pending answer, granted once however many sweeps follow, never granted while the migration
stays pending, and granted once on a normal boot.

## ⏱️ feat(rag): REST and scheduling for ingestion sources (2026-09-17)

**Repo:** EDDI (`feat/rag-ingestion-rest`)

### What this adds

The operable surface for the sources landed in the previous entry: four endpoints and a cron.

| Method | Path | Access |
| ------ | ---- | ------ |
| `POST` | `…/sources/{sourceId}/run` | EDIT |
| `POST` | `…/sources/{sourceId}/preview` | EDIT |
| `GET` | `…/sources/{sourceId}/runs` | VIEW |
| `DELETE` | `…/sources/{sourceId}/documents` | EDIT |

**Running needs EDIT, not VIEW.** A published knowledge base grants VIEW to everyone by design, and a
run rewrites what every agent using it retrieves — so gating a run on read access would let any editor
point a source at any published knowledge base and poison it, on a schedule. The draft this replaces
checked nothing at all. Preview is gated the same way: it writes nothing but still sends a visible
amount of traffic to a third party's site.

Runs are async on a virtual thread (a crawl takes minutes; an HTTP request cannot wait for it), with a
409 rather than a second crawl when one is already in flight.

### Scheduling

A source with a `cron` gets a schedule carrying `ragIngestion` metadata, which `ScheduleFireExecutor`
recognises as a fourth fast-path beside HITL timeouts, Dream consolidation and team cadences — the same
shape of work, and the same reason: a maintenance job, not a conversation turn, that wants the cluster
claim, lease, retry and fire log.

Schedules are named `rag-ingestion:{ragConfigId}:{sourceId}`, so syncing is delete-by-name then create:
no scan and no orphans. The draft searched `readAllSchedules(1000)`; past a thousand schedules — HITL
timeouts and Dream cycles each create one — it silently failed to find the row, then created a duplicate
on update and left a schedule still crawling a deleted source on delete. A sync failure is logged as an
ERROR naming the consequence rather than swallowed behind a 201.

Sources get a generated stable id on write. Addressing them by name would mean renaming a source
orphaned everything it had ingested.

### Tests

15 for the service (schedule upsert, no-cron and disabled handling, surfaced store failures, scheduled
fire against a deleted source or knowledge base, concurrent-run refusal, scoped purge) and 12 for the
REST layer (access level per endpoint, refusals never reaching the service, 404s, 409, limit clamping).
Four existing RAG REST tests were updated for the new constructor parameter.

## 🔒 fix(apicalls): configuration references work in templated HTTP-call fields; data-supplied ones are refused (2026-09-17)

**Repo:** EDDI (`fix/vault-references-in-templates`)

### Why

HTTP-call values are rendered by Qute before `${vars:…}`, `${vault:…}`, `${eddivault:…}`, `${caller:…}` and
`${connection:…}` are resolved. Only `caller` had a pass-through namespace resolver, so every other reference
in a URL, header, body or query parameter failed the call with "No namespace resolver found" — including
`${connection:name}` headers, the documented way to use connections, and the vault references
`docs/secrets-vault.md` lists as supported. Vault was kept failing on purpose, because a resolved body was
stored unredacted.

The resolvers run on the *rendered* string, so a reference that conversation data put there was resolved
too: a template substituting user input, a model reply or an API response sent the plaintext of any vault
secret named in that data (grants are checked at deploy, not at read). That was independent of the
namespace failure.

### What changed

- `ReferencePassThroughNamespaceResolver` (new base; `CallerNamespaceResolver` now extends it) and
  `ConfigReferenceNamespaceResolvers` with pass-through beans for `vault`, `eddivault`, `connection` and `vars`.
- `ConfigReferenceGuard` (new): after rendering and before resolution, every credential reference
  (`vault`, `eddivault`, `connection`, `caller`) must appear in that field's configuration template, or be the
  value of a property the template names that is exactly this agent's auto-vault reference
  (`${vault:<agentId>.<name>}`, under this conversation's own tenant). Otherwise the call is refused, naming
  the field. `${vars:…}` is not itself a credential reference, but a variable may hold one — see the second
  review fix below for how that is guarded.
- `ApiCallExecutor` records the vault plaintexts it substitutes (`BuiltRequest.resolvedSecrets`) and redacts
  them by value from the memory request record (`RequestRedactor.redactResolvedSecrets`), the approval
  preview (`ResolvedRequest.withoutResolvedSecrets`, fingerprint unchanged) and the request log lines.
- `docs/secrets-vault.md`: where references are resolved, and the rule above.

### Verification

- `ConfigReferenceNamespaceResolversTest`, `ConfigReferenceGuardTest` (10) and
  `ApiCallExecutorConfigReferenceTest` (15, incl. the nested `VariableIndirection` group) — the executor with
  the real Qute engine, not a templating stub — plus `RequestRedactorTest`'s new `SafeRequestLog` group (5),
  `ApiCallExecutorTest`, `ApiCallExecutorSecretContextTest`, `ApiCallExecutorBatchPrincipalPropagationTest`,
  `ResolvedRequestTest`, `CallerNamespaceResolverTest`. The apicalls, templating, secrets, connections,
  variables and properties suites are green (6353 tests), as are the repo-wide guards.
- Mutation-checked, one fix at a time: redacting after formatting instead of before, dropping the
  post-variable-expansion guard pass, accepting any tenant prefix on an auto-vault property, removing the
  fail-closed throw, and resolving a second time to build the value each fail a test.
- End to end on the packaged build (MongoDB, vault on, recording mock API): `${vars:}` in the target URL and
  `${vault:}` in a header and the body reach the API; an injected reference to another agent's secret is
  refused and never sent; neither secret appears in the response, the conversation read, the stored
  conversation or the server log (17/17). Run on the pre-merge branch; **not** re-run after the merge and the
  review fixes below, which add a fail-closed path a packaged run would exercise differently.

### Merged `main` (2026-09-20)

`main` had moved 28 commits on. Two conflicts:

- **`ApiCallExecutor.executeFireAndForgetCalls`** — `main` had moved batch request *building* onto the turn's
  own thread (each iteration getting its own copy of the template data) so an unsatisfiable reference fails
  the turn instead of a worker nobody reads; this branch had changed the same loop to carry `BuiltRequest`
  so the log line could be redacted. Resolved by keeping `main`'s structure and collecting `BuiltRequest`
  rather than `IRequest`. `main`'s `rejectExpiredSecretContext` calls in `buildRequest` auto-merged beside
  the guard calls and were kept on all four fields.
- **`docs/changelog.md`** — both sides added an entry at the top; both kept.

### Review fixes

Five findings from the PR review, all in the security path:

- **The log line is redacted before it is formatted.** `RequestWrapper.toString()` folds newlines and cuts the
  body at 150 characters, so redacting its *output* by exact value missed a secret carrying a newline (a PEM
  key) or straddling the cut. New `RequestRedactor.safeRequestLog(IRequest, Set)` reads the raw components
  from `IRequest.toMap()`, redacts those, and shortens afterwards; all three call sites use it.
- **`${vars:…}` can no longer smuggle a credential reference in.** A global variable is allowed to hold
  `${vault:…}` or `${connection:…}`, so a data-supplied `${vars:x}` passed the guard (not yet a credential
  reference) and became one after expansion. `ApiCallExecutor.resolveGuardedVariables` now guards again after
  expansion, against the configured template expanded the same way — a configured variable's reference is
  allowed, a data-supplied one is refused. Covered for URL, body, header and query parameter; in the path the
  reference never forms at all, because `pathSafeView` percent-encodes what data puts there (now asserted).
- **The auto-vault property exception no longer crosses tenants.** It accepted any `<tenant>/` prefix, so a
  user-supplied `${vault:victim/thisAgent.apiKey}` read another tenant's secret of that key name. The
  reference is now compared character-for-character against what `autoVaultSecret` would have written for that
  property under *this* conversation's tenant. Also removes the per-call `Pattern.compile`.
- **An unresolvable reference fails closed.** `SecretResolver.resolveValue` leaves a reference it cannot
  resolve in place, and the call went out carrying the literal `${vault:name}` as its credential. It now
  refuses, naming the field and the reference — the rule `SecretResolver.requireResolved` already applies to
  LLM client parameters.
- **One resolution per reference.** The bookkeeping pass and the substitution pass resolved separately, so a
  rotation between them put the new plaintext in the request while only the old one was in the redaction set —
  the value actually sent was the one that survived into memory, previews and logs. `resolveSecrets` now
  builds the string from the same resolutions it records.

Not fixed here, and why: the auto-vault exception was still a *shape* test rather than a provenance test.
`Property` carried no "auto-vaulted" marker — a `scope: "secret"` instruction stores its vault reference with
`scope: conversation`, exactly like any other property — so a real provenance check needed a marker on the
persisted property model. What was left was bounded: the reference is derived from this agent and the
property name the template reads, so data could not choose which secret is read, and it goes only to the
endpoint the configuration names. **Closed on this branch by the 2026-09-20 entry above**, which adds that
marker and makes the guard require it.

## 🗃️ feat(ingestion): document state and run history for RAG sources (2026-09-17)

**Repo:** EDDI (`feat/ingestion-state-store`)

### Why

Ingesting a source is a *reconciliation*, not an append: each run compares what the source offers now
against what the knowledge base holds, and decides per document whether to skip, re-embed, or conclude
it is gone. That needs durable state, and the shape of it is where the salvaged PR #529 went wrong —
quietly, because a corrupted knowledge base has no stack trace. `IIngestionStateStore` is that state,
designed so the draft's four failure modes are not expressible.

### The two rules the API enforces

- **A document is recorded only after its vectors are stored.** The draft's `shouldIngest(source, doc,
  content)` upserted the new hash while *deciding* whether to ingest, and embedding happened afterwards
  inside a `catch` that only logged. One 429 from the embedding provider therefore marked a page done
  forever: the hash matched on every later run, so it reported "unchanged" and was never embedded. Here
  `lookup` and `recordIngested` are separate calls and the Javadoc says which side of the embedding call
  each belongs on.
- **A document missing from one run is not a deleted document.** The draft marked everything not seen in
  the current run as stale, unconditionally — so a site outage, a network blip, or simply hitting
  `maxPages` flagged the remainder of the corpus. `tombstoneMissing` counts *consecutive* misses and only
  tombstones at a threshold, and callers are told not to call it for a failed run at all.

### What it stores

Per (source, document): content hash, ETag and Last-Modified for conditional fetching, first/last
ingested timestamps, last run id, consecutive miss count, tombstone flag. Per run: status, timings, the
seen/ingested/unchanged/failed/tombstoned counters, segments stored, cost in dollars, and the error —
so a failure is visible in the Manager rather than only in a log line.

`startRun` returns empty when a run is already in flight, enforced by a **partial unique index** on
`(source_id) WHERE status = 'RUNNING'` in both backends. This is what stops an operator clicking "run
now" five times from starting five concurrent crawls into one knowledge base, and because the database
enforces it, it holds across instances. `reapStaleRuns` releases a source whose run died with its
process.

`ContentHashes.sha256` is a static utility rather than an interface method: the draft had each backend
carry its own copy, two chances to drift, and a drift silently re-embeds an entire knowledge base.

### Tests

**One shared contract, run against both backends** — `IngestionStateStoreContract` is a JUnit interface
with 23 cases, implemented by `MongoIngestionStateStoreTest` and `PostgresIngestionStateStoreTest`
(Testcontainers). The draft's two stores had drifted apart — one overwrote the first-ingest timestamp on
every call while the other preserved it — and nothing failed, because each was only tested against
itself. A knowledge base that behaves differently depending on the operator's database choice is a
support problem with no error message.

Plus 7 cases for `ContentHashes`, including a pinned published SHA-256 vector: if the hash ever changes,
every deployed knowledge base re-embeds itself on the next run.

53 tests, all green on both backends. Mutation-checked: reverting `setOnInsert` to `set` and ignoring the
miss threshold fails three of them.

### Next

The crawler, then the source config plus the pipeline that ties fetch → convert → this store → embed,
with vector removal driven by the tombstone list.

## 🔎 fix(operator): let the Platform Operator inspect knowledge bases (2026-09-17)

**Repo:** EDDI (`fix/operator-rag-reads`)

### Why

An admin asked the Operator to check a RAG knowledge base. It answered that the tool `readRag`
"was not found". That tool never existed: `tool-scopes.ts` named no `ragstore` path, so no RAG
tool was ever generated — the model guessed a name by analogy with `readLlm` and the tool loop
answered `Error: Tool 'readRag' not found` (`ToolLoopRunner:654`).

The guess was the symptom of an asymmetry. The Operator *can* read `docs/rag.md` (the docs
endpoints are granted in both scopes), so it knew knowledge bases exist — while holding no tool
for one and no sentence saying so. Knowing a feature exists with neither a tool nor a word about
it is what produces an improvised tool call, the same failure `BODY_AUTHORING_NO_AGENT` already
prevents for agents.

### What changed

- **`tool-scopes.ts`** — three reads added: `GET /ragstore/rags/descriptors`, `GET /ragstore/rags/{id}`,
  `GET /ragstore/rags/{id}/ingestion/{ingestionId}/status`. Descriptors are included because a KB is
  asked about by NAME; without the listing the by-id read is unreachable. New predicates
  `grantsKnowledgeBaseReads` / `grantsKnowledgeBaseAuthoring`.
- **`system-prompt.ts`** — a knowledge-base section, conditional on those reads, plus a derived
  "you cannot create, edit or ingest" sentence; `rag` added to the step-type list and the docs map.
- **`McpAdminTools`** — `read_resource` gained a `"rag"` case, so an MCP client can read a KB config too.

### Decisions

- **Not folded into `WORKFLOW_EXTENSION_STORES`.** That constant doubles as
  `WRITABLE_EXTENSION_STORES`; adding `ragstore/rags` there would grant PUT/POST as a side effect
  of wanting a read.
- **Reads only; `ingest` stays excluded**, as `planning/operator-write-scope-plan.md` §5 requires.
  The ingestion *status* read is what answers "did the documents land?" without a write.
- **No new exposure class.** RAG embedding and vector-store credentials are `${vault:...}`
  references resolved at runtime, exactly like `llmstore`, which was already granted.
- **The boundary sentence is derived, not asserted**, so allow-listing a RAG write later cannot
  leave the prompt claiming the opposite.

### Note

Existing operators keep their old tool set — tools are provisioned at activation, so an operator
must be re-activated to gain these.


### Review round 1 (Copilot)

Four inline findings plus one *suppressed* comment (no thread — only visible in the review body), all
acted on:

- **`ai.labs.rag` → `unknown` in `STEP_TYPE_TO_RESOURCE_TYPE`** — a real dead end: an MCP client
  following `list_agent_resources` into `read_resource` would have passed `unknown` and never
  reached the new case. Mapping added, and the test that pinned `unknown` updated.
- **The cheatsheet leaked RAG unconditionally** — `rag (knowledge base)` in the step-type list and
  the `rag` docs-map entry sat in `BODY_CHEATSHEET`, which every prompt carries. A prompt without the
  RAG endpoints therefore still said knowledge bases exist. Both moved into the conditional section,
  which now has a *no-reads* variant that names the `rag` step and states the boundary rather than
  going silent — silence is what produced the invented call.
- **The ingestion claim did not track its own endpoint** — `grantsKnowledgeBaseReads` gates a section
  that promised an ingestion check while requiring only the two config reads. Split into
  `grantsIngestionStatusReads` rather than requiring all three: the config reads are a complete
  capability alone, so demanding the third would drop the whole section on a deployment missing one
  endpoint.
- **`grantsKnowledgeBaseAuthoring` missed the duplicate verb** (a *suppressed* Copilot comment, which
  carries no thread — found by grepping the review body). `POST /ragstore/rags/{id}` is `duplicateRag`,
  and a copy of a knowledge base is a new knowledge base, so granting it would have left the prompt
  telling an operator that CAN create one that it cannot. Added, with a test covering all four
  authoring routes.
- **Plaintext credentials in a `RagConfiguration`** — the exposure is real but not new: `GET
  /llmstore/llms/{id}` returns a plaintext key verbatim too, and `RestLlmStore` says so in its own
  javadoc. RAG was, however, the one credential-carrying store with **no write-time warning**, so it
  now has the same one `RestLlmStore` and `RestChannelIntegrationStore` already had (warn, never
  reject — a rejection breaks vault-less instances). Redacting config reads platform-wide is a
  separate change; doing it for RAG alone would imply the other stores are safe.

**Files:** `ui/manager/src/lib/operator/tool-scopes.ts`, `.../system-prompt.ts`, their tests,
`src/main/java/ai/labs/eddi/engine/mcp/McpAdminTools.java`,
`src/main/java/ai/labs/eddi/configs/rag/rest/RestRagStore.java`,
`src/test/java/ai/labs/eddi/engine/mcp/{McpAdminToolsSwitchCoverageTest,McpAdminToolsTest}.java`,
`src/test/java/ai/labs/eddi/configs/rag/rest/RestRagStoreWriteValidationTest.java`, `docs/mcp-server.md`

---

## 🔒 fix(security): close the CWE-117 gap in the half of a log line no call site can reach (2026-09-20)

**Repo:** EDDI (`fix/log-injection-record-boundary-handler`)

`LogSanitizer.sanitize(...)` at a call site only ever covered the log **message**.
`quarkus.log.console.format` ends in `%s%e`, and `%e` renders a stack trace whose FIRST
line is the throwable's own `toString()` — `ClassName: message`. So an attacker-controlled
CR/LF inside an **exception message** reached the console verbatim and forged a record that
reads as a genuine, server-authored line, no matter how carefully the message half was
sanitized. 412 log calls in `src/main/java` pass a throwable (244 as a trailing argument,
168 as JBoss `*f(e, …)`), and none of them could fix this themselves.

Dropping the throwable at those call sites was never the trade: `RestAgentAdministration`'s
deploy-failed WARN tells the client only *"Deployment failed. Check server logs for
details."*, so the stack trace is the sole diagnostic a failed deployment leaves.

### What changed

- **`LogSanitizer.escapeRecordBoundaries(String)`** — a second, record-level rule beside the
  existing call-site `sanitize(...)`. It escapes rather than destroys: CR → `\r`, LF → `\n`,
  U+2028/U+2029 and every other ISO control character → `\uXXXX`, TAB kept verbatim. Returns
  the same instance when nothing needs escaping, and `null` for `null` (unlike `sanitize`,
  which renders `null` as the string `"null"` — doing that to a throwable's message would turn
  a printed `java.io.IOException` into `java.io.IOException: null`).
- **`LogRecordRedactor`** now applies both rules in one pass: `SecretRedactionFilter.redact`
  then `escapeRecordBoundaries`, to the record's formatted message and to every message in its
  throwable graph (causes and suppressed included). `RedactedThrowable.of` takes the message
  rewrite as a `UnaryOperator<String>` so one walk of the graph applies both rules instead of
  nesting one stand-in inside another.
- **`BoundedLogStore.capture`**'s own fallback path (used when the upstream pass threw) applies
  the same `LogRecordRedactor.rewrite`, so the ring buffer, the DB and the SSE live tail agree
  with the console.
- **Two log calls that this change would otherwise have made uglier**: the `\n` in
  `ConversationStepRunner`'s "Conversation not ready" ERROR became `": "` (the throwable is
  passed too, so `%e` prints the trace anyway), and `ApiCallExecutor`'s trailing `\n` on the
  execution-time INFO is gone (the pattern already ends in `%n`). They were the only two
  deliberately multi-line log messages in `src/main/java`.

### Design decision — escape the throwable's MESSAGE, not the rendered trace

The obvious reading of "sanitize the rendered `%s%e`" is to scan the finished stack trace and
escape the line breaks that do not begin a genuine continuation line (`\tat `, `Caused by:`,
`\t... N more`). **Rejected**: those three prefixes are also three strings an attacker can put
in an exception message, so such a scan has to decide which `Caused by:` is the JVM's and which
is the payload, and it has no way to know.

There is no need to guess. In a rendered trace the only text an attacker reaches is the
`toString()` of each throwable in the graph; every other line is generated by the JDK from the
`StackTraceElement` array. So EDDI escapes the messages *before* the trace is rendered, by
substituting a copy of the throwable, and lets the JDK produce the structure from clean input.
Nothing is parsed, nothing is guessed, and `LogRecordBoundaryForgeryTest` asserts the frames,
the `Caused by:` and the `... N more` elision come out identical to what the original threw.

Two further choices worth stating: **TAB is kept** (it cannot end a record, and it is what
indents `\tat …`), and **a backslash is not doubled** — the escaping is therefore not injective,
which is a cosmetic ambiguity rather than a forgery, and the alternative doubles every backslash
in the Windows paths and regexes exception messages are full of.

It is also a rewrite of the record rather than a new console formatter, matching the reasoning
already recorded in `LogRecordRedactor`: one definition of "what goes out" for every destination.
The filter is wired to the console handler alone via
`quarkus.log.console.filter=eddi-log-capture`; a file or syslog handler would need the same
filter, and the test below fails if that property or the `%s%e%n` format moves out from under
the claim.

### Tests

New `LogRecordBoundaryForgeryTest` (10 tests) asserts on **rendered** output — a real
`PatternFormatter` built from the pattern read out of `src/main/resources/application.properties`
— because `LogCaptureSupport.captureLogsOf` reads `getMessage()`/`getParameters()` but not
`getThrown()` and so cannot see this defect at all. Its shared invariant: after the first, every
line of a rendered record must be a continuation the JDK generated. Covers the exception message,
a cause, a suppressed exception, U+2028, the message half, a format parameter, plus "a clean
record renders byte-for-byte as before and keeps its throwable" and the config guard.
`LogSanitizerTest` gains 8 cases for the new method.

**Mutation-checked.** Removing the escaping entirely fails 7 of 10 (the 3 survivors are the
must-not-change tests). Escaping the message but not the throwable fails exactly the 5
throwable-half tests — so none of them pass on the strength of the message fix.

### And the message-level alerts, folded in

The handler above stops any of these forging a record at *runtime*, but CodeQL's
`java/log-injection` is a dataflow rule and keeps flagging the call site regardless — and if
the filter is ever detached from a handler, the call site is what is left. So the same branch
also applies the ordinary one-line `LogSanitizer.sanitize(...)` to **38 sinks across the eight
files** the alerts name:

| File | Sinks | The tainted arguments |
|---|---|---|
| `GroupHitlCoordinator` | 16 | `gc.getId()`, `gc.getGroupId()`, `groupConversationId`, `entry.getKey()`, `e.getMessage()` |
| `GroupConversationService` | 11 | `gc.getId()`, `gc.getGroupId()`, `phase.name()`, `outcome.reason()` |
| `MemberTurnExecutor` | 3 | `member.agentId()`, `gc.getId()`, `gc.getGroupId()`, `subGroupId` |
| `ConversationHitlService` | 3 | `conversationId` |
| `PhaseExecutionEngine` | 2 | `gc.getId()`, `phase.name()`, `decision.outcome()` |
| `AuditLedgerService` | 1 | `entry.agentId()`, `e.getMessage()` |
| `AgentGroupStore` | 1 | `groupConfiguration.getName()`, the phase name |
| `SlackGroupDiscussionListener` | 1 | `groupConversationId`, `e.getMessage()` |

Only String-typed arguments are wrapped; the enums, `Instant`s and counters in the same calls
are left alone. `MemberTurnExecutor` and `SlackGroupDiscussionListener` gained the import; each
of the other six already had it, and each call follows the style its own file already used
(qualified `LogSanitizer.sanitize` in six, the static import in `ConversationHitlService` and
`AuditLedgerService`).

The alert list was resolved through `gh api`, not read off `main` at HEAD: a CodeQL alert's
line number is relative to `most_recent_instance.commit_sha`. Two of the 41 reported alerts
turned out to be stale against an older sha — one line had already been sanitized, the other no
longer exists — which is how 41 became 38. The eight files carry a further ~70 log arguments of
the same shape that CodeQL has *not* flagged, overwhelmingly `e.getMessage()`; those are left
alone, because sanitizing them is a codebase-wide policy question and not this PR's.

**Tests.** `SanitizedLogSinksTest` pins all 38 at the source: each is keyed by a fragment of its
own message rather than a line number, and every flagged argument must occur only inside a
`sanitize(...)`. Dropping one fails the build with the file, the message and the expression
named. `GroupHitlCoordinatorLogInjectionTest` covers the two sinks reachable through a public
method with one mock — the forged-id and the forged-exception-message halves — in the
`LogCaptureSupport` idiom the earlier regression tests established. Both mutation-checked.

A source guard rather than 38 behavioural tests is a deliberate call and is argued in the test's
own Javadoc: the rest sit inside a phase loop or a state-race `catch` that takes a whole group
discussion to reach, and a test that builds one to observe a single WARN grades the harness more
than the fix.

### What's next

- `RestAgentAdministration`'s deploy-failed WARN carries a comment on branch
  `fix/log-injection-agent-deployment-logs` (#799) explaining that the throwable cannot be
  sanitized and that only a log handler can fix it. That branch is not merged, so the comment
  does not exist on `main` and could not be updated here: **whichever of the two lands second
  must update it** to say the handler now exists.
- 110 further `java/log-injection` alerts remain open on `main` in files this PR does not touch —
  `RestScheduleStore`, `RestUserMemoryStore`, `VaultSecretProvider`, the REST stores and others.
  `RestAgentAdministration` and `AgentFactory` among them are PR #799's scope and were left to it.
  None of them can forge a record at runtime now, so they are alert hygiene rather than exposure.

---

---

---

## 🕷️ feat(ingestion): web crawler — streaming, bounded, robots-aware (2026-09-17)

**Repo:** EDDI (`feat/ingestion-web-crawler`)

### Why a rewrite rather than a patch

The crawler salvaged from PR #529 was competently written but wrong in shape: it buffered every page's
full HTML in a `List` and returned it when the crawl finished, identified pages by the URL *requested*
rather than the one reached, read every body with an unbounded `ofString()` **before** checking its
Content-Type, and had no run budget. None of that is patchable without touching every line.

It also had no `robots.txt` at all. EDDI installations crawl sites their operators do not own, on a
schedule — ignoring robots gets the installation blocked and its operator a complaint.

### What replaces it

- **`WebCrawler`** — BFS, streaming to a `CrawlSink` one page at a time, so memory is independent of the
  site's size. Budgets for pages, fetch attempts, bytes per page, total bytes and wall clock, each
  reported as a `StopReason`. Cancellation checked between pages.
- **`CrawlUrls`** — canonicalization. Lowercases scheme and host **but not the path**: the draft
  lowercased the whole URL, so `/Docs/Guide` and `/docs/guide` collapsed into one entry and whichever
  came second was silently never crawled. Also strips fragments, default ports, tracking parameters and
  index filenames, and sorts query parameters, so one page is not ingested three times.
- **`UrlPattern`** — exclude globs matched against the **path**, with every metacharacter escaped and
  compiled once. Two defects fixed: the documented `*.pdf` could never match anything (`*` cannot cross
  the slashes in `https://host/`), and a pattern containing `+` or `(` threw `PatternSyntaxException`
  inside the crawl loop, where a blanket catch logged it as a *fetch* error and dropped the current
  page's links — one bad pattern reduced a crawl to its seed URL.
- **`RobotsPolicy`** — groups, longest-match `Allow`/`Disallow`, `*`/`$`, `Crawl-delay` and `Sitemap`.
  Blank lines deliberately do not end a group: real files are full of them, and orphaning a group's
  rules silently allows everything the site meant to block.
- **`PageFetcher`/`SafeHttpPageFetcher`** — `sendValidated` per request (the crawler follows links
  harvested from third-party pages, which is as user-controlled as a URL gets), with a hard cap on the
  body read and charset taken from the header or sniffed from the document. Assuming UTF-8 turns legacy
  pages into mojibake, and mojibake embeds without complaint.

Identity is the URL after redirects, re-checked against the scope: a 301 to another host satisfied
`sameSiteOnly` on the pre-redirect host and smuggled a foreign page into the knowledge base.
`<link rel="canonical">` is honoured, but only when it stays on the same host.

Sitemaps from robots.txt are crawled without needing a link — the cheapest discovery there is, and the
mitigation for the one cost of conditional requests: a 304 has no body, so an unchanged page's links are
not re-read that run.

### Tests

**113 unit tests, no network, no container, no test server.** The `PageFetcher` seam is there for exactly
this: `FakeSite` serves an in-memory website, so scope decisions, budgets, redirect identity, robots,
conditional requests, charset handling and error accounting all run in the unit gate. The draft's only
coverage was one Testcontainers test the unit run does not execute, which is why none of these defects
were caught.

Three of the five failures on the first run were real bugs the tests found, not test bugs: sitemap URLs
bypassed the scope check, `https://host/` and `https://host` canonicalized differently, and fetching the
canonicalized form invented URLs the site never published (the crawler now fetches the address as
published and uses the canonical form only as identity).

Mutation-checked: reverting the final-URL identity and re-lowercasing the path fails four tests.

### Next

The source configuration and the pipeline that ties crawl → convert → state store → embed, with vector
removal driven by the tombstone list, plus the Manager UI.

## 📄 refactor(ingestion): HTML→Markdown converter, and WebScraperTool stops duplicating it (2026-09-17)

**Repo:** EDDI (`feat/html-to-markdown-converter`)

### Why

Ingesting a web page for retrieval needs more than `Jsoup.text()`. Flat text loses the structure a
chunker needs (heading boundaries, which section a passage came from) and merges neighbouring blocks
into single tokens. `WebScraperTool` was doing exactly that, with its own inline
`"script, style, nav, footer, header, aside"` strip — a second, weaker copy of the same rules.

This lands the converter salvaged from the stale PR #529, with its defects fixed, and makes the
existing tool use it instead of its own extraction.

### Why not a library

Checked the classpath first: jsoup and pdfbox are present; flexmark-html2md, commonmark and the
langchain4j document parsers are not. A general HTML→Markdown library optimises for fidelity to the
source document, while ingestion wants the opposite — aggressive removal of everything a reader skips.
~450 lines with a 60-case suite is cheaper than a new supply-chain dependency for that job.

### Defects fixed from the salvaged draft

Each of these silently degraded what reached the vector store; all 46 of the draft's own tests passed
with them present, which is the point — bad ingestion has no stack trace.

- **Adjacent blocks merged.** `div`/`section`/`article` appended children with no separator, so
  `<div>Hello</div><div>World</div>` embedded as `HelloWorld`.
- **`<header>` stripped globally**, deleting the page title in the `<article><header><h1>` layout most
  documentation themes use. Now only `body > header` (the site banner) is removed.
- **Unescaped `|` in table cells**, which ends the column early and shifts every later value under the
  wrong header — corruption that surfaces only as a wrongly cited number.
- **Code blocks flattened**: `text()` collapses whitespace, so every multi-line sample became one line.
  Uses `wholeText()`.
- **Headings resolved links against `null`**, leaving them relative and useless as citations.
- **`<dl>`, `<details>`, `<figure>` fell through to the default branch** and ran together — collapsed
  `<details>` content is still content and is now ingested with its summary as the label.
- Alt-less images emitted `![](url)`: tokens spent on nothing. Dropped.
- Boilerplate selectors extended with `role=navigation|banner|contentinfo|complementary`, cookie
  banners, buttons, `svg`, `template`, `aria-hidden`.
- `maxLength <= 0` truncated everything; now falls back to the default.
- Dead `inPreBlock` plumbing removed (never set true by any caller).

### WebScraperTool

`extractWebPageText` now returns Markdown from the converter rather than a `text()` dump prefixed with
`Title: `. Same 5000-character cap. This is a visible change to an LLM tool's output, and a deliberate
one: the model gets headings, lists and tables instead of one run-on paragraph. Its `extractMainContent`
helper is gone — it was the duplicate.

### Tests

60 converter tests: all 46 inherited from the draft pass unchanged against the rewrite (useful evidence
the behaviour was preserved where it was right), plus 14 in `HtmlToMarkdownConverterSalvageTest`, one per
defect above. `WebScraperToolExtendedTest` gains a case asserting structure survives.

Note: `WebScraperToolTest` cannot run in this environment — its `setUp` constructs a real
`SafeHttpClient`, and creating an `HttpClient` here fails with "Unable to establish loopback
connection". Pre-existing and environmental; CI covers it.

## ♿ fix(ui): closing a dialog hands focus back to what opened it (2026-09-19)

**Repo:** EDDI (`fix/dialog-return-focus`)

`AccessibleDialog` promises "return focus to trigger element on close". It did not keep that promise
in either of the two ways the Manager closes a dialog, so keyboard and screen-reader users were left on
`<body>` and had to find their place from the top of the page again.

### What was wrong

- **With an `autoFocus` field inside** (`CreateAgentDialog`'s Name, the dictionary picker's search),
  "what had focus" was recorded in a `useEffect`. React applies `autoFocus` during commit, before any
  effect runs, so the recorded element was the dialog's own field. On close it had unmounted, and
  focusing it did nothing.
- **When closed by unmounting.** `ShareDialog` (on the Agents, Workflows and resource list pages) and
  the Triggers dialog are rendered as `{target && <X open … />}` and close by unmounting. Focus was
  only restored on an `open === false` render, which an unmount never produces.

### What changed

- `ui/manager/src/components/ui/accessible-dialog.tsx`: the trigger is recorded while rendering the
  opening render, before React commits the dialog. It is restored in the effect's cleanup, which runs
  on close and on unmount alike, but only if focus was actually lost with the dialog (it sits on
  `<body>`). That guard does two things: StrictMode runs the cleanup once on mount with the dialog
  still up, where an unconditional restore pulled focus out of the open dialog, and focus the user
  deliberately moved elsewhere is not taken back.
- `ui/manager/src/components/ui/__tests__/accessible-dialog-focus-return.test.tsx` (new): autoFocus
  close, unmount close, StrictMode mount, and focus moved elsewhere. Against `main` the first two fail;
  with the `<body>` guard removed the last two fail.

### Note

This rewrites the same effect as #788 (initial focus no longer steals from a focused field), which
landed first. `main` is merged in here and the conflict resolved to keep both: #788's guarded,
cancelled frame, and this branch's cleanup restore — the cleanup now cancels the frame *and* returns
focus.

---

## 🧪 fix(ui): a dialog no longer takes focus from a field the user is typing in (2026-09-18)

**Repo:** EDDI (`fix/share-dialog-flaky-test`)

`UI Manager Checks` failed intermittently (run 35295321603) in two unrelated-looking tests that
pass locally: `share-dialog` › "does not let two quick Enters skip the ownership confirmation"
(`share-owner-warning` never appeared) and `create-agent-dialog` › "allows typing in description
field" (the field was empty after `user.type`). They had one cause, and it was in the component, not
the tests.

### Root cause

`AccessibleDialog` moved initial focus to its first focusable element (the header's Close button)
inside a `requestAnimationFrame` scheduled on open. On a loaded runner that frame fired *after* the
test had clicked into a field, and user-event sends keystrokes to `document.activeElement`: "bob"
went to the Close button, the share subject stayed empty, Enter failed validation, and no warning was
ever rendered. The same frame overrode every `autoFocus` inside the dialog in the real UI —
`CreateAgentDialog` autofocuses its Name field, and focus ended on the X a frame later.

Reproduced by stubbing `requestAnimationFrame` to a 30–150 ms timeout: the original share-dialog tests
then fail with exactly the CI error, and the create-agent tests with exactly the empty value.

### What changed

- `ui/manager/src/components/ui/accessible-dialog.tsx`: the frame leaves focus alone when it is
  already inside the dialog, and is cancelled on cleanup. The trap, Escape and return-focus behaviour
  are unchanged.
- `ui/manager/src/components/ui/__tests__/accessible-dialog.test.tsx` (new): holds the frame and
  releases it by hand, so "focus reached a field first" is deterministic. Covers the empty-dialog
  default (Close gets focus), a field focused before the frame, and an `autoFocus` field.
  Mutation-checked: removing the guard fails the latter two.
- `ui/manager/src/components/workspaces/__tests__/share-dialog.test.tsx`: the warning assertions made
  straight after a user event now wait (`findByTestId` for it appearing, `waitFor` for it
  disappearing, the latter safe because each test has just seen it present). This is hygiene, **not**
  the fix — with the delayed frame and the old component these still fail, just after the wait. The
  behavioural guards (`shared` not called, `sentSubject` null) are unchanged.

### Verification

With the fix, the 80 ms and 150 ms delayed-frame copies of both the old and the new share-dialog tests
and of `create-agent-dialog` pass (246/246); without it, they fail as CI did. Full Manager suite with
coverage green locally.

### Not done

`previousFocusRef` is captured in the same effect, after an `autoFocus` child has already taken focus,
so on close focus "returns" to that (now unmounted) field instead of the trigger. Pre-existing and
separate; left alone here.

---

## 🔐 fix(a2a): make the A2A endpoints' anonymity real, and decide which of them deserve it (2026-09-17)

**Repo:** EDDI (`fix/a2a-anonymous-discovery-permissions`)

`RestA2AEndpoint` annotated five endpoints `@PermitAll`, intending them to be reachable by peer
agents that hold no EDDI credential. None of them were named in a
`quarkus.http.auth.permission.*` entry. **Quarkus evaluates those path policies before declarative
RBAC**, so the `/*` catch-all (`policy=authenticated`) claimed all five: on any instance with
`quarkus.oidc.tenant-enabled=true`, Agent Card discovery answered **401** to exactly the callers it
exists for — a bare 401, since `quarkus.oidc.application-type=service` sends no login redirect. The
annotation and the deployment had disagreed for as long as the endpoints existed.

Nothing caught it because `A2aEndpointIT` runs against a `BaseStandaloneIT` instance with
authorization off, where `DisabledAuthController` switches the path policies off wholesale and a
permitted path and a protected one answer identically.

### The decision, endpoint by endpoint

Not all five were meant to be anonymous, so this is not "add a permit entry for the five".

| Endpoint | Posture | Why |
|---|---|---|
| `GET /.well-known/agent.json` | **permit** | The A2A discovery convention. A peer reads the card *before* it holds any credential |
| `GET /a2a/agents/{agentId}/agent.json` | **permit** | The card EDDI's own client fetches — `A2AToolProviderManager.fetchAgentCard` sends `apiKey` only if one is configured. Needs the agent id, so it discloses one agent, not the roster |
| `GET /a2a/agents` | **authenticated** — `@PermitAll` removed | The whole roster: every A2A agent's name, description, skills and URL. Strictly more than the skill-name list that sits behind `eddi.a2a.capabilities.public`, and nothing in the protocol or in this repo fetches it |
| `GET /.well-known/capabilities` | **permit** at the HTTP layer | `eddi.a2a.capabilities.public` (default `false`) is the only *authorization* gate — `eddi.a2a.enabled` gates it as well, but neither looks at the caller. While either is off the handler answers 404 to authenticated and anonymous callers alike, so permitting the path widens nothing — and while both are on, "public" has to mean *without a token* |
| `GET /.well-known/capabilities/skills` | **permit** at the HTTP layer | Same flag, same reasoning |

Where code and config disagreed, the **code** was changed: `listA2AAgents` lost `@PermitAll` and
gained `@Authenticated`, rather than gaining a permit entry.

### Design decisions

- **`/a2a/agents/*/agent.json`, not `/a2a/agents/*`.** Quarkus 3.39's `ImmutablePathMatcher`
  supports an inner wildcard matching exactly one path segment, and the distinction is load-bearing
  twice over. A `/a2a/agents/*` prefix would (a) permit the roster, because the prefix registers
  under `/a2a/agents` and wins over the catch-all, and (b) **break A2A outright**: the JSON-RPC
  `POST /a2a/agents/{agentId}` would match a `methods=GET` entry, and Quarkus *denies* on a method
  mismatch rather than falling through. Both are asserted.
- **No `/.well-known/*` wildcard.** RFC 9728 protected-resource metadata is planned under that
  prefix (`planning/saas-connectors-plan.md` §6.3); a wildcard would pre-permit it, and anything
  else later dropped there, with nobody deciding to. The paths are enumerated and a test asserts a
  sibling still requires authentication.
- **One knob for capability discovery.** The permission entry does not re-express
  `eddi.a2a.capabilities.public`; duplicating the gate into a second property is how the two drift.

### Tests

- **`A2aEndpointPermissionsTest`** (new, unit — runs in `./mvnw test`, no container). Feeds the
  shipped `application.properties` through Quarkus's own `ImmutablePathMatcher` and resolves the
  effective policy per path and method, replicating `findHttpMatchers`' method-filtering rule. Its
  last test reflects over `RestA2AEndpoint` and asserts every `@PermitAll` / `@Authenticated` method
  resolves to the policy it claims — so the *next* endpoint added with a forgotten permit entry
  fails here. Mutation-checked three ways: removing the card entry reproduces the original
  `[authenticated]`; widening to `/a2a/agents/*` catches both failure modes above; a
  `/.well-known/*` wildcard trips the sibling assertion.
- **`ui/manager/e2e/auth/a2a-discovery.spec.ts`** (new). The auth E2E tier is the only one that
  enforces authentication, so it is where the real status codes belong: it creates an A2A-enabled
  agent (a card is built from stored config, no deployment needed), then asserts 200 anonymous for
  both cards and both capability endpoints and 401 anonymous for the roster, the JSON-RPC surface
  and an unlisted `/.well-known` sibling. It opens with its own "this backend really is enforcing
  auth" guard so it cannot pass vacuously, and re-checks the roster with an admin token so the 401
  is provably about anonymity.
- `docker-compose.integration-keycloak.yml` sets `EDDI_A2A_CAPABILITIES_PUBLIC=true`, because with
  the flag off the spec could not tell "permitted, flag says no" (404) from "the permission entry is
  missing again". The flag-off 404 stays covered by `RestA2AEndpointTest`.

### Files

- `src/main/resources/application.properties` — new `a2a-agent-card` and `a2a-capabilities` permit
  entries, GET-only, before the catch-all
- `src/main/java/ai/labs/eddi/engine/a2a/RestA2AEndpoint.java` — `listA2AAgents` is
  `@Authenticated`; Javadoc on every endpoint records the posture and why
- `src/test/java/ai/labs/eddi/engine/a2a/A2aEndpointPermissionsTest.java` — new
- `ui/manager/e2e/auth/a2a-discovery.spec.ts` — new
- `ui/manager/docker-compose.integration-keycloak.yml` — capability flag on
- `docs/a2a-protocol.md` — an "Anonymous?" column and a "Who can call them" section
- `docs/configuration-reference.md` — `eddi.a2a.capabilities.public` says what it actually gates

### Review follow-up (PR #782)

Three findings, all valid, all fixed on the branch:

- The generic guard resolved the HTTP verb as `isAnnotationPresent(GET) ? "GET" : "POST"`, so a
  future `@PermitAll @PUT` would have been graded against a method it does not serve — and since
  the permit entries are GET-only, that is precisely the drift the guard exists to catch. The verb
  now comes from whichever annotation is meta-annotated `jakarta.ws.rs.HttpMethod`, and the guard
  fails on anything other than exactly one. Confirmed by planting a `@PermitAll @PUT` endpoint plus
  a permit entry naming POST: the old code passed it, the new code names the entry and the verb.
- `docs/a2a-protocol.md` said everything is reachable without a token when OIDC is off. True of
  authentication, misleading about the result — `eddi.a2a.capabilities.public` is an independent
  switch and its endpoints 404 either way while it is off.
- `docs/configuration-reference.md` said the capability endpoints expose agent *names*.
  `CapabilityMatch` is `(agentId, skill, confidence, attributes)` — ids. The surface is smaller
  than the doc claimed, which if anything strengthens the case for leaving `/a2a/agents` (names,
  descriptions, URLs) authenticated.

**Second pass** (CodeRabbit's first review was rate-limited before it saw the fix commits, so both bots
were asked for a fresh look):

- `eddi.a2a.capabilities.public` was described as "the only gate". `eddi.a2a.enabled` gates the
  capability endpoints too (`if (!a2aEnabled || !capabilitiesPublic) → 404`). Reworded in all five
  places that said it to "the only *authorization* gate — neither flag inspects the caller", which
  is the claim the permit entry actually rests on.
- The Agent Card's `authentication.credentials` is built from `quarkus.oidc.auth-server-url`, i.e.
  the URL **EDDI** uses to reach the IdP. The shapes that bundle Keycloak set that to an in-cluster
  or compose hostname, so the token endpoint advertised to an outside peer does not resolve — which
  this PR makes consequential, because the card is now anonymously readable under auth. Initially
  deferred as a config-design decision; **fixed here** once CodeRabbit raised it independently at
  Major severity — see the fourth pass below.

**Third pass — two findings Copilot *suppressed* into its review body**, where they have no thread and
a `reviewThreads` query cannot see them. Both were real, and both are properly this PR's:

- **`/.well-known/agent.json` fanned out over the whole roster.** `getDefaultAgentCard()` called
  `listA2AAgents()` and returned `cards.get(0)` — building a card for every A2A-enabled agent
  (`getCurrentResourceId` + `read` + `readDescriptor` apiece, up to 100 candidates) and discarding
  all but one. Merely wasteful while the endpoint required a token; an amplification vector now that
  this PR makes it anonymous. `AgentCardService.getDefaultAgentCard()` now stops at the first match
  (`collectA2AAgents(stopAtFirst)`), and `AgentCardServiceTest` asserts **one** store read across 25
  candidates rather than asserting the card — the card was always right, the cost was not.
- **The E2E cleanup scored a failed request as success.** `await call().catch(() => undefined)`
  followed by `res === undefined || res.status() < 400` passed when the request never completed,
  leaking the A2A-enabled fixture agent. That one contaminates specifically: the default Agent Card
  is whichever A2A agent comes first, so a leftover is exactly what a later run reads. The soft
  assertion now requires a real 2xx/3xx and reports the status or the error.

**Fourth pass — the advertised token endpoint, raised independently by both reviewers.** Deferred
twice on scope, then implemented: two reviewers agreeing, both framing it as "the permission change
makes this pre-existing URL consequential", outweighed the argument for keeping it separate.

`AgentCardService.advertisedTokenEndpoint()` resolves what the card advertises:

- **`eddi.a2a.public-token-endpoint`** (new, optional) — advertised verbatim. The *endpoint*, not
  the issuer, because the path is the provider-specific part.
- Otherwise `<issuer>/protocol/openid-connect/token`, where `<issuer>` is **`eddi.keycloak.public.url`**
  grafted onto the realm path from `quarkus.oidc.auth-server-url`, falling back to
  `quarkus.oidc.auth-server-url` itself. Both shipped authenticated deployments already set the
  public URL — Helm *requires* it, since the Manager SPA cannot start a login without it — so they
  become correct with no new configuration. Only the origin is taken from it; the realm path stays
  what EDDI is configured against, so the two cannot drift. **Nothing moves for a deployment that
  does not opt in**, which is what made this safe to do inside a permissions PR.

The derivation **assumes Keycloak**, which the docs now say rather than gloss. OIDC discovery would
remove the assumption instead of documenting it and is the right follow-up; it is not done here
because it turns rendering an anonymous card into an outbound HTTP call, needing `SafeHttpClient`,
a cache and a failure policy.

Verified end to end rather than by unit test alone — built the image, ran the Keycloak tier, and read
the anonymous card: `credentials` is now
`http://localhost:8180/realms/eddi/protocol/openid-connect/token`, the published port an outside peer
sees, where it was `http://keycloak:8080/...`. That URL is provably reachable — it is the one the
test fixtures fetch their tokens from. `a2a-discovery.spec.ts` now asserts it exactly, as the
reviewer asked.

**Fifth pass — a bug in the fourth pass.** CodeRabbit (Major) caught that the property introduced
above was the *issuer*, while the Keycloak path `/protocol/openid-connect/token` was appended to
whatever it named. So the one knob documented as "the escape hatch for a non-Keycloak IdP" handed an
Okta or Auth0 operator their issuer with a Keycloak path stapled on — it did not do the job it was
documented as doing, and the docs, the commit message and the reply to the reviewer all repeated the
claim.

Replaced `eddi.a2a.public-auth-server-url` with `eddi.a2a.public-token-endpoint`, advertised
verbatim: **one** property instead of two, and it actually covers the case the other one claimed to.
The property was one commit old and unreleased, so nothing depended on it. Two tests pin the
distinction, including one asserting the Keycloak path is never appended to an endpoint given in
full.

Also seen this pass and **not** fixed here: `UI Manager Checks` went red on
`share-dialog.test.tsx › does not let two quick Enters skip the ownership confirmation`, a file this
branch does not touch. It passes 15/15 locally three runs in a row, and the cause is visible in the
test — a synchronous `expect(screen.getByTestId("share-owner-warning"))` immediately after an async
`userEvent.type`, with no `waitFor`, so a slow runner loses the race. A real flake with a one-line
fix, but in unrelated code; filed separately rather than smuggled into a permissions PR.

### What's next

Nothing outstanding for A2A. The generic lesson — `@PermitAll` is not a permit entry — applies to
any future endpoint meant to be anonymous; `A2aEndpointPermissionsTest` only guards
`RestA2AEndpoint`, and widening it to every `@PermitAll` in the codebase would be a reasonable
follow-up.

---

## 🔏 chore(ci): settle the dependency-review licence policy — deny-list kept, broadened, documented (2026-09-17)

**Repo:** EDDI (`chore/dependency-review-license-policy`)

### Why

`.github/workflows/dependency-review.yml` printed a deprecation warning on every PR
("The deny-licenses option is deprecated for possible removal in the next major
release"). The comment above the option already recorded the deferral: migrating to
`allow-licenses` means enumerating every licence the project accepts, which is a
repo-wide policy decision, not a mechanical swap. This session established the real
input, put the decision to the maintainer, and implemented the answer.

### What the dependency graph actually contains

Enumerated three ways: `license-maven-plugin:add-third-party` for the resolved Maven
tree (582 artefacts), `npm query ":not(.dev)"` for both UIs, and — the one that
matters — the live graph the action actually reads,
`gh api repos/labsai/EDDI/dependency-graph/sbom`.

GitHub's Maven graph parses `pom.xml` directly and does **not** resolve transitives,
so the policy is evaluated against 95 Maven entries, not 582:

| Count | Licence |
|---|---|
| 58 | `NOASSERTION` — BOM-managed (`io.quarkus:*`, `jakarta.annotation`, `caffeine`) or `${property}`-versioned (all 22 `dev.langchain4j:*`) |
| 27 | `Apache-2.0` |
| 4 | `MIT` (testcontainers) |
| 2 | `Apache-2.0 AND BSD-3-Clause AND MIT` (maven plugins) |
| 2 | `LicenseRef-bad-non-standard` — `org.jsoup:jsoup`, `io.github.classgraph:classgraph`; both are really MIT |
| 1 | `BSD-2-Clause` (postgresql) |
| 1 | `EPL-2.0 OR (Apache-2.0 AND EPL-2.0)` (jacoco) |

npm contributes 1137 graph entries but `fail-on-scopes` defaults to `runtime` and
`main.ts` runs the licence check on the scope-filtered set, so only production deps
count: manager 179 (MIT 164, OFL-1.1 8, ISC 2, Apache-2.0 2, BSD-3-Clause 1,
`MPL-2.0 OR Apache-2.0` 1) and chat 126 (MIT 122, ISC 2, BSD-3-Clause 1). The
MPL-2.0, CC-BY-4.0 and Python-2.0 entries in the graph are all devDependencies.

### Decision

Keep `deny-licenses`, broaden it, and record why the warning is accepted. Three
findings from reading the action's source made the allow-list migration the worse
option rather than merely the more expensive one:

1. **It would fail the build today.** `spdx.satisfies()` returns `false` for an
   expression it cannot match, so the two `LicenseRef-bad-non-standard` entries land
   in `forbidden` → `setFailed` under an allow-list. Under a deny-list
   `satisfiesAny()` returns `false` and they pass. Migrating would mean two permanent
   per-package exclusions that exist only to work around GitHub's own normalisation.
2. **It buys no coverage.** The 58 unknown-licence entries go to the `unlicensed`
   bucket, and `printNullLicenses()` only prints — it never sets `issueFound`. They
   are informational in *both* modes.
3. **Removal is not scheduled.** Upstream issue #997 was closed by stalebot after 180
   days of inactivity, not by a decision, and v5.0.0 (2026-05-08) is a node20 → node24
   runtime bump that leaves `deny-licenses` fully documented in `action.yml`. There is
   no newer v4 digest, so the pin stays at v4.9.0.

The line is drawn at the library level, because EDDI is Apache-2.0 and ships a fat jar
inside a distributed Docker image — a combined work. Permissive and weak (file-level)
copyleft stay acceptable; EPL especially has to, since the whole Jakarta EE / JUnit /
JaCoCo layer Quarkus pulls in is EPL, usually dual with GPL-2.0 under the Classpath
Exception. Denied: AGPL-3.0, GPL-2.0, GPL-3.0, LGPL-2.0/2.1/3.0 (each `-only` and
`-or-later`), SSPL-1.0, BUSL-1.1, Elastic-2.0. The additions past the original two are
not hypothetical — the realistic hazard for middleware is a dependency relicensing to
source-available, and EDDI already depends on MongoDB and Elasticsearch clients.

### Verified, not assumed

Ran the candidate list through the same libraries the action uses
(`@onebeyond/spdx-license-satisfies`, `spdx-expression-parse`) against every licence
value in the live SBOM:

- nothing currently in the graph is newly denied — the change is a strict superset of
  the old behaviour with no regression;
- every listed hazard is caught;
- deprecated ids still match: a dep declared `GPL-3.0` is caught by `GPL-3.0-only`, so
  modernising the identifiers does not weaken the gate;
- Classpath-Exception artefacts do **not** false-positive —
  `EPL-2.0 OR GPL-2.0-with-classpath-exception` and
  `CDDL-1.1 OR GPL-2.0-only WITH Classpath-exception-2.0` both pass with `GPL-2.0-only`
  and `GPL-2.0-or-later` denied. This was the main risk of adding GPL-2.0 and it is
  disproven, not hoped.

Known trade-off, recorded in the workflow: `satisfiesAny()` treats `A OR B` as denied
when either side is, so a *directly declared* dep offering `Apache-2.0 OR LGPL-2.1`
would be flagged despite the Apache option. Nothing hits this today — the dual-licensed
artefacts (`net.java.dev.jna`, `org.javassist`, `com.github.java-json-tools:*`) are all
transitive and invisible to GitHub's Maven graph.

### Dropped the Caffeine exemption

The `allow-dependencies-licenses` entry for Caffeine is **removed**. It was first kept
with a corrected comment calling it cosmetic; CodeRabbit pushed back on the PR, and it
was right. `groupChanges` in the action's `src/licenses.ts` says so in its own comment —
*"we leave it off of the `licensed` and `unlicensed` lists"* — so the input drops a
package from the licence check **entirely**, not just from the unknown-licence notice.
The exemption therefore also waived `deny-licenses` for any future Caffeine release
whose licence GitHub *can* resolve, while buying nothing: per finding 2 an unresolved
licence cannot fail the build anyway, and 57 other entries sit in the same bucket
unexempted. Caffeine remains verified Apache-2.0 (its own POM on Maven Central at 3.2.4,
the version the Quarkus BOM resolves), shipped transitively via `quarkus-caffeine`
before it was ever declared here — nothing needed waiving. The replacement note records
when that input *is* appropriate: a package whose licence GitHub reports wrongly, naming
the licence being accepted.

### Files

- `.github/workflows/dependency-review.yml` — broadened `deny-licenses`, removed
  `allow-dependencies-licenses`; rewrote the comments to record the decision, the
  evidence, and the revisit condition (upstream announcing removal, or GitHub resolving
  BOM-managed Maven coordinates).

## ⬆️ chore(ui): Node 22 toolchain, Stryker 10, Vitest 5 for the Chat UI (2026-09-17)

**Repo:** EDDI (`feat/node-22-toolchain`, stacked on `fix/ui-npm-vulnerabilities` / #770)

Node 20 reached end of life on 2026-04-30, and it was what held the UIs on Stryker 9 and Vitest 4:
Stryker 10 dropped Node 20 (Dependabot #768 was red for that reason) and Vitest 5 requires ≥ 22.12.

### What changed

- **Node 20 → 22 everywhere the build names a version.** `pom.xml` `node.version` `v20.20.2` →
  `v22.23.2` (the latest 22.x; Maintenance LTS until 2027-04-30), `mise.toml` to match, and all eight
  `actions/setup-node` steps in `ci.yml` (`node-version: 22`, step names too). `AGENTS.md` and
  `README.md` no longer say Maven downloads Node 20. No test asserts on the version and no Dockerfile
  uses Node.
- **Manager: Stryker `9.6.1` → `10.0.0`** (still exact-pinned). Its only breaking change is the Node
  floor. The `typed-rest-client` → `qs` override stays: Stryker 10 still takes `typed-rest-client`
  `~2.3.0`.
- **Chat UI: Vitest `^4.1.11` → `^5.0.1`.** No test or config change was needed.
- **Manager stays on Vitest 4.1.11 — see Decisions.** `dependabot.yml` now ignores Vitest/`@vitest/*`
  *majors* for `/ui/manager` only, with the reason and the upstream issue beside the rule
  (`update-types` scopes it to version updates; security updates still arrive).
- **The UIs' own docs caught up** (Copilot review): `ui/chat/README.md` and `ui/chat/AGENTS.md` still said
  Node ≥ 20, Vitest 3 and react-markdown 9.x; `ui/manager/README.md` still said Node ≥ 20. A contributor
  following them would install an unsupported runtime. Each now names the floor that applies to that UI
  — 22.12 for the Chat (Vitest 5), 22.18 for the Manager (Stryker 10's Babel 8) — and the pinned 22.23.2.
- **`updates.test.ts`: a test for the two cleanups in `getWithoutCredentials`'s `finally`.** Stryker 10
  mutates more statements than 9.6.1 (265 mutants on `updates.ts` against 263), and both new ones
  survived: deleting `clearTimeout(timer)` or `csp.stop()` failed no test. The existing "stops listening
  for violations once the request is done" test cannot see that leak — each request reads its own
  closure's flag, so a leftover listener never touches the next verdict, it only accumulates. The new
  test pins both calls (spies on add/removeEventListener and set/clearTimeout) and fails with either
  line deleted.

### Decisions

- **The Manager cannot take Vitest 5 yet: it breaks Stryker.** On Vitest 5, `@stryker-mutator/vitest-runner`
  10.0.0 selects zero tests per mutant — its per-test filter joins names with a space, Vitest 5 joins
  them with `' > '` ([stryker-mutator/stryker-js#6210](https://github.com/stryker-mutator/stryker-js/issues/6210),
  open, no fixed release on npm). Measured here: `updates.ts` scored **0.00** (all 257 covered mutants
  "survived") against 81.85 on Vitest 4 with the same runner and Node. The break threshold would at least
  fail the run, but a gate whose every mutant survives measures nothing. The Chat UI has no Stryker, so it
  moves. Revisit the Manager when a fixed runner ships — the Dependabot ignore rule names the issue.
- **The real Node floor is 22.18, not 22.12.** Stryker 10 moved to Babel 8, whose packages declare
  `engines.node` `^22.18.0 || >=24.11.0`. The `pom.xml` comment records it; on an older 22.x Stryker
  warns `EBADENGINE` and may not run.
- **22, not 24.** 24 is Active LTS until 2028-04-30, but this change was scoped to leaving the EOL line.
  Every package in both lockfiles declares an `engines.node` range that also accepts 24.21.0, so moving
  on later is a pin change, not a migration.

### Verification (on Node 22.23.2 — the binary Maven downloads)

- `./mvnw package -DskipTests` installed Node v22.23.2 and ran `npm ci` + `npm run build` for both UIs:
  BUILD SUCCESS.
- Manager (Vitest 4.1.11, Stryker 10): lint, typecheck, `vitest run --coverage` — 411 files, 6,515
  tests, thresholds met. Scoped `stryker run --mutate src/lib/api/updates.ts`: **83.78** (216 killed of
  265), above `thresholds.break` 82 — 81.85 before the new cleanup test, 82.49 on Stryker 9.6.1.
- Chat (Vitest 5.0.1): typecheck, 278/278 tests. A static sweep found none of Vitest 5's removals in use
  (`.sequential`, non-top-level `vi.mock`, removed `vitest/*` entry points, `toThrow('')`), and its new
  `clearMocks: true` default broke nothing.
- `npm ci` for both lockfiles in a `node:22.23.2` Linux container. The Windows `@emnapi` pruning did not
  recur; all four entries are present. `npm audit`: 0 vulnerabilities in both UIs.

---

## Decision Log

_For recording decisions that come up during implementation that aren't in the plan._

| Date       | Decision                                                              | Context                               | Alternative Considered                                      |
| ---------- | --------------------------------------------------------------------- | ------------------------------------- | ----------------------------------------------------------- |
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
