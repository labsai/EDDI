# Operator follow-up — `toolCallGuardrails` (F5, `feat/tool-call-guardrails`)

For the combined "F9 integration" PR. Nothing here was changed in the F5 branch: the Platform Operator's
prompt, allow-list and gate guard (`ui/manager/src/lib/operator/**`) are untouched, and no MCP tool was added.

## What the operator needs to learn (system-prompt.ts)

1. **The field exists.** `toolCallGuardrails` is a list on an LLM task (`langchain.json`, next to `toolApprovals`).
   Each entry is a guardrail (`type`, `action`, `maxRetries`, `blockMessage`, `parameters`) plus:
   - `tools` (required) — tool-approval patterns: `mcp:add_issue_comment`, `http.post:/issues/*`, `builtin:*`;
   - `fields` (optional) — JSON pointers into the arguments (`/body`, `/items/*/text`; `""` = whole arguments;
     absent = every string).
   Absent or empty: nothing runs.
2. **Where it runs.** After the call caps and *before* the approval gate: `block`/`reprompt` refuse the call (the
   model gets `NOT_EXECUTED`, reason `tool_call_guardrail`, `retryAllowed` true for reprompt), `redact` rewrites the
   field in place so the approver sees the redacted arguments. On resume, every approved call is re-screened
   **block-only** before the journal claim: an edited call gets every type, an unedited one only the deterministic
   types; any finding refuses it, nothing is rewritten after approval.
3. **Types.** Every built-in type runs on tool-call arguments (`regex-filter`, `pii-redaction`, `max-length`,
   `prompt-injection`, `judge`, `classifier`, `expression`, `json-object`) plus the new `disclosure` type (adds a fixed
   label idempotently; `redact` only; parameters `text`, `position` append|prepend, `separator`). `disclosure` also runs
   on output guardrails.
4. **Validation messages it may have to explain.** Save time (400): unknown action, empty `tools`, invalid pattern
   (with a source-prefix suggestion), invalid pointer, `disclosure` without `redact`, `onExhausted: deliver-best`,
   more than 32 entries / 32 patterns / 16 fields. Deploy time: unknown type or a type that does not run on tool-call
   arguments refuses the deploy (unlike input/output, which skip it).
5. **Why a call did not run.** The `NOT_EXECUTED` envelope: `{status, reason: "tool_call_guardrail", guardrail, field,
   issues, message, retryAllowed}`. "Could not decide" (model down, spend refused, `arguments_unparseable`,
   `arguments_too_large`, `tool_guardrail_model_cap`) follows `failMode` — closed by default for block/reprompt.
6. **Observability.** Audit key `audit:guardrail:tool_call:<type>` (records carry `tool`, `field`, `stage`
   live|resume); `eddi_guardrail_triggered_total{phase="tool_call"}`; new
   `eddi_guardrail_toolcall_decisions_total{stage, outcome}` (allowed|rewritten|blocked|reprompted|undecided); trace
   steps of type `tool_call_guardrail`.
7. **Cost.** Judge/classifier checks are charged as a priced pseudo-tool call `toolCallGuardrail` — they show up in
   the conversation's tool cost and count toward `maxBudgetPerConversation`.

Docs page to cite: `docs/tool-call-guardrails.md`.

## gate-guard.ts

The server now refuses an agent's approved LLM-config write that carries `toolCallGuardrails`
(`SelfUngatingGuard.GUARDRAIL_WRITE_REASON`: body, JSON-Patch path/from/value, or raw arguments). The operator's
`gate-guard.ts` should refuse the same writes client-side, exactly as it does for `toolApprovals`: an llmstore
PUT/POST body containing the key, and a PATCH whose operation `path`/`from` names `toolCallGuardrails` or whose
`value` carries it. Editing these is a human's job in the Manager's LLM editor ("Tool-call guardrails" section).

## Allow-list (tool-scopes.ts)

No change: llmstore read/PATCH is already granted, and the new field lives inside the LLM document.

## Revision bump

The prompt and gate-guard changes above are an `operator-revision.json` bump (with the matching
`operator-revision.test.ts` expectation).

## MCP tools

None to add. Reading and editing the field goes through the existing resource tools (`read_resource`,
`update_resource` on the LLM configuration), and no service method was added that needs its own MCP wrapper. A
decision-time dry run for approvers (screen amended arguments before submitting a decision) belongs to F3's
edit-on-approve UX; if added, it would call `ToolCallGuardrails.screenApproved(...)` with a deterministic-only filter
and no spend — a later follow-up, not part of F5.
