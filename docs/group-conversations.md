# Group Conversations

> Multi-agent structured discussions with moderator synthesis.

## Overview

Group Conversations enable multiple agents to discuss a question. Each agent participates through its normal pipeline — agents are group-unaware by default. A `GroupConversationService` orchestrates the discussion through configurable phases.

## Discussion Styles

| Style | Flow | Best For |
|---|---|---|
| `ROUND_TABLE` | Opinion × N → Synthesis | Brainstorming, open-ended exploration |
| `PEER_REVIEW` | Opinion → Critique → Revision → Synthesis | Code review, document review |
| `DEVIL_ADVOCATE` | Opinion → Challenge → Defense → Synthesis | Risk assessment, stress-testing |
| `DELPHI` | Anonymous rounds → convergence → Synthesis | Forecasting, reducing groupthink |
| `DEBATE` | Pro → Con → Rebuttals → Judge | Trade-off analysis, comparisons |
| `TASK_FORCE` | Plan → Execute → Verify → Synthesis | Structured task decomposition, parallel execution |
| `NEGOTIATION` | Positions → Proposals → Bargaining → (Arbitration) → Synthesis | Surfacing trade-offs, drafting compromises |
| `CUSTOM` | Define your own phases | Any workflow |

## Quick Start (MCP)

```

# 1. Discover available styles
describe_discussion_styles

# 2. Create a group
create_group(
  name="Architecture Review",
  memberAgentIds="expert-1,expert-2,expert-3",
  memberDisplayNames="Backend Expert,Frontend Expert,DevOps Expert",
  moderatorAgentId="moderator-agent",
  style="PEER_REVIEW"
)

# 3. Run a discussion
discuss_with_group(groupId="<id>", question="Should we use microservices?")
```

## Quick Start (REST)

```bash

# Create group config
curl -X POST /groupstore/groups \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Architecture Panel",
    "members": [
      {"agentId": "expert-1", "displayName": "Backend Expert", "speakingOrder": 1},
      {"agentId": "expert-2", "displayName": "Frontend Expert", "speakingOrder": 2}
    ],
    "moderatorAgentId": "moderator-agent",
    "style": "ROUND_TABLE",
    "maxRounds": 2
  }'

# Start discussion
curl -X POST /groups/<groupId>/conversations \
  -H "Content-Type: application/json" \
  -d '{"input": "What is the best architecture for our new service?"}'
```

## Member Roles

Some styles require specific roles:

| Role | Used By | Purpose |
|---|---|---|
| `DEVIL_ADVOCATE` | DEVIL_ADVOCATE style | Argues against consensus |
| `PRO` | DEBATE style | Argues in favor |
| `CON` | DEBATE style | Argues against |

```
create_group(
  name="Debate Panel",
  memberAgentIds="agent-a,agent-b",
  memberRoles="PRO,CON",
  moderatorAgentId="judge-agent",
  style="DEBATE"
)
```

### Debate verdicts

When a DEBATE has **two sides and an impartial judge** — i.e. members carry at
least two distinct roles *and* `moderatorAgentId` names an agent that is not one
of the debaters — the judgment phase returns a structured verdict alongside its
prose, on the conversation's `decision` field:

```json
"decision": {
  "type": "VERDICT",
  "winner": "CON",
  "tally": { "PRO": 4.0, "CON": 9.0 },
  "outcome": "CON wins (PRO 4/10, CON 9/10) — PRO asserted; CON cited.",
  "method": "debate-judgment",
  "decidedAtPhase": "Judgment",
  "dissents": []
}
```

`winner` is `null` for a tie (the tie is stated in `outcome`). The judge is asked
to score argument quality and factual support, and explicitly *not* assertiveness
or fluency.

Any of these leaves `decision` unset and the conclusion as ordinary prose — none
of them is an error:

- **No roles on the members.** Nothing argued PRO, so a PRO-vs-CON score would be
  invented rather than measured.
- **No moderator.** The stand-in synthesizer is one of the debaters, and a
  partisan's call is not the group's finding.
- **A phase `inputTemplate` of your own.** Your instruction wins; set one if you
  want a debate to conclude in plain prose.
- **A judgment the parser cannot read.** The prose conclusion is kept as-is.

## Agent-filed tasks

Work an agent *discovers* mid-discussion — a missing migration, an untested
edge case — would otherwise die in prose: the shared task list is written only
by the PLAN phase and by config. Turning on `taskListConfig` gives members two
tools, `addGroupTask` and `listGroupTasks`, and a filed task is picked up by the
next execution wave with no other changes.

```json
"taskListConfig": {
  "allowAgentTaskCreation": true,
  "maxAgentAddedTasksPerDiscussion": 20,
  "maxPerTurn": 3
}
```

**Off by default, and absent rather than refusing when off.** A tool that is not
assembled costs no prompt tokens and cannot be argued with; one that exists and
always says no invites retries. The tools appear only when the turn belongs to a
live group discussion whose config sets `allowAgentTaskCreation: true` — a
standalone agent, a paused discussion, or an unreadable config all yield no
tools.

`addGroupTask(subject, description, dependsOnSubjects?, priority?, assignToRole?)`

- **`dependsOnSubjects`** names other tasks by their *subject*, as
  `listGroupTasks` prints them. An unknown name is refused, not dropped —
  silently filing a task without its dependency schedules it immediately, which
  is the opposite of what was asked.
- **`assignToRole`** takes `"ROLE:Reviewer"` or a member's exact name, and files
  the task already assigned. Omitting it (or passing `"ALL"`) leaves the task
  for the wave loop to assign, as it does any other. An unmatched role is
  refused with the available roles named.
- Refusals are sentences aimed at the model: duplicate subject, unknown
  dependency, circular dependency, subject over 200 chars, description over
  4,000, and either cap being reached.

