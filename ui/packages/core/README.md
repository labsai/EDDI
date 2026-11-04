# @eddi/core

Framework-free TypeScript for EDDI's conversation contract. No React, and no DOM
beyond `fetch`. It is consumed **as source** through the npm workspace link
(`exports` points at `src/index.ts`), so there is no build step: each app compiles
it with its own Vite and TypeScript.

Phase 1 of the frontend unification plan (`planning/frontend-unification-plan.md`,
§3.1). The Chat UI (`ui/chat`) and the Manager (`ui/manager`) both talk to the
backend through it.

| Module | What it holds |
| --- | --- |
| `http.ts` | `HttpClient`: status-aware fetch, `ApiError`, the `{error, code}` envelope (`errorPayload`), an injected `AuthProvider` with one 401 retry, an injectable transport and error factory. The module-level `setBaseUrl` / `setAuthToken` / `request` drive `defaultHttp` |
| `sse.ts` | The one SSE frame parser (`parseSseFrame`) and stream reader (`readSseStream`) for agent **and** group streams |
| `conversation.ts` | The agent conversation API: start, read, send, stream, undo, redo, rerun, end, the managed variants, profile. `createConversationApi(http)`, plus bound functions over `defaultHttp` |
| `hitl.ts` | Approval status, cancel, resume, deadline maths, poll cadence |
| `stream-events.ts` | Pure interpretation of stream payloads: error frames, tool names, `inputField`, dropped vs paused turns, output texts and images |
| `snapshot.ts` | Snapshot steps → messages (masks secret input) |
| `attachments.ts` | Upload/delete and `attachment_N` context construction |
| `ratings.ts`, `rating-steps.ts` | The owner's rating client and profile parsing; which step a message belongs to |
| `types.ts` | Contract types |

## Using it

```ts
// One client per app. The Chat UI uses the default one:
import { setBaseUrl, setAuthToken, sendMessageStreaming } from "@eddi/core";

// The Manager builds its own, so every call keeps its ApiClient semantics
// (token refresh, 401 retry, ApiClientError messages):
import { HttpClient, createConversationApi } from "@eddi/core";
const conversation = createConversationApi(
  new HttpClient({ baseUrl, fetch: api.authorizedFetch, createError }),
);
```

What text a client shows (a skipped turn, a pause headline, a rating error) is the
client's, not this package's: it has no i18n.

## Rules

- **No React, no app import, no DOM global.** `eslint.config.js` (`sharedPackage`
  in `ui/eslint.config.base.js`) enforces it; `src/boundaries.test.ts` checks the
  same and fails on an import cycle across every package under `ui/packages/`.
- **It compiles under the strictest app**: the Manager's `noUncheckedIndexedAccess`
  and ES2020 target (`tsconfig.json`).
- **Derive, don't invent.** Each module came from the code the two apps already
  had. A change to the contract belongs here, with a test, and both apps get it.
- Checks: `npm run typecheck`, `npm run lint`, `npm test` in this directory. CI runs
  them in the `UI Chat` job.

## Backend contract (verified against EDDI source — do not guess these)

Each rule names the test that pins it.

- **`ConversationState` has SIX values**: `READY`, `IN_PROGRESS`, `ENDED`,
  `EXECUTION_INTERRUPTED`, `ERROR`, `AWAITING_HUMAN`.
- **Ten SSE events** (`RestAgentEngineStreaming`): `task_start`, `task_complete`,
  `task_failed`, `token`, `tool_call`, `cascade_step_start`, `cascade_escalation`,
  `guardrail_triggered`, `done`, `error`. There is **no `thinking` event** — the
  backend never emits one. (`conversation.test.ts`)
- **`tool_call` is `{"tool":"<name>"}`, sent right before each tool runs** — the
  name only; arguments reach the client later, redacted, in `task_complete`'s
  `toolTrace`. There is **no "tool finished" event**: a client shows "Using {tool}…"
  until the next `token`, a `task_failed`, an escalation, or the end of the turn. It
  is not token-gated — a model can write a sentence and then call a tool.
  (`parseToolCallName`, `stream-events.test.ts`)
- **`error` payload is JSON** `{"message":"…"}`, not a bare string. A turn refused
  BEFORE it ran — every condition the non-streaming endpoint answers with a typed
  status (409, 410, 404, 413, 429, 403, 503) — also carries a `code`
  (`awaiting_approval`, `conversation_ended`, …, pinned in
  `UNCONSUMED_STREAM_ERROR_CODES` against `RestAgentEngineStreaming`): treat it like
  a 409 — withdraw the bubble and restore the draft. Branch on `code`, show
  `message`. (`stream-events.review.test.ts`, `turn-protocol.test.ts`)
