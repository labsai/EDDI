## 🐛 fix(manager): app shell — unsaved-changes guard on every exit, per-route error boundaries, auth refresh (2026-09-26)

**Repo:** EDDI (`fix/manager-shell`)

Item 25 of the 2026-09-25 UI review: the Manager's app shell (router, layout,
auth, providers, global components). Frontend only; no REST shape, stored
config or env var changes.

### What changed and why

**Unsaved edits lost on in-app navigation (UI High 14).**
`useUnsavedChangesGuard` covered `beforeunload` only — its own comment said
`useBlocker` needed a data router and the app used `<BrowserRouter>`. So the
sidebar, a breadcrumb, the command palette and Back all discarded a dirty
editor without a prompt.
- `main.tsx` now creates a data router (`createBrowserRouter`) with a single
  splat route; the route table stays declarative `<Routes>` in `app.tsx`, so
  routing tests and the lazy-route split are unchanged.
- Editors report into one registry (`lib/unsaved-changes.ts`) through the same
  hook they already called; one `UnsavedChangesNavigationGuard` at the router
  root blocks a path change while anything is dirty and asks
  (`UnsavedChangesDialog`). React Router allows only one blocker, which is why
  it is a registry and not a blocker per editor. Query-string changes (version
  switch, tabs) are not blocked.
- Deliberate exits while dirty (after a delete; connection-detail's own
  "leave?" prompt) call `allowNextNavigation()` so the user is not asked twice
  (`connection-detail.tsx`, `workflow-detail.tsx`, `resource-detail.tsx`).
- Agent Studio: switching pipeline stage remounts the editor (local state, not
  a navigation), so `pipeline-railroad.tsx` asks before switching when an edit
  is pending.

**Per-route error boundaries.** One root boundary replaced the whole shell
(sidebar, top bar, chat drawer with its conversation) on any page error.
`SuspendedOutlet` now carries an `ErrorBoundary` keyed on the path: the
fallback fills the page area and navigating away recovers. `lazyPage` no
longer caches a rejected import — `React.lazy` does, which is why "Try Again"
could never recover a failed chunk load; it now calls the import again (some
browsers keep a failed module in their module cache, where only a reload
helps). An `errorElement` (`RouteErrorPage`) on the splat route replaces React
Router's unstyled English default for errors thrown by the providers.

**Auth.**
- Tokens are refreshed ahead of expiry (every 20 s, when < 60 s remain, and on
  tab re-focus) instead of only after `onTokenExpired`, and `ApiClient`
  checks freshness before each request and retries a 401 once after a forced
  refresh (`TokenRefresher`, `lib/keycloak-session.ts`).
- A transient refresh failure (network, Keycloak 5xx) no longer logs the user
  out; only a rejected refresh token (keycloak-js clears its tokens) sends the
  user to sign in.
- A failed Keycloak init no longer renders the app unauthenticated with every
  call 401'ing: it shows "Sign-in is unavailable" with Try again; an init
  without a session shows a sign-in prompt.
- Realm roles are re-read on every refresh (and the token re-published), so
  role changes apply without a full sign-in.
- StrictMode (`npm run dev` with Keycloak) could never authenticate: the second
  effect run called `init()` again, keycloak-js threw, and the first result was
  discarded. Both runs now await one init promise.

**Other shell findings.**
- Escape in an open `AgentPicker` closed the whole parent dialog (Trigger,
  Edit Grant) and discarded the form. `AccessibleDialog` now ignores an Escape
  a child already handled (`defaultPrevented`); the picker marks it handled.
- `AccessibleDialog` backdrop clicks never closed it — the click handler sat
  under the centring layer. The layer owns the click now (target check).
- Language selector showed "English" for `de-DE` browsers and English could
  not be chosen: it bound the detected language, not `resolvedLanguage`.
- Mobile sidebar closes after a navigation.
- `ThemeProvider` read/wrote `localStorage` without try/catch — blocked storage
  meant a blank page; an invalid stored value is ignored too.
- Toasts follow the theme (`ThemedToaster`; sonner ignores the `dark` class).
- Missing invalidations: import / merge / upgrade / sync now refresh workflows,
  resources, agent detail/descriptor/prompt, dashboard, orphans and latest
  versions, not only agents; operator activate / reactivate / deactivate / reset
  refresh the agent lists, workflows, resources, variables and deployed-agent
  list, not only the operator's queries.
- Hard-coded strings / a11y: sidebar (version check, collapse/expand), top bar
  (breadcrumb landmark, language select label), auth screens, `AlertDialog`
  defaults (Delete / Cancel / Close), AgentPicker hint and toggle label,
  create-resource placeholders; `formatRelativeTime` (used by the version picker
  and ~15 other call sites) now uses `Intl.RelativeTimeFormat` in the on-screen
  language. `create-resource-dialog` is now an `AccessibleDialog` (dialog role,
  accessible name, focus trap, labelled close button).
