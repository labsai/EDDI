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

**Files:** [`config-editor-layout.tsx`](../../ui/manager/src/components/editors/config-editor-layout.tsx), [`editor-validity.ts`](../../ui/packages/app-kit/src/components/editors/editor-validity.ts), [`json-editor.tsx`](../../ui/manager/src/components/editors/json-editor.tsx), [`agent-wizard.tsx`](../../ui/manager/src/pages/agent-wizard.tsx), [`use-resolved-version.ts`](../../ui/manager/src/hooks/use-resolved-version.ts), [`cascade-save.ts`](../../ui/packages/app-kit/src/lib/api/cascade-save.ts).