Both caps are enforced independently: `maxPerTurn` bounds a runaway single turn,
`maxAgentAddedTasksPerDiscussion` bounds slow drift across a long discussion. The
discussion cap counts only agent-filed tasks, so a large planned backlog does not
exhaust it. A rejected call does not consume the per-turn budget.

**No claim or complete tools.** The wave loop owns every task-state transition;
a second writer racing it would corrupt the state machine that decides what runs
next. Filing is the only agent-side write.


## Transcript windowing

A `FULL`- or `ANONYMOUS`-scope discussion phase re-feeds the transcript to every
member every turn, so a long discussion grows roughly quadratically in prompt
tokens. `contextWindow` bounds what each turn *renders* — the stored transcript
is never modified, and signing verification still runs on the raw entries:

```json
"contextWindow": {
  "enabled": true,
  "maxRecentEntries": 30,
  "summarizeOverflow": true,
  "llmProvider": "openai",
  "llmModel": "gpt-4o-mini",
  "inputPricePer1M": 0.15,
  "outputPricePer1M": 0.60
}
```

When the scope-filtered context exceeds `maxRecentEntries`, the older entries
collapse into a single leading block: a rolling **summary** (extended
incrementally at phase boundaries via the shared summarization service — never
per member turn), or, with `summarizeOverflow: false`, a plain
`[n earlier entries omitted]` marker that costs no LLM call. A summarization
failure never blocks the discussion — the turn renders with the truncation
marker and the next boundary catches up.

- Applies to `FULL` and `ANONYMOUS` scopes only. The `ANONYMOUS` variant keeps
  its own summary, built from the same `"Anonymous"` labels the scope filter
  produces — the summary can never de-anonymize a peer.
- `llmProvider`/`llmModel` name the summarizer; without them, overflow falls
  back to the truncation marker (a save-time warning says so).
- The optional prices attribute the summarizer's own spend to the discussion's
  cost ledger, where `maxCostPerDiscussion` sees it.
- The convergence judge's input is not windowed — it is already bounded to the
  last two rounds.


## Voting

A `VOTE` phase collects **explicit ballots** instead of another round of prose.
LLM ballots are correlated (shared priors, sycophancy) — the durable value is
the auditable artifact: the weighted tally, every raw ballot, and the losing
side's statements recorded as dissents on the `DecisionRecord`.

```json
{
  "name": "Ballot", "type": "VOTE",
  "turnOrder": "PARALLEL", "contextScope": "NONE",
  "voteConfig": {
    "method": "MAJORITY",
    "optionsSource": "EXPLICIT",
    "options": ["Adopt PostgreSQL", "Stay on MongoDB"],
    "quorum": 0.5,
    "weights": { "senior-architect": 2.0 },
    "weightByConfidence": false,
    "tiePolicy": "MODERATOR_DECIDES"
  }
}
```

**Independence is enforced structurally, not advised.** Save-time validation
rejects a VOTE phase that is not `PARALLEL` + `contextScope: NONE`; ballots are
cast blind against the pre-fan-out snapshot, and `VOTE` entries stay
peer-hidden until their phase completes (commit-reveal).

- **Ballot contract:** `{"vote": "<exact option text>", "confidence": <0..1>,
  "statement": "..."}` (`APPROVAL` uses `"votes": [...]`). Three-tier parse:
  strict JSON → JSON embedded in prose → a reply naming exactly one option's
  text. Anything else is a non-ballot and **counts against quorum** — as do
  abstentions; a mostly-silent team has not reached quorum, and that is signal.
- **Options:** `EXPLICIT` is the reliable path. `LAST_SYNTHESIS` extracts
  `Option A: …` lines from the newest synthesis — instruct that synthesis to
  emit them.
- **Ties and quorum failures** go to `tiePolicy`: `MODERATOR_DECIDES` runs one
  moderator turn choosing among the unresolved options (method
  `vote+moderator-tiebreak`); `NO_DECISION` (default) records an honest
  `type: NONE` and the discussion continues. `HUMAN_DECIDES` is still rejected at save
  time: humans can now be group *members* (I6), but a tie-break that waits on a
  person needs its own resume machinery, which has not shipped.
- The result fires the `decision_reached` SSE event (which this feature also
  wires for debate verdicts) and renders a tally block in Slack.

## Humans as group members (I6)

Real deployments are hybrid teams: a `memberType: "HUMAN"` member sits in the
roster like any agent, but their turn **pauses the discussion**
(`AWAITING_HUMAN_INPUT`) until they answer.

```json
{
  "members": [
    { "agentId": "agent-1", "displayName": "Analyst", "speakingOrder": 1 },
    { "agentId": "gregor@example.com", "displayName": "Gregor", "speakingOrder": 2, "memberType": "HUMAN" }
  ],
  "humanMemberConfig": { "turnTimeout": "PT4H", "onTimeout": "SKIP_TURN" }
}
```

- The human's `agentId` is their **principal id** — the identity that may submit
  their turns; `displayName` is required at save time.
- Their prompt is rendered exactly like an agent's and persisted on the
  conversation (`pendingHumanInput.renderedPrompt`); the `human_input_requested`
  SSE event (and a Slack notice) says who is up.
- Submission: `POST /groups/{groupId}/conversations/{id}/human-input`
  `{memberId, content}` or MCP `submit_group_human_input`. **Only the member's
  own principal (or an admin) may submit** — an `eddi-approver` may decide
  approvals, but speaking as another human is impersonation, not review. The
  answer is recorded as the phase's natural entry type (a human OPINION is an
  OPINION) and the discussion resumes from the next speaker.
- This is deliberately NOT the approval surface: approve/reject endpoints never
  accept free text, and the pending-approvals inbox marks these entries
  `pauseType: "HUMAN_TURN"` with the member's id, so a human sees their own
  pending turns without owning the conversation.
- **Timeouts** (`humanMemberConfig`): `turnTimeout` (ISO-8601; unset = wait
  indefinitely) with `onTimeout: SKIP_TURN` (a SKIPPED entry — "no response from
  <name> within <window>" — and the discussion moves on) or `ABORT` (graceful
  cancel). Timeout schedules survive restarts via the HITL crash-recovery sweep.
