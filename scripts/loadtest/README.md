# Turn load test and benchmark

The measurement harness of the [throughput plan](../../planning/throughput-optimization-plan.md) (WS0),
grown into a benchmark suite: a reproducible run that reports turns/s, latency percentiles, EDDI's own
overhead on top of the LLM, time to first token over SSE, multi-node scaling, multi-agent group
throughput and database operations per turn — and renders them as one Markdown benchmark page.

```
k6 / SSE driver ──► EDDI :7370 ──► WireMock "LLM" :18370 (fixed delay, or a stream with a first-chunk delay)
 (VUs = conversations)   │
                      MongoDB :27370 or PostgreSQL :54370

cluster-*:        k6 ──► nginx :7570 ──► N replicas (:7581–7599) ──► NATS JetStream (R3), MongoDB :27570
```

EDDI runs the **published** `labsai/eddi` image with this checkout's `target/quarkus-app` mounted over
`/deployments` (the [cluster demo](../cluster-demo/README.md) does the same), so the numbers belong to
this branch. Everything binds to `127.0.0.1`; EDDI runs unauthenticated, as in the README quick start.
The load generators run **inside** the compose network, so EDDI and the mock are measured over the
same hop.

## Run it

Needs Docker and Python 3.10+. Pulls `labsai/eddi:6.5.0`, `mongo:7.0.43` (or `postgres:16.15-alpine`),
`wiremock/wiremock:3.9.1`, `grafana/k6:0.54.0` and `python:3.12.7-alpine3.20`; the cluster topology
adds `nats:2.11-alpine` and `nginx:1.27.3-alpine`.

```bash
./mvnw package -DskipTests -DskipUi=true
python scripts/loadtest/loadtest.py up                 # stack + one deployed LLM agent
python scripts/loadtest/loadtest.py matrix --label baseline --vus 50,200,500 --delay-ms 3000
python scripts/loadtest/loadtest.py run --label baseline --vus 50 --turns 20 --delay-ms 0      # overhead only
python scripts/loadtest/loadtest.py stream --label baseline --vus 100 --first-chunk-ms 500
python scripts/loadtest/loadtest.py group --label baseline --members 3 --vus 20 --delay-ms 500
python scripts/loadtest/loadtest.py report --label baseline
python scripts/loadtest/loadtest.py down
```

