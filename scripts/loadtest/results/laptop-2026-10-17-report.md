# EDDI performance benchmark — laptop-2026-10-17 — 2026-10-17

> **Laptop run — not publishable.** These numbers come from a developer laptop shared with other
> workloads (other load tests, IDEs, a browser). They show the method and the shape of the results;
> absolute values belong to dedicated hardware.

## Environment

| | |
|---|---|
| CPU | 12th Gen Intel(R) Core(TM) i7-12700K — 20 logical CPUs visible to Docker |
| Memory | 31.2 GiB visible to Docker |
| Host | Windows-10-10.0.26300-SP0 · Docker 29.8.2 on Docker Desktop (containerized) (kernel 6.18.54.1-microsoft-standard-WSL2) |
| EDDI | `labsai/eddi:6.5.0` with the local build of `feat/loadtest-benchmark-suite` @ `4b83ffb8b18c` mounted |
| JVM | openjdk version "25.0.4.1" 2026-08-18 LTS |
| Stack | 1 node(s), messaging in-memory, database mongo; `-Xmx2g`, container limit 3g, CPUs per node: unlimited |
| Stack | 1 node(s), messaging nats, database mongo; `-Xmx1g`, container limit 1600m, CPUs per node: 2.0 |
| Stack | 1 node(s), messaging in-memory, database postgres; `-Xmx2g`, container limit 3g, CPUs per node: unlimited |

Images (digests as pulled):

- `grafana/k6:0.54.0` `sha256:1f40432b1cbe7234e977f96c362c9bc550a2d2b583d014dd8669fe40d3e9e755`
- `labsai/eddi:6.5.0` `sha256:285986fabde1ac0ad823b34e7499a44e7fbbdbd1710168c50fb74fffcfda8048`
- `mongo:7.0.43` `sha256:1f995ad6fdb93244a1addab1b58f934a0bc2f5643c38e02f5e9d7f0c7d227a7b`
- `nats:2.11-alpine` `sha256:e4bf19f15fd3218814a4e3c9e0064e1334bd8aa20d5984b9f1a0afd084f8cc00`
- `nginx:1.27.3-alpine` `sha256:814a8e88df978ade80e584cc5b333144b9372a8e3c98872d07137dbf3b44d0e4`
- `postgres:16.15-alpine` `sha256:721873c34ceb9f8d8fc265984940dc982404c105f19ad51be9fdc5970a6080ea`
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
| steady | 1 | mongo | 50 | 0 | 1000 | 33.7 | 786 | 4975 | 6671 | 786 / 4975 / 6671 | none | command 0.21 · insert 3.1 · query 16.86 · update 2 (total 22.17) |
| steady | 1 | postgres | 50 | 0 | 500 | 43.5 | 1003 | 1927 | 2131 | 1003 / 1927 / 2131 | none | create 0.01 · insert 3.2 · select 17.34 · update 2 (total 22.56) |
| steady | 1 | mongo | 200 | 1000 | 2000 | 104.7 | 1543 | 2790 | 3407 | 543 / 1790 / 2407 | none | command 0.15 · insert 3.2 · query 17.11 · update 2.09 (total 22.55) |

## Streaming — time to first token (100 concurrent streams)

Mock: first token after 500 ms, then 10 tokens 20 ms apart. 5 measured turns per stream after 1 warm-up turn(s).

| Path | OK turns | TTFT p50 ms | TTFT p95 ms | TTFT p99 ms | Overhead p50 / p95 / p99 ms | Last token p50 ms | Token spread p50 ms | Tokens p50 | Errors |
|---|---|---|---|---|---|---|---|---|---|
| mock alone (calibration) | 500 | 529 | 797 | 862 | — | 811 | 207 | 10 | none |
| EDDI SSE `POST /agents/{id}/stream` ⚠ | 500 | 1816 | 2971 | 3602 | 1287 / 2174 / 2740 | 2850 | 198 | 10 | none |
| EDDI `POST /v1/chat/completions` stream=true | 500 | 1837 | 3717 | 4756 | 1308 / 2920 / 3894 | 3117 | 207 | 10 | none |

A token spread close to the mock's means EDDI forwards tokens as they arrive; near 0 would mean it buffered the answer.

## Cluster scaling — 200 conversations, mock delay 0 ms

Nodes on NATS behind nginx round robin (no affinity), CPUs per node: 2.0. Efficiency = turns/s ÷ (nodes × single-node turns/s).

