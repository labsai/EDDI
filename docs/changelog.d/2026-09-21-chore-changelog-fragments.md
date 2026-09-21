## 🧩 chore(changelog): per-branch fragments instead of one shared file (2026-09-21)

**Repo:** EDDI (`chore/changelog-fragments`)

### The problem

[`AGENTS.md`](../../AGENTS.md) §2 rule 8 requires every branch to add a changelog entry, and it
said to add it **directly below the `---` that closes the header** of [`changelog.md`](../changelog.md).
Every open PR therefore inserted text at the same point in the same file. Git has no way to merge two
insertions at one point, so with a dozen PRs in flight every one of them conflicted with every other —
over a document that had nothing to do with the code under review. Resolving it rebased the branch,
which re-ran the identical collision at the next merge. The two running registers at the bottom of the
file (`## Decision Log`, `## Regression Notes`) behaved the same way: a fixed append point in a shared
file.

The rule was right and the storage was wrong. Nothing about "every session records what it did"
requires every session to write into the same twenty lines.

### The shape of the fix

An entry is now a **new file** in [`docs/changelog.d/`](../changelog.d/README.md), named `YYYY-MM-DD-<slug>.md` with
the slug unique to the branch. Two branches adding an entry add two files, and git takes both without
asking. The entries are merged **once, afterwards, on `main`**, by
[`changelog-collate.yml`](../../.github/workflows/changelog-collate.yml) — where there is no competing
branch to conflict with. The changelog gains a third depth:

```
docs/changelog.d/<date>-<slug>.md   pending, written by a PR
        |  collate-changelog.py  (nightly)
        v
docs/changelog.md                   the live file, newest first, capped at 250 KB
        |  rotate-changelog.py   (nightly, when over the cap)
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
[`changelog_common.py`](../../scripts/changelog_common.py) rather than being written twice, masking
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
example row into the live Decision Log while gutting the fence around it. `fence_mask()` applies the
backtick-run rule (a fence closes only on a run at least as long as the one that opened it), and the
heading scan, the register scan and both link transforms all go through it.

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

**Adding an entry in place now fails CI.** The `Changelog Discipline` job rejects a PR whose diff
*adds* an entry heading or a dated register row to `docs/changelog.md`. Prose in AGENTS.md is what
every session follows, but it is not a guard — and twenty-nine PRs were open against the old rule
when this was written. The check is deliberately narrow: editing the header or correcting a past
entry stays allowed, because only insertion at the fixed points conflicts.

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
- The three `2026-03-05` rows at the top of the **Decision Log** were moved to the bottom. The table
  is otherwise newest-first and the collator inserts at the top, so leaving them would have produced
  a table reading 09-22, 03-05, 03-05, 03-05, 09-17.
- `update_summary()` regenerates the month list in `SUMMARY.md` between the anchor and the first line
  that is not an archive row. Putting the *Pending entries* link directly under the anchor — the
  natural place for it — would have had the regenerated months inserted above it and the old rows
  kept below, silently doubling the list; every duplicated link still resolves, so nothing would have
  caught it. It now consumes every child of the anchor and re-emits the non-month ones after.

### Migrating the PRs already open

Twenty-nine open PRs carry an entry in `docs/changelog.md` under the old rule, and seven of them also
carry a branch-local rotation of `docs/changelog/2026-09.md`. Each needs its entry cut into a fragment
— the steps are in [`docs/changelog.d/README.md`](../changelog.d/README.md) — which also removes the
conflict that branch currently has with every other open PR. [`.github/PULL_REQUEST_TEMPLATE.md`](../../.github/PULL_REQUEST_TEMPLATE.md)
and [`ui/chat/AGENTS.md`](../../ui/chat/AGENTS.md) were updated to point at the new location; the
`planning/*.md` documents that mention editing the changelog are historical plans and were left as
written.

**Files:** [`scripts/changelog_common.py`](../../scripts/changelog_common.py),
[`scripts/collate-changelog.py`](../../scripts/collate-changelog.py),
[`scripts/rotate-changelog.py`](../../scripts/rotate-changelog.py),
[`.github/workflows/changelog-collate.yml`](../../.github/workflows/changelog-collate.yml),
[`.github/workflows/ci.yml`](../../.github/workflows/ci.yml),
[`ChangelogFragmentTest.java`](../../src/test/java/ai/labs/eddi/docs/ChangelogFragmentTest.java),
[`DocumentationLinksTest.java`](../../src/test/java/ai/labs/eddi/docs/DocumentationLinksTest.java),
[`DocumentationAccuracyTest.java`](../../src/test/java/ai/labs/eddi/docs/DocumentationAccuracyTest.java),
[`DocumentedRestPathsTest.java`](../../src/test/java/ai/labs/eddi/docs/DocumentedRestPathsTest.java),
[`ConfigurationReferenceCoverageTest.java`](../../src/test/java/ai/labs/eddi/docs/ConfigurationReferenceCoverageTest.java),
[`AGENTS.md`](../../AGENTS.md), [`docs/changelog.md`](../changelog.md),
[`docs/changelog.d/README.md`](../changelog.d/README.md), [`docs/SUMMARY.md`](../SUMMARY.md),
[`.github/PULL_REQUEST_TEMPLATE.md`](../../.github/PULL_REQUEST_TEMPLATE.md),
[`ui/chat/AGENTS.md`](../../ui/chat/AGENTS.md)

```decision-log
| 2026-09-21 | Changelog entries are per-branch fragment files, collated nightly on main | Every PR inserted at the same point in one file, so every open PR conflicted with every other over a document unrelated to its code | Keep one file and resolve by hand (the conflict returns at the next merge); collate on every push to main (a bot commit per merge, and races between them); let the merge tool own it (no merge driver makes two insertions at one point orderable) |
| 2026-09-21 | The nightly job opens a PR, and skips entirely while one is open | main requires a PR; and a second PR proposing the already-claimed fragments would conflict with the first — the very failure being fixed | Push to main directly (a hole in the PR requirement); force-push the bot branch (banned by §2 rule 4); a new branch per night (two PRs carrying the same entries) |
| 2026-09-21 | Fragments live in docs/changelog.d/, one directory below the live file | Puts all changelog material in one place, and makes a fragment exactly as deep as an archive, so the two link transforms are inverses | A repo-root newsfragments/ (avoids the SUMMARY.md carve-out, splits changelog material across two trees); a subdirectory of docs/changelog/ (collides with the archive naming rule) |
| 2026-09-21 | CI fails a PR that ADDS an entry heading or a dated register row to docs/changelog.md | AGENTS.md prose is what a session follows, but it is not a guard, and 29 PRs were open under the old rule | Detect any change to the file (blocks legitimate header edits and typo fixes in past entries); rely on review to catch it (it is one line at the top of a file nobody reads in a diff) |
| 2026-09-21 | The collation PR prefers a CHANGELOG_BOT_TOKEN, and says so in the PR body when it has none | GitHub does not fire pull_request workflows for GITHUB_TOKEN events, so the PR is unmergeable against required checks until a human reopens it | Use GITHUB_TOKEN and say nothing (a nightly PR that silently cannot merge); require the secret (the job would not run at all until someone provisions it) |
```
