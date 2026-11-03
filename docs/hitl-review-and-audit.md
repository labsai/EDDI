# HITL Review & Audit — Edit on Approve, Reason Codes, the Decision Ledger

An approver can do more than say yes or no: they can **approve with an edit** — the final answer of a rule pause,
the arguments of a gated tool call, the result of a group task, the output of a group synthesis — and say **why**
they rejected or changed something with a **reason code**. When the audit ledger is enabled, every decision, edited or not, lands in the audit ledger
in one typed shape, so analytics can count how often an approver changes what an agent does, and why.

This page builds on [Human-in-the-Loop (HITL)](hitl.md); read that first for pauses, resumes and who may decide.

## Configuration

Everything is optional. A configuration without `review` behaves exactly as before: approve/reject only, tool-call
amendments as they always worked, a derived approval category and the built-in reason codes.

### Agents — `hitlConfig.review`

```json
"hitlConfig": {
  "review": {
    "allowAnswerEdits": true,
    "screenEdits": "OUTPUT_GUARDRAILS",
    "maxEditChars": 16000,
    "approvalCategory": "github.reply",
    "reasonCodes": { "FALSE_CLAIM": "States something untrue", "TONE": "Wrong tone" }
  }
}
```

| Field | Default | Meaning |
| --- | --- | --- |
| `allowAnswerEdits` | `false` | An approver may approve a **rule pause** with their own final answer (`editedAnswer`). Off by default: editing writes text in the agent's voice, a power a plain approval gate never granted. |
| `screenEdits` | `OUTPUT_GUARDRAILS` | `OUTPUT_GUARDRAILS`: the LLM task's own `outputGuardrails` screen the edit before it replaces the draft. `NONE`: stored as written. |
| `maxEditChars` | `16000` | Upper bound of an edited answer, in characters (1..65536). |
| `approvalCategory` | `rule:<agentId>` | The category this agent's rule pauses are recorded under. Lower-case `[a-z0-9._:/-]`, at most 128 characters. |
| `reasonCodes` | built-in set | Code → label. Present, it **replaces** the built-in vocabulary. Codes are `[A-Z][A-Z0-9_]{1,39}`, at most 30, labels at most 120 characters. |

### Tool rules — `toolApprovals.rules[].approvalCategory`

```json
"toolApprovals": {
  "requireApproval": ["http.post:*"],
  "rules": [ { "match": "http.post:*/issues/*/comments", "approvalCategory": "github.reply" } ]
}
```

A call governed by the rule is recorded under its `approvalCategory`; any other gated call under
`tool:<the requireApproval pattern that gated it>`.

### Groups — `hitlConfig.review`

```json
"hitlConfig": {
  "granularity": "TASK",
  "review": {
    "allowResultEdits": true,
    "maxEditChars": 16000,
    "phaseCategories": { "Execution": "directory.submission" },
    "reasonCodes": { "SCOPE": "Out of scope" }
  }
}
```

| Field | Default | Meaning |
| --- | --- | --- |
| `allowResultEdits` | `false` | An approver may replace a **task's result** (`taskDecisions[id].editedResult`) or the output of a **SYNTHESIS** phase (`decision.editedAnswer` on a PHASE pause). |
| `maxEditChars` | `16000` | As for agents. |
| `phaseCategories` | derived | Phase name → category for the PHASE and TASK pauses of that phase. Derived: `group:<groupId>:phase:<name>` / `group:<groupId>:task:<name>`. |
| `reasonCodes` | built-in set | As for agents. |

### Built-in reason codes

`FALSE_CLAIM` (states something untrue), `FACTUAL_ERROR`, `TONE`, `OFF_POLICY`, `FORMAT`, `SCOPE`, `OTHER`.
`FALSE_CLAIM` always keeps that meaning, so analytics may rely on it.

## Deciding with an edit

All checks run **before** the pause is consumed: a malformed edit is a `400` and the conversation stays paused.

### Rule pause edits

`POST /agents/{conversationId}/resume`

```json
{ "verdict": "APPROVED", "editedAnswer": "The release is Monday.", "reasonCode": "FALSE_CLAIM", "pauseId": "1762077600000" }
```

- Valid only with `APPROVED`, on a **rule** pause, when the agent version the conversation is pinned to allows it.
- The pause must have kept a model answer from before the pause; otherwise `400` "there is no model answer to edit".
  `approval-status` serves it as `pauseDetails.editableAnswer = {draft, format, maxChars}` (secret-redacted).
