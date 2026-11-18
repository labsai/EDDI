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

### Behaviour changes an upgrader will notice

- The three UIs send an `Idempotency-Key` header. They run on the EDDI origin, so no CORS rule is involved; a widget
  embedded with an `apiBaseUrl` on another origin needs `idempotency-key` in `quarkus.http.cors.headers` (the
  default list does not have it yet; the backend configuration was out of scope for this branch).

### What is left

See the remaining entries of this file as the branch grows.
