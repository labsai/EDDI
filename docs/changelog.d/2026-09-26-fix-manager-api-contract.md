## 🐛 fix(manager): align the Manager's API client and MSW mocks with the real backend contract (2026-09-26)

**Repo:** EDDI (`fix/manager-api-contract`) — Manager only (`ui/manager`), no backend change.

The Manager's suite was green while a dozen calls disagreed with the backend: the
MSW mocks encoded the same wrong contract as the app. This branch fixes the app
side of each mismatch and makes the mocks answer the way the backend does, so the
tests would now fail on a regression.

### What changed and why

- **Undo / redo (UI review High 1).** `POST /agents/{id}/undo|redo` answer an
  empty 200 (moved) or 409 (nothing to move); the client typed them as returning a
  snapshot, so the hook threw on `undefined` *after* the server had undone the
  step and every further click undid another turn. `undoConversation` /
  `redoConversation` now resolve `true`/`false`, and the hook re-reads the whole
  conversation (`returnCurrentStepOnly=false`) for the transcript and both flags,
  ignoring the read if the user switched conversation meanwhile. Undo availability
  is now set after every send (streaming `done` and non-streaming) from the
  backend's `undoAvailable`, with a `steps > 1` fallback (was `> 0`, which offered
  Undo on a fresh conversation). Tests: `chat.test.ts`, `use-chat.test.tsx`
  (`useUndoConversation`, `useRedoConversation`, `undoRedoFlags`, "enables Undo
  once a turn lands").
- **Agents / Workflows lists stopped at 50 (High 2).** The infinite queries sent
  `allPages.length * 50` as `index`; the backend's `index` is a page index
  (`skip = index * limit`). Now `allPages.length`. The sync page and the import
  dialog's target pickers only ever loaded the first page, so they now use
  `useAllAgentDescriptors`, which keeps paging until a short page — past 50
  local agents, sync no longer creates duplicates. Tests: `use-agents.test.tsx`
  ("asks for page 1…", `useAllAgentDescriptors`).
- **Version pickers offered only the latest version (High 3).** They asked the
  store listing `descriptors?filter=id&version=v`, which has no `version`
  parameter. New `getDescriptorVersions(id, latest)` reads
  `/descriptorstore/descriptors/{id}?version=v` per version (bounded concurrency,
  unreadable versions skipped); used by agent, workflow and resource version lists.
  Tests: `descriptors.test.ts`, `use-agents.test.tsx`, `use-workflows.test.tsx`.
- **`manager-user` (High 11).** Group start / follow-up / continue fell back to
  `userId: "manager-user"`, which `OwnershipValidator` rejects with 403 for every
  non-admin (and stamps admins' runs with a synthetic owner). The field is now
  omitted unless a caller names a user, so the backend resolves the signed-in
  caller. Tests: `groups-wave-parity.test.ts` ("discussion owner").
- **Properties page.** `GET /propertiesstore/properties/{userId}` returns raw
  values, not `Property` wrappers, and 204 for a user with none. Types, rendering
  and the empty case fixed; the "Delete all" dialog now says it removes every
  global user-memory entry of the user (11 locales). Tests: `properties.test.ts`,
  `pages/__tests__/properties.test.tsx`.
- **Audit `toolCalls`.** The backend field is a map `{calls: [...]}` of trace
  events; new `auditToolCalls()` normalises it (pairs results with calls, skips
  budget/HITL markers) for the pipeline trace and the prompt viewer. Tests:
  `audit.test.ts`, `prompt-viewer.test.tsx`.
- **Memory Inspector** now sends `returnCurrentStepOnly=false` (backend default is
  `true`, so it only ever showed the last step). Test: `conversations.test.ts`.
- **Validation errors.** `extractErrorMessage` now reads Quarkus's Bean Validation
  report (`violations[].message`, plus the RESTEasy Classic shapes), so `@Valid`
  400s on group saves / discuss / attachment limits show the constraint message
  instead of "Bad Request". Test: `api-client-response.test.ts`.
- **`getToolHistory`** returns a `ToolExecutionTrace` object (`toolCalls`,
  totals), not an array; type and mock fixed. Tests: `tool-metrics.test.ts`,
  `use-tool-metrics.test.tsx`, `use-admin-hooks.test.tsx`.
- **Deployment status at any version.** Every save bumps the version, and the
  per-version status endpoint then says NOT_FOUND although an older version is
  live. `useDeploymentStatuses` and the chat picker now consult
  `GET /administration/{env}/deploymentstatus` (one shared listing per
  environment) and adopt an older READY/IN_PROGRESS version, flagged
  `deployedVersion`; the card's chip shows `vN` and its deploy toggle still acts
  on the card's own version. Tests: `deployment-environments.test.ts`,
  `use-agents.test.tsx`, `agent-card.test.tsx`.
- **Dashboard counts.** The conversation count asked for 1000 but the backend
  clamps to 100, and "100" was shown as a total. It now asks for 100 and renders
  a page-filling count as "100+" (agents/workflows likewise at 1000). Tests:
  `dashboard.test.ts`, `pages/__tests__/dashboard.test.tsx`.
- **MSW handlers.** Mocks now use page-index pagination, clamp conversation
  listings at 100, return empty 200s for undo/redo, raw property values (204 for
  an empty user), the `{calls}` audit shape, the trace-object tool history, an
  environment deployment listing, and 201 for `POST /backup/import`. 48 shadowed
  duplicate handlers were deleted (a second schedule store, coordinator, audit,
  quota, currentversion, descriptor and tool-metrics copies, the `agents/:id`
  memory-inspector copy, the factory `/descriptors` copy). Four of the removed
  copies were the ones that actually *answered*, so their routes now answer from
  the `backupSyncHandlers` copy instead: `POST /backup/export/:agentId`
  (Location `agent-export.zip`, was `test-agent-1.zip`),
  `GET /backup/export/:filename`, `POST /backup/import/preview` (the current
  `ImportPreview` shape, was a pre-6.x one) and `POST /backup/import` (201 with
  `imported-<timestamp>`, was 200 with a fixed `imported-agent`; a merge still
  lands on `agent1` v2). `openapi-contract.test.ts`'s `KNOWN_DUPLICATE_ROUTES`
  ratchet shrinks from 58 entries to the 10 deliberate `:id` fall-through layers.

### Review follow-up

- **Agent detail Environments panel** now uses `isLiveAtRequestedVersion`: an
  environment live at an older version shows `vN` and offers **Deploy** for the
  page's version, never **Undeploy**. Undeploying a version that is not running
  is accepted by the backend, which then disables all of the agent's schedules
  while the old version keeps serving. Test: `agent-detail.test.tsx`
  ("shows an older live version per environment…").
- **Sync auto-match waits for the whole local list** (`isComplete`); remote
  agents received earlier are matched when it completes, and a failed page shows
  an error with Retry instead of matching a partial list (2 new keys, 11 locales).
  Tests: `sync-page.test.tsx`.
- **`getDescriptorVersions` skips only 404s**; any other error fails the list.
  Test: `descriptors.test.ts`.
- **Undo/redo**: one move at a time (a call while one is in flight is dropped),
  the chat panel disables both buttons while either is pending and toasts
  failures, and a failed re-read after a move disables both buttons (the
  transcript can no longer be trusted). Undo, redo and rerun all ignore their
  result when the user switched conversation meanwhile; binding them to the chat
  store's conversation epoch (fix/manager-chat) is a follow-up. Tests:
  `use-chat.test.tsx`.
- **Deployment fallback adopts only READY** listing rows — an adopted,
  unpolled IN_PROGRESS kept the card's toggle disabled. Documented gap: the
  listing holds the highest deployed version whatever its status, so when that
  one is ERROR an older READY version is not visible (no backend "latest READY"
  listing). Test: `deployment-environments.test.ts`.
- **`auditToolCalls`** attaches `tool_error` reasons (budget, quota, HITL cap)
  to the refused call, or lists an orphan refusal. Test: `audit.test.ts`.
- **`useAllAgentDescriptors` has its own cache key**, so the Agents list does
  not inherit (and re-fetch on every invalidation) every page Sync loaded. Test:
  `use-agents.test.tsx`.

### Compatibility

No REST, config or MCP shape changes. Compatible with current `main` and with the
open backend PRs: #840 (workspace scoping) may narrow the deployment listing or
descriptor reads to what the caller can see — the fallback then simply does not
apply; #839 (`pauseId`), #842 (schedules) and #836 (vault adoption) touch
endpoints this branch does not change; #831 does not affect the discuss body.
Group discussions started from the Manager with auth **off** are now owned by
`anonymous` (the backend's default) instead of `manager-user`.

### Not in this branch

The chat picker still de-duplicates agents by name (fix/manager-chat); the
`groups.ts` NUL byte is chore/ci-hardening's. Group/workforce create-flow error
messages benefit from the violation parsing, but their generic-error wrappers
belong to fix/manager-workforce-groups.

```decision-log
| 2026-09-26 | Manager treats a deployment at an older version as "live" (flagged `deployedVersion`) instead of "Not deployed" | Every save bumps the version and the per-version status endpoint answers only for the exact version | A backend "any version" query parameter (needs a contract change); asking the listing for every card (N requests) |
```
