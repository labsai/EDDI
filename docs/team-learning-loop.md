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
- `GET /groupstore/groups/{groupId}/outcomes[?subjectId=…&limit=…]` reads them back, newest first.
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
- Sharing lessons between teams and merging or retiring retro lessons are separate pieces of work.

See also: [HITL Oversight](hitl-oversight.md), [Audit Ledger](audit-ledger.md), [Cross-Team Handoff](team-handoff.md).
