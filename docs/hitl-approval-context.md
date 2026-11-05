# HITL Approval Card Context — Show the Approver What They Are Deciding On

An approval card normally says *that* something is waiting and *why* the agent paused. For a decision that is more than
"yes, run it" — publish this post, send this reply, approve this phase — the approver also needs the thing itself: the
draft, the sources it cites, the claims it makes, the devil's advocate's objection. **`approvalContext`** lets the agent
or group designer say which of that to show, as short templates over the conversation's own data.

This page builds on [Human-in-the-Loop (HITL)](hitl.md) and [HITL Review & Audit](hitl-review-and-audit.md).

Everything is optional. A configuration without `approvalContext` behaves exactly as before: nothing is rendered,
nothing is stored, the Slack card is the card it was.

## Configuration

### Agents — `hitlConfig.approvalContext` and `toolApprovals.rules[].context`

```json
"hitlConfig": {
  "pauseReason": "Outbound GTM post needs marketing sign-off",
  "approvalContext": [
    { "label": "Draft",   "template": "{properties.gtm.draft}", "maxChars": 1200 },
    { "label": "Sources", "template": "{#for s in properties.gtm.sources}{s_count}. {s.title} — {s.url}\n{/for}", "maxChars": 600 },
    { "label": "Claims",  "template": "{#for c in properties.gtm.claims}• {c.text} [{c.sourceRef}]\n{/for}", "maxChars": 800 },
    { "label": "Devil's Advocate", "template": "{context.daObjection}", "maxChars": 600 }
  ],
  "toolApprovals": {
    "requireApproval": ["mcp:publish_*"],
    "rules": [
      { "match": "mcp:publish_linkedin_post",
        "pauseReason": "Publishing to LinkedIn",
        "context": [
          { "label": "Post text", "template": "{call.args.text}", "maxChars": 1500 },
          { "label": "Audience",  "template": "{call.args.visibility} · {call.args.targetSegment}" }
        ] }
    ]
  }
}
```

### Groups — the group `hitlConfig.approvalContext`

```json
"hitlConfig": { "granularity": "TASK", "approvalContext": [
  { "label": "Task",   "template": "{#for t in group.tasksAwaitingApproval}{t.subject} → {t.assignee}\n{/for}" },
  { "label": "Draft",  "template": "{group.artifacts.draft.content}", "maxChars": 1200 },
  { "label": "Claims", "template": "{#for c in group.artifacts.claims.json}• {c.text} [{c.sourceRef}]\n{/for}" },
  { "label": "Devil's Advocate objection", "template": "{group.lastByRole.DEVIL_ADVOCATE.content}", "maxChars": 600 }
]}
```

### Block fields

