## docs(loadtest): adaptive turn limit judged with Retry-After-honouring clients — dropped (2026-10-18)

**Repo:** EDDI · **Branch:** `docs/adaptive-limit-retry-measurement` (on top of `feat/turn-admission-hardening`)

### What changed and why

The adaptive turn limit parked on 2026-10-17 lowers `eddi.conversation.max-in-flight-turns` while the
mean MongoDB pool wait exceeds 100 ms. It had been judged with k6 clients that ignore `Retry-After`. It
has now been measured again with the benchmark suite's `--retry-503 10` clients (PR #1086): limiter off
against on, `down -v` before every run, 2,500 conversations × 10 turns and a 5,000-user burst, 3 s mock
LLM, three rounds. The results and the method are in
[`scripts/loadtest/results/admission-2026-10-17.md`](../../scripts/loadtest/results/admission-2026-10-17.md).
No code changes.

- **Not shipped.** On a quiet machine the limiter gained 12–15 % goodput in the steady scenario, cut its
  p99 and left the p95 even. But it abandoned turns in every burst run (603 and 102) and in the
  contended steady run (1,464), against 0 with it off. That fails the ship criterion: the limiter must
  beat "off" without raising abandoned turns.
- **The pool-wait signal does not track database saturation.** A per-second sampler put it next to
  mongod `opcounters`, `globalLock` queue and CPU. Pool wait correlated negatively with mongod ops/s in
  nine of ten runs. The wait was high while mongod ran at a fraction of its peak, with free pooled
  connections and an empty lock queue. It measures CPU contention in the EDDI JVM, which is why another
  stack's load test drove the limit down to 92. `mongodb_driver_pool_waitqueuesize` moves with it, so it
  is no better.

### Design decisions

- No replacement signal was built. A signal that really measured MongoDB saturation would not have
  fired in these runs (mongod stayed far below its peak), which is the same as "off". The steady gain
  points at CPU instead: a static `max-in-flight-turns` sized to CPU, rather than to heap, is the next
  thing to measure.

### What's next

- Delete the local branch `wip/adaptive-turn-limit` (commit `63659ba18`) once the user agrees.
- Measure a CPU-sized static in-flight limit against `auto` with the same retrying clients.
