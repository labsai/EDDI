# Pending changelog entries

Your branch's changelog entry goes in **a new file in this directory**, not at
the top of [`../changelog.md`](../changelog.md). The nightly job folds every
file here into that one, in date order, and deletes them.

## Why

Every branch is required to add a changelog entry, and the entry used to go at
the top of the same file. Two branches adding an entry therefore inserted at
the same point, which git cannot merge: with a dozen PRs open, every one of
them conflicted with every other, on a file that had nothing to do with the
code under review — and rebasing past it only re-ran the collision at the next
merge. The two running registers at the bottom of that file conflicted the same
way.

A new file with a name no other branch picks merges silently. The entries are
combined once, afterwards, on `main`, where there is no competing branch to
conflict with.

## Writing one

Create `YYYY-MM-DD-<slug>.md`, where the date is today and the slug is
lower-case and unique to your branch — the branch name usually works:

```
docs/changelog.d/2026-09-21-fix-a2a-discovery.md
```

The content is exactly what used to go into `changelog.md` — one or more
entries, each headed with its title and its date:

```markdown
## 🐛 fix(a2a): anonymous discovery returned 401 (2026-09-21)

**Repo:** EDDI (`fix/a2a-anonymous-discovery`)

### What changed and why

...

**Files:** [`A2ADiscoveryResource.java`](../../src/main/java/ai/labs/eddi/...)
```

Two rules the collator enforces, both checked by `ChangelogFragmentTest` and by
`scripts/collate-changelog.py --check`:

- **Every entry's heading ends with its date in brackets**, `(YYYY-MM-DD)`, and
  the first one agrees with the filename. That date is what orders the entries.
- **Relative links carry one extra `../`.** This file sits one directory below
  `changelog.md`, so a link that resolves here needs a `../` removed when the
  entry moves up — which the collator does. Write `../../src/...` for a source
  file and `../architecture.md` for a neighbouring doc, and the link works both
  before and after collation. To link to something in *this* directory, go out
  and back in — `../changelog.d/README.md` — which resolves from both depths.

## The two running registers

`changelog.md` ends with a **Decision Log** and **Regression Notes** table that
sessions append rows to. Those append at a fixed point too, so they conflict
for the same reason. Put the rows in a fenced block anywhere in your fragment
and the collator moves them into the right table:

````markdown
```decision-log
| 2026-09-21 | What was decided | Why it came up | What was rejected |
```

```regression-note
| 2026-09-21 | What broke | Cause | Fix | Commit |
```
````

## What happens next

`.github/workflows/changelog-collate.yml` runs nightly, merges everything here
into `changelog.md` by date, trims that file back under its rotation target if
it has grown past it, and opens a PR with the result. Nobody has to run
anything by hand — but you can:

```bash
python scripts/collate-changelog.py --check   # validate, change nothing
python scripts/collate-changelog.py           # collate locally
```

The job **skips while a collation PR is still open**, because that PR has
already claimed these fragments and a second one proposing the same entries
would conflict with it. So if this directory stops emptying, look for an
unmerged `chore/collate-changelog` PR first.

Until that PR merges, **this directory is part of the recent history** — read
it alongside the top of `changelog.md` when you need current context.

## Migrating a PR that predates this

If your branch already has its entry at the top of `docs/changelog.md`, the
`Changelog Discipline` CI job will fail it. Move the entry:

1. Cut your `## …` section out of `docs/changelog.md`, and any rows you added
   to the Decision Log or Regression Notes tables.
2. Paste the section into a new `docs/changelog.d/YYYY-MM-DD-<slug>.md`, and
   put the rows in a `decision-log` / `regression-note` block in the same file.
3. Add one `../` to every relative link you moved — the entry is now one
   directory deeper.
4. `python scripts/collate-changelog.py --check` to confirm it parses.

That also removes the conflict your branch currently has with every other open
PR, so it is worth doing even while the old file still works.