- **Every `data:` line carries one delimiter space** —
  `RestAgentEngineStreaming.padDataLines` writes it. Strip exactly one; a token's
  own leading space follows it. (`sse.test.ts`, `conversation.test.ts`)
- **A stream can end without the blank line after its last frame**, and a CRLF can
  straddle two reads. `readSseStream` handles both and flushes the decoder.
  (`turn-protocol.test.ts`)
- **`inputField` arrives on `done` too.** Read it from every transport, not only
  the non-streaming snapshot. (`turn-protocol.test.ts`)
- **The route's environment must be sent** as `?environment=` on start; the backend
  defaults a missing one to production.
- **The turn output's `input` is the masked display copy** — `<secret input>` for a
  turn sent with `secretInput`. Use it when rebuilding. The engine scrubs a secret
  turn when it ends and masks older stored ones on read, so `input:initial` also
  reads `<secret input>`; the client-side mask is a second line of defence, not the
  only one. (`snapshot.test.ts`)
- **Math is `$$…$$` only.** Single dollars are prices, not formulas.
- **`done` is a trimmed snapshot** — only `conversationState` and
  `conversationOutputs`. It omits `undoAvailable`/`redoAvailable`, so re-read the
  snapshot to refresh them.
- **A turn that ends `AWAITING_HUMAN` with no tokens is ambiguous**: it paused, or
  it was dropped into an already-paused conversation. Only the state held before
  sending tells them apart. (`isSkippedTurn`, `isTurnPaused`, `turn-protocol.test.ts`)
- **Undo and redo answer an EMPTY 200** when they moved the step and **409** when
  there was nothing to move. Re-read the conversation either way.
- **`output[]` entries are not uniformly objects** — HITL writes its
  pending-approval placeholder and rejection message as bare strings.
- **Attachments reach the model ONLY via `attachment_N` context keys** whose `value`
  is an object carrying `storageRef`. A ref in the message text is silently ignored.
  Omit `fileName` when empty — the extractor backfills the stored name only when the
  key is **absent** (`getFileName() == null`); `""` is a String and suppresses the
  fallback. (`attachments.test.ts`)
- **The per-turn cap of 5 is a default, not a contract** —
  `eddi.attachments.max-per-turn` is a `@ConfigProperty` and no endpoint exposes the
  effective value. Same for the 20 MiB upload / 10 MiB forward caps. Do not write
  client code that treats any of them as fixed truth.
- **Upload cap ≠ forward cap.** A file in between is stored, returns 201, and is
  skipped at forward time — but the model is **not** left unaware: the forwarder
  substitutes a text note (`AttachmentForwarder` → `TextContent.from(...)`) saying
  the file was not sent and that `readAttachment` can fetch it. Surface
  `forwardableInline` so the user knows the bytes were not inlined, but do not claim
  the agent cannot see the file. It is an incomplete signal: a separate
  `max-forward-aggregate-bytes` cap can skip a file that reported `true`.
- **`setPublic(false)` is NOT a wire filter.** `isPublic()` is never read as a
  predicate anywhere in the backend — it is only copied into the snapshot. What
  actually keeps a key out of the response is the key-prefix allowlist in
  `ConversationMemoryUtilities.convertSimpleConversationMemory`, and
  `returnDetailed=true` bypasses it entirely. This package asks for
  `returnDetailed=false`, which is a client choice.
- **Failures answer `{error, code}`** — parse both (`errorPayload`).
  `ATTACHMENT_REJECTED` is a catch-all: a declared-vs-detected MIME **mismatch**
  (there is no type allowlist — an unknown type passes), the per-conversation
  file/byte quotas, empty files, and on Postgres any wrapped `SQLException`, so a
  database outage also arrives as a 400 "rejection". Only `error` says which. A
  body-less 413 from Quarkus is possible but not the normal path — this repo raises
  `max-body-size` to 25M precisely so in-cap uploads do not hit it.
- **A 409 means the input was never consumed** — restore it, do not leave it in the
  transcript as if sent. (`turn-protocol.test.ts`)
- **The rating endpoints answer `{error: "<CODE>", message}`** — the stable code is
  in `error` (`RATING_WINDOW_CLOSED`, `RATING_DISABLED`, …), unlike the `{error,
  code}` envelope above; `ratingErrorCode` parses it. Reply ratings are keyed by
  **step** (`/agents/{conversationId}/steps/{stepIndex}/rating`), step 0 being the
  greeting — see `rating-steps.ts`. (`ratings.test.ts`, `rating-steps.test.ts`)
