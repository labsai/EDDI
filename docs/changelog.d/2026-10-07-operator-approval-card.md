## 🐛 fix(operator): approval card could be approved before its details existed; readable tool arguments; decision summary (2026-10-07)

**Repo:** EDDI (`fix/operator-approval-card`), `ui/manager`

### Root cause

The chat stream's `done` event (snapshot `conversationState: "AWAITING_HUMAN"` plus `hitlPausedAt`) is emitted from the completion callback inside `conversation.say()`. The paused memory is only persisted afterwards, in `ConversationStepRunner.runGuardedConversationStep` (`storeConversationMemoryIfState`). `GET /agents/{id}/approval-status` reads the stored snapshot, so the first read after `done` can return `state` other than `AWAITING_HUMAN`, `pauseId: ""`, `pausedAt: ""` and `pauseDetails: null`. `useApprovalStatus` treated that as loaded data and never polled again (global `staleTime` 30 s, no `refetchInterval`), so `ApprovalBanner` rendered the generic RULE layout with Approve enabled until a window-focus refetch happened to fix it. In that window an admin could approve a tool call they had never seen.

### What changed and why

- `describesPause(status, expectedPausedAt)` in [`hitl-pause-binding.ts`](../../ui/manager/src/lib/hitl-pause-binding.ts) is the single rule for "this response describes this pause": state `AWAITING_HUMAN` and a matching identity (`Date.parse(hitlPausedAt) === Number(pauseId)`; a blank `pauseId` never matches; a backend without `pauseId` falls back to `pausedAt`).
- `useApprovalStatus` ([`use-hitl.ts`](../../ui/manager/src/hooks/use-hitl.ts)) takes `{ verifyPause: true }`. While the response does not describe the pause it re-polls at 500 ms, 1 s, then 2 s, for up to 10 s; withholds the non-matching response from `data`; and returns `detailsPending`, `detailsError` (read failed, or the window ran out) and `retry` (restarts the window and refetches). The operator page uses it. Without the option the hook behaves as before.
- [`approval-banner.tsx`](../../ui/manager/src/components/hitl/approval-banner.tsx): new optional `expectToolCall` prop, and a TOOL_CALL detail with zero calls, both keep Approve disabled (Reject stays available) and show `approval-details-missing`. The operator chat passes `expectToolCall`.
- Approving on the operator surface takes at most two steps. Under `requireExplicitPerCall`, a pause with exactly one gated call hides the per-call buttons and the progress chip, and Approve plus the confirm dialog sends an explicit per-call `APPROVED` (with `amendedArguments` if amended). Two or more calls keep the required per-call verdicts, and once all are set the button (now "Submit decisions") submits directly, because the per-call review is the confirmation. Reject still confirms. The blocked, pending, error, missing-details and `expectToolCall` guards disable the button in every variant. The approvals inbox and conversation-detail also pass `requireExplicitPerCall`, so they get the same flow.
- Tool-call arguments are shown through [`hitl-format-args.ts`](../../ui/manager/src/lib/hitl-format-args.ts): JSON-in-a-JSON-string (the `setupAgent` `requestBody`) is expanded and pretty-printed, with a Show raw / Show formatted toggle. Display only: the amend flow and the submitted decision still use the original `call.arguments`. A level is reformatted only when its parse is lossless (`minify(text) === JSON.stringify(JSON.parse(text))`), so big integers, `1.50`, `1e3`, duplicate keys and escape spellings are never altered; those levels, and truncated or non-JSON arguments, are shown verbatim.
- [`operator-chat.tsx`](../../ui/manager/src/components/operator/operator-chat.tsx): once a decision is submitted the card is replaced by a compact summary (`approval-decision-summary`, per-call outcome, amended flag, note) while the resumed turn runs. The banner stays mounted but hidden so a failed resume brings it back with the per-call choices intact; it and the summary are keyed by the pause, so a re-pause renders fresh.

### Design decisions

