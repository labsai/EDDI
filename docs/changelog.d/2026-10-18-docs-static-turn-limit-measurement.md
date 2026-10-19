## docs(loadtest): static, CPU-sized in-flight turn limit measured against `auto` — default unchanged (2026-10-18)

**Repo:** EDDI · **Branch:** `docs/static-turn-limit-measurement` (on top of `feat/turn-admission-hardening`)

### What changed and why

The adaptive limiter measured earlier the same day gained steady goodput while it held in-flight turns at
about 900–1,900. That suggested a static `eddi.conversation.max-in-flight-turns`, sized to CPU rather
than heap, might keep the gain without a controller. This was measured against the default `auto`
(2,602 at `-Xmx6g`) on 20 cores, with the benchmark suite's `--retry-503 10` clients (PR #1086).
Scenarios: 2,500 conversations × 10 turns, and a 5,000-user burst, both with a 3 s mock LLM. Each run
started from `down -v`, configurations were interleaved, and there were 2–7 uncontended rounds per
configuration (36 runs). The results are in a new section of
[`scripts/loadtest/results/admission-2026-10-17.md`](../../scripts/loadtest/results/admission-2026-10-17.md).
No code changes.

- **Steady:** 2000 and 1500 tie with `auto` (median 228–234 turns/s, p95 including backoff about
  20–22 s). 1000 and 600 abandon turns (45 and 619) without gaining throughput, and 600 loses a third.
  No static limit reproduces the adaptive limiter's 12–15 % gain.
- **Burst:** 2000 has the higher unpaired median (333 against 275 turns/s) and a narrower range
  (281–355 against 185–427). Paired by round it is a tie: three wins each, median difference +2.5
  turns/s. It also abandons turns (9 over six runs against 0 over seven) and has 3–4 s worse p95 and p99
  totals. 1500 and below abandon 70–2,000 turns per burst.

### Design decisions

- **No new default.** No candidate beats `auto` without raising abandoned turns, the same criterion the
  adaptive limiter failed. With closed-loop clients that honour `Retry-After`, a lower limit moves the
  waiting from EDDI's queue into client backoff while throughput stays where the CPU puts it.
- **About 2,000 is a deployment choice, not a default.** It is documented as an option for operators
  who want a predictable burst floor more than the best case.

### What's next

- Nothing on admission control. A CPU-aware limit would need EDDI's own share of each turn's latency,
  which EDDI cannot measure without timing every LLM call.
