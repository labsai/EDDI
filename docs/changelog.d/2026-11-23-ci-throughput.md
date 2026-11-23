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
