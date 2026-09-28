## ✨ feat(chat-ui): show "Using {tool}…" from the live `tool_call` SSE event (2026-09-28)

**Repo:** EDDI (`feat/chat-ui-tool-call-status`)

### What changed and why

The backend has sent a `tool_call` event (`{"tool":"<name>"}`) right before
each LLM tool runs since 2026-08-14 (`RestAgentEngineStreaming.onToolCall`).
The Manager turned it into a "Using {tool}…" status line, but the standalone
Chat UI dropped it, so a slow tool looked like the agent thinking for no
reason. The widget now shows the same wording in its existing status indicator.

- **`sse-events.ts`** — `parseToolCallName()` reads the name, returns `null`
  for anything unusable (so a malformed event never renders "Using
  undefined…"), and caps it at 48 characters so an agent designer's long tool
  identifier cannot stretch the bubble.
- **`chat-store.tsx`** — new transient `activeTool` plus `SET_ACTIVE_TOOL`.
  `FINISH_STREAMING` and `CLEAR_MESSAGES` clear it, the same funnel that already
  covers escalation across done, error, stop-generating and a HITL pause. An
  escalation also clears it, because the model that was calling the tool has
  just been abandoned.
- **`ChatWidget.tsx`** — handles `tool_call` and clears the tool on every token
  and on `task_failed`, and at the start of each new turn.
- **`Indicators.tsx`** — `ThinkingIndicator` takes a `tool` prop ("Using
  calculator…", 🔧). It is a prop on the same component rather than a new one,
  for the reason `escalating` is: React keeps the element and does not replay
  the entrance animation when the copy changes mid-wait.
- **`ui/chat/AGENTS.md`** — the backend-contract list names nine SSE events and
  documents the `tool_call` payload and how the indicator is cleared.

### Design decisions

- **Resumed output is the completion signal.** No event says a tool has
  finished (the per-call result reaches the client only in the turn-end
  `toolTrace`), so the next token clears the status. The Manager
  (`liveToolsSettled`) settles the same way.
- **Not gated on `tokenCount`**, unlike `task_start`. A model can write a
  sentence and then call a tool; the silence while that tool runs is exactly
  the wait worth explaining, and the next token takes the status away again.
- **Precedence: tool, then escalation, then thinking.** A tool called after an
  escalation is the stronger model at work, so it shows the tool. The tool name
  is shown as sent: it is the identifier the agent designer chose, unlike the
  cascade's confidence and model name, which stay admin-side.

### Tests

Parser cases in `sse-events.test.ts`, the event added to the wire-format sweep
in `chat-api.test.ts` (the `test-utils/sse.ts` harness), and reducer cases in
`chat-store.test.tsx`. `ChatWidget.test.tsx` has seven stream-driven cases:
name shown, newest tool wins, cleared by a token, cleared by `done`, shown after
text has already streamed, malformed payload ignored, and a later escalation
taking over. The shared open-stream helpers moved to module scope so the
cascade and tool-call suites can both use them. Mutation-checked: removing the
`tool_call` dispatch fails three widget tests, and removing the per-token clear
fails one. `npm run typecheck` and `npm test` (295 tests) pass in `ui/chat`.

**Files:** [`sse-events.ts`](../../ui/chat/src/api/sse-events.ts),
[`chat-store.tsx`](../../ui/chat/src/store/chat-store.tsx),
[`ChatWidget.tsx`](../../ui/chat/src/components/ChatWidget.tsx),
[`Indicators.tsx`](../../ui/chat/src/components/Indicators.tsx),
[`types.ts`](../../ui/chat/src/types.ts),
[`ui/chat/AGENTS.md`](../../ui/chat/AGENTS.md)