- Workforce `workforce-threads` / `workforce-templates` localStorage keys are
  per signed-in user (`lib/user-storage.ts`); threads (pointers to server
  conversations) are also cleared at logout. Templates are deliberately not
  cleared — they exist only in that browser — but another user no longer sees
  them. With auth disabled the plain keys are used as before.

### Review follow-up (same branch)

- **Backdrop close required the press to START on the backdrop.** A drag that
  began in an input and was released over the dimmed area also delivers a click
  to the layer (the nearest common ancestor), which closed the dialog and lost
  the form — a data-loss path this branch had introduced. The layer now records
  whether the mousedown was on itself.
- **A slow Keycloak no longer stalls requests.** `ensureFresh` waits only when
  the token has actually expired; inside the refresh window it refreshes in the
  background. Before, every request in the last minute of a token's life waited
  on keycloak-js's un-timed fetch.
- **Forced refreshes on 401 are rate-limited** (one per 10 s), so a 401 that is
  not about token age does not hit Keycloak's token endpoint on every request or
  poll; a 401 whose token was already swapped by a background refresh is retried
  with the new token without forcing another. The code now says why replaying a
  POST is safe: Quarkus OIDC rejects before the resource method runs.
- **Pre-upgrade Workforce data is adopted, not hidden.** The first time a
  signed-in user's own key is empty while the old shared key holds data, it is
  moved into their key (once — the next user does not inherit it). Keys use the
  OIDC `sub` (`AuthUser.id`), falling back to the username, because
  `preferred_username` can be renamed.
- **Agent Studio on mobile/tablet:** the editor tab is hidden, not unmounted,
  when the user switches to Pipeline or Chat, so a pending edit survives.
- **Import/sync also invalidates schedules and connections** (an agent ZIP
  carries both); snippets are a resource type and were already covered.
- A cancelled or refused sign-in (`error=access_denied`) now says "Sign-in did
  not complete" instead of "could not reach the sign-in service"; the lost-
  session path no longer calls `login()` on top of keycloak-js's own redirect.
- `formatRelativeTime` caches one `Intl.RelativeTimeFormat` per language, and
  the tests compare against `Intl` rather than English literals, since the
  exact English wording comes from the engine's CLDR data.

### Decisions

- The route table stays declarative inside a single splat data route rather
  than being rewritten as route objects: `useBlocker` only needs the data
  router context, and a rewrite would have touched every route and the lazy
  loading for no behavioural gain.
- The guard lives in `AppRoot`, outside `App`, because `useBlocker` throws
  under the `MemoryRouter` every page and routing test renders `App` in.
- Legacy unscoped Workforce data is moved into the first signed-in user's key
  on that browser (it was visible to every user of the browser before, so this
  exposes nothing new), rather than hidden.

### Overlap with other open branches (left for them)

- Hard-coded wizard step labels, chat panel titles and the group wizard belong
  to `fix/manager-chat` / `fix/manager-workforce-groups`
  (`agent-wizard.tsx`, `chat-panel.tsx`, `group-wizard.tsx`); the Workforce
  history row (`role="option"` containing a delete button) is in
  `workforce-history.tsx`, owned by `fix/manager-workforce-groups`; the chat
  agent listbox is in `chat-panel.tsx` (`fix/manager-chat`).
- Invalidations for delete-group-with-members (`use-groups.ts`), the
  agent-prompt cascade (`use-agent-prompt.ts`) and chat history after start
  (`use-chat.ts`) are in hooks those branches own.
- Raw-`fetch` call sites (SSE, `secrets.ts`, uploads) read
  `api.getAuthHeader()` synchronously and do not get the 401 retry; the
  proactive refresh keeps their token fresh.

### Verification

`tsc -b`, ESLint on changed files, `check-i18n` and the Manager vitest suite.
New tests: `unsaved-changes-navigation-guard`, `suspended-outlet`,
`api-client-token-refresh`, `keycloak-session`, `auth-provider-keycloak`,
`accessible-dialog-dismiss`, `themed-toaster`, `shell-invalidations`,
`route-error-page`, plus cases in `agent-studio`,
in `lazy-page`, `top-bar`, `app-layout`, `theme-provider`, `pipeline-railroad`,
`use-workforce-threads`, `utils`, `create-resource-dialog`; the
`auth-provider` test that could not fail (`getByTestId(a) || getByTestId(b)`)
now asserts.

The Keycloak provider is covered by unit tests against a fake keycloak-js only.
The `Auth E2E (Keycloak)` CI job drives the API with `request`, not the SPA, so
it does not exercise the refresh loop, the session-lost path or StrictMode init.