- Not blank, at most `maxEditChars`, no control characters other than line breaks and tabs; a JSON object when the
  task answers in JSON (`convertToObject`).
- An edit identical to the draft is a plain approval.

**What the engine does.** The LLM task that gave the draft replaces it **in place**: its own output item and data
keys (`langchain:<type>:<taskId>`, `output:text:langchain:<type>`). Nothing after it is cut back and nothing runs
again — not `postResponse`, not later tasks, not the rule that paused (it would pause again). The pipeline then
resumes after the pause as for any approval. The step records `hitl:edit:applied` (digests and sizes, never the
original text — the original stays in the paused turn's LLM audit entry), `hitl:edit:outcome`, and the conversation
output `hitlAnswerEdited = "true"` (`{memory.current.hitlAnswerEdited}` in templates).

**Screening.** Under `screenEdits: OUTPUT_GUARDRAILS` the task's output guardrails run on the edit with no
reprompter: a `reprompt` finding cannot ask the human again, so it acts as `block`; `redact` rewrites the text;
`block` refuses it. Guardrail calls that cost money are admitted against the conversation's spend like a live
answer's.

**A refused edit** (blocked by a guardrail, the LLM configuration changed since the pause, or the edit still carries a
redaction placeholder: `REDACTED_CONTENT`) cannot be applied, but
the human's decision stands: the turn **pauses again** on the same task with the unchanged draft and a new
`pauseId`, the next `approval-status` carries `pauseDetails.lastEditRefused = {reason, guardrail, at}`, and the
ledger records a `hitl.edit.refused` entry.

**The edit is data.** It is never template source, never a property, context value or action, and never put in the
system role; later model calls see it as the assistant's earlier turn. Every later gated tool call still pauses.

**Streaming clients** may already have shown the draft token by token during the paused turn. The resume reply and
the `HitlResumeCompletedEvent` snapshot carry the final text — render from them. Tasks between the answer and the
pause saw the draft and are not re-run: put the pause rule directly after the LLM task.

### Tool-call pause — amendments and reasons

Per-call amendments work as before (`toolDecisions[callId].amendedArguments`, see
[HITL → Tool-Level Approval Gating](hitl.md#tool-level-approval-gating)). New: `toolDecisions[callId].reasonCode`
(for a rejected or amended call) and a top-level `reasonCode`. A reason code on a plain approval is a `400` — only
explanations of a change are useful.

### Group task and synthesis edits

`POST /groups/{groupId}/conversations/{id}/approve` (and `/approve/stream`)

```json
{
  "decision": { "verdict": "APPROVED", "reasonCode": "SCOPE" },
  "taskDecisions": {
    "t1": { "verdict": "APPROVED", "editedResult": "The corrected result" },
    "t2": { "verdict": "REJECTED", "note": "Off topic", "reasonCode": "SCOPE" }
  }
}
```

- `taskDecisions` is the richer form of `taskApprovals`; the two are mutually exclusive (`400`).
- `editedResult` needs `APPROVED` and `allowResultEdits`. It replaces `TaskItem.result` — what dependents and the
  task summary read — and is noted in `SharedTaskList.reviewerEdits`. The agent's original stays in its signed
  `TASK_RESULT` transcript entry.
- A per-task `note` is that task's feedback under `onTaskRejection: RETRY` / `FAIL`.
- `decision.editedAnswer` on a PHASE pause is valid only when the paused phase is a `SYNTHESIS` phase ("phase outputs
  other than a synthesis cannot be edited"). It is appended as an **unsigned** `SYNTHESIS` transcript entry (speaker
  `hitl:<decidedBy>`, display name "Reviewer", `errorReason: "reviewer-edit"`) and becomes the "last synthesis" every
  later phase and the final answer read. Signed entries are never rewritten, and the signing guard skips reviewer
  entries.

### Surfaces

| Surface | How |
| --- | --- |
| REST | The bodies above. |
| MCP | `resume_conversation` takes optional `editedAnswer`, `reasonCode`, `toolDecisionsJson`; `approve_group_phase` takes `editedAnswer`, `reasonCode`, `taskDecisionsJson`. Tool arguments are logged by MCP clients — use REST for long or sensitive edits. |
| Manager | "Edit the answer before approving" on a rule pause with `editableAnswer`, a reason-code select, and a notice for a refused edit. Group task/synthesis editing is REST/MCP in this release. |
| Slack | See below. |

### Slack edit and reject modals

Regular-conversation approval cards gain two buttons that open modals (`views.open`):

- **Edit & approve** on a TOOL_CALL card when the batch has exactly **one** call, not truncated, whose redacted
  arguments fit Slack's 3000-character input. The modal shows the **redacted** arguments as JSON. On submit the edit
  is merged server-side against the raw arguments: a value left as shown keeps its raw value (a redacted secret never
  travels through Slack), a redacted value cannot be changed from Slack, and a new `<REDACTED>` marker is refused.
- **Edit & approve** on a RULE card only when the agent allows answer edits, the integration opted in with
  `platformConfig.hitlShareRuleDrafts = "true"` (drafts reach Slack only with this flag — data minimisation), and the
  draft fits 3000 characters and holds no secret. Otherwise the card says to edit in the Manager.
- **Reject with reason…** asks for a reason code and an optional note.

Submissions re-run every check the buttons run — approver list (fail-closed), workspace, card record, current pause
— and are validated synchronously, so Slack shows errors inside the modal. The card is finalised as "✅ Approved
(edit submitted) by @user" or "⛔ Rejected (TONE) by @user"; the edited text is never echoed into the channel. Group cards keep
Approve/Reject. A note typed in the Slack modal is recorded, but does not drive `onRejection: REVISE`.

## The audit contract

When the audit ledger is enabled, every HITL decision event — a resume, a group approval, a cancellation — writes **one** `hitl.approval` entry
(`taskType: hitl`). Since 6.7 the entry carries `schema: 2`: every pre-6.7 field is kept for existing readers, and
the decision's units are listed in `units[]`. Read entries through `HitlApprovalAudit.parse(AuditEntry)`, never the
raw map: it reads schema 1 and 2 into `HitlDecisionUnit`s.

### Top-level fields (`AuditEntry.output`)

| Field | Values / meaning |
| --- | --- |
| `schema` | `2` |
| `decisionId` | the entry's id (`AuditEntry.id`); `hitl.edit.refused` entries point back with it |
| `surface` | `regular` \| `group` |
| `pauseType` | `RULE` \| `TOOL_CALL` \| `PHASE` \| `TASK` \| `HUMAN_TURN` |
| `pauseId`, `pausedAt`, `decidedAt`, `latencyMs` | the pause and how long it waited (`decidedAt − pausedAt`) |
| `verdict` | legacy: `APPROVED` \| `REJECTED` \| `CANCELLED` (group human turns write `HUMAN_*`) |
| `decisionOutcome` | the units' common outcome, `MIXED` when they differ, absent when there are no units |
| `decidedBy`, `automated` | legacy |
| `channel` | `REST` \| `MANAGER` \| `MCP` \| `SLACK` \| `TIMEOUT` \| `SYSTEM` — from the `decidedBy` prefix (`slack:`, `mcp:`, `system:timeout`, other `system:*`), else the `X-EDDI-Client: manager` request header (a client's claim, an analytics hint, never used for authorization), else `REST` |
| `automationReason` | for `system:*` deciders: `TIMEOUT_POLICY`, `NO_PROGRESS`, `PENDING_MAX_AGE`, `MEMBER_PAUSE_IN_GROUP`, or the decider's suffix upper-cased |
| `timeoutPolicy` | the pause's effective timeout policy |
| `approvalCategory`, `approvalCategorySource` | the pause's category; `CONFIG` \| `DERIVED` |
| `reasonCode`, `note` | the decision's |
| `groupId`, `phaseName`, `mode`, `pauseReason`, `toolDecisions`, `onRejection` | legacy / context fields |
| `units` | below |

For a group entry the ledger's `agentId` column carries the group id, as it always has.

### `units[]`

| Field | Values |
| --- | --- |
| `unitKind` | `ANSWER` (rule pause) \| `TOOL_CALL` \| `PHASE` \| `TASK` |
| `unitId` | `"answer"`, the call id, the phase name or the task id |
| `decisionOutcome` | `APPROVED` \| `APPROVED_EDITED` \| `REJECTED` \| `AUTO_APPROVED` \| `AUTO_REJECTED` \| `ABORTED` |
| `approvalCategory`, `approvalCategorySource` | the unit's (a tool call's own rule or pattern) |
| `reasonCode` | the unit's, falling back to the decision's |
| `toolName`, `argsDigest` | TOOL_CALL only — SHA-256 of the arguments the decision applied to |
| `edit` | `null`, or `{kind, originalChars, editedChars, charsChanged, approximate, changeRatio, originalSha256, editedSha256, fieldsChanged?, editedText?}` |

**Outcomes** (`DecisionOutcome.of`): a cancellation is `ABORTED`; a `system:*` decision is `AUTO_APPROVED` /
`AUTO_REJECTED`; a human approval with a real change is `APPROVED_EDITED`, otherwise `APPROVED`; a rejection is
`REJECTED`. **HUMAN_TURN** resolutions (a group's human member speaking) are not approvals: they have no units and no
`decisionOutcome`.

**Edit metrics.** Lengths in code points. `charsChanged` is the Levenshtein distance of the middle parts left after
trimming the common prefix and suffix — exact up to 4000 code points each, otherwise the longer middle's length with
`approximate: true`. `changeRatio = charsChanged / max(originalChars, 1)`, 4 decimals. `fieldsChanged` (tool
arguments) counts differing leaf JSON paths. `editedText` (answer, task result, synthesis — **never** tool arguments)
is secret-redacted and capped at 32 KB; GDPR erasure redacts it with the rest of the entry's output. The original text
is not in the entry: it is in the paused turn's LLM entry (rule pause) or the signed transcript (groups).

**`hitl.edit.refused`**: `{schema: 2, decisionId, unitKind, reason: SCREENED_OUT | CONFIG_DRIFT | REDACTED_CONTENT, guardrail?}`. A unit
with `APPROVED_EDITED` whose `decisionId` has such an entry was **not** applied; the same pause was followed by a new
pause.

**Schema 1** entries (before 6.7) parse best-effort: `toolDecisions[]` become TOOL_CALL units (`amended` →
`APPROVED_EDITED` with `edit: null`), otherwise one ANSWER (regular) or PHASE (group) unit; `CANCELLED` → `ABORTED`;
category, latency and pause id are `null`; the channel comes from the `decidedBy` prefix.

### The `HitlDecisionRecorded` event

Right after an entry with at least one unit is submitted, the engine fires the CDI event
`ai.labs.eddi.engine.hitl.audit.HitlDecisionRecorded(String auditEntryId, List<HitlDecisionUnit> units)` with
`fireAsync` — observe it with `@ObservesAsync`. Each `HitlDecisionUnit` is self-contained (decision context repeated
on every unit: conversation, agent or group, user, pause, latency, decider, channel, category, outcome, reason, edit).
Delivery is best-effort; a consumer that must not miss a decision rebuilds from the ledger
(`IAuditStore.getEntriesByTask("hitl.approval", …)` + `HitlApprovalAudit.parse`), which yields the same units. The
ledger's own submission is a bounded queue: when it is full the entry is dropped (logged by the ledger) while the event
is still fired, so the ledger is a fallback for a consumer that was down, not a no-miss guarantee. No event is fired
when the ledger is disabled.

### Metrics

`eddi_hitl_decision_total{surface, outcome, channel}` (one per unit), `eddi_hitl_edit_chars{kind}` (distribution of
`charsChanged`) and `eddi_hitl_answer_edit_total{outcome}` (applied, screened_out, config_drift, redacted_content). Categories stay out
of metric tags because their cardinality is configurable.

## Worked example — a GitHub reply with one-click Slack approval

An agent drafts replies to GitHub issues; a human approves each one in Slack, and may fix it on the spot.

```json
"hitlConfig": {
  "approvalTimeout": "PT4H",
  "timeoutPolicy": "AUTO_REJECT",
  "review": {
    "allowAnswerEdits": true,
    "approvalCategory": "github.reply",
    "reasonCodes": {
      "FALSE_CLAIM": "States something untrue",
      "TONE": "Wrong tone",
      "LEAKS_INTERNALS": "Mentions internal systems"
    }
  }
}
```

The behavior rule that pauses runs directly after the LLM task; the Slack integration sets
`hitlApprovalChannel`, `hitlApproverUserIds` and `hitlShareRuleDrafts: "true"`. The approver sees the draft on the
card, clicks **✏️ Edit & approve**, fixes a sentence and picks `FALSE_CLAIM`. The reply goes out with the fix, the
card reads "✅ Approved (edit submitted)", and the ledger records an `APPROVED_EDITED` unit in category `github.reply` with
the size of the change — the numbers an approval dashboard groups by.
