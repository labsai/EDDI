## ✨ feat(llm): `toolCallGuardrails` — guardrails on tool-call arguments before execution (2026-11-02)

**Repo:** EDDI (`feat/tool-call-guardrails`)

### What changed and why

An agent designer can now screen the **arguments** of the tool calls a model asks for — the body of a GitHub comment
it is about to post, an e-mail, a handoff brief — with the guardrail types that already screened input and output.
Until now nothing looked at what went *into* a tool: `toolResultGuardrails` governs results, and the approval gate
only asks a human. A public-posting agent needed "never name a prospect", "no PII", "only approved claims" and a
disclosure label enforced before an approver ever saw the call.

```json
"toolCallGuardrails": [
  { "tools": ["mcp:add_issue_comment"], "fields": ["/body"], "type": "regex-filter", "action": "block",
    "parameters": { "patterns": "\\b(acme|globex)\\b" } },
  { "tools": ["mcp:add_issue_comment"], "fields": ["/body"], "type": "disclosure", "action": "redact",
    "parameters": { "text": "— AI-assisted reply" } }
]
```

- **Config** — `LlmConfiguration.Task.toolCallGuardrails`, a list of
  [`ToolCallGuardrailConfig`](../../src/main/java/ai/labs/eddi/modules/llm/model/ToolCallGuardrailConfig.java): a
  guardrail plus `tools` (tool-approval patterns, the same matcher as `toolApprovals`) and `fields` (JSON pointers with
  a `*` segment; `""` the whole arguments; absent = every string). Absent or empty: nothing runs.
- **Phase** — `GuardrailPhase.TOOL_CALL` (`tool_call` in audit keys, metric tags and SSE). Every built-in type opts in:
  `regex-filter`, `pii-redaction`, `max-length`, `prompt-injection`, `judge`, `classifier`, `expression`, `json-object`.
  The judge's `{output}` is the screened field (`GuardrailModelClient.templateData`, one line).
- **New type `disclosure`** ([`DisclosureGuardrail`](../../src/main/java/ai/labs/eddi/modules/llm/guardrails/impl/DisclosureGuardrail.java)) —
  adds a fixed label, idempotently; `redact` only. Runs on output too.
- **Live path** — [`ToolCallGuardrails`](../../src/main/java/ai/labs/eddi/modules/llm/impl/ToolCallGuardrails.java),
  hooked into `ToolLoopRunner` after the call caps and **before** the approval gate. `block` / `reprompt` answer the
  call with a `NOT_EXECUTED` envelope built from configuration and EDDI vocabulary only (the screened text is never
  echoed); `redact` rewrites the call in the model's own message, so transcript, trace, pending batch and tool all see
  the same request. Reprompts are counted per entry per turn from the trace, so a HITL pause does not reset them.
- **Resume** — `ToolLoopResumer` re-screens each approved call, block-only, before the journal claim. Amended calls get
  every type; unamended ones only the deterministic ones (`screensHistory()`). Journal replays are not re-screened.
- **Engine reuse** — [`ToolCallGuardrailScreener`](../../src/main/java/ai/labs/eddi/modules/llm/guardrails/ToolCallGuardrailScreener.java)
  reuses `GuardrailService`'s resolve/evaluate/emit (four visibility modifiers, no copy);
  [`ArgumentFields`](../../src/main/java/ai/labs/eddi/modules/llm/guardrails/ArgumentFields.java) walks and rewrites
  fields with exact numbers and key order, re-serializing only on change. Caps: 256 fields / 256 KiB per call and
  entry, 20 model-backed checks per loop run.
- **Cost** — a judge or classifier on arguments is admitted against the conversation ceiling and tenant budget and
  charged as a priced pseudo-tool call (`toolCallGuardrail`), through
  [`LoopSideCallSpend`](../../src/main/java/ai/labs/eddi/modules/llm/impl/LoopSideCallSpend.java) — the summarizer's
  admit-and-charge logic extracted into one shared helper, which the summarizer now uses too.
- **Validation** — save time
  ([`ToolCallGuardrailConfigValidator`](../../src/main/java/ai/labs/eddi/modules/llm/tools/guardrail/ToolCallGuardrailConfigValidator.java),
  400; strict action, valid patterns and pointers, `disclosure` needs `redact`); deploy time
  (`ToolCallGuardrailValidation`: parameters by type, unknown or wrong-phase types refused).
- **Self-ungating** — `SelfUngatingGuard` rule 3 now also refuses an approved LLM-config write (body, patch or raw
  arguments) carrying `toolCallGuardrails`.
- **Metrics** — `eddi.guardrail.triggered{phase=tool_call}` and a new `eddi.guardrail.toolcall.decisions{stage,outcome}`.
- **Manager** — a "Tool-call guardrails" section in the LLM editor (tools and fields per entry, the guardrail side
  sheet with a tool-call phase), the `disclosure` type in the output list too, and a new `toolCallGuardrails` i18n
  namespace in all 11 locales.
- **Docs** — new page [Tool-Call Guardrails](../tool-call-guardrails.md); links from `langchain.md`, `hitl.md`,
  `mcp-client.md`, `metrics.md` and `SUMMARY.md`.

### Not in this change

- Configurable rules on tool **results** (`toolResultGuardrails.rules`, deterministic types only) — follow-up; the
  shape is in the docs page.
- Agent- and group-level `toolCallGuardrails` inheritance; refusing a full-PUT that *removes* another agent's
  `toolCallGuardrails` (v1 refuses bodies that carry the field).
- Platform Operator prompt and gate-guard, and MCP tools — see `planning/operator-followups/tool-call-guardrails.md`.

### Review follow-ups

- `SelfUngatingGuard`: a patch `add` or `remove` at an array index at or before a stored task's gate or guardrails,
  under the same parent, is now refused for both gates and guardrails. Two `remove /tasks/0` operations could
  otherwise drop a gated `/tasks/1` unseen. Appends (`/-`) and later indexes still pass.
- [Tool-Call Guardrails](../tool-call-guardrails.md) and the dashboard panel no longer read `live/blocked` as
  "approvals never asked for" or `resume/blocked` as "edits refused". The labels do not separate gated from
  ungated calls, or edited from unchanged ones.

```decision-log
| 2026-11-02 | Tool-call arguments are screened before the approval gate, and again block-only after approval; `disclosure` is a guardrail type, not a transform; result rules deferred | A call certain to be refused must not reach a human, and an approver must see what will run; approval binds to a request, so nothing may be rewritten after it | Screening after the gate (approvers see unredacted text, approvals spent on refused calls); rewriting approved calls (sends text nobody approved); a new `GuardrailAction` for labels (unknown actions block) |
```
