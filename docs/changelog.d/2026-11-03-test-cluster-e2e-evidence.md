## 🧪 test(cluster): multi-process end-to-end cluster suite, nightly cluster demo, soak and scale harness (2026-11-03)

**Repo:** EDDI (`test/cluster-e2e-evidence`)

### What changed and why

The [cluster readiness review](../../planning/cluster-production-readiness-review.md) (committed with this
branch) found that nothing ran more than one JVM against a real database and a real NATS, with no
fault injection, no soak and no scale test, and that `scripts/cluster-demo` never ran in CI. This
is its Phase 3 ("evidence"), written against the contract of the Phase 1 fixes rather than against
today's behaviour.

- **End-to-end suite** (`src/test/java/ai/labs/eddi/integration/cluster`). `ClusterHarness` starts three
  EDDI processes from the packaged app (the production Dockerfile, as `ContainerBaseIT` builds it), an
  R3 NATS cluster (`--no_advertise`, so clients stay behind the proxy), MongoDB or PostgreSQL, the
  demo's mock LLM, and a Toxiproxy proxy per (EDDI node, NATS server). The 8 scenarios in
  `AbstractClusterE2EIT` cover ordering without faults, latency, a partitioned node in
  `degraded.turns=reject`, NATS replies dropped after the write was applied (A2), a half-open link
  (A1), a holder frozen by SIGSTOP past `lease.ttl` (A1/A6), repeated stream-leader step-downs under
  load (A2/D3), and `kill -9` mid-turn. Each scenario writes JSON evidence to
  `target/cluster-e2e/<db>/`. A scenario that `main` is expected to fail names the finding in its
  title, and its assertions state the contract.
- **`ConversationHistoryChecker`**: a history checker in the style of Knossos and Elle, reduced to the
  ordering contract. It compares client answers, committed history and the LLM's view, and flags
  lost, duplicated, phantom, refused-but-committed, out-of-order (real-time order), overlapping and
  stale-context turns. It has 20 unit tests of its own in `ConversationHistoryCheckerTest`, which run
  in the normal build.
- **Opt-in.** The suite runs only with `-Deddi.cluster.e2e=true`, so `./mvnw verify -DskipITs=false`
  skips it. It runs through surefire, which has no 15-minute fork timeout and no rerun.
- **`.github/workflows/cluster-e2e-nightly.yml`**: the suite on MongoDB and on PostgreSQL, nightly and
  on PRs that touch `engine/cluster/**`. The cluster demo runs nightly on both databases. Results and
  node logs are uploaded as artifacts, with a job summary. The workflow is not a required check.
- **`scripts/cluster-demo/demo.py`**: `--exit-code`, so CI sees a failed scenario, and `--summary-md`,
  for the step summary.
- **`scripts/loadtest/cluster_soak.py`**: `soak` runs chunked steady load for a configurable duration,
  restarting replicas along the way. It samples per-node heap, threads, held leases and open files,
  plus NATS subscriptions and stream and bucket growth, and fits a per-hour slope to each series.
  `scale` runs many concurrently held leases per node, locally or against a cluster deployed on
  separate hosts (`--external`). Both are documented in the load-test README.
- **[Clustering](../clustering.md#how-cluster-mode-is-tested)**: a new section, "How cluster mode is
  tested".

### What's next

Fill in the measured results here once the A–D workstreams land. The scenarios that name a finding
should then turn green.
