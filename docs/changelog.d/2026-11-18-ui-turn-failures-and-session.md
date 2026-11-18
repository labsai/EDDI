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

- **Leaving the page keeps the exchange.** The Workforce member thread reset its controller on unmount, which aborted
  the fetch so the backend discarded the turn. `ParkedControllers` (`@eddi/conversation`) now parks a controller whose
  turn is running for the same 120 s grace the Manager's chat has (`DETACHED_TURN_GRACE_MS`, shared with
  `DETACHED_STREAM_GRACE_MS`); returning within it shows the running turn without re-opening the thread, and the grace
  running out aborts it as before. `useLeaveGuard` asks before the tab is closed or reloaded, only while a turn is in
  flight, in `ChatPanel` (Manager chat, Workforce chat), the Workforce thread and the Chat widget.
  Files: `ui/packages/conversation/src/controller/parked-controllers.ts`, `react/hooks.tsx`,
  `ui/workforce/src/pages/workforce/workforce-thread.tsx`, `ui/packages/app-kit/src/components/chat/chat-panel.tsx`,
  `ui/chat/src/components/ChatWidget.tsx`.

- **One fetch path with token refresh.** The 15 bare `fetch` calls in `@eddi/app-kit` (group streams, attachments,
  secrets, ratings export, branding theme) now use `api.authorizedFetch`: token renewed before the request, one retry
  with a fresh token after a 401 (for the streams before the body is read), then the session-expired banner instead
  of a raw 401. The XMLHttpRequest upload renews the token first and reports a surviving 401 (`api.ensureFreshToken`,
  `api.reportUnauthorized`). `no-raw-fetch.ts` (hosted by the Manager's suite) fails on a new bare `fetch(` in
  app-kit outside a documented allow-list (the client, the reconnecting `BearerEventSource`, the boot probe).

- **Workforce session basics.** A user menu in the Workforce top bar (who is signed in, Logout) on the shared
  `useAuth`; nothing is shown with authentication off. `AuthProvider` (and `AppProviders`) take an `app`: Workforce
  signs out back to `/workforce` instead of landing in the Manager, and its failed-sign-in text no longer says "The
  Manager" (`auth.initFailedBodyWorkforce`, eleven locales). `workforce-threads` in `localStorage` was one key for
  everybody and survived sign-out; it is now `workforce-threads-<principal>`, every thread key (and the old unscoped
  one, which is deleted unread) is removed at sign-out. `user-display.ts` and the three menu strings (`auth.logout`,
  `auth.userMenu`, `auth.signedIn`) moved to `@eddi/app-kit` so both apps share them.
  Files: `ui/workforce/src/components/workforce/workforce-user-menu.tsx`, `hooks/use-workforce-threads.ts`,
  `ui/packages/app-kit/src/components/auth/auth-provider.tsx`, `lib/workforce-threads-storage.ts`.

- **Stale caches (I4).** Deleting, duplicating or setting up an agent now also refreshes the chat's deployed-agents
  list and the dashboard counts (`AGENT_MUTATION_INVALIDATIONS`); deleting or starting a conversation refreshes the
  dashboard's recent-conversations card and counts as well as the history (`CONVERSATION_MUTATION_INVALIDATIONS`).
- **`BearerEventSource` (I10).** A new connection ends the previous one (which no longer reconnects on its abort); a
  throwing listener neither skips the other listeners nor leaks the reader, which is released however the read ends; an
  unterminated block is never dispatched when the connection ends; the retry budget refills after the first COMPLETE
  event (a heartbeat comment counts), not after the first bytes, so a server that sends a few bytes and drops is given
  up on instead of retried every five seconds for good. Kept apart from the 403 hunk of `fix/manager-role-gating`.

### Behaviour changes an upgrader will notice

- The three UIs send an `Idempotency-Key` header. They run on the EDDI origin, so no CORS rule is involved; a widget
  embedded with an `apiBaseUrl` on another origin needs `idempotency-key` in `quarkus.http.cors.headers` (the
  default list does not have it yet; the backend configuration was out of scope for this branch).

- Workforce thread pointers (`workforce-threads`) are not carried over to the per-user key: after the upgrade each
  advisor thread starts a new conversation once (the old conversations stay in the history).
- Signing out of Workforce lands on `/workforce`; the Keycloak client must allow that post-logout redirect (the
  shipped realm uses `/*`).

### Not done

- `Last-Event-ID` for `BearerEventSource` (named in I10): the log and cluster streams do not send event ids.
- The group board's streams (`GroupDiscussion`) have no leave guard or grace of their own.
- Q7 beyond the `localStorage` item (phone chat list, board session scoping, ENDED display in the thread, …).
