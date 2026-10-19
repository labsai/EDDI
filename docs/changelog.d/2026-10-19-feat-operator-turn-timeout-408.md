## feat(operator): the Platform Operator explains 408 Request Timeout and `caller_timed_out` (2026-10-19)

**Repo:** EDDI · **Branch:** `feat/operator-turn-timeout-408`

### What changed and why

PR #1101 changed what a `408` means under load, and left telling the Platform Operator for later,
because the "Turn capacity" prompt section of #1091 had not landed yet. Without this an admin asking
"why do my users get 408?" or "what is `eddi_turn_skipped_total`?" got an answer from the old
behaviour, or none. AGENTS.md §4.6 asks for this check whenever behaviour changes.

- [`system-prompt.ts`](../../ui/manager/src/lib/operator/system-prompt.ts), "Turn capacity":
  - A fourth `capacity_exceeded` cause: the database pool-wait timeout ("The database is busy: no
    connection was free in time"), now a `503` with `Retry-After` instead of a `500`.
  - How a `408` differs from a `503`: the turn was accepted but its caller's time
    (`systemRuntime.agentTimeoutInSeconds`, counted from the request's **arrival**) ran out. The
    prompt gives the three cases and whether the turn ran in each: rejected before queueing
    (`eddi_turn_rejected_total{reason="caller_timed_out"}`), dropped unrun from the queue with its
    input unconsumed (`eddi_turn_skipped_total{reason="caller_timed_out"}`,
    `eddi.conversation.skip-turns-of-timed-out-callers`; only then is a retry exactly-once, and with
    the setting off the queued turn still runs), or already started, whose outcome is unknown at the
    `408` because the execution watchdog times from the turn's start, not the request's arrival.
  - What a rising `caller_timed_out` means: the node is overloaded. The fix is more nodes or a lower
    `max-in-flight-turns`, which turns a 60 s wait into a fast `503`. Raising the timeout does not
    help. Flat counters do not prove health: a turn that queued until just before its deadline and
    then started is counted by neither, so compare wait-before-start with run time.
  - `/q/metrics` answers on dedicated threads (`eddi.metrics.dedicated-scrape-threads`).
  - The logs section: pool-wait refusals are WARN lines, and both `408` paths log at DEBUG only, so
    the logs do not show them.
- No allow-list change. The meters are at `/q/metrics`, which is not in the OpenAPI spec and so
  cannot become a tool. The prompt already says the operator cannot read them.
- [`operator-revision.json`](../../ui/manager/src/lib/operator/operator-revision.json): revision bump,
  so existing deployments are offered the upgrade.
- [`system-prompt.test.ts`](../../ui/manager/src/lib/operator/__tests__/system-prompt.test.ts): pins
  the pool-wait message, the 408/503 distinction, both counters, the "did it run" sentence, the
  overload advice and the DEBUG-only logging note.
