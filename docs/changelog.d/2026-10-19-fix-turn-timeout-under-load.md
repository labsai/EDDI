## fix(engine): the turn timeout and `/q/metrics` hold under overload (2026-10-19)

**Repo:** EDDI · **Branch:** `fix/turn-timeout-under-load`

### What changed and why

The collapse investigation of 2026-10-19 (PR #1098) found three overload behaviours in a 5,000-user
burst against a node held to 2 CPUs: turns answered `200` after a median of 113 s although the REST
timeout is 60 s, `/q/metrics` silent for about 110 s, and queued turns that still ran after their caller
had been answered `408`. A reproduction with per-request timing
([results note](../../scripts/loadtest/results/overload-timeouts-2026-10-19.md)) traced the first two to
one queue. `say` and the Prometheus endpoint ran on the Quarkus worker pool (200 threads), and in the
burst every one of those threads was busy with conversation starts waiting on MongoDB. A turn request
waited up to 79 s for a thread before its 60 s timeout was even armed, a finished turn waited up to 37 s
more for a thread to write its answer, and the scrape queued behind both. The timeout timer itself fired
on time.

- [`RequestArrivalStamp`](../../src/main/java/ai/labs/eddi/engine/internal/RequestArrivalStamp.java):
  the router's first route records when each request arrived.
  [`TurnDeadlineHeaderReader`](../../src/main/java/ai/labs/eddi/engine/internal/TurnDeadlineHeaderReader.java)
  puts it on the turn's `InputData`.
- [`RestAgentEngine`](../../src/main/java/ai/labs/eddi/engine/internal/RestAgentEngine.java): the `408`
  fires at `systemRuntime.agentTimeoutInSeconds` after **arrival**. A request that has already used the
  whole budget is answered `408` at once and queues nothing (`eddi_turn_rejected_total{reason="caller_timed_out"}`).
  `say`, `sayWithinContext` and `rerun` are `@RunOnVirtualThread`, which also takes the response write
  off the worker pool. A database pool-wait timeout on a turn or a start is `503 capacity_exceeded` with
  `Retry-After: 5` instead of a `500`
  ([`ConnectionWaitTimeouts`](../../src/main/java/ai/labs/eddi/datastore/ConnectionWaitTimeouts.java)).
- [`ConversationService`](../../src/main/java/ai/labs/eddi/engine/internal/ConversationService.java) and
  [`ConversationStepRunner`](../../src/main/java/ai/labs/eddi/engine/internal/ConversationStepRunner.java):
  the turn carries when its caller gives up. A turn still queued then is dropped before any read, its input
  unconsumed (`eddi.conversation.skip-turns-of-timed-out-callers`, default `true`,
  `eddi_turn_skipped_total{reason="caller_timed_out"}`). The caller is told through the new
  `ConversationResponseHandler.onCallerGone` (REST: `408`), and an idempotency key is released, so a
  retry runs the turn. A turn deadline (`turnDeadlineMs`, `X-EDDI-Turn-Deadline-Ms`) now counts from the
  request's arrival too.
- [`MetricsScrapeRoute`](../../src/main/java/ai/labs/eddi/engine/internal/MetricsScrapeRoute.java):
  `GET`/`HEAD /q/metrics` is answered on two threads of its own, by a route at order −10: after the
  security filters (−200, −100), so the metrics permission still applies, and before the `/q` sub-router
  (`eddi.metrics.dedicated-scrape-threads`, default `true`).
- Docs: `conversations.md` (when a turn is answered `408` and whether it ran), `configuration-reference.md`
  (both properties), `metrics.md` (the `caller_timed_out` reasons), `application.properties`.

### Measured

Interleaved on a contended laptop (host CPU 72–79 %; other sessions' builds ran throughout), two runs per
arm, burst of 5,000 users, limit 1,500:

- `/q/metrics`: 0 of 335 scrapes timed out, against 120 s blind in every baseline run.
- Slowest `200`: 60.4 s, against 94–105 s. Every `200` arrived within the caller's 60 s.
- `200`s within 60 s (estimated from percentiles): 24.7 and 27.9 per second, against 21.9 and 17.4. Fewer
  `200`s in all (3,297–3,734 against about 4,700), because the late answers became `408`s.

### Design decisions

- **Starts stay on the worker pool.** Measured with starts on virtual threads too, the node collapsed:
  0 turns, connections refused, event loops blocked for up to 60 s. Two carriers at 2 CPUs could not
  absorb thousands of CPU-heavy starts admitted at once. The worker pool is the start path's only
  concurrency limit. `AgentEngineVirtualThreadIT` pins both halves of this.
- **A caller-gone skip, not a default turn deadline.** A default `turnDeadlineMs` would change how every
  agent's retries and fallbacks spend their time. The skip only stops work whose answer cannot reach
  anyone, and only for callers that stamped when they give up (REST `say`). Streams, schedules and group
  members carry no such time and are untouched.
- **The scrape route replaces Quarkus' handler rather than moving it.** A management interface would
  still use the same worker pool for its blocking route. A route of our own ahead of Quarkus' keeps the
  path, the content negotiation and the permission, and can be switched off.

### Not done (follow-ups)

- A request that cannot be dispatched within 60 s (up to 123 s on two saturated carriers) still gets its
  `408` only when it runs. The remedy is earlier load shedding: an in-flight limit sized to CPU.
- Conversation starts have no concurrency bound of their own.
- The Platform Operator learns about the `408` / `caller_timed_out` behaviour once the "Turn capacity"
  section of PR #1091 lands; changing the same prompt here would be a revision-bump conflict.

```decision-log
| 2026-10-19 | The REST turn timeout counts from the request's arrival (first route of the router), and a turn still queued when its caller gave up is dropped unrun | Under a burst the worker-pool wait before the timeout was armed reached 79 s, and turns nobody waited for spent the model and the database | A default `turnDeadlineMs` for every agent; arming the timeout on a Vert.x timer at arrival, which races the resource method's own response |
| 2026-10-19 | `say`/`rerun` run on virtual threads, conversation starts stay on the worker pool | Starts on virtual threads collapsed a 2-CPU node (event loops starved); the pool is their only concurrency bound | All agent endpoints on virtual threads; a new start-concurrency limit (follow-up) |
| 2026-10-19 | `/q/metrics` is served by an EDDI route on two dedicated threads, ahead of Quarkus' blocking handler | The blocking route shares the worker pool and went blind for two minutes in every overloaded run | The Quarkus management interface, whose blocking routes use the same pool |
```