| Field | Default | Meaning |
| --- | --- | --- |
| `label` | — (required) | The heading of the block. Plain text, at most 80 characters. |
| `template` | — (required) | A runtime template, at most 4,000 characters. See [the data it can read](#what-a-template-can-read). |
| `maxChars` | `1000` | Cut-off for the rendered text, `50`–`2900` (Slack allows 3,000 characters per section). A longer render is cut and ends with `…`. |

A block whose render is **blank is dropped**. That is how a block becomes conditional: write the condition into the
template, for example `{#if group.phase.name == 'Synthesis'}{group.lastByType.SYNTHESIS.content}{/if}`.

### Limits

| What | Limit |
| --- | --- |
| Blocks in `hitlConfig.approvalContext` (agent) | 8 |
| Blocks in one `toolApprovals.rules[].context` | 4 |
| Blocks in the group `hitlConfig.approvalContext` | 8 |
| Text of all blocks of one pause, summed | 8,000 characters (`eddi.hitl.approval-context.max-total-chars`); further blocks are dropped and the context is marked `truncated` |
| One block's render | 16,000 characters of output, 2,000 loop iterations, 500 ms (`eddi.hitl.approval-context.render-timeout-ms`) |

## What a template can read

Templates are **author-written config**; everything they read is **data**. Tool arguments, model output and group
transcripts are substituted into the template and never parsed as one — the same rule as every runtime template, see
[Runtime Template Engine](security.md#runtime-template-engine). They run on that restricted engine with the limits above.

| Root | Holds |
| --- | --- |
| `properties`, `context`, `memory`, `conversationInfo`, `userInfo`, `snippets`, `vars`, `conversationLog` | The usual template data model, as in prompts and outputs (see [Architecture](architecture.md)). Regular conversations only. |
| `hitl` | `pauseType` (`RULE`, `TOOL_CALL`, or the group's `PHASE`/`TASK`), `reason`, `toolCalls[]` and `executedUngatedCalls[]` (regular conversations; at most 5 calls). Each call: `name`, `source`, `gateReason`, `matchedRule`, `arguments`, `args`. |
| `call` | **Rule blocks only.** The one gated call the rule matched, same shape as one `hitl.toolCalls[]` entry. |
| `group` | **Group pauses only.** See below. |

**Tool arguments are always the redacted ones.** `arguments` is the redacted text stored with the pause, passed through
the current redaction filter again; `args` is that same text parsed as JSON (empty when it is not JSON). The raw
arguments are never read.

`group` holds:

| Key | Content |
| --- | --- |
| `groupId`, `conversationId`, `question`, `granularity` | The discussion and what it was asked. |
| `phase` | `{index, name, type}` of the phase that paused. |
| `members[]` | `{agentId, displayName, role}` from the group config. |
| `transcript[]` | The last 50 entries as `{speaker, displayName, role, type, phaseName, content}`; the role is joined from the member config. |
| `lastByRole.<ROLE>`, `lastByType.<TYPE>`, `lastBySpeaker.<agentId>` | The latest entry of that role, entry type or speaker, as `{content, displayName, phaseName}`. Errors and skips never count. |
| `tasksAwaitingApproval[]` | `{id, subject, description, assignee, result}` of the tasks gated at `TASK` granularity. |
| `artifacts.<name>` | `{type, version, status, content, json}` of each [shared artifact](group-conversations.md) (at most 20). `json` is the parsed content of a `JSON` artifact and `null` when it does not parse. |

Every string a template receives from these is capped at 4,000 characters. An artifact whose name contains a dash is
reached with `group.artifacts.get('post-draft').content`. `{s_count}` in a loop follows
`quarkus.qute.iteration-metadata-prefix` like every other template.

### A realistic setup

The writer agent answers with `convertToObject: "true"` and a `responseSchema` of
`{draft, sources:[{title,url}], claims:[{text, sourceRef}]}`, and a `postResponse` property instruction with
`"fromObjectPath": "gtmOutput"` stores it as the property `gtm` (see [LangChain](langchain.md)). The templates above
then read `properties.gtm.*`. In a **group** the writer puts the draft in a markdown artifact and the claims in a JSON
artifact, and the devil's advocate's challenge is read with `group.lastByRole.DEVIL_ADVOCATE`.

## Rendered once, at pause time

The blocks are rendered **once, when the pause is committed**, and the result is stored with the pause
(`hitlApprovalContext` on the conversation snapshot or the group conversation) and served unchanged everywhere.

- **One text for every surface and for the audit.** The Slack card is posted once. The Manager, REST and MCP show the
  same text, and a later reviewer sees what the approver saw — even if a snippet changed, an artifact was edited or the
  agent was republished in between. Changing the configuration does not change approvals already waiting.
- **Bounded cost.** A read-time render would need the whole conversation memory (megabytes for a tool-using agent) on
  every poll. Rendering once is a few milliseconds on the pause's commit thread and about 8 KB of storage per paused
  conversation.
- **Redacted twice.** Reference tokens (`${vault:…}`, `${eddivault:…}`, `${connection:…}`, `${caller:…}`) in the output
  become `[secret]` and the secret redaction filter runs on the text at render time, and again when it is served.
- **It never fails a pause.** A block that throws, times out or exceeds a limit becomes an item with an `error`
  (`render_failed`, `too_large`) and no text; the other blocks still render and the pause is committed as usual.
- A re-pause renders again and replaces the previous context; the context is cleared with the pause bookmark when the
  conversation resumes, ends or is cancelled.

`digest` — a SHA-256 over the rendered items — identifies what was shown, so an audit entry can prove it without
copying the text.

## Where it is shown, and to whom

| Surface | Shows the context to | Needs |
| --- | --- | --- |
| Manager approval card (Approvals page, conversation page, group discussion) | Owner, admin, or an approver while the conversation is paused | `approvalContext` configured |
| `GET /agents/{conversationId}/approval-status` — `approvalContext` | The same | `approvalContext` configured |
| MCP `get_approval_status` / `get_group_approval_status` — `approvalContext` | The same | `approvalContext` configured |
| Group `approval-status` and `detail=full` | Owner, admin, an approver inside the approval pause | `approvalContext` configured |
| **Slack approval card** | **Everyone in the approval channel** | `approvalContext` configured **and** `platformConfig.hitlApprovalContext = "true"` |

Not carried anywhere else: the pending-approvals listing, the [email notification](hitl.md#email-notifications) (its
contract is that it carries no conversation content), the in-thread Slack notice, the conversation snapshot a client
chats with, and the raw conversation read (which strips it, as it strips the pending tool arguments).

The REST and MCP views are `{items: [{label, text, truncated, source, callId, error}], truncated, digest}`, `null` when
nothing is configured or the conversation is not paused. `source` is `agent`, `group` or `rule:<match>`; a rule block
rendered for one of several gated calls carries that call's `callId`, and its label gets the tool name appended.

### Slack

Everyone who can read the approval channel sees the card, which is a wider audience than the approvers, so showing the
context there is an opt-in of its own: set `platformConfig.hitlApprovalContext` to `"true"` on the integration. Without
it the card gets a single line, "More context available in the Manager".

With it, the context sits between the card's facts and its buttons: a divider, a line saying the text is "rendered from
conversation data at pause time — not verified by EDDI", and for each block a bold label followed by the text.

- **The text is `plain_text`, never mrkdwn.** A model-written value cannot ping `<!channel>`, mention `<@U…>`, build a
  disguised `<url|label>` link or fake a bold "Approved by …" line. Labels are escaped.
- **No previews.** A card that carries context is posted with `unfurl_links` and `unfurl_media` off.
- **Block budget.** Slack accepts 50 blocks per message. What does not fit becomes "+N more context items — open in
  Manager"; the buttons are always the last block and the card always posts.
- A value longer than 2,900 characters is split over sections.

Group discussions started from Slack get the same on their group approval card.

## Save-time validation

`approvalContext` and `rules[].context` are checked wherever a configuration is saved or imported (agent store, group
store, LLM task `toolApprovals`, ZIP import, setup service), and a refusal is a `400` that names the block:

| Refusal | Example message |
| --- | --- |
| Too many blocks | `hitlConfig.approvalContext has 9 blocks; at most 8 are allowed` |
| Blank or oversized `label` / `template`, `maxChars` out of range | `hitlConfig.approvalContext[2].maxChars must be between 50 and 2900, got 5000` |
| The template does not parse on the restricted engine | `hitlConfig.approvalContext[1].template does not parse: …` |
| A `vault:`, `eddivault:`, `connection:` or `caller:` reference | `hitlConfig.approvalContext[0].template uses the 'vault:' namespace …` — it would print the *name* of a secret onto a card and is never meaningful to an approver |
| `call.*` outside a rule's `context` | `… uses 'call.*' … which exists only in a tool-approval rule's context blocks` |

`group.*` in an agent's blocks is only a log warning at save time: it renders empty there, so the block is dropped.
Whether `call.args.x` exists cannot be known at save time either; a missing value renders empty and the block is dropped.

## Monitoring

`eddi_hitl_approval_context_render_total{outcome, surface}` counts blocks by `outcome` (`ok`, `empty`, `failed`,
`too_large`) and `surface` (`regular`, `group`); see [metrics](metrics.md). A card that is missing a block you expected
is either `empty` (the template rendered blank) or `failed`/`too_large` (the item is there, with an error note).

## For code that adds to the context

`ApprovalContextRenderer.appendItems(memory, items)` appends already-rendered items — for example a guardrail finding
with `source: "guardrail"` — to the context of the pause being committed, recomputing the digest. Every surface then
shows them with no further work.

## Known limits

- The Manager's tool-approval editor has no rule editor, so `rules[].context` is written in the agent's JSON. The agent
  and group editors have a block editor.
- A draft produced by a rule-pause task is not exposed as `hitl.draft`; read it from `memory.current` or a property.
- The Platform Operator does not know about `approvalContext` yet; that is tracked in
  `planning/operator-followups/approval-card-context.md` in the repository.