- **PARALLEL phases**: agents fan out first; humans are then prompted one at a
  time against the *pre-fan-out* snapshot, so an independent round stays
  independent — a human answering after the agents cannot read their answers.
- **v1 bounds (save-time rejected)**: no HUMAN members in task-force groups
  (PLAN/EXECUTE/VERIFY) or `targetEachPeer` phases, and a group containing
  humans cannot be nested as a GROUP member. A human **moderator** is allowed —
  every synthesis then waits on that person (the save warns about it).

## Facilitator with bounded moves (I12)

Orchestration is static choreography; nothing reacts ("we've agreed — stop",
"we need a specialist — recruit"). A full LLM orchestrator conflicts with
deterministic governance, so the facilitator chooses among
**config-enumerated moves** — validated, capped, audit-logged:

```json
{
  "facilitator": {
    "enabled": true,
    "agentId": "facilitator-agent",
    "allowedMoves": ["CONTINUE", "CALL_VOTE", "ESCALATE_HUMAN"],
    "checkAfter": "EACH_PHASE",
    "maxMovesPerDiscussion": 10,
    "escalateTo": "gregor@example.com"
  }
}
```

At each checkpoint (`EACH_PHASE` default, or `EACH_REPEAT`) the facilitator
agent receives a **compact briefing** — phase/repeat position, budget
arithmetic, roster, entry counts, capped excerpts, *never the full transcript*
— and replies `{"move", "args", "reason"}`. Moves v1:

- `CONTINUE` — the ambient no-op and every failure's fallback. Silent.
- `END_PHASE` / `EXTEND_PHASE` — skip the remaining repeats / add one (≤2
  extensions per phase, still bounded by `maxTurns`). They act mid-phase, so
  the save **rejects** them unless `checkAfter: EACH_REPEAT`. A convergence
  exit is never overruled.
- `CALL_VOTE` — inserts a one-off VOTE phase next (`args.options`, 2–10),
  built to I14's enforced PARALLEL+NONE shape by construction.
- `RECRUIT` — brings a deployed agent in (`args.agentId`), the same
  validation path as I7's `recruitAgent` tool.
- `ESCALATE_HUMAN` — pauses the discussion (`AWAITING_HUMAN_INPUT`) for the
  configured `escalateTo` principal with the facilitator's question, riding
  I6's pending-input machinery whole; the answer records as a peer-visible
  `FOLLOW_UP` and the discussion resumes.

**Bounds and honesty.** The consult itself is a real LLM turn (counted, cost
on the I1 ledger, skipped once either budget is gone). Executed non-CONTINUE
moves are capped by `maxMovesPerDiscussion` (default 10) and each lands as a
peer-hidden `FACILITATION` entry + audit event + the
`eddi_group_facilitator_moves_total{move,outcome}` counter. Anything
unparseable, disallowed or invalid-in-context degrades to CONTINUE **and is
recorded as a rejected attempt** — the audit trail must show the model tried.
Facilitator failure never fails a discussion.

**Runtime phase divergence.** CALL_VOTE and EXTEND_PHASE mutate a runtime
copy of the phase list, persisted on the conversation (schema v4). Every
resume path — approval, human turn, timeout skip — executes and drift-checks
against that list, so a pause taken inside an inserted phase resumes
correctly. The divergence is one-off: completion (and every new round) starts
from the config again.

## Negotiation (I11)

EDDI's other decision forms are win/lose; `NEGOTIATION` is the **trade** form —
a process for surfacing trade-offs whose output is a drafted compromise with an
explicit **concession ledger** for human sign-off.

The preset: ① *Positions & Interests* (parallel, context-free — interests
enable integrative trades) ② *Opening Proposals* ③ *Bargaining* (repeats =
`maxRounds`, exits early on agreement) ④ *Arbitration* (moderator; **skipped
entirely** when an agreement was reached — `skipIf: "AGREEMENT_REACHED"`, the
single deterministic skip condition) ⑤ *Synthesis*.

- A **BARGAIN** turn is a typed move: `{"accept": "<proposalId>"|null,
  "proposal": {"terms": "..."}|null, "concessions": [{"gaveUp": "...",
  "inReturnFor": "..."}]}` plus free-text reasoning. Three-tier parse; an
  unreadable turn is prose with no state effect.
- The typed structure is the anti-sycophancy mechanism: an acceptance must name
  a specific proposal id, a concession that names nothing in return is **not
  recorded**, and the open proposals + ledger are quoted into every turn — the
  record the outcome will cite.
- A new proposal supersedes the mover's own open one (one live offer per
  agent); the proposer signs their own terms implicitly.
- **Agreement** = every non-moderator participant signed the same open
  proposal. The bargaining phase ends its repeats early and the conversation
  carries `decision: {type: "AGREEMENT", method: "negotiation"}` whose
  `tally.signedAcceptances` maps each signatory to the transcript index of
  their signed acceptance entry — the (already signed) entries are the
  co-signatures; no new crypto.
- No agreement → the arbitration runs and its conclusion becomes
  `decision: {type: "VERDICT", method: "arbitration"}`.

## Retro → group memory

A `RETRO` phase stops discussions from evaporating: the group reviews how it
worked and distills lessons that persist as **team-owned group memory**, then
surface as `{properties.*}` in every member's later discussions — institutional
knowledge that compounds run-over-run.

```json
{ "name": "Retro", "type": "RETRO", "participants": "MODERATOR" },
"retroConfig": { "maxLessonsPerRun": 3, "maxStoredLessons": 50 }
```

- The built-in template asks for `{"lessons": [{"lesson": "...", "context":
  "..."}]}`; parsing is three-tier (strict JSON → embedded JSON → nothing) and
  the per-run cap is enforced at parse time whatever the model produced.
