## feat(loadtest): turn load-test harness and the throughput baseline (2026-10-15)

**Repo:** EDDI · **Branch:** `feat/throughput-loadtest-harness`

### What changed and why

WS0 of the [throughput plan](../../planning/throughput-optimization-plan.md): every later workstream
must show before/after numbers, and until now there was nothing to measure with.

- **`scripts/loadtest/`** (new). A compose stack (MongoDB, a WireMock OpenAI-compatible LLM, EDDI,
  k6) with everything bound to `127.0.0.1`, plus `loadtest.py`, which uses only the standard library.
  `up` mounts this checkout's `target/quarkus-app` into the published image, the way
  `scripts/cluster-demo` does, and creates one deployed LLM agent through
  `POST /administration/agents/setup`. `run` and `matrix` set the mock's latency through WireMock's
  admin API, run k6 (scenarios `steady`, `burst`, `same-conversation`, `long-conversation`), sample the
  saturation gauges every second, and write turns/s, latency percentiles, errors by status, peak
  executor, coordinator, audit and pool gauges, and MongoDB operations per turn (`opcounters` and `top`
  per collection).
- **`scripts/loadtest/results/baseline-2026-10-15.md`**. Measured on `aa811c838` at 3 s LLM delay:
  50 and 200 concurrent conversations run at the LLM's pace. At 500, throughput flattens at about
  61 turns/s with the managed pool pinned at 199 active and up to 198 queued, and p50 rises from 3.1 s
  to 7.5 s. No request is rejected: overload shows up as latency. Each turn costs about 23 MongoDB
  operations, independent of load.

### Design decisions

- **One Python driver instead of the plan's `setup.sh` / `collect.sh`.** It matches the cluster demo
  and runs the same way from PowerShell and Git Bash.
- **No new meter.** The existing executor gauges explain the latency on their own, so the plan's
  optional `eddi.turn.queue_wait` timer is not added.
- **WireMock with async responses** and no request journal, so the mock is not the bottleneck at
  hundreds of concurrent delayed calls.

### Next

WS1 (configuration cache): re-measure with `--think-ms 3000`, because back-to-back turns hide the 2 s
TTL. WS2 (thread ceiling and admission). Unexplained so far: about 3 vault reads per turn.
