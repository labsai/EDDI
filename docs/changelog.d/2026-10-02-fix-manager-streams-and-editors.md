## 🐛 fix(manager): live streams survive a token refresh, editors keep what was typed (2026-10-02)

**Repo:** EDDI (`fix/manager-streams-and-editors`) — Manager only (`ui/manager`); no Java changed.

The second of three PRs answering the Manager findings of the 2026-10-02 review (§4.10): the fixes
that need no new user-facing strings. Independent of `fix/manager-ui-review` and
`refactor/manager-dead-exports`. Each behavioural fix has a regression test that was
mutation-checked.

### Streams and state

- **SSE token:** `BearerEventSource` accepts a header *provider*, asked again on every reconnect;
  the log and coordinator streams pass one. A reconnect after a token refresh used to resend the
  expired token, get a 401 on every attempt and stop — the live streams died roughly one token
  lifetime after the page was opened.
- **Logs:** a reseed fetched for the previous filter no longer merges into the new filter's view
  (and the unused `useRecentLogs` hook is gone from the same file).
- **`useAgent`** keeps the previous document as placeholder only for the same agent: with a blanket
  `keepPreviousData`, navigating from agent A to agent B showed A's configuration under B's id.
- **Operator:** Stop files the trace of what the stopped turn had already done under its answer.
- **GDPR:** an erasure invalidates every cached read it can make stale — user memories, properties,
  conversations (and the dashboard's recent-conversations card), user conversations, group
  conversations, schedules, audit and the restriction badge; the restriction lookup waits for
  typing to pause instead of firing per keystroke.
- **Versions:** group detail resolves the current version when the URL has none (it used to open
  version 1 and 409 on every save); resource detail writes the versions a cascade save created
  back into the URL (`replace`), so a reload or the back link no longer address stale versions.

### Editors

- **Number fields:** `parseFloat(raw) || fallback` turned a typed `0` into the fallback
  (`pruneStaleAfterDays: 0`, `maxWritesPerTurn: 0`). The debounced inputs moved to
  [`debounced-inputs.tsx`](../../ui/manager/src/components/editors/debounced-inputs.tsx), parse with
  `Number.isFinite`, respect `min`, and **flush** a pending edit on blur and unmount instead of
  dropping it when a section collapses. The test that re-implemented and asserted the bug is gone.
- Review follow-ups on the inputs: `min` now clamps the fallback too (a cleared `min={1}` field
  never commits `0`); the field shows the value it actually committed when clamping changed it;
  and a `commitKey` (the agent id) binds a pending edit to the agent it was typed for — when an
  in-app navigation reuses the input for another agent, the edit is flushed through the previous
  agent's save first instead of being saved into the new one. The capability attribute rows now use
  the same input, so their edits flush on collapse too.
- Group detail: a failed current-version lookup shows a retryable error instead of loading
  version 1. GDPR erasure removes the erased user's inactive cache entries (invalidation alone left
  them to paint on the next mount) and refetches active ones. Operator Stop also drops stream
  frames that were already buffered.
- **Rules editor:** config keys rename through `RenamableKeyInput` (commit on blur, refuse a taken
  key) and "Add config" picks a free `keyN` — renaming on every keystroke merged two rows.
- **Editor registry:** `EXTENSION_TO_SLUG` gained the snippet store's real extension, the singular
  `ai.labs.snippet`; its test now checks every resource type's own extension instead of listing them.
- Channel targets are keyed by a stable id, so removing one no longer hands its half-typed trigger
  to the card that slides into its place.
- Schedule toasts carry the backend's reason; remove buttons have translated, named labels.

### Dev mode and wording

- `keycloak.init()` ran twice under StrictMode and the second call threw, so `npm run dev` with
  Keycloak never finished signing in; both effect runs now share one init promise.
- The Vite proxy lacked `/usermemorystore`, `/workspaces`, `/spacestore` and `/llm`;
  `EDDI_BACKEND_URL` points the dev proxy at another backend.
- `gate-guard.ts`, `self-guard.ts`, `tool-scopes.ts` and `ui/manager/AGENTS.md` now say the operator
  guards run in the Manager's approval UI only — defence in depth; an approval over REST, MCP or
  Slack never reaches them and the server's own check is the enforcement there. Comments only: the
  operator prompt, allow-list and revision are unchanged.
