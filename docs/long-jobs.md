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
  schedule; the first turn of a job — the one a caller sent — has no schedule behind it, so the
  caller sends it again (an `Idempotency-Key` makes that safe).

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
(`schedule_failed`) and logged.

**The one window that is not recovered automatically.** A node that dies in the moment between
committing a turn and writing its schedule, or a schedule write that keeps failing, leaves a
committed step with `continuation:next` and no schedule: the job stops there. Nothing re-arms it
yet. Watch `eddi_continuation_refused_total{reason="schedule_failed"}`, and for a job that stopped
without a refusal look at its last step: `continuation:next` without a later continuation turn is
this case. To pick the job back up, send the conversation a message whose turn ends in
`CONTINUE_CONVERSATION` again: a message starts one new turn, and only a turn that emits the
action arms the next schedule.

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
- **Schedules** — the pending continuation is a schedule named `continuation-<conversationId>`,
  with its fire log (status, error, attempt) like any schedule. Spent continuations are pruned as the
  job runs: after a job only the last continuation's row is left, with its fire log.
- **Metrics** — `eddi_continuation_scheduled_total`, `eddi_continuation_run_total` and
  `eddi_continuation_refused_total{reason}`; see [Metrics](metrics.md#self-continuation-metrics).

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
