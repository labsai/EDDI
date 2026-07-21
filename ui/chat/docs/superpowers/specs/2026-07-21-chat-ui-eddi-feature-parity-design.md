# Chat UI ↔ EDDI Feature Parity — Design

**Date:** 2026-07-21
**Repo:** eddi-chat-ui (branch `claude/chat-ui-feature-audit-977b8d`)
**Backend truth:** EDDI @ `chore/langchain4j-1.18.0` (= `origin/main` + a dependency bump)

## Problem

An audit of EDDI features merged since and including the HITL framework (PR #585, merge
`be7dd62dc`, 2026-07-13) found 133 verified gaps in the chat UI. Scope covered #585 HITL,
#588 attachments, #587 model cascade, #600 MCP ownership, #595 group follow-ups, and
#593 error handling.

Two findings dominate, and neither is about HITL:

1. **Attachments are broken end to end.** The paperclip button uploads successfully, then
   stitches the returned `storageRef` into the message *text*. The backend only reads
   attachments from `attachment_*` context keys, so the file never reaches the model. The
   request returns 201, a bubble appears, and the agent behaves as if nothing was attached.
2. **Several always-on stream defects** corrupt or drop output for every agent, with no HITL
   configuration involved.

HITL itself is entirely unimplemented — the widget has no HITL vocabulary at all — but it is
opt-in per agent, so it affects fewer deployments than the two above.

## Verified backend contract

Established by reading EDDI source directly, not inferred.

### Conversation states — 6, not 4

`ConversationState.java`:

```
READY, IN_PROGRESS, ENDED, EXECUTION_INTERRUPTED, ERROR, AWAITING_HUMAN
```

`src/types.ts` models `READY | IN_PROGRESS | ERROR | ENDED`. `AWAITING_HUMAN` and
`EXECUTION_INTERRUPTED` are unmodelled.

### SSE events — 8 emitted, 3 handled

From `RestAgentEngineStreaming.java`:

| Event | Payload | UI today |
| --- | --- | --- |
| `task_start` | `{taskId, taskType, index}` | dropped |
| `task_complete` | `{taskId, taskType, durationMs, actions?, toolTrace?, confidence?}` | dropped |
| `token` | raw text | handled |
| `cascade_step_start` | `{stepIndex, modelType, modelName, totalSteps}` | dropped |
| `cascade_escalation` | `{fromStep, toStep, confidence, threshold, reason, durationMs}` | dropped |
| `done` | `{conversationState, conversationOutputs}` | handled |
| `error` | `{"message": "…"}` — a **JSON object** | handled, rendered raw |
| `task_failed` | `{taskId, taskType, durationMs, errorType, error}` | dropped |

The UI additionally declares and handles a `thinking` event that **the backend never emits**.

### HITL endpoints

- `POST /agents/{conversationId}/resume` — body `HitlDecision {verdict: APPROVED|REJECTED, note ≤4096, toolDecisions?}`. `decidedBy` is set server-side from `SecurityIdentity` and ignored from the body. Async: 200 means "decision accepted", not "turn finished". 409 if not `AWAITING_HUMAN`.
- `GET /agents/{conversationId}/approval-status?detail=summary|full`
- `GET /agents/pending-approvals?limit` — reviewer inbox (Manager UI surface, not this widget)
- `POST /agents/{conversationId}/cancel` — 200, or 409 when nothing to cancel

Roles on all of the above include `eddi-user`, so an end user *can* self-approve. We
deliberately do not expose that (see Decisions).

### Attachment context contract

From `AttachmentContextExtractor.java`:

- Context key must be prefixed `attachment_` (`attachment_0`, `attachment_1`, …)
- `Context.value` must be an **object/Map**, not a string
- Stored-blob path takes highest precedence: `{storageRef, fileName?}`. MIME type and size
  are resolved server-side from validated store metadata — client-supplied MIME is not
  trusted and should not be sent for this path.
- `Context.type` must be `object` (`ContextType` = `string | expressions | object | array`)
- Cap: `DEFAULT_MAX_ATTACHMENTS_PER_TURN = 5`

`docs/attachments-guide.md` "Path C" tells clients to put the ref in `url`. That is wrong for
uploaded blobs — `url` routes into the URL/SSRF branch which only accepts http/https. The
code is truth. (`url` remains legitimate for genuine remote URLs, and inline base64 `data` is
a third path for small images that avoids the upload round-trip. We use neither.)

## Decisions

**Approval model: read-only wait + poll.** The widget shows that a turn is awaiting approval
and polls until resolution; it never renders Approve/Reject. Rationale: an end user approving
their own gate defeats the oversight purpose HITL exists for (EU AI Act). Deciding belongs in
Manager UI via `/agents/pending-approvals`. This is a product decision, not a backend
limitation — `/resume` would accept the call.

**No push channel exists for 1:1 conversations.** Polling `approval-status` is the only way to
observe resolution, including auto-resolution by timeout policy (`AUTO_APPROVE` /
`AUTO_REJECT` / `ABORT`).

**`actions[]`, not `conversationState`, is the always-on pause discriminator.** `actions` is
whitelisted by the non-detailed projection and always carries `PAUSE_CONVERSATION` on a RULE
pause. We read both.

**`hitlPausedAt` is epoch millis, not ISO-8601.** `SerializationCustomizer` registers
`JavaTimeModule` at the numeric-timestamp default. Any countdown must parse a number.

