## ✨ feat(ui): readable tool calls and edit-on-approve for gated calls in every chat (2026-11-06)

**Repo:** EDDI (`feat/chats-670-tool-display-and-hitl`)

### What changed and why

Every chat surface and approval card named tools by their identifier (`getAgentDescriptors`,
`add_issue_comment`) and showed gated-call arguments as a JSON blob, and none of them used what 6.7
sends with a live tool call: the SSE `tool_call` event now carries the designer's `toolDisplay` label
(`displayName`) and up to three allow-listed values (`display`), and a `tool_result` event follows each
call. Amending a gated call's arguments meant retyping the whole object.

- **One helper per app.** [`tool-labels.ts`](../../ui/manager/src/lib/tool-labels.ts) (Manager) and
  [`tool-labels.ts`](../../ui/chat/src/tool-labels.ts) (Chat UI) turn an identifier into words
  (`getAgentDescriptors` → "Get agent descriptors", `fetchHTTPStatus` → "Fetch HTTP status") and leave
  text with spaces (a designer label) as written. The Manager's also parses the `tool_call` payload and
  builds label/value rows from tool arguments, reusing the lossless parse of `hitl-format-args.ts`.
- **Live status lines** (Manager chat page and drawer, Platform Operator, Chat UI): "Using Get agent
  descriptors…", or the designer's phrase on its own ("Creating an action: Renew the contract…").
  `tool_result` stops the spinner of the call that finished (Manager) or returns the widget to
  "Thinking…" (Chat UI). Whether a status line is an identifier or a designer phrase is decided where
  the `tool_call` event is parsed and carried as `{ text, phrase }` — it is never re-derived from the
  string's shape, so a one-word `toolDisplay` label ("Searching") is not wrapped in "Using …" and a
  prefixed MCP name (`github.search-code`) is still put into words. An event without `displayName`
  or `display` (pre-6.7 backend) is an identifier, as before.
- **Approval card** (`approval-banner.tsx`, every surface that decides: conversation page, Approvals
  inbox, Platform Operator): each gated call shows its name in words with the exact identifier beside
  it, and its arguments as label/value rows by default, with JSON and Raw one click away (a generated
  tool's nested `requestBody` string is expanded into rows). The confirmation dialog, the decision
  receipt, the pause notice and the Approvals inbox list name tools in words; the inbox search matches
  both forms.
- **Edit-on-approve for gated calls**: "Amend arguments" now opens pre-filled with the call's arguments
  pretty-printed as sent (nested strings are not expanded, so a string parameter keeps its type). A copy
  that differs only in whitespace is a plain approval. An amendment that still contains `<REDACTED>` is
  refused before submit: the REST resume runs `amendedArguments` verbatim (only Slack merges against the
  raw arguments), so the marker would otherwise become the value.
- **Group transcripts**: "using {tool}…" names the tool in words.
- **Platform Operator prompt** (revision 60 → 61): `toolDisplay`, httpcall `argumentSchema` /
  `unknownFields`, and `answerMode: "tool"` were missing; an admin asking how to make tool calls read
  well, or why a call was refused before approval, now gets an answer. No allow-list or guard change.
- **Mocks**: `conv-toolcall-1` serves a TOOL_CALL pause shaped like `buildToolCallPauseDetails` (a
  generated tool with a JSON-string body and a redacted secret, plus an MCP tool) for MSW mode.
- Chat UI `AGENTS.md`: the backend-contract notes on `tool_call` and the event list are brought up to 6.7.

### Per-surface feature table

| Surface | Tool names in words | toolDisplay label | tool_result | Args as fields | Edit gated call (pre-filled) | Edit rule-pause answer | Reason codes | Approval context |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Manager chat page / drawer | new | new | new | new (activity details) | via conversation page | via conversation page | via conversation page | via conversation page |
| Conversation page + Approvals inbox (ApprovalCard) | new | n/a | n/a | new | new (was blank) | already | already | already |
| Platform Operator chat | new | new | new | new | new (was blank) | n/a (tool gate) | shown, not sent (see below) | n/a |
| Group / Workforce transcripts | new | event carries name only | n/a | n/a | n/a | n/a | already | already |
| Chat UI widget | new | new | new | n/a (no arguments on the wire) | n/a (read-only by design) | n/a | n/a | n/a |

### Not in this change

- **Group task and synthesis edits** (`taskDecisions[].editedResult`, `decision.editedAnswer` on a
  SYNTHESIS phase) stay REST/MCP: the group `approval-status` exposes neither whether
  `review.allowResultEdits` is on nor the results to edit, so the Manager cannot offer it honestly.
- `member_tool_call` (group streams) carries only the tool name, so group lines cannot use `toolDisplay`.
- The Chat UI remains read-only on a pause (no approve/edit for end users).
- The operator chat's `onDecide` wrapper drops the banner's `review` argument, so a reason code picked
  on an operator approval card is not sent. Left as is: the operator's resume path is security-reviewed
  and this change stays UI-only there.