- Fail closed: a response that cannot be matched to the pause is "not described". The cost is a Retry click; the opposite error approves a call nobody saw. The timeout becomes the existing `pauseDetailsError` state, never an endless spinner.
- `verifyPause` is opt-in. The approvals inbox and conversation-detail read the paused state and the status from the same store, so they do not have the stream-before-persist race, and a decision from them is still bound to `pauseId` by the pause-binding layer. The compact drawer surface has no Approve control (it only links to the full page), so it was left alone.
- The "request summary" for the operator's own tools was not added: the backend's resolved request preview plus the escalation flags already cover it, and `src/lib/operator/**` is under mutation testing. No operator revision bump: the prompt, allow-list and gate are untouched.
- The formatted view must never misrepresent the call: a lossy parse falls back to the raw text, the safe direction, even when that is a false "lossy".
- The permanent transcript decision entry was not changed.

### Tests

`hitl-pause-binding.test.ts` (`describesPause`), `hitl-format-args.test.ts`, `approval-card-pause-race.test.tsx` (not-yet-stored first read, different pause id, 10 s timeout and Retry, backoff, legacy backend), `approval-banner.test.tsx` (zero calls, `expectToolCall`, argument formatting and toggle, decision payload uses the original arguments), `operator.test.tsx` (page-level race), `operator-chat.test.tsx` (summary after approve and reject, failed resume restores the card, re-pause renders fresh).

## 🐛 fix(manager,chat): every approval surface behaves the same (2026-10-07)

**Repo:** EDDI (`fix/operator-approval-card`), `ui/manager` and `ui/chat`

### What changed and why

Phase 1 fixed the operator's approval card; this applies the same rules everywhere a pause is shown or decided, so no surface can drift.

- **One deciding experience.** `ApprovalCard` ([`approval-card.tsx`](../../ui/manager/src/components/hitl/approval-card.tsx)) wraps `ApprovalBanner` and owns the lifecycle: pending, details, at most two steps, a `DecisionSummary` receipt while the resumed work runs, the card hidden but mounted so a failed resume brings it back with its choices. It is used by the operator chat, conversation-detail, the approvals inbox rows and the group transcript. `summariseDecision` ([`hitl-decision.ts`](../../ui/manager/src/lib/hitl-decision.ts)) covers tool calls, group tasks and group phases.
- **Verify the pause everywhere.** conversation-detail and the inbox now use `useApprovalStatus(..., { verifyPause: true })`, so a not-yet-stored or different pause is never approvable. conversation-detail also re-reads for up to 10 s when opened from a pause link (`?awaiting=1`).
- **Read-only surfaces share `PauseNotice`** ([`pause-notice.tsx`](../../ui/manager/src/components/hitl/pause-notice.tsx)): the Manager chat panel and the Workforce thread name the gated tools, link to that conversation's own review page, and clear when the pause is decided elsewhere (`onResolved`, 5 s watch). The chat store now remembers `pausedAt` to tell this pause from another.
- **Group transcript:** Approve is held while only the stream says `AWAITING_APPROVAL` and the persisted conversation has not caught up (no phase or task data yet). A discussion waiting on a human is re-read every 5 s. The Workforce board's review link opens the specific conversation.
- **ui/chat:** `useHitlPolling` took the first approval-status read of a pause that was not stored yet (READY) as "resolved", refreshed to the old snapshot and dropped the card. A READY read before the pause has been seen stored is now distrusted for 10 s (500 ms backing off to 2 s). `PausedCard` stays read-only, and shows tool names only, never arguments.

### Design decisions

- Client-side polling is defence in depth. The root cause (the stream's `done` is emitted before the pause is persisted) is being fixed on the backend separately; these windows then rarely trigger.
- For ui/chat the pause identity is not available to the client (no `pauseId` in its approval-status type, no `hitlPausedAt` tracked), so the signal is "has this pause ever been seen stored". A pause decided before the first read looks identical to a not-yet-stored one, so the doubt ends after 10 s.
- Every deciding 1:1 surface uses `requireExplicitPerCall`, so the single-call and multi-call flows are identical on all of them. Group cards have no tool calls and use Approve plus confirm.

### Tests

`approval-card.test.tsx`, `pause-notice.test.tsx`, `conversation-detail-pause-race.test.tsx`, additions to `approvals.test.tsx`, `discussion-transcript.test.tsx`, `use-groups.test.tsx`, and `useHitlPolling.test.ts` in `ui/chat`. Each safety fix was mutation-checked.
