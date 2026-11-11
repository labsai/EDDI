# Group Approved-Result Actions (`onApproved`)

A member agent's approval-gated tool call is rejected inside a group turn
(`inGroupTurns: REJECT`, see [HITL → Group members](hitl.md#group-members)), so a
team that may only publish human-approved text cannot gate the posting call
itself. `onApproved` closes that gap from the other side: the team drafts, a
person approves (and may edit) the draft, and the group then makes **one
configured call with the approved text exactly as approved** — no member turn,
no LLM, nothing that could reword it in between.

It replaces the old workaround of a separate "Publisher" member that re-posted
the approved text in a later phase: one extra LLM turn per post, a judge to
check it did not change the wording, and phase plumbing to file the publish
task after the approval.

## Configuration

`onApproved` sits on an `EXECUTE` phase that has `requiresApproval: true`, in a
group whose `hitlConfig.granularity` is `TASK`:

```json
{
  "name": "Draft",
  "type": "EXECUTE",
  "requiresApproval": true,
  "onApproved": {
    "action": {
      "type": "HTTPCALL",
      "uri": "eddi://ai.labs.apicalls/apicallstore/apicalls/6740a1b2c3d4e5f6a7b8c9d0?version=1",
      "call": "postIssueComment"
    },
    "onFailure": "FAIL_TASK"
  }
}
```

| Field | Default | Meaning |
| --- | --- | --- |
| `action.type` | `HTTPCALL` | The executor. `HTTPCALL` is the only kind today: one call of an `httpcalls` configuration, run by the same executor agents use. |
| `action.uri` | — (required) | The `httpcalls` configuration, **with an explicit `?version=`** — the call an approval triggers must not change under it. |
| `action.call` | — (required) | The `name` of the call inside that configuration. |
| `onFailure` | `FAIL_TASK` | What a failed call does to the task. `FAIL_TASK`: the task becomes `FAILED` with the failure as its note, so its dependents do not run and VERIFY sees a failed task. `KEEP_COMPLETED`: the task stays `COMPLETED` and the failure is recorded on the action only. |

`null` (the default) runs nothing — every existing phase behaves as before.

### When it runs — and when it does not

| The task was… | Action |
| --- | --- |
| Approved by a person (`APPROVED`) | Runs once with the agent's result |
| Approved with an edit (`review.allowResultEdits`) | Runs once with the **edited** result |
| Rejected (`onTaskRejection: FAIL`) or reset for another attempt (`RETRY`) | Nothing |
| Approved by a timeout's `AUTO_APPROVE` (`decidedBy: system:…`) | Recorded as `SKIPPED` — the point is *human*-approved text |
| Approved, then the discussion was cancelled before the action ran | `SKIPPED` |
| Rejected at phase level (`verdict: REJECTED`, the whole discussion ends) | Nothing |
| Approved, then the discussion ended before the action ran (completed, failed, cancelled — crash recovery included) | `NOT_SENT` — never sent afterwards |
| Approved **again** by a person after a retry or rework reset, and its earlier action ended `FAILED`, `SKIPPED` or `NOT_SENT` | Runs again — a new attempt with the newly approved result and the same idempotency key |
| Approved again after a reset, and its earlier action ended `SUCCEEDED` | Nothing — a publish is never sent twice; the record of what was sent is kept |
| Approved again after a reset, and its earlier action ended `OUTCOME_UNKNOWN` | Nothing — the target may have acted; check it |

A task has **at most one live action** at a time, and an action whose call
went out and succeeded, or whose outcome is unknown, is never sent again. A
task is awaiting approval a second time only after a reset — `onTaskRejection:
RETRY`, or VERIFY sending it back for rework (`maxReworks`) — so "approve the
task again" means: send it back, let the member redo it, and approve the new
result. The action runs on the resumed leg *before* the phase loop re-enters
the EXECUTE phase, so a task that depends on the published one never starts
before the publish has settled (or, under `FAIL_TASK`, failed it).

## The call

The referenced call is an ordinary `httpcalls` call. It sees the approved task
only as **template data** — never as template source:

| Variable | Value |
| --- | --- |
| `{task.result}` | The approved result, exactly as approved (edited, if the approver edited it) |
| `{task.id}`, `{task.subject}`, `{task.description}` | The task |
| `{task.approvedBy}`, `{task.edited}` | Who approved it, and whether they edited it |
| `{group.id}`, `{group.conversationId}` | The group configuration and the discussion |
| `{action.idempotencyKey}` | 32 hex chars, stable per task — send it to a target that deduplicates. A standing team's backlog task is keyed by the backlog task, so it keeps its key across every discussion that works it; any other task is keyed by discussion and task (see [Standing teams](#standing-teams-cadence-writeback)) |

The call's path, query, headers and body are its author's templates, rendered
by the restricted runtime engine. A result that contains `{...}`, `{#eval}` or
`${vault:...}` is substituted as text and never evaluated — and a vault
reference that arrives in the data is refused by the executor's reference
guard. As in every httpcalls body, a value inside a JSON string must go through
`json:serialize` (see [HTTP Calls → Values in a JSON body are not
escaped](httpcalls.md#values-in-a-json-body-are-not-escaped)).

What the call may contain is checked when the group is saved, and again just
before every send:

- the configuration and the call exist at the pinned version, and the saver may
  use that configuration (workspace USE access);
- the call makes exactly one request and sees its outcome — `fireAndForget`,
  batch and `forEach` calls are refused;
- the call has no `preRequest.propertyInstructions` (even an empty list): the
  executor rebuilds the template data from conversation memory after them, which
  would drop `task.*`, `group.*` and `action.*`;
- **credentials:** a group is not an agent, so there is no identity to test a
  narrow vault grant against. Every `${vault:...}` reference in the target URL,
  path, headers, query or body must name a secret **granted to all agents**
  (`allowedAgents: ["*"]`) — the rule the SERVICE judge and lesson consolidation
  follow ([Group Convergence Judge: SERVICE](group-service-judge.md)). A grant
  narrowed after the save fails the next send instead of using the secret. The
  URL is also checked as the executor joins it (`targetServerUrl` + path), so a
  reference split across the two fields is caught;
- `${connection:...}`, `${caller:...}` and `${context:...}` are refused: the
  action runs after the approval, in the background, with no caller or request
  context they could mean.

A refused save answers `400` and names the phase and the fix, e.g.
`phases[2].onApproved needs requiresApproval: true on the same phase`.

### Who it runs as

Nobody's personal identity. The call carries only what its author wrote — the
target and the all-agents secrets above. The approver is recorded on the action
(`approvedBy`) and, when the audit ledger is enabled, in its audit entry, which
states `runAs: group:<groupId>`.

## Exactly once — what is guaranteed

1. **Recorded with the approval.** Approving a task adds a `PENDING` record to
   the discussion's task list in the same write as the approval — the
   compare-and-set on state *and* `pauseId` that every group approval uses. A
   retried, duplicated or concurrent approval of the same pause loses that write
   (`409`) and records nothing. A task gets a new record only from a later
   human approval after a reset, and only when its earlier action ended
   without an accepted publish (`FAILED`, `SKIPPED`, `NOT_SENT`).
2. **Claimed before it is sent.** The resumed leg marks the record `RUNNING` and
   persists that *before* the request goes out. A record found `RUNNING` belongs
   to a run that died mid-call (pod crash, failover): it becomes
   `OUTCOME_UNKNOWN` and is **never sent again** — the same contract as the
   tool-call [execution journal](hitl.md#the-execution-journal-at-most-once).
   `OUTCOME_UNKNOWN` counts as a failure for `onFailure`. The claim is a
   compare-and-set on the discussion still being `IN_PROGRESS`, so a cancel
   stored first makes it fail. A claim that cannot be written sends nothing and
   settles the record visibly — `SKIPPED` when the discussion was cancelled
   (seen by this pod, or read back from the store after a cancel by another
   pod), `FAILED` otherwise (store unavailable) — rather than leaving it
   `PENDING` for a leg that may never come. The metric always counts that
   outcome; an audit entry carries it only when the audit ledger is enabled and
   its submission succeeds; the stored record shows it only if the follow-up
   write succeeds. When that write fails too — and a cancelled discussion
   always refuses it, since it requires `IN_PROGRESS` — the stored discussion
   is already ended, and rule 3 settles the record.
3. **Settled when the discussion ends.** Whenever a discussion is in a terminal
   state (`COMPLETED`, `FAILED`, `REJECTED`, `CANCELLED`, `CLOSED`), the
   discussion store settles every record it still owes — on each read and each
   write of the document, MongoDB and PostgreSQL alike: `PENDING` becomes
   `NOT_SENT` (summary: the state it ended in, and "post it by hand if it is
   still wanted"), and an orphaned `RUNNING` claim becomes `OUTCOME_UNKNOWN`.
   Nothing is sent after the fact: an approval whose run a crash interrupted is
   not acted on later, behind the approver's back.

So the guarantee is **at most once**: exactly once whenever the run survives,
and effectively once across a crash for a target that deduplicates on
`{action.idempotencyKey}`. Retries configured on the call itself
(`postResponse.retryApiCallInstruction`) still apply and can repeat a request
the target already processed — leave them off for a non-idempotent publish.

A discussion whose pod dies after the approval but before the claim keeps
the record `PENDING` while it stays `IN_PROGRESS`, and sends it only if a later
leg of that discussion runs. Once the discussion ends — crash recovery cancels
it, someone cancels it, or a cadence reclaims it — the record reads
`NOT_SENT`, and was not sent. A record left `RUNNING` by a crash reads
`OUTCOME_UNKNOWN` as soon as a later leg finds it or the discussion ends; that
status does not say whether the target received the request — check the
target.

## What is recorded

`taskList.approvedActions.<taskId>` on the discussion document (MongoDB and
PostgreSQL alike; documents written before the feature simply have none):

```json
{
  "status": "SUCCEEDED",
  "phaseName": "Draft",
  "phaseIndex": 1,
  "target": "eddi://ai.labs.apicalls/apicallstore/apicalls/6740a1b2c3d4e5f6a7b8c9d0?version=1#postIssueComment",
  "onFailure": "FAIL_TASK",
  "approvedBy": "alice",
  "edited": true,
  "idempotencyKey": "3f9c2a…",
  "approvedAt": "2026-11-09T10:00:00Z",
  "startedAt": "2026-11-09T10:00:01Z",
  "finishedAt": "2026-11-09T10:00:02Z",
  "httpCode": 201,
  "responseUrl": "https://github.com/acme/site/issues/42#issuecomment-1",
  "summary": "HTTP 201"
}
```

`status` is one of `PENDING`, `RUNNING`, `SUCCEEDED`, `FAILED`,
`OUTCOME_UNKNOWN`, `SKIPPED`, `NOT_SENT`. A new attempt (see above) replaces
the record of the earlier one. `responseUrl` is the response's `html_url` (else
`url`) when it is a JSON object carrying one; `summary` is short and redacted.

- **Audit ledger:** when the ledger is enabled, one entry per settled action,
  task id `group.approved_action` — approver, edit flag, target, status, HTTP
  code, response URL and the result's SHA-256 and length (the text itself stays
  on the task). With the ledger disabled no entry is written, and a submission
  that fails is logged as a warning and leaves none.
- **Metrics:** `eddi_group_approved_action_total{outcome=succeeded|failed|outcome_unknown|skipped}`,
  incremented for every action a leg settles, whether or not the ledger is
  enabled.
- A record the store settles because the discussion ended (`NOT_SENT`, or
  `OUTCOME_UNKNOWN` for an orphaned claim) has no audit entry and no metric
  increment — no leg is running to emit them. It is logged at INFO and shows on
  the discussion document.

## Cost and quotas

An HTTP call has no LLM cost. Tool pricing, tool rate limits and the group cost
ceiling do not apply to it — they meter LLM tool calls and member turns. The
number of calls is bounded by the number of approved tasks, one each.

## Standing teams (cadence writeback)

A cadence run reads the discussion's task statuses when it writes back to the
backlog. Under `FAIL_TASK` a publish that failed leaves its task `FAILED`, so
the run does not count it as done; under `KEEP_COMPLETED` it stays `COMPLETED`
and only the action record shows the failure.

A task that is not done goes back to the backlog and is worked again in a
**new** discussion, with a new action record there. At most once is a
per-discussion guarantee, so a publish that ended `FAILED` or
`OUTCOME_UNKNOWN` in one discussion can reach the target again from the next.
What stays the same is the key: a task pulled from the backlog is keyed by the
standing team and the **backlog task** (found the way writeback matches it —
by subject, among the tasks the workspace's running discussion pulled), so
every discussion that works it offers the same `{action.idempotencyKey}`, and
a target that deduplicates on it publishes once. A task that is not a backlog
task — or whose workspace cannot be read at approval time — is keyed by
discussion and task, and a later discussion gets a different key.

## Example — a content team that posts approved GitHub comments

The `httpcalls` configuration (save it, then use its id and version):

```json
{
  "targetServerUrl": "https://api.github.com",
  "httpCalls": [
    {
      "name": "postIssueComment",
      "description": "Posts the approved reply as a comment on issue #42",
      "saveResponse": true,
      "responseObjectName": "githubComment",
      "request": {
        "method": "POST",
        "path": "/repos/acme/site/issues/42/comments",
        "contentType": "application/json",
        "headers": {
          "Authorization": "Bearer ${vault:github-content-bot}",
          "Accept": "application/vnd.github+json",
          "X-GitHub-Api-Version": "2022-11-28"
        },
        "body": "{\"body\": {json:serialize(task.result)}}"
      }
    }
  ]
}
```

`github-content-bot` must be granted to all agents. GitHub does not
deduplicate requests, so the comment is posted at most once rather than
"exactly once even across a crash".

The group:

```json
{
  "name": "Content Studio",
  "style": "CUSTOM",
  "members": [
    { "agentId": "writer-agent-id", "displayName": "Writer", "role": "WRITER" },
    { "agentId": "editor-agent-id", "displayName": "Editor", "role": "EDITOR" }
  ],
  "tasks": [
    {
      "subject": "Reply to issue #42",
      "description": "Draft a friendly, accurate reply to the question in acme/site#42.",
      "assignToRole": "ROLE:WRITER"
    }
  ],
  "hitlConfig": {
    "granularity": "TASK",
    "timeoutPolicy": "WAIT_INDEFINITELY",
    "onTaskRejection": "RETRY",
    "review": { "allowResultEdits": true }
  },
  "phases": [
    {
      "name": "Draft",
      "type": "EXECUTE",
      "requiresApproval": true,
      "onApproved": {
        "action": {
          "uri": "eddi://ai.labs.apicalls/apicallstore/apicalls/6740a1b2c3d4e5f6a7b8c9d0?version=1",
          "call": "postIssueComment"
        },
        "onFailure": "FAIL_TASK"
      }
    },
    { "name": "Wrap-up", "type": "SYNTHESIS", "participants": "MODERATOR" }
  ],
  "moderatorAgentId": "editor-agent-id"
}
```

The Writer drafts; the discussion pauses on the task; the reviewer approves it
(editing the wording if needed) with
`POST /groups/{groupId}/conversations/{gcId}/approve`; the comment is posted
with that text; the record carries the comment's `html_url`. A rejection under
`RETRY` sends the draft back to the Writer with the note and posts nothing.

## Not supported (yet)

- **Editing in the Manager.** The phase editor does not show `onApproved`; it
  carries the field through unchanged when you save other edits. Set it in the
  group JSON — `POST`/`PUT /groupstore/groups`, or the MCP `update_group` tool
  with the full configuration (`create_group` builds a group from a style and
  takes no phase JSON).
- **MCP-tool actions.** Only `HTTPCALL`; the `type` field leaves room for an MCP
  server tool later.
- **PHASE granularity and SYNTHESIS edits.** The action acts on approved
  *tasks*.
