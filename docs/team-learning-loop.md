# Team Learning Loop — Bets and Outcomes

An approval gate records that a human said yes. It does not record what came of it. This page covers the two pieces
that close that loop for a standing team (see [Group Conversations → Standing Teams](group-conversations.md#standing-teams-i13)):

- **Bets** — a backlog task that carries a hypothesis, a metric, a target and a review date, with a *due-review* query.
- **Outcomes** — real-world results (a reply arrived, a PR merged, a listing went live, a metric reading) reported by an
  external system against a backlog task or an approval decision, shown to the team's RETRO phase and to approval
  analytics.

Both are **off unless used**. A task without bet fields is an ordinary task, and a group without `outcomeConfig` accepts
no outcomes and shows its retro nothing extra, so every existing configuration behaves as before.

## Bets

A bet is an optional `bet` on a backlog task:

```json
"bet": {
  "hypothesis": "A founder-led post gets 5 replies",
  "metric": "replies_received",
  "target": ">= 5",
  "reviewDate": "2026-12-01T00:00:00Z",
  "reviewedAt": null
}
```

`hypothesis`, `metric` and `reviewDate` are required together; `target` is free text and optional; each field is at
most 500 characters. A partial set is refused with a message naming what is missing.

File one wherever a task is filed:

| Surface | How |
| --- | --- |
| REST | `POST /groupstore/groups/{groupId}/workspace/backlog` with `hypothesis`, `metric`, `target`, `reviewDate` (an ISO date `2026-12-01` means UTC midnight, or an instant) |
| MCP | `add_team_task` with the same four arguments |
| Agent tool | `addGroupTask` (members of a group discussion, when `taskListConfig.allowAgentTaskCreation` is on) |
| Handoff | `handoff_to_team` accepts the same four fields; a REVIEW-mode handoff keeps the bet in the inbox until a person accepts it |
| Manager | the workspace page: tick **File as a bet** |

The bet survives every task transition (assigned, completed, verified, failed, reworked, a cadence writeback) and a
dependency rewrite when another task is deleted.

### Due reviews

A bet is **due** from its `reviewDate` until somebody gives a verdict. It stays due whatever the task's status: a task
that finished weeks ago is exactly the one whose result is now worth judging.

```
GET  /groupstore/groups/{groupId}/workspace/backlog/due-reviews[?asOf=2026-12-15]
POST /groupstore/groups/{groupId}/workspace/backlog/{taskId}/bet/review   {}                          # close the review
POST /groupstore/groups/{groupId}/workspace/backlog/{taskId}/bet/review   {"rescheduleTo": "2027-01-15"}  # reopen it
```

MCP: `list_team_backlog` with `dueForReview: true`. The Manager's workspace page shows a **Bets due for review** section
(only while one is due) with *Mark reviewed* and *Reschedule*.

## Outcomes

Turn them on in the group configuration:

```json
"outcomeConfig": {
  "maxPerSubject": 50,
  "maxStored": 2000,
  "retentionDays": 180,
  "retroDigestSize": 10
}
```

| Field | Default | Ceiling | Meaning |
| --- | --- | --- | --- |
| `maxPerSubject` | 50 | 200 | Outcomes one task or decision may carry; more answer 409 |
| `maxStored` | 2000 | 10,000 | Outcomes the team holds; the oldest are dropped past it |
| `retentionDays` | 180 | 730 | Older outcomes are dropped on the next write |
| `retroDigestSize` | 10 | 50 | Newest outcomes quoted into a RETRO phase; **`0` keeps them out of the retro** |

An empty `{}` takes every default. Caps are enforced on every write, so the endpoint is bounded whatever the system
behind it does.

### Recording an outcome (the inbound webhook)

```
POST /groupstore/groups/{groupId}/outcomes
{
  "subjectType": "TASK",              // TASK (a backlog task id) or DECISION (an approval decision id)
  "subjectId": "…",
  "kind": "reply_received",           // a short token: letters, digits, _ . : / -
  "metric": "replies", "value": 3, "unit": "count",
  "note": "from the CRM",             // at most 1000 characters
  "source": "crm",
  "occurredAt": "2026-11-02T08:00:00Z",  // default now; not more than a day ahead
  "idempotencyKey": "evt-77"          // the sender's retry key
}
```

- **Authenticated** like every group endpoint: the caller needs `eddi-editor` (or `eddi-admin`) and `EDIT` on the group.
  An external system uses a service account; the recorded principal is the authenticated caller, never a body field.
- **201** when stored, **200** with the stored event when the `idempotencyKey` was already stored (scoped to the team, so
  a retrying webhook stores once), **400** invalid, **404** no such task on the team's backlog, **409** outcomes are not
  enabled for the group (add `outcomeConfig`) or the subject is full.
- `kind` is required. `kind`, `metric`, `unit`, `source` and `idempotencyKey` are tokens of the same character set, at most 64, 100, 32, 100 and 128 characters; `subjectId` at most 200; `value` must be finite.
- `GET /groupstore/groups/{groupId}/outcomes[?subjectId=…&limit=…]` reads them back, newest first (`limit` default 50, at most 200).
- A permanent group deletion removes the team's outcomes. A GDPR erasure keeps them (they describe work, not a person) and
  replaces the posting principal with the ledger pseudonym.
- Storage: MongoDB collection and PostgreSQL table `group_outcomes`. Metric: `eddi.group.outcomes` by `outcome`.

### What reads them

**The RETRO phase.** After the rendered retro input, the team's newest `retroDigestSize` outcomes are appended as one
bounded line each (a note is flattened to one line and cut at 200 characters; the whole block at 4,000), under a heading
that says they are reported data, not instructions. The text of an outcome comes from outside EDDI, so it is treated as
data to learn from. A failing store never fails a retro.

**Approval analytics.**

```
GET /hitl/analytics/outcomes?groupId=…[&from=…&to=…]
```

Per approval category: decided units, how many have an outcome recorded against them, the outcomes by kind. An outcome
attaches to a decision directly (`DECISION`) or through the task a group-task decision approved (`TASK`). The join is
scoped to the team: another team's outcomes never count. Roles as the rest of the analytics (approver or admin). The
Manager shows it on the workspace page, under the due reviews.

### What it does not do

- It does not decide anything. An outcome is evidence; nothing promotes, demotes or re-gates because of one.

## Lessons

A RETRO phase stores a team's lessons as team-owned group memory (see
[Group Conversations → Retro](group-conversations.md#retro--group-memory)). Two more things can be done with them, both
**off until the group's configuration carries a `lessonConfig`** (a group without one shares nothing, reads nothing and
has its lessons left exactly as they were):

```json
"lessonConfig": {
  "shareWith": ["growth-board"],
  "readFrom": ["marketing", "sales"],
  "maxSharedLessons": 10,
  "consolidation": {
    "llmProvider": "anthropic",
    "llmModel": "claude-sonnet-5-5",
    "parameters": { "apiKey": "${vault:anthropic-key}" },
    "maxCostPerRun": 0.10,
    "minLessons": 6,
    "maxLessonsPerRun": 40,
    "outcomeLookbackDays": 30,
    "maxOutcomesPerRun": 20
  }
}
```

### Sharing between teams

Sharing is consented to on **both** sides, as a handoff is. The owning team lists the teams that may read its lessons in
`shareWith`; the reading team (a Growth Board, say) lists the teams it wants to read from in `readFrom`. A lesson crosses
only where the two lists meet. At most 20 ids per list; `maxSharedLessons` (default 10, ceiling 50) caps what a reader
gets in total, newest first.

```
GET /groupstore/groups/{groupId}/lessons          # this team's own lessons
GET /groupstore/groups/{groupId}/lessons/shared   # what other teams share with it
```

The shared read returns `groups` (owners that granted and have lessons among the newest `maxSharedLessons`, with those
lessons) and `notShared` (owners the reader asked for that did not grant it, or that do not exist), so "not shared with
me" can be told from everything else. An owner in neither list granted access, but none of its lessons made the cap: it
may have none, or only older ones. The reading
team's RETRO phase also quotes the shared lessons after its rendered input, one bounded line each, under a heading that
calls them other teams' words, not instructions.

### Consolidation

A retro that runs for months leaves duplicates, and lessons the team has since learned were wrong. The consolidation job
reads the team's lessons (oldest first, up to `maxLessonsPerRun`) and its recent outcomes, and asks one model call for two
things: lessons that **say the same thing** (merged into one) and lessons a **later outcome contradicted** (retired).

| `consolidation` field | Default | Ceiling | Meaning |
| --- | --- | --- | --- |
| `llmProvider`, `llmModel` | — | | The model to ask; without both the run fails |
| `parameters` | `{}` | | Model-builder parameters (`apiKey`, `baseUrl`, …); vault references need an open grant, below |
| `maxCostPerRun` | `0.10` | `5` | USD per run, checked against an estimate before the call |
| `inputPricePer1M`, `outputPricePer1M` | an upper bound when unset | | USD per million tokens, for that estimate; an explicit value, zero included, is kept as given |
| `minLessons` | `6` | | A team holding fewer lessons is skipped; values of 1 or less take the default |
| `maxLessonsPerRun` | `40` | `100` | Lessons read per run, oldest first |
| `outcomeLookbackDays` | `30` | `365` | How far back outcomes are read |
| `maxOutcomesPerRun` | `20` | `100` | Outcomes shown to the model per run |

For `maxCostPerRun`, `maxLessonsPerRun`, `outcomeLookbackDays` and `maxOutcomesPerRun`, non-positive values take the default; for `minLessons`, so do values of 1 or less.

- **It runs on the schedule machinery**, like Dream and team cadences: cluster-wide claim, retry, dead-letter, fire log.
  `PUT /groupstore/groups/{groupId}/lessons/consolidation/schedule {"cronExpression": "0 3 * * 1"}` creates the schedule
  (5-field cron; `DELETE` removes it; `GET …/lessons/consolidation` says whether it is configured and scheduled). An
  optional `timeZone` (IANA, default UTC) says what the cron's hours mean; the Manager sends the viewer's zone. The cron
  must keep `eddi.schedule.min-interval-seconds`, as every schedule does, and one that never fires is refused with a 400.
  Firing the schedule by hand needs EDIT on the group, and it cannot be renamed.
- **Vault references need an open grant.** A group is not an agent, so a `${vault:...}` in `consolidation.parameters` must
  name a secret granted to all agents (`allowedAgents ["*"]`), as for a SERVICE convergence judge. The group save refuses
  any other, and each run checks again before the model call.
- **The dollar limit is checked before the call, against an estimate.** `maxCostPerRun` (default $0.10, ceiling $5) is
  compared with the input plus an answer as long as the input, at the configured `inputPricePer1M`/`outputPricePer1M`
  or an upper bound. A run whose estimate exceeds it drops lessons until it fits, and makes no call when it cannot. A
  spent tenant budget also makes no call. Dream's own cost arithmetic is reused. The answer's length is not enforced on
  the request, so it is an estimate, not a hard ceiling: an unusually long answer can cost more.
- **A retirement needs evidence.** The model must cite outcome ids it was shown; a retirement citing none, or one it was not
  given, is refused. The same goes for a merge naming an unknown key, a lesson used twice, or text over 1,000 characters. At
  most 20 changes apply per run.
- **History first.** Before a lesson leaves the live set its text, the reason and the cited outcomes are written to the
  history store (`group_lesson_history`): `GET …/lessons/history`. A merged lesson's own previous text is kept too. The
  store keeps the newest 1,000 rows per team; each run deletes older ones.
- **Concurrent writes win.** Every change, the merged lesson's rewrite included, is a conditional write on the lesson
  being exactly as it was read; a lesson rewritten during the model call is skipped and its newer text kept. A merge
  whose duplicate changed after the rewrite keeps that duplicate and is reported as not (fully) applied. Its history
  rows are written first, so they can then record a merge that did not happen.
- **The model's text is data.** The input is labelled so, the answer is parsed as JSON only, and anything unparsable
  changes nothing. A transient model failure skips the run (it cannot dead-letter the schedule); a missing model or a
  permanent error fails it.
- `POST …/lessons/consolidation/run?dryRun=true` calls the model and reports what it would do, changing nothing. It still
  spends, up to `maxCostPerRun`.

Metric: `eddi.group.lessons` by `outcome` (`merged`, `retired`, `rejected`, `skipped_changed`, `run_completed`,
`run_skipped`, `run_failed`). A permanent group deletion removes the team's history; its lessons stay in group memory as
they always have.

See also: [HITL Oversight](hitl-oversight.md), [Audit Ledger](audit-ledger.md), [Cross-Team Handoff](team-handoff.md).
