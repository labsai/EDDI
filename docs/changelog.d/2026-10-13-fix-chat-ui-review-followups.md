## Chat UI: recover from an unreadable `done`, cap the token-refresh timer (2026-10-13)

**Repo:** EDDI · **Branch:** `fix/chat-ui-review-followups`

Two CodeRabbit findings posted on #948 after its last push, and still open when it merged.

**What changed:**
- **An unreadable `done` payload** (`ui/chat/src/components/ChatWidget.tsx`). `parseDoneSnapshot` returns null for an empty or malformed body, for example when a proxy truncates the frame. The widget then applied it as an empty snapshot. A turn that had streamed no tokens ended with no reply and no quick replies, and nothing said so.
  - The `done` handling moved into `applyDoneSnapshot`.
  - A null payload now only marks the turn (`TurnContext.doneUnreadable`) and ends the read loop.
  - The re-read that already follows every stream (for undo/redo) asks for the current step, which carries the same two fields as `done`: `conversationState` and `conversationOutputs`. On an unreadable `done` that re-read is applied as the turn's outcome.
  - If the re-read fails too, the turn ends with the "response may be incomplete" notice. The draft is not handed back.
- **The token-refresh timer** (`ui/chat/src/api/embed-auth.ts`). Browsers store a `setTimeout` delay as a signed 32-bit value. A JWT whose `exp` was more than about 24.8 days away overflowed it, so the timer fired at once. The refresh request then went out immediately. A host that answered with the same token got the same instant timer back, and the two looped over `postMessage`. The delay is now capped at 2,147,483,647 ms. When a capped timer fires, it schedules again for the remainder instead of asking for a token.

**Design decisions:**
- CodeRabbit suggested throwing on a null `done`, so the stream-failure path handles it. That path treats the message as never consumed: with no tokens it withdraws the bubble and puts the text back in the composer as "could not be sent". A `done` means the server finished the turn, so a resend would run it twice. The re-read recovers the real outcome instead. A test pins this: the throwing variant fails both new tests.
- `parseSseFrame` stays generic. The `done` schema is still interpreted only in `sse-events.ts` and the widget.

**Tests:** `embed-auth.test.ts` adds a fake-timers test with an `exp` 60 days out. `ChatWidget.review-fixes.test.tsx` covers the re-read recovery and the re-read failure. Each test fails with its fix reverted.
