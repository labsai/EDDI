# EDDI performance benchmark — retry-2026-10-18 — 2026-10-18

> **Laptop run — not publishable.** These numbers come from a developer laptop shared with other
> workloads (other load tests, IDEs, a browser). They show the method and the shape of the results;
> absolute values belong to dedicated hardware.

## Environment

| | |
|---|---|
| CPU | 12th Gen Intel(R) Core(TM) i7-12700K — 20 logical CPUs visible to Docker |
| Memory | 31.2 GiB visible to Docker |
| Host | Windows-10-10.0.26300-SP0 · Docker 29.8.2 on Docker Desktop (kernel 6.18.54.1-microsoft-standard-WSL2) |
| EDDI | `labsai/eddi:6.5.0` with the local build of `feat/loadtest-benchmark-suite` @ `c2aa3daa51bf` mounted |
| JVM | openjdk version "25.0.4.1" 2026-08-18 LTS |
| Stack | 1 node(s), messaging in-memory, database mongo; `-Xmx2g`, container limit 3g, CPUs per node: unlimited |

Images (digests as pulled):

- `grafana/k6:0.54.0` `sha256:1f40432b1cbe7234e977f96c362c9bc550a2d2b583d014dd8669fe40d3e9e755`
- `labsai/eddi:6.5.0` `sha256:285986fabde1ac0ad823b34e7499a44e7fbbdbd1710168c50fb74fffcfda8048`
- `mongo:7.0.43` `sha256:1f995ad6fdb93244a1addab1b58f934a0bc2f5643c38e02f5e9d7f0c7d227a7b`
- `python:3.12.7-alpine3.20` `sha256:5049c050bdc68575a10bcb1885baa0689b6c15152d8a56a7e399fb49f783bf98`
- `wiremock/wiremock:3.9.1` `sha256:8fe02bc3f9b63deb1454d41750dbaf081adf4b3e8c74fd8e31f790bee5647b88`

## Method

- **The LLM is a mock** (WireMock, OpenAI wire format) with a fixed, configured latency, so every millisecond above it is EDDI's: HTTP, the lifecycle pipeline, conversation load and save, audit. No model time is hidden in the numbers.
- **Load generators run inside the Docker network** next to EDDI: k6 for turns and group discussions, a standard-library asyncio SSE client (`scripts/loadtest/drivers/stream_driver.py`) for streaming, because k6 has no SSE client.
- **Stateful conversations:** every virtual user owns one conversation and sends its turns back to back, so the conversation's memory is loaded and stored on every turn, as in production.
- **EDDI overhead** = turn latency minus the mock's configured delay (one LLM call per turn). For streaming, overhead = EDDI's time to first token minus the mock's own time to first token, measured by the same client at the same concurrency.
- **Database operations per turn** are MongoDB `serverStatus().opcounters` (or PostgreSQL `pg_stat_statements` calls) over the measured phase, divided by successful turns. They include conversation starts and background work; they do not depend on hardware.

## Turns — stateful conversations

| Scenario | Nodes | DB | Concurrent conv. | Mock delay ms | OK turns | Turns/s | p50 ms | p95 ms | p99 ms | EDDI overhead p50 / p95 / p99 ms | Errors | DB ops per turn |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| steady | 1 | mongo | 500 | 1000 | 339 | 17.5 | 3971 | 7295 | 9844 | 2971 / 6295 / 8844 | turns_503=1399, start_failed=762 | command 0.9 · insert 5.37 · query 48.41 · update 2 (total 56.68) |
| steady (retry 5) | 1 | mongo | 500 | 1000 | 2404 | 43.3 | 1499 | 3141 | 3910 | 499 / 2141 / 2910 | turns_503=2358, start_failed=22, turns_abandoned=74, starts_refused=456 | command 0.28 · insert 3.42 · query 23.85 · update 2 (total 29.54) |

Runs with a client that honours 503 + Retry-After (`--retry-503 N`): a refused turn waits Retry-After (capped, ±20 % jitter) and is resent, at most N attempts. Latency above is the accepted attempt; the user waited the total below. `turns_503` counts refused attempts, not lost turns.

| Scenario | Concurrent conv. | Max attempts | Retry-After cap ms | OK turns | Total p50 ms | Total p95 ms | Total p99 ms | Attempts avg / p99 / max | Abandoned |
|---|---|---|---|---|---|---|---|---|---|
| steady | 500 | 5 | 10000 | 2404 | 2486 | 18114 | 23298 | 1.92 / 5 / 5 | 74 |

## Reproduce

From the repository root, with Docker and Python 3.10+:

```bash
export COMPOSE_PROJECT_NAME=eddi-loadtest-bench
export LOADTEST_COMPOSE_EXTRA=scripts/loadtest/overrides/in-flight-100.yml
export LOADTEST_EDDI_PORT=7470
export LOADTEST_LB_PORT=7480
export LOADTEST_MONGO_PORT=27470
export LOADTEST_POSTGRES_PORT=54470
export LOADTEST_WIREMOCK_PORT=18470
./mvnw package -DskipTests -DskipUi=true
python scripts/loadtest/loadtest.py up
python scripts/loadtest/loadtest.py run --label retry-2026-10-18 --vus 500 --turns 5 --warmup-turns 1 --delay-ms 1000
python scripts/loadtest/loadtest.py down
python scripts/loadtest/loadtest.py up
python scripts/loadtest/loadtest.py run --label retry-2026-10-18 --vus 500 --turns 5 --warmup-turns 1 --delay-ms 1000 --retry-503 5
python scripts/loadtest/loadtest.py down
```

Raw results: `scripts/loadtest/results/retry-2026-10-18-*.json` (not committed). Report: `python scripts/loadtest/loadtest.py report --label retry-2026-10-18 --laptop`.