| Nodes | Turns/s | Speedup | Efficiency | p50 ms | p95 ms | EDDI overhead p95 ms | Errors | DB ops per turn |
|---|---|---|---|---|---|---|---|---|
| 1 ⚠ | 22.9 | 1.00× | 100 % | 7348 | 15309 | 15309 | none | command 0.17 · insert 3.13 · query 16.96 · update 3.07 (total 23.33) |
| 2 ⚠ | 40.8 | 1.78× | 89 % | 2419 | 10824 | 10824 | none | command 0.15 · insert 3.13 · query 16.68 · update 3.1 (total 23.07) |
| 4 | 57.9 | 2.53× | 63 % | 1933 | 7911 | 7911 | none | command 0.18 · insert 3.13 · query 16.7 · update 3 (total 23.01) |

## Multi-agent group discussions

Each discussion: every member speaks once per round, then the moderator synthesises (docs/group-conversations.md). A member turn is one full pipeline run of that member's agent.

| Style | Members | Rounds | Concurrent discussions | Mock delay ms | Discussions OK | Member turns | Member turns/s | Turns per discussion | Discussion p50 ms | Discussion p95 ms | Failed | DB ops per member turn |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| ROUND_TABLE | 3 | 1 | 20 | 500 | 60 | 240 | 16.0 | 4.0 | 3893 | 4368 | 0 | command 1.8 · insert 5.5 · query 23.12 · update 3.25 (total 33.67) |
| ROUND_TABLE | 5 | 1 | 10 | 500 | 30 | 180 | 11.0 | 6.0 | 3960 | 5806 | 0 | command 1.76 · insert 5.33 · query 22.56 · update 2.83 (total 32.48) |

⚠ Measured while another load test ran on the same machine (eddi-loadtest-cluster-k6-run-38d602437c57, eddi-loadtest-cluster-k6-run-520999c6c0c1, eddi-loadtest-cluster-k6-run-6708e9787bf8, eddi-loadtest-cluster-k6-run-7a8192ccc751, eddi-loadtest-hotpath-k6-run-79d6fb9e2416): those rows share the CPU with it.

## Reproduce

From the repository root, with Docker and Python 3.10+:

```bash
export COMPOSE_PROJECT_NAME=eddi-loadtest-bench
export LOADTEST_EDDI_PORT=7470
export LOADTEST_LB_PORT=7480
export LOADTEST_MONGO_PORT=27470
export LOADTEST_POSTGRES_PORT=54470
export LOADTEST_WIREMOCK_PORT=18470
./mvnw package -DskipTests -DskipUi=true
python scripts/loadtest/loadtest.py up
python scripts/loadtest/loadtest.py run --label laptop-2026-10-17 --vus 200 --turns 10 --warmup-turns 2 --delay-ms 1000
python scripts/loadtest/loadtest.py group --label laptop-2026-10-17 --members 3 --vus 20 --discussions 3 --delay-ms 500
python scripts/loadtest/loadtest.py group --label laptop-2026-10-17 --members 5 --vus 10 --discussions 3 --delay-ms 500
python scripts/loadtest/loadtest.py down
LOADTEST_EDDI_MEMORY=1600m LOADTEST_JAVA_OPTS=-Xmx1g python scripts/loadtest/loadtest.py up --nodes 1 --cpus-per-node 2
LOADTEST_EDDI_MEMORY=1600m LOADTEST_JAVA_OPTS=-Xmx1g python scripts/loadtest/loadtest.py scaling --label laptop-2026-10-17 --nodes 1,2,4 --vus 200 --turns 15 --delay-ms 0
python scripts/loadtest/loadtest.py down
python scripts/loadtest/loadtest.py up --db postgres
python scripts/loadtest/loadtest.py run --label laptop-2026-10-17 --vus 50 --turns 10 --warmup-turns 2 --delay-ms 0
python scripts/loadtest/loadtest.py down
python scripts/loadtest/loadtest.py up
python scripts/loadtest/loadtest.py stream --label laptop-2026-10-17 --vus 100 --turns 5 --first-chunk-ms 500
python scripts/loadtest/loadtest.py run --label laptop-2026-10-17 --vus 50 --turns 20 --warmup-turns 2 --delay-ms 0
python scripts/loadtest/loadtest.py down
```

Raw results: `scripts/loadtest/results/laptop-2026-10-17-*.json` (not committed). Report: `python scripts/loadtest/loadtest.py report --label laptop-2026-10-17 --laptop`.
