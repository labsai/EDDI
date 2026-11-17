## 🔀 merge: the rest of the frontend-unification stack, one combined branch (2026-11-16)

**Repo:** EDDI (`feat/workforce-app`, #1285, with #1287, #1307, #1308, #1310, #1315, #1317, #1331 and #1333 merged in, then `main`)

The owner batched the stack behind one CI run. Each PR head was merged with a real merge commit (so GitHub marks it merged); this entry records what the merges had to decide.

### What changed and why

- **The Workforce/app-kit split (#1285) against current main.** #1285 was cut from an old #1284, so main's 245 commits since had to cross the split. Main-added modules that the moved code imports went to `@eddi/app-kit` with `git mv` (tool labels and argument fields, the human tie-break fields, the group lessons/outcomes, HITL oversight, team budget and spend-control APIs and their mock handlers, the team budget card and dialogs, the quotas API); `agent-tool-refs` and the sessions hook test went to `ui/workforce`. Imports were rewritten to `@eddi/app-kit/…` (relative inside the package). The locale split is kept: a key main added lives where the code that renders it lives (app-kit when a shared component renders it). Main's CJK font stacks moved into app-kit's `styles/app.css`; the Workforce branding tests and the landing-page role routing (now through `AppNavigate`) moved with their code. `@types/node` is `^26.6.6` in the two new workspaces, like everywhere else.
- **One `tool_result` event.** Main (#1263) and #1307 had each added a `tool_result` SSE event. They are now one: `{tool, callId, status, durationMs, ok, summary}` — main's call id, status and duration, #1307's `ok` (`status == "ok"`) and redacted summary. `ConversationEventSink` and `StreamingResponseHandler` gain a five-argument `onToolResult(tool, callId, status, durationMs, summary)` that defaults to the status-only form; #1307's three-argument form is gone. `ToolLoopRunner` emits once per call from main's `finally`, with #1307's trace-based summary; a call that wrote no result entry is reported `error`, a result that says `NOT_EXECUTED` is `not_executed`, and — #1307's rule, accepted by the owner — a call cut off by a cancel or an interrupt gets no event (the end of the turn clears the tool line). `SSEEventType` also lists `hitl_resume` and `approval_required`, which the Java already sent.
- **The controller.** `agent-conversation.ts` keeps one `turnsSent` counter (both sides had added an increment) with #1308's `activeTurn`, its dropped-stream follow before main's host failure presentation, and `moved()` in `stop()`.
- **Composer.** `ChatPanel` keeps #1317's @mention hand-off together with #1331's composer modes (a separate secret value).
- **#1287's moves.** Main's changes to the group types that #1287 moved went into `@eddi/core/groups`; `Badge` imports go to `@eddi/ui`, the team components to `@eddi/conversation/team`.
- **Monitoring.** The Full Metrics dashboard keeps main's rows and appends #1315's user-event row with fresh ids; the Kubernetes copies were regenerated with `scripts/sync-monitoring-assets.py`.
- **Platform Operator.** The prompt carries every PR's text, the merged `tool_result` payload, the conversation listing's `mine=true` / `excludeGroupMembers=true` filters, and #1333's rule that a scheduled run is toasted only on the first failure of a streak and on recovery. Revision 79.

### Design decisions

- One event per tool call, not two: two `tool_result` frames per call would have broken every client that pairs calls.
- The squash of #1284 into main was resolved in favour of the stack wherever main's file equals #1284's branch head; files #1285 had moved that the squash brought back to their old paths were removed.