- Lessons are stored under the synthetic owner `group:<groupId>` with `group`
  visibility — they belong to the team, not to whichever human ran the
  discussion, and survive that human's GDPR erasure without carrying their
  identity. The idempotency key `retro:<hash(lesson)>` makes re-running the
  same retro a no-op.
- **Bounded growth is non-negotiable:** the stored set FIFOs at
  `maxStoredLessons` — storing past the cap evicts the oldest, ordered by
  *creation* time, so re-harvesting an old lesson cannot shield it from
  eviction. Both caps are clamped to hard ceilings (20 per run, 500 stored) no
  config can exceed: an LLM-driven write surface with an operator-supplied
  ceiling is only as bounded as the operator's typing.
- `maxLessonsPerRun` bounds the whole harvest, not each contribution — a RETRO
  phase with several participants or repeats cannot multiply it.
- Member conversations already load group-visible entries at init, so recall
  needs no new namespace. The `retro_recorded` SSE event reports each harvest.

## Standing Teams (I13)

A group *conversation* is an episode; a **team** persists. The `GroupWorkspace`
(one document per group, own collection) holds what survives between episodes:
a **backlog** (a `SharedTaskList`, so pulled tasks flow straight into the
task-force machinery), **cadences** that pull from it on a schedule, and the
team's running **metrics** — deliberately thin glue over the existing
schedulers, I1's cost ledger and I8's retro memory.

```
POST /groupstore/groups/{groupId}/workspace/backlog   {"subject": "...", "description": "...", "priority": 5}
POST /groupstore/groups/{groupId}/workspace/cadences  {"cronExpression": "0 9 * * 1", "maxBacklogTasksPerRun": 5, "maxCostPerRun": 2.50}
GET  /groupstore/groups/{groupId}/workspace
```

MCP: `add_team_task`, `list_team_backlog`. The backlog caps at 200 with an
actionable error — it is a working set, not an archive.

**Cadence fires ride the schedule machinery whole** (`SchedulePollerService`
claim/lease/retry/dead-letter; the executor branches on
`teamCadenceType: "team_cadence"` exactly like Dream consolidation). Each fire:

1. **Reconcile** — a finished previous run is written back first; one still in
   flight (or paused at an HITL gate for days) skips the fire.
2. **Pull** — top-N *executable* backlog tasks by priority; an empty pull
   skips, logged.
3. **Claim** — a conditional store write on `runningDiscussionId`; two pods
   firing concurrently cannot both start, without any in-JVM lock.
4. **Run** — pulled tasks are injected as a runtime copy of `config.tasks`
   (the stored config is never written) and the cadence's `maxCostPerRun`
   rides the inherited-ceiling slot: dollar-primary, per the Dream precedent.
   The discussion runs under the cadence *creator's* identity.

**Writeback** happens at the next fire (or on a workspace read — read-repair),
never from inside the discussion thread, so a pod crash mid-discussion loses
nothing. VERIFIED outcomes stay VERIFIED on the backlog and credit the
assignee's `perMemberStats`; anything else returns to PENDING with the
reviewer's feedback appended to the description — **the cross-run retry
loop**. A FAILED/CANCELLED discussion returns every pulled task untouched.
Retro lessons flow through I8 unchanged (no duplication).

Per-member stats are reliability **recording only** — nothing routes or
weights on them in v1. A *permanent* group deletion cascades to the workspace;
a soft (versioned) delete keeps it, because the group can come back. Not in
v1: cross-team handoff, Manager UI, reputation weighting.

## Shared artifacts (blackboard-lite)

Without artifacts, the transcript is the only medium — every structured thing an
agent produces is prose the next agent re-parses. `artifactConfig` gives members
four tools to **create together**: `createArtifact(name, type, content)`,
`readArtifact(nameOrId)`, `proposeArtifactUpdate(nameOrId, content,
expectedVersion, markFinal?)` and `listArtifacts()`. Artifacts are typed
documents (`TEXT`, `MARKDOWN`, `JSON`) in their own collection, listed on the
discussion's REST/MCP status payload as `artifacts`, and announced over SSE and
Slack as `artifact_updated` events.

```json
"artifactConfig": {
  "allowArtifactTools": true,
  "maxArtifactsPerDiscussion": 5,
  "validators": [
    { "kind": "JSON_SCHEMA", "spec": "{\"type\":\"object\",\"required\":[\"title\"]}" },
    { "kind": "MAX_LENGTH", "spec": "20000" }
  ]
}
```

**Concurrency is deterministic compare-and-set, not an LLM merge.** Every update
presents the version it read; a stale writer is told *"artifact changed since
you read it (now v3); re-read and merge your change"* and retries against fresh
content. The failure mode is a retry, never a silent bad merge.

**Validators are declarative only** — `JSON_SCHEMA`, `REGEX` (content must
contain a match), `MAX_LENGTH` (characters) — never code. Specs are checked at
config save time; at write time a failing validator refuses the write with its
message and stores nothing. Content is additionally capped at 256 KB per
artifact.

Off by default with the same absence discipline as the task tools: no opt-in
means the tools are never assembled. The member agent's own
`enableBuiltInTools` switch still applies. `markFinal: true` freezes an
artifact — FINAL artifacts accept no further updates. Artifacts are deleted
with their discussion (close/delete cascade) and by GDPR erasure; the durable
trace of the work is the transcript.

## Preset Templates (I10)

Enterprises understand "research pod" and "decision board", not
`contextScope: OWN_FEEDBACK`. Packaged templates ship complete, validated
group configs whose member slots are **named roles** — instantiation just
assigns agents (or human principals) to them and saves through the normal
store path, so every save-time validation applies.

```
GET  /groupstore/templates                       → manifests (id, title, roles)
GET  /groupstore/templates/{id}                  → manifest + full config
POST /groupstore/templates/{id}/instantiate      {"name": "...", "roleAssignments": {"researcher1": "<agentId>", ...}}
```

