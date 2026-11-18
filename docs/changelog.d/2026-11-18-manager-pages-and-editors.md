## 🐛 fix(manager): editors, guard rails and misleading pages from the November review (2026-11-18)

**Repo:** EDDI (`fix/manager-pages-and-editors`)

### What changed and why

Fixes for the Manager findings of the November 2026 code review, in three blocks (one commit each).

**1. Editors no longer lose or silently mis-save work**

- A failed save keeps the draft and the layout says why (`Not saved: <reason>`); the "what was saved" marker no longer survives a failure, so a later refetch cannot overwrite the draft.
- Form editors report invalid fields to the shared layout through `useReportInvalid` (`@eddi/app-kit/components/editors/editor-validity`). Save, Save & Test and Ctrl+S are disabled with the reason while any field is invalid; the LLM temperature is the first field wired. A field is only checked while its form is mounted, so the JSON tab still relies on its own JSON validation.
- The save response's `warnings` array (`{"warnings":[…]}` beside the new version) is shown under the header by every editor that saves through the layout.
- The agent wizard asks before browser Back, a sidebar click or a reload throws its input away.
- Changing the provider in the wizard says what it reset and restores what was typed for a provider when the user switches back. The API key still never travels to another provider.
- The JSON editor's format-on-mount no longer marks an unedited document dirty; its loading text is translated.
- `channel-detail`, `connection-detail` and the group workspace resolve the current version for a link without `?version` instead of reading version 1.

**2. Guard rails where the backend is silent**

- Regex-filter patterns are checked as they are typed (`new RegExp`, case-insensitive like the backend). A line the browser rejects blocks Save, even with the sheet closed; syntax only Java understands (possessive quantifiers, atomic groups, inline flags, `\Q..\E`, `\A \z`, class intersection) is shown as "cannot be checked here" and never blocks.
- The secret picker nudges when a pasted value looks like a literal key and offers "Store in vault" (the typed value pre-fills the create dialog) or an explicit "Keep as plain text". The create dialog says when it has to grant the secret to every agent because the agent id is unknown (it already grants `[agentId]` when known). The version diff masks values of credential-shaped keys (a short fingerprint keeps a change visible).
- "Add Retry Policy" starts with no retries for non-idempotent methods (POST, PATCH) and warns once they are enabled. The request body editor explains that values are inserted as written unless the server escapes them, and points to `json:serialize`.
- RAG ingestion: unique local row ids, the server's reason for a refused upload, up to four failed status checks tolerated before an ingestion is called failed, files over 10 MB are not read. Editing the embedding provider, model, dimension or chunk settings of a knowledge base that has ingested documents shows a warning that saving does not re-embed them.

**3. Pages that mislead**

- The live log tail follows the newest line once the 500-entry view is full; the History level filter and its counts say they cover the loaded entries only (the history endpoint has no `level` parameter).
- Delete agent names the agent, refreshes every cache that lists agents (chat, groups, channels, Studio), and on a `409` shows the server's message and every list it carries, with an "End all active conversations" switch that retries with `endAllActiveConversations=true`.
- Oversight tabs answer the arrow keys, Home and End; the time window follows the clock. The dashboard names agents from the full list. Channels: one duplicate per click with feedback, a warning for an empty Slack channel id, a note that the router picks changes up within about a minute, and a note when the trigger `help` is refused.
- One clipboard helper (`copyToClipboard`) that awaits the write and reports failure; used by the synthesis copy and the chat copy buttons. A group with no members disables the composer. A conversation in ERROR lists the `taskErrors` of its steps. The conversation delete dialog stays until the delete finished. The typed rotate-secret value and the create-secret draft are cleared when their dialog closes. The secrets, schedules, variables, parser and create-channel dialogs now use `AccessibleDialog` (focus trap and focus return).

### Design decisions

- Field validity travels through a context (`useReportInvalid`) so any editor can block Save without the layout knowing its fields. The alternative, a `validate` prop on every editor, would have meant editing all of them.
- The wizard keeps the API key tied to its provider (restored on switching back, never sent to another vendor) rather than carrying it across, which is what BR8 asked for in its strongest reading.
- The delete 409 handler renders whatever lists the body carries and never names fields, because the backend contract is still moving.

### What is left

Not done here: group outcomes, member conversations and cancel state (U2, U3, U5, U6), role gating (U8, I3), the Platform Operator (V1-V4), the lint rule for raw `fetch`. The RAG vector-space endpoints (`/vector-space`, `/reembed`) are not called yet.

**Files:** [`config-editor-layout.tsx`](../../ui/manager/src/components/editors/config-editor-layout.tsx), [`editor-validity.ts`](../../ui/packages/app-kit/src/components/editors/editor-validity.ts), [`json-editor.tsx`](../../ui/manager/src/components/editors/json-editor.tsx), [`agent-wizard.tsx`](../../ui/manager/src/pages/agent-wizard.tsx), [`use-resolved-version.ts`](../../ui/manager/src/hooks/use-resolved-version.ts), [`cascade-save.ts`](../../ui/packages/app-kit/src/lib/api/cascade-save.ts).
