## ci: run the pipeline for merge-queue entries (`merge_group`) (2026-11-14)

**Repo:** EDDI (`ci/merge-queue-trigger`)

### Why

On 2026-11-12 #1352 renamed `ResourceAccessGuard.callerIsAdmin()` to `isAdmin()` and #1345 added a test calling
`callerIsAdmin()`. Each PR was green against an older `main`, they were merged minutes apart, and `main`'s test
sources stopped compiling. Branch protection requires `Build & Test` and `CodeQL Analysis` but not an up-to-date
branch, so nothing tested the two together. A GitHub merge queue does, but only if the workflow that produces the
required checks runs on the queue's `merge_group` event. Without that, every queue entry waits for checks that
never report.

### What changed

- `.github/workflows/ci.yml`: new `merge_group` trigger (`types: [checks_requested]`). Every event-dependent
  condition was checked against it:
  - `detect-changes` skips the path filter and forces every filter true on `merge_group`, in the same branch as
    a release tag. A queue entry runs every test path instead of trusting a diff.
  - Maven Cache, Build & Test and CodeQL Analysis (the required checks and what they `need`) run and report.
    Integration Tests, the UI jobs, Build Image and the E2E tiers run as on `main`. Backend E2E runs the MongoDB
    leg only, as on a PR, because it is not required and a second leg per entry only lengthens the runner queue.
  - Secret Scanning scans `merge_group.base_sha..head_sha` and takes `.gitleaksignore`/`.gitleaks.toml` from the
    base. Before this, the event fell through to scanning only the tip commit.
  - SBOM, Docker push, Smoke Test, Preflight Verify, Release, Red Hat publish and Post-Release stay push/tag-only.
    Changelog Discipline and Preflight Dry-Run stay PR-only. Slack now skips `merge_group` explicitly.
  - Concurrency: queue runs use `cancel-in-progress` like PRs. Each entry has its own ref, so parallel entries
    never cancel each other. `main` and tag pushes are still never cancelled.
- `BuildQualityGatesTest#mergeQueueEntriesRunTheRequiredChecksAndPublishNothing` checks this contract: the
  trigger exists, no required check (or anything it needs) is gated on an event or a ref, detect-changes forces
  its filters on `merge_group`, and every publishing job is unreachable from it.
- `docs/code-review-standards.md`: new "Merge queue" section covering what runs per job on a queue entry and the
  admin steps and suggested settings for turning the queue on.

### Design decisions

- **Force every filter instead of diffing.** The pinned `dorny/paths-filter` (v4.0.3) does support
  `merge_group`: it diffs `base_sha..head_sha`. But the queue's purpose is to catch combinations, and running the
  full pipeline is the safe answer at the cost of runner time. That cost is bounded by the queue's group size and
  build concurrency.
- **Repository settings are not changed here.** An admin enables the queue afterwards, following the steps in
  the doc.

### What's next

- An admin turns on "Require merge queue" for `main`. The `merge_group` path can only be fully exercised once
  the queue is live, so the first few queue runs should be watched.
