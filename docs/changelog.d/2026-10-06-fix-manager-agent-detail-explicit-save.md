## 🐛 fix(manager): agent detail saves on Save, not on every edit — with review, discard and Save & leave (2026-10-06)

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
- Merged with #967: Save shows its "Saved as vN — not live yet" toast with the
  Deploy action (once per Save instead of once per edit); removing a workflow
  no longer opens a confirmation, since it only changes the draft and Discard
  restores it; #967's rename-in-place, live-version header, workflow names and
  accessibility changes are kept.

**Leaving with unsaved edits.** The app holds in-app navigation with one router
blocker and one prompt (`NavigationGuardDialog`, #968); a page only calls
`useUnsavedChangesGuard`. This adds an optional `onSave` to that hook: when every
dirty page can save on demand, the prompt also offers **Save & leave** and lets the
navigation through only once the save landed. Agent detail and the workflow
editor pass it. (This branch first carried its own app-wide guard, written while
the app was still on `<BrowserRouter>`, then a per-page prompt after #939; both
were dropped as #939 and #968 landed.) While a placeholder document from the
previous version is on screen (#939's `configPending`), the draft takes no edits.

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
- Save & leave lives in the shared guard and prompt, not on the agent page, so
  any editor whose save resolves to success can opt in with one argument.

**Files:** [`agent-detail.tsx`](../../ui/manager/src/pages/agent-detail.tsx),
[`agent-config-sections.tsx`](../../ui/manager/src/components/editors/agent-config-sections.tsx),
[`agent-draft.ts`](../../ui/manager/src/lib/agent-draft.ts),
[`unsaved-changes-dialog.tsx`](../../ui/manager/src/components/ui/unsaved-changes-dialog.tsx),
[`use-unsaved-changes-guard.ts`](../../ui/manager/src/hooks/use-unsaved-changes-guard.ts),
[`navigation-guard-dialog.tsx`](../../ui/manager/src/components/shared/navigation-guard-dialog.tsx),
[`edit-grant-dialog.tsx`](../../ui/manager/src/components/secrets/edit-grant-dialog.tsx),
[`compatibility-generation-badge.tsx`](../../ui/manager/src/components/agents/compatibility-generation-badge.tsx)

```decision-log
| 2026-10-06 | Agent detail edits a draft saved by an explicit Save, with Save & leave on the app's one unsaved-changes prompt | Every inline edit created an agent version and could not be undone | Keeping auto-save with an undo stack; a second navigation guard beside the router's single blocker |
```
