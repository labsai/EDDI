## feat(loadtest): benchmark suite — streaming TTFT, cluster scaling, group discussions, DB-ops CI gate (2026-10-17)

**Repo:** EDDI · **Branch:** `feat/loadtest-benchmark-suite`

### What changed and why

EDDI leads on concurrent stateful conversations per node, but nobody — EDDI included — publishes
numbers for stateful multi-turn load, time to first token overhead, scaling efficiency or
multi-agent throughput. This extends the [load-test harness](../../scripts/loadtest/README.md) so
those numbers can be published reproducibly and regressions caught. Nothing under `src/main` changed.

- **`stream`**: time to first token over EDDI's SSE endpoint (`POST /agents/{id}/stream`) and over
  `POST /v1/chat/completions` with `stream: true`, against the same streamed request sent straight
  to the mock as calibration; overhead = EDDI's TTFT minus the mock's, per percentile. The WireMock
  stub streams equal-length SSE events with `chunkedDribbleDelay`, so each slice is exactly one event,
  with a configurable first-chunk delay, token count and spacing. Driver:
  [`drivers/stream_driver.py`](../../scripts/loadtest/drivers/stream_driver.py), asyncio on the standard
  library, in a pinned `python` image on the compose network.
- **`--retry-503 N`** (k6 `RETRY_503`, off by default so earlier results stay comparable): a client
  that honours 503 + `Retry-After` (capped, jittered) and resends the same turn up to N attempts;
  reports latency including backoff, attempts per turn and abandoned turns. Requested by the
  admission-control work, whose shedding otherwise read as collapsed goodput. `LOADTEST_COMPOSE_EXTRA`
  adds compose overrides for EDDI settings under test.
- **Overhead as a first-class metric**: every turn run reports `turn_overhead` (latency minus the
  mock's delay) at p50/p95/p99.
- **`up --nodes N` and `scaling --nodes 1,2,4`**: the cluster-demo topology (NATS JetStream,
  `eddi.messaging.type=nats`, nginx round robin without affinity); nodes 2..N are generated as copies
  of the fully merged `eddi` service, `--cpus-per-node` gives each node its own CPU budget. Reports
  speedup and scaling efficiency.
- **`group`**: N-member group discussions (`POST /groups/{id}/conversations`, k6
  [`groups.js`](../../scripts/loadtest/k6/groups.js)); member turns per second across members.
- **`gate`** and [`.github/workflows/loadtest-db-ops.yml`](../../.github/workflows/loadtest-db-ops.yml)
  (manual dispatch, nightly, PRs touching the harness): a fixed scenario on a GitHub runner fails when
  MongoDB operations per turn exceed [`thresholds/db-ops-mongo.json`](../../scripts/loadtest/thresholds/db-ops-mongo.json).
  Throughput and latency are artifacts, not gates.
- **`report`**: one Markdown benchmark page per label — hardware, image digests, build commit, JVM,
  method, every number, and the exact commands (with their `LOADTEST_*` variables) to reproduce it.
  Sample: [`results/laptop-2026-10-17-report.md`](../../scripts/loadtest/results/laptop-2026-10-17-report.md).
- **`up --db postgres`**: PostgreSQL with `pg_stat_statements`; database operations per turn are
  statement executions by verb.
- **Sharing a machine**: compose files are split (base, `mongo`, `postgres`, `app`, `cluster`) and
  `COMPOSE_PROJECT_NAME` keeps stacks apart. Before a measurement the driver waits while another
  stack's k6/SSE container runs, and a result measured while one appeared records `contended_by`
  (⚠ in the report).

### Design decisions

- **A Python SSE driver, not k6 + xk6-sse.** xk6-sse needs a custom k6 build (Go toolchain, xk6,
  a community extension, each to pin); one standard-library file in a pinned image has nothing to
  build or drift, and its definition of "first token" is readable in place.
- **What the gate gates.** Document counts for insert/update/delete and the operations on the
  collections a turn touches itself (conversation memory, descriptors, vault, user memory) are
  identical run to run. Reads of agents, workflows, quotas and the audit ledger follow the clock
  (cache refresh, batched flushes) and grow on a slower runner. The audit ledger flushes from a queue every 3 s, which first moved the insert total by a batch either way on short runner runs (3.8 and 2.66 vs 3.2 per turn); every measurement now waits for that queue to drain plus one flush interval before each snapshot. The clock-driven reads are reported and covered only
  by a loose total-query ceiling (5 ops of headroom). Measured: 3 runs on this laptop agreed to
  0.01 per turn on every per-collection counter, and the first GitHub-runner run matched them exactly.
- **Ceilings in git, not the Actions cache**, which evicts entries at its size cap.
- **One NATS server (R1)** in the scaling topology: it measures throughput, not NATS failover.

### Measured (laptop, not publishable)

See the sample report. On `4b83ffb8b`: about 22.5 MongoDB operations per turn (17 queries, 3.2
inserts, 2 updates); streamed tokens reach the client unbuffered on both paths (token spread equal to
the mock's).

### Next

- Run the suite on dedicated hardware for publishable numbers.
- When a round-trip cut ([labsai/EDDI#1055](https://github.com/labsai/EDDI/pull/1055) and its follow-up) lands, the gate reports "well below": lower the
  ceilings with `gate --record` in that PR.
- Not diagnosed: at 10–50 conversations with a 0–100 ms mock the laptop shows EDDI overhead p50
  135–190 ms and p95/p99 stalls of 1.5–5 s; check on an idle machine before reading anything into it.