| Command | What it does |
|---|---|
| `up [--db mongo\|postgres]` | Starts the stack and deploys one agent. `--no-app` measures the image as published; `--image labsai/eddi:<tag>` picks another runtime |
| `run`, `matrix` | Turns: one scenario, or `steady` at several VU counts plus a Markdown table |
| `stream` | Time to first token over SSE, native and `/v1` |
| `group` | Multi-agent group discussions |
| `cluster-matrix --nodes 1,2,4,8` | Cluster scaling: a fresh N-replica stack per point, one steady run each — see [Cluster mode](#cluster-mode-n-replicas) |
| `gate` | The CI regression gate: database operations per turn against committed ceilings |
| `report --label L [--laptop]` | Every result of a label as one benchmark page |
| `down` | Removes the stack and its volumes |

Ports move with `LOADTEST_EDDI_PORT`, `LOADTEST_WIREMOCK_PORT`, `LOADTEST_MONGO_PORT`,
`LOADTEST_POSTGRES_PORT`; the heap with `LOADTEST_JAVA_OPTS` (default `-Xmx2g`),
the container limit with `LOADTEST_EDDI_MEMORY` (default `3g`). Keep them set for every command of a
session: `loadtest.py` reads them on each call.

### Sharing a machine with other load tests

Two load tests on one machine measure each other. Give each stack its own project name and ports,
and never stop a stack that is not yours:

```bash
export COMPOSE_PROJECT_NAME=eddi-loadtest-bench LOADTEST_EDDI_PORT=7470 LOADTEST_WIREMOCK_PORT=18470 \
       LOADTEST_MONGO_PORT=27470 LOADTEST_POSTGRES_PORT=54470
```

Before each measurement the driver waits while another stack's k6 or SSE driver container runs
(`--no-wait` skips that), and it watches during the run: a result measured while one appeared
records it in `contended_by`, and the report marks the row with ⚠. The cluster stack has its own
ports (see [Cluster mode](#cluster-mode-n-replicas)) and reports other stacks' CPU instead.

## Scenarios

### Turns

| `--scenario` | Shape | Exposes |
|---|---|---|
| `steady` | `--vus` conversations, one per VU, `--turns` turns each, back to back | The concurrent-turn ceiling (WS2) |
| `burst` | Ramp to `--vus` over `--ramp`, hold for `--hold` | Admission behaviour: 503s vs. timeouts (WS2, WS4) |
| `same-conversation` | `--vus` callers on one conversation | Per-conversation queueing (WS4) |
| `long-conversation` | A few conversations of 200 turns | Costs that grow with conversation length (WS3, WS6) |

`--warmup-turns N` runs N turns per conversation first and does not record them (JIT, pools, caches).

**Clients that honour 503 + Retry-After: `--retry-503 N`.** By default a refused turn (503) uses up
the VU's iteration and the next request goes out at once. That keeps every earlier result comparable,
but it makes any load shedding read as collapsed goodput, and a 503 storm keeps the HTTP workers busy
answering refusals. With `--retry-503 N` (k6 `RETRY_503=N`) a VU that gets a 503 sleeps for its
`Retry-After` (capped at `--retry-cap-ms`, default 10000, with ±20 % jitter so refused VUs do not come
back in lockstep; 1 s when the header is missing) and resends the same turn, at most N attempts in
all, after which the turn counts as **abandoned**. Conversation starts are admitted like turns, so a
refused start backs off the same way (`starts_refused`). The result adds the latency the user actually
waited (`total_latency_ms`, refusals and backoff included), attempts per turn and `turns_abandoned`;
`turns_503` then counts refused *attempts*, not lost turns. Result files get a `-retryN` suffix. Use
this mode to measure an admission-control change; compare like with like (retry against retry), and
run `down` (fresh database) between configurations.

**Changing EDDI settings.** `LOADTEST_COMPOSE_EXTRA` names one or more extra compose files
(separated by the OS path separator) that are applied last, e.g. a file setting
`EDDI_CONVERSATION_MAX_IN_FLIGHT_TURNS` on the `eddi` service —
[`overrides/in-flight-100.yml`](overrides/in-flight-100.yml) does exactly that. Set it before `up` and keep it set.
[`results/retry-2026-10-18-report.md`](results/retry-2026-10-18-report.md) is a laptop run of both clients against
that limit: 500 conversations, the default client completed 339 of 2,500 turns, the retrying one 2,404 (74 abandoned).

**EDDI overhead** is reported for every turn run: turn latency minus the mock's configured delay
(`turn_overhead` in k6, p50/p95/p99 in the result and the report). One turn is one LLM call here, so
it is everything the platform adds — HTTP, the pipeline, conversation load and save, audit. With
`--delay-ms 0` the latency *is* the overhead; with a realistic delay it shows what concurrency does to
it.

### Streaming: time to first token

`stream` installs a streaming answer in the mock (`stream: true` requests only): headers and the first
token after `--first-chunk-ms`, then `--tokens` tokens `--chunk-ms` apart. It then runs the SSE
driver three times at the same concurrency:

1. **mock alone** — the streamed request straight to WireMock: the calibration;
2. **native** — `POST /agents/{conversationId}/stream`, one conversation per stream, TTFT = first `token` event;
3. **openai** — `POST /v1/chat/completions` with `stream: true`, one conversation per stream
   (`X-EDDI-Chat-Id`), TTFT = first chunk with non-empty `delta.content`.

Overhead = EDDI's TTFT minus the mock's own TTFT, per percentile. *Token spread* (first to last token)
equal to the mock's shows that EDDI forwards tokens as they arrive; near 0 it would have buffered.

The stub's SSE events all have the same byte length, so WireMock's `chunkedDribbleDelay` — which cuts
the body into equal slices — sends exactly one event per slice. WireMock waits one slice interval
after the headers, so the stub's fixed delay is the first-chunk delay minus one `--chunk-ms`.

**Why a Python driver and not k6 with xk6-sse.** k6 has no SSE client; xk6-sse would mean building a
custom k6 binary from a Go toolchain, the xk6 builder and a community extension, each with its own
version to pin, and an image to rebuild whenever one moves. `drivers/stream_driver.py` is one file on
the Python standard library, run in a pinned `python` image: nothing to build, nothing to drift, and
it reads the bytes itself, so the definition of "first token" is visible in 30 lines. It runs every
stream as a coroutine on one asyncio loop, so timestamps are taken by the code that reads the
socket, not by a thread waiting for the GIL. Hundreds of concurrent streams are comfortably within
its reach; for thousands, check that its own TTFT against the mock stays flat.

### Group discussions

`group` creates `--members` agents plus a moderator (one agent per seat: a group refuses a repeated
member) and a `--style` group (default `ROUND_TABLE`, `--rounds` rounds), then runs `--vus`
concurrent discussions, `--discussions` each, through `POST /groups/{id}/conversations`
([group conversations](../../docs/group-conversations.md)). It reports member turns per second
across all members — every transcript entry a member or the moderator spoke is one pipeline run with
one LLM call — plus discussion latency, turns per discussion and database operations per member turn.

### Cluster scaling

Scaling efficiency is measured by the cluster stack: `cluster-matrix --nodes 1,2,4,8` brings up a
fresh N-replica stack per point and reports turns/s, per-node latency and the efficiency
`turns/s(N) / (N × turns/s(1))` — see [Cluster mode](#cluster-mode-n-replicas) below. It writes its
own report (`results/cluster-scaling-<date>.md`), not part of `report --label`.

### PostgreSQL

`up --db postgres` swaps MongoDB for PostgreSQL. `pg_stat_statements` is preloaded, and database
operations per turn count statement executions on the `eddi` database by verb (`select`, `insert`,
`update`, …), plus row counters from `pg_stat_database` in the result's `db.detail`.

## Regression gate

Throughput on a shared CI runner is noise; **database operations per turn are not**. A fixed
scenario does the same reads and writes on any machine, so
[`.github/workflows/loadtest-db-ops.yml`](../../.github/workflows/loadtest-db-ops.yml) (manual
dispatch, nightly, and pull requests that touch this harness) boots the stack on a GitHub runner,
runs `gate`, and fails when an operation count per turn rises above
[`thresholds/db-ops-mongo.json`](thresholds/db-ops-mongo.json). Throughput and latency are uploaded
as artifacts and written to the job summary, never gated.

What is gated, and what is not:

- **`max_ops_per_turn`** caps `serverStatus().opcounters`. MongoDB counts inserted, updated and
  deleted *documents* there, which the fixed scenario reproduces. The audit ledger is written from a
  queue every 3 s, so without care a short run catches the warm-up's last batch or loses its own
  (runner runs showed 3.8 and 2.66 inserts per turn against 3.2). Every measurement therefore
  *settles* before both snapshots: it waits until each node's `eddi_audit_queue_depth` is 0, then
  one more flush interval (`LOADTEST_AUDIT_FLUSH_S`, default 3).
- **`max_collection_ops_per_turn`** caps the operations of the collections a turn reads and writes
  itself (`top` per collection): conversation memory, descriptors, vault, user memory.
- **Clock-driven reads are reported, not gated.** Agent, workflow and quota caches refresh on a
  timer and the audit ledger flushes in batches, so those collections do more work the longer the
  run takes — i.e. on a slower runner. The loose `query` total (5 operations of headroom) is the only
  ceiling over them.

When a change removes round trips the gate says "well below: consider lowering the ceiling";
lower it in the same PR. Raise a ceiling only with a reason in the changelog.
`gate --record` rewrites every ceiling as the measured value plus the file's `headroom`.

Two things the workflow depends on:

- **The ceilings live in git, not in the Actions cache.** The repository's cache sits at its size cap
  and evicts entries; a baseline that silently disappears turns the gate off.
- **GitHub-hosted runners never run workflows for pull requests opened with the default
  `GITHUB_TOKEN`** (GitHub's anti-recursion rule). A bot that opens a PR touching this harness must
  use a PAT or app token, as `base-image-check.yml` explains, or the gate never reports on it.

## Benchmark report

`report --label L` collects every result of label `L` (turns, streaming, group, gate) into
`results/L-report.md`: hardware (CPU model, CPUs and memory visible to Docker, host OS), versions
(image digests, the build's commit and branch, the JVM), the method, every number, and the exact
commands that produced them — including the `LOADTEST_*` variables in effect. `--laptop` marks the
page as not publishable. [`results/laptop-2026-10-17-report.md`](results/laptop-2026-10-17-report.md)
is a sample from a developer laptop; its scaling section was measured with an earlier `up --nodes` /
`scaling` path of this harness, which the cluster stack has since replaced.

## Cluster mode (N replicas)

`docker-compose.cluster.yml` is the topology of the repository's `docker-compose.cluster.yml` and
the [cluster demo](../cluster-demo/README.md) — N EDDI replicas with `eddi.messaging.type=nats`, a
three-node NATS JetStream cluster (R3), one MongoDB, an nginx round-robin balancer with no affinity —
plus the same WireMock LLM and k6. Its own ports (balancer `7570`, replicas `7581–7599` (one port each; spares in case another stack takes one), MongoDB
`27570`, WireMock `18570`; moved with `LOADTEST_CLUSTER_PORT`, `LOADTEST_CLUSTER_NODE_PORTS`,
`LOADTEST_CLUSTER_MONGO_PORT`, `LOADTEST_CLUSTER_WIREMOCK_PORT`), so it can run next to the single-node stack.

```bash
./mvnw package -DskipTests -DskipUi=true
python scripts/loadtest/loadtest.py cluster-matrix --baseline --nodes 1,2,4,8 --vus-per-node 300 --turns 20 --delay-ms 3000
```

Each point gets a fresh stack (empty database), a discarded warm-up run, then one `steady` run with
`--vus-per-node` × N conversations, so the offered load grows with the cluster. `--baseline` adds a
single node with `eddi.messaging.type=in-memory` first, which is the cluster-mode overhead at N=1.
`cluster-up --nodes N` / `cluster-run` / `cluster-down` run one point by hand, and
`cluster-report results/*.json` rebuilds the report from earlier runs.

What decides whether two points are comparable is set explicitly, per node, never derived from the
heap: `--max-in-flight` (`eddi.conversation.max-in-flight-turns`, default 400), `--mongo-pool`
(`maxPoolSize`, default 100), `--java-opts` (`-Xmx1536m`), `--eddi-memory` (2g) and `--eddi-cpus`
(2); MongoDB gets `--mongo-cpus` (4), each NATS node `--nats-cpus` (1, the sizing
[docs/clustering.md](../../docs/clustering.md#sizing) calls ample).

The balancer adds `X-Loadtest-Upstream` to every response, k6 tags each turn with it, and the driver
reads per-node turns/s, p50/p95/p99, 409 and 503 from the k6 summary. From every replica's
`/q/metrics` it takes `eddi_cluster_lease_acquire_seconds` by outcome, `eddi_cluster_fence_rejected_total`,
peak in-flight turns and leases held; from MongoDB the opcounters (per turn and per second on the
primary); from NATS `/varz` the messages per turn; and from `docker stats` the CPU of every container
on the host — other stacks' too, because on one machine a parallel load test is part of why the host
saturates. The report is `results/cluster-scaling-<date>.md`, with a scaling-efficiency column
`turns/s(N) / (N × turns/s(1))`.

One Docker host is not N machines: every replica, MongoDB, NATS, nginx and k6 share its CPUs, so a
falling efficiency can be the host as easily as the database. Read the CPU columns before the
efficiency one.

## What a run records

Each command writes `results/<label>-<kind>-….json`; `matrix` adds the Markdown report
`results/<label>-<scenario>-<date>.md`, `report` the benchmark page. Only the Markdown is committed.

- **Turns/s and latency:** p50/p90/p95/p99/max of successful turns, as k6 measured them, and the
  same for EDDI's overhead.
- **Errors by status:** 503 (`capacity_exceeded`), 429, 408, 409, other 5xx, no response, failed starts,
  and HTTP 200 answers that carry a `TurnError`.
- **Peaks**, sampled from every node's `/q/metrics` every second: `eddi_runtime_executor_{active,queued,max_threads}{pool}`
  (`queued{pool="managed"} > 0` is the thread-ceiling signal), `eddi_processing_conversation_count`,
  coordinator queue depth and active conversations, the audit queue, JVM live threads, MongoDB pool.
- **Counters** before/after, summed over nodes: dropped audit entries, dead letters, idempotency
  outcomes, circuit breaker, HTTP requests.
- **Database:** MongoDB `serverStatus().opcounters` and `top` per collection, or PostgreSQL
  `pg_stat_statements` by verb, divided by successful turns — this is what tells conversation loads
  from configuration reads.

The numbers include the run's conversation starts and everything else EDDI does meanwhile (schedulers,
retention); with no other traffic that noise is small against hundreds of turns.
