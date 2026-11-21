# Long Jobs: the Conversation as the Job Log

A job of 150 model calls over 20 minutes is not one turn. The per-request deadline header is
capped at 10 minutes, a scheduled fire waits at most `eddi.schedule.fire-timeout` (5 minutes),
and the turn is EDDI's unit of tracing, persistence, audit and recovery: a turn that dies takes
its work with it. So EDDI does not run long jobs in a workflow engine. It runs them as **many
ordinary turns of one conversation**, and the conversation is the job's log:

- every phase is a turn — traced, audited, priced, persisted, visible in the Manager;
- the job's state is the conversation's properties and the previous step's actions, which
  behaviour rules route on, so the job is a state machine written in configuration;
- a crash loses at most the turn that was running. A *continuation* turn is retried by its
  schedule, and a turn that had decided to continue when its node died is picked up by the
  [recovery sweep](#crash-recovery). The first turn of a job — the one a caller sent — has no
  schedule behind it: if it never committed, the caller sends it again; if it committed its
  decision and only the response was lost, the sweep starts the chain and there is nothing to
  resend. When the caller cannot tell which happened, it resends with the **same**
  `Idempotency-Key` it sent the first time, which returns the stored answer of a turn that already
  committed (within `eddi.turns.idempotency.ttl-seconds`, see
  [Conversations](conversations.md#idempotent-turns)) instead of running it twice.

What makes this possible is **self-continuation**: a turn can ask for the next turn of the same
conversation, and the engine starts it without user input.

## Self-continuation

### Switch it on

Self-continuation is off unless the agent opts in. Two agent-level fields:

```json
{
  "workflows": ["eddi://ai.labs.workflow/workflowstore/workflows/<id>?version=1"],
  "maxContinuations": 20,
  "continuationInput": "continue",
  "turnDeadlineMs": 120000,
  "maxCostPerConversationUsd": 2.0
}
```

| Field | Type | Default | Description |
|---|---|---|---|
| `maxContinuations` | integer `0`..`10000` | unset (= `0`, off) | How many turns one conversation may start itself, **counted over the whole conversation**. With `0` the action is ignored with a WARN, the step records `continuation:refused = disabled`, and `eddi_continuation_refused_total{reason="disabled"}` counts it. |
| `continuationInput` | string, ≤ 4096 characters | empty | The user input of every continuation turn. A literal written by the agent designer — never a template, never runtime data. Set it when an LLM task builds its user message from the input: some providers refuse an empty one. |

Both are validated when the agent is saved and on ZIP import: an out-of-range value is a `400`.
In the Manager they are under **Limits → Self-continuation**.

### Ask for the next turn

A behaviour rule emits the reserved action `CONTINUE_CONVERSATION`. Here a three-phase job —
draft, review, publish — keyed on the previous step's action:

```json
{
  "behaviorGroups": [
    { "name": "start", "behaviorRules": [ {
        "name": "start the job",
        "actions": ["draft", "CONTINUE_CONVERSATION"],
        "conditions": [
          { "type": "actionmatcher", "configs": { "actions": "CONVERSATION_START", "occurrence": "lastStep" } },
          { "type": "inputmatcher", "configs": { "expressions": "start_job", "occurrence": "currentStep" } }
        ] } ] },
    { "name": "review", "behaviorRules": [ {
        "name": "review the draft",
        "actions": ["review", "CONTINUE_CONVERSATION"],
        "conditions": [
          { "type": "actionmatcher", "configs": { "actions": "draft", "occurrence": "lastStep" } }
        ] } ] },
    { "name": "publish", "behaviorRules": [ {
        "name": "publish the result",
        "actions": ["publish"],
        "conditions": [
          { "type": "actionmatcher", "configs": { "actions": "review", "occurrence": "lastStep" } }
        ] } ] }
  ]
}
```

`draft`, `review` and `publish` trigger the tasks of each phase (an LLM task, an `httpcalls`
call, …) like any action. The last phase emits no `CONTINUE_CONVERSATION`, so the job stops.
For loops — "generate, check, regenerate until the check passes" — keep the phase in a
`conversation`-scoped property and route on it with a `dynamicvaluematcher`
(`"valuePath": "properties.phase"`); the loop is bounded by
`maxContinuations` and the cost ceiling either way.

`CONTINUE_CONVERSATION` is a reserved action like `PAUSE_CONVERSATION`: do not use it as a quick
reply expression.

### What a continuation turn sees

| Where | What |
|---|---|
| Input | `continuationInput` (or empty) |
| `{context.selfContinuation.number}` | Which continuation this is: `1`, `2`, … over the conversation |
| `{context.selfContinuation.max}` | The agent's `maxContinuations` when it was armed |
| Caller identity | None, as on every scheduled turn: `${caller:token}` fails closed |

`selfContinuation` is an [engine-reserved context key](passing-context-information.md#reserved-context-keys):
EDDI drops it from client input, so a client cannot make a turn look like a continuation. A rule
can tell a continuation from a user turn with a `contextmatcher` on `selfContinuation`.

## Bounds

| Bound | How it applies |
|---|---|
| `maxContinuations` | Counted per conversation: the number of steps the engine marked as continuation turns. Once reached, a further `CONTINUE_CONVERSATION` is refused (`limit`) and the step says so. A job that needs more starts a new conversation. |
| `turnDeadlineMs` | Every continuation is an ordinary turn under the agent's deadline. Keep it below `eddi.schedule.fire-timeout` (5 minutes): a turn still running at the fire timeout is recorded as a failed fire and retried, and the retry finds the turn's result once it has committed. |
| `maxCostPerConversationUsd` | It sums `audit:cost` over every step, so it bounds all continuation turns together. A continuation is refused before it is armed once the ceiling is reached (`cost_ceiling`); inside a turn, every model call checks it as usual. |
| Tenant quotas | Each continuation turn takes an API-call slot and is charged to the tenant's monthly cost budget like any turn. |
| Latency | One continuation starts at the next schedule poll after its predecessor committed: up to `eddi.schedule.poll-interval` (15 s) between two turns. |

## How it is delivered

A continuation is decided before the asking turn is persisted — the outcome is part of the
committed step: `continuation:next` (the number scheduled) or `continuation:refused` (why not) —
and armed only **after** the commit, as a one-shot schedule due immediately. A turn that paused,
failed, was cancelled, lost its cluster lease or could not be stored arms nothing. A schedule write
that fails is retried a few times on the spot; one that keeps failing is counted
(`schedule_failed`) and logged. With the recovery sweep on and the decision stored, it is left to
the sweep and the job carries on. Otherwise — the sweep switched off, or no stored decision it could
arm — the decision is retired and the job ends there, with `job.finished` (`schedule_failed`) for
its owner. A decision that cannot even be retired is not announced as ended, since a sweep could
still arm it; the failure is logged instead.

### Crash recovery

A node that dies in the moment between committing a turn and writing its schedule — or a schedule
write that keeps failing — would leave a committed `continuation:next` with no schedule, and the job
would stop there. It does not, because the decision is written as an **outbox record** in the same
document write as the step: the stored conversation carries a top-level `pendingContinuation`
(the continuation's number, the agent's `maxContinuations` and `continuationInput` at the time,
the environment, when it was decided and the decision's own id) for as long as its latest step
decided to continue. The step stores the same decision id as `continuation:decision`, and the record
is carried only while the two match — so an undo back to an earlier step never carries the undone
turn's decision along. The next committed turn replaces or removes it, because it is derived from
that turn's own step.

A **recovery sweep** runs on every node every `eddi.continuation.recovery.interval` (1 minute). It
reads the records older than `eddi.continuation.recovery.grace-period` (1 minute — by then the
turn's own arm, which runs milliseconds after the commit, has long happened or never will), oldest
first and at most `eddi.continuation.recovery.batch-size` (100) per pass, from an index that holds
only those records — never the rest of the conversations. A record whose recovery fails is left out
of the passes for ten minutes and the read reaches past it, so a record that fails on its own
cannot hold up the ones behind it. For each one:

| The conversation… | What the sweep does |
|---|---|
| is READY and its continuation has no schedule | Writes the schedule, as the turn would have, and marks the record handled. Counted as `eddi_continuation_recovered_total` and logged at WARN. |
| is READY and the schedule exists (the turn armed it) | Writes nothing; marks the record handled, which takes it out of the index. |
| is not READY — ended, paused for a human, interrupted | Arms nothing — an ended conversation is never revived — and removes the record (the one case where a sweep removes one). |
| was cancelled or ended, or ran a newer turn, while the sweep worked | Withdraws the schedule the sweep had just written. |

**Exactly once.** The schedule's id is derived from the conversation, the continuation number and
the decision id, and both stores write it as an insert-if-absent (MongoDB's unique `_id`,
PostgreSQL's primary key with `ON CONFLICT DO NOTHING`). So the turn's own arm, a retry of it and
any number of sweeping nodes write one schedule between them. A *later* decision of the same number
— a turn that decided again before the continuation ran, say after its fire found the conversation
paused — gets a schedule of its own instead of taking the earlier one's row for its own; the guards
under [Once only](#once-only) still run the number once. For a READY conversation, sweeps only ever
*mark* a record handled, matching its decision id; only a cancel, an end or a newer turn removes or
replaces it — so a sweep that finds its decision gone knows the job was stopped or moved on, not
that another sweep was faster. A cancel removes the record before it deletes the schedules, which
closes the window between the two. Cancelling a job whose arm was lost therefore answers
`200 CANCELLED`, as for any job between two turns; if the record itself cannot be removed, the
cancel answers `500` instead — the conversation is still READY and the sweep could arm the job
again — and is safe to repeat.

**What the sweep does not decide again.** `maxContinuations`, the cost ceiling and the group-member
rule were applied by the turn that decided; the sweep arms what it decided. Everything the fire
checks — a duplicate, the owner, an ended, paused, erased or restricted conversation, an undeployed
agent — applies to a recovered continuation exactly as to any other.

In a cluster, the sweep holds the `leader.continuation-recovery` lease while it runs, so one node
does the reads; correctness does not depend on it. `eddi.continuation.recovery.enabled=false`
switches the sweep off; the records are still written, and a sweep switched back on later arms
whatever is left — except a decision whose schedule write failed while it was off, which was
retired when its owner was told the job had ended.

A schedule rather than the conversation coordinator's queue, because the queue lives in memory and
dies with its node. The schedule row survives a restart; exactly one node claims it
(compare-and-set, lease, fencing token); a failed fire is retried with the schedule backoff (15 s,
1 min, 4 min, 16 min) and then dead-lettered, with every attempt in the fire log. And the turn it
starts still runs through `say`: the coordinator orders it behind any other turn of the
conversation, in cluster mode under the [conversation lease](clustering.md), with quotas, audit
and the turn deadline.

### Only the engine arms a continuation

A continuation schedule names a conversation, and its fire runs a turn there. So the marker is
refused on every schedule write from outside (REST create, update and ZIP import answer `400`), and
a fire runs only when the schedule's `userId` — the owner the engine stamped when it armed it — is
the conversation's owner; anything else completes without a turn (`owner_mismatch`).

### Once only

Schedule delivery is at least once: a node that dies after the turn ran but before the fire was
recorded leaves a claim that another node steals. Two guards make a re-delivery harmless:

1. Every continuation turn marks its step with its number (`continuation:number`) before the step
   is persisted. A fire first looks for that marker in the stored conversation — every step, with
   no expiry — and completes without running anything when it is there (`duplicate`).
2. The turn carries an automatic `Idempotency-Key` (`eddi-continuation-<n>`). Two fires that both
   passed the first check meet in the [idempotent-turn](conversations.md#idempotent-turns)
   machinery; and when the conversation lease serialises them, the second one — rebuilt over the
   newer document — finds the first one's marker and is dropped without running. That last check
   does not depend on the `Idempotency-Key` feature being switched on.

### When a continuation does not run

| The conversation… | What happens |
|---|---|
| ended | Ending it through the API (`endConversation`) withdraws the pending continuation. A turn that ends it (`CONVERSATION_END`, `STOP_CONVERSATION`) arms nothing. A fire that still arrives completes without a turn (`ended`). |
| was cancelled | `POST /agents/{conversationId}/cancel` withdraws the pending continuation — between two turns of a job this now answers `200 CANCELLED` instead of `409`. A job that has finished has nothing pending, so its cancel still answers `409`. A continuation turn that is running is signalled as before, and an interrupted continuation turn stops the chain (`interrupted`). So does a cancel that reaches a turn before its successor is armed, or while it is: the turn queues nothing, or withdraws what it just wrote. The approver's paused-only cancel never touches it. |
| is paused for a human (`AWAITING_HUMAN`) | Not run (`awaiting_human`). A turn resumed from the pause may ask to continue again. |
| was deleted or its user erased (GDPR) | Not run (`gone`). The schedule carries the owner's `userId`, so an erasure deletes it with the user's other schedules. |
| is restricted (GDPR Art. 18) | Not run (`restricted`). |
| belongs to an undeployed agent | Never run on it. Undeploy disables the agent's schedules, this one included, and a redeploy re-enables it; a fire in between fails (`undeployed`) and is retried, then dead-lettered. |
| is a group member's private conversation | A member's turn cannot continue itself (`group_member`): the group orchestrates its members' turns. |

A continuation schedule cannot be fired by hand (`POST /schedulestore/schedules/{scheduleId}/fire`
answers `409`, the
MCP `fire_schedule_now` tool refuses it) and is never exported with its agent.

## Observing a job

- **The conversation** — every phase is a step: `GET /agents/{conversationId}?returnDetailed=true`
  shows `continuation:number`, `continuation:next` and `continuation:refused` on the steps.
- **The outbox record** — `pendingContinuation` on the stored conversation while its latest step
  decided to continue; once the recovery sweep has confirmed the schedule its `decidedAt` is gone.
- **Schedules** — the pending continuation is a schedule named `continuation-<conversationId>`,
  with its fire log (status, error, attempt) like any schedule. Spent continuations are pruned as the
  job runs: after a job only the last continuation's row is left, with its fire log.
- **The owner's event stream** — [`GET /me/events`](user-events.md) sends the conversation's owner
  `job.finished` when the last turn of a job ran (`state`: `done`, or why it stopped — `limit`,
  `cost_ceiling`, …) and `job.failed` when a continuation fire failed. Ids only. A schedule
  write that failed raises no `job.finished` while the recovery sweep will arm the stored decision
  (the sweep is on): the job is not over. With the sweep off, or with no stored decision it could
  arm, it raises `job.finished` with `schedule_failed`, after retiring the decision — and nothing
  at all if the decision cannot be retired.
- **Metrics** — `eddi_continuation_scheduled_total`, `eddi_continuation_run_total`,
  `eddi_continuation_refused_total{reason}` and `eddi_continuation_recovered_total`; see [Metrics](metrics.md#self-continuation-metrics).

## Waiting for an external operation

A long operation outside EDDI — a video render, a batch export — should not be polled inside a
turn: an `httpcalls` retry sleeps on the conversation's thread and caps each backoff at 30 seconds.
Until a turn can **park** on the operation (planned: the HITL pause machinery with a machine as
the resolver — a status poll bound to the pause, or a signed webhook that resolves it), poll from
continuation turns instead: one turn starts the operation and stores its id in a property, and
each following turn checks the status once and emits `CONTINUE_CONVERSATION` again until it is
done. Every check is a short, traced turn; `maxContinuations` bounds the number of checks and the
schedule poller checks for due continuations every `eddi.schedule.poll-interval` (15 s by default),
which does not guarantee a minimum delay between them: another node can pick a new continuation
up on its own next tick.

## See also

- [Conversations](conversations.md) — turns, the turn deadline, idempotent turns
- [Scheduled Execution & Heartbeats](scheduling.md) — the schedule machinery continuations use
- [Human-in-the-Loop](hitl.md) — pausing a turn for a human
- [Behavior Rules](behavior-rules.md) — routing on actions, properties and context
