## ✨ feat(hitl): config-driven context on approval cards (2026-11-03)

**Repo:** EDDI (`feat/approval-card-context`), stacked on `feat/hitl-edit-on-approve`

### What changed and why

An approval card said *that* something was waiting and *why*. For a decision that is more than "run it" — publish this
post, send this reply — the approver also needs the thing itself. `approvalContext` lets the agent or group designer
say what to show: short templates over the conversation's own data, rendered **once, when the pause is committed**, and
served unchanged on every surface.

- **Config** — `hitlConfig.approvalContext` (agent, at most 8 blocks), `toolApprovals.rules[].context` (at most 4,
  with `call.*` = the one gated call the rule matched) and the group `hitlConfig.approvalContext` (at most 8, with
  `group.*`: the phase, members, transcript, `lastByRole`/`lastByType`/`lastBySpeaker`, tasks awaiting approval and
  shared artifacts). A block is `{label, template, maxChars}`
  ([`ApprovalContextBlock`](../../src/main/java/ai/labs/eddi/configs/hitl/model/ApprovalContextBlock.java)). All
  optional; absent config renders nothing, stores nothing and leaves the Slack card byte-identical.
- **Save-time validation** — [`ApprovalContextValidation`](../../src/main/java/ai/labs/eddi/configs/hitl/ApprovalContextValidation.java)
  from `HitlConfigValidation`, so every store and the ZIP import refuse a bad block with a 400 that names it: limits, a
  template that does not parse on the restricted runtime engine
  ([`RuntimeTemplateParseCheck`](../../src/main/java/ai/labs/eddi/modules/templating/impl/RuntimeTemplateParseCheck.java),
  extracted from `TemplateSyntaxMigrator`, which now delegates to it), the `vault:`/`eddivault:`/`connection:`/`caller:`
  namespaces (they would print a secret's name on a card), and `call.*` outside a rule.
- **Rendering** — [`ApprovalContextRenderer`](../../src/main/java/ai/labs/eddi/engine/hitl/context/ApprovalContextRenderer.java)
  (regular conversations and rule blocks per gated call) and
  [`GroupApprovalContextRenderer`](../../src/main/java/ai/labs/eddi/engine/hitl/context/GroupApprovalContextRenderer.java),
  on a dedicated restricted `TemplatingEngine` with a 500 ms / 16,000-character / 2,000-iteration budget per block and
  8,000 characters in total. Reference tokens become `[secret]`, `SecretRedactionFilter` runs, and tool arguments come
  only from `argumentsRedacted` — `argumentsRaw` is never read. A block that throws, times out or exceeds a limit
  becomes an item with an `error` and **never fails or delays the pause**.
- **Persistence** — one field, `hitlApprovalContext` ([`RenderedApprovalContext`](../../src/main/java/ai/labs/eddi/engine/hitl/context/RenderedApprovalContext.java):
  items, `truncated`, a SHA-256 `digest`), on the conversation snapshot and the group conversation. Written at the
  single pause-commit hooks (`ConversationHitlService.populateHitlTimeoutBookmark`, `GroupHitlCoordinator.commitPause`
  and the restore after a failed group resume), overwritten by a re-pause, cleared with the bookmark in memory, in the
  MongoDB and PostgreSQL stores and on every group resume/reject/cancel/fail. The raw conversation read strips it.
- **Surfaces** — `approvalContext` on REST `approval-status` and MCP `get_approval_status` (one shared
  [`ApprovalContextViews`](../../src/main/java/ai/labs/eddi/engine/hitl/context/ApprovalContextViews.java), re-redacted
  at serve time; the MCP summary map is now `Map<String,Object>`, its existing values unchanged), on the group
  approval-status (REST and MCP) and on the group conversation read.
- **Slack** — [`SlackApprovalContextBlocks`](../../src/main/java/ai/labs/eddi/integrations/slack/SlackApprovalContextBlocks.java)
  inserts the blocks before the `actions` block (which stays last), as `plain_text` (so no mention, link or bold
  spoofing), within Slack's 50-block budget ("+N more context items — open in Manager"). Because everyone in the
  approval channel sees a card, the channel needs `platformConfig.hitlApprovalContext = "true"`; without it the card
  gets one line, "More context available in the Manager". A card that carries context is posted with
  `unfurl_links`/`unfurl_media` off (`SlackWebApiClient.postBlocksMessage(…, unfurl)`); cards without context are posted
  by the unchanged call. `HitlPauseEvent` gains a `@JsonIgnore approvalContext` component and keeps its old constructors.
- **Manager** — `ApprovalContext` shows the items as plain text (`whitespace-pre-wrap`, never markdown or HTML) above the
  decision on the Approvals page, the conversation page and the group discussion; a block editor
  (`ApprovalContextEditor`) in the agent's Human-in-the-Loop section and in the group approval editor, with the same
  limits shown as the author types. New keys under `approvalContext.*` in all 11 locales.
- **Docs and metrics** — new page [HITL Approval Card Context](../hitl-approval-context.md) (config, the data model, the
  audience table, validation, Slack), linked from `hitl.md`, `group-conversations.md`, `security.md` and `SUMMARY.md`;
  `eddi_hitl_approval_context_render_total{outcome,surface}` in `metrics.md` and on the Full Metrics Reference dashboard
  (panel 420); two properties in the configuration reference. The operator and MCP follow-up is in
  [`planning/operator-followups/approval-card-context.md`](../../planning/operator-followups/approval-card-context.md).

### Not in this change

- No Platform Operator edits and no new MCP tools (both go to the combined follow-up).
- The Manager's tool-approval editor has no rule editor, so `rules[].context` is written in the agent's JSON.
- No `hitl.draft` template root: this branch does not resolve a rule pause's draft for templates.
- Stacked on `feat/hitl-edit-on-approve`: merge after it. Expect a trivial conflict with the approval-analytics branch in
  the Full Metrics Reference dashboard's HITL row and in `GroupConversation`/`HitlConfig` field lists.

```decision-log
| 2026-11-03 | `approvalContext` is rendered once at pause commit and persisted as `hitlApprovalContext`; every surface serves that text, re-redacted | The Slack card is posted once, so the Manager, REST, MCP and a later audit must show the same text the approver saw; a read-time render would drift (snippets, artifacts, republished config) and would need the whole multi-MB conversation memory on every poll | Rendering at read time on each surface; rendering only for Slack |
| 2026-11-03 | Showing the context in a Slack channel needs the integration's own opt-in `hitlApprovalContext=true`, on top of the designer's `approvalContext` | The channel audience (everyone who can read it) is wider than the approver role the Manager, REST and MCP surfaces are gated to | Designer opt-in only; always on |
| 2026-11-03 | Slack values are `plain_text` sections; a card that carries context is posted with link and media unfurling off | The text is conversation data a model may have written: plain text cannot form a mention, a disguised link or bold spoofing, and a model-chosen URL must not make Slack fetch it | Escaping mrkdwn (`SlackMrkdwn.escape` leaves `@here` and bold untouched) |
| 2026-11-03 | A render failure becomes an item with an `error`; it never throws into the pause commit | A broken template must not strand a pause or hide the approval from the person who has to decide | Failing the pause; dropping the block silently |
```
