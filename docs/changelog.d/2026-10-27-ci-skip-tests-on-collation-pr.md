## CI: the changelog collation PR no longer runs the Java suite (2026-10-27)

**Repo:** EDDI, branch `ci/skip-tests-on-collation-pr`.

**What changed:** `.github/workflows/ci.yml` — `detect-changes` now forces `operator_docs=false` for a pull request whose head is this repository's own `chore/collate-changelog` branch **and** whose diff contains only files the collator writes (`docs/changelog.md`, `docs/changelog.d/*`, `docs/changelog/*.md`, `docs/SUMMARY.md`). Build & Test and the dependent jobs are skipped for it; a skipped required check still satisfies branch protection.

**Design decisions:** the exemption checks the diff as well as the branch name, because a name alone was already abused as an opt-out of the Changelog Discipline job. Any other file on that branch restores full gating.
