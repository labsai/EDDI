# Turn load test

The measurement harness of the [throughput plan](../../planning/throughput-optimization-plan.md) (WS0):
a reproducible local run that reports turns/s, latency percentiles, executor saturation and MongoDB
operations per turn, so every later change can show before/after numbers.

```
k6 (VUs = concurrent conversations) ──► EDDI :7370 ──► WireMock "LLM" :18370 (fixed delay, set per run)
                                          │
                                       MongoDB :27370
```

EDDI runs the **published** `labsai/eddi` image with this checkout's `target/quarkus-app` mounted over
`/deployments` (the [cluster demo](../cluster-demo/README.md) does the same), so the numbers belong to
this branch. Everything binds to `127.0.0.1`; EDDI runs unauthenticated, as in the README quick start.

## Run it

Needs Docker and Python 3.10+. Pulls `labsai/eddi:6.5.0`, `mongo:7.0.43`, `wiremock/wiremock:3.9.1`
and `grafana/k6:0.54.0`.

```bash
./mvnw package -DskipTests -DskipUi=true
python scripts/loadtest/loadtest.py up                 # stack + one deployed LLM agent
python scripts/loadtest/loadtest.py matrix --label baseline --vus 50,200,500 --delay-ms 3000
python scripts/loadtest/loadtest.py run --scenario long-conversation --vus 5 --turns 200 --delay-ms 0 --label baseline
python scripts/loadtest/loadtest.py down
```

`up --no-app` measures the image as published; `up --image labsai/eddi:<tag>` picks another runtime.
Ports move with `LOADTEST_EDDI_PORT`, `LOADTEST_WIREMOCK_PORT`, `LOADTEST_MONGO_PORT`; the heap with
`LOADTEST_JAVA_OPTS` (default `-Xmx2g`) and the container limit with `LOADTEST_EDDI_MEMORY` (default `3g`).

## Scenarios

| `--scenario` | Shape | Exposes |
|---|---|---|
| `steady` | `--vus` conversations, one per VU, `--turns` turns each, back to back | The concurrent-turn ceiling (WS2) |
| `burst` | Ramp to `--vus` over `--ramp`, hold for `--hold` | Admission behaviour: 503s vs. timeouts (WS2, WS4) |
| `same-conversation` | `--vus` callers on one conversation | Per-conversation queueing (WS4) |
| `long-conversation` | A few conversations of 200 turns | Costs that grow with conversation length (WS3, WS6) |

Streaming is not covered: k6 has no built-in SSE client.

## What a run records

`run` writes `results/<label>-<scenario>-<vus>vu-<delay>ms.json`; `matrix` adds the Markdown report
`results/<label>-<scenario>-<date>.md`. Only the reports are committed.

- **Turns/s and latency:** p50/p90/p95/p99/max of successful turns, as k6 measured them.
- **Errors by status:** 503 (`capacity_exceeded`), 429, 408, 409, other 5xx, no response, failed starts.
- **Peaks**, sampled from `/q/metrics` every second: `eddi_runtime_executor_{active,queued,max_threads}{pool}`
  (`queued{pool="managed"} > 0` is the thread-ceiling signal), `eddi_processing_conversation_count`,
  coordinator queue depth and active conversations, the audit queue, JVM live threads, MongoDB pool.
- **Counters** before/after: dropped audit entries, dead letters, idempotency outcomes, circuit breaker, HTTP requests.
- **MongoDB:** `serverStatus().opcounters` and `top` per collection, divided by successful turns — this is
  what tells conversation loads from configuration reads.

The numbers include the run's conversation starts and everything else EDDI does meanwhile (schedulers,
retention); with no other traffic that noise is small against hundreds of turns.