## Work breakdown

Five sub-projects, ordered by leverage. P0 first because it fixes silent corruption affecting
every current user, ahead of any HITL work.

### P0 — Wire fidelity (always-on; affects every agent today)

1. **Token whitespace corruption.** `chat-api.ts:180` calls `.trim()` on each `data:` line.
   The SSE spec already strips one leading space; `.trim()` then eats the token's own leading
   whitespace and any trailing whitespace. Breaks code blocks, indentation, nested markdown.
   Fix: strip exactly one leading space, preserve everything else.
2. **Status-aware HTTP core.** No call site inspects `res.status` or reads a non-2xx body; all
   10 collapse to `statusText`. Introduce `http.ts` exporting a `request()` that returns
   status and parsed body, and an `ApiError` carrying `status` + server body. Root cause of
   every 409/403/408 mishandling downstream.
3. **Handle all 8 SSE events.** Add `task_start`, `task_complete`, `task_failed`,
   `cascade_step_start`, `cascade_escalation`. Unknown events must be ignored safely, not
   treated as tokens — today the parser defaults `eventType` to `"token"`.
4. **Remove the phantom `thinking` event** and drive the indicator from real signals
   (`task_start`/`task_complete`), so a turn that fails before emitting a token stops the
   spinner.
5. **`error` payload is JSON.** Parse `{"message"}` and render the message, not the raw blob.
6. **undo/redo.** `res.json()` on an empty 200 throws; and `undoAvailable` is never dispatched
   on the streaming path (the `done` payload omits it), so both buttons sit permanently
   greyed. Fix both: tolerate empty bodies, and re-read the snapshot after a streamed turn.
7. **Skipped-turn handling.** A turn dropped server-side arrives as an ordinary `done` whose
   payload carries the *previous* step's outputs. Today that re-dispatches stale quick replies
   as if new. Detect and surface it instead.

### P1 — Attachments

8. Widen `context` type to `Record<string, {type: string; value: unknown}>` — the current
   `value: string` cannot express the required payload.
9. Stage uploads in store state instead of calling `onSend`; render removable chips above the
   composer; cap at 5 with a clear message on overflow.
10. On send, emit `attachment_N: {type: "object", value: {storageRef, fileName}}`.
11. Delete the `[ref:…]` text convention.
12. Surface upload rejections (`ATTACHMENT_TOO_LARGE`, `ATTACHMENT_REJECTED`, bare 400).

### P2 — HITL (read-only + poll)

13. Extend `ConversationState` to all 6 values.
14. `hitl-api.ts`: `getApprovalStatus`, `cancelConversation`.
15. `useHitlPolling` hook: poll while `AWAITING_HUMAN`, backoff, stop on resolution/unmount.
16. Paused card: pause reason, gated tool names, auto-decide countdown from epoch millis.
17. Lock the composer while paused; **409 must preserve the user's typed text** — it was never
    consumed server-side.
18. Render bare-string entries in `conversationOutputs[].output[]` (pending-approval
    placeholder and reviewer-rejection messages arrive as raw strings, and are silently
    dropped today because the code only reads `.text`).

### P3 — Cancel

19. `POST /cancel`, abort the SSE reader, handle 409 `NOTHING_TO_CANCEL`.
20. Stop button while streaming or paused; `EXECUTION_INTERRUPTED` rendering.

### P4 — Auth / ownership

21. Optional Bearer token in `http.ts`; distinguish 401 / 403 / 404 in user-facing copy.

## Structural changes

`ChatWidget.tsx` is 735 lines and every sub-project above adds to it; `chat-api.ts` is 328 and
gains three endpoint families. Without splitting, P2 lands in an already-overloaded file.

- `src/api/http.ts` — status-aware fetch core, `ApiError`
- `src/api/chat-api.ts` — conversation lifecycle (slimmed)
- `src/api/hitl-api.ts` — approval-status, resume, cancel
- `src/api/attachments-api.ts` — upload
- `src/hooks/useConversationStream.ts` — SSE loop + event dispatch
- `src/hooks/useHitlPolling.ts` — approval polling
- `src/components/PausedCard.tsx`, `src/components/AttachmentChips.tsx`

Scope discipline: no unrelated refactoring. Existing conventions kept — Context + useReducer
(no state library), BEM CSS, vanilla `fetch`.

## Testing

TDD throughout, Vitest + jsdom as already configured. Two harnesses are prerequisites and
land first:

- **SSE stream harness** — builds a `ReadableStream` of `data:`/`event:` frames, including
  multi-line data, CRLF, split-across-chunk frames, and leading-whitespace tokens.
- **Status-aware fetch mock** — returns arbitrary status + body, including empty 200 and
  non-JSON bodies.

Each numbered item above gets a test written first and observed failing. Regression tests to
pin specifically: leading-whitespace token preservation; empty-200 undo; unknown SSE event
ignored; 409 preserves input; stale-quick-reply suppression on a skipped turn.

## Out of scope

- **Group conversations (#595)** — this widget is a single-agent, single-conversation surface
  with no group route. Manager UI territory.
- **MCP ownership (#600)** — the REST guard it refactored predates the audit window and
  behaves identically for this client.
- **Approve/Reject UI** — deliberate; see Decisions.
- **`PATCH /agents/{conversationId}/state`** — `eddi-admin` only; operator remedy, not a
  widget control.