MCP: `list_group_templates`, `create_group_from_template`. Missing or unknown
roles fail loudly, naming the template's real roles.

| Template | Style | What it packages |
|---|---|---|
| `research-pod` | DELPHI-style CUSTOM | Blind estimates → anonymous convergence rounds (I2) → synthesis → retro into team memory (I8), windowed context (I9), dollar ceiling (I1) |
| `editorial-team` | CUSTOM | Writer drafts a shared artifact (I17), editors propose CAS-guarded updates, dissents recorded (I4) |
| `ops-task-force` | TASK_FORCE | Bid-based assignment (I18), agent-filed tasks (I5), specialist recruitment (I7), ceiling |
| `decision-board` | CUSTOM | Hybrid board with a HUMAN director (I6) deliberating and voting (I14); options distilled by the chair, ties to the chair |
| `negotiation-table` | NEGOTIATION | Typed two-party bargaining with concession ledger (I11); the arbiter role may be a human principal |

## Nested Groups (Group-of-Groups)

Members can be other groups. The sub-group runs its own discussion and its synthesized answer becomes the member's response.

```

# Create sub-groups
create_group(name="Team A", memberAgentIds="a1,a2", style="PEER_REVIEW")  → g1
create_group(name="Team B", memberAgentIds="a3,a4", style="DEBATE")       → g2

# Create meta-group with GROUP members
create_group(
  name="Tournament",
  memberAgentIds="g1,g2",
  memberTypes="GROUP,GROUP",
  moderatorAgentId="judge-agent",
  style="ROUND_TABLE"
)
```

Depth tracking prevents infinite recursion (`eddi.groups.max-depth`, default: 3).

## Custom Phases

For full control, define phases directly:

```json
{
  "name": "Custom Panel",
  "style": "CUSTOM",
  "phases": [
    {
      "name": "Independent Opinions",
      "type": "OPINION",
      "participants": "ALL",
      "turnOrder": "PARALLEL",
      "contextScope": "NONE"
    },
    {
      "name": "Peer Critique",
      "type": "CRITIQUE",
      "participants": "ALL",
      "targetEachPeer": true,
      "contextScope": "FULL"
    },
    {
      "name": "Final Synthesis",
      "type": "SYNTHESIS",
      "participants": "MODERATOR",
      "contextScope": "FULL"
    }
  ]
}
```

### Per-phase controls

Beyond `type`/`participants`/`turnOrder`/`contextScope`, a phase carries four
controls that shape *when it stops* and *what it costs*:

```json
{
  "name": "Estimates",
  "type": "OPINION",
  "repeats": 3,
  "requiresApproval": false,
  "allowAbstention": true,
  "convergence": { "enabled": true, "minRepeats": 2, "threshold": 0.8, "judge": "SERVICE" }
}
```

**`repeats`** runs the same phase N times over the growing transcript — the
mechanism behind DELPHI's rounds.

**`requiresApproval`** pauses the discussion at the phase boundary until a human
approves or rejects (see [HITL](hitl.md)). At `TASK` granularity on an `EXECUTE`
phase it pauses per submitted task instead.

**`convergence` (I2)** ends a repeating phase early once the round stopped
producing movement, so a group that agreed after round 2 does not pay for round
5. A judge compares this round's contributions against the previous round's and
scores similarity against `threshold`; `minRepeats` (minimum 2 — one round has
nothing to compare against) guards against calling convergence before there is
evidence. `judge` is `SERVICE` (a cheap dedicated call) or `MODERATOR` (the
moderator agent judges). Converging is a *real completed repeat*: it still
persists, still fires `onPhaseComplete`, and still runs the phase's decision
block.

**`allowAbstention` (I4)** appends an instruction telling members they may reply
with the single token `PASS` when they have nothing to add. A passing member
produces no transcript entry and does not count toward the round, which is what
makes a late DELPHI round cheap instead of forcing three agents to restate
agreement in prose. Task phases never advertise the token — the detector and the
prompt read the same switch, so they cannot disagree.

### Dissent (I4)

`"recordDissents": true` on the group gives every participant who did *not*
write a synthesis one short turn to state where they still materially disagree.
Non-`PASS` replies become public `DISSENT` transcript entries and populate
`decision.dissents`.

It is opt-in because it costs one extra short call per non-synthesizer. It is
nonetheless the honest design: the alternative is asking the synthesizer to
report the objections to its own synthesis, which is precisely the failure mode
a minority report exists to prevent.

### Phase Types

