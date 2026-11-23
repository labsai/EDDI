## ⚡ chore(ci): shorter CI critical path and merge-queue support (2026-11-23)

**Repo:** EDDI (`chore/ci-throughput`)

### Why

An analysis of 1,000 CI/CD runs and 145 merged PRs (2026-11-05 to 11-23) found the merge
latency dominated by manual one-at-a-time merging (median 51 min between merges, each waiting
for `main`'s 32-minute run) and by `main` going red when two individually green PRs broke each
other (49 h on 2026-11-12/14, 70 failed PR runs). The CI critical path was Build & Test (~14 min)
followed by Integration Tests (~15 min), which waited for it only to reuse the unit-test coverage
data.

### What changed

- **`ConfigurationReferenceCoverageTest`** ran a regex per property constant over all ~1,900
  sources, three times per class (104 s on CI, 126 s locally). A `contains()` pre-check that is a
  necessary condition of that regex, plus one scan per class, brings it to under 2 s. The
  extracted property map was diffed before and after: byte-identical.
- **Unit tests run in three JVMs on CI.** `pom.xml` gains a `surefire.forkCount` property (default
  1, so local builds are unchanged — other worktrees share dev machines) and Build & Test passes
  `-Dsurefire.forkCount=3`. A dedicated property, not surefire's `forkCount`, because failsafe reads
  that one too. A full local run with three forks surfaced one real cross-fork hazard: the 32
  `backup.impl` tests that construct `RestExportService`/`RestImportService` share
  `<working dir>/tmp/`, and one fork's cleanup deleted another's export mid-test (reproduced 1 in 2
  runs). [`@SharedWorkingDirTmp`](../../src/test/java/ai/labs/eddi/backup/impl/SharedWorkingDirTmp.java)
  now serialises exactly those classes across forks with an OS file lock (3 of 3 clean runs after),
  and `SharedWorkingDirTmpGuardTest` fails if a test constructs either service without it, or if a
  third production class starts deriving a path from `user.dir` (mutation-checked).
  No unit test boots Quarkus, so forks cannot clash on Quarkus or Mongo dev-service ports.
- **Integration Tests no longer wait for Build & Test.** It needed it only to download the unit
  tests' JaCoCo data for the merged 90/80 gate. The gate moves to a new **Coverage Gate** job that
  needs both suites, checks that `jacoco.exec`, `jacoco-it.exec` and `jacoco-quarkus.exec` are all
  present and non-empty (JaCoCo skips a missing file with an info line, so a renamed artifact would
  otherwise grade less data and pass), runs
  `compile jacoco:report@report … jacoco:check@merged-check -DskipITs=false` and fails if the
  merged report is missing. Integration Tests passes `-Djacoco.haltOnFailure=false`, since it has
  no unit data. Verified locally: on unit-only data the command fails at 0.89/0.79, so the check is
  armed. Docker (publishing) and the Slack summary now include the gate.
  `BuildQualityGatesTest.theCoverageGateIsArmedByCi` used to grep for `mvnw verify -DskipITs=false`,
  which the integration job still satisfies with the check relaxed; it now pins the single
  `jacoco:check@merged-check` line (armed, no `haltOnFailure`/`jacoco.skip`), its needs, the
  data-presence step and docker's dependency on it.
- **Merge-queue support and a `CI Gate` check.** `ci.yml` triggers on `merge_group`.
  detect-changes skips the path filter there and forces every filter true in the same branch as
  a tag, so a queue run is the full pipeline (both E2E databases) and publishes nothing (every
  publishing job gates on `push`). Secret Scanning scans `merge_group.base_sha..head_sha` (it
  fell through to "scan the tip with `--no-merges`", which finds nothing on a merge commit), and
  Slack stays quiet for queue runs. The new **CI Gate** job (`if: always()`) needs every job docker
  needs plus shell-lint, manifest-lint and release-pointers; it fails on any failed or cancelled
  need, and on any job the change required that did not succeed — derived from detect-changes on
  a PR or push, every job on a merge-queue run (so a broken forced-true branch cannot merge
  untested code). One tolerance: a PR-level Secret Scanning failure when the PR itself changes
  `.gitleaksignore`/`.gitleaks.toml` (that scan uses the base config, so such a PR could never pass);
  the queue run then scans with the PR's config. The script was run against 16 simulated
  outcomes (PR, push and merge_group; skipped, failed and cancelled needs; both gitleaks cases).
  `BuildQualityGatesTest.ciGateBlocksWhateverBlocksPublishing` pins its needs against docker's,
  its `always()` and the merge-queue strictness; the `ciJobNeeds` helper no longer reads the words
  of a comment after a `needs:` list as job names (it reported docker as needing `pushed`/`tagged`).
- **The Manager's Vitest suite runs in three shards.** It took 12–16 min inside UI Manager Checks.
  `ui-manager-unit` (matrix 1–3) runs `vitest --shard=N/3 --reporter=default --reporter=blob
  --coverage` with the coverage thresholds zeroed — a shard covers a third of the suite and would
  fail them every time — and uploads `.vitest/blob/` (`include-hidden-files`; it is a dot-directory).
  `ui-manager-unit-merge` ("UI Manager Unit Tests") refuses to run with fewer than three blobs, then
  `vitest --merge-reports --coverage` reports every test and enforces the thresholds from
  `vitest.config.ts` on the merged coverage. Verified locally on Vitest 5.1 over a subset (17 files,
  555 tests across two shards): the merge summed both shards and exited 1 on the real thresholds,
  0 with them zeroed. UI Manager Checks keeps audit, lint, i18n, typecheck, build and the bundle
  budget; UI Gate now requires the shard and merge jobs too. `ui/manager/.gitignore` ignores
  `.vitest/`.
