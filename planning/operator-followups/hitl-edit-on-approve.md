# Operator follow-up — HITL edit-on-approve and the decision audit contract (`feat/hitl-edit-on-approve`)

Nothing in this file is built. It is the input for the combined operator/MCP follow-up PR ("F9 integration"),
updated to what `feat/hitl-edit-on-approve` actually shipped. The feature itself is documented in
[`docs/hitl-review-and-audit.md`](../../docs/hitl-review-and-audit.md).

## Platform Operator

### System prompt (`ui/manager/src/lib/operator/system-prompt.ts`)

Extend the HITL section (next to the "Rejected rule pauses" / `onRejection` paragraph) with:

- **Agent `hitlConfig.review`** — `allowAnswerEdits` (default false: lets an approver approve a rule pause with their
  own final answer), `screenEdits` (`OUTPUT_GUARDRAILS` default, or `NONE`), `maxEditChars` (1..65536, default 16000),
  `approvalCategory` (lower-case `[a-z0-9._:/-]`, ≤128; default `rule:<agentId>`), `reasonCodes` (code → label,
  `[A-Z][A-Z0-9_]{1,39}`, ≤30 codes, labels ≤120; replaces the built-in `FALSE_CLAIM, FACTUAL_ERROR, TONE,
  OFF_POLICY, FORMAT, SCOPE, OTHER`).
- **`toolApprovals.rules[].approvalCategory`** — the audit category of the calls a rule governs; default
  `tool:<requireApproval pattern>`.
- **Group `hitlConfig.review`** — `allowResultEdits` (task results via `taskDecisions[id].editedResult`, SYNTHESIS
  phase output via `decision.editedAnswer`), `maxEditChars`, `phaseCategories` (phase name → category), `reasonCodes`.
- How an approver edits: REST resume body `editedAnswer`/`reasonCode`/`toolDecisions[].reasonCode`; group approve
  body `taskDecisions` (exclusive with `taskApprovals`); MCP `resume_conversation(editedAnswer, reasonCode,
  toolDecisionsJson)` and `approve_group_phase(editedAnswer, reasonCode, taskDecisionsJson)`; the Manager banner; Slack
  "✏️ Edit & approve" / "Reject with reason…" modals. **The operator itself still never approves, rejects or edits.**
- Slack: `platformConfig.hitlShareRuleDrafts = "true"` is the data-minimisation opt-in for rule drafts in Slack; the
  edit button needs one call (TOOL_CALL) or a draft (RULE) that fits 3000 characters and holds no secret.
- Failure modes an admin will ask about: `400` "this agent does not allow approving with an edited answer
  (hitlConfig.review.allowAnswerEdits is off)"; "there is no model answer to edit — the pause came before the model
  answered" (put the pause rule directly after the LLM task); a refused edit re-pauses with
  `pauseDetails.lastEditRefused` (`SCREENED_OUT` by an output guardrail, or `CONFIG_DRIFT`); "phase outputs other than
  a synthesis cannot be edited"; "taskDecisions and taskApprovals are mutually exclusive"; a reason code on a plain
  approval is refused.
- **Audit contract** — `hitl.approval` entries carry `schema: 2` with `decisionOutcome` (`APPROVED`,
  `APPROVED_EDITED`, `REJECTED`, `AUTO_APPROVED`, `AUTO_REJECTED`, `ABORTED`; none for group human turns),
  `approvalCategory`/`approvalCategorySource`, `reasonCode`, `channel` (`REST`, `MANAGER`, `MCP`, `SLACK`, `TIMEOUT`,
  `SYSTEM`), `latencyMs`, `units[]` with edit metrics; `hitl.edit.refused` entries join by `decisionId`. Docs topic
  `hitl-review-and-audit`. This lets the operator answer "how often do approvers edit X, and why" from
  `GET /auditstore/...` once it may read it.

### Allow-list (`tool-scopes.ts`)

- **No new endpoints.** `POST /agents/{id}/resume` and the group approve endpoints stay excluded — the operator must
  not decide pauses, with or without an edit.
- Optional: read access to the audit entries by task (`hitl.approval`) if the analytics feature (F4) exposes a
  filtered endpoint — coordinate there; do not open the raw audit store here.

### `operator-revision.json`

One bump in the combined PR (prompt text changes above).

## MCP tools

**None to add.** This feature only added optional arguments to existing tools (allowed by the program conventions):

| Tool | New optional arguments | Service call |
| --- | --- | --- |
| `resume_conversation` | `editedAnswer`, `reasonCode`, `toolDecisionsJson` | `IConversationService.resumeConversation` (validation: `ConversationHitlService` pre-CAS, `HitlDecisionValidator`) |
| `approve_group_phase` | `editedAnswer`, `reasonCode`, `taskDecisionsJson` | `IGroupConversationService.resumeDiscussion` (validation: `GroupReviewEditor.prepare`) |

A possible later read-only tool (not built, no service method yet): `get_hitl_decisions(agentId|groupId, from, to)`
→ `IAuditStore.getEntriesByTask("hitl.approval", …)` + `HitlApprovalAudit.parse`, role tier `eddi-admin`. Better
served by F4's analytics endpoints.
