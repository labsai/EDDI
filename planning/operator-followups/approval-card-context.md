# Operator follow-up — approval card context (`feat/approval-card-context`)

Nothing in this file is built. It is the input for the combined operator/MCP follow-up PR ("F9 integration"),
updated to what `feat/approval-card-context` actually shipped. The feature itself is documented in
[`docs/hitl-approval-context.md`](../../docs/hitl-approval-context.md).

## Platform Operator

### System prompt (`ui/manager/src/lib/operator/system-prompt.ts`)

Extend the HITL section with:

- **Config.** `hitlConfig.approvalContext` (agent, at most 8 blocks), `hitlConfig.toolApprovals.rules[].context` (at most
  4, `call.*` available) and the group `hitlConfig.approvalContext` (at most 8, `group.*` available). A block is
  `{label (≤80), template (≤4000), maxChars (50..2900, default 1000)}`. All optional; absent = nothing rendered or
  stored. A block that renders blank is dropped (that is how a block is made conditional).
- **Template roots.** Everything a prompt template reads (`properties`, `context`, `memory`, …) plus `hitl.*`
  (`pauseType`, `reason`, `toolCalls[]` with `name/source/gateReason/matchedRule/arguments/args`,
  `executedUngatedCalls[]`), `call.*` (rule blocks) and `group.*` (`phase`, `members`, `transcript` (last 50),
  `lastByRole`/`lastByType`/`lastBySpeaker`, `tasksAwaitingApproval`, `artifacts.<name>.{content,json}`; a dashed
  artifact name needs `group.artifacts.get('post-draft').content`). Tool arguments are always the redacted ones.
- **The realistic GTM example.** Writer agent with `convertToObject` and a `responseSchema` of
  `{draft, sources[], claims[]}` stored as a property via `postResponse` `fromObjectPath`, templates reading
  `properties.gtm.*`; in a group, a markdown artifact for the draft and a JSON artifact for the claims, with the devil's
  advocate read as `group.lastByRole.DEVIL_ADVOCATE.content`.
- **Save-time refusals the operator will be asked to explain** (all `400`, naming `hitlConfig.approvalContext[i]`): too
  many blocks; blank or oversized `label`/`template`; `maxChars` outside 50..2900; "does not parse"; the `vault:`,
  `eddivault:`, `connection:` and `caller:` namespaces are refused (they would print a secret's name on a card);
  `call.*` outside a rule's `context`.
- **Rendered once at pause time.** Editing the configuration does not change approvals already waiting; a re-pause
  renders again. Every surface (Manager, REST, MCP, Slack) shows the same stored text, redacted when rendered and again
  when served.
- **Who sees it.** Owner, admin, or an approver while paused, on the Manager, REST `approval-status` and MCP
  `get_approval_status`. In Slack, everyone in the approval channel does, so the integration needs
  `platformConfig.hitlApprovalContext = "true"`; without it the card says only "More context available in the Manager".
  The email notification, the pending-approvals listing and the in-thread notice never carry it.
- **Reading it.** `GET /agents/{conversationId}/approval-status` and `get_approval_status` return `approvalContext`
  (`{items:[{label,text,truncated,source,callId,error}], truncated, digest}`); the group status does too, and the group
  conversation read carries `hitlApprovalContext`. An item with `error: render_failed|too_large` means a template threw,
  timed out or exceeded the limits — the pause still happened.
- **Diagnosing an empty or partial card.** `eddi_hitl_approval_context_render_total{outcome,surface}`: `empty` = the
  template rendered blank (usually a wrong path such as `properties.gtm.draft` when the property is stored under another
  name), `failed`/`too_large` = the item shows an error note. Total text is capped at 8,000 characters
  (`eddi.hitl.approval-context.max-total-chars`).

The operator **cannot write** `approvalContext`: an agent's `hitlConfig` is outside its write allow-list, and
`gate-guard.ts` already refuses any write that names `toolApprovals`, which covers `rules[].context`. No
`tool-scopes.ts` change is needed. The prompt text above is the whole change.

### Revision bump

Any change to the system prompt is a bump of `ui/manager/src/lib/operator/operator-revision.json` (and
`operator-revision.test.ts` fails until you do).

## MCP

No new tool is needed to read the context: `get_approval_status` and `get_group_approval_status` return
`approvalContext`. Optional arguments were not added.

Candidate new tools, for the combined PR (each is a thin wrapper over service methods that already exist and are
REST-backed):

| Tool | Args | Role tier | Calls |
| --- | --- | --- | --- |
| `preview_approval_context` | `agentId`, `version`, `conversationId` (a conversation to read data from), `blockJson` | `eddi-admin` | would render one block against a live conversation's data through `ApprovalContextRenderer` (needs a public `renderPreview(IConversationMemory, ApprovalContextBlock)`; nothing in this branch exposes it) |

A preview/test-render endpoint for authors was left out on purpose: the save-time parse check plus the item-level
`error` are enough for now. It is the natural follow-up if authors keep shipping templates that render blank.
