## 🔒 fix(manager,chat): UI findings from the 2026-10-17 security review (2026-10-17)

**Repo:** EDDI (`fix/security-review-ui`)

### What changed and why

The UI half of the 2026-10-17 review (L1, L5, and the Manager side of M5). The backend halves are separate branches.

- **L1, Chat `?apiServer=`.** `http.ts` builds request URLs by concatenating the base and the path, and the allow-list let `?` and `#` through, so `?apiServer=%2Fadministration%2Fcluster%2Fcaches%2Fresync%3F` made the widget's start call a state-changing request to another same-origin path, carrying the page's credentials. `sanitizeApiServer` now parses the value against the page origin and accepts it only when the origin is unchanged, `search` and `hash` are empty, the pathname is exactly the input (so `..` and `.` segments are refused), and it has no `%`. Documented in [`chat-ui-embedding.md`](../chat-ui-embedding.md).
- **L5a, opt-in agent HTML.** `sanitizeAgentHtml` now forbids `class` (Tailwind utilities could paint a full-viewport fake prompt) and the `svg`, `math`, `image`, `feImage` and `use` elements (render-time fetches), and forces `target="_blank" rel="noopener noreferrer"` on every anchor through a hook registered only for the call.
- **L5b, ids in request paths.** New `lib/api/path-segment.ts`: `seg()` encodes an id and refuses `/`, `\`, `..`, whitespace, control characters and empty values; `nameSeg()` encodes a free-form name (connection name, tenant, secret key) and refuses only `.`, `..` and empty. Every id interpolated into a path under `lib/api/` now goes through one of them (about 160 call sites in 32 files). The chat panel drops an unsafe `?agentId=` / `?conversationId=` before issuing `GET /agents/<id>`.
- **L5c, `navigateAway`.** The OAuth link flow navigates only to an `https:` URL, or `http:` for localhost, 127.0.0.1 and [::1], without embedded credentials (`lib/authorization-url.ts`). Anything else is a failed start with the existing "Could not start linking." toast.
- **L5d, pinned Workforce responses.** The `localStorage` key now names the signed-in principal (`workforce-pins-<principal>:<board>`), and sign-out removes every `workforce-pins-*` key.
- **M5, Platform Operator trust.** `platform.operator` is writable by `eddi-editor`. The Manager now opens the operator chat, history and conversation restore only when the agent's descriptor carries `[eddi-platform-operator]` **and** the approval gate verifies on every version (`useOperatorTrust`, `lib/operator/operator-trust.ts`). Otherwise the drawer and the operator page show an "operator could not be verified" panel with *Check again*, and fail closed when a check cannot be completed. Before an **Upgrade** that keeps the stored instructions, or a **Reconfigure** that carries them over, the admin sees a line diff against the default (and the full stored text) and must tick a confirmation. The Manager also does nothing *to* an agent it could not verify (review follow-up on PR #1082): the marker is read at the agent's **current** version from the store, never at `config.version` (that number sits in the same editor-writable variable as the id, so its author chose which descriptor would be inspected); an unverified agent gets no *Check connection*, *Deactivate* or upgrade notice, and a paused one no *Turn back on*; *Reconfigure* provisions the replacement with `retirePredecessor: false`, which leaves the old agent exactly as it was and reports that in the superseded banner; and *Delete operator* becomes *Unregister operator* (`useUnregisterOperator`), which drops `platform.operator` and touches no agent. 21 new strings in all eleven locales.

### Design decisions

- The marker is writable by anyone who can edit the descriptor, so the check is a tripwire, not a signature. The real fix is reserving `platform.*` for `eddi-admin` on the backend (other branch). It is stated in the docs.
- An unverified agent is left alone rather than retired on Reconfigure or deleted on Delete, even though the admin asked: `platform.operator` can name *any* agent, so a retire-and-replace flow would be the one-click way for an editor to have an admin permanently delete someone else's agent and its resources. The admin gets the Agents screen instead, where the agent is shown for what it is.
- `seg()` throws rather than sending a mangled request. Free-form names get the lighter `nameSeg()` because existing tests (and real tenants and secret keys) contain spaces and slashes that `encodeURIComponent` already makes inert.
- No change to `system-prompt.ts` or `tool-scopes.ts`, so `operator-revision.json` is not bumped.

### Not done / follow-up

- The Stryker scope includes `src/lib/operator/**`; the mutation run was not repeated for the two new files there (`operator-trust.ts`, `prompt-diff.ts`).

**Files:** [`ChatWidget.tsx`](../../ui/chat/src/components/ChatWidget.tsx), [`markdown-safe.tsx`](../../ui/manager/src/lib/markdown-safe.tsx), [`path-segment.ts`](../../ui/manager/src/lib/api/path-segment.ts), [`authorization-url.ts`](../../ui/manager/src/lib/authorization-url.ts), [`workforce-pins.ts`](../../ui/manager/src/lib/workforce-pins.ts), [`operator-trust.ts`](../../ui/manager/src/lib/operator/operator-trust.ts), [`operator-untrusted.tsx`](../../ui/manager/src/components/operator/operator-untrusted.tsx)