| Type | Purpose |
|---|---|
| `OPINION` | Share perspective on the question |
| `CRITIQUE` | Review another member's response |
| `REVISION` | Revise own response based on feedback |
| `CHALLENGE` | Argue against consensus (devil's advocate) |
| `DEFENSE` | Defend position against challenges |
| `ARGUE` | Present argument for a side (debate) |
| `REBUTTAL` | Counter opposing arguments |
| `PLAN` | Decompose the question into sub-tasks |
| `EXECUTE` | Work on assigned sub-task |
| `VERIFY` | Review and validate another member's work |
| `SYNTHESIS` | Moderator produces balanced conclusion |
| `VOTE` | Collect explicit ballots and tally a decision (I14) |
| `PROPOSAL` | State an opening offer in a negotiation (I11) |
| `BARGAIN` | Counter, concede, or sign an acceptance (I11) |
| `RETRO` | Draw process lessons into team memory (I8) |

### Context Scopes

| Scope | What the agent sees |
|---|---|
| `NONE` | Only the question (independent) |
| `FULL` | All previous transcript entries |
| `LAST_PHASE` | Only the previous phase's entries |
| `ANONYMOUS` | Previous entries with speaker names removed |
| `OWN_FEEDBACK` | Only feedback addressed to this agent |
| `TASK_ONLY` | Only this agent's assigned task from the plan |
| `TASK_WITH_DEPS` | Assigned task plus outputs from dependency tasks |

### TASK_FORCE Configuration

The TASK_FORCE style uses a 4-phase pipeline: **Plan → Execute → Verify → Synthesize**.

1. **PLAN** — The moderator decomposes the goal into actionable tasks and assigns each to an agent
2. **EXECUTE** — Agents execute their assigned tasks in parallel (each sees only `TASK_ONLY` or `TASK_WITH_DEPS` context)
3. **VERIFY** — The moderator reviews each task result against the original goal
4. **SYNTHESIS** — The moderator combines all verified results into a coherent final deliverable

#### Pre-Configured Tasks

Pass a `tasks` array to skip the PLAN phase entirely — useful for deterministic, repeatable workflows:

```json
{
  "name": "Documentation Team",
  "style": "TASK_FORCE",
  "moderatorAgentId": "moderator-id",
  "members": [
    {"agentId": "researcher-id", "displayName": "Researcher"},
    {"agentId": "writer-id", "displayName": "Writer"}
  ],
  "tasks": [
    {
      "subject": "Research topic",
      "description": "Research the key trends and data points.",
      "assignToRole": "Researcher",
      "priority": 0
    },
    {
      "subject": "Write article",
      "description": "Using the research findings, write a 500-word article.",
      "assignToRole": "Writer",
      "dependsOn": ["Research topic"],
      "priority": 1
    }
  ]
}
```

When `tasks` is provided, the system posts `[System] "Pre-configured task plan: N tasks"` instead of invoking the moderator's LLM.

#### Task Dependencies

Use `dependsOn` to create sequential execution chains. Each entry references a task `subject`:

- Tasks with no dependencies execute in **parallel**
- Tasks with dependencies wait for their predecessors to complete
- Dependent tasks receive their predecessor's output via the `TASK_WITH_DEPS` context scope
- **Cycle detection** prevents circular dependency chains (fails fast at planning time)

#### Task Statuses

| Status | Meaning |
|---|---|
| `PENDING` | Waiting for dependencies or execution |
| `ASSIGNED` | Assigned to an agent, waiting to start |
| `IN_PROGRESS` | Currently being executed by an agent |
| `COMPLETED` | Agent produced output |
| `VERIFIED` | Moderator verified the result |
| `BLOCKED` | A dependency failed — this task can never run |
| `AWAITING_APPROVAL` | Result submitted, waiting on a human (TASK-granularity HITL) |
| `FAILED` | Agent or verification failed |

### Bid-based assignment (I18, CNP-lite)

The planner cannot know members' actual fit or load. Setting
`assignmentMode: "BID"` — per task on a `TaskDefinition`, or as the group
default on `taskListConfig` — leaves those tasks unassigned at PLAN time; the
execution wave **announces** them to eligible members in blind, parallel bid
turns and **awards** each to the highest self-assessed confidence:

```json
"taskListConfig": { "assignmentMode": "BID" },
"tasks": [
  { "subject": "Write the migration", "description": "...", "assignmentMode": "BID" }
]
```

- Members reply `{"bids": [{"subject", "confidence": 0..1, "estimatedComplexity":
  "XS|S|M|L", "rationale"}]}` — three-tier parse; prose casts no bids; bids on
  unannounced tasks are dropped; confidence is clamped.
- **Blind**: the bid prompt carries the announced tasks and nothing else — no
  transcript, no peer bids. Bid replies land as `BID` transcript entries
  (peer-hidden while the phase runs, auditable afterwards).
- **Deterministic award**: highest confidence; ties break by speaking order,
  then agent id. The winning bid is recorded per task
  (`taskList.awardedBids[taskId] = {agentId, confidence, estimatedComplexity,
  rationale}`).
- **Never stalls a wave**: a task nobody bid on falls back to the ROLE path,
  and the auction skips itself entirely (logged) when it cannot beat its own
  overhead — fewer than 2 eligible bidders or fewer than 2 unassigned tasks —
  or when the remaining turn budget cannot cover one bid turn per member.
- Bid turns are real member turns: they count toward the turn budget and their
  cost lands in the discussion's cost attribution.

### Dynamic Agents

During TASK_FORCE (or any group) discussions, agents with the appropriate LLM tools can **create, recruit, and delegate to new agents at runtime**:

| Tool | Purpose |
|---|---|
| `CreateSubAgentTool` | Create a new ephemeral agent with a specific system prompt |
| `ConverseWithAgentTool` | Delegate a sub-task to an existing deployed agent |
| `FindAgentsByCapabilityTool` | Discover agents by capability keywords |
| `RecruitAgentTool` | Bring a discovered agent into the discussion as a member |
| `TeardownAgentTool` | Clean up dynamically created agents |

#### Recruitment

Discovery and recruitment are two halves of one capability, and both are gated by
`allowRecruitment`. `findAgentsByCapability` locates a specialist;
`recruitAgent(agentId, role, reason)` brings it in.

A recruit **joins from the next round**, never mid-round — a roster that changed
while a round was running would move the speaker index a paused discussion
resumes from, and change the denominator the convergence and unanimity checks
already computed for the round in flight. The recruitment is recorded as a
`FACILITATION` transcript entry naming the recruiter, the recruit, the role and
the reason, so the rest of the team can see why the roster changed.

Recruitment is refused, with an actionable message, when the agent is not
deployed, is already a member, is the recruiter itself, or when
`maxRecruitedAgentsPerDiscussion` is reached.

**Recruits are never torn down.** They are pre-existing deployed agents the
discussion borrowed, so `TeardownAgentTool` and end-of-discussion cleanup leave
them alone — undeploying one would take it away from every other conversation
using it. Only agents the discussion *created* are cleaned up.

#### DynamicAgentConfig

Guardrails for dynamic agent creation are configured per-group via `AgentGroupConfiguration.dynamicAgents`:

```json
{
  "dynamicAgents": {
    "enabled": true,
    "allowCreation": true,
    "allowRecruitment": true,
    "allowDelegation": true,
    "maxCreatedAgentsPerDiscussion": 5,
    "maxRecruitedAgentsPerDiscussion": 10,
    "maxDelegationsPerTask": 3,
    "lifecyclePolicy": "ephemeral",
    "inheritParentModel": true,
    "allowedProviders": ["anthropic", "openai"],
    "allowedModels": {
      "anthropic": ["claude-sonnet-4-6"],
      "openai": ["gpt-4o"]
    }
  }
}
```

| Setting | Default | Purpose |
|---|---|---|
| `enabled` | `false` | Master switch for dynamic agent capabilities |
| `allowCreation` | `false` | Allow creating new agents (vs. only recruiting existing) |
| `allowRecruitment` | `false` | Allow recruiting already-deployed agents into the discussion |
| `allowDelegation` | `true` | Allow delegating sub-tasks to other agents |
| `maxCreatedAgentsPerDiscussion` | `5` | Cap on new agents created per discussion |
| `maxRecruitedAgentsPerDiscussion` | `10` | Cap on recruited agents per discussion |
| `delegationTimeoutSeconds` | `60` | How long a delegating agent waits for its delegate's turn. Non-positive falls back to the default |
| `maxDelegationsPerTask` | `3` | Cap on delegations per task |
| `maxDelegationDepth` | `3` | How deep a delegation chain may nest before it is refused |
| `allowedDelegationTargets` | `null` (any deployed agent) | Whitelist of agent ids a member may delegate to |
| `lifecyclePolicy` | `EPHEMERAL` | `EPHEMERAL`, `KEEP_DEPLOYED`, `UNDEPLOY_ONLY`, or `AGENT_DECIDES` |
| `inheritParentModel` | `true` | Created agents inherit the parent agent's model |
| `allowedProviders` | `null` (any) | Whitelist of LLM providers |
| `allowedModels` | `null` (any) | Per-provider model whitelist |

Dynamic agents are tracked in `GroupConversation.dynamicMembers`, `createdAgentIds`, and `retainedAgentIds`.

### Tenant Quota Enforcement

If tenant quotas are enabled, `QuotaExceededException` is propagated regardless of the group's `onAgentFailure` policy — quota violations always abort the discussion to prevent runaway resource consumption.

## Protocol Configuration

```json
{
  "protocol": {
    "agentTimeoutSeconds": 180,
    "onAgentFailure": "SKIP",
    "maxRetries": 2,
    "onMemberUnavailable": "SKIP",
    "maxTurns": 50,
    "maxCostPerDiscussion": 5.00,
    "onCostExceeded": "SYNTHESIZE_NOW"
  }
}
```

| Setting | Options | Default |
|---|---|---|
| `agentTimeoutSeconds` | Any positive integer | 180 |
| `onAgentFailure` | `SKIP`, `RETRY`, `ABORT` | `SKIP` |
| `maxRetries` | 0+ | 2 |
| `onMemberUnavailable` | `SKIP`, `FAIL` | `SKIP` |
| `maxTurns` | Any positive integer (0 or negative → default) | 50 |
| `maxCostPerDiscussion` | Dollars, or `null` for unlimited | `null` |
| `onCostExceeded` | `SYNTHESIZE_NOW`, `ABORT` | `SYNTHESIZE_NOW` |

**The two ceilings are different instruments.** `maxTurns` bounds *how many
member turns* a discussion may spend — it is the structural stop that keeps a
recruiting, extending, re-planning discussion finite. `maxCostPerDiscussion`
bounds *dollars*, which is what actually differs between a cheap 40-turn round
table and an expensive 8-turn one with large contexts. Hitting the cost ceiling
under `SYNTHESIZE_NOW` skips the remaining discussion phases and jumps straight
to synthesis, so the caller still gets an answer for what was already spent;
`ABORT` fails the discussion instead. Every LLM call in the discussion is
attributed, including the facilitator's, the convergence judge's, and the
summarizer's.

> **Timeout guidance**: 180s covers thinking models (e.g. `claude-sonnet-5`) and synthesis phases comfortably. For tool-calling agents with multiple tool loops, consider `300`–`600`. The timeout is per agent turn, not per phase.

## REST API

| Method | Path | Description |
|---|---|---|
| `POST` | `/groupstore/groups` | Create group config |
| `GET` | `/groupstore/groups/descriptors` | List group configs (`filter`, `index`, `limit`) |
| `GET` | `/groupstore/groups/{id}` | Read group config |
| `PUT` | `/groupstore/groups/{id}` | Update group config |
| `DELETE` | `/groupstore/groups/{id}` | Delete group config |
| `GET` | `/groupstore/groups/styles` | List discussion styles |
| `POST` | `/groups/{groupId}/conversations` | Start discussion |
| `GET` | `/groups/{groupId}/conversations/{id}` | Read transcript |
| `GET` | `/groups/{groupId}/conversations` | List conversations |
| `DELETE` | `/groups/{groupId}/conversations/{id}` | Delete + cascade |
| `GET` | `/groupstore/groups/jsonSchema` | JSON schema for the group config |
| `POST` | `/groups/{groupId}/conversations/stream` | Start discussion, stream events over SSE |
| `POST` | `/groups/{groupId}/conversations/{id}/followup` | Ask one member a follow-up |
| `POST` | `/groups/{groupId}/conversations/{id}/continue` | Continue with a new question (same transcript) |
| `POST` | `/groups/{groupId}/conversations/{id}/continue/stream` | Continue, streaming |
| `POST` | `/groups/{groupId}/conversations/{id}/close` | Close the conversation to further rounds |
| `POST` | `/groups/{groupId}/conversations/{id}/cancel` | Cancel a running discussion |
| `POST` | `/groups/{groupId}/conversations/{id}/approve` | Approve/reject a HITL pause |
| `POST` | `/groups/{groupId}/conversations/{id}/approve/stream` | Approve and stream the resumed run |
| `POST` | `/groups/{groupId}/conversations/{id}/human-input` | Submit a HUMAN member's turn (I6) |
| `GET` | `/groups/{groupId}/conversations/{id}/approval-status` | Pause coordinates (`detail=full` for approvers) |
| `GET` | `/groups/pending-approvals` | Every group discussion awaiting a decision |
| `GET` | `/groupstore/templates` | List preset templates (I10) |
| `GET` | `/groupstore/templates/{templateId}` | Read one template |
| `POST` | `/groupstore/templates/{templateId}/instantiate` | Create a group from a template |
| `GET` | `/groupstore/groups/{id}/workspace` | Read the standing-team workspace (I13) |
| `GET` | `/groupstore/groups/{id}/workspace/backlog` | Read the team backlog |
| `POST` | `/groupstore/groups/{id}/workspace/backlog` | File a backlog task |
| `POST` | `/groupstore/groups/{id}/workspace/cadences` | Add a cron cadence |
| `DELETE` | `/groupstore/groups/{id}/workspace/cadences/{cadenceId}` | Remove a cadence and its schedule |

## SSE Events

`POST /groups/{groupId}/conversations/stream` (and the `continue`/`approve`
streaming variants) emit these events. A client that only cares about the
answer can watch `group_complete`; a UI rendering the discussion live wants the
speaker and phase pairs.

| Event | Fires when |
|---|---|
| `group_start` | The discussion begins (carries group id and question) |
| `phase_start` / `phase_complete` | A phase opens / closes |
| `round_start` | A repeat of a repeating phase begins |
| `speaker_start` / `speaker_complete` | A member's turn opens / closes (complete carries the content) |
| `token` | Incremental token from a streaming member turn |
| `synthesis_start` / `synthesis_complete` | The synthesis phase opens / closes |
| `convergence_checked` / `convergence_reached` | A convergence judge ran / declared the phase converged (I2) |
| `decision_reached` | A `DecisionRecord` was set — vote tally, debate verdict, or negotiation agreement (I14/I11) |
| `task_plan_created` / `task_verified` | TASK_FORCE planned its backlog / verified a task |
| `retro_recorded` | A RETRO phase finished, with the number of lessons stored (I8) |
| `artifact_updated` | A shared artifact was created or revised (I17) |
| `human_input_requested` | A HUMAN member's turn is pending (I6) |
| `awaiting_approval` / `hitl_resume` | A HITL pause was committed / released |
| `member_pause_skipped` | A member turn was skipped rather than paused |
| `cancelled` | The discussion was cancelled |
| `group_complete` | Terminal — carries the final state and the synthesized answer |
| `group_error` | Terminal failure, with a curated (never raw) message |

## MCP Tools

| Tool | Description |
|---|---|
| `describe_discussion_styles` | Rich descriptions of all styles |
| `list_groups` | List group configs |
| `read_group` | Read group config |
| `create_group` | Create group (name, members, style, roles, types) |
| `update_group` | Update group config JSON |
| `delete_group` | Delete group config |
| `discuss_with_group` | Start discussion, return transcript |
| `read_group_conversation` | Read conversation transcript |
| `list_group_conversations`  | List past discussions for a group, with state and timestamps                                                                         |
| `start_group_discussion`    | Start a discussion asynchronously (returns immediately). Poll with `read_group_conversation`                                         |
| `delete_group_conversation` | Delete a group conversation and cascade-delete all member conversations                                                              |
| `followup_with_member` | Ask a single member a follow-up on a finished discussion |
| `continue_group_discussion` | Continue a discussion with a new question |
| `close_group_conversation` | Close a conversation to further rounds |
| `submit_group_human_input` | Submit a HUMAN member's turn (I6) |
| `approve_group_phase` | Approve or reject a HITL pause |
| `cancel_group_discussion` | Cancel a running discussion |
| `add_team_task` | File a task on a standing team's backlog (I13) |
| `list_team_backlog` | Read a standing team's backlog |
| `list_group_templates` | List the preset group templates (I10) |
| `create_group_from_template` | Create a group from a template + role assignments |

## Slack Integration

Group discussions integrate natively with Slack. See [slack-integration.md](slack-integration.md) for full setup instructions.

### UX Pattern: Header + Thread

All discussion styles use the same rendering pattern in Slack:

1. **Start Banner** — posted in the user's thread with style name, agent count, and question
2. **Agent Headers** — each agent's first contribution is a channel-level message with a short preview
3. **Full Content** — the complete response is posted as a thread reply under the agent's header
4. **Peer Feedback** — feedback threads under the target agent's header message
5. **Revisions** — revised contributions thread under the agent's own header
6. **Synthesis** — moderator's synthesis gets its own channel-level header + thread

### Discussion Styles in Slack

| Style | Phase Flow in Slack |
|-------|-------------------|
| **ROUND_TABLE** | Each agent posts → Moderator synthesizes |
| **PEER_REVIEW** | Agents post → Critiques thread under targets → Revisions thread under own → Synthesis |
| **DEVIL_ADVOCATE** | Agent posts → Challenger threads challenges → Agent threads defense → Synthesis |
| **DEBATE** | PRO agent posts → CON agent posts → Rebuttals thread under opponents → Judge synthesizes |
| **DELPHI** | Round 1 agents post → Round 2 agents post (convergence) → Synthesis |
| **TASK_FORCE** | Moderator posts plan → Agents post task results → Verifiers thread under targets → Synthesis |

### Trigger Keywords

Configure trigger keywords in `ChannelIntegrationConfiguration` to route to specific groups:

```
@EDDI panel: Should we adopt microservices?     → GROUP target "panel"
@EDDI debate: REST vs GraphQL                   → GROUP target "debate"
@EDDI peer: Review this architecture             → GROUP target "peer"
```

### Follow-up Conversations

After a discussion, users can reply in any agent's thread to ask follow-up questions. The system injects the agent's discussion context (contribution + peer feedback received) into the prompt for a contextual response.

## Configuration

```properties

# application.properties
eddi.groups.max-depth=3    # Max recursion depth for nested groups
```

