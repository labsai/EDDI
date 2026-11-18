## 🐛 fix(ui): turns are sent once, failures are classified, the session survives idle tabs (2026-11-18)

**Repo:** EDDI (`fix/ui-turn-failures-and-session`)

### What changed and why

Code review wave 2, section Q (shared client code of the Manager, Workforce and the Chat widget).

- **`Idempotency-Key` on every turn.** The shared conversation controller generates a key once per user submission and
  sends it on `say`, streaming `say` and managed `say`; it keeps it while the submission is unsettled (a refused or
  failed turn, a 408, a dropped connection) so the Retry button or the draft sent again carries the same key and the
  backend runs the turn once or answers from the stored one. A new submission, an edited draft, another conversation
  or a settled turn gets a new key. A replayed `done` is shown as the reply, not as an error.
  Files: `ui/packages/core/src/conversation.ts`, `ui/packages/conversation/src/controller/agent-conversation.ts`,
  `transport.ts`, `ui/packages/app-kit/src/lib/api/chat.ts`, `hooks/use-chat.ts`, the Workforce thread transport.

- **One failure taxonomy in the shared controller** (`turn-failure.ts`): not accepted (the draft comes back, the
  message leaves), accepted-but-outcome-unknown (408, 502, 504, a connection that dropped after the send, a stream
  that ended with nothing: the message stays, the notice says the agent may still be working and a retry carries the
  same key), ended (a 410 or `conversation_ended` is ENDED in every app, the Manager no longer pre-empts it),
  paused (only a 409 after which the conversation reads AWAITING_HUMAN, or `awaiting_approval`; Workforce no longer
  labels every 409 "paused"), failed, and empty. The apps render from `failure.class`. A `done` with no output, a
  stream that ends with zero tokens and a swallowed `task_failed` now show a notice, and a `done` text that is shorter
  than what streamed (and is not the interim-commentary case) says it replaced it. Unknown events and extra fields on
  `done` are ignored; new stream error codes are mapped in one table (`STREAM_CODE_CLASSES`) and the whole error
  payload reaches the host (`failure.payload`). Four new `conversation.*` strings in all eleven locales.
  Files: `ui/packages/conversation/src/controller/turn-failure.ts`, `agent-conversation.ts`, `notices.ts`,
  `ui/packages/app-kit/src/hooks/use-chat.ts`, `ui/workforce/src/pages/workforce/workforce-thread.tsx`.

### Behaviour changes an upgrader will notice

- The three UIs send an `Idempotency-Key` header. They run on the EDDI origin, so no CORS rule is involved; a widget
  embedded with an `apiBaseUrl` on another origin needs `idempotency-key` in `quarkus.http.cors.headers` (the
  default list does not have it yet; the backend configuration was out of scope for this branch).

