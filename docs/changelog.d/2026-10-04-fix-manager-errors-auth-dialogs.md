## fix(manager): error surfacing, session handling and shared dialogs (2026-10-04)

**Repo:** EDDI (`fix/manager-errors-auth-dialogs`), `ui/manager`

### What changed and why

From a UX review of the Manager:

- **Auth.** A failed Keycloak init used to render the whole app unauthenticated, so every page read "Something went wrong". It now shows a "Sign-in failed" screen with **Sign in again**. The API client renews the token before each request (`updateToken(30)`, so a token that expired in a throttled tab is not sent), and on a 401 forces one refresh and retries once. When that fails the Manager shows a non-destructive "session expired" banner over the still-mounted app instead of calling `keycloak.logout()` immediately, which dropped unsaved work.
- **ErrorState** accepts the error object and says 401 / 403 / 404 / "can't reach EDDI" in its own words, with the server's message beneath. About 25 callers now pass `error`.
- **Global mutation fallback** (`lib/query-client.ts`): a failed mutation that nobody reported is toasted with the server message. It stays quiet for `meta: { silent: true }`, for mutations with a hook-level `onError`, and when the caller toasted an error within 50 ms (call-site handlers cannot be introspected, so this is how double toasts are avoided). 401s are left to the session banner.
- **Dashboard** no longer shows zeros for failed reads: all-failed is an error state, a partial failure shows "—" on the affected card plus a retry notice. The vault strip says "checking" while loading, the skeleton draws 3 cards (as many as render) and conversation states are translated.
- **Orphans**: a failed scan renders the error, a refused purge shows the server's reason (409), each orphan links to its detail page, and Purge All is an `AlertDialog` that needs the orphan count typed.
- **AlertDialog** ignores Escape / backdrop / X while its action is pending, and its default labels and the sr-only "Close" are translated. "Authenticating…" is translated too.
- **Capabilities** shows agent names instead of raw ids (no external-link icon), has an explanatory empty state, labelled search / strategy controls and `aria-expanded` on rows.

### Decisions

- The QueryClient moved into `lib/query-client.ts` (a `MutationCache` needs to be built with it); `main.tsx` only imports it.
- Not done: the `404 GET /variablestore/variables/default/platform.operator` console line. `readOperatorConfig` already treats the 404 as "not configured"; the line is the browser's own network log and cannot be suppressed without a backend change (e.g. a 200 with an empty body).
- Raw-`fetch` modules (SSE, `text/plain` chat) still read `api.getAuthHeader()` without a pre-flight refresh.
