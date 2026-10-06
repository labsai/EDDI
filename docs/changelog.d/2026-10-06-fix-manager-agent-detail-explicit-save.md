## 🐛 fix(manager): agent detail saves on Save, not on every edit — and unsaved edits are guarded app-wide (2026-10-06)

**Repo:** EDDI (`fix/manager-agent-detail-explicit-save`)

### What changed and why

**Agent detail no longer auto-saves.** Every toggle, select and (after a 600 ms
debounce) every keystroke in the agent detail page's ten config sections —
workflows, A2A, security, capabilities, user memory, memory policy, session
management, conversation review, HITL, channels — PUT the whole agent document
on its own. Each PUT creates a new agent version, so one sitting of edits made a
dozen versions, there was no way to back out of a change, and the page behaved
unlike every other editor in the Manager. It now works like the workflow and
resource editors:

- Sections edit one page-level **draft**; nothing reaches the backend until
  **Save**, which writes it as one version and follows it ("Saved as version N").
  **Discard** (confirmed) restores the stored version. Ctrl/Cmd+S saves.
- **Seeing what changed:** an *Unsaved changes* badge in the header, a
  **Modified** pill on every section whose fields differ from the stored
  version, a floating bar at the bottom of the (long) page with the count,
  Discard and Save, and **Review changes** — a diff of the draft against the
  stored version (`ResourceDiffViewer`).
- While there are edits, the version picker and Deploy/Undeploy are disabled
  (deploying acts on the *saved* version; the tooltip says so).
- A **409** keeps the edits, moves the page onto the newer version and re-applies
  only the blocks the user changed (`applyChangedFields`), so another client's
  change survives the next Save.
- The debounce timers and the per-agent save queue that existed only to make
  auto-save survivable are gone (`use-agent-section-save.ts` removed).

**Leaving with unsaved edits is now asked about everywhere.** The app uses
`<BrowserRouter>`, so React Router's `useBlocker` is unavailable and
`useUnsavedChangesGuard` only covered tab close/reload. A new
`UnsavedChangesNavigationGuard` (mounted once in `app.tsx`) holds in-app link
clicks (sidebar, breadcrumbs, back links, cards), the browser back/forward
buttons and command-palette jumps (`requestNavigation`) while any registered
page is dirty, and asks **Keep editing / Discard changes / Save & leave**. Every
existing caller — resource editors, workflow editor, connections, the Workforce
agent sheet — gets it without change; agent detail and the workflow editor pass
`onSave` to offer Save & leave.

**Also:** the "compat. gen 1" badge left the monospace id line, where it
inherited the code font and duplicated the version chip; it now sits beside the
version picker at the same height as "Generation 1", with the explanation in
its tooltip. The secret access ("Who may use …") dialog's body had no padding
against the dialog border; it now has the `p-5` every other dialog body has.

**Dependency:** `source-map-js` 1.2.1 → 1.2.2 in `ui/manager/.ds-sync/package-lock.json`
(CVE-2026-93749, HIGH — `Trivy Filesystem Scan`). Edited by hand rather than via
`npm install`, which on Windows prunes the Tailwind wasm dependencies from the lock.

### Design decisions

- One draft of the whole document rather than per-section drafts: one Save, one
  version, one diff, and the same shape as the workflow editor.
- Back/forward are held by a `popstate` listener installed at module load
  (before the router's own) that undoes the step and replays it on Discard,
  using the `idx` React Router keeps in `history.state`.

**Files:** [`agent-detail.tsx`](../../ui/manager/src/pages/agent-detail.tsx),
[`agent-config-sections.tsx`](../../ui/manager/src/components/editors/agent-config-sections.tsx),
[`agent-draft.ts`](../../ui/manager/src/lib/agent-draft.ts),
[`unsaved-changes-registry.ts`](../../ui/manager/src/lib/unsaved-changes-registry.ts),
[`unsaved-changes-navigation-guard.tsx`](../../ui/manager/src/components/layout/unsaved-changes-navigation-guard.tsx),
[`use-unsaved-changes-guard.ts`](../../ui/manager/src/hooks/use-unsaved-changes-guard.ts),
[`edit-grant-dialog.tsx`](../../ui/manager/src/components/secrets/edit-grant-dialog.tsx),
[`compatibility-generation-badge.tsx`](../../ui/manager/src/components/agents/compatibility-generation-badge.tsx)

```decision-log
| 2026-10-06 | Agent detail edits a draft saved by an explicit Save; in-app navigation away from unsaved edits is held app-wide | Every inline edit created an agent version and could not be undone; leaving a page silently dropped edits | Keeping auto-save with an undo stack; migrating to the data router for `useBlocker` (larger change than the guard) |
```
