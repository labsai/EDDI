## Scheduler keeps claiming due schedules while long fires run (2026-11-01)

**Repo:** EDDI, branch `fix/schedule-claim-cycle` (stacked on #1197)

- `SchedulePollerService.pollDueSchedules()` waited for its whole batch of fires, so with `concurrentExecution = SKIP` one fire running for minutes suppressed every 15-second poll on that instance; schedules that fell due meanwhile (HITL approval deadlines included) waited for the long fire. The poll is now a short claim cycle: it claims due schedules and hands each to a bounded fire executor, then returns.
- New `eddi.schedule.max-concurrent-fires` (default 100) caps fires in flight per instance. A poll takes a slot before claiming, so nothing is claimed that cannot start; the rest stay due.
- Each fire is bounded by its own watchdog (`fire-timeout` plus grace) instead of a shared batch deadline; a timed-out fire is interrupted, counted in `eddi.schedule.fire.timedout`, and gives its slot back even if it ignores the interrupt. The lease invariant (lease > fire-timeout + grace), dead-lettering, `failCount`, expired-claim-as-failed-attempt and the manual-fire path are unchanged.
- Graceful shutdown (`@PreDestroy`): stop claiming, let in-flight fires finish for 5 s, interrupt the rest.
- Tests: a long fire does not block the next poll from claiming another schedule; the in-flight cap holds and slots are returned (also after a lost claim); shutdown drains and cancels; the per-fire timeout frees its slot.
- Docs: `docs/scheduling.md`, `docs/configuration-reference.md`.
